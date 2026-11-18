package com.ogclip.studio.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.ogclip.studio.core.Prefs
import com.ogclip.studio.input.HotkeyService
import com.ogclip.studio.input.InputLog
import com.ogclip.studio.integration.ClipClient
import com.ogclip.studio.overlay.OverlayService

/** Clip source, floating button, keyboard shortcut, mouse button, and an input tester. */
class ControlsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var overlaySwitch: Switch
    private lateinit var hotkeySwitch: Switch
    private lateinit var shortcutText: TextView
    private lateinit var serviceText: TextView
    private lateinit var logText: TextView
    private lateinit var sourceNote: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val c = Ui.scrollPage(this, "Controls", true)

        // ---- source
        val src = Ui.card(this)
        src.addView(Ui.text(this, "Clip source", 15f, Ui.GOLD, true))
        src.addView(Ui.chips(this, listOf("Standalone capture", "OG STREAM STUDIO"), prefs.mode) { i ->
            prefs.mode = i
            updateSourceNote()
        }, Ui.lp(this, topDp = 8))
        sourceNote = Ui.text(this, "", 12f, Ui.MUTED)
        src.addView(sourceNote, Ui.lp(this, topDp = 8))
        c.addView(src, Ui.lp(this, topDp = 4))

        // ---- floating button
        val ov = Ui.card(this)
        ov.addView(Ui.text(this, "Floating clip button", 15f, Ui.GOLD, true))
        overlaySwitch = Switch(this)
        overlaySwitch.text = "Show button over the game"
        overlaySwitch.setTextColor(Ui.WHITE)
        overlaySwitch.setOnCheckedChangeListener { v, on ->
            if (!v.isPressed) return@setOnCheckedChangeListener // ignore programmatic changes
            toggleOverlay(on)
        }
        ov.addView(overlaySwitch, Ui.lp(this, topDp = 8))
        ov.addView(Ui.text(this, "Tap = clip. Long-press = menu. Drag to move. Needs \"Display over other apps\". " +
                "Android draws overlays into screen capture, so your stream viewers may see this button.", 12f, Ui.MUTED), Ui.lp(this, topDp = 6))
        c.addView(ov, Ui.lp(this, topDp = 12))

        // ---- keyboard
        val kb = Ui.card(this)
        kb.addView(Ui.text(this, "Keyboard shortcut", 15f, Ui.GOLD, true))
        hotkeySwitch = Switch(this)
        hotkeySwitch.text = "Enable shortcut"
        hotkeySwitch.setTextColor(Ui.WHITE)
        hotkeySwitch.setOnCheckedChangeListener { v, on ->
            if (!v.isPressed) return@setOnCheckedChangeListener
            prefs.hotkeyEnabled = on
            if (on && HotkeyService.instance == null) openAccessibility()
        }
        kb.addView(hotkeySwitch, Ui.lp(this, topDp = 8))
        shortcutText = Ui.text(this, "", 16f, Ui.WHITE, true)
        kb.addView(shortcutText, Ui.lp(this, topDp = 8))
        kb.addView(Ui.button(this, "Change shortcut", Ui.BLACK, Ui.WHITE, Ui.GOLD) { recordShortcut() }, Ui.lp(this, topDp = 8))
        serviceText = Ui.text(this, "", 13f, Ui.MUTED)
        kb.addView(serviceText, Ui.lp(this, topDp = 10))
        kb.addView(Ui.button(this, "Open Accessibility settings", Ui.CARD, Ui.WHITE) { openAccessibility() }, Ui.lp(this, topDp = 8))
        kb.addView(Ui.text(this, "Android only gives keys to the app in front, so a global shortcut needs this accessibility service " +
                "(turn on \"OG CLIP STUDIO hotkeys\"). It only matches your shortcut; it reads no screen content and stores nothing.", 12f, Ui.MUTED), Ui.lp(this, topDp = 8))
        c.addView(kb, Ui.lp(this, topDp = 12))

        // ---- mouse
        val ms = Ui.card(this)
        ms.addView(Ui.text(this, "Mouse button (experimental)", 15f, Ui.GOLD, true))
        val msw = Switch(this)
        msw.text = "Mouse \"Forward\" side button saves a clip"
        msw.setTextColor(Ui.WHITE)
        msw.isChecked = prefs.mouseForward
        msw.setOnCheckedChangeListener { _, on -> prefs.mouseForward = on }
        ms.addView(msw, Ui.lp(this, topDp = 8))
        ms.addView(Ui.text(this, "Works only if your mouse's side button reaches Android as a key (see the tester below). " +
                "Middle and other buttons are not delivered to background apps by Android. Use the tester to check what your mouse sends. " +
                "Needs the accessibility service above.", 12f, Ui.MUTED), Ui.lp(this, topDp = 6))
        c.addView(ms, Ui.lp(this, topDp = 12))

        // ---- tester
        val t = Ui.card(this)
        t.addView(Ui.text(this, "Input tester", 15f, Ui.GOLD, true))
        t.addView(Ui.text(this, "Press keys or mouse buttons now (accessibility service must be on). Recorded only while this screen is open, kept in memory only.", 12f, Ui.MUTED), Ui.lp(this, topDp = 6))
        logText = Ui.text(this, "", 13f, Ui.WHITE)
        logText.typeface = android.graphics.Typeface.MONOSPACE
        t.addView(logText, Ui.lp(this, topDp = 8))
        c.addView(t, Ui.lp(this, topDp = 12))

        overlaySwitch.isChecked = prefs.overlayEnabled
        hotkeySwitch.isChecked = prefs.hotkeyEnabled
        updateSourceNote()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        InputLog.clear()
        InputLog.capturing = true
        // permission may just have been granted in Settings
        if (prefs.overlayEnabled && !Settings.canDrawOverlays(this)) prefs.overlayEnabled = false
        overlaySwitch.isChecked = prefs.overlayEnabled
        refresher.run()
    }

    override fun onPause() {
        InputLog.capturing = false
        InputLog.clear()
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun updateSourceNote() {
        sourceNote.text = if (prefs.mode == Prefs.MODE_STREAM) {
            if (ClipClient.isInstalled(this))
                "Clips are cut from the replay buffer inside OG STREAM STUDIO (game + mic + teammates audio, stream quality). " +
                        "Turn on \"Replay buffer\" in its settings, then go live."
            else "OG STREAM STUDIO (patched version 1.6+) is not installed on this phone."
        } else {
            "This app records its own screen capture (game sound only). Start the buffer on the Dashboard."
        }
    }

    private fun toggleOverlay(on: Boolean) {
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow \"Display over other apps\" for OG CLIP STUDIO, then come back and switch it on.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                overlaySwitch.isChecked = false
                return
            }
            prefs.overlayEnabled = true
            OverlayService.start(this)
        } else {
            prefs.overlayEnabled = false
            OverlayService.stop(this)
        }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun shortcutLabel(): String {
        val sb = StringBuilder()
        if (prefs.hotkeyCtrl) sb.append("Ctrl + ")
        if (prefs.hotkeyShift) sb.append("Shift + ")
        if (prefs.hotkeyAlt) sb.append("Alt + ")
        sb.append(KeyEvent.keyCodeToString(prefs.hotkeyCode).removePrefix("KEYCODE_"))
        return sb.toString()
    }

    private fun recordShortcut() {
        val d = AlertDialog.Builder(this)
            .setTitle("Press the new shortcut")
            .setMessage("Hold Ctrl, Shift or Alt and press a key (a keyboard must be connected).")
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnKeyListener { dialog, keyCode, event ->
            val isModifier = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_BACK
            if (event.action == KeyEvent.ACTION_DOWN && !isModifier) {
                if (!event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed) {
                    Toast.makeText(this, "Hold Ctrl, Shift or Alt as well, so normal keys are not hijacked.", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.hotkeyCode = keyCode
                    prefs.hotkeyCtrl = event.isCtrlPressed
                    prefs.hotkeyShift = event.isShiftPressed
                    prefs.hotkeyAlt = event.isAltPressed
                    dialog.dismiss()
                    refresh()
                }
                true
            } else false
        }
        d.show()
    }

    private fun refresh() {
        shortcutText.text = shortcutLabel()
        serviceText.text = if (HotkeyService.instance != null) "Accessibility service: ON" else "Accessibility service: OFF (shortcut and mouse button will not work)"
        serviceText.setTextColor(if (HotkeyService.instance != null) Ui.GOLD else Ui.RED)
        val lines = InputLog.snapshot()
        logText.text = if (lines.isEmpty()) "(nothing yet)" else lines.joinToString("\n")
    }
}
