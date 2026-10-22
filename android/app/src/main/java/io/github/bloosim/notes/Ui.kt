package io.github.bloosim.notes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Colours of a phone notes app: warm paper, ink, amber accent. */
class Pal(
    val paper: Int, val sheet: Int, val ink: Int, val muted: Int, val faint: Int, val rule: Int,
    val accent: Int, val accentInk: Int, val onAccent: Int, val danger: Int, val dark: Boolean
)

object Ui {
    fun pal(ctx: Context): Pal {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Pal(
            Color.parseColor("#141413"), Color.parseColor("#1D1C1A"), Color.parseColor("#EAE7E0"), Color.parseColor("#8E8A81"),
            Color.parseColor("#4A4740"), Color.parseColor("#262420"), Color.parseColor("#E2A43A"), Color.parseColor("#EBB860"),
            Color.parseColor("#1A1406"), Color.parseColor("#E66C54"), true
        ) else Pal(
            Color.parseColor("#FCFBF8"), Color.parseColor("#FFFFFF"), Color.parseColor("#1E1D1A"), Color.parseColor("#847F75"),
            Color.parseColor("#CBC6BC"), Color.parseColor("#ECE8E0"), Color.parseColor("#D9961A"), Color.parseColor("#9E6708"),
            Color.parseColor("#FFFFFF"), Color.parseColor("#B93B27"), false
        )
    }

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** a pill-shaped button */
    fun pill(ctx: Context, s: String, p: Pal, filled: Boolean = false): TextView = text(ctx, s, 14f, if (filled) p.onAccent else p.ink, true).apply {
        gravity = Gravity.CENTER
        val h = dp(ctx, 12f); val v = dp(ctx, 9f)
        setPadding(h, v, h, v)
        background = if (filled) round(p.accent, dp(ctx, 22f).toFloat()) else round(p.sheet, dp(ctx, 22f).toFloat(), dp(ctx, 1f), p.rule)
        isClickable = true
    }

    /** one numbered line: number on the left, Chinese on top, original below */
    class Row(ctx: Context, p: Pal) {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val no = text(ctx, "", 12f, p.accentInk, true).apply { gravity = Gravity.END; minWidth = dp(ctx, 26f); setPadding(0, dp(ctx, 3f), dp(ctx, 8f), 0) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val zh = text(ctx, "", 17f, p.ink).apply { setLineSpacing(0f, 1.25f) }
        val src = text(ctx, "", 13f, p.muted).apply { setPadding(0, dp(ctx, 2f), 0, 0); setLineSpacing(0f, 1.2f) }

        init {
            col.addView(zh); col.addView(src)
            root.addView(no)
            root.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(e: Entry, p: Pal, fontSp: Int, showSrc: Boolean) {
            no.text = e.n.toString()
            zh.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
            src.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp * 0.78f)
            when {
                e.zh.isNotBlank() -> { zh.text = e.zh; zh.setTextColor(if (e.st == "wait") p.muted else p.ink) }
                e.st == "asr" -> { zh.text = "识别中…"; zh.setTextColor(p.faint) }
                e.st == "err" -> { zh.text = "翻译没成功，点这里重试"; zh.setTextColor(p.danger) }
                else -> { zh.text = "···"; zh.setTextColor(p.faint) }
            }
            src.text = e.src
            src.visibility = if (showSrc && e.src.isNotBlank()) View.VISIBLE else View.GONE
        }
    }
}
