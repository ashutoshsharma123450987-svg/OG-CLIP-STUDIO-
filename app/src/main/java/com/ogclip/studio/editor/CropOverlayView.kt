package com.ogclip.studio.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Drawn on top of the preview: dims everything outside the 9:16 window so you see exactly what the Short will contain,
 * and where. Assumes the video is fitted (letter-boxed) and centred in this view, which is what VideoView does.
 */
class CropOverlayView(ctx: Context) : View(ctx) {
    var videoAspect = 16f / 9f
        set(v) { field = v; invalidate() }
    var vertical = false
        set(v) { field = v; invalidate() }
    var pos = 0.5f
        set(v) { field = v; invalidate() }

    private val dim = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val line = Paint().apply {
        color = Color.parseColor("#FF8A00")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(c: Canvas) {
        if (!vertical) return
        val w = width.toFloat()
        val h = height.toFloat()
        val rw: Float
        val rh: Float
        if (videoAspect > w / h) {
            rw = w
            rh = w / videoAspect
        } else {
            rh = h
            rw = h * videoAspect
        }
        val left = (w - rw) / 2f
        val top = (h - rh) / 2f
        val cropW = rh * EditSpec.CROP_AR
        if (cropW >= rw) return
        val x0 = left + pos.coerceIn(0f, 1f) * (rw - cropW)
        c.drawRect(left, top, x0, top + rh, dim)
        c.drawRect(x0 + cropW, top, left + rw, top + rh, dim)
        c.drawRect(x0, top, x0 + cropW, top + rh, line)
    }
}
