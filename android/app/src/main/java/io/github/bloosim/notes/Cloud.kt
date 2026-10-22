package io.github.bloosim.notes

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ApiErr(val kind: String, msg: String, val retryMs: Long = 0) : Exception(msg.take(300))

object Http {
    class Resp(val code: Int, val text: String, val retryAfter: String?)

    fun call(url: String, method: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, readTimeout: Int = 45000): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = readTimeout
            c.requestMethod = method
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text, c.getHeaderField("retry-after"))
        } finally {
            c.disconnect()
        }
    }
}

/** 16 kHz mono PCM -> WAV file bytes, gently amplified when the recording is quiet */
object Wav {
    fun encode(pcmIn: ShortArray): ByteArray {
        var peak = 1
        for (s in pcmIn) peak = max(peak, abs(s.toInt()))
        val gain = min(6.0, 26000.0 / peak).let { if (it < 1.3) 1.0 else it }
        val n = pcmIn.size
        val out = ByteArray(44 + n * 2)
        fun s4(o: Int, s: String) { for (i in 0 until 4) out[o + i] = s[i].code.toByte() }
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        s4(0, "RIFF"); i32(4, 36 + n * 2); s4(8, "WAVE"); s4(12, "fmt ")
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, 16000); i32(28, 32000); i16(32, 2); i16(34, 16)
        s4(36, "data"); i32(40, n * 2)
        var o = 44
        for (s in pcmIn) {
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); o += 2
        }
        return out
    }
}

/**
 * The free AI services, tried in order: Gemini (hears the audio and translates it in one go),
 * then Groq and Mistral (speech-to-text only; the text is translated afterwards).
 * A service that runs out of quota is paused until its quota comes back and the next one is used.
 */
object Cloud {
    class Prov(val id: String, val name: String, val models: List<String>, val url: String?) {
        fun key(): String = when (id) { "g" -> Prefs.gKey; "q" -> Prefs.qKey; else -> Prefs.mKey }
    }

    class Result(val src: String, val zh: String, val by: String)

    val G_MODELS = listOf("gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-latest", "gemini-3.6-flash", "gemini-2.5-flash")
    val PROV = listOf(
        Prov("g", "Gemini", G_MODELS, null),
        Prov("q", "Groq", listOf("whisper-large-v3", "whisper-large-v3-turbo"), "https://api.groq.com/openai/v1/audio/transcriptions"),
        Prov("m", "Mistral", listOf("voxtral-mini-latest"), "https://api.mistral.ai/v1/audio/transcriptions")
    )

    @Volatile var lastOkBy = ""
    private val noThink = ConcurrentHashMap<String, Boolean>()
    private var pst: JSONObject? = null

    @Synchronized private fun state(): JSONObject {
        if (pst == null) pst = try { JSONObject(Prefs.pst) } catch (e: Exception) { JSONObject() }
        return pst!!
    }

    @Synchronized fun cooling(k: String): Boolean {
        val o = state().optJSONObject(k) ?: return false
        return o.optLong("until") > System.currentTimeMillis()
    }

    @Synchronized private fun why(k: String): String? {
        val o = state().optJSONObject(k) ?: return null
        return if (o.optLong("until") > System.currentTimeMillis()) o.optString("why") else null
    }

    @Synchronized private fun until(k: String): Long = state().optJSONObject(k)?.optLong("until") ?: 0

    @Synchronized fun pstSet(k: String, why: String, until: Long) {
        state().put(k, JSONObject().put("why", why).put("until", until))
        Prefs.pst = state().toString()
    }

    /** a new key gets a fresh start; a used-up daily quota is kept unless [all] */
    @Synchronized fun clearFor(id: String, all: Boolean = true) {
        val s = state()
        val keys = mutableListOf<String>()
        s.keys().forEach { keys.add(it) }
        keys.filter { it == id || it.startsWith("$id:") }.forEach { k ->
            if (all || s.optJSONObject(k)?.optString("why") != "day") s.remove(k)
        }
        Prefs.pst = s.toString()
    }

    fun usable(p: Prov) = p.key().isNotBlank() && !cooling(p.id) && p.models.any { !cooling("${p.id}:$it") }
    fun anyKey() = PROV.any { it.key().isNotBlank() }
    fun anyUsable() = PROV.any { usable(it) }

    fun status(p: Prov): String {
        if (p.key().isBlank()) return "未填写"
        when (why(p.id)) {
            "key" -> return "Key 无效"
            "net" -> return "连不上，稍后自动重试"
        }
        val cool = p.models.filter { cooling("${p.id}:$it") }
        if (cool.size == p.models.size) {
            val whys = cool.map { why("${p.id}:$it") }
            if (whys.all { it == "model" || it == "bad" }) return "没有能用的模型"
            if (whys.any { it == "day" }) return "今天的免费额度用完了，明天自动恢复"
            val soon = cool.minOf { until("${p.id}:$it") }
            return "额度暂时用完，约 ${max(1L, (soon - System.currentTimeMillis()) / 1000)} 秒后恢复"
        }
        return if (lastOkBy == p.name) "可用 ✓ 正在使用" else "可用"
    }

    private fun nextPacificMidnight(): Long {
        val zone = ZoneId.of("America/Los_Angeles")
        return ZonedDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 30_000
    }

    private fun classify(r: Http.Resp): ApiErr {
        var msg = r.text
        var details: JSONArray? = null
        try {
            val o = JSONObject(r.text)
            val e = o.opt("error")
            msg = when (e) {
                is JSONObject -> { details = e.optJSONArray("details"); e.optString("message", r.text) }
                is String -> e
                else -> o.optString("message", o.optString("detail", r.text))
            }
        } catch (_: Exception) { }
        val all = msg + " " + (details?.toString() ?: "")
        if (r.code == 401 || r.code == 403 || (r.code == 400 && Regex("(?i)api[ _-]?key").containsMatchIn(msg))) return ApiErr("key", msg)
        if (r.code == 429) {
            var retry = 0L
            details?.let { for (i in 0 until it.length()) { val d = it.optJSONObject(i); val rd = d?.optString("retryDelay") ?: ""; if (rd.isNotEmpty()) retry = ((rd.trimEnd('s').toDoubleOrNull() ?: 0.0) * 1000).toLong() } }
            if (retry == 0L) r.retryAfter?.toDoubleOrNull()?.let { retry = (it * 1000).toLong() }
            if (retry == 0L) Regex("(?i)try again in ([\\d.]+)\\s*(ms|s|m)\\b").find(msg)?.let { m ->
                val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                retry = (v * when (m.groupValues[2].lowercase()) { "ms" -> 1.0; "m" -> 60000.0; else -> 1000.0 }).toLong()
            }
            val perDay = Regex("(?i)per ?day|PerDay|RPD|daily").containsMatchIn(all)
            return ApiErr(if (perDay) "day" else "quota", msg, if (retry > 0) retry else 60_000)
        }
        if (r.code == 404) return ApiErr("model", msg)
        if (r.code == 400 || r.code == 422) return ApiErr("bad", msg)
        return ApiErr("server", "HTTP ${r.code} $msg")
    }

    private fun send(url: String, headers: Map<String, String>, body: ByteArray, ct: String): String {
        val r = try { Http.call(url, "POST", headers, body, ct) } catch (e: IOException) { throw ApiErr("net", e.message ?: "network") }
        if (r.code in 200..299) return r.text
        throw classify(r)
    }

    private fun setModelState(k: String, e: ApiErr) {
        val now = System.currentTimeMillis()
        val until = when (e.kind) {
            "day" -> nextPacificMidnight()
            "quota" -> now + min(if (e.retryMs > 0) e.retryMs else 60_000L, 30 * 60_000L)
            "model", "bad" -> now + 24 * 3600_000L
            else -> now + 60_000
        }
        pstSet(k, e.kind, until)
        Hub.dl("$k ${e.kind}：${e.message?.take(80)}")
    }

    private fun orderModels(pid: String, models: List<String>): List<String> {
        val last = try { JSONObject(Prefs.lastModels).optString(pid) } catch (e: Exception) { "" }
        return if (last.isNotEmpty() && models.contains(last)) listOf(last) + models.filter { it != last } else models
    }

    private fun rememberModel(pid: String, model: String) {
        val o = try { JSONObject(Prefs.lastModels) } catch (e: Exception) { JSONObject() }
        if (o.optString(pid) != model) { o.put(pid, model); Prefs.lastModels = o.toString() }
    }

    private fun parseLoose(t: String): JSONObject {
        try { return JSONObject(t) } catch (_: Exception) { }
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) try { return JSONObject(t.substring(a, b + 1)) } catch (_: Exception) { }
        return JSONObject().put("src", t)
    }

    private fun geminiPrompt(lang: String, ctx: String): String {
        val ln = if (lang == "en") "English" else "Japanese"
        return "This audio is one segment of a spoken conversation or meeting in Japan, recorded on a phone. The listener is a Chinese speaker who does not understand $ln well.\n" +
            "1. \"src\": transcribe exactly what is said, in the original language ($ln). Write only what is actually spoken. Leave out filler sounds such as えっと, あの, うーん, um, uh. Use normal punctuation.\n" +
            "2. \"zh\": translate \"src\" into natural Simplified Chinese.\n" +
            "If there is no clear speech in the audio, return empty strings for both.\n" +
            (if (ctx.isNotBlank()) "Earlier in the same conversation (for names and topic only; do not repeat it):\n$ctx\n" else "") +
            "Reply with JSON only: {\"src\": \"...\", \"zh\": \"...\"}"
    }

    private fun gemini(wav: ByteArray, lang: String, ctx: String): Result {
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val prompt = geminiPrompt(lang, ctx)
        var last: ApiErr? = null
        for (model in orderModels("g", G_MODELS)) {
            if (cooling("g:$model")) continue
            var attempt = 0
            while (attempt < 2) {
                val think = attempt == 0 && noThink[model] != true
                val gen = JSONObject().put("temperature", 0).put("responseMimeType", "application/json")
                if (think) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val parts = JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64)))
                    .put(JSONObject().put("text", prompt))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))).put("generationConfig", gen)
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" + URLEncoder.encode(Prefs.gKey, "UTF-8")
                    val txt = send(url, emptyMap(), body.toString().toByteArray(), "application/json")
                    val cands = JSONObject(txt).optJSONArray("candidates")
                    val ps = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (ps != null) for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text") ?: "")
                    val out = parseLoose(sb.toString())
                    rememberModel("g", model)
                    return Result(Txt.clean(out.optString("src", out.optString("transcript"))), Txt.clean(out.optString("zh", out.optString("translation"))), "Gemini")
                } catch (e: ApiErr) {
                    last = e
                    if (e.kind == "bad" && think && Regex("(?i)think").containsMatchIn(e.message ?: "")) { noThink[model] = true; attempt++; continue }
                    if (e.kind == "key" || e.kind == "net") throw e
                    setModelState("g:$model", e)
                    break
                } catch (e: Exception) {
                    last = ApiErr("server", e.message ?: "error")
                    setModelState("g:$model", last)
                    break
                }
            }
        }
        throw last ?: ApiErr("quota", "所有模型都在冷却")
    }

    private fun multipart(fields: List<Pair<String, String>>, wav: ByteArray, boundary: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        fields.forEach { (k, v) -> w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
        out.write(wav)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun openAiCompat(p: Prov, wav: ByteArray, lang: String, ctx: String): Result {
        var last: ApiErr? = null
        for (model in orderModels(p.id, p.models)) {
            if (cooling("${p.id}:$model")) continue
            val fields = mutableListOf("model" to model, "language" to (if (lang == "en") "en" else "ja"))
            if (p.id == "q") {
                fields.add("response_format" to "json"); fields.add("temperature" to "0")
                val c = ctx.takeLast(200)
                if (c.isNotBlank()) fields.add("prompt" to c)
            }
            val boundary = "----notes" + System.nanoTime()
            try {
                val txt = send(p.url!!, mapOf("Authorization" to "Bearer " + p.key()), multipart(fields, wav, boundary), "multipart/form-data; boundary=$boundary")
                rememberModel(p.id, model)
                return Result(Txt.clean(JSONObject(txt).optString("text")), "", p.name)
            } catch (e: ApiErr) {
                last = e
                if (e.kind == "key" || e.kind == "net") throw e
                setModelState("${p.id}:$model", e)
            } catch (e: Exception) {
                last = ApiErr("server", e.message ?: "error")
                setModelState("${p.id}:$model", last)
            }
        }
        throw last ?: ApiErr("quota", "冷却中")
    }

    fun runOne(p: Prov, wav: ByteArray, lang: String, ctx: String): Result =
        if (p.id == "g") gemini(wav, lang, ctx) else openAiCompat(p, wav, lang, ctx)

    fun provError(p: Prov, e: ApiErr) {
        when (e.kind) {
            "key" -> pstSet(p.id, "key", Long.MAX_VALUE)
            "net" -> pstSet(p.id, "net", System.currentTimeMillis() + 30_000)
        }
        Hub.dl("${p.name} ${e.kind}：${e.message?.take(80)}")
    }

    /** Runs on a worker thread. Null when no service could answer. */
    fun transcribe(pcm: ShortArray, lang: String, ctx: String): Result? {
        val wav = Wav.encode(pcm)
        for (p in PROV) {
            if (!usable(p)) continue
            try {
                val r = runOne(p, wav, lang, ctx)
                lastOkBy = p.name
                return r
            } catch (e: ApiErr) {
                provError(p, e)
            }
        }
        return null
    }

    /** Checks one service with a second of near-silence. Returns a line for the settings screen. */
    fun check(p: Prov): String {
        clearFor(p.id, all = false)
        val pcm = ShortArray(16000) { ((Math.random() - 0.5) * 60).toInt().toShort() }
        return try {
            runOne(p, Wav.encode(pcm), Prefs.lang, "")
            "${p.name}：可以用 ✓"
        } catch (e: ApiErr) {
            if (e.kind == "key" || e.kind == "net") provError(p, e)
            "${p.name}：" + when (e.kind) {
                "key" -> "Key 无效"; "net" -> "连不上网络"; "quota" -> "额度暂时用完"; "day" -> "今天的额度用完了"
                "model" -> "没有能用的模型"; "bad" -> "请求被拒绝"; else -> "服务器出错，稍后再试"
            }
        }
    }
}

/** Google Translate for text that the AI service did not translate itself. */
object Tr {
    private fun gtx(text: String, sl: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=$sl&tl=zh-CN&q=" + URLEncoder.encode(text.take(1500), "UTF-8")
        val r = Http.call(url, "GET", emptyMap(), null, null, 20000)
        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
        val segs = JSONArray(r.text).optJSONArray(0) ?: throw IOException("empty")
        val sb = StringBuilder()
        for (i in 0 until segs.length()) { val s = segs.optJSONArray(i); if (s != null && !s.isNull(0)) sb.append(s.optString(0)) }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw IOException("empty")
        return out
    }

    /** Worker thread. The previous sentence goes along as context; only the new sentence's translation is kept. */
    fun translate(text: String, prev: String, lang: String): String? {
        val q = Txt.forTranslate(text, lang)
        if (q.isEmpty()) return "（嗯…）"
        val sl = if (lang == "en") "en" else "ja"
        if (prev.isNotBlank()) {
            try {
                val parts = gtx(prev + "\n" + q, sl).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size == 2) return parts[1]
            } catch (_: Exception) { }
        }
        repeat(2) {
            try { return gtx(q, sl) } catch (_: Exception) { try { Thread.sleep(500) } catch (_: InterruptedException) { } }
        }
        return null
    }
}
