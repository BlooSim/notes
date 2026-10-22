package io.github.bloosim.notes

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Deepgram live transcription: our recording is streamed over a WebSocket and Deepgram sends text back
 * while the person is still talking. It decides where a sentence ends (a pause), so every sentence
 * arrives as one piece. Audio recorded while the connection is down waits in a queue and is sent
 * when it is back, so nothing is lost. Methods are for the main thread unless noted.
 */
class Deepgram(private val cb: Callback) {
    interface Callback {
        /** words heard so far in the sentence being said */
        fun onDgLive(t: String)
        /** a finished sentence */
        fun onDgSentence(t: String)
        fun onDgUp()
        /** Deepgram cannot be used (wrong key, no balance, no connection) */
        fun onDgFail(reason: String)
    }

    companion object {
        private val CJK_SPACE = Regex("(?<=[\\u3000-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef])\\s+(?=[\\u3000-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef])")

        /** Checks the key. Worker thread. Returns a line for the settings screen. */
        fun check(key: String): String {
            if (key.isBlank()) return "Deepgram：还没有填写 Key"
            return try {
                val r = Http.call("https://api.deepgram.com/v1/projects", "GET", mapOf("Authorization" to "Token $key"), null, null, 15000)
                when (r.code) {
                    in 200..299 -> "Deepgram：可以用 ✓"
                    401, 403 -> "Deepgram：Key 无效"
                    402 -> "Deepgram：余额用完了"
                    else -> "Deepgram：服务器回复 HTTP ${r.code}"
                }
            } catch (e: Exception) {
                "Deepgram：" + Tr.netWhy(e)
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    /** recorded audio waiting to be sent: up to 30 s */
    private val audio = LinkedBlockingQueue<ByteArray>(1500)
    /** control messages, sent after the audio queued before them */
    private val ctl = LinkedBlockingQueue<String>()
    @Volatile private var gen = 0
    @Volatile private var ws: Ws? = null
    /** streaming is wanted (read from the recording thread) */
    @Volatile var want = false
        private set
    var up = false
        private set
    private var lang = "ja"
    private var pauseMs = 1300
    private var model = "nova-3"
    private var buf = ""
    private var fails = 0
    private var upAt = 0L
    private var heard = false
    private var loggedUp = false
    private val finishCbs = mutableListOf<() -> Unit>()
    private var finishTimer: Runnable? = null
    private var awaitingFinalize = false

    fun start(lang: String, pauseMs: Int) {
        if (want && this.lang == lang && this.pauseMs == pauseMs) return
        if (want) close()
        this.lang = lang
        this.pauseMs = pauseMs
        want = true
        fails = 0
        buf = ""
        audio.clear(); ctl.clear()
        connect()
    }

    /** recording thread */
    fun feed(b: ByteArray) {
        if (!want) return
        if (!audio.offer(b)) { audio.poll(); audio.offer(b) }
    }

    /** the words heard so far become a finished sentence now */
    fun commit() {
        val t = buf
        buf = ""
        if (t.isNotBlank()) cb.onDgSentence(t)
        cb.onDgLive("")
    }

    /** Asks Deepgram to finish what it has heard, delivers it, then calls [then]. The connection stays open. */
    fun finish(then: () -> Unit) {
        finishCbs.add(then)
        if (finishTimer != null) return
        val t = Runnable { endFinish() }
        finishTimer = t
        when {
            up -> { ctl.offer("{\"type\":\"Finalize\"}"); awaitingFinalize = true; h.postDelayed(t, 1800) }
            want -> h.postDelayed(t, 4000) // still connecting; Finalize goes out once connected
            else -> h.post(t)
        }
    }

    private fun endFinish() {
        finishTimer?.let { h.removeCallbacks(it) }
        finishTimer = null
        awaitingFinalize = false
        commit()
        val l = finishCbs.toList()
        finishCbs.clear()
        l.forEach { it() }
    }

    /** stops streaming and closes the connection */
    fun close() {
        want = false
        up = false
        gen++
        val w = ws
        ws = null
        audio.clear(); ctl.clear()
        if (finishTimer != null) endFinish() else commit()
        if (w != null) Thread({
            try { w.sendText("{\"type\":\"CloseStream\"}") } catch (_: Exception) { }
            try { Thread.sleep(400) } catch (_: InterruptedException) { }
            w.close()
            w.shutdown()
        }, "dg-close").start()
    }

    private fun url(): String {
        val endpointing = when { pauseMs <= 800 -> 300; pauseMs >= 2000 -> 900; else -> 500 }
        return "wss://api.deepgram.com/v1/listen?model=$model&language=" + (if (lang == "en") "en" else "ja") +
            "&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&punctuate=true" +
            "&endpointing=$endpointing&utterance_end_ms=" + max(1000, pauseMs)
    }

    private fun connect() {
        if (!want) return
        val g = ++gen
        up = false
        val key = Prefs.dgKey
        val u = url()
        val ja = lang != "en"
        Thread({
            val w: Ws
            try {
                w = Ws.connect(u, mapOf("Authorization" to "Token $key", "User-Agent" to "notes-android"), 12000)
            } catch (e: Ws.HandshakeErr) {
                h.post { if (g == gen) refused(e.code, e.body) }
                return@Thread
            } catch (e: Exception) {
                h.post { if (g == gen) dropped("连不上：" + Tr.netWhy(e), false) }
                return@Thread
            }
            if (g != gen) { w.close(); w.shutdown(); return@Thread }
            ws = w
            try { w.readTimeoutMs = 30000 } catch (_: Exception) { }
            h.post { if (g == gen) onUp() }
            val sender = Thread({ sendLoop(g, w) }, "dg-send").apply { start() }
            var err: String? = null
            try {
                w.readLoop { txt -> onMessage(g, txt, ja) }
            } catch (e: Exception) {
                err = Tr.netWhy(e)
            }
            w.shutdown()
            sender.interrupt()
            val reason = err ?: ("关闭 " + w.closeCode + (if (w.closeReason.isNotEmpty()) " " + w.closeReason.take(80) else ""))
            h.post { if (g == gen) dropped(reason, true) }
        }, "dg-conn").start()
    }

    /** sending thread: audio in ~0.1 s pieces, then control messages; a keep-alive when there is no audio */
    private fun sendLoop(g: Int, w: Ws) {
        val batch = ByteArrayOutputStream()
        var last = SystemClock.elapsedRealtime()
        try {
            while (g == gen) {
                val first = audio.poll(200, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.reset()
                    batch.write(first, 0, first.size)
                    while (batch.size() < 6400) {
                        val nx = audio.poll() ?: break
                        batch.write(nx, 0, nx.size)
                    }
                    w.sendBinary(batch.toByteArray())
                    last = SystemClock.elapsedRealtime()
                }
                if (audio.size < 2) {
                    while (true) { val c = ctl.poll() ?: break; w.sendText(c); last = SystemClock.elapsedRealtime() }
                }
                if (SystemClock.elapsedRealtime() - last > 5000) {
                    w.sendText("{\"type\":\"KeepAlive\"}")
                    last = SystemClock.elapsedRealtime()
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            // the reading thread notices the broken connection and reconnects
        }
    }

    /** reading thread */
    private fun onMessage(g: Int, txt: String, ja: Boolean) {
        val o = try { JSONObject(txt) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "Results" -> {
                val alt = o.optJSONObject("channel")?.optJSONArray("alternatives")?.optJSONObject(0)
                var t = Txt.clean(alt?.optString("transcript"))
                if (ja) t = t.replace(CJK_SPACE, "")
                val isFinal = o.optBoolean("is_final")
                val speechFinal = o.optBoolean("speech_final")
                val fromFinalize = o.optBoolean("from_finalize")
                h.post { if (g == gen) results(t, isFinal, speechFinal, fromFinalize) }
            }
            "UtteranceEnd" -> h.post { if (g == gen) commit() }
            "Error" -> {
                val m = o.optString("description", o.optString("message", txt)).take(100)
                h.post { Hub.dl("Deepgram 出错：$m") }
            }
        }
    }

    private fun results(t: String, isFinal: Boolean, speechFinal: Boolean, fromFinalize: Boolean) {
        heard = true
        fails = 0
        if (isFinal) {
            if (t.isNotEmpty()) buf = Txt.join(buf, t, lang)
            if (speechFinal) commit() else cb.onDgLive(buf)
            if (fromFinalize && finishTimer != null) endFinish()
        } else {
            cb.onDgLive(Txt.join(buf, t, lang))
        }
    }

    private fun onUp() {
        up = true
        upAt = SystemClock.elapsedRealtime()
        heard = false
        if (!loggedUp) { loggedUp = true; Hub.dl("Deepgram 已连接（$model）") }
        cb.onDgUp()
        val t = finishTimer
        if (t != null && !awaitingFinalize) {
            ctl.offer("{\"type\":\"Finalize\"}")
            awaitingFinalize = true
            h.removeCallbacks(t)
            h.postDelayed(t, 2500)
        }
    }

    private fun errMsg(body: String): String = try {
        val o = JSONObject(body)
        o.optString("err_msg", o.optString("message", o.optString("err_code", body)))
    } catch (_: Exception) { body }.take(100)

    private fun refused(code: Int, body: String) {
        Hub.dl("Deepgram 拒绝连接：HTTP $code ${errMsg(body)}")
        when {
            code == 401 || code == 403 -> fail("Deepgram Key 无效")
            code == 402 -> fail("Deepgram 余额用完了")
            code == 400 && model == "nova-3" -> { model = "nova-2"; Hub.dl("改用 Deepgram nova-2 模型"); connect() }
            code == 400 -> fail("Deepgram 不支持现在的设置")
            else -> dropped("HTTP $code", false)
        }
    }

    private fun dropped(reason: String, wasUp: Boolean) {
        ws = null
        up = false
        if (!want) return
        if (awaitingFinalize) endFinish() // the answer to Finalize will not come on this connection
        val quick = !wasUp || (!heard && SystemClock.elapsedRealtime() - upAt < 5000)
        if (quick) fails++ else fails = 0
        Hub.dl("Deepgram 断开：$reason")
        if (fails >= 4) {
            fail(if (wasUp) "Deepgram 连接一直断开" else "连不上 Deepgram")
            return
        }
        if (wasUp && quick && fails == 2 && model == "nova-3") model = "nova-2"
        val g = gen
        h.postDelayed({ if (want && gen == g) connect() }, if (fails == 0) 200L else 1000L * fails)
    }

    private fun fail(reason: String) {
        close()
        cb.onDgFail(reason)
    }
}
