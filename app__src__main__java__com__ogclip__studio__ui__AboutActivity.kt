package com.ogclip.studio.ui

import android.app.Activity
import android.os.Bundle

class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Ui.scrollPage(this, "About", true)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        c.addView(Ui.text(this, "OG CLIP STUDIO", 24f, Ui.GOLD, true))
        c.addView(Ui.text(this, "Version $ver", 14f, Ui.MUTED), Ui.lp(this, topDp = 2))
        c.addView(Ui.text(this, "Independent gaming clip recorder, separate from OG STREAM STUDIO.", 15f, Ui.WHITE), Ui.lp(this, topDp = 10))

        val works = Ui.card(this)
        works.addView(Ui.text(this, "\u2022 Rolling replay buffer (15\u2013180 s) with H.264 + AAC, held in RAM\n" +
                "\u2022 Standalone mode: own screen capture, game sound\n" +
                "\u2022 Stream link: clips cut from OG STREAM STUDIO's buffer (game + mic + teammates mix), no re-encode\n" +
                "\u2022 CLIP NOW from the app, notification, floating button or keyboard shortcut\n" +
                "\u2022 Clips saved as MP4 in Movies/OG CLIP STUDIO\n" +
                "\u2022 Clip library with thumbnails, playback, edit, share and delete\n" +
                "\u2022 Editor: trim, 9:16 Short with movable crop window, text, logo, 720p/1080p, preview before saving", 14f, Ui.WHITE), Ui.lp(this, topDp = 8))
        c.addView(works, Ui.lp(this, topDp = 16))

        val notYet = Ui.card(this)
        notYet.addView(Ui.text(this, "\u2022 YouTube connection and upload\n" +
                "\u2022 Voice commands and AI highlight detection", 14f, Ui.WHITE), Ui.lp(this, topDp = 8))
        c.addView(notYet, Ui.lp(this, topDp = 12))

        val limits = Ui.card(this)
        limits.addView(Ui.text(this, "Known limits", 16f, Ui.GOLD, true))
        limits.addView(Ui.text(this, "• Clips start on a key frame, so a 60 s clip is about 60–62 s.\n" +
                "• Standalone mode records game sound only. Games can block audio capture.\n" +
                "• Screen size is fixed when the buffer starts (Landscape or Portrait setting).\n" +
                "• Protected (secure) screens are recorded black by Android.", 14f, Ui.WHITE), Ui.lp(this, topDp = 8))
        c.addView(limits, Ui.lp(this, topDp = 12))
    }
}
