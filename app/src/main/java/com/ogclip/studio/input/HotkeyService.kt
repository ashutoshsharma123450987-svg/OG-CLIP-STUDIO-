package com.ogclip.studio.input

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ogclip.studio.core.Clipper
import com.ogclip.studio.core.Prefs

/**
 * Global shortcut for CLIP NOW. Android sends hardware key events only to the focused window (the game), so the only
 * non-root route is an accessibility service with key-event filtering. The user must switch it on in Android settings.
 * It looks at key events only to match the shortcut; it does not read screen content or store anything.
 */
class HotkeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        InputLog.add(event)
        val p = Prefs(this)
        val match = (p.hotkeyEnabled && matchesCombo(event, p)) ||
                (p.mouseForward && event.keyCode == KeyEvent.KEYCODE_FORWARD)
        if (!match) return false
        // act on key-down only, but swallow key-up as well so the game never sees half of the combination
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Clipper.clipNow(this)
        return true
    }

    private fun matchesCombo(e: KeyEvent, p: Prefs): Boolean {
        if (e.keyCode != p.hotkeyCode) return false
        if (p.hotkeyCtrl && !e.isCtrlPressed) return false
        if (p.hotkeyShift && !e.isShiftPressed) return false
        if (p.hotkeyAlt && !e.isAltPressed) return false
        return true
    }

    companion object {
        @Volatile var instance: HotkeyService? = null
    }
}
