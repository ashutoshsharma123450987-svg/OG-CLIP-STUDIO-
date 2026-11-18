package com.ogclip.studio.clips

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import com.ogclip.studio.buffer.ClipSnapshot
import com.ogclip.studio.buffer.EncodedSample
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Writes a [ClipSnapshot] to an MP4 in MediaStore (Movies/OG CLIP STUDIO) by copying the already-encoded
 * samples into the container. No decoding or encoding happens, so saving is fast and cheap on CPU.
 */
object ClipWriter {

    fun write(
        ctx: Context,
        snap: ClipSnapshot,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?,
        width: Int,
        height: Int
    ): ClipRecord {
        val resolver = ctx.contentResolver
        val now = System.currentTimeMillis()
        val name = "OGCLIP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OG CLIP STUDIO")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create a file in Movies/OG CLIP STUDIO")

        var size = 0L
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open the clip file")
            pfd.use {
                muxTo(MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), snap, videoFormat, audioFormat)
                size = it.statSize
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw if (e is IOException) e else IOException("Saving the clip failed: ${e.message}", e)
        }

        return ClipRecord(
            id = UUID.randomUUID().toString(),
            uri = uri.toString(),
            name = name,
            createdAtMs = now,
            durationMs = snap.durationUs / 1000L,
            sizeBytes = size,
            width = width,
            height = height
        )
    }

    /** Copies a finished MP4 handed over by OG STREAM STUDIO (content:// URI) into Movies/OG CLIP STUDIO. */
    fun importFrom(ctx: Context, src: android.net.Uri, durationMs: Long, width: Int, height: Int, prefix: String = "OGCLIP_"): ClipRecord {
        val resolver = ctx.contentResolver
        val now = System.currentTimeMillis()
        val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OG CLIP STUDIO")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create a file in Movies/OG CLIP STUDIO")
        var size = 0L
        try {
            val input = resolver.openInputStream(src) ?: throw IOException("Could not read the clip from OG STREAM STUDIO")
            input.use { i ->
                val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the clip file")
                output.use { o -> size = i.copyTo(o) }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw if (e is IOException) e else IOException("Importing the clip failed: ${e.message}", e)
        }
        return ClipRecord(UUID.randomUUID().toString(), uri.toString(), name, now, durationMs, size, width, height)
    }

    private fun muxTo(muxer: MediaMuxer, snap: ClipSnapshot, vf: MediaFormat, af: MediaFormat?) {
        var started = false
        try {
            val vTrack = muxer.addTrack(vf)
            val withAudio = af != null && snap.audio.isNotEmpty()
            val aTrack = if (withAudio) muxer.addTrack(af!!) else -1
            muxer.start()
            started = true

            val base = snap.startPtsUs
            val v = snap.video
            val a = snap.audio
            val info = MediaCodec.BufferInfo()
            var vi = 0
            var ai = 0
            // interleave by timestamp so players can stream the file without huge seeks
            while (vi < v.size || (withAudio && ai < a.size)) {
                val takeVideo = when {
                    vi >= v.size -> false
                    !withAudio || ai >= a.size -> true
                    else -> v[vi].ptsUs <= a[ai].ptsUs
                }
                if (takeVideo) {
                    put(muxer, vTrack, v[vi], base, info, v[vi].isKey)
                    vi++
                } else {
                    put(muxer, aTrack, a[ai], base, info, false)
                    ai++
                }
            }
        } finally {
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun put(m: MediaMuxer, track: Int, s: EncodedSample, base: Long, info: MediaCodec.BufferInfo, key: Boolean) {
        val flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        info.set(0, s.data.size, maxOf(0L, s.ptsUs - base), flags)
        m.writeSampleData(track, ByteBuffer.wrap(s.data), info)
    }
}
