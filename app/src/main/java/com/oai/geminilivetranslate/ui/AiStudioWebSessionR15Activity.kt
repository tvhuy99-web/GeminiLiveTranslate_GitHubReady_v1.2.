package com.oai.geminilivetranslate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.audio.AudioSource
import com.oai.geminilivetranslate.audio.FileAudioSource
import com.oai.geminilivetranslate.core.AiStudioWebLiveClient
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicLong

/**
 * R15 continuous real-source bridge.
 *
 * R14 proved that Android PCM can replace AI Studio's authenticated Live audio carrier. R15 removes
 * the fixed 8-second test harness and routes the application's real FileAudioSource continuously
 * through a reusable AiStudioWebLiveClient. AI Studio still owns authentication, model setup,
 * WebChannel sequence/offset/ack state and server output. The page microphone remains the carrier
 * clock for this milestone and the user still starts Live manually.
 *
 * This activity deliberately stays isolated from TranslationService until server output parsing and
 * automatic Live-session bootstrap are proven, avoiding regressions in the production API-key path.
 */
class AiStudioWebSessionR15Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var liveClient: AiStudioWebLiveClient

    private lateinit var stateView: TextView
    private lateinit var sourceView: TextView
    private lateinit var bridgeView: TextView
    private lateinit var micView: TextView

    private var selectedAudioUri: Uri? = null
    private var source: FileAudioSource? = null
    private var sourceJob: Job? = null
    private var statsJob: Job? = null
    private val sourceChunks = AtomicLong(0L)
    private val sourceBytes = AtomicLong(0L)
    private val sendQueued = AtomicLong(0L)
    private val sendBackpressured = AtomicLong(0L)
    private val sendNotArmed = AtomicLong(0L)
    private val sendClosed = AtomicLong(0L)
    private val noisyProbeLogCounters = mutableMapOf<String, Int>()
    @Volatile private var sourceStartedAt = 0L
    @Volatile private var sourceCompleted = false

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::micView.isInitialized) {
            micView.text = if (granted) "Mic carrier WebView: Android đã cấp quyền" else "Mic carrier WebView: Android chưa cấp quyền"
        }
        log("R15_ANDROID_MIC_PERMISSION", "granted=$granted")
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedAudioUri = uri
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceView.text = "Nguồn thật: ${safe(uri.lastPathSegment.orEmpty(), 300)}"
            log("R15_AUDIO_FILE_SELECTED", "scheme=${uri.scheme.orEmpty()} nameChars=${uri.lastPathSegment.orEmpty().length}")
        } else {
            sourceView.text = "Nguồn thật: chưa chọn"
            log("R15_AUDIO_FILE_SELECTED", "cancelled=true")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installDocumentStartLayers()
        installLiveWebPermissions()
        liveClient = AiStudioWebLiveClient(executor.webView) { name, detail -> log(name, detail) }
        buildUi()
        log(
            "R15_ACTIVITY_CREATE",
            "version=$VERSION client=${AiStudioWebLiveClient.VERSION} direct=${AiStudioWebSessionR14DirectLiveEngine.VERSION} targetModel=${AiStudioWebSessionLiveProbe.TARGET_MODEL} executor=${AiStudioWebSessionExecutor.VERSION}",
        )
        executor.start(AI_STUDIO_NEW_CHAT)
        startStatsLoop()
    }

    override fun onDestroy() {
        stopRealSource(clearQueues = true)
        statsJob?.cancel()
        liveClient.close()
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread {
            if (::stateView.isInitialized) stateView.text = "Web Session: $state | $detail"
        }
        log("R15_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        if (isNoisyLegacyProbeLog(name)) {
            val ordinal = synchronized(noisyProbeLogCounters) {
                val next = (noisyProbeLogCounters[name] ?: 0) + 1
                noisyProbeLogCounters[name] = next
                next
            }
            if (ordinal <= LEGACY_PROBE_INITIAL_KEEP || ordinal % LEGACY_PROBE_SAMPLE_EVERY == 0) {
                log(name, "sampleOrdinal=$ordinal ${safe(detail, LEGACY_PROBE_DETAIL_CHARS)}")
            }
            return
        }
        log(name, detail)
    }

    private fun isNoisyLegacyProbeLog(name: String): Boolean =
        name.startsWith("JS_R132_BIDI_") ||
            name.startsWith("JS_R13_XHR_") ||
            name == "JS_R13_BEACON" ||
            name == "JS_R13_RESOURCE"

    private fun installDocumentStartLayers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R15_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        listOf(
            AiStudioWebSessionLiveProbe.DOCUMENT_START,
            AiStudioWebSessionR13DeepProbe.DOCUMENT_START,
            AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START,
        ).forEach { script ->
            WebViewCompat.addDocumentStartJavaScript(executor.webView, script, setOf(AI_STUDIO_ORIGIN))
        }
        log(
            "R15_DOCUMENT_START_REGISTERED",
            "origin=$AI_STUDIO_ORIGIN transport=${AiStudioWebSessionLiveProbe.VERSION} deep=${AiStudioWebSessionR13DeepProbe.VERSION} direct=${AiStudioWebSessionR14DirectLiveEngine.VERSION}",
        )
    }

    private fun installLiveWebPermissions() {
        executor.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val resources = req.resources.orEmpty()
                val asksAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val asksVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val androidGranted = hasMicPermission()
                log(
                    "R15_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$androidGranted resources=${resources.size}",
                )
                runOnUiThread {
                    if (asksAudio && androidGranted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R15_WEB_PERMISSION_RESULT", "audioCapture=granted videoCapture=false")
                    } else {
                        req.deny()
                        log("R15_WEB_PERMISSION_RESULT", "denied asksAudio=$asksAudio asksVideo=$asksVideo")
                        if (asksAudio && !androidGranted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(TextView(this).apply {
            text = "AI STUDIO WEB SESSION R15 - REAL SOURCE BRIDGE"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())
        controls.addView(TextView(this).apply {
            text = "R15 stream liên tục chính FileAudioSource của ứng dụng vào Gemini 3.1 Flash Live Preview. AI Studio vẫn giữ auth/WebChannel và microphone của trang chỉ làm nhịp carrier. Không còn giới hạn 8 giây."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(6))
        }, fullWidth())

        stateView = TextView(this).apply { text = "Web Session: NEW"; textSize = 14f; setTextIsSelectable(true) }
        micView = TextView(this).apply {
            text = if (hasMicPermission()) "Mic carrier WebView: Android đã cấp quyền" else "Mic carrier WebView: Android chưa cấp quyền"
            textSize = 14f
        }
        sourceView = TextView(this).apply { text = "Nguồn thật: chưa chọn"; textSize = 14f; setTextIsSelectable(true) }
        bridgeView = TextView(this).apply { text = "R15 Bridge: chưa chạy"; textSize = 12f; setTextIsSelectable(true) }
        controls.addView(stateView, fullWidth())
        controls.addView(micView, fullWidth())
        controls.addView(sourceView, fullWidth())
        controls.addView(bridgeView, fullWidth())

        controls.addView(actionButton("Cấp quyền microphone carrier cho AI Studio") { requestMic.launch(Manifest.permission.RECORD_AUDIO) }, fullWidth())
        controls.addView(actionButton("1. Reset probe + R14/R15") { resetAll() }, fullWidth())
        controls.addView(actionButton("2. Đánh dấu rồi mở Live thủ công") { markAll() }, fullWidth())
        controls.addView(actionButton("3. ARM R15 sau khi Live đã chạy") { liveClient.arm(true) }, fullWidth())
        controls.addView(actionButton("4. Chọn tệp audio nguồn thật") { pickAudio.launch(arrayOf("audio/*", "video/*")) }, fullWidth())
        controls.addView(actionButton("5. Bắt đầu stream nguồn thật liên tục") { startRealSource() }, fullWidth())
        controls.addView(actionButton("Tạm dừng nguồn thật") { source?.pause(); log("R15_SOURCE_PAUSE", "requested=true") }, fullWidth())
        controls.addView(actionButton("Tiếp tục nguồn thật") { source?.resume(); log("R15_SOURCE_RESUME", "requested=true") }, fullWidth())
        controls.addView(actionButton("Dừng nguồn thật và xóa queue") { stopRealSource(clearQueues = true) }, fullWidth())
        controls.addView(actionButton("Chụp R15 FINAL SUMMARY") { persistFinalSummary("snapshot") }, fullWidth())
        controls.addView(actionButton("Tải lại AI Studio") { stopRealSource(clearQueues = true); executor.start(AI_STUDIO_NEW_CHAT) }, fullWidth())
        controls.addView(actionButton("Mở / chia sẻ diagnostics") { shareLogsWithFinalSummary() }, fullWidth())

        root.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(500)))
        root.addView(executor.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun resetAll() {
        stopRealSource(clearQueues = false)
        synchronized(noisyProbeLogCounters) { noisyProbeLogCounters.clear() }
        sourceChunks.set(0L)
        sourceBytes.set(0L)
        sendQueued.set(0L)
        sendBackpressured.set(0L)
        sendNotArmed.set(0L)
        sendClosed.set(0L)
        sourceCompleted = false
        sourceStartedAt = 0L
        liveClient.reset()
        eval(
            "JSON.stringify({transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.reset('r15-before-live'):null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.reset('r15-before-live'):null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null})",
            "R15_RESET_NATIVE",
        )
        labLog.snapshot("r15-final-summary", "R15 FINAL SUMMARY\nreason=reset\nactivityVersion=$VERSION\nstate=RESET\n")
    }

    private fun markAll() {
        eval(
            "JSON.stringify({transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.mark('r15-before-live'):null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.mark('r15-before-live'):null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null})",
            "R15_MARK_NATIVE",
        )
    }

    private fun startRealSource() {
        val uri = selectedAudioUri ?: run {
            sourceView.text = "Nguồn thật: hãy chọn tệp trước"
            log("R15_SOURCE_REJECTED", "reason=no-file")
            return
        }
        stopRealSource(clearQueues = true)
        liveClient.arm(true)
        sourceChunks.set(0L)
        sourceBytes.set(0L)
        sendQueued.set(0L)
        sendBackpressured.set(0L)
        sendNotArmed.set(0L)
        sendClosed.set(0L)
        sourceCompleted = false
        sourceStartedAt = SystemClock.elapsedRealtime()

        val created = FileAudioSource(
            context = this,
            uri = uri,
            pacingEnabled = true,
            leadMs = FILE_SOURCE_LEAD_MS,
            initialPlaybackSpeed = 1f,
            logger = null,
        )
        source = created
        sourceView.text = "Nguồn thật: đang stream liên tục..."
        log("R15_SOURCE_START", "type=FileAudioSource pacing=true leadMs=$FILE_SOURCE_LEAD_MS")
        sourceJob = lifecycleScope.launch(Dispatchers.IO) {
            created.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    if (source !== created || data.isEmpty()) return
                    val chunks = sourceChunks.incrementAndGet()
                    val bytes = sourceBytes.addAndGet(data.size.toLong())
                    when (liveClient.sendAudio(data)) {
                        AiStudioWebLiveClient.SendResult.QUEUED -> sendQueued.incrementAndGet()
                        AiStudioWebLiveClient.SendResult.BACKPRESSURED -> sendBackpressured.incrementAndGet()
                        AiStudioWebLiveClient.SendResult.NOT_ARMED -> sendNotArmed.incrementAndGet()
                        AiStudioWebLiveClient.SendResult.CLOSED -> sendClosed.incrementAndGet()
                    }
                    if (chunks == 1L || chunks % 50L == 0L) {
                        log(
                            "R15_SOURCE_PCM",
                            "chunks=$chunks bytes=$bytes lastBytes=${data.size} queued=${sendQueued.get()} bp=${sendBackpressured.get()} notArmed=${sendNotArmed.get()} closed=${sendClosed.get()}",
                        )
                    }
                }

                override fun onProgress(percent: Int, positionMs: Long, durationMs: Long) {
                    if (source === created && (percent % 10 == 0 || percent >= 99)) {
                        runOnUiThread { sourceView.text = "Nguồn thật: $percent% | ${positionMs / 1000}s / ${durationMs / 1000}s" }
                    }
                }

                override fun onCompleted() {
                    if (source !== created) return
                    sourceCompleted = true
                    liveClient.sendAudioStreamEnd()
                    log(
                        "R15_SOURCE_COMPLETED",
                        "chunks=${sourceChunks.get()} bytes=${sourceBytes.get()} elapsedMs=${elapsedSourceMs()} queued=${sendQueued.get()} bp=${sendBackpressured.get()}",
                    )
                    runOnUiThread { sourceView.text = "Nguồn thật: đã gửi hết tệp, đang chờ Gemini phản hồi" }
                    persistFinalSummary("source-completed")
                }

                override fun onError(error: Throwable) {
                    if (source !== created) return
                    log("R15_SOURCE_ERROR", "type=${error.javaClass.simpleName} message=${safe(error.message.orEmpty(), 900)}")
                    runOnUiThread { sourceView.text = "Nguồn thật: lỗi ${safe(error.message.orEmpty(), 500)}" }
                    persistFinalSummary("source-error")
                }
            })
        }
    }

    private fun stopRealSource(clearQueues: Boolean) {
        runCatching { source?.stop() }
        source = null
        sourceJob?.cancel()
        sourceJob = null
        if (clearQueues && ::liveClient.isInitialized) liveClient.clear()
        if (::sourceView.isInitialized && selectedAudioUri != null && !sourceCompleted) {
            sourceView.text = "Nguồn thật: đã dừng"
        }
        log("R15_SOURCE_STOP", "clearQueues=$clearQueues chunks=${sourceChunks.get()} bytes=${sourceBytes.get()}")
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (isActive) {
                val stats = liveClient.stats()
                bridgeView.text = buildString {
                    append("R15 Bridge: armed=${stats.armed}")
                    append(" | localQ=${stats.localQueueFrames}")
                    append(" | frames=${stats.framesCreated}")
                    append(" | JS accepted=${stats.framesAcceptedByJs}")
                    append(" | localDrop=${stats.framesDroppedLocally}")
                    append(" | jsDrop=${stats.framesDroppedByJs}")
                    append(" | sourceChunks=${sourceChunks.get()}")
                }
                if (source != null) liveClient.requestEngineSnapshot()
                delay(1_000L)
            }
        }
    }

    private fun persistFinalSummary(reason: String, after: (() -> Unit)? = null) {
        if (!::liveClient.isInitialized) {
            after?.invoke()
            return
        }
        liveClient.requestEngineSnapshot { stats ->
            val engine = parseObject(stats.engineState)
            val summary = buildString {
                appendLine("R15 FINAL SUMMARY")
                appendLine("reason=${safe(reason, 120)}")
                appendLine("capturedAtEpochMs=${System.currentTimeMillis()}")
                appendLine("activityVersion=$VERSION")
                appendLine("clientVersion=${AiStudioWebLiveClient.VERSION}")
                appendLine("directVersion=${AiStudioWebSessionR14DirectLiveEngine.VERSION}")
                appendLine("targetModel=${AiStudioWebSessionLiveProbe.TARGET_MODEL}")
                appendLine("audioFileSelected=${selectedAudioUri != null}")
                appendLine("sourceRunning=${source != null}")
                appendLine("sourceCompleted=$sourceCompleted")
                appendLine("sourceElapsedMs=${elapsedSourceMs()}")
                appendLine("sourceChunks=${sourceChunks.get()}")
                appendLine("sourceBytes=${sourceBytes.get()}")
                appendLine("sendQueued=${sendQueued.get()}")
                appendLine("sendBackpressured=${sendBackpressured.get()}")
                appendLine("sendNotArmed=${sendNotArmed.get()}")
                appendLine("sendClosed=${sendClosed.get()}")
                appendLine("clientArmed=${stats.armed}")
                appendLine("clientLocalQueueFrames=${stats.localQueueFrames}")
                appendLine("clientPcmBytesReceived=${stats.pcmBytesReceived}")
                appendLine("clientFramesCreated=${stats.framesCreated}")
                appendLine("clientFramesSubmittedToJs=${stats.framesSubmittedToJs}")
                appendLine("clientFramesAcceptedByJs=${stats.framesAcceptedByJs}")
                appendLine("clientFramesRejectedByJs=${stats.framesRejectedByJs}")
                appendLine("clientFramesDroppedByJs=${stats.framesDroppedByJs}")
                appendLine("clientFramesDroppedLocally=${stats.framesDroppedLocally}")
                appendLine("clientJsBatches=${stats.jsBatches}")
                appendLine("clientJsCallbacks=${stats.jsCallbacks}")
                if (engine != null) {
                    appendLine("templateObserved=${engine.optBoolean("templateObserved", false)}")
                    appendLine("templateMime=${safe(engine.optString("templateMime"), 120)}")
                    appendLine("templatePayloadChars=${engine.optInt("templatePayloadChars", 0)}")
                    appendLine("carrierRequests=${engine.optLong("carrierRequests", 0L)}")
                    appendLine("carrierFrames=${engine.optLong("carrierFrames", 0L)}")
                    appendLine("replacedFrames=${engine.optLong("replacedFrames", 0L)}")
                    appendLine("injectedRequests=${engine.optLong("injectedRequests", 0L)}")
                    appendLine("injectedHttp2xx=${engine.optLong("injectedHttp2xx", 0L)}")
                    appendLine("injectedHttpError=${engine.optLong("injectedHttpError", 0L)}")
                    appendLine("injectedZeroStatusEnd=${engine.optLong("injectedZeroStatusEnd", 0L)}")
                    appendLine("engineQueueDepth=${engine.optInt("queueDepth", 0)}")
                    appendLine("lastStatus=${engine.optInt("lastStatus", 0)}")
                    appendLine("lastCarrierAgeMs=${engine.optLong("lastCarrierAgeMs", -1L)}")
                    appendLine("lastReplaceAgeMs=${engine.optLong("lastReplaceAgeMs", -1L)}")
                }
                appendLine("engineState=${safe(stats.engineState, 6000)}")
            }
            labLog.snapshot("r15-final-summary", summary)
            log(
                "R15_FINAL_SUMMARY",
                "reason=$reason sourceChunks=${sourceChunks.get()} sourceBytes=${sourceBytes.get()} framesCreated=${stats.framesCreated} accepted=${stats.framesAcceptedByJs} localDrop=${stats.framesDroppedLocally} jsDrop=${stats.framesDroppedByJs} replaced=${engine?.optLong("replacedFrames", 0L) ?: -1L} http2xx=${engine?.optLong("injectedHttp2xx", 0L) ?: -1L} httpError=${engine?.optLong("injectedHttpError", 0L) ?: -1L}",
            )
            runOnUiThread { after?.invoke() }
        }
    }

    private fun shareLogsWithFinalSummary() {
        persistFinalSummary("before-share") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }
    }

    private fun eval(expression: String, logName: String) {
        executor.webView.evaluateJavascript(expression) { raw ->
            log(logName, safe(decodeEvalValue(raw), 12_000))
        }
    }

    private fun parseObject(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun elapsedSourceMs(): Long = if (sourceStartedAt <= 0L) 0L else (SystemClock.elapsedRealtime() - sourceStartedAt).coerceAtLeast(0L)

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun log(name: String, detail: String) = labLog.event("I", name, detail)

    private fun safeUrl(raw: String?): String {
        val uri = runCatching { Uri.parse(raw.orEmpty()) }.getOrNull()
        return if (uri == null) "" else "${uri.scheme.orEmpty()}://${uri.host.orEmpty()}${uri.path.orEmpty()}".take(700)
    }

    private fun safe(text: String, max: Int): String = text.replace('\u0000', ' ').replace('\n', ' ').take(max)

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minimumHeight = dp(50)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VERSION = "2026-09-03-web-session-r15.0-real-source-bridge"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_NEW_CHAT = "https://aistudio.google.com/prompts/new_chat"
        private const val FILE_SOURCE_LEAD_MS = 80
        private const val LEGACY_PROBE_SAMPLE_EVERY = 100
        private const val LEGACY_PROBE_INITIAL_KEEP = 3
        private const val LEGACY_PROBE_DETAIL_CHARS = 3_500
    }
}
