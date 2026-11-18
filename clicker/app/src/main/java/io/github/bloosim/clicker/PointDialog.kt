package io.github.bloosim.clicker

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Edits one step of the current scheme. Works in the app and over other apps ([overlay] = from the floating window). */
object PointDialog {
    fun show(ctx: Context, overlay: Boolean, index: Int) {
        val s = Store.cur
        if (index !in s.pts.indices) return
        val pt = s.pts[index].copy()
        val t = Dlg.themed(ctx)
        fun dp(v: Float) = Ui.dp(t, v)
        val box = LinearLayout(t).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), dp(4f)) }
        fun label(s0: String): TextView = Ui.text(t, s0, 14f, C.INK, true).apply { setPadding(0, dp(12f), 0, dp(4f)) }
        fun hint(s0: String): TextView = Ui.text(t, s0, 12f, C.MUTED)

        // ---- what to do
        box.addView(label("动作"))
        val holdLabel = label("")
        lateinit var holdChips: Pair<View, (Int) -> Unit>
        val holdBox = LinearLayout(t).apply { orientation = LinearLayout.VERTICAL }
        val endBox = LinearLayout(t).apply { orientation = LinearLayout.VERTICAL }

        fun holdList() = if (pt.swipe) Presets.SWIPE_TIME else Presets.TAP_HOLD
        fun holdText() = (if (pt.swipe) "滑动用时：" else "按住时间：") + Fmt.dur(pt.holdMs) + (if (!pt.swipe && pt.holdMs >= 500) "（长按）" else "")
        fun buildHold() {
            holdBox.removeAllViews()
            holdLabel.text = holdText()
            val list = holdList()
            holdChips = Ui.chips(t, list.map { Fmt.dur(it) } + "自定义…", list.indexOf(pt.holdMs).let { if (it < 0) list.size else it }, 4) { i ->
                if (i < list.size) { pt.holdMs = list[i]; holdLabel.text = holdText() }
                else Dlg.duration(ctx, overlay, if (pt.swipe) "滑动用时" else "按住时间", pt.holdMs, 10L, 59_000L) { v ->
                    pt.holdMs = v; holdLabel.text = holdText()
                    holdChips.second(holdList().indexOf(v).let { if (it < 0) holdList().size else it })
                }
            }
            holdBox.addView(holdChips.first)
        }

        val (typeChips, _) = Ui.chips(t, listOf("点击", "滑动"), if (pt.swipe) 1 else 0, 2) { i ->
            val want = if (i == 1) "swipe" else "tap"
            if (want != pt.type) {
                pt.type = want
                pt.holdMs = if (pt.swipe) 400 else 60
                if (pt.swipe && pt.x2 == pt.x && pt.y2 == pt.y) { pt.y2 = pt.y - 300 }
                buildHold()
                endBox.visibility = if (pt.swipe) View.VISIBLE else View.GONE
            }
        }
        box.addView(typeChips)

        // ---- the interval
        val ivLabel = label("")
        fun ivText() = "做完这一步后，等 " + Fmt.dur(pt.delayMs) + " 再做下一步"
        ivLabel.text = ivText()
        box.addView(ivLabel)
        val iv = Presets.INTERVALS
        lateinit var ivChips: Pair<View, (Int) -> Unit>
        ivChips = Ui.chips(t, iv.map { Fmt.dur(it) } + "自定义…", iv.indexOf(pt.delayMs).let { if (it < 0) iv.size else it }, 4) { i ->
            if (i < iv.size) { pt.delayMs = iv[i]; ivLabel.text = ivText() }
            else Dlg.duration(ctx, overlay, "间隔时间", pt.delayMs, 0L, 24 * 3_600_000L) { v ->
                pt.delayMs = v; ivLabel.text = ivText()
                ivChips.second(iv.indexOf(v).let { if (it < 0) iv.size else it })
            }
        }
        box.addView(ivChips.first)

        // ---- how long the finger is down
        box.addView(holdLabel)
        box.addView(holdBox)
        buildHold()

        // ---- position
        box.addView(label("位置（像素）"))
        box.addView(hint("也可以在悬浮窗里直接拖动带编号的圆点"))
        fun num(v: Int): EditText = EditText(t).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()); setSingleLine() }
        fun pair(a: EditText, b: EditText, title: String): LinearLayout = Ui.row(t).apply {
            addView(Ui.text(t, title, 14f, C.MUTED), LinearLayout.LayoutParams(dp(56f), -2))
            addView(Ui.text(t, "X", 13f, C.MUTED)); addView(a, Ui.lpW(t, 1f, 2f))
            addView(Ui.text(t, "Y", 13f, C.MUTED)); addView(b, Ui.lpW(t, 1f, 2f))
        }
        val ex = num(pt.x); val ey = num(pt.y)
        val ex2 = num(pt.x2); val ey2 = num(pt.y2)
        box.addView(pair(ex, ey, if (pt.swipe) "起点" else "位置"))
        endBox.addView(pair(ex2, ey2, "终点"))
        endBox.visibility = if (pt.swipe) View.VISIBLE else View.GONE
        box.addView(endBox)

        val sv = ScrollView(t).apply { addView(box) }
        val d = AlertDialog.Builder(t)
            .setTitle("第 ${index + 1} 步")
            .setView(sv)
            .setPositiveButton("保存") { _, _ ->
                ex.text.toString().toIntOrNull()?.let { pt.x = it.coerceAtLeast(0) }
                ey.text.toString().toIntOrNull()?.let { pt.y = it.coerceAtLeast(0) }
                ex2.text.toString().toIntOrNull()?.let { pt.x2 = it.coerceAtLeast(0) }
                ey2.text.toString().toIntOrNull()?.let { pt.y2 = it.coerceAtLeast(0) }
                val list = Store.cur.pts
                if (index in list.indices) { list[index] = pt; Store.changed() }
            }
            .setNeutralButton("删除这一步") { _, _ ->
                val list = Store.cur.pts
                if (index in list.indices) { list.removeAt(index); Store.changed() }
            }
            .setNegativeButton("取消", null)
            .create()
        Dlg.show(d, overlay)
    }
}
