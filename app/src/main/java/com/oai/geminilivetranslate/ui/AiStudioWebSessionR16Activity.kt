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
import com.oai.geminilivetranslate.core.AiStudioWebLiveOutputBridge
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicLong

/**
 * R16 bidirectional Web Session milestone.
 *
 * R15 input replacement is retained. R16 additionally unwraps BrowserChannel response framing and
 * sends recognized Live output to Android over a dedicated non-diagnostic JavascriptInterface.
 * Production TranslationService remains untouched until this receive path is proven on device.
 */
class AiStudioWebSessionR16Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var inputClient: AiStudioWebLiveClient
    private lateinit var outputBridge: AiStudioWebLiveOutputBridge

    private lateinit var stateView: TextView
    private lateinit var sourceView: TextView
    private lateinit var inputView: TextView
    private lateinit var outputView: TextView
    private lateinit var textView: TextView
    private lateinit var micView: TextView

    private var selectedAudioUri: Uri? = null
    private var source: FileAudioSource? = null
    private var sourceJob: Job? = null
    private var statsJob: Job? = null
    private val sourceChunks = AtomicLong(0L)
    private val sourceBytes = AtomicLong(0L)
    private val sendQueued = AtomicLong(0L)
    private val sendBackpressured = AtomicLong(0L)
    private val noisyLogCounters = mutableMapOf<String, Int>()
    @Volatile private var sourceStartedAt = 0L
    @Volatile private var sourceCompleted = false
    @Volatile private var lastTextKind = ""
    @Volatile private var lastText = ""
    @Volatile private var lastSignal = ""

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::micView.isInitialized) micView.text = if (granted) "Mic carrier: đã cấp quyền" else "Mic carrier: chưa cấp quyền"
        log("R16_ANDROID_MIC_PERMISSION", "granted=$granted")
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedAudioUri = uri
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceView.text = "Nguồn: ${safe(uri.lastPathSegment.orEmpty(), 300)}"
            log("R16_AUDIO_FILE_SELECTED", "scheme=${uri.scheme.orEmpty()} nameChars=${uri.lastPathSegment.orEmpty().length}")
        } else {
            sourceView.text = "Nguồn: chưa chọn"
            log("R16_AUDIO_FILE_SELECTED", "cancelled=true")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installDocumentStartLayers()
        installLiveWebPermissions()
        inputClient = AiStudioWebLiveClient(executor.webView) { name, detail -> log(name, detail) }
        outputBridge = AiStudioWebLiveOutputBridge(
            executor.webView,
            object : AiStudioWebLiveOutputBridge.Listener {
                override fun onAudio(pcm24kMono: ByteArray, mimeType: String) {
                    val s = outputBridge.stats()
                    if (s.audioChunks == 1L || s.audioChunks % 25L == 0L) {
                        runOnUiThread { outputView.text = outputStatus(s) }
                    }
                }

                override fun onText(kind: String, text: String) {
                    lastTextKind = kind
                    lastText = text
                    runOnUiThread {
                        textView.text = "R16 text [$kind]: ${text.take(1200)}"
                        outputView.text = outputStatus(outputBridge.stats())
                    }
                }

                override fun onSignal(kind: String, value: String) {
                    lastSignal = "$kind:$value"
                    runOnUiThread { outputView.text = outputStatus(outputBridge.stats()) + "\nSignal: ${safe(lastSignal, 300)}" }
                }
            },
        ) { name, detail -> log(name, detail) }
        buildUi()
        log(
            "R16_ACTIVITY_CREATE",
            "version=$VERSION input=${AiStudioWebLiveClient.VERSION} output=${AiStudioWebLiveOutputBridge.VERSION} decoder=${AiStudioWebSessionR16LiveOutputEngine.VERSION} targetModel=${AiStudioWebSessionLiveProbe.TARGET_MODEL}",
        )
        executor.start(AI_STUDIO_NEW_CHAT)
        startStatsLoop()
    }

    override fun onDestroy() {
        stopRealSource(clearQueues = true)
        statsJob?.cancel()
        inputClient.close()
        outputBridge.close()
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread { if (::stateView.isInitialized) stateView.text = "Web Session: $state | $detail" }
        log("R16_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        if (isNoisy(name)) {
            val ordinal = synchronized(noisyLogCounters) {
                val next = (noisyLogCounters[name] ?: 0) + 1
                noisyLogCounters[name] = next
                next
            }
            if (ordinal <= NOISY_INITIAL_KEEP || ordinal % NOISY_SAMPLE_EVERY == 0) {
                log(name, "sampleOrdinal=$ordinal ${safe(detail, NOISY_DETAIL_CHARS)}")
            }
            return
        }
        log(name, detail)
    }

    private fun isNoisy(name: String): Boolean =
        name.startsWith("JS_R132_BIDI_") ||
            name.startsWith("JS_R13_XHR_") ||
            name == "JS_R13_BEACON" ||
            name == "JS_R13_RESOURCE" ||
            name == "JS_R16_AUDIO_OUT" ||
            name == "JS_R16_BC_CHUNK"

    private fun installDocumentStartLayers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R16_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        listOf(
            AiStudioWebSessionLiveProbe.DOCUMENT_START,
            AiStudioWebSessionR13DeepProbe.DOCUMENT_START,
            AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START,
            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,
        ).forEach { script -> WebViewCompat.addDocumentStartJavaScript(executor.webView, script, setOf(AI_STUDIO_ORIGIN)) }
        log(
            "R16_DOCUMENT_START_REGISTERED",
            "transport=${AiStudioWebSessionLiveProbe.VERSION} deep=${AiStudioWebSessionR13DeepProbe.VERSION} direct=${AiStudioWebSessionR14DirectLiveEngine.VERSION} output=${AiStudioWebSessionR16LiveOutputEngine.VERSION}",
        )
    }

    private fun installLiveWebPermissions() {
        executor.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val resources = req.resources.orEmpty()
                val asksAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val asksVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val granted = hasMicPermission()
                log("R16_WEB_PERMISSION_REQUEST", "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$granted")
                runOnUiThread {
                    if (asksAudio && granted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R16_WEB_PERMISSION_RESULT", "audioCapture=granted videoCapture=false")
                    } else {
                        req.deny()
                        log("R16_WEB_PERMISSION_RESULT", "denied asksAudio=$asksAudio asksVideo=$asksVideo")
                        if (asksAudio && !granted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(TextView(this).apply {
            text = "AI STUDIO WEB SESSION R16 - BIDIRECTIONAL LIVE"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())
        controls.addView(TextView(this).apply {
            text = "R16 giữ input bridge R15 và bóc BrowserChannel chiều về. Audio/text chỉ đi qua bridge riêng; diagnostics không chứa Base64 audio hay nội dung BrowserChannel thô. Vẫn mở Live thủ công ở milestone này."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(6))
        }, fullWidth())
        stateView = TextView(this).apply { text = "Web Session: NEW"; textSize = 14f; setTextIsSelectable(true) }
        micView = TextView(this).apply { text = if (hasMicPermission()) "Mic carrier: đã cấp quyền" else "Mic carrier: chưa cấp quyền"; textSize = 14f }
        sourceView = TextView(this).apply { text = "Nguồn: chưa chọn"; textSize = 14f; setTextIsSelectable(true) }
        inputView = TextView(this).apply { text = "Input bridge: chưa chạy"; textSize = 12f; setTextIsSelectable(true) }
        outputView = TextView(this).apply { text = "Output bridge: chưa nhận"; textSize = 12f; setTextIsSelectable(true) }
        textView = TextView(this).apply { text = "R16 text: chưa nhận"; textSize = 13f; setTextIsSelectable(true) }
        listOf(stateView, micView, sourceView, inputView, outputView, textView).forEach { controls.addView(it, fullWidth()) }

        controls.addView(actionButton("Cấp quyền microphone carrier") { requestMic.launch(Manifest.permission.RECORD_AUDIO) }, fullWidth())
        controls.addView(actionButton("1. Reset R13/R14/R16") { resetAll() }, fullWidth())
        controls.addView(actionButton("2. Đánh dấu rồi mở Live thủ công") { markAll() }, fullWidth())
        controls.addView(actionButton("3. ARM input sau khi Live đã chạy") { inputClient.arm(true) }, fullWidth())
        controls.addView(actionButton("4. Chọn tệp audio nguồn thật") { pickAudio.launch(arrayOf("audio/*", "video/*")) }, fullWidth())
        controls.addView(actionButton("5. Stream sau khi kiểm tra carrier") { startRealSourceWithCarrierGate() }, fullWidth())
        controls.addView(actionButton("Dừng nguồn và xóa queue") { stopRealSource(clearQueues = true) }, fullWidth())
        controls.addView(actionButton("Chụp R16 FINAL SUMMARY") { persistFinalSummary("snapshot") }, fullWidth())
        controls.addView(actionButton("Tải lại AI Studio") { stopRealSource(clearQueues = true); executor.start(AI_STUDIO_NEW_CHAT) }, fullWidth())
        controls.addView(actionButton("Mở / chia sẻ diagnostics") { shareLogs() }, fullWidth())

        root.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520)))
        root.addView(executor.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun resetAll() {
        stopRealSource(clearQueues = false)
        synchronized(noisyLogCounters) { noisyLogCounters.clear() }
        sourceChunks.set(0L); sourceBytes.set(0L); sendQueued.set(0L); sendBackpressured.set(0L)
        sourceCompleted = false; sourceStartedAt = 0L; lastTextKind = ""; lastText = ""; lastSignal = ""
        inputClient.reset()
        eval(
            "JSON.stringify({transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.reset('r16-before-live'):null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.reset('r16-before-live'):null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.reset():null})",
            "R16_RESET_NATIVE",
        )
        labLog.snapshot("r16-final-summary", "R16 FINAL SUMMARY\nreason=reset\nactivityVersion=$VERSION\nstate=RESET\n")
    }

    private fun markAll() {
        eval(
            "JSON.stringify({transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.mark('r16-before-live'):null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.mark('r16-before-live'):null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null})",
            "R16_MARK_NATIVE",
        )
    }

    private fun startRealSourceWithCarrierGate() {
        val uri = selectedAudioUri ?: run {
            sourceView.text = "Nguồn: hãy chọn tệp trước"
            log("R16_SOURCE_REJECTED", "reason=no-file")
            return
        }
        inputClient.arm(true)
        inputClient.requestEngineSnapshot { stats ->
            val state = runCatching { JSONObject(stats.engineState) }.getOrNull()
            val template = state?.optBoolean("templateObserved", false) == true
            val carriers = state?.optLong("carrierRequests", 0L) ?: 0L
            if (!template || carriers <= 0L) {
                runOnUiThread { sourceView.text = "Nguồn: CHƯA STREAM. Hãy bật Live trước, chờ mic hoạt động rồi thử lại." }
                log("R16_SOURCE_REJECTED", "reason=live-carrier-not-ready template=$template carriers=$carriers")
                return@requestEngineSnapshot
            }
            startRealSourceNow(uri)
        }
    }

    private fun startRealSourceNow(uri: Uri) {
        stopRealSource(clearQueues = true)
        inputClient.arm(true)
        sourceChunks.set(0L); sourceBytes.set(0L); sendQueued.set(0L); sendBackpressured.set(0L)
        sourceCompleted = false; sourceStartedAt = SystemClock.elapsedRealtime()
        val created = FileAudioSource(this, uri, pacingEnabled = true, leadMs = FILE_SOURCE_LEAD_MS, initialPlaybackSpeed = 1f, logger = null)
        source = created
        sourceView.text = "Nguồn: đang stream..."
        log("R16_SOURCE_START", "type=FileAudioSource pacing=true carrierGate=passed")
        sourceJob = lifecycleScope.launch(Dispatchers.IO) {
            created.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    if (source !== created || data.isEmpty()) return
                    sourceChunks.incrementAndGet(); sourceBytes.addAndGet(data.size.toLong())
                    when (inputClient.sendAudio(data)) {
                        AiStudioWebLiveClient.SendResult.QUEUED -> sendQueued.incrementAndGet()
                        AiStudioWebLiveClient.SendResult.BACKPRESSURED -> sendBackpressured.incrementAndGet()
                        else -> Unit
                    }
                }

                override fun onProgress(percent: Int, positionMs: Long, durationMs: Long) {
                    if (source === created && (percent % 10 == 0 || percent >= 99)) runOnUiThread {
                        sourceView.text = "Nguồn: $percent% | ${positionMs / 1000}s / ${durationMs / 1000}s"
                    }
                }

                override fun onCompleted() {
                    if (source !== created) return
                    sourceCompleted = true
                    inputClient.sendAudioStreamEnd()
                    log("R16_SOURCE_COMPLETED", "chunks=${sourceChunks.get()} bytes=${sourceBytes.get()} elapsedMs=${elapsedSourceMs()}")
                    runOnUiThread { sourceView.text = "Nguồn: đã đọc hết tệp, chờ queue/carrier hoàn tất" }
                    persistFinalSummary("source-completed")
                }

                override fun onError(error: Throwable) {
                    if (source !== created) return
                    log("R16_SOURCE_ERROR", "type=${error.javaClass.simpleName} message=${safe(error.message.orEmpty(), 500)}")
                    runOnUiThread { sourceView.text = "Nguồn lỗi: ${safe(error.message.orEmpty(), 300)}" }
                }
            })
        }
    }

    private fun stopRealSource(clearQueues: Boolean) {
        val old = source
        source = null
        runCatching { old?.stop() }
        sourceJob?.cancel(); sourceJob = null
        if (clearQueues && ::inputClient.isInitialized) inputClient.clear()
        if (::labLog.isInitialized) log("R16_SOURCE_STOP", "clearQueues=$clearQueues chunks=${sourceChunks.get()} bytes=${sourceBytes.get()}")
    }

    private fun startStatsLoop() {
        statsJob = lifecycleScope.launch {
            while (true) {
                delay(1_500L)
                if (!::inputClient.isInitialized || !::outputBridge.isInitialized) continue
                val i = inputClient.stats(); val o = outputBridge.stats()
                inputView.text = "Input: armed=${i.armed} localQ=${i.localQueueFrames} frames=${i.framesCreated} submitted=${i.framesSubmittedToJs} accepted=${i.framesAcceptedByJs} reject=${i.framesRejectedByJs} jsDrop=${i.framesDroppedByJs} localDrop=${i.framesDroppedLocally}"
                outputView.text = outputStatus(o)
            }
        }
    }

    private fun outputStatus(s: AiStudioWebLiveOutputBridge.Stats): String =
        "Output Android: audioChunks=${s.audioChunks} audioBytes=${s.audioBytes} textEvents=${s.textEvents} textChars=${s.textChars} signals=${s.signalEvents} decodeErrors=${s.decodeErrors} mime=${s.lastMime.ifBlank { "-" }}"

    private fun persistFinalSummary(reason: String) {
        if (!::inputClient.isInitialized || !::outputBridge.isInitialized) return
        inputClient.requestEngineSnapshot { inputStats ->
            evalRaw("JSON.stringify(window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():({ok:false,error:'r16-output-not-installed'}))") { outputEngine ->
                val o = outputBridge.stats()
                val summary = buildString {
                    appendLine("R16 FINAL SUMMARY")
                    appendLine("reason=$reason")
                    appendLine("activityVersion=$VERSION")
                    appendLine("inputClientVersion=${AiStudioWebLiveClient.VERSION}")
                    appendLine("outputBridgeVersion=${AiStudioWebLiveOutputBridge.VERSION}")
                    appendLine("outputEngineVersion=${AiStudioWebSessionR16LiveOutputEngine.VERSION}")
                    appendLine("targetModel=${AiStudioWebSessionLiveProbe.TARGET_MODEL}")
                    appendLine("sourceCompleted=$sourceCompleted")
                    appendLine("sourceElapsedMs=${elapsedSourceMs()}")
                    appendLine("sourceChunks=${sourceChunks.get()}")
                    appendLine("sourceBytes=${sourceBytes.get()}")
                    appendLine("sendQueued=${sendQueued.get()}")
                    appendLine("sendBackpressured=${sendBackpressured.get()}")
                    appendLine("inputFramesCreated=${inputStats.framesCreated}")
                    appendLine("inputFramesAcceptedByJs=${inputStats.framesAcceptedByJs}")
                    appendLine("inputFramesDroppedLocally=${inputStats.framesDroppedLocally}")
                    appendLine("inputFramesDroppedByJs=${inputStats.framesDroppedByJs}")
                    appendLine("inputEngineState=${safe(inputStats.engineState, 5000)}")
                    appendLine("outputAudioChunks=${o.audioChunks}")
                    appendLine("outputAudioBytes=${o.audioBytes}")
                    appendLine("outputTextEvents=${o.textEvents}")
                    appendLine("outputTextChars=${o.textChars}")
                    appendLine("outputSignalEvents=${o.signalEvents}")
                    appendLine("outputDecodeErrors=${o.decodeErrors}")
                    appendLine("outputLastMime=${o.lastMime}")
                    appendLine("outputLastTextKind=${o.lastTextKind}")
                    appendLine("outputLastSignalKind=${o.lastSignalKind}")
                    appendLine("outputEngineState=${safe(outputEngine, 7000)}")
                    appendLine("lastTextChars=${lastText.length}")
                    appendLine("lastTextKind=$lastTextKind")
                    appendLine("lastSignal=${safe(lastSignal, 300)}")
                }
                labLog.snapshot("r16-final-summary", summary)
                log("R16_FINAL_SUMMARY", "reason=$reason inputFrames=${inputStats.framesCreated} outputAudioChunks=${o.audioChunks} outputAudioBytes=${o.audioBytes} outputText=${o.textEvents} signals=${o.signalEvents} decodeErrors=${o.decodeErrors}")
            }
        }
    }

    private fun shareLogs() {
        persistFinalSummary("before-share")
        lifecycleScope.launch {
            delay(350L)
            startActivity(Intent(this@AiStudioWebSessionR16Activity, AiStudioWebSessionLogShareActivity::class.java))
        }
    }

    private fun eval(script: String, name: String) = evalRaw(script) { decoded -> log(name, safe(decoded, 12000)) }

    private fun evalRaw(script: String, callback: (String) -> Unit) {
        executor.webView.post {
            executor.webView.evaluateJavascript(script) { raw -> callback(decodeEvalValue(raw)) }
        }
    }

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { when (val v = JSONTokener(raw).nextValue()) { is String -> v; else -> v.toString() } }.getOrElse { raw }
    }

    private fun elapsedSourceMs(): Long = if (sourceStartedAt > 0L) (SystemClock.elapsedRealtime() - sourceStartedAt).coerceAtLeast(0L) else 0L
    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun safe(v: String, max: Int): String = v.replace('\u0000', ' ').replace('\n', ' ').take(max)
    private fun safeUrl(v: String?): String = runCatching { Uri.parse(v.orEmpty()).let { "${it.scheme.orEmpty()}://${it.host.orEmpty()}${it.path.orEmpty()}" } }.getOrDefault("")
    private fun log(name: String, detail: String) = labLog.event("I", name, safe(detail, 24_000))

    companion object {
        const val VERSION = "2026-09-03-web-session-r16.0-bidirectional-live"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_NEW_CHAT = "https://aistudio.google.com/prompts/new_chat"
        private const val FILE_SOURCE_LEAD_MS = 80
        private const val NOISY_INITIAL_KEEP = 3
        private const val NOISY_SAMPLE_EVERY = 100
        private const val NOISY_DETAIL_CHARS = 3_500
    }
}
