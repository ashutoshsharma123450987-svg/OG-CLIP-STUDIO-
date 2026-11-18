package com.ogclip.studio.editor

import android.graphics.Bitmap
import android.net.Uri

/** Everything the exporter needs. Pure data + size maths (no Android calls), so it is easy to reason about. */
class EditSpec(
    val src: Uri,
    val startMs: Long,
    val endMs: Long,
    val srcW: Int,
    val srcH: Int,
    /** true = 9:16 Short (centre-crop window that the user positions); false = keep the original aspect (16:9). */
    val vertical: Boolean,
    /** 0..1, where the 9:16 window sits horizontally inside the source (0 = far left, 1 = far right). */
    val cropPos: Float,
    /** 0 = original size, otherwise the long side of the output: 720 or 1080 (16:9 height / 9:16 uses 1280 or 1920). */
    val quality: Int,
    val text: String,
    /** 0 top, 1 centre, 2 bottom */
    val textPos: Int,
    val textGold: Boolean,
    val logo: Bitmap?,
    /** 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right */
    val logoCorner: Int
) {
    /** Output height in pixels. */
    fun outH(): Int = when {
        quality == 0 -> even(srcH)
        vertical -> if (quality >= 1080) 1920 else 1280
        else -> if (quality >= 1080) 1080 else 720
    }

    /** Output width in pixels. */
    fun outW(): Int = if (vertical) even(outH() * 9 / 16) else even((srcW.toLong() * outH() / srcH).toInt())

    /** Width of the crop window as a fraction of the source width (1 = no crop needed). */
    fun cropFraction(): Float {
        val srcAr = srcW.toFloat() / srcH.toFloat()
        return (CROP_AR / srcAr).coerceAtMost(1f)
    }

    /** Crop edges in normalised device coordinates (-1..1), as the Media3 Crop effect expects. */
    fun cropNdc(): FloatArray {
        val f = cropFraction()
        val centre = -1f + f + cropPos.coerceIn(0f, 1f) * (2f - 2f * f)
        return floatArrayOf(centre - f, centre + f) // left, right
    }

    companion object {
        const val CROP_AR = 9f / 16f
        private fun even(v: Int) = v / 2 * 2
    }
}
