package com.ogclip.studio.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.ogclip.studio.buffer.ClipSnapshot
import com.ogclip.studio.buffer.EncodedSample
import com.ogclip.studio.buffer.ReplayRing
import com.ogclip.studio.core.Prefs
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Settings the engine needs, copied from [Prefs] when the buffer starts. */
data class EngineConfig(
    val bufferSeconds: Int,
    val shortSide: Int,
    val fps: Int,
    val videoKbps: Int,
    val landscape: Boolean,
    val captureAudio: Boolean
)

/**
 * STANDALONE capture source (Phase 1): this app's own MediaProjection -> VirtualDisplay -> H.264 encoder
 * (surface input, no CPU copies) plus game-audio playback capture -> AAC encoder. Encoded frames go
 * straight into a [ReplayRing]. Nothing is re-encoded when a clip is saved.
 *
 * In integration mode (Phase 2) the stream app owns the encoders and this class is not used.
 */
class ReplayEngine(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val cfg: EngineConfig,
    private val onFatal: (String) -> Unit
) {
    val ring = ReplayRing(cfg.bufferSeconds, Prefs.MAX_BUFFER_BYTES)

    @Volatile var videoFormat: MediaFormat? = null
        private set
    @Volatile var audioFormat: MediaFormat? = null
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var fps = 0
        private set
    @Volatile var audioActive = false
        private set
    @Volatile var audioNote = ""
        private set

    @Volatile private var running = false
    @Volatile private var paused = false

    private var vEncoder: MediaCodec? = null
    private var vSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var vThread: Thread? = null

    private var aRecord: AudioRecord? = null
    private var aEncoder: MediaCodec? = null
    private var aThread: Thread? = null

    private var cbThread: HandlerThread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (running) fail("Screen capture was stopped by Android or by you.")
        }
    }

    // ------------------------------------------------------------------ start / stop

    /** Throws on failure. Must not be called on the main thread for long (it opens codecs). */
    fun start() {
        val size = chooseSize()
        width = size.first
        height = size.second
        fps = size.third

        cbThread = HandlerThread("og-clip-cb").also { it.start() }
        val handler = Handler(cbThread!!.looper)

        // Android 14: the callback must be registered before createVirtualDisplay().
        projection.registerCallback(projectionCallback, handler)

        val enc = createVideoEncoder(width, height, fps)
        vEncoder = enc
        val surface = enc.createInputSurface()
        vSurface = surface
        enc.start()

        display = projection.createVirtualDisplay(
            "OGClipCapture", width, height, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
        )

        running = true
        vThread = Thread({ videoDrainLoop(enc) }, "og-clip-video").also { it.start() }

        if (cfg.captureAudio) startAudio()
    }

    fun stop() {
        running = false
        try { vThread?.join(1500) } catch (_: InterruptedException) {}
        try { aThread?.join(1500) } catch (_: InterruptedException) {}
        try { display?.release() } catch (_: Exception) {}
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        closeAudio()
        try { vEncoder?.stop() } catch (_: Exception) {}
        try { vEncoder?.release() } catch (_: Exception) {}
        try { vSurface?.release() } catch (_: Exception) {}
        cbThread?.quitSafely()
        display = null; vEncoder = null; vSurface = null; vThread = null; aThread = null; cbThread = null
        ring.clear()
    }

    /** Pause = stop rendering into the encoder and forget the buffer (a gap would corrupt a clip). */
    fun pause() {
        if (paused) return
        paused = true
        ring.clear()
        try { display?.surface = null } catch (_: Exception) {}
    }

    fun resume() {
        if (!paused) return
        ring.clear()
        try { display?.surface = vSurface } catch (_: Exception) {}
        requestKeyFrame()
        paused = false
    }

    fun isPaused() = paused

    /** Cuts the last [seconds] seconds out of the buffer. Cheap; the caller writes the file off-thread. */
    fun snapshot(seconds: Int): ClipSnapshot? = ring.snapshot(seconds * 1_000_000L)

    private fun fail(msg: String) {
        if (!running) return
        running = false
        onFatal(msg)
    }

    // ------------------------------------------------------------------ video

    /** Picks the capture size: device aspect ratio if the encoder supports it, else plain 16:9. */
    private fun chooseSize(): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenW: Int
        val screenH: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
        val longPx = max(screenW, screenH).toFloat()
        val shortPx = min(screenW, screenH).toFloat()
        val aspect = longPx / shortPx

        val s = cfg.shortSide
        val nativeLong = ((s * aspect).toInt() / 16) * 16
        val wideLong = ((s * 16f / 9f).toInt() / 16) * 16
        val candidates = listOf(nativeLong, wideLong, ((s * 0.667f * 16f / 9f).toInt() / 16) * 16)
        val shorts = listOf(s, s, (s * 0.667f).toInt() / 2 * 2)

        var wantFps = cfg.fps
        for (i in candidates.indices) {
            val l = candidates[i]
            val sh = shorts[i]
            val w = if (cfg.landscape) l else sh
            val h = if (cfg.landscape) sh else l
            val f = supportedFps(w, h, wantFps)
            if (f > 0) return Triple(w, h, f)
        }
        // last resort: let createEncoder report the real error
        val l = wideLong
        return if (cfg.landscape) Triple(l, s, 30) else Triple(s, l, 30)
    }

    /** Returns the fps (wanted, else 30) the AVC encoder supports at this size, or 0 if unsupported. */
    private fun supportedFps(w: Int, h: Int, wanted: Int): Int {
        try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            var found = false
            for (info in infos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) continue
                val vc = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                if (!vc.isSizeSupported(w, h)) continue
                found = true
                if (vc.areSizeAndRateSupported(w, h, wanted.toDouble())) return wanted
                if (vc.areSizeAndRateSupported(w, h, 30.0)) return 30
            }
            return if (found) 30 else 0
        } catch (_: Exception) {
            return wanted // cannot query; let the encoder decide
        }
    }

    private fun createVideoEncoder(w: Int, h: Int, fps: Int): MediaCodec {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            var c: MediaCodec? = null
            try {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoKbps * 1000)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // keeps the GOP structure alive when the screen is static (a clip must start on a key frame)
                f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
                if (attempt == 0) {
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } else if (attempt == 1) {
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return c
            } catch (e: Exception) {
                last = e
                try { c?.release() } catch (_: Exception) {}
            }
        }
        throw IllegalStateException("Video encoder could not start at ${w}x$h: ${last?.message}")
    }

    private fun requestKeyFrame() {
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            vEncoder?.setParameters(b)
        } catch (_: Exception) {}
    }

    private fun videoDrainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 20_000)
            } catch (e: Exception) {
                fail("Video encoder error: ${e.message}")
                return
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoFormat = enc.outputFormat
                continue
            }
            if (idx < 0) continue
            try {
                val out = enc.getOutputBuffer(idx)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                // SPS/PPS travel inside videoFormat (csd-0/csd-1), so config buffers are not stored as frames.
                if (out != null && info.size > 0 && !isConfig && !paused) {
                    val data = ByteArray(info.size)
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(data)
                    val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    ring.addVideo(EncodedSample(data, info.presentationTimeUs, key))
                }
            } finally {
                try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------------ audio (game sound only)

    private fun startAudio() {
        try {
            var rec = openPlaybackCapture(true)
            if (rec == null) rec = openPlaybackCapture(false)
            if (rec == null) {
                audioNote = "Game audio capture is not available (permission denied or the game blocks it). Clips will have no sound."
                return
            }
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, Prefs.AUDIO_KBPS * 1000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            aRecord = rec
            aEncoder = enc
            audioActive = true
            rec.startRecording()
            aThread = Thread({ audioLoop(rec, enc) }, "og-clip-audio").also { it.start() }
        } catch (e: Exception) {
            audioNote = "Audio could not start: ${e.message}. Clips will have no sound."
            closeAudio()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openPlaybackCapture(withVoice: Boolean): AudioRecord? {
        return try {
            val pb = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            if (withVoice) pb.addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val buf = max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2,
                32768
            )
            val r = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(pb.build())
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(buf)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                null
            } else r
        } catch (_: Throwable) {
            null
        }
    }

    private fun closeAudio() {
        audioActive = false
        try { aRecord?.stop() } catch (_: Exception) {}
        try { aRecord?.release() } catch (_: Exception) {}
        try { aEncoder?.stop() } catch (_: Exception) {}
        try { aEncoder?.release() } catch (_: Exception) {}
        aRecord = null
        aEncoder = null
    }

    /**
     * One thread: read PCM (blocks), feed the AAC encoder, drain its output. Timestamps come from a sample counter
     * anchored to the monotonic clock (the same clock the video encoder uses) and are re-anchored if they drift
     * by more than 50 ms. Verified only by design so far: **audio/video sync must be checked on the device**.
     */
    private fun audioLoop(rec: AudioRecord, enc: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ShortArray(CHUNK_SAMPLES)
        val info = MediaCodec.BufferInfo()
        var anchorUs = 0L
        var framesTotal = 0L
        var anchored = false
        while (running) {
            val n = try { rec.read(pcm, 0, pcm.size) } catch (_: Exception) { -1 }
            if (n <= 0) {
                if (n < 0) { try { Thread.sleep(50) } catch (_: InterruptedException) { return } }
                drainAudio(enc, info)
                continue
            }
            val nowUs = System.nanoTime() / 1000L
            val chunkFrames = n / 2
            val chunkUs = chunkFrames * 1_000_000L / SAMPLE_RATE
            if (!anchored) {
                anchorUs = nowUs - chunkUs
                anchored = true
            }
            var ptsUs = anchorUs + framesTotal * 1_000_000L / SAMPLE_RATE
            val expected = nowUs - chunkUs
            if (abs(expected - ptsUs) > 50_000L) {
                anchorUs += expected - ptsUs
                ptsUs = expected
            }
            framesTotal += chunkFrames

            if (!paused) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val ib = enc.getInputBuffer(i)
                    if (ib != null) {
                        ib.clear()
                        ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
                        enc.queueInputBuffer(i, 0, n * 2, ptsUs, 0)
                    }
                }
            }
            drainAudio(enc, info)
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = try { enc.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = enc.outputFormat
                continue
            }
            if (idx < 0) return
            try {
                val out = enc.getOutputBuffer(idx)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (out != null && info.size > 0 && !isConfig && !paused) {
                    val data = ByteArray(info.size)
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(data)
                    ring.addAudio(EncodedSample(data, info.presentationTimeUs, false))
                }
            } finally {
                try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val CHUNK_SAMPLES = 2048 // 1024 stereo frames = one AAC frame
    }
}
