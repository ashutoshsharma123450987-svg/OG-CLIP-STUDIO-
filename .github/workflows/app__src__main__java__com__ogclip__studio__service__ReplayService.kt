package com.ogclip.studio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.ogclip.studio.R
import com.ogclip.studio.clips.ClipStore
import com.ogclip.studio.clips.ClipWriter
import com.ogclip.studio.core.Fmt
import com.ogclip.studio.core.Prefs
import com.ogclip.studio.core.ReplayState
import com.ogclip.studio.engine.EngineConfig
import com.ogclip.studio.engine.ReplayEngine
import com.ogclip.studio.ui.MainActivity
import java.util.concurrent.Executors

/**
 * Foreground service that owns the MediaProjection and the [ReplayEngine] while the buffer runs.
 * Triggers available in Phase 1: dashboard button and the notification's CLIP NOW action.
 */
class ReplayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var engine: ReplayEngine? = null
    private var clipSeconds = 60
    private var lastClipAt = 0L
    @Volatile private var tickRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Replay buffer", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopBuffer("Buffer stopped.")
            ACTION_CLIP -> saveClip()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            else -> if (engine == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun handleStart(intent: Intent) {
        // startForegroundService() obliges us to call startForeground() quickly, even if we then ignore the request.
        // Android 14: the mediaProjection foreground service must be running BEFORE getMediaProjection().
        val n = buildNotification("Starting...")
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (engine != null || ReplayState.status == ReplayState.Status.STARTING) return
        ReplayState.reset()
        ReplayState.status = ReplayState.Status.STARTING
        ReplayState.message = ""

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val prefs = Prefs(this)
        clipSeconds = prefs.bufferSeconds
        val cfg = EngineConfig(
            bufferSeconds = prefs.bufferSeconds,
            shortSide = prefs.shortSide,
            fps = prefs.fps,
            videoKbps = prefs.videoKbps,
            landscape = prefs.landscape,
            captureAudio = prefs.captureAudio
        )

        worker.execute {
            var eng: ReplayEngine? = null
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpm.getMediaProjection(code, data ?: throw IllegalStateException("No screen-capture permission data"))
                    ?: throw IllegalStateException("Screen-capture permission was not granted")
                projection = mp
                val e2 = ReplayEngine(this, mp, cfg) { msg -> worker.execute { stopBuffer(msg) } }
                eng = e2
                e2.start()
                engine = e2
                ReplayState.windowSeconds = cfg.bufferSeconds
                ReplayState.width = e2.width
                ReplayState.height = e2.height
                ReplayState.fps = e2.fps
                ReplayState.kbps = cfg.videoKbps
                ReplayState.audioOn = e2.audioActive
                ReplayState.audioNote = e2.audioNote
                ReplayState.startedAtMs = SystemClock.elapsedRealtime()
                ReplayState.status = ReplayState.Status.RUNNING
                startTicker()
            } catch (e: Exception) {
                try { eng?.stop() } catch (_: Exception) {} // release a half-started engine
                stopBuffer("Could not start: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ clip

    private fun saveClip() {
        val eng = engine
        val now = SystemClock.elapsedRealtime()
        if (eng == null || ReplayState.status != ReplayState.Status.RUNNING) {
            toast("Buffer is not running.")
            return
        }
        if (now - lastClipAt < 1500) return // debounce double taps
        lastClipAt = now

        val snap = eng.snapshot(clipSeconds)
        val vf = eng.videoFormat
        if (snap == null || vf == null) {
            toast("Nothing buffered yet, try again in a few seconds.")
            return
        }
        val af = eng.audioFormat
        val w = eng.width
        val h = eng.height
        toast("Saving clip...")
        saver.execute {
            try {
                val rec = ClipWriter.write(this, snap, vf, af, w, h)
                ClipStore.add(this, rec)
                ReplayState.clipsThisSession++
                ReplayState.message = "Saved ${Fmt.duration(rec.durationMs)} clip (${Fmt.size(rec.sizeBytes)})"
                toast("Clip saved: ${Fmt.duration(rec.durationMs)}")
            } catch (e: Exception) {
                ReplayState.message = "Clip failed: ${e.message}"
                toast("Clip failed: ${e.message}")
            }
        }
    }

    private fun setPaused(p: Boolean) {
        val eng = engine ?: return
        if (p && ReplayState.status == ReplayState.Status.RUNNING) {
            eng.pause()
            ReplayState.status = ReplayState.Status.PAUSED
        } else if (!p && ReplayState.status == ReplayState.Status.PAUSED) {
            eng.resume()
            ReplayState.status = ReplayState.Status.RUNNING
        }
        updateNotification()
    }

    // ------------------------------------------------------------------ status ticker

    private fun startTicker() {
        if (tickRunning) return
        tickRunning = true
        Thread({
            while (tickRunning) {
                val eng = engine
                if (eng != null) {
                    ReplayState.bufferedMs = eng.ring.bufferedUs() / 1000L
                    ReplayState.bufferedBytes = eng.ring.bufferedBytes()
                    updateNotification()
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }, "og-clip-tick").start()
    }

    // ------------------------------------------------------------------ stop

    @Synchronized
    private fun stopBuffer(message: String) {
        tickRunning = false
        val eng = engine
        engine = null
        try { eng?.stop() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        ReplayState.reset()
        ReplayState.message = message
        main.post {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        }
    }

    override fun onDestroy() {
        tickRunning = false
        if (engine != null) {
            try { engine?.stop() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            engine = null
            ReplayState.reset()
        }
        worker.shutdown()
        saver.shutdown() // lets an in-progress clip finish
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = ReplayState.status == ReplayState.Status.PAUSED
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("OG CLIP STUDIO")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "CLIP NOW", actionPi(ACTION_CLIP, 1)).build())
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", actionPi(if (paused) ACTION_RESUME else ACTION_PAUSE, 2)).build())
            .addAction(Notification.Action.Builder(null, "Stop", actionPi(ACTION_STOP, 3)).build())
        return b.build()
    }

    private fun actionPi(action: String, code: Int): PendingIntent {
        val i = Intent(this, ReplayService::class.java).setAction(action)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun updateNotification() {
        if (engine == null) return
        val text = when (ReplayState.status) {
            ReplayState.Status.PAUSED -> "Paused"
            ReplayState.Status.RUNNING -> "Buffering ${Fmt.duration(ReplayState.bufferedMs)} / ${Fmt.duration(clipSeconds * 1000L)}"
            else -> "Starting..."
        }
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val ACTION_START = "com.ogclip.studio.START"
        const val ACTION_STOP = "com.ogclip.studio.STOP"
        const val ACTION_CLIP = "com.ogclip.studio.CLIP"
        const val ACTION_PAUSE = "com.ogclip.studio.PAUSE"
        const val ACTION_RESUME = "com.ogclip.studio.RESUME"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "og_clip_buffer"
        private const val NOTIF_ID = 91

        /** Plain command for a running service (clip / pause / resume / stop). */
        fun command(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, ReplayService::class.java).setAction(action))
        }

        /** Starts the buffer with the result of the system screen-capture consent dialog. */
        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ReplayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }
    }
}
