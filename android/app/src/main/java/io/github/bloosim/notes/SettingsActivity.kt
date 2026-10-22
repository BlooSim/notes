package io.github.bloosim.notes

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var p: Pal
    private lateinit var form: LinearLayout
    private lateinit var llmBox: LinearLayout
    private lateinit var keysOut: TextView
    private lateinit var logView: TextView
    private val statusViews = mutableMapOf<String, TextView>()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { paintStatus(); h.postDelayed(this, 1000) }
    }

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this); Store.init(this)
        p = Ui.pal(this)
        window.statusBarColor = p.paper
        window.navigationBarColor = p.paper
        @Suppress("DEPRECATION")
        if (!p.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val sv = ScrollView(this).apply { setBackgroundColor(p.paper) }
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(12f), dp(18f), dp(40f)) }
        sv.addView(form)
        setContentView(sv)
        build()
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    private fun title(t: String) = form.addView(Ui.text(this, t, 20f, p.ink, true).apply { setPadding(0, dp(4f), 0, dp(10f)) })
    private fun hint(t: String, parent: LinearLayout = form) = Ui.text(this, t, 13f, p.muted).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(4f), 0, dp(6f)) }.also { parent.addView(it) }
    private fun gap(parent: LinearLayout = form, h: Float = 12f) = parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    private fun rule() = form.addView(View(this).apply { setBackgroundColor(p.rule) }, LinearLayout.LayoutParams(-1, dp(1f)).apply { topMargin = dp(10f); bottomMargin = dp(10f) })

    private fun row(label: String, right: View, parent: LinearLayout = form) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8f), 0, dp(8f)) }
        r.addView(Ui.text(this, label, 15f, p.ink), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(right)
        parent.addView(r)
    }

    /** a small segmented control */
    private fun seg(options: List<Pair<String, String>>, current: () -> String, onPick: (String) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = Ui.round(p.paper, dp(9f).toFloat(), dp(1f), p.rule); setPadding(dp(2f), dp(2f), dp(2f), dp(2f)) }
        val views = options.map { (v, label) -> Ui.text(this, label, 14f, p.muted).apply { gravity = Gravity.CENTER; setPadding(dp(12f), dp(6f), dp(12f), dp(6f)); tag = v } }
        fun paint() = views.forEach { tv ->
            val on = tv.tag == current()
            tv.setTextColor(if (on) p.ink else p.muted)
            tv.background = if (on) Ui.round(p.sheet, dp(7f).toFloat(), dp(1f), p.rule) else null
        }
        views.forEach { tv -> tv.setOnClickListener { onPick(tv.tag as String); paint() }; box.addView(tv) }
        paint()
        return box
    }

    private fun switch(on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, c -> onChange(c) } }

    private fun button(t: String, on: () -> Unit) = Ui.pill(this, t, p).apply { setOnClickListener { on() } }

    private fun link(t: String, url: String, parent: LinearLayout) = Ui.text(this, t, 13f, p.accentInk).apply {
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(2f), 0, dp(4f))
        setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { } }
    }.also { parent.addView(it) }

    @SuppressLint("SetTextI18n")
    private fun build() {
        title("设置")

        row("识别方式", seg(listOf("llm" to "大模型", "phone" to "手机"), { Prefs.engine }) { Prefs.engine = it; llmBox.visibility = if (it == "llm") View.VISIBLE else View.GONE; if (Hub.running) toast("暂停后重新开始生效") })
        llmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(llmBox)
        llmBox.visibility = if (Prefs.engine == "llm") View.VISIBLE else View.GONE
        hint("大模型识别更准，填一个 Key 就能用。填多个时，一个额度用完会自动换下一个；全都用不了时，自动改用手机识别。", llmBox)
        provider("g", "Gemini", "效果最好：识别和翻译一次完成。用 Google 账号免费创建，不要绑定付款方式。", "去 Google AI Studio 创建 Key", "https://aistudio.google.com/apikey")
        provider("q", "Groq", "免费的 Whisper 识别，速度很快，作为备用。", "去 Groq 创建 Key", "https://console.groq.com/keys")
        provider("m", "Mistral", "Voxtral 识别。免费模式的内容可能被用于训练模型，介意就不要填。", "去 Mistral 创建 Key", "https://console.mistral.ai/api-keys")
        row("连续翻译也用大模型", switch(Prefs.llmCont) { Prefs.llmCont = it }, llmBox)
        hint("关掉可以省额度：只有「按住」时用大模型，连续翻译用手机识别。", llmBox)
        row("手机识别做实时预览和兜底", switch(Prefs.phonePreview) { Prefs.phonePreview = it }, llmBox)
        hint("边说边显示灰色的原文，大模型用不了时用它的结果。如果它打断了其他应用的声音，可以关掉。", llmBox)
        val kb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kb.addView(button("检查 Key 能不能用") { checkKeys() })
        llmBox.addView(kb)
        keysOut = hint("", llmBox)
        rule()

        row("对方说的语言", seg(listOf("ja" to "日语", "en" to "英语"), { Prefs.lang }) { Prefs.lang = it; ListenService.send(this, ListenService.ACT_LANG) })
        row("停顿多久算一句", seg(listOf("800" to "短", "1300" to "中", "2000" to "长"), { Prefs.pause.toString() }) { Prefs.pause = it.toInt() })
        hint("对方说话慢、句子被切碎时调长；想更快出译文时调短。")
        row("字号", seg(listOf("15" to "小", "17" to "中", "20" to "大"), { Prefs.fontSp.toString() }) { Prefs.fontSp = it.toInt() })
        row("显示原文", switch(Prefs.showSrc) { Prefs.showSrc = it; Hub.changed() })
        rule()

        val b1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b1.addView(button("允许悬浮窗") {
            if (Settings.canDrawOverlays(this)) toast("已经允许了")
            else try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
        })
        b1.addView(View(this), LinearLayout.LayoutParams(dp(10f), 1))
        b1.addView(button("允许后台运行") { allowBackground() })
        form.addView(b1)
        hint("国行系统可能会在后台关掉应用。点「允许后台运行」选择「不优化」；系统设置 → 应用 → 笔记 → 耗电管理里，也打开「允许后台行为」。")
        gap()
        val b2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b2.addView(button("新建笔记") {
            if (Store.current.entries.isEmpty()) { toast("当前已经是空白笔记"); return@button }
            Store.newNote(); Store.save(); Hub.changed(); toast("已新建笔记，编号从 1 开始")
        })
        b2.addView(View(this), LinearLayout.LayoutParams(dp(10f), 1))
        b2.addView(button("历史笔记") { history() })
        b2.addView(View(this), LinearLayout.LayoutParams(dp(10f), 1))
        b2.addView(button("分享") { share() })
        form.addView(b2)
        rule()

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        hint("版本 $ver · 识别和翻译需要联网 · 笔记只保存在这台手机上")
        logView = Ui.text(this, "", 11f, p.muted).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        form.addView(logView)
    }

    private fun provider(id: String, name: String, about: String, linkText: String, url: String) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, dp(10f), 0, dp(4f)) }
        head.addView(Ui.text(this, name, 15f, p.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        val st = Ui.text(this, "", 12f, p.muted).apply { gravity = Gravity.END }
        head.addView(st)
        statusViews[id] = st
        llmBox.addView(head)
        val et = EditText(this).apply {
            setText(Cloud.PROV.first { it.id == id }.key())
            hint = "粘贴 $name API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            textSize = 15f
            setTextColor(p.ink); setHintTextColor(p.faint)
            background = Ui.round(p.sheet, dp(9f).toFloat(), dp(1f), p.rule)
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = (s?.toString() ?: "").replace(Regex("\\s+"), "")
                    when (id) { "g" -> Prefs.gKey = v; "q" -> Prefs.qKey = v; else -> Prefs.mKey = v }
                    Cloud.clearFor(id)
                    paintStatus()
                }
            })
        }
        llmBox.addView(et, LinearLayout.LayoutParams(-1, -2))
        hint(about, llmBox)
        link(linkText, url, llmBox)
    }

    private fun paintStatus() {
        Cloud.PROV.forEach { pv ->
            val t = Cloud.status(pv)
            statusViews[pv.id]?.apply {
                text = t
                setTextColor(when { t.startsWith("可用") -> p.accentInk; t.contains("无效") || t.contains("没有能用") -> p.danger; else -> p.muted })
            }
        }
        logView.text = if (Hub.log.isEmpty()) "" else "诊断记录：\n" + Hub.log.toList().takeLast(30).joinToString("\n")
    }

    private fun checkKeys() {
        val list = Cloud.PROV.filter { it.key().isNotBlank() }
        if (list.isEmpty()) { keysOut.text = "还没有填写任何 Key。"; return }
        keysOut.text = "正在检查…"
        Thread {
            val lines = mutableListOf<String>()
            list.forEach { pv ->
                lines.add(Cloud.check(pv))
                runOnUiThread { keysOut.text = lines.joinToString("\n"); paintStatus() }
            }
        }.start()
    }

    private fun allowBackground() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("已经允许了"); return }
        try {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun history() {
        val notes = Store.notes.filter { it.entries.isNotEmpty() || it === Store.current }
        val labels = notes.map { n ->
            val first = n.entries.firstOrNull { it.zh.isNotBlank() }?.zh ?: ""
            (if (n === Store.current) "● " else "") + n.displayTitle() + "  (" + n.entries.size + " 条)" + (if (first.isNotEmpty()) "\n" + first.take(30) else "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("历史笔记").setItems(labels) { _, i ->
            Store.current = notes[i]; Store.save(); Hub.changed(); toast("已打开：" + notes[i].displayTitle()); finish()
        }.setNegativeButton("关闭", null).show()
    }

    private fun share() {
        val n = Store.current
        if (n.entries.isEmpty()) { toast("还没有内容"); return }
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, n.displayTitle()); putExtra(Intent.EXTRA_TEXT, n.asText()) }
        startActivity(Intent.createChooser(i, "分享笔记"))
    }
}
