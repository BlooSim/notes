package io.github.bloosim.notes

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Floating caption window over other apps: the latest whole segment, like song lyrics. */
class Overlay(private val svc: ListenService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var tvNo: TextView
    private lateinit var tvState: TextView
    private lateinit var tvZh: TextView
    private lateinit var tvSrc: TextView
    private lateinit var tvLive: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnHold: TextView
    private lateinit var btnRun: TextView
    private var big = true
    private val update: () -> Unit = { refresh() }

    private fun dp(v: Float) = Ui.dp(svc, v)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val ink = Color.parseColor("#F5F1E8"); val muted = Color.parseColor("#A39E93"); val accent = Color.parseColor("#E2A43A")
        val r = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Color.parseColor("#EE17150F"), dp(16f).toFloat())
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        val head = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvNo = Ui.text(svc, "#–", 13f, accent, true)
        tvState = Ui.text(svc, "", 12f, muted).apply { gravity = Gravity.END }
        val close = Ui.text(svc, "  ✕", 15f, muted).apply { setPadding(dp(8f), 0, 0, 0) }
        head.addView(tvNo)
        head.addView(tvState, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(close)
        r.addView(head)

        val body = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        tvZh = Ui.text(svc, "还没有内容", 19f, ink, true).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(4f), 0, 0) }
        tvSrc = Ui.text(svc, "", 12f, muted).apply { setPadding(0, dp(4f), 0, 0) }
        body.addView(tvZh); body.addView(tvSrc)
        scroll = ScrollView(svc).apply { addView(body); isVerticalScrollBarEnabled = false }
        r.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        tvLive = Ui.text(svc, "", 12f, Color.parseColor("#C9A35B")).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.START; setPadding(0, dp(6f), 0, 0)
        }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (Prefs.overlayX >= 0) Prefs.overlayX else (dm.widthPixels * 0.04f).toInt()
            y = if (Prefs.overlayY >= 0) Prefs.overlayY else (dm.heightPixels * 0.55f).toInt()
        }

        // drag by the top row
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        head.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt()
                    try { wm.updateViewLayout(r, lp) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.overlayX = lp.x; Prefs.overlayY = lp.y
                    if (abs(ev.rawX - sx) < 10 && abs(ev.rawY - sy) < 10) { big = !big; refresh() } // a tap folds it
                    true
                }
                else -> true
            }
        }
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

    private fun refresh() {
        if (root == null) return
        val last = Store.current.entries.lastOrNull()
        tvNo.text = if (last != null) "#${last.n}" else "#–"
        tvState.text = when {
            Hub.holding -> "● 按住中"
            Hub.running -> "● 记录中"
            else -> "已暂停"
        }
        tvZh.text = when {
            last == null -> "还没有内容"
            last.zh.isNotBlank() -> last.zh
            last.st == "asr" -> "识别中…"
            else -> "翻译中…"
        }
        tvSrc.text = last?.src ?: ""
        tvSrc.visibility = if (Prefs.showSrc && !tvSrc.text.isNullOrBlank() && big) View.VISIBLE else View.GONE
        tvZh.maxLines = if (big) 12 else 2
        tvZh.ellipsize = if (big) null else TextUtils.TruncateAt.START
        val live = Hub.live
        tvLive.text = when {
            live.isNotBlank() -> "正在听：$live"
            Hub.liveLabel.isNotBlank() -> Hub.liveLabel
            else -> ""
        }
        tvLive.visibility = if (tvLive.text.isNullOrBlank()) View.GONE else View.VISIBLE
        btnRun.text = if (Hub.running) "⏸ 暂停" else "▶ 连续"
        btnHold.text = if (Hub.holding) "🎤 松开结束" else "🎤 按住"
        // keep long segments readable: cap the height and show the end
        val maxH = (svc.resources.displayMetrics.heightPixels * (if (big) 0.32f else 0.12f)).toInt()
        val lpS = scroll.layoutParams
        scroll.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        lpS.height = if (scroll.measuredHeight > maxH) maxH else LinearLayout.LayoutParams.WRAP_CONTENT
        scroll.layoutParams = lpS
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
