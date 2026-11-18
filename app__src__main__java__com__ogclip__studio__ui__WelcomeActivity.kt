package com.ogclip.studio.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.ogclip.studio.core.Prefs

/** First-run screen: explains what the app does and asks for the permissions it needs. */
class WelcomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Ui.scrollPage(this, "OG CLIP STUDIO", false)
        c.addView(Ui.text(this, "Replay buffer for mobile gaming", 22f, Ui.WHITE, true))
        c.addView(Ui.text(this, "Keeps the last moments of your gameplay ready. Press CLIP NOW after a great play and the previous seconds are saved as an MP4.", 15f, Ui.MUTED), Ui.lp(this, topDp = 8))

        val how = Ui.card(this)
        how.addView(Ui.text(this, "How it works in this version", 16f, Ui.GOLD, true))
        how.addView(Ui.text(this, "• Standalone mode: the app records its own screen capture into a rolling buffer.\n" +
                "• It captures game sound only (no microphone) so it does not fight the game or your stream for the mic.\n" +
                "• Android asks you to allow screen capture every time you start the buffer.\n" +
                "• If OG STREAM STUDIO is live at the same time, a second capture may not work on every phone. Direct integration with the stream app is the next phase.", 14f, Ui.WHITE), Ui.lp(this, topDp = 8))
        c.addView(how, Ui.lp(this, topDp = 16))

        val perm = Ui.card(this)
        perm.addView(Ui.text(this, "Permissions", 16f, Ui.GOLD, true))
        perm.addView(Ui.text(this, "• Audio: required by Android to capture game sound.\n• Notifications: shows the CLIP NOW button while the buffer runs.", 14f, Ui.WHITE), Ui.lp(this, topDp = 8))
        c.addView(perm, Ui.lp(this, topDp = 12))

        c.addView(Ui.button(this, "CONTINUE", Ui.GOLD, Ui.BLACK) { askPermissions() }, Ui.lp(this, topDp = 20))
    }

    private fun askPermissions() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    // Continue whatever the answer was: the app still works without them (clips just have no sound / no notification button).
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Prefs(this).onboarded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
