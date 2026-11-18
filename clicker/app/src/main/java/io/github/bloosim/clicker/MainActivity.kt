package io.github.bloosim.clicker

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private val onStore: () -> Unit = { render() }

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = C.PAPER
        window.navigationBarColor = C.PAPER
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        scroll = ScrollView(this).apply { setBackgroundColor(C.PAPER); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(12f), dp(14f), dp(40f)) }
        scroll.addView(body)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Store.listen(onStore)
        render()
    }

    override fun onPause() {
        Store.unlisten(onStore)
        super.onPause()
    }

    /** the service is switched on in the system settings (it may still be starting) */
    private fun serviceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ClickService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun gap(h: Float = 12f) = View(this).also { body.addView(it, LinearLayout.LayoutParams(-1, dp(h))) }

    private fun render() {
        val y = scroll.scrollY
        body.removeAllViews()
        header()
        gap()
        serviceCard()
        gap()
        schemeCard()
        gap()
        stepsCard()
        gap()
        runCard()
        gap()
        otherCard()
        gap()
        helpCard()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun header() {
        val r = Ui.row(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, "连点器", 24f, C.INK, true))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        col.addView(Ui.text(this, "自动点击 · 多点顺序 · 定时 · v$ver", 12f, C.MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(r)
    }

    // ------------------------------------------------------------------ service

    private fun serviceCard() {
        val running = ClickService.me != null
        val enabled = serviceEnabled()
        val card = Ui.card(this, null)
        when {
            running -> {
                card.addView(Ui.text(this, "✓ 无障碍服务已开启", 15f, C.GREEN, true))
                val svc = ClickService.me!!
                card.addView(Ui.text(this, if (svc.panelShown) "悬浮窗正在显示。切到要连点的应用，把圆点拖到要点的位置，按 ▶ 开始。" else "点下面的按钮显示悬浮窗，然后切到要连点的应用。", 13f, C.MUTED).apply { setPadding(0, dp(4f), 0, dp(10f)) })
                card.addView(Ui.button(this, if (svc.panelShown) "回到桌面去设置位置" else "显示悬浮窗", filled = true) {
                    svc.showPanel()
                    moveTaskToBack(true)
                })
                if (svc.panelShown) card.addView(Ui.button(this, "关闭悬浮窗", color = C.RED) { svc.closePanel(); render() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
            }
            else -> {
                card.addView(Ui.text(this, if (enabled) "无障碍服务已打开，但还没有运行" else "第一步：开启无障碍服务", 15f, C.RED, true))
                card.addView(Ui.text(this,
                    (if (enabled) "请到无障碍设置里把「连点器」关掉再重新打开。\n\n" else "连点器用安卓的无障碍服务模拟手指点击，不需要 root。它只在你按 ▶ 后按你设置的位置点击，不读取屏幕内容。\n\n") +
                        "打开方法：点下面的按钮 →（已下载的应用 / 已安装的服务）→ 连点器 → 打开。\n" +
                        "如果开关是灰色、打不开：手机设置 → 应用 → 应用管理 → 连点器 → 右上角 ⋮ → 「允许受限制的设置」，再回来打开。",
                    13f, C.MUTED).apply { setPadding(0, dp(4f), 0, dp(10f)) })
                card.addView(Ui.button(this, "去开启无障碍服务", filled = true) {
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { toast("打不开设置") }
                })
                card.addView(Ui.button(this, "打开本应用的系统设置页", color = C.MUTED) {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
            }
        }
        body.addView(card)
    }

    // ------------------------------------------------------------------ schemes

    private fun schemeCard() {
        val s = Store.cur
        val card = Ui.card(this, "方案")
        val line = Ui.row(this)
        line.addView(Ui.text(this, s.name, 18f, C.BLUE, true), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Ui.text(this, "共 ${Store.schemes.size} 个方案", 12f, C.MUTED))
        card.addView(line)
        card.addView(Ui.text(this, "${s.pts.size} 步，一轮约 ${Fmt.total(s.roundMs())}", 13f, C.MUTED).apply { setPadding(0, dp(2f), 0, dp(8f)) })
        val r = Ui.row(this)
        r.addView(Ui.button(this, "切换") { pickScheme() }, Ui.lpW(this))
        r.addView(Ui.button(this, "新建") { newScheme(false) }, Ui.lpW(this))
        r.addView(Ui.button(this, "复制") { newScheme(true) }, Ui.lpW(this))
        card.addView(r)
        val r2 = Ui.row(this)
        r2.addView(Ui.button(this, "改名") {
            Dlg.textInput(this, false, "方案名称", s.name) { n -> if (n.isNotEmpty()) { s.name = n; Store.changed() } }
        }, Ui.lpW(this))
        r2.addView(Ui.button(this, "删除", color = C.RED) {
            if (Store.schemes.size <= 1) { toast("至少要保留一个方案"); return@button }
            Dlg.confirm(this, false, "删除方案", "删除「${s.name}」？", "删除") {
                stopIfRunning()
                Store.schemes.removeAt(Store.curIdx); Store.curIdx = 0; Store.changed()
            }
        }, Ui.lpW(this))
        card.addView(r2)
        body.addView(card)
    }

    private fun stopIfRunning() { ClickService.me?.runner?.stop("方案已更改，已停止") }

    private fun pickScheme() {
        val names = Store.schemes.map { "${it.name}（${it.pts.size} 步）" }.toTypedArray()
        AlertDialog.Builder(Dlg.themed(this)).setTitle("切换方案")
            .setSingleChoiceItems(names, Store.curIdx) { d, i ->
                stopIfRunning()
                Store.curIdx = i; Store.changed(); d.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun newScheme(copy: Boolean) {
        val base = if (copy) Store.cur.copy().also { it.name = Store.uniqueName(Store.cur.name + "副本") }
        else Scheme(Store.uniqueName("方案")).also { val sz = Screen.size(this); it.pts.add(Pt.tap(sz.x / 2, sz.y / 2, 1000)) }
        stopIfRunning()
        Store.schemes.add(base)
        Store.curIdx = Store.schemes.size - 1
        Store.changed()
        toast(if (copy) "已复制为「${base.name}」" else "已新建「${base.name}」")
    }

    // ------------------------------------------------------------------ steps

    private fun stepsCard() {
        val s = Store.cur
        val card = Ui.card(this, "点击顺序（按 1、2、3… 依次执行）")
        if (s.pts.isEmpty()) card.addView(Ui.text(this, "还没有步骤，点下面的按钮添加。", 13f, C.MUTED))
        s.pts.forEachIndexed { i, p ->
            val r = Ui.row(this).apply {
                setPadding(0, dp(6f), 0, dp(6f))
                isClickable = true
                setOnClickListener { PointDialog.show(this@MainActivity, false, i) }
            }
            val no = Ui.text(this, "${i + 1}", 14f, android.graphics.Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(if (p.swipe) C.ORANGE else C.BLUE, dp(14f).toFloat())
            }
            r.addView(no, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { rightMargin = dp(10f) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val what = if (p.swipe) "滑动 (${p.x}, ${p.y}) → (${p.x2}, ${p.y2})，用时 ${Fmt.dur(p.holdMs)}"
            else "点击 (${p.x}, ${p.y})" + if (p.holdMs >= 500) "，长按 ${Fmt.dur(p.holdMs)}" else ""
            col.addView(Ui.text(this, what, 14f, C.INK))
            col.addView(Ui.text(this, "然后等 ${Fmt.dur(p.delayMs)}", 13f, if (p.delayMs >= 30_000L) C.ORANGE else C.MUTED, p.delayMs >= 30_000L))
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(small("↑") { if (i > 0) { val t = s.pts.removeAt(i); s.pts.add(i - 1, t); Store.changed() } })
            r.addView(small("↓") { if (i < s.pts.size - 1) { val t = s.pts.removeAt(i); s.pts.add(i + 1, t); Store.changed() } })
            card.addView(r)
            if (i < s.pts.size - 1) card.addView(View(this).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, dp(1f)))
        }
        val add = Ui.row(this).apply { setPadding(0, dp(8f), 0, 0) }
        add.addView(Ui.button(this, "＋ 点击") { addStep(false) }, Ui.lpW(this))
        add.addView(Ui.button(this, "＋ 滑动") { addStep(true) }, Ui.lpW(this))
        card.addView(add)

        card.addView(Ui.text(this, "一键设置所有步骤的间隔", 14f, C.INK, true).apply { setPadding(0, dp(14f), 0, dp(4f)) })
        val iv = Presets.INTERVALS
        val common = s.pts.map { it.delayMs }.distinct().singleOrNull()
        val sel = if (common == null) -1 else iv.indexOf(common).let { if (it < 0) iv.size else it }
        val (chips, _) = Ui.chips(this, iv.map { Fmt.dur(it) } + "自定义…", sel, 4) { k ->
            if (k < iv.size) setAll(iv[k])
            else Dlg.duration(this, false, "所有步骤的间隔", common ?: 1000L, 0L, 24 * 3_600_000L) { setAll(it) }
        }
        card.addView(chips)
        card.addView(Ui.text(this, "点某一步可以单独设置它的间隔、长按时间和位置。", 12f, C.MUTED).apply { setPadding(0, dp(6f), 0, 0) })
        body.addView(card)
    }

    private fun small(s: String, onClick: () -> Unit): TextView = Ui.text(this, s, 18f, C.MUTED, true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
    }

    private fun setAll(ms: Long) {
        val s = Store.cur
        if (s.pts.isEmpty()) return
        s.pts.forEach { it.delayMs = ms }
        Store.changed()
        toast("所有步骤的间隔都设为 ${Fmt.dur(ms)}")
    }

    private fun addStep(swipe: Boolean) {
        val s = Store.cur
        val sz = Screen.size(this)
        val last = s.pts.lastOrNull()
        val x = last?.x ?: (sz.x / 2)
        val y = ((last?.y ?: (sz.y / 2 - dp(80f))) + dp(80f)).let { if (it > sz.y - dp(120f)) dp(200f) else it }
        s.pts.add(if (swipe) Pt.swipe(x, y, last?.delayMs ?: 1000L) else Pt.tap(x, y, last?.delayMs ?: 1000L))
        Store.changed()
        toast("已添加第 ${s.pts.size} 步，到悬浮窗里拖动圆点设置位置")
    }

    // ------------------------------------------------------------------ run settings

    private fun runCard() {
        val s = Store.cur
        val card = Ui.card(this, "运行方式")
        fun label(t: String) = card.addView(Ui.text(this, t, 14f, C.INK, true).apply { setPadding(0, dp(10f), 0, dp(4f)) })

        label("重复")
        val modes = listOf("loop", "rounds", "minutes")
        val (rep, _) = Ui.chips(this, listOf("一直循环", "循环 ${s.rounds} 轮", "运行 ${s.minutes} 分钟"), modes.indexOf(s.repeat), 3) { k ->
            when (k) {
                0 -> { s.repeat = "loop"; Store.changed() }
                1 -> Dlg.intInput(this, false, "循环几轮后停止？", s.rounds, 1, 1_000_000) { n -> s.rounds = n; s.repeat = "rounds"; Store.changed() }
                else -> Dlg.intInput(this, false, "运行几分钟后停止？", s.minutes, 1, 100_000) { n -> s.minutes = n; s.repeat = "minutes"; Store.changed() }
            }
        }
        card.addView(rep)

        label("按 ▶ 后先等")
        val delays = listOf(0, 3, 5, 10)
        val (dl, _) = Ui.chips(this, listOf("不等", "3秒", "5秒", "10秒"), delays.indexOf(s.startDelaySec).let { if (it < 0) 1 else it }, 4) { k ->
            s.startDelaySec = delays[k]; Store.changed()
        }
        card.addView(dl)

        label("定时开始")
        val tr = Ui.row(this)
        tr.addView(Ui.text(this, if (s.startClock.isBlank()) "不定时（按 ▶ 就开始）" else "按 ▶ 后，等到 ${s.startClock} 才开始", 14f, if (s.startClock.isBlank()) C.MUTED else C.BLUE, s.startClock.isNotBlank()), LinearLayout.LayoutParams(0, -2, 1f))
        tr.addView(Ui.button(this, "设置") {
            val parts = s.startClock.split(":")
            val hh = parts.getOrNull(0)?.toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val mm = parts.getOrNull(1)?.toIntOrNull() ?: 0
            TimePickerDialog(this, { _, h, m -> s.startClock = String.format("%02d:%02d", h, m); Store.changed() }, hh, mm, true).show()
        })
        if (s.startClock.isNotBlank()) tr.addView(Ui.button(this, "取消定时", color = C.RED) { s.startClock = ""; Store.changed() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) })
        card.addView(tr)

        label("间隔随机浮动（更像真人）")
        val jit = listOf(0, 10, 20, 30)
        val (jc, _) = Ui.chips(this, listOf("不浮动", "±10%", "±20%", "±30%"), jit.indexOf(s.jitterPct).let { if (it < 0) 0 else it }, 4) { k ->
            s.jitterPct = jit[k]; Store.changed()
        }
        card.addView(jc)

        label("位置随机偏移")
        val offs = listOf(0, 5, 10, 20)
        val (oc, _) = Ui.chips(this, listOf("不偏移", "±5像素", "±10像素", "±20像素"), offs.indexOf(s.offsetPx).let { if (it < 0) 0 else it }, 4) { k ->
            s.offsetPx = offs[k]; Store.changed()
        }
        card.addView(oc)
        body.addView(card)
    }

    // ------------------------------------------------------------------ other

    private fun otherCard() {
        val card = Ui.card(this, "其他")
        fun check(t: String, sub: String, v: Boolean, set: (Boolean) -> Unit) {
            val cb = CheckBox(this).apply { text = t; textSize = 15f; isChecked = v; setOnCheckedChangeListener { _, on -> set(on) } }
            card.addView(cb)
            card.addView(Ui.text(this, sub, 12f, C.MUTED).apply { setPadding(dp(32f), 0, 0, dp(6f)) })
        }
        check("按音量下键立即停止", "运行时按一下音量下键就停（不会调音量）", Opt.volStop) { Opt.volStop = it }
        check("运行时隐藏圆点", "不勾选时圆点变成半透明，点击会穿过圆点", Opt.hideRun) { Opt.hideRun = it }
        val r = Ui.row(this).apply { setPadding(0, dp(8f), 0, 0) }
        r.addView(Ui.button(this, "导出全部方案") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("连点器方案", Store.exportAll()))
            toast("已复制到剪贴板，可以粘贴保存")
        }, Ui.lpW(this))
        r.addView(Ui.button(this, "从剪贴板导入") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            val n = Store.importAll(t)
            toast(if (n > 0) "已导入 $n 个方案" else "剪贴板里没有连点器方案")
        }, Ui.lpW(this))
        card.addView(r)
        body.addView(card)
    }

    private fun helpCard() {
        val card = Ui.card(this, "使用方法")
        card.addView(Ui.text(this,
            "1. 开启无障碍服务，点「显示悬浮窗」。\n" +
                "2. 切到要连点的应用。屏幕边上的悬浮窗：\n" +
                "   ▶ 开始 / ■ 停止　＋ 加一个点击　⇅ 加一个滑动　－ 删掉最后一步\n" +
                "   ◉ 显示 / 隐藏圆点　⚙ 回到这里改设置　✕ 关闭悬浮窗\n" +
                "   按住最上面的 ⋮⋮ 可以拖动悬浮窗。\n" +
                "3. 把带编号的圆点拖到要点的位置（圆点中间的小白点就是点击的地方）。单击圆点可以改这一步的间隔、长按时间。\n" +
                "4. 按 ▶ 开始。悬浮窗下面显示第几轮、第几步、距离下一下还有几秒。运行中按 ■ 或音量下键停止。\n\n" +
                "间隔的意思：做完这一步，等多久再做下一步。可以选 30 秒、40 秒，也可以自定义（毫秒 / 秒 / 分钟）。\n\n" +
                "ColorOS 小提示：如果用一会儿就失效，到「设置 → 应用 → 连点器」里打开「允许自启动」，耗电管理选「允许后台运行」，" +
                "并在最近任务里把连点器锁住（下拉卡片）。失效后把无障碍服务关掉再打开即可。\n\n" +
                "注意：圆点位置是按屏幕像素记的，横竖屏切换后需要重新拖一下。",
            13f, C.MUTED))
        body.addView(card)
    }
}
