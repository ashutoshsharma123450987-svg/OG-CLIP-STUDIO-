package com.ogclip.studio.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.ogclip.studio.clips.ClipRecord
import com.ogclip.studio.clips.ClipStore
import com.ogclip.studio.clips.ClipWriter
import java.util.concurrent.Executors

/**
 * Link to the replay buffer inside OG STREAM STUDIO (bound Messenger service protected by a signature permission).
 * All callbacks run on the main thread. Each call binds, sends one message, gets one reply and unbinds.
 */
object ClipClient {
    const val PKG = "com.ogstudio.streamer"
    private const val SERVICE = "com.ogstudio.streamer.engine.ClipControlService"
    private const val MSG_STATUS = 1
    private const val MSG_CLIP = 2
    private const val MSG_SET_WINDOW = 3

    data class Status(
        val enabled: Boolean,
        val live: Boolean,
        val buffering: Boolean,
        val bufferedMs: Long,
        val bufferedBytes: Long,
        val windowSeconds: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val kbps: Int,
        val audio: Boolean
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Sends one request and delivers (replyBundle, errorText). */
    private fun call(ctx: Context, what: Int, arg1: Int, timeoutMs: Long, cb: (Bundle?, String?) -> Unit) {
        val app = ctx.applicationContext
        var done = false
        var conn: ServiceConnection? = null

        fun finish(b: Bundle?, err: String?) {
            if (done) return
            done = true
            try { conn?.let { app.unbindService(it) } } catch (_: Exception) {}
            cb(b, err)
        }

        val replyHandler = Handler(Looper.getMainLooper()) { msg ->
            val d = msg.data
            finish(d, d?.getString("error"))
            true
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, what, arg1, 0)
                    m.replyTo = Messenger(replyHandler)
                    Messenger(binder!!).send(m)
                } catch (e: Exception) {
                    finish(null, "Link error: ${e.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(null, "OG STREAM STUDIO disconnected.")
            }
        }
        val intent = Intent().setComponent(ComponentName(PKG, SERVICE))
        val ok = try {
            app.bindService(intent, conn!!, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            finish(null, "No permission to talk to OG STREAM STUDIO. Both apps must be signed with the same key (see README).")
            return
        }
        if (!ok) {
            finish(null, "Cannot connect to OG STREAM STUDIO. Install the patched version (1.6+), open it once, then try again.")
            return
        }
        main.postDelayed({ finish(null, "OG STREAM STUDIO did not answer.") }, timeoutMs)
    }

    fun status(ctx: Context, cb: (Status?, String?) -> Unit) {
        if (!isInstalled(ctx)) {
            cb(null, "OG STREAM STUDIO is not installed.")
            return
        }
        call(ctx, MSG_STATUS, 0, 4000) { b, err ->
            if (b == null || err != null) cb(null, err ?: "No answer.")
            else cb(parse(b), null)
        }
    }

    /** Tells the stream app how long the replay window should be (15-180 s). */
    fun setWindow(ctx: Context, seconds: Int) {
        if (!isInstalled(ctx)) return
        call(ctx, MSG_SET_WINDOW, seconds, 4000) { _, _ -> }
    }

    /** Asks the stream app to cut the last [seconds], copies the MP4 into our library, then deletes the temp file. */
    fun clip(ctx: Context, seconds: Int, cb: (ClipRecord?, String?) -> Unit) {
        val app = ctx.applicationContext
        if (!isInstalled(app)) {
            cb(null, "OG STREAM STUDIO is not installed.")
            return
        }
        call(app, MSG_CLIP, seconds, 20000) { b, err ->
            val uriStr = b?.getString("uri")
            if (b == null || err != null || uriStr == null) {
                cb(null, err ?: "No clip returned.")
            } else {
                val dur = b.getLong("durationMs")
                val w = b.getInt("width")
                val h = b.getInt("height")
                io.execute {
                    val src = Uri.parse(uriStr)
                    try {
                        val rec = ClipWriter.importFrom(app, src, dur, w, h)
                        ClipStore.add(app, rec)
                        main.post { cb(rec, null) }
                    } catch (e: Exception) {
                        main.post { cb(null, e.message ?: "Import failed.") }
                    } finally {
                        try { app.contentResolver.delete(src, null, null) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun parse(b: Bundle) = Status(
        enabled = b.getBoolean("enabled"),
        live = b.getBoolean("live"),
        buffering = b.getBoolean("buffering"),
        bufferedMs = b.getLong("bufferedMs"),
        bufferedBytes = b.getLong("bufferedBytes"),
        windowSeconds = b.getInt("windowSeconds"),
        width = b.getInt("width"),
        height = b.getInt("height"),
        fps = b.getInt("fps"),
        kbps = b.getInt("kbps"),
        audio = b.getBoolean("audio")
    )
}
