package com.ogclip.studio.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny UI toolkit: Matte Black, Metallic Gold, Fiery Red, small white accents. Built in code to keep the APK lean. */
object Ui {
    val BLACK = Color.parseColor("#0E0E10")
    val CARD = Color.parseColor("#1A1A1D")
    val GOLD = Color.parseColor("#D4AF37")
    val RED = Color.parseColor("#E0301E")
    val WHITE = Color.parseColor("#F5F5F5")
    val MUTED = Color.parseColor("#9A9AA0")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun bg(ctx: Context, fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0) d.setStroke(dp(ctx, strokeDp), stroke)
        return d
    }

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = WHITE, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun lp(ctx: Context, w: Int = MATCH, h: Int = WRAP, topDp: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.topMargin = dp(ctx, topDp)
        return p
    }

    /** Big rounded button. Pass [stroke] for an outline style. */
    fun button(ctx: Context, label: String, fill: Int, textColor: Int, stroke: Int = 0, onClick: () -> Unit): TextView {
        val b = text(ctx, label, 16f, textColor, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        b.background = bg(ctx, fill, 14, stroke, if (stroke != 0) 2 else 0)
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    fun setEnabled(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        c.background = bg(ctx, CARD, 16, Color.parseColor("#2A2A2E"), 1)
        return c
    }

    /** Row of selectable chips. [selected] may be -1 (none). */
    fun chips(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            for (i in views.indices) {
                val on = i == sel
                views[i].background = bg(ctx, if (on) GOLD else BLACK, 10, if (on) GOLD else Color.parseColor("#3A3A3F"), 1)
                views[i].setTextColor(if (on) BLACK else WHITE)
            }
        }
        for (i in labels.indices) {
            val t = text(ctx, labels[i], 14f, WHITE, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10))
            val p = LinearLayout.LayoutParams(0, WRAP, 1f)
            p.marginEnd = if (i < labels.size - 1) dp(ctx, 6) else 0
            t.layoutParams = p
            t.isClickable = true
            t.setOnClickListener {
                paint(i)
                onSelect(i)
            }
            views.add(t)
            row.addView(t)
        }
        paint(selected)
        return row
    }

    /** Screen frame with a title bar. Returns the root; add content below it. */
    fun shell(a: Activity, title: String, back: Boolean): LinearLayout {
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BLACK)
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 12))
        if (back) {
            val b = text(a, "‹", 30f, GOLD, true)
            b.setPadding(0, 0, dp(a, 14), 0)
            b.setOnClickListener { a.finish() }
            bar.addView(b)
        }
        bar.addView(text(a, title, 20f, GOLD, true))
        root.addView(bar)
        val line = View(a)
        line.setBackgroundColor(Color.parseColor("#55D4AF37"))
        root.addView(line, LinearLayout.LayoutParams(MATCH, dp(a, 1)))
        a.setContentView(root)
        return root
    }

    /** Scrolling screen. Returns the vertical content container. */
    fun scrollPage(a: Activity, title: String, back: Boolean): LinearLayout {
        val root = shell(a, title, back)
        val scroll = ScrollView(a)
        val content = LinearLayout(a)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 24))
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return content
    }
}
