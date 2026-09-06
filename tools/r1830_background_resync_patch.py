from pathlib import Path
import re

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {count}')
    return text.replace(old, new, 1)


def regex_once(text, pattern, replacement, label):
    out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 regex match, got {count}')
    return out


# AiStudioWebSessionExecutor: make timeout clocks background/scheduler aware,
# resync before failure, and apply the same policy to video + dedicated STT.
path = 'app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt'
s = read(path)
s = replace_once(
    s,
    'import androidx.webkit.WebViewFeature\n',
    'import androidx.webkit.WebViewFeature\nimport com.oai.geminilivetranslate.GeminiTranslateApp\n',
    'executor app import',
)
s = regex_once(
    s,
    r'''    private data class PendingAttachment\(.*?\n    \)\n\n    private data class Pending\(.*?\n    \)''',
    '''    private data class PendingAttachment(\n        val token: Int,\n        val uri: Uri,\n        val name: String,\n        val mimeType: String,\n        val size: Long,\n        var startedAt: Long,\n        val callback: (Boolean, String) -> Unit,\n        val requireUploadReady: Boolean,\n        var readyScans: Int = 0,\n        var readySince: Long = 0L,\n        var lastPollAt: Long = startedAt,\n        var backgroundDeferredMs: Long = 0L,\n        var wasBackground: Boolean = false,\n    )\n\n    private data class Pending(\n        val seq: Int,\n        val callback: (Result) -> Unit,\n        var startedAt: Long,\n        val completionValidator: ((String) -> Boolean)? = null,\n        var firstProgressAt: Long = 0L,\n        var lastProgressAt: Long = 0L,\n        var lastResponseChars: Int = 0,\n        var lastWatchdogAt: Long = startedAt,\n        var backgroundDeferredMs: Long = 0L,\n        var wasBackground: Boolean = false,\n        var timeoutProbeAt: Long = 0L,\n        var timeoutReason: String = "",\n    )''',
    'executor pending data classes',
)

s = regex_once(
    s,
    r'''    private fun pollSttAttachment\(token: Int\) \{.*?\n    \}\n\n    fun attachFile\(''',
    '''    private fun pollSttAttachment(token: Int) {\n        val item = activeAttachment ?: return\n        if (item.token != token || destroyed) return\n        val script = "JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.fileState?window.__AIS_STT_PAGE__.fileState(${JSONObject.quote(item.name)}):({ok:false,error:'stt-file-state-not-installed'}))"\n        webView.evaluateJavascript(script) { raw ->\n            if (activeAttachment?.token != token) return@evaluateJavascript\n            val now = SystemClock.uptimeMillis()\n            val timeoutSuppressed = compensateAttachmentTiming(item, now, "stt")\n            val decoded = decodeEvalValue(raw)\n            val obj = runCatching { JSONObject(decoded) }.getOrNull()\n            val ready = obj?.optBoolean("ready") == true\n            if (ready) {\n                item.readyScans += 1\n                if (item.readySince == 0L) item.readySince = now\n            } else {\n                item.readyScans = 0\n                item.readySince = 0L\n            }\n            events?.onLog("R28_STT_FILE_POLL", "readyScans=${item.readyScans} ${decoded.take(6000)}")\n            when {\n                ready && item.readyScans >= ATTACHMENT_READY_STABLE_SCANS && now - item.readySince >= ATTACHMENT_READY_SETTLE_MS ->\n                    finishAttachment(token, true, decoded)\n                !timeoutSuppressed && now - item.startedAt > ATTACHMENT_TIMEOUT_MS -> {\n                    events?.onLog("R38_ATTACHMENT_TIMEOUT_CONFIRMED", "kind=stt token=$token activeMs=${now - item.startedAt}")\n                    finishAttachment(token, false, "STT_ATTACHMENT_TIMEOUT")\n                }\n                else -> main.postDelayed({ pollSttAttachment(token) }, 500L)\n            }\n        }\n    }\n\n    fun attachFile(''',
    'executor STT attachment timeout',
)

s = regex_once(
    s,
    r'''    private fun pollAttachment\(token: Int\) \{.*?\n    \}\n\n    private fun finishAttachment''',
    '''    private fun pollAttachment(token: Int) {\n        val item = activeAttachment ?: return\n        if (item.token != token || destroyed) return\n        val script = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachmentEvidence?window.__AIS_R11_SUPPORT__.attachmentEvidence():({ok:false,error:'r11-attachment-evidence-not-installed'}))"\n        webView.evaluateJavascript(script) { raw ->\n            if (activeAttachment?.token != token) return@evaluateJavascript\n            val now = SystemClock.uptimeMillis()\n            val timeoutSuppressed = compensateAttachmentTiming(item, now, "video")\n            val decoded = decodeEvalValue(raw)\n            val obj = runCatching { JSONObject(decoded) }.getOrNull()\n            val present = obj?.optBoolean("present", false) == true\n            val ready = obj?.optBoolean("ready", false) == true\n            if (!item.requireUploadReady && present) {\n                events?.onLog("R19_ATTACHMENT_PRESENT_MANUAL", "token=$token waitedMs=${now - item.startedAt} detail=${decoded.take(6000)}")\n                finishAttachment(token, true, decoded)\n                return@evaluateJavascript\n            }\n            if (ready) {\n                item.readyScans += 1\n                if (item.readySince == 0L) item.readySince = now\n            } else {\n                item.readyScans = 0\n                item.readySince = 0L\n            }\n            events?.onLog("R18_ATTACHMENT_STATE", "readyScans=${item.readyScans} ${decoded.take(7000)}")\n            when {\n                ready && item.readyScans >= ATTACHMENT_READY_STABLE_SCANS && now - item.readySince >= ATTACHMENT_READY_SETTLE_MS -> {\n                    events?.onLog("R20_ATTACHMENT_PREPARED", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt} localReadReady=${obj?.optBoolean("localReadReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)}")\n                    finishAttachment(token, true, decoded)\n                }\n                !timeoutSuppressed && now - item.startedAt > ATTACHMENT_TIMEOUT_MS -> {\n                    events?.onLog("R38_ATTACHMENT_TIMEOUT_CONFIRMED", "kind=video token=$token activeMs=${now - item.startedAt} present=$present ready=$ready")\n                    finishAttachment(token, false, "ATTACHMENT_TIMEOUT")\n                }\n                else -> {\n                    if (present && !ready) {\n                        events?.onLog("R20_ATTACHMENT_WAIT_PREPARED", "token=$token busy=${obj?.optBoolean("busy", false)} present=$present localReadReady=${obj?.optBoolean("localReadReady", false)} attachmentPrepared=${obj?.optBoolean("attachmentPrepared", false)} submitReady=${obj?.optBoolean("submitReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)} payloadActive=${obj?.optInt("payloadActive", 0)} payloadStarted=${obj?.optInt("payloadStarted", 0)} payloadCompleted=${obj?.optInt("payloadCompleted", 0)} payloadFailed=${obj?.optInt("payloadFailed", 0)}")\n                    }\n                    main.postDelayed({ pollAttachment(token) }, 500L)\n                }\n            }\n        }\n    }\n\n    private fun compensateAttachmentTiming(item: PendingAttachment, now: Long, kind: String): Boolean {\n        val rawGap = (now - item.lastPollAt).coerceAtLeast(0L)\n        item.lastPollAt = now\n        val appBackground = GeminiTranslateApp.currentActivity() == null\n        val deferred = when {\n            appBackground -> rawGap\n            item.wasBackground -> rawGap\n            rawGap >= ATTACHMENT_SCHEDULER_GAP_MS -> (rawGap - ATTACHMENT_POLL_EXPECTED_MS).coerceAtLeast(0L)\n            else -> 0L\n        }\n        if (deferred > 0L) {\n            item.startedAt += deferred\n            if (item.readySince > 0L) item.readySince += deferred\n            item.backgroundDeferredMs += deferred\n        }\n        val wasBackground = item.wasBackground\n        item.wasBackground = appBackground\n        if (appBackground && !wasBackground) {\n            events?.onLog("R38_ATTACHMENT_BACKGROUND_DEFER", "kind=$kind token=${item.token} state=enter")\n        } else if (!appBackground && wasBackground) {\n            events?.onLog("R38_ATTACHMENT_BACKGROUND_DEFER", "kind=$kind token=${item.token} state=exit deferredMs=${item.backgroundDeferredMs}")\n            resumeWebViewForBackgroundResync()\n        } else if (!appBackground && rawGap >= ATTACHMENT_SCHEDULER_GAP_MS) {\n            events?.onLog("R38_ATTACHMENT_SCHEDULER_GAP", "kind=$kind token=${item.token} gapMs=$rawGap deferredMs=$deferred")\n            resumeWebViewForBackgroundResync()\n        }\n        return appBackground || wasBackground || rawGap >= ATTACHMENT_SCHEDULER_GAP_MS\n    }\n\n    private fun finishAttachment''',
    'executor video attachment timeout',
)

s = regex_once(
    s,
    r'''    private fun scheduleProgressWatchdog\(requestSeq: Int\) \{.*?\n    \}\n\n    private fun recordProgress''',
    '''    private fun scheduleProgressWatchdog(requestSeq: Int) {\n        main.postDelayed(object : Runnable {\n            override fun run() {\n                val p = pending ?: return\n                if (p.seq != requestSeq) return\n                val now = SystemClock.uptimeMillis()\n                val rawGap = (now - p.lastWatchdogAt).coerceAtLeast(0L)\n                p.lastWatchdogAt = now\n                val appBackground = GeminiTranslateApp.currentActivity() == null\n                var suppressTimeoutThisTick = false\n\n                when {\n                    appBackground -> {\n                        shiftPendingTimeoutClocks(p, rawGap)\n                        p.backgroundDeferredMs += rawGap\n                        if (!p.wasBackground) {\n                            events?.onLog("R38_WATCHDOG_BACKGROUND_DEFER", "seq=$requestSeq state=enter responseChars=${p.lastResponseChars}")\n                        }\n                        p.wasBackground = true\n                        suppressTimeoutThisTick = true\n                    }\n                    p.wasBackground -> {\n                        shiftPendingTimeoutClocks(p, rawGap)\n                        p.backgroundDeferredMs += rawGap\n                        p.wasBackground = false\n                        events?.onLog("R38_WATCHDOG_BACKGROUND_DEFER", "seq=$requestSeq state=exit deferredMs=${p.backgroundDeferredMs} responseChars=${p.lastResponseChars}")\n                        resumeWebViewForBackgroundResync()\n                        resyncPendingRequest(requestSeq, "background-exit")\n                        suppressTimeoutThisTick = true\n                    }\n                    rawGap >= WATCHDOG_SCHEDULER_GAP_MS -> {\n                        val deferred = (rawGap - WATCHDOG_TICK_MS).coerceAtLeast(0L)\n                        shiftPendingTimeoutClocks(p, deferred)\n                        events?.onLog("R38_WATCHDOG_SCHEDULER_GAP", "seq=$requestSeq gapMs=$rawGap deferredMs=$deferred responseChars=${p.lastResponseChars}")\n                        resumeWebViewForBackgroundResync()\n                        resyncPendingRequest(requestSeq, "scheduler-gap")\n                        suppressTimeoutThisTick = true\n                    }\n                }\n\n                if (suppressTimeoutThisTick) {\n                    main.postDelayed(this, WATCHDOG_TICK_MS)\n                    return\n                }\n\n                val total = now - p.startedAt\n                val noProgressYet = p.firstProgressAt == 0L\n                val idle = if (p.lastProgressAt > 0L) now - p.lastProgressAt else total\n                val timeoutReason = when {\n                    total >= PROGRESS_HARD_TIMEOUT_MS -> "HARD_TIMEOUT totalMs=$total responseChars=${p.lastResponseChars}"\n                    noProgressYet && total >= FIRST_PROGRESS_TIMEOUT_MS -> "FIRST_PROGRESS_TIMEOUT totalMs=$total"\n                    !noProgressYet && idle >= PROGRESS_IDLE_TIMEOUT_MS -> "IDLE_TIMEOUT idleMs=$idle totalMs=$total responseChars=${p.lastResponseChars}"\n                    else -> null\n                }\n\n                if (timeoutReason != null) {\n                    if (p.timeoutProbeAt == 0L) {\n                        p.timeoutProbeAt = now\n                        p.timeoutReason = timeoutReason\n                        events?.onLog("R38_TIMEOUT_RESYNC", "seq=$requestSeq reason=$timeoutReason")\n                        resumeWebViewForBackgroundResync()\n                        resyncPendingRequest(requestSeq, "timeout-probe")\n                        main.postDelayed(this, TIMEOUT_RESYNC_GRACE_MS)\n                        return\n                    }\n                    if (now - p.timeoutProbeAt >= TIMEOUT_RESYNC_GRACE_MS) {\n                        events?.onLog("R38_TIMEOUT_CONFIRMED", "seq=$requestSeq reason=${p.timeoutReason} graceMs=${now - p.timeoutProbeAt}")\n                        timeoutRequest(requestSeq, p.timeoutReason)\n                        return\n                    }\n                } else {\n                    p.timeoutProbeAt = 0L\n                    p.timeoutReason = ""\n                    if (total % 10_000L < WATCHDOG_TICK_MS) {\n                        events?.onLog(\n                            "R12_PROGRESS_WATCHDOG",\n                            "seq=$requestSeq totalMs=$total firstProgress=${p.firstProgressAt > 0L} idleMs=$idle responseChars=${p.lastResponseChars}",\n                        )\n                    }\n                }\n                main.postDelayed(this, WATCHDOG_TICK_MS)\n            }\n        }, WATCHDOG_TICK_MS)\n    }\n\n    private fun shiftPendingTimeoutClocks(p: Pending, deferredMs: Long) {\n        if (deferredMs <= 0L) return\n        p.startedAt += deferredMs\n        if (p.firstProgressAt > 0L) p.firstProgressAt += deferredMs\n        if (p.lastProgressAt > 0L) p.lastProgressAt += deferredMs\n        if (p.timeoutProbeAt > 0L) p.timeoutProbeAt += deferredMs\n    }\n\n    private fun resumeWebViewForBackgroundResync() {\n        runCatching { webView.onResume() }\n        runCatching { webView.resumeTimers() }\n    }\n\n    private fun resyncPendingRequest(requestSeq: Int, reason: String) {\n        if (pending?.seq != requestSeq) return\n        if (sttModeModel == null) {\n            readNormalized(requestSeq, "watchdog-resync-$reason")\n            return\n        }\n        val script = "JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.resultState?window.__AIS_STT_PAGE__.resultState():({ok:false,error:'stt-result-state-not-installed'}))"\n        webView.evaluateJavascript(script) { raw ->\n            if (pending?.seq != requestSeq) return@evaluateJavascript\n            val decoded = decodeEvalValue(raw)\n            val obj = runCatching { JSONObject(decoded) }.getOrNull()\n            val text = obj?.optString("text").orEmpty()\n            val responseChars = obj?.optInt("responseChars", 0) ?: 0\n            events?.onLog("R38_STT_TIMEOUT_RESYNC", "seq=$requestSeq reason=$reason chars=${text.length} responseChars=$responseChars terminal=${obj?.optBoolean("terminal", false)}")\n            recordProgress(requestSeq, maxOf(text.length, responseChars), "stt-timeout-resync")\n            if (text.isNotBlank()) {\n                finish(\n                    requestSeq,\n                    Result(\n                        ok = true,\n                        status = obj?.optInt("status", 200) ?: 200,\n                        modelText = text,\n                        complete = true,\n                        phase = "stt-dom-resync-result",\n                    ),\n                )\n            }\n        }\n    }\n\n    private fun recordProgress''',
    'executor watchdog resync',
)

s = replace_once(
    s,
    '''        p.lastResponseChars = responseChars\n        p.lastProgressAt = now\n        if (p.firstProgressAt == 0L) p.firstProgressAt = now\n''',
    '''        p.lastResponseChars = responseChars\n        p.lastProgressAt = now\n        if (p.firstProgressAt == 0L) p.firstProgressAt = now\n        p.timeoutProbeAt = 0L\n        p.timeoutReason = ""\n''',
    'executor progress clears timeout probe',
)
s = replace_once(
    s,
    'const val VERSION = "2026-09-06-web-session-r12.10-background-service"',
    'const val VERSION = "2026-09-06-web-session-r12.11-background-resync"',
    'executor version',
)
s = replace_once(
    s,
    '''        private const val WATCHDOG_TICK_MS = 2_000L\n        private const val ATTACHMENT_PARTIAL_POLL_MS = 850L\n''',
    '''        private const val WATCHDOG_TICK_MS = 2_000L\n        private const val WATCHDOG_SCHEDULER_GAP_MS = 8_000L\n        private const val TIMEOUT_RESYNC_GRACE_MS = 15_000L\n        private const val ATTACHMENT_SCHEDULER_GAP_MS = 8_000L\n        private const val ATTACHMENT_POLL_EXPECTED_MS = 500L\n        private const val ATTACHMENT_PARTIAL_POLL_MS = 850L\n''',
    'executor resync constants',
)
write(path, s)

# AI Studio realtime translate/transcribe: same main-loop protection and renderer priority.
path = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt'
s = read(path)
s = replace_once(s, 'import android.os.Handler\n', 'import android.os.Build\nimport android.os.Handler\n', 'realtime Build import')
s = replace_once(
    s,
    '''    @Volatile private var startSessionRecoveryAttempts = 0\n    @Volatile private var lastStartSessionRecoveryAt = 0L\n''',
    '''    @Volatile private var startSessionRecoveryAttempts = 0\n    @Volatile private var lastStartSessionRecoveryAt = 0L\n    @Volatile private var lastHealthTickAt = 0L\n    @Volatile private var backgroundDeferredMs = 0L\n    @Volatile private var healthWasBackground = false\n''',
    'realtime health fields',
)
s = replace_once(
    s,
    '''        connectingStartedAt = now\n        lastBootstrapProgressAt = now\n        lastBootstrapSignature = ""\n''',
    '''        connectingStartedAt = now\n        lastBootstrapProgressAt = now\n        lastHealthTickAt = now\n        backgroundDeferredMs = 0L\n        healthWasBackground = false\n        lastBootstrapSignature = ""\n''',
    'realtime connect timing init',
)
s = replace_once(
    s,
    '''    private fun configureWebView(view: WebView) {\n        view.settings.apply {\n''',
    '''    private fun configureWebView(view: WebView) {\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)\n        }\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {\n            view.settings.offscreenPreRaster = true\n        }\n        logger.log(\n            2,\n            "AiStudioLive",\n            "R38_LIVE_WEBVIEW_BACKGROUND_POLICY rendererPriority=important waiveWhenNotVisible=false offscreenPreRaster=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.M}",\n        )\n        view.settings.apply {\n''',
    'realtime background WebView policy',
)
s = replace_once(
    s,
    '''            val now = SystemClock.elapsedRealtime()\n            val currentUri = runCatching { Uri.parse(current.url.orEmpty()) }.getOrNull()\n''',
    '''            val now = SystemClock.elapsedRealtime()\n            val rawHealthGap = (now - lastHealthTickAt).coerceAtLeast(0L)\n            lastHealthTickAt = now\n            val appBackground = GeminiTranslateApp.currentActivity() == null\n            var suppressTimeouts = false\n            when {\n                appBackground -> {\n                    shiftLiveControlClocks(rawHealthGap)\n                    backgroundDeferredMs += rawHealthGap\n                    if (!healthWasBackground) {\n                        logger.log(2, "AiStudioLive", "R38_LIVE_BACKGROUND_DEFER state=enter operation=$operationMode")\n                    }\n                    healthWasBackground = true\n                    suppressTimeouts = true\n                    runCatching { current.onResume() }\n                    runCatching { current.resumeTimers() }\n                }\n                healthWasBackground -> {\n                    shiftLiveControlClocks(rawHealthGap)\n                    backgroundDeferredMs += rawHealthGap\n                    healthWasBackground = false\n                    suppressTimeouts = true\n                    logger.log(2, "AiStudioLive", "R38_LIVE_BACKGROUND_DEFER state=exit deferredMs=$backgroundDeferredMs operation=$operationMode")\n                    runCatching { current.onResume() }\n                    runCatching { current.resumeTimers() }\n                }\n                rawHealthGap >= HEALTH_SCHEDULER_GAP_MS -> {\n                    val deferred = (rawHealthGap - HEALTH_TICK_MS).coerceAtLeast(0L)\n                    shiftLiveControlClocks(deferred)\n                    suppressTimeouts = true\n                    logger.log(1, "AiStudioLive", "R38_LIVE_SCHEDULER_GAP gapMs=$rawHealthGap deferredMs=$deferred operation=$operationMode")\n                    runCatching { current.onResume() }\n                    runCatching { current.resumeTimers() }\n                }\n            }\n            val currentUri = runCatching { Uri.parse(current.url.orEmpty()) }.getOrNull()\n''',
    'realtime health gap detection',
)
s = replace_once(
    s,
    '''            if (!setupDelivered.get()) {\n                val stalledFor = now - lastBootstrapProgressAt.coerceAtLeast(connectingStartedAt)\n''',
    '''            if (!suppressTimeouts && !setupDelivered.get()) {\n                val stalledFor = now - lastBootstrapProgressAt.coerceAtLeast(connectingStartedAt)\n''',
    'realtime setup timeout suppression',
)
s = replace_once(
    s,
    '''            if (setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {\n''',
    '''            if (!suppressTimeouts && setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {\n''',
    'realtime stale timeout suppression',
)
# Insert clock shifter immediately before healthTick.
s = replace_once(
    s,
    '''    private val healthTick = object : Runnable {\n''',
    '''    private fun shiftLiveControlClocks(deferredMs: Long) {\n        if (deferredMs <= 0L) return\n        connectingStartedAt += deferredMs\n        lastBootstrapProgressAt += deferredMs\n        if (lastProgressAt > 0L) lastProgressAt += deferredMs\n        if (lastRouteRepairAt > 0L) lastRouteRepairAt += deferredMs\n        if (lastBootstrapRecoveryAt > 0L) lastBootstrapRecoveryAt += deferredMs\n        if (lastStartSessionRecoveryAt > 0L) lastStartSessionRecoveryAt += deferredMs\n    }\n\n    private val healthTick = object : Runnable {\n''',
    'realtime clock shifter',
)
s = replace_once(
    s,
    'const val VERSION = "2026-09-05-production-ai-studio-live-r7-fast-start-recovery-debug"',
    'const val VERSION = "2026-09-06-production-ai-studio-live-r8-background-resync"',
    'realtime version',
)
s = replace_once(
    s,
    '''        private const val HEALTH_TICK_MS = 650L\n        private const val SETUP_STALL_TIMEOUT_MS = 20_000L\n''',
    '''        private const val HEALTH_TICK_MS = 650L\n        private const val HEALTH_SCHEDULER_GAP_MS = 5_000L\n        private const val SETUP_STALL_TIMEOUT_MS = 20_000L\n''',
    'realtime scheduler gap constant',
)
write(path, s)

# Subtitle translation is an auxiliary processing mode; keep its service alive too.
path = 'app/src/main/AndroidManifest.xml'
s = read(path)
s = replace_once(
    s,
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />\n',
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n',
    'manifest data sync permission',
)
s = replace_once(
    s,
    'android:foregroundServiceType="microphone|mediaProjection|mediaPlayback"',
    'android:foregroundServiceType="microphone|mediaProjection|mediaPlayback|dataSync"',
    'manifest service type',
)
write(path, s)

path = 'app/src/main/java/com/oai/geminilivetranslate/service/NotificationController.kt'
s = read(path)
s = replace_once(
    s,
    '''    fun update(state: SessionUiState) {\n        manager.notify(NOTIFICATION_ID, build(state))\n    }\n''',
    '''    fun startDataSync(service: TranslationService, state: SessionUiState) {\n        ServiceCompat.startForeground(\n            service,\n            NOTIFICATION_ID,\n            build(state),\n            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,\n        )\n    }\n\n    fun update(state: SessionUiState) {\n        manager.notify(NOTIFICATION_ID, build(state))\n    }\n''',
    'notification data sync start',
)
s = replace_once(
    s,
    '''        return NotificationCompat.Builder(context, channel)\n            .setSmallIcon(R.drawable.ic_app)\n            .setContentTitle("Gemini Live Translate")\n            .setContentText(if (state.running) "$mode → ${state.currentLanguage}: ${state.status}" else state.status)\n            .setContentIntent(contentIntent)\n            .setOngoing(state.running)\n            .setOnlyAlertOnce(true)\n            .setSilent(true)\n            .setCategory(NotificationCompat.CATEGORY_SERVICE)\n            .addAction(0, pauseLabel, pauseIntent)\n            .addAction(0, "Dừng", stopIntent)\n            .build()\n''',
    '''        val active = state.running || state.subtitleTranslationInProgress\n        val builder = NotificationCompat.Builder(context, channel)\n            .setSmallIcon(R.drawable.ic_app)\n            .setContentTitle("Gemini Live Translate")\n            .setContentText(if (state.running) "$mode → ${state.currentLanguage}: ${state.status}" else state.status)\n            .setContentIntent(contentIntent)\n            .setOngoing(active)\n            .setOnlyAlertOnce(true)\n            .setSilent(true)\n            .setCategory(NotificationCompat.CATEGORY_SERVICE)\n        if (state.running) builder.addAction(0, pauseLabel, pauseIntent)\n        builder.addAction(0, "Dừng", stopIntent)\n        return builder.build()\n''',
    'notification auxiliary active state',
)
write(path, s)

path = 'app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt'
s = read(path)
s = replace_once(
    s,
    '            ACTION_STOP -> stopTranslation()\n',
    '''            ACTION_STOP -> {\n                if (_state.value.subtitleTranslationInProgress) stopSubtitleTranslation("Đã dừng dịch phụ đề")\n                else stopTranslation()\n            }\n''',
    'service stop auxiliary work',
)
s = replace_once(
    s,
    '''        subtitleTranslationJob?.cancel()\n        subtitleTranslationJob = serviceScope.launch(Dispatchers.IO) {\n''',
    '''        ensureStartedForSubtitleTranslation()\n        subtitleTranslationJob?.cancel()\n        subtitleTranslationJob = serviceScope.launch(Dispatchers.IO) {\n''',
    'subtitle foreground ownership',
)
s = replace_once(
    s,
    '''            } finally {\n                subtitleTranslationJob = null\n            }\n        }\n    }\n\n    fun toggleSubtitleLanguage() {\n''',
    '''            } finally {\n                subtitleTranslationJob = null\n                finishSubtitleTranslationForeground()\n            }\n        }\n    }\n\n    private fun ensureStartedForSubtitleTranslation() {\n        ContextCompat.startForegroundService(\n            this,\n            Intent(this, TranslationService::class.java).setAction(ACTION_SESSION_KEEP_ALIVE),\n        )\n        runCatching { acquireWakeLock() }.onFailure {\n            logger.log(0, "SubtitleTranslate", "Không tạo được wake lock cho dịch phụ đề", it)\n        }\n        notificationController.startDataSync(this, _state.value)\n        logger.log(2, "Service", "R38_SUBTITLE_BACKGROUND_OWNED processing=$processingMode")\n    }\n\n    private fun finishSubtitleTranslationForeground() {\n        releaseWakeLock()\n        notificationController.cancel()\n        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }\n        stopSelf()\n        logger.log(2, "Service", "R38_SUBTITLE_BACKGROUND_RELEASED")\n    }\n\n    private fun stopSubtitleTranslation(message: String) {\n        subtitleTranslationJob?.cancel()\n        subtitleTranslationJob = null\n        updateState {\n            it.copy(\n                status = message,\n                subtitleTranslationInProgress = false,\n                subtitleShowingVietnamese = false,\n            )\n        }\n        scheduleHistorySave("subtitle-translate-stop")\n        finishSubtitleTranslationForeground()\n    }\n\n    fun toggleSubtitleLanguage() {\n''',
    'subtitle foreground release helper',
)
write(path, s)

# Update existing executor source test and add cross-mode timing regression tests.
path = 'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionExecutorSourceTest.kt'
s = read(path)
s = replace_once(
    s,
    '2026-09-06-web-session-r12.10-background-service',
    '2026-09-06-web-session-r12.11-background-resync',
    'executor test version',
)
s = replace_once(
    s,
    '''        assertTrue(executor.contains("R37_WEBVIEW_BACKGROUND_POLICY"))\n''',
    '''        assertTrue(executor.contains("R37_WEBVIEW_BACKGROUND_POLICY"))\n        assertTrue(executor.contains("R38_WATCHDOG_BACKGROUND_DEFER"))\n        assertTrue(executor.contains("R38_WATCHDOG_SCHEDULER_GAP"))\n        assertTrue(executor.contains("R38_TIMEOUT_RESYNC"))\n        assertTrue(executor.contains("R38_TIMEOUT_CONFIRMED"))\n        assertTrue(executor.contains("R38_ATTACHMENT_SCHEDULER_GAP"))\n        assertTrue(executor.contains("R38_STT_TIMEOUT_RESYNC"))\n''',
    'executor test timing markers',
)
write(path, s)

path = 'app/src/test/java/com/oai/geminilivetranslate/network/AiStudioBackgroundTimingSourceTest.kt'
write(path, '''package com.oai.geminilivetranslate.network\n\nimport java.io.File\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass AiStudioBackgroundTimingSourceTest {\n    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))\n        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")\n\n    @Test\n    fun attachmentExecutorResyncsBeforeTimingOutVideoAndStt() {\n        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")\n        assertTrue(executor.contains("GeminiTranslateApp.currentActivity() == null"))\n        assertTrue(executor.contains("resyncPendingRequest(requestSeq, \"timeout-probe\")"))\n        assertTrue(executor.contains("R38_ATTACHMENT_TIMEOUT_CONFIRMED"))\n        assertTrue(executor.contains("R38_STT_TIMEOUT_RESYNC"))\n        assertTrue(executor.contains("tag\",\"STT_RUN"))\n        assertTrue(executor.contains("role\",\"stt-run"))\n        assertFalse(executor.contains("performAccessibilityAction"))\n    }\n\n    @Test\n    fun realtimeAiStudioModesProtectWebViewAndSuppressBackgroundStaleTimeouts() {\n        val live = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")\n        assertTrue(live.contains("2026-09-06-production-ai-studio-live-r8-background-resync"))\n        assertTrue(live.contains("RENDERER_PRIORITY_IMPORTANT"))\n        assertTrue(live.contains("R38_LIVE_WEBVIEW_BACKGROUND_POLICY"))\n        assertTrue(live.contains("R38_LIVE_BACKGROUND_DEFER"))\n        assertTrue(live.contains("R38_LIVE_SCHEDULER_GAP"))\n        assertTrue(live.contains("!suppressTimeouts && !setupDelivered.get()"))\n        assertTrue(live.contains("!suppressTimeouts && setupDelivered.get()"))\n    }\n}\n''')

path = 'app/src/test/java/com/oai/geminilivetranslate/service/TranslationServiceBackgroundSourceTest.kt'
s = read(path)
s = replace_once(
    s,
    '''    @Test\n    fun rebindingSameFileCannotStopRunningFileSession() {\n''',
    '''    @Test\n    fun subtitleTranslationAlsoOwnsForegroundLifecycle() {\n        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")\n        val notification = source("src/main/java/com/oai/geminilivetranslate/service/NotificationController.kt")\n        val manifest = source("src/main/AndroidManifest.xml")\n        assertTrue(service.contains("ensureStartedForSubtitleTranslation()"))\n        assertTrue(service.contains("R38_SUBTITLE_BACKGROUND_OWNED"))\n        assertTrue(notification.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))\n        assertTrue(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))\n        assertTrue(manifest.contains("mediaPlayback|dataSync"))\n    }\n\n    @Test\n    fun rebindingSameFileCannotStopRunningFileSession() {\n''',
    'service background subtitle test',
)
write(path, s)

print('R18.30 background resync patch applied')
