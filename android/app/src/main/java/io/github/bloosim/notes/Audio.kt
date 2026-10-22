package io.github.bloosim.notes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Records the microphone at 16 kHz mono in 20 ms frames on its own thread. Recording this way does not
 *  take the audio focus, so music or videos in other apps keep playing. */
class Capture(private val onFrame: (ShortArray, Float) -> Unit) {
    @Volatile var running = false
        private set
    private var thread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 32000))
        } catch (e: SecurityException) { Hub.dl("录音权限被拒绝"); return false } catch (e: Exception) { Hub.dl("打不开麦克风：" + e.message); return false }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); Hub.dl("麦克风初始化失败"); return false }
        try { ar.startRecording() } catch (e: Exception) { ar.release(); Hub.dl("开始录音失败：" + e.message); return false }
        running = true
        thread = Thread {
            val buf = ShortArray(320)
            try {
                while (running) {
                    var got = 0
                    while (got < 320 && running) {
                        val r = ar.read(buf, got, 320 - got)
                        if (r < 0) { running = false; Hub.dl("录音中断：$r"); break }
                        if (r == 0) break
                        got += r
                    }
                    if (got < 320) continue
                    var sum = 0.0
                    for (s in buf) sum += s.toDouble() * s
                    onFrame(buf.copyOf(), (sqrt(sum / 320) / 32768.0).toFloat())
                }
            } catch (e: Exception) {
                Hub.dl("录音出错：" + e.message)
            } finally {
                try { ar.stop() } catch (_: Exception) { }
                ar.release()
            }
        }.apply { name = "capture"; start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }
}

/** Finds where speech starts and ends (by loudness against the room's background level) and hands out
 *  one piece of audio per sentence. While the hold button is pressed, everything goes into one piece. */
class Vad(private val listener: Listener) {
    interface Listener {
        fun onSpeechStart()
        fun onSegment(pcm: ShortArray, voiced: Int)
    }

    @Volatile var enabled = false
    @Volatile var pauseMs = 1300
    /** when voice was last heard (elapsedRealtime), whether or not sentences are being cut */
    @Volatile var lastVoiceAt = 0L
    private var vh = 0
    private var noise = 0.004f
    private val pre = ArrayDeque<ShortArray>()
    private var hot = 0
    private var seg: MutableList<ShortArray>? = null
    private var voiced = 0
    private var silence = 0
    private var hold: MutableList<ShortArray>? = null
    private var holdVoiced = 0

    @Synchronized fun frame(f: ShortArray, rms: Float) {
        Hub.level = max(Hub.level * 0.8f, min(1f, rms * 6))
        val th = max(0.01f, noise * 3)
        pre.addLast(f)
        if (pre.size > 25) pre.removeFirst() // 0.5 s from before speech starts
        if (rms > th) { if (++vh >= 3) lastVoiceAt = SystemClock.elapsedRealtime() } else vh = 0
        val h = hold
        if (h != null) { h.add(f); if (rms > th) holdVoiced++; return }
        val s = seg
        if (s == null) {
            noise = noise * 0.98f + min(rms, noise * 2 + 0.002f) * 0.02f // background level, tracked between sentences
            if (!enabled) { hot = 0; return }
            if (rms > th) {
                if (++hot >= 4) {
                    seg = ArrayList(pre); voiced = hot; silence = 0; hot = 0
                    listener.onSpeechStart()
                }
            } else hot = 0
            return
        }
        s.add(f)
        if (rms > th * 0.8f) { voiced++; silence = 0 } else silence++
        if (silence * 20 >= pauseMs || s.size * 20 >= 25000) cut()
    }

    private fun cut() {
        val s = seg ?: return
        seg = null
        if (voiced < 12) return // under 0.25 s of voice: a cough or a bump
        val keep = s.size - max(0, silence - 15) // keep 0.3 s of the closing pause
        listener.onSegment(join(s.subList(0, keep)), voiced)
    }

    /** finish the sentence in progress now (when stopping or when the hold button is pressed) */
    @Synchronized fun flush() { if (seg != null) cut() }

    @Synchronized fun beginHold() {
        flush()
        hold = ArrayList(pre.toList().takeLast(15)) // 0.3 s before the press
        holdVoiced = 0
    }

    @Synchronized fun endHold(): Pair<ShortArray, Int>? {
        val h = hold ?: return null
        hold = null
        return if (h.isEmpty()) null else join(h) to holdVoiced
    }

    private fun join(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.size * 320)
        frames.forEachIndexed { i, f -> System.arraycopy(f, 0, out, i * 320, 320) }
        return out
    }
}

/**
 * The offline language pack of the phone's speech service. Recognizing our own recording (which keeps
 * working in the background) is done on the phone, so it needs the pack for the other person's language.
 * Android 13+.
 */
object LangPack {
    /** results: installed, pending, missing, unsupported, downloaded, scheduled, requested, error:… */
    fun check(ctx: Context, lang: String, download: Boolean, done: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { done("unsupported"); return }
        val comp = PhoneAsr.pickService(ctx)
        val r = try {
            if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp) else SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) { done("error:" + e.javaClass.simpleName); return }
        val h = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(st: String) {
            if (finished) return
            finished = true
            done(st)
            h.postDelayed({ try { r.destroy() } catch (_: Exception) { } }, 3000)
        }
        h.postDelayed({ finish("error:timeout") }, 15000)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        val primary = lang.substringBefore('-').lowercase()
        fun has(l: List<String>) = l.any { it.lowercase().replace('_', '-').substringBefore('-') == primary }
        try {
            r.checkRecognitionSupport(i, ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    when {
                        has(s.installedOnDeviceLanguages) -> finish("installed")
                        has(s.pendingOnDeviceLanguages) -> finish("pending")
                        !has(s.supportedOnDeviceLanguages) -> finish("unsupported")
                        !download -> finish("missing")
                        else -> startDownload(ctx, r, i, ::finish)
                    }
                }
                override fun onError(error: Int) { finish("error:$error") }
            })
        } catch (e: Exception) { finish("error:" + e.javaClass.simpleName) }
    }

    private fun startDownload(ctx: Context, r: SpeechRecognizer, i: Intent, finish: (String) -> Unit) {
        Hub.dl("开始下载离线语言包")
        fun plain() {
            try { r.triggerModelDownload(i); finish("requested") } catch (e: Exception) { finish("error:" + e.javaClass.simpleName) }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                r.triggerModelDownload(i, ctx.mainExecutor, object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) { }
                    override fun onSuccess() { finish("downloaded") }
                    override fun onScheduled() { finish("scheduled") }
                    override fun onError(error: Int) { if (error == 15) plain() else finish("error:$error") }
                })
            } catch (e: Exception) { plain() }
        } else plain()
    }

    fun describe(st: String, langName: String): String = when {
        st == "installed" || st == "downloaded" -> "${langName}离线包已安装 ✓"
        st == "pending" || st == "scheduled" || st == "requested" -> "${langName}离线包正在下载…"
        st == "missing" -> "${langName}离线包还没下载"
        st == "unsupported" -> "手机的语音服务不支持${langName}离线识别"
        else -> "查不到离线包状态（$st）"
    }
}

/**
 * The phone's own speech recognizer (Google). On Android 13+ it is fed from our recording through a pipe,
 * so it keeps working in the background and on phones that mute other apps' microphones.
 * Each session is one sentence; a new one starts right after, with a fresh recognizer (as Chrome does),
 * and starts with the audio the last session did not use. A watchdog restarts a session that hangs.
 * Main thread only, except [feed].
 */
class PhoneAsr(private val ctx: Context, private val cb: Callback, private val voiceAt: () -> Long) {
    interface Callback {
        fun onPartial(t: String)
        fun onFinal(t: String)
        fun onGiveUp(reason: String)
        /** a session ended, with or without a final result */
        fun onSessionEnd() {}
        /** result of checking (and downloading) the offline language pack */
        fun onPack(st: String) {}
    }

    companion object {
        fun pickService(ctx: Context): ComponentName? {
            val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            for (pkg in listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")) {
                val ri = list.firstOrNull { it.serviceInfo?.packageName == pkg } ?: continue
                return ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name)
            }
            return null
        }
    }

    /** the language whose offline pack was last checked, and the answer */
    private var packLang = ""
    var packState = ""
        private set

    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    /** sessions run on the current recognizer */
    private var used = 0
    var active = false
        private set
    var piped = false
        private set
    var pauseMs = 1300
    private var lang = "ja-JP"
    @Volatile private var writer: PipeWriter? = null
    private var readSide: ParcelFileDescriptor? = null
    private val lock = Any()
    /** the last 3 s of our recording with the time each piece arrived */
    private val recent = ArrayDeque<Pair<Long, ByteArray>>()
    private var fails = 0
    private var quick = 0
    private var hangs = 0
    private var session = 0
    private var startedAt = 0L
    private var lastEvt = 0L
    private var ready = false
    private var gotSomething = false
    private var speechEndAt = 0L
    private var endedAt = 0L
    /** when we ended the current sentence ourselves */
    private var cutAt = 0L
    private var silentLogged = 0

    private var restartPending = false
    private val restart = Runnable { restartPending = false; begin() }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            check()
            h.postDelayed(this, 1000)
        }
    }

    val pipeSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun start(lang: String, usePipe: Boolean) {
        this.lang = lang
        piped = usePipe && pipeSupported
        active = true; fails = 0; quick = 0; hangs = 0
        h.removeCallbacks(restart); h.removeCallbacks(watchdog); restartPending = false
        try { sr?.cancel() } catch (_: Exception) { }
        speechEndAt = 0; endedAt = 0
        Hub.dl("手机识别开始（" + (if (piped) "用 App 的录音" else "识别服务自己录音") + "，$lang）")
        if (piped) ensurePack()
        begin()
        h.postDelayed(watchdog, 1000)
    }

    fun stop() {
        active = false
        h.removeCallbacks(restart); h.removeCallbacks(watchdog); restartPending = false
        try { sr?.cancel() } catch (_: Exception) { }
        closePipe()
        synchronized(lock) { recent.clear() }
    }

    /** end the current sentence now (at a pause we noticed); its final result still arrives */
    fun cut() {
        if (!active) return
        cutAt = SystemClock.elapsedRealtime()
        synchronized(lock) { writer?.close() }
        try { sr?.stopListening() } catch (_: Exception) { }
    }

    fun destroy() {
        stop()
        try { sr?.destroy() } catch (_: Exception) { }
        sr = null
    }

    /** audio from our recording (16 kHz mono PCM16 little-endian); called on the recording thread */
    fun feed(bytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            recent.addLast(now to bytes)
            while (recent.size > 150) recent.removeFirst()
            val w = writer
            if (w != null && w.open) w.offer(bytes)
        }
    }

    private fun pickService(): ComponentName? = pickService(ctx)

    /** our recording is recognized on the phone: make sure the language pack is there, download it if not */
    private fun ensurePack() {
        if (packLang == lang && (packState == "installed" || packState == "downloaded")) return
        packLang = lang
        val forLang = lang
        LangPack.check(ctx, forLang, true) { st ->
            if (forLang != lang) return@check
            packState = st
            Hub.dl("离线语言包 $forLang：$st")
            if (active && piped && st != "installed" && st != "downloaded" && !st.startsWith("error")) {
                giveUp("手机上没有这个语言的离线包（$st）")
            }
            cb.onPack(st)
        }
    }

    /** false when the language pack is known to be missing, so recognizing our recording cannot work */
    fun pipeUsable(forLang: String): Boolean =
        pipeSupported && !(packLang == forLang && packState.isNotEmpty() && packState != "installed" && packState != "downloaded" && !packState.startsWith("error"))

    /** checks again later whether a pack being downloaded is ready */
    fun recheckPack(done: (Boolean) -> Unit) {
        val forLang = lang
        LangPack.check(ctx, forLang, false) { st ->
            if (forLang == lang) { packLang = forLang; packState = st }
            done(st == "installed")
        }
    }

    private var loggedService = false
    private fun create(): SpeechRecognizer? {
        val comp = pickService()
        return try {
            val r = if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp) else SpeechRecognizer.createSpeechRecognizer(ctx)
            if (!loggedService) { loggedService = true; Hub.dl("手机识别服务：" + (comp?.packageName ?: "系统默认")) }
            r.setRecognitionListener(listener)
            r
        } catch (e: Exception) { Hub.dl("创建手机识别失败：" + e.message); null }
    }

    private fun dropRecognizer() {
        try { sr?.cancel() } catch (_: Exception) { }
        try { sr?.destroy() } catch (_: Exception) { }
        sr = null
    }

    private fun begin() {
        if (!active) return
        if (piped && used >= 1) dropRecognizer() // a fresh recognizer for every sentence
        if (sr == null) { sr = create(); used = 0 }
        val r = sr ?: run { active = false; h.removeCallbacks(watchdog); cb.onGiveUp("这台手机没有可用的语音识别服务"); return }
        closePipe()
        val now = SystemClock.elapsedRealtime()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs + 300L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs + 300L)
        if (piped && Build.VERSION.SDK_INT >= 33) {
            try {
                val p = ParcelFileDescriptor.createPipe()
                readSide = p[0]
                val w = PipeWriter(p[1]).also { it.start() }
                // start with what was said after the last session stopped listening
                val endRef = when { cutAt > 0 -> cutAt + 100; speechEndAt > 0 -> speechEndAt; endedAt > 0 -> endedAt; else -> now }
                val from = max(endRef - 200, now - 3000)
                synchronized(lock) {
                    for ((t, b) in recent) if (t >= from) w.offer(b)
                    writer = w
                }
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            } catch (e: IOException) { Hub.dl("建立音频管道失败：" + e.message) }
        }
        session++; used++
        startedAt = now; lastEvt = now; ready = false; gotSomething = false
        speechEndAt = 0; cutAt = 0
        try { r.startListening(i) } catch (e: Exception) { Hub.dl("手机识别启动失败：" + e.message); dropRecognizer(); again(800) }
    }

    private fun again(delay: Long) {
        if (!active) return
        h.removeCallbacks(restart)
        restartPending = true
        h.postDelayed(restart, delay)
    }

    /** restarts a session that stopped answering */
    private fun check() {
        val now = SystemClock.elapsedRealtime()
        if (restartPending) return
        val stuck = when {
            !ready && now - startedAt > 5000 -> "没有响应"
            ready && now - lastEvt > 8000 && voiceAt() > lastEvt + 1500 && now - voiceAt() < 6000 -> "有声音但没有结果"
            now - startedAt > 75_000 -> "太久"
            else -> return
        }
        if (stuck == "太久" && gotSomething && cutAt == 0L) { cut(); return } // a very long sentence: end it so its text comes back
        if (stuck != "太久") hangs++
        Hub.dl("手机识别#$session $stuck，重新开始" + (if (hangs > 0) "（第 $hangs 次）" else ""))
        dropRecognizer()
        if (piped && hangs >= 3) { giveUp("手机识别收不到这里的录音（$stuck）"); return }
        endedAt = now
        again(150)
    }

    private fun closePipe() {
        synchronized(lock) { writer?.close(); writer = null }
        try { readSide?.close() } catch (_: Exception) { }
        readSide = null
    }

    private fun text(b: Bundle?): String = Txt.clean(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

    private fun errName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到说话"
        SpeechRecognizer.ERROR_NO_MATCH -> "没识别出内容"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有权限"
        10 -> "请求太多"
        11 -> "服务断开"
        12 -> "不支持这个语言"
        13 -> "语言包不可用"
        else -> "错误"
    } + "($e)"

    private fun touch() { lastEvt = SystemClock.elapsedRealtime() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            ready = true; touch()
            if (session <= 2) Hub.dl("手机识别#$session 就绪")
        }
        override fun onBeginningOfSpeech() { touch() }
        override fun onRmsChanged(rmsdB: Float) { }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { speechEndAt = SystemClock.elapsedRealtime(); touch() }
        override fun onEvent(eventType: Int, params: Bundle?) { }

        override fun onPartialResults(partialResults: Bundle?) {
            touch()
            val t = text(partialResults)
            if (t.isNotEmpty()) { gotSomething = true; hangs = 0; cb.onPartial(t) }
        }

        override fun onResults(results: Bundle?) {
            touch()
            endedAt = SystemClock.elapsedRealtime()
            val t = text(results)
            fails = 0; quick = 0; hangs = 0
            if (session <= 4) Hub.dl("手机识别#$session 结果：" + t.take(14).ifEmpty { "（空）" })
            if (t.isNotEmpty()) cb.onFinal(t)
            cb.onSessionEnd()
            again(50)
        }

        override fun onError(error: Int) {
            touch()
            val now = SystemClock.elapsedRealtime()
            endedAt = now
            val ran = now - startedAt
            cb.onSessionEnd()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // normal when nobody talks; but endless instant failures mean the service ignores our audio
                    if (silentLogged < 3) { silentLogged++; Hub.dl("手机识别#$session ${errName(error)}，${ran / 100 / 10.0} 秒") }
                    if (ran < 700 && !gotSomething) quick++ else quick = 0
                    if (quick >= 12) { giveUp("手机识别不接收这里的录音（${errName(error)}）"); return }
                    again(if (quick > 3) 800L else 50L)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    Hub.dl("手机识别#$session ${errName(error)}")
                    dropRecognizer()
                    again(500)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp("手机识别没有麦克风权限")
                12, 13 -> if (piped) { packLang = ""; ensurePack(); giveUp("手机识别不能这样录音（${errName(error)}）") } else { fails++; Hub.dl("手机识别#$session ${errName(error)}"); if (fails >= 4) giveUp("手机识别用不了（${errName(error)}）") else again(1500) }
                else -> {
                    fails++
                    Hub.dl("手机识别#$session ${errName(error)}")
                    if (piped && error == SpeechRecognizer.ERROR_AUDIO && fails >= 3) { giveUp("手机识别读不到这里的录音"); return }
                    dropRecognizer()
                    if (fails >= 8) giveUp("手机识别一直出错（${errName(error)}）") else again(min(3000L, 300L * fails))
                }
            }
        }
    }

    private fun giveUp(reason: String) {
        Hub.dl(reason)
        stop()
        cb.onGiveUp(reason)
    }

    /** Writes audio into the pipe on its own thread so a slow reader never blocks the recording. */
    private class PipeWriter(pfd: ParcelFileDescriptor) : Thread("asr-pipe") {
        private val q = LinkedBlockingQueue<ByteArray>(400)
        private val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        @Volatile private var closed = false
        val open: Boolean get() = !closed
        fun offer(b: ByteArray) { if (!closed) q.offer(b) }
        fun close() { closed = true; interrupt() }
        override fun run() {
            try {
                while (!closed) {
                    val b = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out.write(b)
                }
            } catch (_: Exception) {
            } finally {
                try { out.close() } catch (_: Exception) { }
            }
        }
    }
}
