package com.ogclip.studio.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import com.ogclip.studio.core.Fmt
import com.ogclip.studio.core.Prefs
import com.ogclip.studio.core.Clipper
import com.ogclip.studio.core.ReplayState
import com.ogclip.studio.integration.ClipClient
import com.ogclip.studio.service.ReplayService

/** Dashboard: start/stop the buffer, CLIP NOW, live status. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var bufferText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var detailText: TextView
    private lateinit var audioText: TextView
    private lateinit var messageText: TextView
    private lateinit var startBtn: TextView
    private lateinit var clipBtn: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var modeNote: TextView
    private var streamStatus: ClipClient.Status? = null
    private var streamError: String? = null
    private var polling = false
    private var tick = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (Prefs(this@MainActivity).mode == Prefs.MODE_STREAM && tick % 3 == 0) pollStream()
            tick++
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs(this).onboarded) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        build()
    }

    private fun build() {
        val c = Ui.scrollPage(this, "OG CLIP STUDIO", false)
        modeNote = Ui.text(this, "", 13f, Ui.MUTED)
        c.addView(modeNote)

        val card = Ui.card(this)
        statusText = Ui.text(this, "BUFFER OFF", 22f, Ui.MUTED, true)
        card.addView(statusText)
        bufferText = Ui.text(this, "0:00 / 1:00", 15f, Ui.WHITE)
        card.addView(bufferText, Ui.lp(this, topDp = 10))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(Ui.GOLD)
        progress.progressBackgroundTintList = ColorStateList.valueOf(Ui.BLACK)
        card.addView(progress, Ui.lp(this, h = Ui.dp(this, 10), topDp = 6))
        detailText = Ui.text(this, "", 13f, Ui.MUTED)
        card.addView(detailText, Ui.lp(this, topDp = 10))
        audioText = Ui.text(this, "", 13f, Ui.MUTED)
        card.addView(audioText, Ui.lp(this, topDp = 4))
        messageText = Ui.text(this, "", 13f, Ui.GOLD)
        card.addView(messageText, Ui.lp(this, topDp = 8))
        c.addView(card, Ui.lp(this, topDp = 14))

        clipBtn = Ui.button(this, "CLIP NOW", Ui.RED, Ui.WHITE) { Clipper.clipNow(this) }
        clipBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        clipBtn.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 26))
        c.addView(clipBtn, Ui.lp(this, topDp = 16))

        startBtn = Ui.button(this, "START BUFFER", Ui.GOLD, Ui.BLACK) { onStartStop() }
        c.addView(startBtn, Ui.lp(this, topDp = 12))

        pauseBtn = Ui.button(this, "PAUSE", Ui.BLACK, Ui.WHITE, Ui.GOLD) {
            val paused = ReplayState.status == ReplayState.Status.PAUSED
            ReplayService.command(this, if (paused) ReplayService.ACTION_RESUME else ReplayService.ACTION_PAUSE)
        }
        c.addView(pauseBtn, Ui.lp(this, topDp = 12))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lib = Ui.button(this, "Clip Library", Ui.CARD, Ui.WHITE) { startActivity(Intent(this, LibraryActivity::class.java)) }
        val set = Ui.button(this, "Buffer Settings", Ui.CARD, Ui.WHITE) { startActivity(Intent(this, SettingsActivity::class.java)) }
        val p1 = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        p1.marginEnd = Ui.dp(this, 6)
        val p2 = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        row.addView(lib, p1)
        row.addView(set, p2)
        c.addView(row, Ui.lp(this, topDp = 12))
        c.addView(Ui.button(this, "Controls: source, floating button, shortcut", Ui.CARD, Ui.GOLD) {
            startActivity(Intent(this, ControlsActivity::class.java))
        }, Ui.lp(this, topDp = 12))

        val about = Ui.text(this, "About & known limits", 14f, Ui.MUTED)
        about.gravity = android.view.Gravity.CENTER
        about.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8))
        about.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        c.addView(about, Ui.lp(this))
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) ticker.run()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun pollStream() {
        if (polling) return
        polling = true
        ClipClient.status(this) { st, err ->
            polling = false
            streamStatus = st
            streamError = err
            // keep the stream app's replay window equal to our clip length
            val want = Prefs(this).bufferSeconds
            if (st != null && st.enabled && st.windowSeconds != want) ClipClient.setWindow(this, want)
        }
    }

    private fun refreshStream() {
        val prefs = Prefs(this)
        val st = streamStatus
        val err = streamError
        modeNote.text = "Replay buffer \u00B7 OG STREAM STUDIO link"
        val windowS = prefs.bufferSeconds
        when {
            st == null -> {
                statusText.text = if (err == null) "CHECKING\u2026" else "NO LINK"
                statusText.setTextColor(if (err == null) Ui.MUTED else Ui.RED)
                detailText.text = err ?: ""
                bufferText.text = "Buffered 0:00 of ${Fmt.duration(windowS * 1000L)}"
                progress.progress = 0
            }
            !st.enabled -> {
                statusText.text = "BUFFER OFF IN STREAM APP"
                statusText.setTextColor(Ui.GOLD)
                detailText.text = "Open OG STREAM STUDIO > Settings > turn on \"Replay buffer\", save, then go live."
                bufferText.text = "Buffered 0:00 of ${Fmt.duration(windowS * 1000L)}"
                progress.progress = 0
            }
            !st.buffering -> {
                statusText.text = "WAITING FOR LIVE"
                statusText.setTextColor(Ui.GOLD)
                detailText.text = "Replay buffer is on. It starts filling when you press GO LIVE in OG STREAM STUDIO."
                bufferText.text = "Buffered 0:00 of ${Fmt.duration(windowS * 1000L)}"
                progress.progress = 0
            }
            else -> {
                statusText.text = "\u25CF BUFFERING (STREAM)"
                statusText.setTextColor(Ui.RED)
                val w = if (st.windowSeconds > 0) st.windowSeconds else windowS
                bufferText.text = "Buffered ${Fmt.duration(st.bufferedMs)} of ${Fmt.duration(w * 1000L)}   \u00B7   ${Fmt.size(st.bufferedBytes)} RAM"
                progress.progress = (st.bufferedMs * 100 / (w * 1000L)).toInt().coerceIn(0, 100)
                detailText.text = "${st.width}\u00D7${st.height} \u00B7 ${st.fps} fps \u00B7 ${st.kbps} kbps (stream quality)"
            }
        }
        audioText.text = if (st != null && st.buffering) (if (st.audio) "Audio: stream mix (game + mic + teammates)" else "Audio: none") else ""
        messageText.text = ReplayState.message
        messageText.visibility = if (ReplayState.message.isEmpty()) View.GONE else View.VISIBLE
        startBtn.visibility = View.GONE
        pauseBtn.visibility = View.GONE
        Ui.setEnabled(clipBtn, st != null && st.buffering)
    }

    private fun refresh() {
        if (Prefs(this).mode == Prefs.MODE_STREAM) {
            refreshStream()
            return
        }
        startBtn.visibility = View.VISIBLE
        pauseBtn.visibility = View.VISIBLE
        modeNote.text = "Replay buffer \u00B7 standalone capture"
        val st = ReplayState.status
        val prefs = Prefs(this)
        statusText.text = when (st) {
            ReplayState.Status.STOPPED -> "BUFFER OFF"
            ReplayState.Status.STARTING -> "STARTING…"
            ReplayState.Status.RUNNING -> "● BUFFERING"
            ReplayState.Status.PAUSED -> "PAUSED"
        }
        statusText.setTextColor(
            when (st) {
                ReplayState.Status.RUNNING -> Ui.RED
                ReplayState.Status.PAUSED -> Ui.GOLD
                else -> Ui.MUTED
            }
        )
        val windowS = if (ReplayState.isActive() && ReplayState.windowSeconds > 0) ReplayState.windowSeconds else prefs.bufferSeconds
        bufferText.text = "Buffered ${Fmt.duration(ReplayState.bufferedMs)} of ${Fmt.duration(windowS * 1000L)}" +
                if (ReplayState.isActive()) "   ·   ${Fmt.size(ReplayState.bufferedBytes)} RAM" else ""
        progress.progress = (ReplayState.bufferedMs * 100 / (windowS * 1000L)).toInt().coerceIn(0, 100)

        detailText.text = if (ReplayState.isActive() && ReplayState.width > 0)
            "${ReplayState.width}×${ReplayState.height} · ${ReplayState.fps} fps · ${ReplayState.kbps} kbps"
        else "Clip length: ${windowS}s · ${prefs.shortSide}p · ${prefs.fps} fps · ${prefs.videoKbps} kbps"

        audioText.text = when {
            !ReplayState.isActive() -> if (prefs.captureAudio) "Game audio: on" else "Game audio: off"
            ReplayState.audioOn -> "Game audio: capturing"
            ReplayState.audioNote.isNotEmpty() -> ReplayState.audioNote
            else -> "Game audio: off"
        }
        messageText.text = ReplayState.message
        messageText.visibility = if (ReplayState.message.isEmpty()) View.GONE else View.VISIBLE

        startBtn.text = if (ReplayState.isActive()) "STOP BUFFER" else "START BUFFER"
        startBtn.background = if (ReplayState.isActive()) Ui.bg(this, Ui.BLACK, 14, Ui.RED, 2) else Ui.bg(this, Ui.GOLD, 14)
        startBtn.setTextColor(if (ReplayState.isActive()) Ui.WHITE else Ui.BLACK)
        Ui.setEnabled(clipBtn, st == ReplayState.Status.RUNNING)
        Ui.setEnabled(pauseBtn, st == ReplayState.Status.RUNNING || st == ReplayState.Status.PAUSED)
        pauseBtn.text = if (st == ReplayState.Status.PAUSED) "RESUME" else "PAUSE"
    }

    // ------------------------------------------------------------------ start flow

    private fun onStartStop() {
        if (ReplayState.isActive()) {
            ReplayService.command(this, ReplayService.ACTION_STOP)
            return
        }
        ReplayState.message = ""
        if (Prefs(this).captureAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO)
        } else {
            requestProjection()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Carry on either way: without the permission the buffer still records video, the status card says audio is off.
        if (requestCode == RC_AUDIO) requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), RC_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            ReplayService.start(this, resultCode, data)
        } else {
            Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val RC_AUDIO = 10
        private const val RC_PROJECTION = 11
    }
}
