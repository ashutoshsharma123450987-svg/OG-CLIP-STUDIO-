package com.ogclip.studio.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.ogclip.studio.clips.ClipRecord
import com.ogclip.studio.clips.ClipStore
import com.ogclip.studio.core.Fmt
import java.util.concurrent.Executors

/** Local clip library: thumbnail, length, date, size, status, Play / Share / Delete. */
class LibraryActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(48)
    private var items: List<ClipRecord> = emptyList()
    private lateinit var adapter: Adapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.shell(this, "Clip Library", true)
        empty = Ui.text(this, "No clips yet.\nStart the buffer and press CLIP NOW.", 15f, Ui.MUTED)
        empty.gravity = Gravity.CENTER
        empty.setPadding(Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 24), 0)
        empty.visibility = View.GONE
        root.addView(empty, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        adapter = Adapter()
        val list = ListView(this)
        list.divider = null
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        list.clipToPadding = false
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        io.execute {
            val l = ClipStore.list(this)
            main.post {
                items = l
                adapter.notifyDataSetChanged()
                empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun play(r: ClipRecord) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URI, r.uri))
    }

    private fun edit(r: ClipRecord) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_URI, r.uri))
    }

    private fun share(r: ClipRecord) {
        val i = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share clip"))
    }

    private fun confirmDelete(r: ClipRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete clip?")
            .setMessage("${r.name}\n${Fmt.duration(r.durationMs)} · ${Fmt.size(r.sizeBytes)}\n\nThis removes the video file.")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    val ok = try {
                        contentResolver.delete(Uri.parse(r.uri), null, null) >= 0
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) ClipStore.remove(this, r.id)
                    main.post {
                        if (!ok) Toast.makeText(this, "Could not delete the file.", Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadThumb(r: ClipRecord, view: ImageView) {
        val cached = thumbs.get(r.id)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        view.tag = r.id
        io.execute {
            val bmp = try {
                contentResolver.loadThumbnail(Uri.parse(r.uri), Size(320, 180), null)
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                thumbs.put(r.id, bmp)
                main.post { if (view.tag == r.id) view.setImageBitmap(bmp) }
            }
        }
    }

    private class Holder(
        val thumb: ImageView,
        val title: TextView,
        val meta: TextView,
        val status: TextView,
        val play: TextView,
        val edit: TextView,
        val share: TextView,
        val delete: TextView
    )

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h: Holder
            val v: View
            if (convertView == null) {
                val card = Ui.card(this@LibraryActivity)
                val top = LinearLayout(this@LibraryActivity)
                top.orientation = LinearLayout.HORIZONTAL
                val img = ImageView(this@LibraryActivity)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setBackgroundColor(Ui.BLACK)
                top.addView(img, LinearLayout.LayoutParams(Ui.dp(this@LibraryActivity, 128), Ui.dp(this@LibraryActivity, 72)))
                val info = LinearLayout(this@LibraryActivity)
                info.orientation = LinearLayout.VERTICAL
                info.setPadding(Ui.dp(this@LibraryActivity, 12), 0, 0, 0)
                val t = Ui.text(this@LibraryActivity, "", 14f, Ui.WHITE, true)
                t.maxLines = 1
                val m = Ui.text(this@LibraryActivity, "", 12f, Ui.MUTED)
                val s = Ui.text(this@LibraryActivity, "", 12f, Ui.GOLD, true)
                info.addView(t)
                info.addView(m, Ui.lp(this@LibraryActivity, topDp = 2))
                info.addView(s, Ui.lp(this@LibraryActivity, topDp = 2))
                top.addView(info, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                card.addView(top)

                val row = LinearLayout(this@LibraryActivity)
                row.orientation = LinearLayout.HORIZONTAL
                val play = Ui.button(this@LibraryActivity, "Play", Ui.GOLD, Ui.BLACK) {}
                val editBtn = Ui.button(this@LibraryActivity, "Edit", Ui.BLACK, Ui.WHITE, Ui.GOLD) {}
                val share = Ui.button(this@LibraryActivity, "Share", Ui.BLACK, Ui.WHITE, Ui.GOLD) {}
                val del = Ui.button(this@LibraryActivity, "Delete", Ui.BLACK, Ui.RED, Ui.RED) {}
                for ((i, b) in listOf(play, editBtn, share, del).withIndex()) {
                    b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    b.setPadding(0, Ui.dp(this@LibraryActivity, 9), 0, Ui.dp(this@LibraryActivity, 9))
                    val p = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
                    if (i < 3) p.marginEnd = Ui.dp(this@LibraryActivity, 8)
                    row.addView(b, p)
                }
                card.addView(row, Ui.lp(this@LibraryActivity, topDp = 10))

                val wrap = LinearLayout(this@LibraryActivity)
                wrap.setPadding(0, 0, 0, Ui.dp(this@LibraryActivity, 10))
                wrap.addView(card, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
                h = Holder(img, t, m, s, play, editBtn, share, del)
                wrap.tag = h
                v = wrap
            } else {
                v = convertView
                h = v.tag as Holder
            }
            val r = items[position]
            h.title.text = r.name
            h.meta.text = "${Fmt.duration(r.durationMs)} · ${Fmt.size(r.sizeBytes)} · ${r.width}×${r.height}\n${Fmt.dateTime(r.createdAtMs)}"
            h.status.text = if (r.uploadStatus == "LOCAL") "Saved locally" else r.uploadStatus
            h.play.setOnClickListener { play(r) }
            h.edit.setOnClickListener { edit(r) }
            h.share.setOnClickListener { share(r) }
            h.delete.setOnClickListener { confirmDelete(r) }
            loadThumb(r, h.thumb)
            return v
        }
    }
}
