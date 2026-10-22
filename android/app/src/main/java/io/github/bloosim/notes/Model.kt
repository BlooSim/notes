package io.github.bloosim.notes

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/** One numbered line: what the other person said and its Chinese translation. */
class Entry(
    var n: Int,
    var src: String,
    var zh: String,
    var lang: String,
    var ts: Long,
    /** "asr" waiting for the AI service, "wait" translating, "ok", "err" translation failed */
    var st: String,
    var by: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("n", n).put("src", src).put("zh", zh)
        .put("lang", lang).put("ts", ts).put("st", st).put("by", by)

    companion object {
        fun from(o: JSONObject) = Entry(
            o.optInt("n"), o.optString("src"), o.optString("zh"), o.optString("lang", "ja"),
            o.optLong("ts"), o.optString("st", "ok"), o.optString("by")
        )
    }
}

class Note(val id: String, val created: Long, var title: String, val entries: MutableList<Entry> = mutableListOf()) {
    fun toJson(): JSONObject {
        val a = JSONArray()
        entries.forEach { a.put(it.toJson()) }
        return JSONObject().put("id", id).put("created", created).put("title", title).put("entries", a)
    }

    fun displayTitle(): String {
        if (title.isNotBlank()) return title
        val c = Calendar.getInstance().apply { timeInMillis = created }
        val wd = "日一二三四五六"[c.get(Calendar.DAY_OF_WEEK) - 1]
        return String.format(
            "%d月%d日 周%s %02d:%02d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), wd,
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    fun nextNo(): Int = (entries.lastOrNull()?.n ?: 0) + 1

    fun asText(): String {
        val sb = StringBuilder(displayTitle()).append("\n\n")
        entries.forEach { sb.append(it.n).append(". ").append(it.zh.ifBlank { "（未翻译）" }).append("\n   ").append(it.src).append("\n") }
        return sb.toString()
    }

    companion object {
        fun from(o: JSONObject): Note {
            val n = Note(o.optString("id"), o.optLong("created"), o.optString("title"))
            val a = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until a.length()) n.entries.add(Entry.from(a.getJSONObject(i)))
            return n
        }

        fun create(): Note {
            val now = System.currentTimeMillis()
            return Note("n" + now.toString(36), now, "")
        }
    }
}

/** Notes on disk. Only touched on the main thread; files are written on a background thread. */
object Store {
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    val notes = mutableListOf<Note>() // newest first
    lateinit var current: Note
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        file = File(ctx.filesDir, "notes.json")
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val a = o.optJSONArray("notes") ?: JSONArray()
                for (i in 0 until a.length()) notes.add(Note.from(a.getJSONObject(i)))
                notes.forEach { n ->
                    n.entries.removeAll { it.st == "asr" && it.src.isBlank() } // pieces still waiting when the app was closed
                    n.entries.forEach { e -> if (e.st == "asr" || e.st == "wait") e.st = if (e.zh.isNotBlank()) "ok" else "err" }
                }
                current = notes.firstOrNull { it.id == o.optString("current") } ?: notes.firstOrNull() ?: newNote()
                return
            }
        } catch (e: Exception) {
            Hub.dl("读取笔记失败：" + e.message)
        }
        current = newNote()
    }

    fun newNote(): Note {
        notes.removeAll { it.entries.isEmpty() }
        val n = Note.create()
        notes.add(0, n)
        while (notes.size > 60) notes.removeAt(notes.size - 1)
        current = n
        return n
    }

    private var pending = false
    fun save() {
        if (pending) return
        pending = true
        main.postDelayed({
            pending = false
            val o = JSONObject().put("current", current.id)
            val a = JSONArray()
            notes.forEach { a.put(it.toJson()) }
            o.put("notes", a)
            val text = o.toString()
            io.execute {
                try {
                    val tmp = File(file.parentFile, "notes.json.tmp")
                    tmp.writeText(text)
                    tmp.renameTo(file)
                } catch (e: Exception) { }
            }
        }, 400)
    }
}

/** Settings, kept in SharedPreferences. Safe to read from any thread. */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun ps(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun pi(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    /** "ja" or "en": what the other person speaks */
    var lang: String get() = s("lang", "ja"); set(v) = ps("lang", v)
    /** silence that ends a sentence, ms */
    var pause: Int get() = i("pause", 1300); set(v) = pi("pause", v)
    /** "llm" = AI services first, "phone" = phone recognizer only */
    var engine: String get() = s("engine", "llm"); set(v) = ps("engine", v)
    var gKey: String get() = s("gKey", ""); set(v) = ps("gKey", v)
    var qKey: String get() = s("qKey", ""); set(v) = ps("qKey", v)
    var mKey: String get() = s("mKey", ""); set(v) = ps("mKey", v)
    /** continuous translation also goes through the AI services (uses more of the free quota) */
    var llmCont: Boolean get() = b("llmCont", true); set(v) = pb("llmCont", v)
    /** run the phone recognizer next to the AI services, for the live line and as a fallback */
    var phonePreview: Boolean get() = b("phonePreview", true); set(v) = pb("phonePreview", v)
    var showSrc: Boolean get() = b("showSrc", true); set(v) = pb("showSrc", v)
    var fontSp: Int get() = i("fontSp", 17); set(v) = pi("fontSp", v)
    var pst: String get() = s("pst", "{}"); set(v) = ps("pst", v)
    var lastModels: String get() = s("lastModels", "{}"); set(v) = ps("lastModels", v)
    var overlayX: Int get() = i("ovx", -1); set(v) = pi("ovx", v)
    var overlayY: Int get() = i("ovy", -1); set(v) = pi("ovy", v)

    val recLang: String get() = if (lang == "en") "en-US" else "ja-JP"
    val langName: String get() = if (lang == "en") "英语" else "日语"
}

/** Shared live state between the service, the screen and the floating window. Main thread only. */
object Hub {
    val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    /** continuous translation is on */
    var running = false
    /** the hold-to-talk button is held */
    var holding = false
    var overlayOn = false
    /** what is being heard right now (not yet a numbered line) */
    var live = ""
    var liveLabel = ""
    /** which service answered last, or why the phone recognizer is used */
    var status = ""
    var level = 0f
    val log = ArrayDeque<String>()
    private var pending = false

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    fun changed() {
        if (pending) return
        pending = true
        main.post {
            pending = false
            listeners.toList().forEach { try { it() } catch (e: Exception) { } }
        }
    }

    fun dl(msg: String) {
        val run = Runnable {
            val c = Calendar.getInstance()
            log.addLast(String.format("%02d:%02d:%02d %s", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), msg))
            while (log.size > 80) log.removeFirst()
            changed()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }
}

object Txt {
    private val WS = Regex("\\s+")
    private val NORM = Regex("[\\s。、，,.．！!？?「」『』…]")
    fun clean(s: String?) = (s ?: "").replace(WS, " ").trim()
    fun norm(s: String?) = (s ?: "").replace(NORM, "").lowercase()

    /** join two pieces of what was heard, without repeating text the phone sent twice */
    fun combine(a: String, b: String): String {
        val an = norm(a); val bn = norm(b)
        if (an.isEmpty()) return b
        if (bn.isEmpty()) return a
        if (bn.contains(an)) return b
        if (an.contains(bn)) return a
        return "$a $b"
    }

    private val FILL_JA = Regex("(^|[\\s、。,，])(?:えっと|えーっと|えーと|ええと|えー+|あのー+|あのう|あの|うーん|うーむ|そのー+|まあー+)(?=$|[\\s、。,，])")
    private val FILL_EN = Regex("(?i)(^|[\\s,.])(?:um+|uh+|erm+|hmm+|uh-huh)(?=$|[\\s,.])")
    private val LEAD = Regex("^[\\s、。,，.]+")

    /** fillers like えっと confuse the translator, so they are left out of what gets translated */
    fun forTranslate(text: String, lang: String): String {
        var t = text
        val re = if (lang == "en") FILL_EN else FILL_JA
        repeat(3) { t = re.replace(t, "$1") }
        return t.replace(LEAD, "").replace(WS, " ").trim()
    }

    /** phrases Whisper-type models invent for silence */
    val HALLU = Regex("(?i)ご視聴ありがとうございました|ご清聴ありがとうございました|チャンネル登録|thanks? for watching|字幕|subtitles by")
}
