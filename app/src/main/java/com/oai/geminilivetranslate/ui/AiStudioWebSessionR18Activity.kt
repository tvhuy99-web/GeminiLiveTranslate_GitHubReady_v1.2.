package com.oai.geminilivetranslate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.oai.geminilivetranslate.audio.MicAudioSource
import com.oai.geminilivetranslate.audio.StreamingPcmPlayer
import com.oai.geminilivetranslate.core.AiStudioWebLiveClient
import com.oai.geminilivetranslate.core.AiStudioWebLiveOutputBridge
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicLong

/**
 * R19 one-trusted-tap physical handoff.
 *
 * AI Studio Live is activated by one real user tap on its own Start control. This Activity never
 * locates or activates that control. Once genuine setupComplete + Vietnamese setup are observed,
 * Android takes over realtime PCM input through R14/R15 and model output through R16. The WebView
 * remains alive as the authenticated runtime but is visually collapsed and removed from the
 * accessibility tree until the user asks to show it again.
 */
class AiStudioWebSessionR18Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var inputClient: AiStudioWebLiveClient
    private lateinit var outputBridge: AiStudioWebLiveOutputBridge

    private lateinit var stateView: TextView
    private lateinit var phaseView: TextView
    private lateinit var inputView: TextView
    private lateinit var outputView: TextView
    private lateinit var textView: TextView
    private lateinit var toggleWebButton: Button

    private var destroyed = false
    private var handoffComplete = false
    private var webHidden = false
    private var micSource: MicAudioSource? = null
    private var micJob: Job? = null
    private var translatedPlayer: StreamingPcmPlayer? = null
    private var lastPollSignature = ""

    private val micChunks = AtomicLong(0L)
    private val micBytes = AtomicLong(0L)
    private val micBackpressure = AtomicLong(0L)
    private val translatedChunks = AtomicLong(0L)
    private val translatedBytes = AtomicLong(0L)

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        log("R19_ANDROID_MIC_PERMISSION", "granted=$granted")
        if (granted && handoffComplete) {
            startMicBridgeIfReady()
        } else if (!granted) {
            updatePhase("Live đã sẵn sàng nhưng Android chưa có quyền microphone")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installDocumentStartLayers()
        installWebPermissions()

        inputClient = AiStudioWebLiveClient(executor.webView) { name, detail -> log(name, detail) }
        outputBridge = AiStudioWebLiveOutputBridge(
            executor.webView,
            object : AiStudioWebLiveOutputBridge.Listener {
                override fun onAudio(pcm24kMono: ByteArray, mimeType: String) {
                    translatedPlayer?.enqueue(pcm24kMono)
                    val chunks = translatedChunks.incrementAndGet()
                    val bytes = translatedBytes.addAndGet(pcm24kMono.size.toLong())
                    if (chunks == 1L || chunks % 25L == 0L) {
                        runOnUiThread {
                            outputView.text = "Output: chunks=$chunks bytes=$bytes mime=${safe(mimeType, 80)}"
                        }
                    }
                }

                override fun onText(kind: String, text: String) {
                    runOnUiThread {
                        textView.text = "Text [$kind]: ${text.take(1400)}"
                    }
                }

                override fun onSignal(kind: String, value: String) {
                    if (kind == "setupComplete") {
                        log("R19_OUTPUT_SETUP_SIGNAL", "valueChars=${value.length}")
                    }
                }
            },
        ) { name, detail -> log(name, detail) }

        buildUi()
        log(
            "R19_ACTIVITY_CREATE",
            "version=$VERSION carrier=${AiStudioWebSessionPhysicalCarrier.VERSION} language=${AiStudioWebSessionR18LanguageGuard.VERSION} " +
                "input=${AiStudioWebLiveClient.VERSION} r14=${AiStudioWebSessionR14DirectLiveEngine.VERSION} " +
                "output=${AiStudioWebLiveOutputBridge.VERSION} r16=${AiStudioWebSessionR16LiveOutputEngine.VERSION}",
        )
        executor.start(LIVE_URL)
        scheduleStatusPoll()
    }

    override fun onDestroy() {
        destroyed = true
        stopRealtime(clearQueue = true)
        inputClient.close()
        outputBridge.close()
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread {
            if (::stateView.isInitialized) stateView.text = "Web Session: $state | ${safe(detail, 300)}"
        }
        log("R19_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        val important = name.startsWith("JS_R19") ||
            name.startsWith("JS_R18") ||
            name.startsWith("JS_R16") ||
            name.startsWith("JS_R14") ||
            name.startsWith("R15_") ||
            name.startsWith("R16_") ||
            name.startsWith("R19_")
        if (important) log(name, safe(detail, 24_000))
    }

    private fun installDocumentStartLayers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R19_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        listOf(
            AiStudioWebSessionPhysicalCarrier.DOCUMENT_START,
            AiStudioWebSessionR18LanguageGuard.DOCUMENT_START,
            AiStudioWebSessionLiveProbe.DOCUMENT_START,
            AiStudioWebSessionR13DeepProbe.DOCUMENT_START,
            AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START,
            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,
        ).forEach { script ->
            WebViewCompat.addDocumentStartJavaScript(executor.webView, script, setOf(AI_STUDIO_ORIGIN))
        }
        log(
            "R19_DOCUMENT_START_REGISTERED",
            "carrier=${AiStudioWebSessionPhysicalCarrier.VERSION} language=${AiStudioWebSessionR18LanguageGuard.VERSION} " +
                "direct=${AiStudioWebSessionR14DirectLiveEngine.VERSION} output=${AiStudioWebSessionR16LiveOutputEngine.VERSION}",
        )
    }

    private fun installWebPermissions() {
        executor.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val resources = req.resources.orEmpty()
                val asksAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val asksVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val androidGranted = hasMicPermission()
                log(
                    "R19_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$androidGranted",
                )
                runOnUiThread {
                    if (asksAudio && androidGranted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        req.deny()
                    }
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        controls.addView(TextView(this).apply {
            text = "R19 - MỘT CHẠM START, SAU ĐÓ APP TIẾP QUẢN"
            textSize = 19f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())
        controls.addView(TextView(this).apply {
            text = "Chờ trang Live phía dưới tải xong, rồi CHẠM START TRỰC TIẾP trên AI Studio đúng một lần. App tự ép tiếng Việt. Khi setup hoàn tất, app tự thu nhỏ AI Studio, dùng microphone Android làm input và phát audio dịch qua R16."
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
        }, fullWidth())

        phaseView = TextView(this).apply {
            text = "Bước hiện tại: đang chuẩn bị runtime; chưa chạm Start"
            textSize = 16f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        stateView = TextView(this).apply { text = "Web Session: đang tải"; textSize = 12f; setTextIsSelectable(true) }
        inputView = TextView(this).apply { text = "Input: chưa tiếp quản"; textSize = 12f; setTextIsSelectable(true) }
        outputView = TextView(this).apply { text = "Output: chưa nhận"; textSize = 12f; setTextIsSelectable(true) }
        textView = TextView(this).apply { text = "Text: chưa nhận"; textSize = 12f; setTextIsSelectable(true) }
        listOf(phaseView, stateView, inputView, outputView, textView).forEach { controls.addView(it, fullWidth()) }

        controls.addView(actionButton("Cấp quyền microphone Android") {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }, fullWidth())

        toggleWebButton = actionButton("Ẩn AI Studio") {
            if (webHidden) showWebRuntime() else hideWebRuntime()
        }
        controls.addView(toggleWebButton, fullWidth())

        controls.addView(actionButton("Mở lại Live và bắt đầu lại") {
            resetForAnotherPhysicalStart()
        }, fullWidth())

        controls.addView(actionButton("Dừng microphone + input/output") {
            stopRealtime(clearQueue = true)
            updatePhase("Đã dừng realtime; AI Studio runtime vẫn còn mở")
        }, fullWidth())

        controls.addView(actionButton("Xem / chia sẻ diagnostics") {
            snapshotCurrent("manual-share")
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        root.addView(
            ScrollView(this).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)),
        )
        root.addView(
            executor.webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun scheduleStatusPoll() {
        executor.webView.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed) return
                val script = """
                    JSON.stringify((function(){
                      var c=window.__AIS_PHYSICAL_CARRIER__,g=window.__AIS_R183_LANGUAGE__,
                          d=window.__AIS_LIVE_DIRECT_ENGINE__,o=window.__AIS_LIVE_OUTPUT_ENGINE__;
                      if(c&&c.ensure)c.ensure();
                      if(g&&g.configure)g.configure('vi');
                      return {
                        carrier:c&&c.describe?c.describe():null,
                        lang:g&&g.describe?g.describe():null,
                        direct:d&&d.describe?d.describe():null,
                        output:o&&o.describe?o.describe():null
                      };
                    })())
                """.trimIndent()
                executor.webView.evaluateJavascript(script) { raw ->
                    if (destroyed) return@evaluateJavascript
                    val decoded = decodeEvalValue(raw)
                    val root = runCatching { JSONObject(decoded) }.getOrNull()
                    val carrier = root?.optJSONObject("carrier")
                    val lang = root?.optJSONObject("lang")
                    val direct = root?.optJSONObject("direct")
                    val output = root?.optJSONObject("output")
                    if (carrier?.optBoolean("ok") == true) {
                        val created = carrier.optBoolean("created")
                        val served = carrier.optInt("served", 0)
                        val setup = output?.optInt("setupCompleteEvents", 0) ?: 0
                        val verified = lang?.optBoolean("targetLanguageVerified") == true
                        val template = direct?.optBoolean("templateObserved") == true
                        val signature = "$created|$served|$setup|$verified|$template|$handoffComplete|$webHidden"
                        if (signature != lastPollSignature) {
                            lastPollSignature = signature
                            if (!handoffComplete) {
                                updatePhase(
                                    when {
                                        setup > 0 && verified -> "Đã nhận setupComplete + target vi; đang chuyển quyền cho app"
                                        served > 0 -> "Đã nhận cú Start thật; đang chờ setupComplete và xác minh tiếng Việt"
                                        created -> "Carrier im lặng đã sẵn sàng. Hãy CHẠM START trực tiếp trên AI Studio"
                                        else -> "Đang chuẩn bị carrier; chưa chạm Start"
                                    },
                                )
                            }
                        }
                        if (!handoffComplete && setup > 0 && verified) {
                            completePhysicalHandoff()
                        }
                    }
                }
                executor.webView.postDelayed(this, STATUS_POLL_MS)
            }
        }, STATUS_POLL_MS)
    }

    private fun completePhysicalHandoff() {
        if (handoffComplete) return
        handoffComplete = true
        log("R19_HANDOFF_BEGIN", "physicalStart=true targetLanguage=vi")
        ensureTranslatedPlayer()
        inputClient.arm(true)
        startMicBridgeIfReady()
        hideWebRuntime()
        updatePhase("TIẾP QUẢN XONG: AI Studio đang chạy nền; microphone Android → R14 → Live → R16 → loa")
        snapshotCurrent("handoff-complete")
    }

    private fun ensureTranslatedPlayer() {
        if (translatedPlayer != null) return
        translatedPlayer = StreamingPcmPlayer(
            sampleRate = 24_000,
            bufferBytes = 24_000,
            queueCapacity = 96,
            initialJitterChunks = 2,
            logger = null,
            diagnosticName = "PhysicalHandoffTranslated",
        ).also { it.start() }
        log("R19_TRANSLATED_PLAYER_START", "sampleRate=24000 bufferBytes=24000 queueCapacity=96")
    }

    private fun startMicBridgeIfReady() {
        if (!handoffComplete || micJob?.isActive == true || micSource != null) return
        if (!hasMicPermission()) {
            updatePhase("Live đã sẵn sàng. Cần cấp quyền microphone Android để bắt đầu dịch")
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val source = MicAudioSource(this, logger = null)
        micSource = source
        micJob = lifecycleScope.launch(Dispatchers.IO) {
            source.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    val chunks = micChunks.incrementAndGet()
                    val bytes = micBytes.addAndGet(data.size.toLong())
                    when (inputClient.sendAudio(data)) {
                        AiStudioWebLiveClient.SendResult.BACKPRESSURED -> micBackpressure.incrementAndGet()
                        AiStudioWebLiveClient.SendResult.CLOSED -> return
                        else -> Unit
                    }
                    if (chunks == 1L || chunks % 50L == 0L) {
                        val bp = micBackpressure.get()
                        runOnUiThread { inputView.text = "Input: micChunks=$chunks bytes=$bytes backpressure=$bp" }
                    }
                }

                override fun onCompleted() {
                    inputClient.sendAudioStreamEnd()
                    log("R19_MIC_COMPLETED", "chunks=${micChunks.get()} bytes=${micBytes.get()}")
                }

                override fun onError(error: Throwable) {
                    log("R19_MIC_ERROR", "type=${error.javaClass.simpleName} message=${safe(error.message.orEmpty(), 600)}")
                    runOnUiThread { updatePhase("Lỗi microphone Android: ${safe(error.message.orEmpty(), 180)}") }
                }
            })
        }
        log("R19_MIC_BRIDGE_START", "physicalHandoff=true")
    }

    private fun stopRealtime(clearQueue: Boolean) {
        runCatching { micSource?.stop() }
        micSource = null
        micJob?.cancel()
        micJob = null
        inputClient.arm(false)
        if (clearQueue) inputClient.clear()
        translatedPlayer?.stop()
        translatedPlayer = null
        log(
            "R19_REALTIME_STOP",
            "micChunks=${micChunks.get()} micBytes=${micBytes.get()} translatedChunks=${translatedChunks.get()} translatedBytes=${translatedBytes.get()}",
        )
    }

    private fun resetForAnotherPhysicalStart() {
        stopRealtime(clearQueue = true)
        handoffComplete = false
        micChunks.set(0L)
        micBytes.set(0L)
        micBackpressure.set(0L)
        translatedChunks.set(0L)
        translatedBytes.set(0L)
        lastPollSignature = ""
        showWebRuntime()
        updatePhase("Đang mở lại Live. Khi trang sẵn sàng, hãy CHẠM START trực tiếp một lần")
        executor.start(LIVE_URL)
    }

    private fun hideWebRuntime() {
        if (webHidden || !::executor.isInitialized) return
        webHidden = true
        executor.webView.alpha = 0f
        executor.webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val lp = executor.webView.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.height = dp(2)
            lp.weight = 0f
            executor.webView.layoutParams = lp
        }
        toggleWebButton.text = "Hiện AI Studio"
        log("R19_WEB_RUNTIME_COLLAPSED", "visibility=VISIBLE alpha=0 heightDp=2")
    }

    private fun showWebRuntime() {
        if (!::executor.isInitialized) return
        webHidden = false
        executor.webView.alpha = 1f
        executor.webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val lp = executor.webView.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.height = 0
            lp.weight = 1f
            executor.webView.layoutParams = lp
        }
        if (::toggleWebButton.isInitialized) toggleWebButton.text = "Ẩn AI Studio"
        log("R19_WEB_RUNTIME_EXPANDED", "visibility=VISIBLE alpha=1 weight=1")
    }

    private fun snapshotCurrent(reason: String) {
        val script = """
            JSON.stringify({
              carrier:window.__AIS_PHYSICAL_CARRIER__?window.__AIS_PHYSICAL_CARRIER__.describe():null,
              lang:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,
              direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,
              output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null
            })
        """.trimIndent()
        executor.webView.evaluateJavascript(script) { raw ->
            val page = decodeEvalValue(raw)
            val input = inputClient.stats()
            val output = outputBridge.stats()
            val summary = buildString {
                append("R19 PHYSICAL HANDOFF\n")
                append("reason=").append(reason).append('\n')
                append("version=").append(VERSION).append('\n')
                append("handoffComplete=").append(handoffComplete).append(" webCollapsed=").append(webHidden).append('\n')
                append("micChunks=").append(micChunks.get()).append(" micBytes=").append(micBytes.get()).append(" backpressure=").append(micBackpressure.get()).append('\n')
                append("inputArmed=").append(input.armed).append(" inputFramesCreated=").append(input.framesCreated).append(" inputAcceptedJs=").append(input.framesAcceptedByJs).append('\n')
                append("outputChunks=").append(output.audioChunks).append(" outputBytes=").append(output.audioBytes).append(" outputTextEvents=").append(output.textEvents).append('\n')
                append("page=").append(safe(page, 42_000))
            }
            labLog.snapshot("r19-physical-handoff-state", summary)
            labLog.snapshot("r18-bootstrap-state", page)
            log("R19_SNAPSHOT", safe(summary, 46_000))
        }
    }

    private fun updatePhase(text: String) {
        runOnUiThread {
            if (::phaseView.isInitialized) phaseView.text = "Bước hiện tại: $text"
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun log(name: String, detail: String) = labLog.event("I", name, detail)

    private fun safeUrl(raw: String?): String {
        val uri = runCatching { android.net.Uri.parse(raw.orEmpty()) }.getOrNull()
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

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(4) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VERSION = "2026-09-04-r19-one-trusted-tap-physical-handoff"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val LIVE_URL = "https://aistudio.google.com/live?model=gemini-3.5-live-translate-preview"
        private const val STATUS_POLL_MS = 600L
    }
}
