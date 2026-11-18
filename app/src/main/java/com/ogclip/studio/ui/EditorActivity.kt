package com.ogclip.studio.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.content.res.ColorStateList
import com.ogclip.studio.clips.ClipStore
import com.ogclip.studio.clips.ClipWriter
import com.ogclip.studio.core.Fmt
import com.ogclip.studio.core.Prefs
import com.ogclip.studio.editor.CropOverlayView
import com.ogclip.studio.editor.EditSpec
import com.ogclip.studio.editor.ShortsExporter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Simple clip editor: trim, 9:16 Short with adjustable crop window, text, logo, quality. Export re-encodes into a
 * temporary file, then you preview it and choose Save to library or Discard.
 */
class EditorActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var srcUri: Uri
    private var durationMs = 0L
    private var srcW = 0
    private var srcH = 0

    private lateinit var video: VideoView
    private lateinit var cropView: CropOverlayView
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var trimLabel: TextView
    private lateinit var cropSeekRow: LinearLayout
    private lateinit var cropSeek: SeekBar
    private lateinit var textInput: EditText
    private lateinit var logoLabel: TextView
    private lateinit var exportBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView

    // current choices
    private var vertical = true
    private var quality = 0
    private var textPos = 2
    private var textGold = false
    private var useLogo = false
    private var logoCorner = 1
    private var logoBitmapSource: Uri? = null

    private var exporter: ShortsExporter? = null
    private var exportFile: File? = null
    private var exportSpec: EditSpec? = null

    private val stopAtEnd = object : Runnable {
        override fun run() {
            if (!video.isPlaying) return
            if (video.currentPosition >= endMs()) {
                video.pause()
                return
            }
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prefs = Prefs(this)
        val u = intent.getStringExtra(EXTRA_URI)
        if (u == null) {
            finish()
            return
        }
        srcUri = Uri.parse(u)
        if (!readInfo()) {
            Toast.makeText(this, "This clip cannot be opened for editing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        useLogo = prefs.logoUri != null
        logoCorner = prefs.logoCorner
        logoBitmapSource = prefs.logoUri?.let { Uri.parse(it) }
        cleanOldExports()
        build()
    }

    private fun readInfo(): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, srcUri)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            srcW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            srcH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationMs > 500 && srcW > 0 && srcH > 0
        } catch (_: Exception) {
            false
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun build() {
        val c = Ui.scrollPage(this, "Edit clip", true)

        // ---- preview
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Ui.BLACK)
        video = VideoView(this)
        frame.addView(video, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, android.view.Gravity.CENTER))
        cropView = CropOverlayView(this)
        cropView.videoAspect = srcW.toFloat() / srcH.toFloat()
        cropView.vertical = vertical
        frame.addView(cropView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        c.addView(frame, Ui.lp(this, h = Ui.dp(this, 230)))
        video.setVideoURI(srcUri)
        video.setOnPreparedListener { video.seekTo(1) }

        c.addView(Ui.button(this, "▶  Play selection", Ui.CARD, Ui.WHITE) { playSelection() }, Ui.lp(this, topDp = 8))
        c.addView(Ui.text(this, "Preview shows the crop window; text and logo appear in the exported result.", 12f, Ui.MUTED), Ui.lp(this, topDp = 4))

        // ---- trim
        val trim = Ui.card(this)
        trim.addView(Ui.text(this, "Trim", 15f, Ui.GOLD, true))
        trimLabel = Ui.text(this, "", 14f, Ui.WHITE)
        trim.addView(trimLabel, Ui.lp(this, topDp = 6))
        val steps = (durationMs / 100).toInt().coerceAtLeast(1)
        startSeek = SeekBar(this)
        endSeek = SeekBar(this)
        startSeek.max = steps
        endSeek.max = steps
        startSeek.progress = 0
        endSeek.progress = steps
        trim.addView(Ui.text(this, "Start", 12f, Ui.MUTED), Ui.lp(this, topDp = 8))
        trim.addView(startSeek)
        trim.addView(Ui.text(this, "End", 12f, Ui.MUTED), Ui.lp(this, topDp = 4))
        trim.addView(endSeek)
        startSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (p > endSeek.progress - MIN_STEPS) startSeek.progress = (endSeek.progress - MIN_STEPS).coerceAtLeast(0)
                updateTrimLabel()
                video.seekTo(startMs().toInt())
            }
            override fun onStartTrackingTouch(s: SeekBar?) { video.pause() }
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        endSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (p < startSeek.progress + MIN_STEPS) endSeek.progress = (startSeek.progress + MIN_STEPS).coerceAtMost(steps)
                updateTrimLabel()
                video.seekTo((endMs() - 500).coerceAtLeast(startMs()).toInt())
            }
            override fun onStartTrackingTouch(s: SeekBar?) { video.pause() }
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        c.addView(trim, Ui.lp(this, topDp = 12))

        // ---- format
        val fmt = Ui.card(this)
        fmt.addView(Ui.text(this, "Format", 15f, Ui.GOLD, true))
        fmt.addView(Ui.chips(this, listOf("9:16 YouTube Short", "Original (16:9)"), if (vertical) 0 else 1) { i ->
            vertical = i == 0
            cropView.vertical = vertical
            cropSeekRow.visibility = if (vertical) View.VISIBLE else View.GONE
        }, Ui.lp(this, topDp = 8))
        cropSeekRow = LinearLayout(this)
        cropSeekRow.orientation = LinearLayout.VERTICAL
        cropSeekRow.addView(Ui.text(this, "Move the crop window left / right (nothing is cropped automatically)", 12f, Ui.MUTED), Ui.lp(this, topDp = 10))
        cropSeek = SeekBar(this)
        cropSeek.max = 100
        cropSeek.progress = 50
        cropSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                cropView.pos = p / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        cropSeekRow.addView(cropSeek)
        fmt.addView(cropSeekRow)
        fmt.addView(Ui.text(this, "Quality", 12f, Ui.MUTED), Ui.lp(this, topDp = 10))
        fmt.addView(Ui.chips(this, listOf("Original size", "720p", "1080p"), quality) { i -> quality = i }, Ui.lp(this, topDp = 6))
        c.addView(fmt, Ui.lp(this, topDp = 12))

        // ---- text
        val tx = Ui.card(this)
        tx.addView(Ui.text(this, "Text", 15f, Ui.GOLD, true))
        textInput = EditText(this)
        textInput.hint = "Add text (optional)"
        textInput.setHintTextColor(Ui.MUTED)
        textInput.setTextColor(Ui.WHITE)
        textInput.inputType = InputType.TYPE_CLASS_TEXT
        textInput.maxLines = 1
        textInput.background = Ui.bg(this, Ui.BLACK, 10, Ui.MUTED, 1)
        textInput.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        tx.addView(textInput, Ui.lp(this, topDp = 8))
        tx.addView(Ui.chips(this, listOf("Top", "Centre", "Bottom"), textPos) { i -> textPos = i }, Ui.lp(this, topDp = 8))
        tx.addView(Ui.chips(this, listOf("White", "Orange"), if (textGold) 1 else 0) { i -> textGold = i == 1 }, Ui.lp(this, topDp = 8))
        c.addView(tx, Ui.lp(this, topDp = 12))

        // ---- logo
        val lg = Ui.card(this)
        lg.addView(Ui.text(this, "Channel logo / watermark", 15f, Ui.GOLD, true))
        logoLabel = Ui.text(this, "", 13f, Ui.MUTED)
        lg.addView(logoLabel, Ui.lp(this, topDp = 6))
        lg.addView(Ui.button(this, "Choose image…", Ui.BLACK, Ui.WHITE, Ui.GOLD) { pickLogo() }, Ui.lp(this, topDp = 8))
        lg.addView(Ui.chips(this, listOf("Top-left", "Top-right", "Bottom-left", "Bottom-right"), logoCorner) { i ->
            logoCorner = i
            prefs.logoCorner = i
        }, Ui.lp(this, topDp = 8))
        lg.addView(Ui.button(this, "Remove logo", Ui.BLACK, Ui.MUTED) {
            useLogo = false
            prefs.logoUri = null
            logoBitmapSource = null
            updateLogoLabel()
        }, Ui.lp(this, topDp = 8))
        c.addView(lg, Ui.lp(this, topDp = 12))

        // ---- export
        exportBtn = Ui.button(this, "EXPORT", Ui.GOLD, Ui.BLACK) { startExport() }
        c.addView(exportBtn, Ui.lp(this, topDp = 16))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(Ui.GOLD)
        progress.visibility = View.GONE
        c.addView(progress, Ui.lp(this, h = Ui.dp(this, 10), topDp = 10))
        statusText = Ui.text(this, "", 13f, Ui.MUTED)
        c.addView(statusText, Ui.lp(this, topDp = 6))

        resultCard = Ui.card(this)
        resultCard.visibility = View.GONE
        resultText = Ui.text(this, "", 14f, Ui.WHITE)
        resultCard.addView(resultText)
        resultCard.addView(Ui.button(this, "Preview result", Ui.CARD, Ui.WHITE) { previewResult() }, Ui.lp(this, topDp = 10))
        resultCard.addView(Ui.button(this, "Save to library", Ui.GOLD, Ui.BLACK) { saveResult() }, Ui.lp(this, topDp = 8))
        resultCard.addView(Ui.button(this, "Discard", Ui.BLACK, Ui.RED, Ui.RED) { discardResult() }, Ui.lp(this, topDp = 8))
        c.addView(resultCard, Ui.lp(this, topDp = 12))

        cropSeekRow.visibility = if (vertical) View.VISIBLE else View.GONE
        updateTrimLabel()
        updateLogoLabel()
    }

    // ------------------------------------------------------------------ helpers

    private fun startMs() = startSeek.progress * 100L
    private fun endMs() = minOf(durationMs, endSeek.progress * 100L)

    private fun updateTrimLabel() {
        val len = endMs() - startMs()
        trimLabel.text = String.format(Locale.US, "%.1f s  →  %.1f s    (length %.1f s of %.1f s)",
            startMs() / 1000.0, endMs() / 1000.0, len / 1000.0, durationMs / 1000.0)
    }

    private fun updateLogoLabel() {
        logoLabel.text = if (useLogo && logoBitmapSource != null) "Logo selected. It is shown at about 20% of the video width." else "No logo."
    }

    private fun playSelection() {
        video.seekTo(startMs().toInt())
        video.start()
        main.postDelayed(stopAtEnd, 200)
    }

    @Suppress("DEPRECATION")
    private fun pickLogo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, RC_LOGO)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_LOGO || resultCode != RESULT_OK) return
        val u = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // still usable for this session
        }
        prefs.logoUri = u.toString()
        logoBitmapSource = u
        useLogo = true
        updateLogoLabel()
    }

    /** Decodes the logo scaled to ~20% of the output width. */
    private fun loadLogo(targetW: Int): Bitmap? {
        val u = logoBitmapSource ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val h = (raw.height.toLong() * targetW / raw.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, targetW, h, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun exportDir(): File {
        val d = File(cacheDir, "exports")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun cleanOldExports() {
        val limit = System.currentTimeMillis() - 60L * 60L * 1000L
        exportDir().listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    // ------------------------------------------------------------------ export

    private fun startExport() {
        if (exporter != null) return
        video.pause()
        resultCard.visibility = View.GONE
        // build the spec once with a null logo to learn the output width, then load the logo at 20% of it
        val base = makeSpec(null)
        val logo = if (useLogo) loadLogo((base.outW() * 0.2f).toInt().coerceAtLeast(32)) else null
        if (useLogo && logo == null) {
            Toast.makeText(this, "The logo image could not be read. Exporting without it.", Toast.LENGTH_LONG).show()
        }
        val spec = makeSpec(logo)
        val out = File(exportDir(), "short_" + System.currentTimeMillis() + ".mp4")
        exportFile = out
        exportSpec = spec

        Ui.setEnabled(exportBtn, false)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        statusText.text = "Exporting… keep this screen open."
        val ex = ShortsExporter(this)
        exporter = ex
        try {
            ex.start(spec, out, { p -> progress.progress = p }) { err ->
                exporter = null
                Ui.setEnabled(exportBtn, true)
                progress.visibility = View.GONE
                if (err == null && out.exists() && out.length() > 0) {
                    statusText.text = ""
                    resultText.text = "Done: ${Fmt.duration(spec.endMs - spec.startMs)} · ${spec.outW()}×${spec.outH()} · ${Fmt.size(out.length())}\nPreview it, then save or discard."
                    resultCard.visibility = View.VISIBLE
                } else {
                    statusText.text = "Export failed: ${err ?: "empty file"}"
                    out.delete()
                }
            }
        } catch (e: Exception) {
            exporter = null
            Ui.setEnabled(exportBtn, true)
            progress.visibility = View.GONE
            statusText.text = "Export could not start: ${e.message}"
        }
    }

    private fun makeSpec(logo: Bitmap?) = EditSpec(
        src = srcUri,
        startMs = startMs(),
        endMs = endMs(),
        srcW = srcW,
        srcH = srcH,
        vertical = vertical,
        cropPos = cropSeek.progress / 100f,
        quality = when (quality) {
            1 -> 720
            2 -> 1080
            else -> 0
        },
        text = textInput.text.toString().trim(),
        textPos = textPos,
        textGold = textGold,
        logo = logo,
        logoCorner = logoCorner
    )

    private fun previewResult() {
        val f = exportFile ?: return
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, Uri.fromFile(f).toString()))
    }

    private fun saveResult() {
        val f = exportFile ?: return
        val spec = exportSpec ?: return
        val app = applicationContext
        statusText.text = "Saving…"
        io.execute {
            try {
                val rec = ClipWriter.importFrom(app, Uri.fromFile(f), spec.endMs - spec.startMs, spec.outW(), spec.outH(), "OGSHORT_")
                ClipStore.add(app, rec)
                f.delete()
                main.post {
                    exportFile = null
                    resultCard.visibility = View.GONE
                    statusText.text = "Saved to your library: ${rec.name}"
                    Toast.makeText(app, "Saved to library", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                main.post { statusText.text = "Save failed: ${e.message}" }
            }
        }
    }

    private fun discardResult() {
        exportFile?.delete()
        exportFile = null
        resultCard.visibility = View.GONE
        statusText.text = "Discarded."
    }

    override fun onPause() {
        if (::video.isInitialized) video.pause()
        super.onPause()
    }

    override fun onDestroy() {
        exporter?.cancel()
        exporter = null
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        private const val RC_LOGO = 21
        private const val MIN_STEPS = 5 // 0.5 s
    }
}
