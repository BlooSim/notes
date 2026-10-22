package io.github.bloosim.notes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.IdentityHashMap

class MainActivity : Activity() {
    private lateinit var p: Pal
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var empty: TextView
    private lateinit var liveRow: Ui.Row
    private lateinit var chip: TextView
    private lateinit var holdBtn: TextView
    private lateinit var fab: TextView
    private lateinit var pipBtn: TextView
    private val rows = IdentityHashMap<Entry, Ui.Row>()
    private var shownNote: Note? = null
    private val update: () -> Unit = { render() }

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Store.init(this)
        p = Ui.pal(this)
        window.statusBarColor = p.paper
        window.navigationBarColor = p.paper
        @Suppress("DEPRECATION")
        if (!p.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(build())
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        p = Ui.pal(this)
        Hub.listen(update)
        render(true)
    }

    override fun onPause() {
        Hub.unlisten(update)
        super.onPause()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(p.paper) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // header: title, count, which service is answering; buttons on the right
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(8f), dp(8f))
        }
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTitle = Ui.text(this, "", 18f, p.ink, true).apply { maxLines = 1 }
        tvMeta = Ui.text(this, "", 12f, p.muted).apply { maxLines = 2 }
        tcol.addView(tvTitle); tcol.addView(tvMeta)
        head.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        pipBtn = headBtn("浮窗") { toggleOverlay() }
        head.addView(pipBtn)
        head.addView(headBtn("复制") { copyAll() })
        head.addView(headBtn("⋯") { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(head)
        tvTitle.setOnClickListener { rename() }

        scroll = ScrollView(this).apply { isFillViewport = true }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), dp(4f), dp(16f), dp(150f)) }
        empty = Ui.text(
            this,
            "点右下角的 ✎ 开始连续翻译：对方每说完一段，这里就多一条带编号的中文，下面的小字是原文。\n\n" +
                "也可以按住「按住」按钮：对方开始说话时按下，说完松开，这一整段变成一条翻译。\n\n" +
                "右上角「浮窗」可以把字幕浮在其他应用上面；切到后台或锁屏也会继续听写。\n\n" +
                "大模型识别更准：在「⋯」设置里填一个免费的 Gemini Key。",
            15f, p.muted
        ).apply { setLineSpacing(0f, 1.3f); setPadding(dp(4f), dp(16f), 0, 0) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveRow = Ui.Row(this, p)
        inner.addView(empty); inner.addView(list)
        inner.addView(liveRow.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        scroll.addView(inner)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // bottom right: language, hold to talk, start/stop
        val dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chip = Ui.pill(this, "日", p).apply { setOnClickListener { toggleLang() } }
        holdBtn = Ui.pill(this, "🎤 按住", p).apply { setPadding(dp(16f), dp(13f), dp(16f), dp(13f)) }
        fab = Ui.text(this, "✎", 24f, p.onAccent, true).apply {
            gravity = Gravity.CENTER
            background = Ui.round(p.accent, dp(28f).toFloat())
            elevation = dp(4f).toFloat()
            setOnClickListener { toggleRun() }
        }
        dock.addView(chip)
        dock.addView(View(this), LinearLayout.LayoutParams(dp(10f), 1))
        dock.addView(holdBtn)
        dock.addView(View(this), LinearLayout.LayoutParams(dp(10f), 1))
        dock.addView(fab, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18f), dp(22f)) })

        holdBtn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hasMic()) askPermissions() else ListenService.send(this, ListenService.ACT_HOLD_DOWN)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ListenService.send(this, ListenService.ACT_HOLD_UP)
            }
            true
        }
        return root
    }

    private fun headBtn(t: String, on: () -> Unit) = Ui.text(this, t, 14f, p.ink).apply {
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        setOnClickListener { on() }
    }

    private fun toggleRun() {
        if (Hub.running) { ListenService.send(this, ListenService.ACT_STOP); return }
        if (!hasMic()) { askPermissions(); return }
        if (Prefs.engine == "llm" && !Cloud.anyKey()) toast("还没有填写大模型 Key，先用手机识别")
        ListenService.send(this, ListenService.ACT_START)
    }

    private fun toggleLang() {
        Prefs.lang = if (Prefs.lang == "ja") "en" else "ja"
        toast("对方的语言：" + Prefs.langName)
        ListenService.send(this, ListenService.ACT_LANG)
        render()
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请允许「笔记」显示在其他应用上层，然后返回")
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            return
        }
        if (!hasMic()) { askPermissions(); return }
        ListenService.send(this, ListenService.ACT_OVERLAY)
    }

    private fun copyAll() {
        val n = Store.current
        if (n.entries.isEmpty()) { toast("还没有内容"); return }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("笔记", n.asText()))
        toast("已复制全部内容")
    }

    private fun rename() {
        val n = Store.current
        val et = EditText(this).apply { setText(n.title); hint = n.displayTitle(); setSingleLine() }
        AlertDialog.Builder(this).setTitle("笔记标题").setView(et)
            .setPositiveButton("保存") { _, _ -> n.title = et.text.toString().trim(); Store.save(); render() }
            .setNegativeButton("取消", null).show()
    }

    private fun retry(e: Entry) {
        val svc = ListenService.instance
        if (svc != null) { svc.translate(e); return }
        e.st = "wait"; render()
        val src = e.src; val lang = e.lang
        Thread {
            val zh = try { Tr.translate(src, "", lang) } catch (x: Exception) { null }
            runOnUiThread { if (zh != null) { e.zh = zh; e.st = "ok" } else e.st = "err"; Store.save(); render() }
        }.start()
    }

    private fun render(force: Boolean = false) {
        if (!::list.isInitialized) return
        val note = Store.current
        if (force || shownNote !== note) { list.removeAllViews(); rows.clear(); shownNote = note }
        val stick = !scroll.canScrollVertically(1)
        // remove lines that are gone, add new ones, refresh all
        val alive = note.entries.toHashSet()
        rows.keys.filter { it !in alive }.forEach { list.removeView(rows.remove(it)!!.root) }
        note.entries.forEachIndexed { i, e ->
            var r = rows[e]
            if (r == null) {
                r = Ui.Row(this, p)
                r.root.setPadding(0, dp(8f), 0, dp(8f))
                r.root.setOnClickListener { if (e.st == "err") retry(e) }
                r.root.setOnLongClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("笔记", "${e.n}. ${e.zh}\n${e.src}"))
                    toast("已复制第 ${e.n} 条"); true
                }
                rows[e] = r
                list.addView(r.root, i.coerceAtMost(list.childCount))
            }
            r.bind(e, p, Prefs.fontSp, Prefs.showSrc)
        }
        empty.visibility = if (note.entries.isEmpty() && !Hub.running && !Hub.holding) View.VISIBLE else View.GONE

        // the line being heard right now
        val showLive = Hub.running || Hub.holding || Hub.liveLabel.isNotBlank()
        liveRow.root.visibility = if (showLive) View.VISIBLE else View.GONE
        liveRow.no.text = note.nextNo().toString(); liveRow.no.setTextColor(p.faint)
        liveRow.zh.text = Hub.liveLabel.ifBlank { "▍" }; liveRow.zh.setTextColor(p.accentInk)
        liveRow.src.text = Hub.live; liveRow.src.visibility = if (Hub.live.isBlank()) View.GONE else View.VISIBLE

        tvTitle.text = note.displayTitle()
        val c = note.entries.size
        val state = when { Hub.holding -> " · 按住中"; Hub.running -> " · 记录中"; else -> "" }
        tvMeta.text = (if (c > 0) "$c 条" else "空白笔记") + state + (if ((Hub.running || Hub.holding) && Hub.status.isNotBlank()) "\n" + Hub.status else "")
        chip.text = if (Prefs.lang == "en") "EN" else "日"
        holdBtn.text = if (Hub.holding) "🎤 松开结束" else "🎤 按住"
        holdBtn.background = if (Hub.holding) Ui.round(p.accent, dp(22f).toFloat()) else Ui.round(p.sheet, dp(22f).toFloat(), dp(1f), p.rule)
        holdBtn.setTextColor(if (Hub.holding) p.onAccent else p.ink)
        fab.text = if (Hub.running) "❚❚" else "✎"
        fab.background = Ui.round(if (Hub.running) p.ink else p.accent, dp(28f).toFloat())
        fab.setTextColor(if (Hub.running) p.paper else p.onAccent)
        pipBtn.setTextColor(if (Hub.overlayOn) p.accentInk else p.ink)
        if (Hub.running || Hub.holding) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (stick || force) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
