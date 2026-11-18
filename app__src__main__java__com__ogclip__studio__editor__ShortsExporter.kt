package com.ogclip.studio.editor

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File

/**
 * Re-encodes a clip with trim, optional 9:16 crop, text and logo using Media3 Transformer (hardware H.264 + AAC).
 * Must be created and used on the main thread (Transformer needs a Looper). Callbacks run on the main thread.
 *
 * Effect order matters: Crop -> Presentation (resize) -> Overlays, so overlay pixel sizes refer to the output frame.
 */
class ShortsExporter(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var polling = false

    fun start(spec: EditSpec, out: File, onProgress: (Int) -> Unit, onDone: (error: String?) -> Unit) {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(spec.startMs)
            .setEndPositionMs(spec.endMs)
            .build()
        val item = MediaItem.Builder()
            .setUri(spec.src)
            .setClippingConfiguration(clip)
            .build()

        val video = ArrayList<Effect>()
        if (spec.vertical && spec.cropFraction() < 1f) {
            val e = spec.cropNdc()
            video.add(Crop(e[0], e[1], -1f, 1f)) // left, right, bottom, top
        }
        if (spec.quality != 0 || spec.vertical) {
            video.add(Presentation.createForHeight(spec.outH()))
        }
        val overlays = ImmutableList.Builder<TextureOverlay>()
        var haveOverlay = false
        if (spec.text.isNotBlank()) {
            overlays.add(textOverlay(spec))
            haveOverlay = true
        }
        if (spec.logo != null) {
            overlays.add(logoOverlay(spec))
            haveOverlay = true
        }
        if (haveOverlay) video.add(OverlayEffect(overlays.build()))

        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(emptyList<AudioProcessor>(), video))
            .build()

        val builder = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
        if (spec.quality != 0) {
            val bitrate = if (spec.quality >= 1080) 8_000_000 else 4_000_000
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .build()
            )
        }
        val t = builder.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                polling = false
                onDone(null)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                polling = false
                onDone(exportException.message ?: "Export failed (code ${exportException.errorCode})")
            }
        }).build()
        transformer = t
        if (out.exists()) out.delete()
        t.start(edited, out.absolutePath)

        polling = true
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (!polling) return
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 300)
            }
        }
        main.postDelayed(poll, 300)
    }

    fun cancel() {
        polling = false
        try { transformer?.cancel() } catch (_: Exception) {}
        transformer = null
    }

    private fun textOverlay(spec: EditSpec): TextureOverlay {
        val px = (spec.outH() * 0.045f).toInt().coerceAtLeast(18)
        val sp = SpannableString(spec.text)
        val end = sp.length
        sp.setSpan(AbsoluteSizeSpan(px), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(if (spec.textGold) Color.parseColor("#FF8A00") else Color.WHITE), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val y = when (spec.textPos) {
            0 -> 0.8f
            1 -> 0f
            else -> -0.8f
        }
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, y)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
        return TextOverlay.createStaticTextOverlay(sp, settings)
    }

    private fun logoOverlay(spec: EditSpec): TextureOverlay {
        val right = spec.logoCorner == 1 || spec.logoCorner == 3
        val top = spec.logoCorner == 0 || spec.logoCorner == 1
        val x = if (right) 1f else -1f
        val y = if (top) 1f else -1f
        val settings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(x, y)
            .setBackgroundFrameAnchor(x * 0.95f, y * 0.96f)
            .build()
        return BitmapOverlay.createStaticBitmapOverlay(spec.logo!!, settings)
    }
}
