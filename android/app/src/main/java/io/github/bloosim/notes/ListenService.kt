package io.github.bloosim.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Does the listening. Runs as a foreground service with a notification, so it keeps going
 * with the screen off or another app in front.
 *
 * Continuous translation, AI mode: our recording is cut at pauses; each piece goes to the AI services;
 *   the phone recognizer runs alongside for the live line and as a fallback.
 * Continuous translation, phone mode: every sentence from the phone recognizer becomes a line.
 * Hold to talk: everything said while the button is held becomes one line.
 */
class ListenService : Service(), Vad.Listener, PhoneAsr.Callback {
    companion object {
        const val ACT_START = "start"
        const val ACT_STOP = "stop"
        const val ACT_TOGGLE = "toggle"
        const val ACT_HOLD_DOWN = "hold_down"
        const val ACT_HOLD_UP = "hold_up"
        const val ACT_OVERLAY = "overlay"
        const val ACT_EXIT = "exit"
        const val ACT_LANG = "lang"
        private const val CH = "listen"
        private const val NID = 7

        var instance: ListenService? = null
            private set

        /** Commands from the screen, the floating window and the notification. */
        fun send(ctx: Context, action: String) {
            val s = instance
            if (s != null) { s.handle(action); return }
            if (action == ACT_EXIT || action == ACT_STOP || action == ACT_HOLD_UP || action == ACT_LANG) return
            try {
                ctx.startForegroundService(Intent(ctx, ListenService::class.java).setAction(action))
            } catch (e: Exception) {
                Hub.dl("启动服务失败：" + e.message)
            }
        }
    }

    private class HoldState(val t0: Long) {
        var text = ""
        var interim = ""
        var released = false
        var audio: Pair<ShortArray, Int>? = null
    }

    /** the phone recognizer's text for one stretch of AI-mode audio, available ~1 s after the audio ends */
    private class Fallback {
        var text = ""
        var ready = false
        var onReady: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var capture: Capture
    private lateinit var vad: Vad
    private lateinit var phone: PhoneAsr
    private var overlay: Overlay? = null
    private val cloudExec = Executors.newSingleThreadExecutor()
    private val trExec = Executors.newFixedThreadPool(2)
    private var fg = false
    private var cloudCont = false
    private var pvText = ""
    private var pvInterim = ""
    private var speaking = false
    private var hold: HoldState? = null
    private var pendingJobs = 0
    private var lastFallbackToast = 0L
    @Volatile private var feedPhone = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Store.init(this)
        capture = Capture { f, rms -> onFrame(f, rms) }
        vad = Vad(this)
        phone = PhoneAsr(this, this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "听写", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureForeground()) return START_NOT_STICKY
        intent?.action?.let { handle(it) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay?.hide(); overlay = null
        Hub.overlayOn = false
        phone.destroy()
        capture.stop()
        Hub.running = false; Hub.holding = false; Hub.live = ""; Hub.liveLabel = ""
        instance = null
        Hub.changed()
        super.onDestroy()
    }

    fun handle(action: String) {
        when (action) {
            ACT_START -> startRun()
            ACT_STOP -> stopRun()
            ACT_TOGGLE -> if (Hub.running) stopRun() else startRun()
            ACT_HOLD_DOWN -> holdDown()
            ACT_HOLD_UP -> holdUp()
            ACT_OVERLAY -> toggleOverlay()
            ACT_EXIT -> exit()
            ACT_LANG -> if (phone.active) phone.start(Prefs.recLang, phone.piped)
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun ensureForeground(): Boolean {
        if (fg) return true
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NID, n)
            fg = true
            true
        } catch (e: Exception) {
            Hub.dl("前台服务启动失败：" + e.message)
            toast("需要麦克风权限才能听写")
            stopSelf()
            false
        }
    }

    // ---------- audio ----------

    private fun onFrame(f: ShortArray, rms: Float) { // capture thread
        vad.frame(f, rms)
        if (feedPhone) {
            val b = ByteArray(f.size * 2)
            for (i in f.indices) { val v = f[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
            phone.feed(b)
        }
    }

    private fun holdUsesCloud() = Prefs.engine == "llm" && Cloud.anyKey()

    private fun wantPhone(): Boolean = when {
        hold != null -> Prefs.phonePreview || !holdUsesCloud()
        Hub.running -> !cloudCont || Prefs.phonePreview
        else -> false
    }

    /** start or stop the phone recognizer to match what is going on */
    private fun syncPhone() {
        if (!wantPhone()) { if (phone.active) phone.stop(); feedPhone = false; return }
        if (phone.active) return
        if (phone.pipeSupported) {
            if (!capture.running) capture.start()
            if (capture.running) { phone.start(Prefs.recLang, true); feedPhone = true; return }
        }
        // older Android: the recognizer can only use the microphone itself, so only while we are not recording
        if (!capture.running) { phone.start(Prefs.recLang, false); feedPhone = false }
    }

    private fun startRun() {
        if (Hub.running) return
        if (!ensureForeground()) return
        cloudCont = Prefs.engine == "llm" && Prefs.llmCont && Cloud.anyKey()
        if (cloudCont && !capture.start()) { toast("打不开麦克风"); return }
        vad.pauseMs = Prefs.pause
        vad.enabled = cloudCont
        pvText = ""; pvInterim = ""; speaking = false
        Hub.running = true
        Hub.live = ""; Hub.liveLabel = ""
        Hub.status = if (cloudCont) (if (Cloud.anyUsable()) "大模型识别" else "大模型额度用完，正在用手机识别") else "手机识别"
        syncPhone()
        Hub.dl("开始连续翻译：" + Hub.status)
        updateNotification(); Hub.changed()
    }

    private fun stopRun() {
        if (!Hub.running) { maybeExit(); return }
        vad.flush() // the sentence being said when you stopped
        vad.enabled = false
        Hub.running = false
        if (!cloudCont && Hub.live.isNotBlank()) commitPhone(Hub.live)
        Hub.live = ""; Hub.liveLabel = ""; speaking = false
        if (hold == null) { phone.stop(); feedPhone = false; capture.stop() }
        Hub.dl("暂停")
        updateNotification(); Hub.changed()
        maybeExit()
    }

    // ---------- phone recognizer results ----------

    override fun onPartial(t: String) {
        val h = hold
        when {
            h != null -> { h.interim = t; showHoldLive() }
            Hub.running && cloudCont -> { pvInterim = t; showCloudLive() }
            Hub.running -> { Hub.live = t; Hub.liveLabel = ""; Hub.changed() }
        }
    }

    override fun onFinal(t: String) {
        val h = hold
        when {
            h != null -> { h.text = Txt.combine(h.text, t); h.interim = ""; showHoldLive() }
            Hub.running && cloudCont -> { pvText = Txt.combine(pvText, t); pvInterim = ""; showCloudLive() }
            Hub.running -> { Hub.live = ""; commitPhone(t) }
        }
    }

    override fun onGiveUp(reason: String) {
        val wasPiped = phone.piped
        feedPhone = false
        val phoneOnly = (Hub.running && !cloudCont) || (hold != null && !holdUsesCloud())
        if (wasPiped && phoneOnly && !(Hub.running && cloudCont)) {
            // the recognizer ignored our recording: let it use the microphone itself (works while the screen is on)
            Hub.dl("改用手机识别自己的麦克风：$reason")
            if (hold == null) capture.stop()
            if (!capture.running) { phone.start(Prefs.recLang, false); return }
        }
        if (Hub.running && !cloudCont && hold == null) { toast(reason); stopRun() }
        else Hub.dl("手机识别停用：$reason")
    }

    private fun showCloudLive() {
        if (hold != null) return
        Hub.live = Txt.combine(pvText, pvInterim)
        Hub.liveLabel = if (speaking) "正在听…" else ""
        Hub.changed()
    }

    private fun showHoldLive() {
        val h = hold ?: return
        Hub.live = Txt.combine(h.text, h.interim)
        if (!h.released) Hub.liveLabel = "正在听…松开后翻译"
        Hub.changed()
    }

    /** phone mode: a finished sentence; a sentence the phone sends again with more words updates the line */
    private fun commitPhone(raw: String) {
        val t = Txt.clean(raw)
        if (t.isEmpty()) return
        val note = Store.current
        val last = note.entries.lastOrNull()
        val now = System.currentTimeMillis()
        if (last != null && now - last.ts < 15000 && last.st != "asr") {
            val tn = Txt.norm(t); val ln = Txt.norm(last.src)
            if (tn == ln || (tn.length >= 4 && ln.contains(tn))) return
            if (ln.length >= 2 && tn.contains(ln)) { last.src = t; last.ts = now; translate(last); return }
        }
        addEntry(t)
    }

    // ---------- AI-mode pieces ----------

    override fun onSpeechStart() {
        main.post { if (Hub.running && cloudCont && hold == null) { speaking = true; showCloudLive() } }
    }

    override fun onSegment(pcm: ShortArray, voiced: Int) {
        main.post { segmentReady(pcm) }
    }

    private fun segmentReady(pcm: ShortArray) {
        if (!cloudCont) return
        speaking = false
        val e = addPlaceholder("")
        val fb = Fallback()
        main.postDelayed({
            fb.text = Txt.clean(Txt.combine(pvText, pvInterim))
            pvText = ""; pvInterim = ""
            fb.ready = true
            fb.onReady?.invoke()
            showCloudLive()
        }, 900)
        if (!Cloud.anyUsable()) {
            Hub.status = "大模型额度用完，正在用手机识别"
            noteFallback()
            fb.onReady = { applyFallback(e, fb.text) }
            return
        }
        submitCloud(e, pcm, Prefs.lang, fb)
        showCloudLive()
    }

    private fun submitCloud(e: Entry, pcm: ShortArray, lang: String, fb: Fallback) {
        val ctx = contextFor(e)
        pendingJobs++
        cloudExec.execute {
            val res = try { Cloud.transcribe(pcm, lang, ctx) } catch (x: Exception) { Hub.dl("大模型出错：" + x.message); null }
            main.post {
                pendingJobs--
                applyCloud(e, res, fb)
                maybeExit()
            }
        }
    }

    private fun applyCloud(e: Entry, res: Cloud.Result?, fb: Fallback) {
        if (noteOf(e) == null) return
        val good = res != null && res.src.isNotBlank() && !(Txt.HALLU.containsMatchIn(res.src) && !Txt.HALLU.containsMatchIn(fb.text))
        if (good && res != null) {
            e.src = res.src; e.by = res.by
            Hub.status = "识别：" + res.by
            if (res.zh.isNotBlank()) { e.zh = res.zh; e.st = "ok"; Store.save(); Hub.changed() } else translate(e)
            return
        }
        if (res == null) { Hub.status = "大模型暂时用不了，正在用手机识别"; noteFallback() }
        if (fb.ready) applyFallback(e, fb.text) else fb.onReady = { applyFallback(e, fb.text) }
    }

    private fun applyFallback(e: Entry, text: String) {
        if (noteOf(e) == null) return
        if (text.isNotBlank()) { e.src = text; e.by = "phone"; translate(e) } else removeEntry(e)
    }

    private fun noteFallback() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFallbackToast > 300_000) { lastFallbackToast = now; toast("大模型暂时用不了，先用手机识别") }
        Hub.changed()
    }

    // ---------- hold to talk ----------

    private fun holdDown() {
        if (hold != null) return
        if (!ensureForeground()) return
        val h = HoldState(SystemClock.elapsedRealtime())
        hold = h
        Hub.holding = true
        if (!capture.running) capture.start()
        vad.beginHold()
        syncPhone()
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
        Hub.live = ""; Hub.liveLabel = "正在听…松开后翻译"
        Hub.dl("按住说话 开始")
        updateNotification(); Hub.changed()
    }

    private fun holdUp() {
        val h = hold ?: return
        if (h.released) return
        h.released = true
        Hub.holding = false
        h.audio = vad.endHold()
        val short = SystemClock.elapsedRealtime() - h.t0 < 500
        Hub.liveLabel = "正在整理这一段…"
        Hub.changed()
        val waitPhone = phone.active
        if (waitPhone) phone.finishSentence()
        // the phone's final words for the held speech arrive a moment after release
        main.postDelayed({ commitHold(h, short) }, if (waitPhone) 900L else 30L)
    }

    private fun commitHold(h: HoldState, short: Boolean) {
        if (hold !== h) return
        hold = null
        val text = Txt.clean(Txt.combine(h.text, h.interim))
        val audio = h.audio
        if (Hub.running) syncPhone() else { phone.stop(); feedPhone = false; if (!cloudCont || !Hub.running) capture.stop() }
        if (Hub.running && cloudCont && !capture.running) capture.start()
        Hub.live = ""; Hub.liveLabel = ""
        if (audio != null && audio.second >= 8 && holdUsesCloud() && Cloud.anyUsable()) {
            val fb = Fallback().apply { this.text = text; ready = true }
            submitCloud(addPlaceholder(text), audio.first, Prefs.lang, fb)
        } else if (text.isNotBlank()) {
            if (holdUsesCloud() && audio != null && !Cloud.anyUsable()) { Hub.status = "大模型额度用完，正在用手机识别"; noteFallback() }
            addEntry(text)
        } else {
            toast(if (short) "要按住不放，等对方说完再松开" else "这一段没有听到内容")
        }
        updateNotification(); Hub.changed()
        maybeExit()
    }

    // ---------- lines ----------

    private fun noteOf(e: Entry): Note? = Store.notes.firstOrNull { n -> n.entries.any { it === e } }

    private fun addEntry(src: String): Entry {
        val note = Store.current
        val e = Entry(note.nextNo(), src, "", Prefs.lang, System.currentTimeMillis(), "wait")
        note.entries.add(e)
        translate(e)
        Store.save(); Hub.changed()
        return e
    }

    private fun addPlaceholder(src: String): Entry {
        val note = Store.current
        val e = Entry(note.nextNo(), src, "", Prefs.lang, System.currentTimeMillis(), "asr")
        note.entries.add(e)
        Store.save(); Hub.changed()
        return e
    }

    private fun removeEntry(e: Entry) {
        val note = noteOf(e) ?: return
        val i = note.entries.indexOfFirst { it === e }
        if (i < 0) return
        note.entries.removeAt(i)
        for (j in i until note.entries.size) note.entries[j].n--
        Store.save(); Hub.changed()
    }

    private fun contextFor(e: Entry): String {
        val note = noteOf(e) ?: return ""
        val i = note.entries.indexOfFirst { it === e }.let { if (it < 0) note.entries.size else it }
        return note.entries.subList(maxOf(0, i - 3), i).filter { it.src.isNotBlank() }.joinToString("\n") { it.src }.takeLast(600)
    }

    fun translate(e: Entry) {
        val note = noteOf(e) ?: return
        e.st = "wait"
        val i = note.entries.indexOfFirst { it === e }
        val pe = if (i > 0) note.entries[i - 1] else null
        val prev = if (pe != null && pe.lang == e.lang && e.ts - pe.ts < 120_000) Txt.forTranslate(pe.src, pe.lang).takeLast(200) else ""
        val src = e.src
        val lang = e.lang
        Hub.changed()
        trExec.execute {
            val zh = try { Tr.translate(src, prev, lang) } catch (x: Exception) { null }
            main.post {
                if (e.src != src) return@post // the line changed meanwhile; a newer translation is on its way
                if (zh != null) { e.zh = zh; e.st = "ok" } else e.st = "err"
                Store.save(); Hub.changed()
            }
        }
    }

    // ---------- floating window, notification, exit ----------

    private fun toggleOverlay() {
        if (overlay != null) { overlay?.hide(); overlay = null; Hub.overlayOn = false; updateNotification(); Hub.changed(); maybeExit(); return }
        if (!Settings.canDrawOverlays(this)) { toast("请先在设置里允许「显示在其他应用上层」"); maybeExit(); return }
        overlay = Overlay(this).also { it.show() }
        Hub.overlayOn = true
        updateNotification(); Hub.changed()
    }

    fun hideOverlay() { if (overlay != null) toggleOverlay() }

    private fun maybeExit() {
        if (!Hub.running && hold == null && !Hub.overlayOn && pendingJobs == 0) exit()
    }

    private fun exit() {
        if (Hub.running) { vad.flush(); Hub.running = false }
        hold = null; Hub.holding = false
        overlay?.hide(); overlay = null; Hub.overlayOn = false
        phone.stop(); feedPhone = false
        capture.stop()
        Hub.live = ""; Hub.liveLabel = ""
        Hub.changed()
        if (pendingJobs > 0) return // finish the pieces still with the AI service first
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        fg = false
        stopSelf()
    }

    private fun pi(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, ListenService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            Hub.holding -> "按住说话中"
            Hub.running -> "正在听写 · " + Hub.status
            else -> "已暂停"
        }
        val noIcon: Icon? = null
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("笔记")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(noIcon, if (Hub.running) "暂停" else "继续", pi(ACT_TOGGLE)).build())
            .addAction(Notification.Action.Builder(noIcon, if (Hub.overlayOn) "收起浮窗" else "浮窗", pi(ACT_OVERLAY)).build())
            .addAction(Notification.Action.Builder(noIcon, "退出", pi(ACT_EXIT)).build())
            .build()
    }

    private fun updateNotification() {
        if (!fg) return
        try { getSystemService(NotificationManager::class.java).notify(NID, buildNotification()) } catch (_: Exception) { }
    }
}
