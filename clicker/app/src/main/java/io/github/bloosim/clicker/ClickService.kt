package io.github.bloosim.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The accessibility service does the actual tapping (dispatchGesture), and shows the floating control panel and the
 * numbered markers over other apps (accessibility overlay windows, so no extra "display over other apps" permission).
 */
class ClickService : AccessibilityService() {
    companion object {
        var me: ClickService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var btnRun: TextView? = null
    private var btnEye: TextView? = null
    private var status: TextView? = null
    private val editButtons = mutableListOf<TextView>()
    private val marks = mutableListOf<Mark>()
    var markersShown = true
        private set
    val runner = Runner(this)
    val panelShown: Boolean get() = panel != null

    private val onStore: () -> Unit = {
        if (panel != null && !runner.running) rebuildMarkers()
        updatePanel()
    }

    fun dp(v: Float) = Ui.dp(this, v)
    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Store.init(this)
        Store.listen(onStore)
        me = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** volume-down = emergency stop while running */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (runner.running && Opt.volStop && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) runner.stop("已按音量下键，停止连点")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        runner.stop(null)
        closePanel()
        Store.unlisten(onStore)
        if (me === this) me = null
    }

    private fun overlayLp(w: Int, hgt: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, hgt, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x; p.y = y
        if (Build.VERSION.SDK_INT >= 30) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return p
    }

    // ------------------------------------------------------------------ floating panel

    @SuppressLint("ClickableViewAccessibility")
    fun showPanel() {
        if (panel != null) { markersShown = true; rebuildMarkers(); updatePanel(); return }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(Color.parseColor("#EE1F2937"), dp(16f).toFloat())
            setPadding(dp(4f), dp(4f), dp(4f), dp(6f))
        }
        fun glyph(s: String, sp: Float = 19f, onClick: () -> Unit): TextView = Ui.text(this, s, sp, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
        val size = dp(44f)
        val handle = glyph("⋮⋮", 14f) { }.apply { setTextColor(Color.parseColor("#9CA3AF")) }
        col.addView(handle, LinearLayout.LayoutParams(size, dp(24f)))
        btnRun = glyph("▶", 22f) { toggleRun() }
        col.addView(btnRun, LinearLayout.LayoutParams(size, size))
        editButtons.clear()
        val add = glyph("＋") { addPoint(false) }
        val addSwipe = glyph("⇅") { addPoint(true) }
        val undo = glyph("－") { removeLast() }
        btnEye = glyph("◉") { toggleMarkers() }
        val gear = glyph("⚙") { openApp() }
        editButtons.addAll(listOf(add, addSwipe, undo, gear))
        for (v in listOf(add, addSwipe, undo, btnEye!!, gear)) col.addView(v, LinearLayout.LayoutParams(size, size))
        col.addView(glyph("✕", 17f) { closePanel() }, LinearLayout.LayoutParams(size, dp(36f)))
        status = Ui.text(this, "", 10f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 3; setLineSpacing(0f, 1.0f) }
        col.addView(status, LinearLayout.LayoutParams(dp(52f), LinearLayout.LayoutParams.WRAP_CONTENT))

        val lp = overlayLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Opt.panelX, Opt.panelY)
        // drag the panel by its top handle or by the status text
        val drag = object : View.OnTouchListener {
            var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt()
                        try { wm.updateViewLayout(col, lp) } catch (_: Exception) { }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { Opt.panelX = lp.x; Opt.panelY = lp.y }
                }
                return true
            }
        }
        handle.setOnTouchListener(drag)
        status!!.setOnTouchListener(drag)
        try { wm.addView(col, lp) } catch (e: Exception) { toast("显示悬浮窗失败：" + e.message); return }
        panel = col
        panelLp = lp
        markersShown = true
        rebuildMarkers()
        updatePanel()
    }

    fun closePanel() {
        runner.stop(null)
        removeMarkers()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null; panelLp = null; btnRun = null; btnEye = null; status = null
        editButtons.clear()
    }

    private fun toggleRun() {
        if (runner.running) { runner.stop("已停止"); return }
        val err = runner.start()
        if (err != null) toast(err)
    }

    private fun addPoint(swipe: Boolean) {
        if (runner.running) return
        val s = Store.cur
        val sz = Screen.size(this)
        val last = s.pts.lastOrNull()
        // place the new marker a little below the last one, so they do not cover each other
        val bx = last?.x ?: (sz.x / 2)
        val by = ((last?.y ?: (sz.y / 2 - dp(80f))) + dp(80f)).let { if (it > sz.y - dp(120f)) dp(200f) else it }
        val delay = last?.delayMs ?: 1000L
        s.pts.add(if (swipe) Pt.swipe(bx, by, delay) else Pt.tap(bx, by, delay))
        markersShown = true
        Store.changed()
        toast(if (swipe) "已添加第 ${s.pts.size} 步（滑动）：拖动两个圆点设置起点和终点" else "已添加第 ${s.pts.size} 步：把圆点拖到要点的位置")
    }

    private fun removeLast() {
        if (runner.running) return
        val s = Store.cur
        if (s.pts.isEmpty()) return
        s.pts.removeAt(s.pts.size - 1)
        Store.changed()
    }

    private fun toggleMarkers() {
        if (runner.running) return
        markersShown = !markersShown
        rebuildMarkers()
        updatePanel()
    }

    private fun openApp() {
        if (runner.running) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    fun updatePanel() {
        val run = runner.running
        btnRun?.apply { text = if (run) "■" else "▶"; setTextColor(if (run) Color.parseColor("#FF6B6B") else Color.parseColor("#4ADE80")) }
        btnEye?.apply { text = if (markersShown) "◉" else "○"; alpha = if (run) 0.35f else 1f }
        editButtons.forEach { it.alpha = if (run) 0.35f else 1f }
        status?.text = statusText()
    }

    private fun statusText(): String {
        val s = runner.scheme ?: Store.cur
        if (!runner.running) return "${s.pts.size}步\n" + (if (s.pts.isEmpty()) "点＋添加" else "▶开始")
        val left = max(0L, runner.nextAt - SystemClock.elapsedRealtime())
        fun secs(ms: Long) = if (ms >= 10_000L) "${ceil(ms / 1000.0).toLong()}秒" else String.format("%.1f秒", ms / 1000.0)
        return when (runner.phase) {
            "wait" -> if (runner.waitClock.isNotEmpty() && left > 10_000L) "等到\n${runner.waitClock}\n${Fmt.total(left)}" else "准备\n${secs(left)}"
            "click" -> "第${runner.round + 1}轮\n${runner.idx + 1}/${s.pts.size}\n点击"
            else -> "第${runner.round + 1}轮\n${runner.idx + 1}/${s.pts.size}\n${secs(left)}"
        }
    }

    /** called by the runner when it starts or stops */
    fun onRunState(running: Boolean) {
        val lp = panelLp
        val p = panel
        if (lp != null && p != null) {
            lp.flags = if (running) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            try { wm.updateViewLayout(p, lp) } catch (_: Exception) { }
        }
        if (running) {
            // markers must not catch the taps
            for (m in marks) {
                if (Opt.hideRun) m.visibility = View.GONE
                m.lp.flags = m.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                m.alpha = 0.45f
                try { wm.updateViewLayout(m, m.lp) } catch (_: Exception) { }
            }
        } else rebuildMarkers()
        updatePanel()
    }

    /** called by the runner after each step and a few times a second */
    fun onProgress() {
        updatePanel()
        val active = runner.idx
        for (m in marks) { val a = m.idx == active; if (m.active != a) { m.active = a; m.invalidate() } }
    }

    // ------------------------------------------------------------------ markers

    private fun removeMarkers() {
        for (m in marks) try { wm.removeView(m) } catch (_: Exception) { }
        marks.clear()
    }

    fun rebuildMarkers() {
        removeMarkers()
        if (panel == null || !markersShown) return
        val s = Store.cur
        s.pts.forEachIndexed { i, p ->
            addMark(i, false, p.x, p.y)
            if (p.swipe) addMark(i, true, p.x2, p.y2)
        }
    }

    private fun addMark(i: Int, end: Boolean, cx: Int, cy: Int) {
        val m = Mark(i, end)
        m.lp.x = cx - m.size / 2
        m.lp.y = cy - m.size / 2
        try { wm.addView(m, m.lp); marks.add(m) } catch (_: Exception) { }
    }

    /** a numbered circle that marks where a step taps; drag to move, tap to edit */
    @SuppressLint("ViewConstructor")
    inner class Mark(val idx: Int, val end: Boolean) : View(this@ClickService) {
        val size = dp(46f)
        val lp = overlayLp(size, size, 0, 0)
        var active = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f).toFloat() }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var sx = 0; private var sy = 0; private var moved = false

        override fun onDraw(c: Canvas) {
            val r = size / 2f
            val base = if (active) C.ORANGE else C.BLUE
            if (end) {
                fill.color = Color.argb(70, Color.red(base), Color.green(base), Color.blue(base))
                c.drawCircle(r, r, r - dp(2f), fill)
                ring.color = base
                c.drawCircle(r, r, r - dp(3f), ring)
            } else {
                fill.color = Color.argb(205, Color.red(base), Color.green(base), Color.blue(base))
                c.drawCircle(r, r, r - dp(2f), fill)
                ring.color = Color.WHITE
                c.drawCircle(r, r, r - dp(3f), ring)
                // a small dot at the exact tap point
                fill.color = Color.WHITE
                c.drawCircle(r, r, dp(2f).toFloat(), fill)
            }
            txt.color = if (end) base else Color.WHITE
            txt.textSize = dp(if (end) 12f else 13f).toFloat()
            val label = if (end) "${idx + 1}终" else "${idx + 1}"
            c.drawText(label, r, r - dp(5f), txt)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (runner.running) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                        try { wm.updateViewLayout(this, lp) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) savePosition()
                    else PointDialog.show(this@ClickService, true, idx)
                }
            }
            return true
        }

        private fun savePosition() {
            post {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val cx = loc[0] + width / 2
                val cy = loc[1] + height / 2
                val list = Store.cur.pts
                if (idx in list.indices) {
                    val p = list[idx]
                    if (end) { p.x2 = cx.coerceAtLeast(0); p.y2 = cy.coerceAtLeast(0) } else { p.x = cx.coerceAtLeast(0); p.y = cy.coerceAtLeast(0) }
                    Store.changed()
                }
            }
        }
    }

    // ------------------------------------------------------------------ tapping

    private val rnd = java.util.Random()

    private fun panelRect(): Rect? {
        val p = panel ?: return null
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
    }

    private fun setPanelTouchable(on: Boolean) {
        val lp = panelLp ?: return
        val p = panel ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(p, lp) } catch (_: Exception) { }
    }

    /** Taps or swipes [p]. [done] runs on the main thread when the gesture has finished. False if it could not start. */
    fun perform(p: Pt, offset: Int, done: () -> Unit): Boolean {
        val ox = if (offset > 0) rnd.nextInt(offset * 2 + 1) - offset else 0
        val oy = if (offset > 0) rnd.nextInt(offset * 2 + 1) - offset else 0
        val x1 = (p.x + ox).coerceAtLeast(0).toFloat(); val y1 = (p.y + oy).coerceAtLeast(0).toFloat()
        val x2 = (p.x2 + ox).coerceAtLeast(0).toFloat(); val y2 = (p.y2 + oy).coerceAtLeast(0).toFloat()
        val path = Path().apply { moveTo(x1, y1); if (p.swipe) lineTo(x2, y2) }
        val dur = if (p.swipe) p.holdMs.coerceIn(50L, 59_000L) else p.holdMs.coerceIn(1L, 59_000L)
        val g = try {
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        } catch (e: Exception) { return false }
        // a step under the floating panel: let the tap go through the panel for this moment
        val pr = panelRect()
        val shield = pr != null && (pr.contains(x1.toInt(), y1.toInt()) || (p.swipe && pr.contains(x2.toInt(), y2.toInt())))
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { h.post { if (shield) setPanelTouchable(true); done() } }
            override fun onCancelled(gestureDescription: GestureDescription?) { h.post { if (shield) setPanelTouchable(true); done() } }
        }
        if (shield) {
            setPanelTouchable(false)
            h.postDelayed({ if (!dispatchGesture(g, cb, null)) { setPanelTouchable(true); runner.stop("点击没有成功：请把「连点器」无障碍服务关掉再重新打开") } }, 60)
            return true
        }
        return dispatchGesture(g, cb, null)
    }
}

/** Runs the steps of a scheme in order, with the interval of each step, until a stop condition. Main thread. */
class Runner(private val svc: ClickService) {
    private val h = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()
    var running = false
        private set
    var scheme: Scheme? = null
        private set
    var idx = 0
        private set
    var round = 0
        private set
    var clicks = 0L
        private set
    /** "wait" before the first step, "click" while a gesture runs, "gap" while waiting between steps */
    var phase = ""
        private set
    var nextAt = 0L
        private set
    var waitClock = ""
        private set
    private var startedAt = 0L

    private fun now() = SystemClock.elapsedRealtime()

    /** null = started; otherwise the reason it cannot start */
    fun start(): String? {
        if (running) return null
        val s = Store.cur.copy()
        if (s.pts.isEmpty()) return "这个方案还没有步骤，先点 ＋ 添加"
        scheme = s
        running = true
        idx = 0; round = 0; clicks = 0
        var wait = s.startDelaySec * 1000L
        waitClock = ""
        if (s.startClock.isNotBlank()) {
            val w = untilClock(s.startClock)
            if (w != null) { wait = max(wait, w); waitClock = s.startClock }
        }
        phase = "wait"
        nextAt = now() + wait
        startedAt = nextAt
        h.postDelayed(step, wait)
        h.post(tick)
        svc.onRunState(true)
        if (waitClock.isNotEmpty()) svc.toast("将在 $waitClock 开始")
        return null
    }

    fun stop(reason: String?) {
        if (!running) return
        running = false
        h.removeCallbacks(step)
        h.removeCallbacks(tick)
        phase = ""
        svc.onRunState(false)
        if (reason != null) svc.toast(reason + "（共点了 $clicks 下）")
    }

    private val step = Runnable { doStep() }

    private fun doStep() {
        if (!running) return
        val s = scheme ?: return
        if (s.repeat == "minutes" && now() - startedAt >= s.minutes * 60_000L) { stop("已运行 ${s.minutes} 分钟，自动停止"); return }
        phase = "click"
        svc.onProgress()
        val ok = svc.perform(s.pts[idx], s.offsetPx) { after() }
        if (!ok) stop("点击没有成功：请把「连点器」无障碍服务关掉再重新打开")
    }

    private fun after() {
        if (!running) return
        val s = scheme ?: return
        val p = s.pts[idx]
        clicks++
        idx++
        if (idx >= s.pts.size) {
            idx = 0
            round++
            if (s.repeat == "rounds" && round >= s.rounds) { stop("已完成 ${s.rounds} 轮，自动停止"); return }
        }
        var d = p.delayMs
        if (s.jitterPct > 0 && d > 0) {
            val span = d * s.jitterPct / 100.0
            d = (d + (rnd.nextDouble() * 2 - 1) * span).toLong()
        }
        d = max(0L, d)
        phase = "gap"
        nextAt = now() + d
        h.postDelayed(step, d)
        svc.onProgress()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            svc.onProgress()
            h.postDelayed(this, 200)
        }
    }

    /** ms until the next HH:mm (today or tomorrow) */
    fun untilClock(hm: String): Long? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hm.trim()) ?: return null
        val hh = m.groupValues[1].toInt(); val mm = m.groupValues[2].toInt()
        if (hh > 23 || mm > 59) return null
        val c = Calendar.getInstance()
        val nowMs = c.timeInMillis
        c.set(Calendar.HOUR_OF_DAY, hh); c.set(Calendar.MINUTE, mm); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis - nowMs
    }
}
