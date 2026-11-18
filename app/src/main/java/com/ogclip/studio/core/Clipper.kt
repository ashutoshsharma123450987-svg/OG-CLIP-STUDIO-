package com.ogclip.studio.core

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.ogclip.studio.integration.ClipClient
import com.ogclip.studio.service.ReplayService

/** Single entry point for "save a clip now", used by the dashboard, floating button and hotkeys. Call on the main thread. */
object Clipper {
    private var lastAt = 0L

    fun clipNow(ctx: Context) {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 1500) return // debounce double presses
        lastAt = now
        val prefs = Prefs(app)
        if (prefs.mode == Prefs.MODE_STREAM) {
            Toast.makeText(app, "Saving clip from stream…", Toast.LENGTH_SHORT).show()
            ClipClient.clip(app, prefs.bufferSeconds) { rec, err ->
                if (rec != null) {
                    ReplayState.clipsThisSession++
                    ReplayState.message = "Saved ${Fmt.duration(rec.durationMs)} clip (${Fmt.size(rec.sizeBytes)})"
                    Toast.makeText(app, "Clip saved: ${Fmt.duration(rec.durationMs)}", Toast.LENGTH_SHORT).show()
                } else {
                    ReplayState.message = "Clip failed: $err"
                    Toast.makeText(app, "Clip failed: $err", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            ReplayService.command(app, ReplayService.ACTION_CLIP)
        }
    }
}
