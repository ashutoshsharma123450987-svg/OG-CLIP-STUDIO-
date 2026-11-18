package com.ogclip.studio.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView

/** Plays a saved clip with the framework VideoView (no extra library). */
class PlayerActivity : Activity() {
    private lateinit var video: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent.getStringExtra(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        video = VideoView(this)
        frame.addView(video, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        setContentView(frame)

        val mc = MediaController(this)
        mc.setAnchorView(video)
        video.setMediaController(mc)
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "This clip cannot be played.", Toast.LENGTH_LONG).show()
            true
        }
        video.setVideoURI(Uri.parse(uri))
        video.setOnPreparedListener { it.isLooping = false; video.start() }
    }

    override fun onPause() {
        if (::video.isInitialized) video.pause()
        super.onPause()
    }

    companion object {
        const val EXTRA_URI = "uri"
    }
}
