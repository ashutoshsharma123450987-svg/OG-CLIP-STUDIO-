package com.ogclip.studio.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ogclip.studio.R
import com.ogclip.studio.core.Clipper
import com.ogclip.studio.core.Prefs
import com.ogclip.studio.core.ReplayState
import com.ogclip.studio.service.ReplayService
import com.ogclip.studio.ui.LibraryActivity
import com.ogclip.studio.ui.MainActivity
import com.ogclip.studio.ui.Ui
import kotlin.math.abs

/**
 * Floating clip button. Tap = CLIP NOW (one tap while gaming). Long-press = small panel (Pause/Resume, Stop,
 * Library, Open app, Hide). Drag to move; position is remembered. NOTE: Android draws overlays into screen capture,
 * so while OG STREAM STUDIO streams, viewers can see this bubble (same as the stream app's own bubble).
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ch = NotificationChannel(CHANNEL, "Floating clip button", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            hideAll()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!Settings.canDrawOverlays(this)) {
            Prefs(this).overlayEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, OverlayService::class.java).setAction(ACTION_HIDE)
        val stopPi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("OG CLIP STUDIO")
            .setContentText("Floating clip button is on")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hide button", stopPi).build())
            .build()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val prefs = Prefs(this)
        val size = Ui.dp(this, 56)
        val b = TextView(this)
        b.text = "CLIP"
        b.setTextColor(Ui.WHITE)
        b.textSize = 12f
        b.gravity = Gravity.CENTER
        b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
        b.background = Ui.bg(this, Ui.RED, 28, Ui.GOLD, 2)
        b.alpha = 0.75f

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.overlayX
        lp.y = prefs.overlayY

        val slop = Ui.dp(this, 8)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                togglePanel()
            }
        }
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    downAt = e.eventTime
                    moved = false
                    longFired = false
                    main.postDelayed(longPress, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        prefs.overlayX = lp.x
                        prefs.overlayY = lp.y
                    } else if (!longFired) {
                        closePanel()
                        flash()
                        Clipper.clipNow(this)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
        bubble = b
        bubbleLp = lp
        wm.addView(b, lp)
    }

    private fun flash() {
        val b = bubble ?: return
        b.background = Ui.bg(this, Ui.GOLD, 28, Ui.WHITE, 2)
        b.setTextColor(Ui.BLACK)
        main.postDelayed({
            b.background = Ui.bg(this, Ui.RED, 28, Ui.GOLD, 2)
            b.setTextColor(Ui.WHITE)
        }, 700)
    }

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun openPanel() {
        val lp0 = bubbleLp ?: return
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8))
        col.background = Ui.bg(this, Ui.BLACK, 14, Ui.GOLD, 1)

        fun item(label: String, fill: Int = Ui.CARD, color: Int = Ui.WHITE, action: () -> Unit) {
            val t = Ui.button(this, label, fill, color) {
                closePanel()
                action()
            }
            t.textSize = 13f
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9))
            col.addView(t, Ui.lp(this, topDp = 4))
        }

        item("CLIP NOW", Ui.RED) { Clipper.clipNow(this) }
        if (Prefs(this).mode == Prefs.MODE_STANDALONE) {
            if (ReplayState.status == ReplayState.Status.PAUSED) {
                item("Resume") { ReplayService.command(this, ReplayService.ACTION_RESUME) }
            } else {
                item("Pause") { ReplayService.command(this, ReplayService.ACTION_PAUSE) }
            }
            item("Stop buffer") { ReplayService.command(this, ReplayService.ACTION_STOP) }
        }
        item("Clip Library") { openActivity(LibraryActivity::class.java) }
        item("Open app (start buffer)") { openActivity(MainActivity::class.java) }
        item("Hide button", Ui.BLACK, Ui.MUTED) { hideAll() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = lp0.x + Ui.dp(this, 62)
        lp.y = lp0.y
        panel = col
        wm.addView(col, lp)
        // close by itself so it never stays over the game
        main.postDelayed({ if (panel === col) closePanel() }, 6000)
    }

    private fun openActivity(c: Class<*>) {
        startActivity(Intent(this, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun hideAll() {
        Prefs(this).overlayEnabled = false
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "og_clip_overlay"
        private const val NOTIF_ID = 92
        const val ACTION_HIDE = "com.ogclip.studio.OVERLAY_HIDE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
