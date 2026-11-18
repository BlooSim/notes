package io.github.bloosim.clicker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/** One step of a scheme: a tap (or long press) at (x, y), or a swipe from (x, y) to (x2, y2). Screen pixels. */
class Pt(
    var type: String,
    var x: Int, var y: Int,
    var x2: Int, var y2: Int,
    /** wait after this step before the next one, ms */
    var delayMs: Long,
    /** finger down time for a tap, or how long the swipe takes, ms */
    var holdMs: Long
) {
    val swipe: Boolean get() = type == "swipe"

    fun toJson(): JSONObject = JSONObject().put("t", type).put("x", x).put("y", y).put("x2", x2).put("y2", y2)
        .put("d", delayMs).put("h", holdMs)

    fun copy() = Pt(type, x, y, x2, y2, delayMs, holdMs)

    companion object {
        fun from(o: JSONObject): Pt {
            val x = o.optInt("x"); val y = o.optInt("y")
            return Pt(o.optString("t", "tap"), x, y, o.optInt("x2", x + 300), o.optInt("y2", y),
                o.optLong("d", 1000L).coerceAtLeast(0L), o.optLong("h", 60L).coerceIn(1L, 59_000L))
        }

        fun tap(x: Int, y: Int, delay: Long) = Pt("tap", x, y, x + 300, y, delay, 60)
        fun swipe(x: Int, y: Int, delay: Long) = Pt("swipe", x, y + 300, x, y - 300, delay, 400)
    }
}

/** A saved set of steps plus how to run them. */
class Scheme(var name: String) {
    val pts = mutableListOf<Pt>()
    /** "loop" forever, "rounds" = [rounds] times, "minutes" = for [minutes] minutes */
    var repeat = "loop"
    var rounds = 10
    var minutes = 10
    /** countdown before the first step, s */
    var startDelaySec = 3
    /** "HH:mm" = wait until this clock time before starting; "" = start right away */
    var startClock = ""
    /** random ± percentage on every interval */
    var jitterPct = 0
    /** random ± pixels on every position */
    var offsetPx = 0

    /** time of one round, ms */
    fun roundMs(): Long = pts.sumOf { it.delayMs + it.holdMs }

    fun toJson(): JSONObject {
        val a = JSONArray()
        pts.forEach { a.put(it.toJson()) }
        return JSONObject().put("name", name).put("pts", a).put("repeat", repeat).put("rounds", rounds)
            .put("minutes", minutes).put("startDelay", startDelaySec).put("startClock", startClock)
            .put("jitter", jitterPct).put("offset", offsetPx)
    }

    fun copy(): Scheme = from(toJson())

    companion object {
        fun from(o: JSONObject): Scheme {
            val s = Scheme(o.optString("name", "方案"))
            val a = o.optJSONArray("pts") ?: JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { s.pts.add(Pt.from(it)) }
            s.repeat = o.optString("repeat", "loop").let { if (it in listOf("loop", "rounds", "minutes")) it else "loop" }
            s.rounds = o.optInt("rounds", 10).coerceIn(1, 1_000_000)
            s.minutes = o.optInt("minutes", 10).coerceIn(1, 100_000)
            s.startDelaySec = o.optInt("startDelay", 3).coerceIn(0, 3600)
            s.startClock = o.optString("startClock", "")
            s.jitterPct = o.optInt("jitter", 0).coerceIn(0, 90)
            s.offsetPx = o.optInt("offset", 0).coerceIn(0, 200)
            return s
        }
    }
}

/** All schemes on disk. Main thread only. */
object Store {
    private lateinit var sp: SharedPreferences
    val schemes = mutableListOf<Scheme>()
    var curIdx = 0
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notifyPending = false

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("clicker", Context.MODE_PRIVATE)
        Opt.sp = sp
        try {
            val o = JSONObject(sp.getString("data", "{}") ?: "{}")
            val a = o.optJSONArray("schemes") ?: JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { schemes.add(Scheme.from(it)) }
            curIdx = o.optInt("cur", 0)
        } catch (_: Exception) { }
        if (schemes.isEmpty()) {
            val s = Scheme("方案1")
            val sz = Screen.size(ctx)
            s.pts.add(Pt.tap(sz.x / 2, sz.y / 2, 1000))
            schemes.add(s)
        }
        curIdx = curIdx.coerceIn(0, schemes.size - 1)
    }

    val cur: Scheme get() = schemes[curIdx.coerceIn(0, schemes.size - 1)]

    fun listen(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    /** save and tell the screen and the floating window */
    fun changed() {
        curIdx = curIdx.coerceIn(0, schemes.size - 1)
        val a = JSONArray()
        schemes.forEach { a.put(it.toJson()) }
        sp.edit().putString("data", JSONObject().put("cur", curIdx).put("schemes", a).toString()).apply()
        if (notifyPending) return
        notifyPending = true
        main.post {
            notifyPending = false
            listeners.toList().forEach { try { it() } catch (_: Exception) { } }
        }
    }

    fun exportAll(): String {
        val a = JSONArray()
        schemes.forEach { a.put(it.toJson()) }
        return JSONObject().put("app", "clicker").put("schemes", a).toString()
    }

    /** adds the schemes found in [text]; returns how many */
    fun importAll(text: String): Int {
        val t = text.trim()
        val arr: JSONArray = try {
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> o.optJSONArray("schemes") ?: JSONArray().put(o) }
        } catch (_: Exception) { return 0 }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("pts")) continue
            schemes.add(Scheme.from(o)); n++
        }
        if (n > 0) { curIdx = schemes.size - 1; changed() }
        return n
    }

    fun uniqueName(base: String): String {
        if (schemes.none { it.name == base }) return base
        var i = 2
        while (schemes.any { it.name == "$base$i" }) i++
        return "$base$i"
    }
}

/** Settings that are not part of a scheme. */
object Opt {
    lateinit var sp: SharedPreferences
    var volStop: Boolean get() = sp.getBoolean("volStop", true); set(v) = sp.edit().putBoolean("volStop", v).apply()
    var hideRun: Boolean get() = sp.getBoolean("hideRun", false); set(v) = sp.edit().putBoolean("hideRun", v).apply()
    var panelX: Int get() = sp.getInt("panelX", 0); set(v) = sp.edit().putInt("panelX", v).apply()
    var panelY: Int get() = sp.getInt("panelY", 360); set(v) = sp.edit().putInt("panelY", v).apply()
}

object Screen {
    fun size(ctx: Context): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }
}

object Fmt {
    /** 100 → 0.1秒, 1500 → 1.5秒, 30000 → 30秒, 120000 → 2分钟, 45 → 45毫秒 */
    fun dur(ms: Long): String = when {
        ms <= 0L -> "0秒"
        ms < 1000L && ms % 100L == 0L -> "0.${ms / 100}秒"
        ms < 1000L -> "${ms}毫秒"
        ms % 60_000L == 0L -> "${ms / 60_000}分钟"
        ms % 1000L == 0L -> "${ms / 1000}秒"
        ms < 60_000L -> trim(ms / 1000.0) + "秒"
        else -> "${ms / 60_000}分" + trim((ms % 60_000L) / 1000.0) + "秒"
    }

    private fun trim(v: Double): String {
        val s = String.format("%.2f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty()) "0" else s
    }

    /** long total times */
    fun total(ms: Long): String {
        if (ms < 60_000L) return dur(ms)
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1000L
        return (if (h > 0) "${h}小时" else "") + (if (m > 0) "${m}分" else "") + (if (s > 0 && h == 0L) "${s}秒" else "")
    }
}

/** Interval presets offered everywhere; 30 s and 40 s included on purpose. */
object Presets {
    val INTERVALS = listOf(100L, 200L, 500L, 1000L, 2000L, 3000L, 5000L, 10_000L, 30_000L, 40_000L, 60_000L)
    val TAP_HOLD = listOf(20L, 60L, 150L, 500L, 1000L, 3000L)
    val SWIPE_TIME = listOf(150L, 300L, 500L, 1000L, 2000L)
}
