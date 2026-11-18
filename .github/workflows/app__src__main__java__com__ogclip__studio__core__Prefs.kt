package com.ogclip.studio.core

import android.content.Context

/** All user settings. Read when the buffer starts (changing them while it runs applies next start). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("og_clip", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Replay window in seconds, 15..180. */
    var bufferSeconds: Int
        get() = sp.getInt("buffer_s", 60).coerceIn(MIN_BUFFER, MAX_BUFFER)
        set(v) = sp.edit().putInt("buffer_s", v.coerceIn(MIN_BUFFER, MAX_BUFFER)).apply()

    /** Short side of the captured video: 480, 720 or 1080. */
    var shortSide: Int
        get() = sp.getInt("short_side", 720)
        set(v) = sp.edit().putInt("short_side", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    var videoKbps: Int
        get() = sp.getInt("kbps", 3000).coerceIn(500, 12000)
        set(v) = sp.edit().putInt("kbps", v.coerceIn(500, 12000)).apply()

    /** Game is played in landscape (Free Fire MAX). Fixed for the whole session, like OG STREAM STUDIO. */
    var landscape: Boolean
        get() = sp.getBoolean("landscape", true)
        set(v) = sp.edit().putBoolean("landscape", v).apply()

    var captureAudio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    /** Where clips come from: own screen capture, or the replay buffer inside OG STREAM STUDIO. */
    var mode: Int
        get() = sp.getInt("mode", MODE_STANDALONE)
        set(v) = sp.edit().putInt("mode", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay", false)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var overlayX: Int
        get() = sp.getInt("overlay_x", 24)
        set(v) = sp.edit().putInt("overlay_x", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlay_y", 300)
        set(v) = sp.edit().putInt("overlay_y", v).apply()

    var hotkeyEnabled: Boolean
        get() = sp.getBoolean("hk_on", false)
        set(v) = sp.edit().putBoolean("hk_on", v).apply()

    /** Android KeyEvent key code of the shortcut key (default C). */
    var hotkeyCode: Int
        get() = sp.getInt("hk_code", 31)
        set(v) = sp.edit().putInt("hk_code", v).apply()

    var hotkeyCtrl: Boolean
        get() = sp.getBoolean("hk_ctrl", true)
        set(v) = sp.edit().putBoolean("hk_ctrl", v).apply()

    var hotkeyShift: Boolean
        get() = sp.getBoolean("hk_shift", true)
        set(v) = sp.edit().putBoolean("hk_shift", v).apply()

    var hotkeyAlt: Boolean
        get() = sp.getBoolean("hk_alt", false)
        set(v) = sp.edit().putBoolean("hk_alt", v).apply()

    /** Mouse "forward" side button also saves a clip (only if Android delivers it as a key event: untested). */
    var mouseForward: Boolean
        get() = sp.getBoolean("mouse_fwd", false)
        set(v) = sp.edit().putBoolean("mouse_fwd", v).apply()

    /** Channel logo / watermark image chosen with the system file picker (persisted read permission). */
    var logoUri: String?
        get() = sp.getString("logo_uri", null)
        set(v) = sp.edit().putString("logo_uri", v).apply()

    /** 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. */
    var logoCorner: Int
        get() = sp.getInt("logo_corner", 1)
        set(v) = sp.edit().putInt("logo_corner", v).apply()

    companion object {
        const val MODE_STANDALONE = 0
        const val MODE_STREAM = 1

        const val MIN_BUFFER = 15
        const val MAX_BUFFER = 180
        const val AUDIO_KBPS = 128

        /** Rough RAM need in MB for the encoded buffer. */
        fun estimateMb(seconds: Int, videoKbps: Int, audio: Boolean): Int {
            val kbps = videoKbps + if (audio) AUDIO_KBPS else 0
            return ((kbps * 125L * seconds) / 1_000_000L).toInt() + 1
        }

        /** Hard cap for the buffer so the app cannot exhaust RAM. */
        const val MAX_BUFFER_BYTES = 192L * 1024 * 1024
    }
}
