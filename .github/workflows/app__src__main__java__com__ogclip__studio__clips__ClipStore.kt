package com.ogclip.studio.clips

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small JSON index of the clips this app created. Files live in MediaStore (Movies/OG CLIP STUDIO);
 * the index only adds metadata MediaStore does not have (duration at save time, upload status later).
 * Entries whose file was deleted outside the app are dropped by [list].
 */
object ClipStore {
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "clips.json")

    fun add(ctx: Context, r: ClipRecord) = synchronized(lock) {
        val all = readAll(ctx)
        all.add(0, r)
        writeAll(ctx, all)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val all = readAll(ctx)
        all.removeAll { it.id == id }
        writeAll(ctx, all)
    }

    /** Newest first; silently removes records whose media file no longer exists. */
    fun list(ctx: Context): List<ClipRecord> = synchronized(lock) {
        val all = readAll(ctx)
        val alive = all.filter { exists(ctx, it.uri) }
        if (alive.size != all.size) writeAll(ctx, alive.toMutableList())
        alive
    }

    private fun exists(ctx: Context, uri: String): Boolean {
        return try {
            ctx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun readAll(ctx: Context): MutableList<ClipRecord> {
        val f = file(ctx)
        val out = ArrayList<ClipRecord>()
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ClipRecord(
                        id = o.getString("id"),
                        uri = o.getString("uri"),
                        name = o.getString("name"),
                        createdAtMs = o.getLong("created"),
                        durationMs = o.getLong("duration"),
                        sizeBytes = o.getLong("size"),
                        width = o.optInt("w"),
                        height = o.optInt("h"),
                        uploadStatus = o.optString("upload", "LOCAL")
                    )
                )
            }
        } catch (_: Exception) {
            // corrupt index: start fresh rather than crash; media files are untouched
        }
        return out
    }

    private fun writeAll(ctx: Context, list: List<ClipRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("id", r.id).put("uri", r.uri).put("name", r.name)
                    .put("created", r.createdAtMs).put("duration", r.durationMs)
                    .put("size", r.sizeBytes).put("w", r.width).put("h", r.height)
                    .put("upload", r.uploadStatus)
            )
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, "clips.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(arr.toString())
            tmp.delete()
        }
    }
}
