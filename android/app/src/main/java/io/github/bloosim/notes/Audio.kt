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
import android.speech.RecognitionListener
import android.speech.RecognitionService
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
 * The phone's own speech recognizer (Google). On Android 13+ it is fed from our recording through a pipe,
 * so it keeps working in the background and on phones that mute other apps' microphones.
 * Each session is one sentence; a new one starts right after. Main thread only.
 */
class PhoneAsr(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onPartial(t: String)
        fun onFinal(t: String)
        fun onGiveUp(reason: String)
    }

    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    var active = false
        private set
    var piped = false
        private set
    private var lang = "ja-JP"
    @Volatile private var writer: PipeWriter? = null
    private var readSide: ParcelFileDescriptor? = null
    private val lock = Any()
    /** audio that arrives between two sentences, handed to the next sentence so nothing is lost */
    private val gap = ArrayDeque<ByteArray>()
    private var fails = 0
    private var quick = 0
    private var startedAt = 0L
    private var gotSomething = false

    val pipeSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun start(lang: String, usePipe: Boolean) {
        this.lang = lang
        piped = usePipe && pipeSupported
        active = true; fails = 0; quick = 0
        h.removeCallbacksAndMessages(null)
        try { sr?.cancel() } catch (_: Exception) { }
        begin()
    }

    fun stop() {
        active = false
        h.removeCallbacksAndMessages(null)
        try { sr?.cancel() } catch (_: Exception) { }
        closePipe()
    }

    /** end the current sentence now; its final result still arrives */
    fun finishSentence() {
        try { sr?.stopListening() } catch (_: Exception) { }
        synchronized(lock) { writer?.close() }
    }

    fun destroy() {
        stop()
        try { sr?.destroy() } catch (_: Exception) { }
        sr = null
    }

    /** audio from our recording (16 kHz mono PCM16 little-endian); called on the recording thread */
    fun feed(bytes: ByteArray) {
        synchronized(lock) {
            val w = writer
            if (w != null && w.open) { w.offer(bytes); return }
            gap.addLast(bytes)
            while (gap.size > 50) gap.removeFirst() // keep at most 1 s
        }
    }

    private fun pickService(): ComponentName? {
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        for (pkg in listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")) {
            val ri = list.firstOrNull { it.serviceInfo?.packageName == pkg } ?: continue
            return ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name)
        }
        return null
    }

    private fun create(): SpeechRecognizer? {
        val comp = pickService()
        return try {
            val r = if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp) else SpeechRecognizer.createSpeechRecognizer(ctx)
            Hub.dl("手机识别服务：" + (comp?.packageName ?: "系统默认"))
            r.setRecognitionListener(listener)
            r
        } catch (e: Exception) { Hub.dl("创建手机识别失败：" + e.message); null }
    }

    private fun begin() {
        if (!active) return
        if (sr == null) sr = create()
        val r = sr ?: run { active = false; cb.onGiveUp("这台手机没有可用的语音识别服务"); return }
        closePipe()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (piped && Build.VERSION.SDK_INT >= 33) {
            try {
                val p = ParcelFileDescriptor.createPipe()
                readSide = p[0]
                val w = PipeWriter(p[1]).also { it.start() }
                synchronized(lock) {
                    while (gap.isNotEmpty()) w.offer(gap.removeFirst())
                    writer = w
                }
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            } catch (e: IOException) { Hub.dl("建立音频管道失败：" + e.message) }
        }
        startedAt = SystemClock.elapsedRealtime()
        gotSomething = false
        try { r.startListening(i) } catch (e: Exception) { Hub.dl("手机识别启动失败：" + e.message); again(800) }
    }

    private fun again(delay: Long) {
        if (!active) return
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ begin() }, delay)
    }

    private fun closePipe() {
        synchronized(lock) { writer?.close(); writer = null; gap.clear() }
        try { readSide?.close() } catch (_: Exception) { }
        readSide = null
    }

    private fun text(b: Bundle?): String = Txt.clean(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { }
        override fun onBeginningOfSpeech() { }
        override fun onRmsChanged(rmsdB: Float) { }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { }
        override fun onEvent(eventType: Int, params: Bundle?) { }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = text(partialResults)
            if (t.isNotEmpty()) { gotSomething = true; cb.onPartial(t) }
        }

        override fun onResults(results: Bundle?) {
            val t = text(results)
            fails = 0; quick = 0
            if (t.isNotEmpty()) cb.onFinal(t)
            again(60)
        }

        override fun onError(error: Int) {
            val ran = SystemClock.elapsedRealtime() - startedAt
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // normal when nobody talks; but endless instant failures mean the service ignores our audio
                    if (ran < 700 && !gotSomething) quick++ else quick = 0
                    if (quick >= 12) { giveUp("手机识别不接收这里的录音（$error）"); return }
                    again(if (quick > 3) 800L else 60L)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    try { sr?.destroy() } catch (_: Exception) { }
                    sr = null
                    again(500)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp("手机识别没有麦克风权限")
                else -> {
                    fails++
                    Hub.dl("手机识别出错：$error")
                    if (fails >= 8) giveUp("手机识别一直出错（$error）") else again(min(3000L, 300L * fails))
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
        private val q = LinkedBlockingQueue<ByteArray>(250)
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
