package io.github.bloosim.clicker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToLong

object C {
    val PAPER = Color.parseColor("#F4F6F9")
    val CARD = Color.WHITE
    val INK = Color.parseColor("#1F2328")
    val MUTED = Color.parseColor("#6B7280")
    val LINE = Color.parseColor("#E3E7ED")
    val BLUE = Color.parseColor("#2F7CF6")
    val BLUE_SOFT = Color.parseColor("#E8F0FE")
    val RED = Color.parseColor("#E5484D")
    val GREEN = Color.parseColor("#1F9D55")
    val ORANGE = Color.parseColor("#F59E0B")
}

object Ui {
    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int = C.INK, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
    }

    /** rounded button; [filled] = blue background */
    fun button(ctx: Context, s: String, filled: Boolean = false, color: Int = C.BLUE, onClick: () -> Unit): TextView =
        text(ctx, s, 15f, if (filled) Color.WHITE else color, true).apply {
            gravity = Gravity.CENTER
            val h = dp(ctx, 14f); val v = dp(ctx, 10f)
            setPadding(h, v, h, v)
            background = if (filled) round(color, dp(ctx, 22f).toFloat()) else round(C.CARD, dp(ctx, 22f).toFloat(), dp(ctx, 1f), C.LINE)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** a white card with a title */
    fun card(ctx: Context, title: String?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(C.CARD, dp(ctx, 14f).toFloat())
        val p = dp(ctx, 14f)
        setPadding(p, p, p, p)
        if (title != null) addView(text(ctx, title, 16f, C.INK, true).apply { setPadding(0, 0, 0, dp(ctx, 8f)) })
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    fun lpW(ctx: Context, weight: Float = 1f, margin: Float = 4f) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            val m = dp(ctx, margin); setMargins(m, m, m, m)
        }

    /**
     * A grid of choice chips, [perRow] per row. [selected] is highlighted; picking one highlights it and calls [onPick].
     * Returns the view and a function to change the highlighted chip later.
     */
    fun chips(ctx: Context, labels: List<String>, selected: Int, perRow: Int, onPick: (Int) -> Unit): Pair<View, (Int) -> Unit> {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val views = mutableListOf<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                val on = i == sel
                v.setTextColor(if (on) Color.WHITE else C.INK)
                v.background = if (on) round(C.BLUE, dp(ctx, 16f).toFloat()) else round(C.PAPER, dp(ctx, 16f).toFloat(), dp(ctx, 1f), C.LINE)
            }
        }
        var r: LinearLayout? = null
        labels.forEachIndexed { i, s ->
            if (i % perRow == 0) { r = row(ctx); col.addView(r) }
            val v = text(ctx, s, 14f, C.INK, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 4f), dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 8f))
                maxLines = 1
                setOnClickListener { paint(i); onPick(i) }
            }
            views.add(v)
            r!!.addView(v, lpW(ctx, 1f, 3f))
        }
        // keep the last row's chips the same width as the others
        val rest = (perRow - labels.size % perRow) % perRow
        repeat(rest) { r!!.addView(View(ctx), lpW(ctx, 1f, 3f)) }
        paint(selected)
        return col to { sel: Int -> paint(sel) }
    }
}

/** Dialogs that work both in the app and over other apps (from the accessibility service). */
object Dlg {
    fun themed(ctx: Context): Context = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    fun show(d: AlertDialog, overlay: Boolean): AlertDialog {
        if (overlay) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
        return d
    }

    fun info(ctx: Context, overlay: Boolean, title: String, msg: String) {
        show(AlertDialog.Builder(themed(ctx)).setTitle(title).setMessage(msg).setPositiveButton("知道了", null).create(), overlay)
    }

    fun confirm(ctx: Context, overlay: Boolean, title: String, msg: String, ok: String, onOk: () -> Unit) {
        show(AlertDialog.Builder(themed(ctx)).setTitle(title).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }.setNegativeButton("取消", null).create(), overlay)
    }

    fun textInput(ctx: Context, overlay: Boolean, title: String, initial: String, onOk: (String) -> Unit) {
        val t = themed(ctx)
        val e = EditText(t).apply { setText(initial); setSelection(initial.length); setSingleLine() }
        val box = LinearLayout(t).apply { val p = Ui.dp(t, 20f); setPadding(p, Ui.dp(t, 8f), p, 0); addView(e, LinearLayout.LayoutParams(-1, -2)) }
        show(AlertDialog.Builder(t).setTitle(title).setView(box)
            .setPositiveButton("确定") { _, _ -> onOk(e.text.toString().trim()) }.setNegativeButton("取消", null).create(), overlay)
    }

    fun intInput(ctx: Context, overlay: Boolean, title: String, initial: Int, min: Int, max: Int, onOk: (Int) -> Unit) {
        val t = themed(ctx)
        val e = EditText(t).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(initial.toString()); setSelection(text.length) }
        val box = LinearLayout(t).apply { val p = Ui.dp(t, 20f); setPadding(p, Ui.dp(t, 8f), p, 0); addView(e, LinearLayout.LayoutParams(-1, -2)) }
        show(AlertDialog.Builder(t).setTitle(title).setView(box)
            .setPositiveButton("确定") { _, _ -> e.text.toString().toIntOrNull()?.let { onOk(it.coerceIn(min, max)) } }
            .setNegativeButton("取消", null).create(), overlay)
    }

    /** a time length typed as a number plus a unit (毫秒 / 秒 / 分钟) */
    fun duration(ctx: Context, overlay: Boolean, title: String, initialMs: Long, minMs: Long, maxMs: Long, onOk: (Long) -> Unit) {
        val t = themed(ctx)
        val units = listOf("毫秒" to 1L, "秒" to 1000L, "分钟" to 60_000L)
        var unit = when {
            initialMs >= 60_000L && initialMs % 60_000L == 0L -> 2
            initialMs >= 1000L || initialMs == 0L -> 1
            else -> 0
        }
        fun shown(ms: Long, u: Int): String {
            val v = ms.toDouble() / units[u].second
            return if (v == Math.floor(v)) v.toLong().toString() else String.format("%.3f", v).trimEnd('0').trimEnd('.')
        }
        val e = EditText(t).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(shown(initialMs, unit)); setSelection(text.length)
        }
        val box = LinearLayout(t).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(t, 20f); setPadding(p, Ui.dp(t, 8f), p, 0)
            addView(e, LinearLayout.LayoutParams(-1, -2))
        }
        val (chips, _) = Ui.chips(t, units.map { it.first }, unit, 3) { u -> unit = u }   // the typed number stays, only the unit changes
        box.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(t, 8f) })
        box.addView(Ui.text(t, "范围：${Fmt.dur(minMs)} ～ ${Fmt.dur(maxMs)}", 12f, C.MUTED).apply { setPadding(0, Ui.dp(t, 6f), 0, 0) })
        show(AlertDialog.Builder(t).setTitle(title).setView(box)
            .setPositiveButton("确定") { _, _ ->
                val v = e.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                onOk((v * units[unit].second).roundToLong().coerceIn(minMs, maxMs))
            }
            .setNegativeButton("取消", null).create(), overlay)
    }
}
