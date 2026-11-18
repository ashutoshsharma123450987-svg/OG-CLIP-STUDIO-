package com.ogclip.studio.input

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Last few key events seen by the hotkey service, shown on the Controls screen so you can find out what your
 * keyboard / mouse really sends. Privacy: it records ONLY while the Controls screen is open ([capturing]),
 * keeps 14 lines in memory and never writes anything to disk.
 */
object InputLog {
    @Volatile var capturing = false
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(e: KeyEvent) {
        if (!capturing) return
        val src = when {
            (e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE -> "mouse"
            (e.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> "keyboard"
            else -> "other"
        }
        val mods = buildString {
            if (e.isCtrlPressed) append("Ctrl+")
            if (e.isShiftPressed) append("Shift+")
            if (e.isAltPressed) append("Alt+")
        }
        val act = if (e.action == KeyEvent.ACTION_DOWN) "down" else "up"
        lines.addFirst("$act  $mods${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")}  [$src]")
        while (lines.size > 14) lines.removeLast()
    }

    @Synchronized
    fun snapshot(): List<String> = ArrayList(lines)

    @Synchronized
    fun clear() = lines.clear()
}
