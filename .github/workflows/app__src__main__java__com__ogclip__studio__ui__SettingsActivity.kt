package com.ogclip.studio.ui

import android.app.Activity
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.ogclip.studio.core.Prefs
import com.ogclip.studio.core.ReplayState

/** Replay Buffer Settings. Values are read when the buffer starts. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var bufferLabel: TextView
    private lateinit var bitrateLabel: TextView
    private lateinit var memLabel: TextView
    private lateinit var bufferSeek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val c = Ui.scrollPage(this, "Replay Buffer", true)

        if (ReplayState.isActive()) {
            c.addView(Ui.text(this, "The buffer is running. Changes apply the next time you start it.", 13f, Ui.GOLD), Ui.lp(this))
        }

        // ---- buffer length
        val len = Ui.card(this)
        len.addView(Ui.text(this, "Clip length (what CLIP NOW saves)", 15f, Ui.GOLD, true))
        bufferLabel = Ui.text(this, "", 15f, Ui.WHITE, true)
        len.addView(bufferLabel, Ui.lp(this, topDp = 8))
        val presets = listOf(30, 60, 90, 120)
        len.addView(Ui.chips(this, presets.map { "${it}s" }, presets.indexOf(prefs.bufferSeconds)) { i ->
            prefs.bufferSeconds = presets[i]
            bufferSeek.progress = prefs.bufferSeconds - Prefs.MIN_BUFFER
            update()
        }, Ui.lp(this, topDp = 8))
        bufferSeek = SeekBar(this)
        bufferSeek.max = Prefs.MAX_BUFFER - Prefs.MIN_BUFFER
        bufferSeek.progress = prefs.bufferSeconds - Prefs.MIN_BUFFER
        bufferSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.bufferSeconds = Prefs.MIN_BUFFER + p
                    update()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        len.addView(bufferSeek, Ui.lp(this, topDp = 8))
        len.addView(Ui.text(this, "Custom: 15–180 seconds. A clip starts on a key frame, so it can be up to 2 s longer.", 12f, Ui.MUTED), Ui.lp(this, topDp = 2))
        c.addView(len, Ui.lp(this, topDp = 8))

        // ---- video
        val vid = Ui.card(this)
        vid.addView(Ui.text(this, "Video", 15f, Ui.GOLD, true))
        vid.addView(Ui.text(this, "Resolution (short side)", 13f, Ui.MUTED), Ui.lp(this, topDp = 8))
        val res = listOf(480, 720, 1080)
        vid.addView(Ui.chips(this, res.map { "${it}p" }, res.indexOf(prefs.shortSide)) { i ->
            prefs.shortSide = res[i]
            update()
        }, Ui.lp(this, topDp = 6))
        vid.addView(Ui.text(this, "Frame rate (falls back to 30 if the phone cannot do 60)", 13f, Ui.MUTED), Ui.lp(this, topDp = 12))
        val fpsList = listOf(30, 60)
        vid.addView(Ui.chips(this, fpsList.map { "$it fps" }, fpsList.indexOf(prefs.fps)) { i ->
            prefs.fps = fpsList[i]
        }, Ui.lp(this, topDp = 6))
        bitrateLabel = Ui.text(this, "", 13f, Ui.MUTED)
        vid.addView(bitrateLabel, Ui.lp(this, topDp = 12))
        val bitrateSeek = SeekBar(this)
        bitrateSeek.max = 23 // 500 .. 12000 kbps in 500 steps
        bitrateSeek.progress = (prefs.videoKbps / 500 - 1).coerceIn(0, 23)
        bitrateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.videoKbps = (p + 1) * 500
                    update()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        vid.addView(bitrateSeek, Ui.lp(this, topDp = 4))
        vid.addView(Ui.text(this, "Orientation of the game (fixed while the buffer runs)", 13f, Ui.MUTED), Ui.lp(this, topDp = 12))
        vid.addView(Ui.chips(this, listOf("Landscape", "Portrait"), if (prefs.landscape) 0 else 1) { i ->
            prefs.landscape = i == 0
        }, Ui.lp(this, topDp = 6))
        c.addView(vid, Ui.lp(this, topDp = 12))

        // ---- audio
        val aud = Ui.card(this)
        aud.addView(Ui.text(this, "Audio", 15f, Ui.GOLD, true))
        val sw = Switch(this)
        sw.text = "Capture game sound"
        sw.setTextColor(Ui.WHITE)
        sw.isChecked = prefs.captureAudio
        sw.setOnCheckedChangeListener { _, on ->
            prefs.captureAudio = on
            update()
        }
        aud.addView(sw, Ui.lp(this, topDp = 8))
        aud.addView(Ui.text(this, "Standalone mode captures game sound only. Your microphone and teammates are added by the integration with OG STREAM STUDIO (next phase).", 12f, Ui.MUTED), Ui.lp(this, topDp = 6))
        c.addView(aud, Ui.lp(this, topDp = 12))

        // ---- memory estimate
        val mem = Ui.card(this)
        mem.addView(Ui.text(this, "Memory estimate", 15f, Ui.GOLD, true))
        memLabel = Ui.text(this, "", 14f, Ui.WHITE)
        mem.addView(memLabel, Ui.lp(this, topDp = 8))
        mem.addView(Ui.text(this, "Higher resolution and bitrate use more RAM, CPU and battery and make the phone hotter. If the game lags, lower these first.", 12f, Ui.MUTED), Ui.lp(this, topDp = 6))
        c.addView(mem, Ui.lp(this, topDp = 12))

        update()
    }

    private fun update() {
        bufferLabel.text = "${prefs.bufferSeconds} seconds"
        bitrateLabel.text = "Video bitrate: ${prefs.videoKbps} kbps"
        val mb = Prefs.estimateMb(prefs.bufferSeconds, prefs.videoKbps, prefs.captureAudio)
        val capMb = (Prefs.MAX_BUFFER_BYTES / (1024 * 1024)).toInt()
        memLabel.text = "About $mb MB of RAM for the buffer" +
                if (mb > capMb) "\nOver the $capMb MB safety cap: the buffer will hold less than ${prefs.bufferSeconds} s." else ""
        memLabel.setTextColor(if (mb > capMb) Ui.RED else Ui.WHITE)
    }
}
