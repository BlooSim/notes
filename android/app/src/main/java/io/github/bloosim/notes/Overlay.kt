package io.github.bloosim.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Floating caption window over other apps: the latest whole segment, like song lyrics. */
class Overlay(private val svc: ListenService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: Card? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var head: LinearLayout
    private lateinit var tvNo: TextView
    private lateinit var tvState: TextView
    private lateinit var tvZh: TextView
    private lateinit var tvSrc: TextView
    private lateinit var tvLive: TextView
    private lateinit var btnHold: TextView
    private lateinit var btnRun: TextView
    private var big = true
    private val update: () -> Unit = { refresh() }

    private fun dp(v: Float) = Ui.dp(svc, v)

    /** The card can be dragged from anywhere except the hold button; a tap on its top row folds it. */
    @SuppressLint("ViewConstructor")
    private inner class Card(ctx: Context) : LinearLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var sx = 0f
        private var sy = 0f
        private var ox = 0
        private var oy = 0
        private var dragging = false
        private var blocked = false

        private fun on(v: View, ev: MotionEvent): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
        }

        private fun begin(ev: MotionEvent) {
            sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y
            dragging = false
            blocked = on(btnHold, ev)
        }

        private fun far(ev: MotionEvent) = abs(ev.rawX - sx) > slop || abs(ev.rawY - sy) > slop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> if (!blocked && !dragging && far(ev)) dragging = true
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && far(ev)) dragging = true
                    if (dragging) {
                        lp.x = ox + (ev.rawX - sx).toInt()
                        lp.y = oy + (ev.rawY - sy).toInt()
                        try { wm.updateViewLayout(this, lp) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) { keepOnScreen(); Prefs.overlayX = lp.x; Prefs.overlayY = lp.y }
                    else if (ev.actionMasked == MotionEvent.ACTION_UP && on(head, ev)) { big = !big; refresh() }
                    dragging = false
                }
            }
            return true
        }
    }

    private fun keepOnScreen() {
        val dm = svc.resources.displayMetrics
        val w = lp.width
        val h = root?.height ?: 0
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - w))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - maxOf(h, dp(80f))))
        try { root?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) { }
    }

    fun show() {
        if (root != null) return
        val ink = Color.parseColor("#F5F1E8"); val muted = Color.parseColor("#A39E93"); val accent = Color.parseColor("#E2A43A")
        val r = Card(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Color.parseColor("#EE17150F"), dp(16f).toFloat())
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        head = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvNo = Ui.text(svc, "#–", 13f, accent, true)
        tvState = Ui.text(svc, "", 12f, muted).apply { gravity = Gravity.END }
        val close = Ui.text(svc, "✕", 16f, muted).apply { setPadding(dp(14f), dp(2f), dp(2f), dp(2f)) }
        head.addView(tvNo)
        head.addView(tvState, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(close)
        r.addView(head)

        tvZh = Ui.text(svc, "还没有内容", 19f, ink, true).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(4f), 0, 0); ellipsize = TextUtils.TruncateAt.END }
        tvSrc = Ui.text(svc, "", 12f, muted).apply { setPadding(0, dp(4f), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        r.addView(tvZh); r.addView(tvSrc)

        tvLive = Ui.text(svc, "", 13f, Color.parseColor("#C9A35B")).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6f), 0, 0) }
        r.addView(tvLive)

        val btns = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8f), 0, 0) }
        btnHold = Ui.text(svc, "🎤 按住", 14f, Color.parseColor("#1A1406"), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = Ui.round(accent, dp(20f).toFloat())
        }
        btnRun = Ui.text(svc, "", 14f, ink, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = Ui.round(Color.parseColor("#33FFFFFF"), dp(20f).toFloat())
        }
        btns.addView(btnRun)
        btns.addView(View(svc), LinearLayout.LayoutParams(dp(8f), 1))
        btns.addView(btnHold)
        r.addView(btns)

        val dm = svc.resources.displayMetrics
        lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (Prefs.overlayX >= 0) Prefs.overlayX else (dm.widthPixels * 0.04f).toInt()
            y = if (Prefs.overlayY >= 0) Prefs.overlayY else (dm.heightPixels * 0.55f).toInt()
        }
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - dp(120f)))

        close.setOnClickListener { svc.hideOverlay() }
        btnRun.setOnClickListener { ListenService.send(svc, ListenService.ACT_TOGGLE) }
        btnHold.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> ListenService.send(svc, ListenService.ACT_HOLD_DOWN)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ListenService.send(svc, ListenService.ACT_HOLD_UP)
            }
            true
        }

        root = r
        wm.addView(r, lp)
        Hub.listen(update)
        refresh()
    }

    fun hide() {
        Hub.unlisten(update)
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
    }

    /** sets text only when it changed, so the window is not laid out again for nothing */
    private fun put(tv: TextView, t: String) { if (tv.text.toString() != t) tv.text = t }
    private fun vis(v: View, on: Boolean) { val want = if (on) View.VISIBLE else View.GONE; if (v.visibility != want) v.visibility = want }

    /** long text shows its end, where the newest words are */
    private fun tail(t: String, n: Int) = if (t.length > n) "…" + t.takeLast(n) else t

    private fun refresh() {
        if (root == null) return
        val last = Store.current.entries.lastOrNull()
        put(tvNo, if (last != null) "#${last.n}" else "#–")
        put(tvState, when {
            Hub.holding -> "● 按住中"
            Hub.running -> "● 记录中"
            else -> "已暂停"
        })
        val zh = when {
            last == null -> "还没有内容"
            last.zh.isNotBlank() -> last.zh
            last.st == "asr" -> "识别中…"
            last.st == "err" -> "翻译没成功"
            else -> "翻译中…"
        }
        tvZh.maxLines = if (big) 8 else 2
        put(tvZh, tail(zh, if (big) 150 else 34))
        put(tvSrc, tail(last?.src ?: "", 90))
        vis(tvSrc, Prefs.showSrc && big && !last?.src.isNullOrBlank())
        val live = when {
            Hub.liveZh.isNotBlank() -> "正在听：" + tail(Hub.liveZh, 60)
            Hub.live.isNotBlank() -> "正在听：" + tail(Hub.live, 60)
            Hub.liveLabel.isNotBlank() -> Hub.liveLabel
            else -> ""
        }
        put(tvLive, live)
        vis(tvLive, live.isNotBlank())
        put(btnRun, if (Hub.running) "⏸ 暂停" else "▶ 连续")
        put(btnHold, if (Hub.holding) "🎤 松开结束" else "🎤 按住")
    }
}
