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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R18.3C causal runtime-learning lab.
 *
 * R18.3B proved that blind page-wide scanning cannot see the private Live bootstrap service before
 * the session starts. R18.3C therefore performs one controlled discovery run: the tester starts
 * capture in the native app, presses the real AI Studio Start control exactly once, and the existing
 * page-local structured XHR observer captures non-UI V8 call-frame handles at the genuine Live setup
 * request. No synthetic click, selector, coordinate, MotionEvent or handler replay is used.
 *
 * The learned function/receiver references remain page-local. Diagnostics export only structural
 * metadata, hashes, argument types, receiver types and bundle line/column information.
 */
class AiStudioWebSessionR18Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var statusView: TextView
    private lateinit var phaseView: TextView
    private lateinit var detailView: TextView
    private lateinit var captureButton: Button
    private lateinit var finishButton: Button

    private var destroyed = false
    private var captureActive = false
    private var lastStatusSignature = ""

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        log("R183C_ANDROID_MIC_PERMISSION", "granted=$granted")
        updatePhase(if (granted) "Microphone Android đã sẵn sàng" else "Chưa cấp quyền microphone Android")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installDocumentStartProbes()
        installWebPermissions()
        buildUi()
        log(
            "R183C_ACTIVITY_CREATE",
            "version=$VERSION bootstrap=${AiStudioWebSessionR18RuntimeBootstrap.VERSION} language=${AiStudioWebSessionR18LanguageGuard.VERSION} causal=${AiStudioWebSessionR18CausalProbe.VERSION}",
        )
        executor.start(LIVE_URL)
        scheduleStatusPoll()
    }

    override fun onDestroy() {
        destroyed = true
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread {
            if (::statusView.isInitialized) statusView.text = "Web Session: $state | ${safe(detail, 300)}"
        }
        log("R183C_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        val important = name.startsWith("JS_R183B_") ||
            name.startsWith("JS_R183_") ||
            name.startsWith("JS_R18_") ||
            name.startsWith("JS_R132_") ||
            name.startsWith("JS_R13_") ||
            name.startsWith("JS_R16_") ||
            name.startsWith("R183C_") ||
            name.startsWith("R183B_") ||
            name.startsWith("R183_") ||
            name.startsWith("R18_")
        if (important) log(name, safe(detail, 46_000))
    }

    private fun installDocumentStartProbes() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R183C_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18CausalProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18LanguageGuard.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18RuntimeBootstrap.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionLiveProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR13DeepProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        log(
            "R183C_DOCUMENT_START_REGISTERED",
            "origin=$AI_STUDIO_ORIGIN bootstrap=${AiStudioWebSessionR18RuntimeBootstrap.VERSION} language=${AiStudioWebSessionR18LanguageGuard.VERSION} causal=${AiStudioWebSessionR18CausalProbe.VERSION} output=${AiStudioWebSessionR16LiveOutputEngine.VERSION}",
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
                    "R183C_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$androidGranted resources=${resources.size}",
                )
                runOnUiThread {
                    if (asksAudio && androidGranted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R183C_WEB_PERMISSION_RESULT", "audioCapture=granted videoCapture=false")
                    } else {
                        req.deny()
                        log("R183C_WEB_PERMISSION_RESULT", "denied asksAudio=$asksAudio asksVideo=$asksVideo")
                        if (asksAudio && !androidGranted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
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
            text = "R18.3C - HỌC RUNTIME LIVE THẬT"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())

        controls.addView(TextView(this).apply {
            text = "Bài thử này chỉ dùng một lần bấm Start thủ công để học đường gọi thật. Không chọn ngôn ngữ. Sau khi nhấn BẮT ĐẦU HỌC bên dưới, hãy bấm Start Live trên trang đúng một lần, nói vài giây, rồi nhấn KẾT THÚC."
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }, fullWidth())

        phaseView = TextView(this).apply {
            text = "Bước hiện tại: đang mở AI Studio; chưa bắt đầu học"
            textSize = 16f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        controls.addView(phaseView, fullWidth())

        statusView = TextView(this).apply {
            text = "Web Session: đang tải"
            textSize = 13f
            setTextIsSelectable(true)
        }
        controls.addView(statusView, fullWidth())

        controls.addView(actionButton("Cấp quyền microphone") {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }, fullWidth())

        controls.addView(actionButton("Mở lại trang Live Translate") {
            if (captureActive) stopCapture("reload-before-complete")
            executor.start(LIVE_URL)
        }, fullWidth())

        captureButton = actionButton("1. BẮT ĐẦU HỌC R18.3C, TỰ ÉP TARGET VI") { startLearningCapture() }
        controls.addView(captureButton, fullWidth())

        finishButton = actionButton("2. KẾT THÚC + CHỤP TOÀN BỘ") { finishCapture() }.apply {
            isEnabled = false
        }
        controls.addView(finishButton, fullWidth())

        controls.addView(actionButton("XEM / CHIA SẺ NHẬT KÝ ZIP") {
            snapshotCurrent("manual-open-log")
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        detailView = TextView(this).apply {
            text = "Chưa chạy R18.3C."
            textSize = 12f
            setTextIsSelectable(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(0, dp(6), 0, dp(6))
        }
        controls.addView(detailView, fullWidth())

        root.addView(ScrollView(this).apply {
            isFillViewport = false
            addView(controls)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(410)))
        root.addView(executor.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun startLearningCapture() {
        if (!hasMicPermission()) requestMic.launch(Manifest.permission.RECORD_AUDIO)
        val script = """
            JSON.stringify({
              r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.configure('vi'):null,
              r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.startCapture('manual-learn-runtime-network-vi'):null,
              r13:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.reset('r183c-before-manual-start'):null,
              r132:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.reset('r183c-before-manual-start'):null,
              r183b:window.__AIS_R183B_BOOTSTRAP__?window.__AIS_R183B_BOOTSTRAP__.reset():null
            })
        """.trimIndent()
        executor.webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            val causalReady = root?.isNull("r18") == false
            val languageReady = root?.optJSONObject("r183")?.optBoolean("guardInstalled") == true
            val bootstrapReady = root?.optJSONObject("r183b")?.optBoolean("ok") == true
            if (!causalReady || !languageReady || !bootstrapReady) {
                updatePhase("R18.3C chưa được cài đầy đủ. Hãy mở lại trang Live rồi thử lại.")
                log("R183C_CAPTURE_START_FAILED", safe(decoded, 20_000))
                return@evaluateJavascript
            }
            captureActive = true
            captureButton.isEnabled = false
            finishButton.isEnabled = true
            updatePhase("ĐANG HỌC. Bây giờ bấm Start Live trên trang đúng MỘT LẦN; không chọn ngôn ngữ")
            detailView.text = "targetLanguage=vi đã bật ở tầng setup. Structured observer đang chờ request /v1/bidiGenerateContent để giữ các frame runtime không-UI."
            log("R183C_CAPTURE_STARTED_NATIVE", safe(decoded, 32_000))
            labLog.snapshot("r18-capture-start", decoded)
            labLog.snapshot("r18-language-state", root?.optJSONObject("r183")?.toString().orEmpty())
            labLog.snapshot("r18-bootstrap-state", root?.optJSONObject("r183b")?.toString().orEmpty())
        }
    }

    private fun finishCapture() {
        stopCapture("manual-learn-runtime-complete-network-vi")
        snapshotCurrent("capture-finished")
        captureActive = false
        captureButton.isEnabled = true
        finishButton.isEnabled = false
        updatePhase("Đã chụp xong R18.3C. Hãy chọn XEM / CHIA SẺ NHẬT KÝ ZIP")
    }

    private fun stopCapture(label: String) {
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.stopCapture(${JSONObject.quote(label)}):({ok:false,error:'r18-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            log("R183C_CAPTURE_STOPPED_NATIVE", safe(decoded, 32_000))
            labLog.snapshot("r18-final-summary", decoded)
        }
    }

    private fun snapshotCurrent(reason: String) {
        val stateScript = """
            JSON.stringify({
              r183b:window.__AIS_R183B_BOOTSTRAP__?window.__AIS_R183B_BOOTSTRAP__.describe():null,
              r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,
              r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.describe():null,
              r13:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.describe():null,
              r132:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.describe():null,
              r16:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null
            })
        """.trimIndent()
        executor.webView.evaluateJavascript(stateScript) { raw ->
            val decoded = decodeEvalValue(raw)
            log("R183C_SNAPSHOT_NATIVE", "reason=$reason ${safe(decoded, 46_000)}")
            labLog.snapshot("r18-state-$reason", decoded)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            labLog.snapshot("r18-bootstrap-state", root?.optJSONObject("r183b")?.toString().orEmpty())
            labLog.snapshot("r18-language-state", root?.optJSONObject("r183")?.toString().orEmpty())
            renderSnapshot(decoded)
        }
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.recent(520):({ok:false,error:'r18-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            labLog.snapshot("r18-causal-timeline", decoded)
            log("R183C_CAUSAL_TIMELINE_NATIVE", safe(decoded, 46_000))
        }
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.recent(300):({ok:false,error:'r132-not-installed'}))",
        ) { raw -> labLog.snapshot("r18-r132-deep-recent", decodeEvalValue(raw)) }
    }

    private fun renderSnapshot(decoded: String) {
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return
        val bootstrap = root.optJSONObject("r183b")
        val language = root.optJSONObject("r183")
        val r18 = root.optJSONObject("r18")
        val r16 = root.optJSONObject("r16")
        val counters = r18?.optJSONObject("counters")
        val trusted = counters?.optInt("trusted", 0) ?: 0
        val open = counters?.optInt("bidiOpen", 0) ?: 0
        val send = counters?.optInt("bidiSend", 0) ?: 0
        val setup = r16?.optInt("setupCompleteEvents", 0) ?: 0
        val verified = language?.optBoolean("targetLanguageVerified") == true
        val learned = bootstrap?.optInt("learnedCount", 0) ?: 0
        val observations = bootstrap?.optInt("setupObservations", 0) ?: 0
        detailView.text = buildString {
            append("R18.3C: learnedFrames=").append(learned)
            append(" | setupObservations=").append(observations)
            append(" | setupComplete=").append(setup)
            append(" | languageVi=").append(verified)
            append("\nBidiOpen=").append(open)
            append(" | BidiSend=").append(send)
            append(" | trustedWebEvents=").append(trusted)
            append("\nKhông có replay tự động trong R18.3C; đây là lượt học runtime thật.")
        }
    }

    private fun scheduleStatusPoll() {
        executor.webView.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed) return
                val script = """
                    JSON.stringify({
                      r183b:window.__AIS_R183B_BOOTSTRAP__?window.__AIS_R183B_BOOTSTRAP__.describe():null,
                      r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,
                      r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.describe():null,
                      r16:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null
                    })
                """.trimIndent()
                executor.webView.evaluateJavascript(script) { raw ->
                    if (destroyed) return@evaluateJavascript
                    val decoded = decodeEvalValue(raw)
                    val root = runCatching { JSONObject(decoded) }.getOrNull()
                    val bootstrap = root?.optJSONObject("r183b")
                    val language = root?.optJSONObject("r183")
                    val r18 = root?.optJSONObject("r18")
                    val r16 = root?.optJSONObject("r16")
                    if (bootstrap?.optBoolean("ok") == true && r18?.optBoolean("ok") == true) {
                        val counters = r18.optJSONObject("counters")
                        val learned = bootstrap.optInt("learnedCount", 0)
                        val observations = bootstrap.optInt("setupObservations", 0)
                        val verified = language?.optBoolean("targetLanguageVerified") == true
                        val setup = r16?.optInt("setupCompleteEvents", 0) ?: 0
                        val signature = listOf(
                            r18.optBoolean("captureActive"), learned, observations,
                            counters?.optInt("bidiSend", 0), setup, verified,
                        ).joinToString("|")
                        if (signature != lastStatusSignature) {
                            lastStatusSignature = signature
                            if (captureActive) {
                                renderSnapshot(decoded)
                                updatePhase(
                                    when {
                                        learned > 0 && setup > 0 && verified -> "ĐÃ HỌC ĐƯỢC $learned runtime frame, setupComplete và target=vi đều xác minh. Hãy kết thúc và gửi ZIP"
                                        learned > 0 -> "Đã bắt được $learned runtime frame không-UI; đang chờ setupComplete"
                                        (counters?.optInt("bidiSend", 0) ?: 0) > 0 -> "Bidi đã chạy; structured observer đang thu runtime frame"
                                        else -> "Đang chờ bạn bấm Start Live thủ công đúng một lần"
                                    },
                                )
                            }
                        }
                    }
                }
                executor.webView.postDelayed(this, STATUS_POLL_MS)
            }
        }, STATUS_POLL_MS)
    }

    private fun updatePhase(text: String) {
        runOnUiThread { if (::phaseView.isInitialized) phaseView.text = "Bước hiện tại: $text" }
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
        const val VERSION = "2026-09-03-r18.3c-manual-causal-runtime-learning"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val LIVE_URL = "https://aistudio.google.com/live?model=gemini-3.5-live-translate-preview"
        private const val STATUS_POLL_MS = 700L
    }
}
