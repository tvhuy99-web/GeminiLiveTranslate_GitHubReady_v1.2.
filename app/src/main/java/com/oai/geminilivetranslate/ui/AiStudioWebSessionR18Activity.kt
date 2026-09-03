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
 * R18.3A guided language experiment.
 *
 * The page remains fully manual for the one Start action. The tester must NOT select a target
 * language in AI Studio. R18.3A applies targetLanguage=vi only in the Live Translate setup request,
 * then records whether the network setup was verified/repaired before media starts flowing.
 *
 * This activity never clicks, taps, dispatches MotionEvent, opens a language selector or invokes a
 * page UI handler. That isolation is intentional: a successful Vietnamese response proves that the
 * language can be configured at the Live setup layer rather than through AI Studio UI state.
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
        log("R183_ANDROID_MIC_PERMISSION", "granted=$granted")
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
            "R183_ACTIVITY_CREATE",
            "version=$VERSION language=${AiStudioWebSessionR18LanguageGuard.VERSION} causal=${AiStudioWebSessionR18CausalProbe.VERSION} deep=${AiStudioWebSessionR13DeepProbe.VERSION} transport=${AiStudioWebSessionLiveProbe.VERSION}",
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
        log("R183_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        val important = name.startsWith("JS_R183_") ||
            name.startsWith("JS_R18_") ||
            name.startsWith("JS_R132_") ||
            name.startsWith("JS_R13_") ||
            name.startsWith("JS_R16_") ||
            name.startsWith("R183_") ||
            name.startsWith("R18_")
        if (important) log(name, safe(detail, 46_000))
    }

    private fun installDocumentStartProbes() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R183_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }

        // R18 causal is installed first. R18.3A then wraps send() outside it, so the causal probe
        // observes the already-rewritten setup metadata while the language guard keeps its own
        // before/after hashes and never exports the request body.
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionR18CausalProbe.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionR18LanguageGuard.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionLiveProbe.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionR13DeepProbe.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        log(
            "R183_DOCUMENT_START_REGISTERED",
            "origin=$AI_STUDIO_ORIGIN language=${AiStudioWebSessionR18LanguageGuard.VERSION} causal=${AiStudioWebSessionR18CausalProbe.VERSION} transport=${AiStudioWebSessionLiveProbe.VERSION} deep=${AiStudioWebSessionR13DeepProbe.VERSION} output=${AiStudioWebSessionR16LiveOutputEngine.VERSION}",
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
                    "R183_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$androidGranted resources=${resources.size}",
                )
                runOnUiThread {
                    if (asksAudio && androidGranted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R183_WEB_PERMISSION_RESULT", "audioCapture=granted videoCapture=false")
                    } else {
                        req.deny()
                        log("R183_WEB_PERMISSION_RESULT", "denied asksAudio=$asksAudio asksVideo=$asksVideo")
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
            text = "R18.3A - ÉP TIẾNG VIỆT TỪ LIVE SETUP"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())

        controls.addView(TextView(this).apply {
            text = "Bài thử này không tự bấm giao diện. QUAN TRỌNG: không chọn Vietnamese, Tiếng Việt hay Target language trên trang AI Studio. Bấm BẮT ĐẦU GHI, sau đó chỉ tự bấm Start Live đúng một lần và nói tiếng Anh vài giây. Nếu Gemini trả lời bằng tiếng Việt, target language đã được kích hoạt từ request setup."
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }, fullWidth())

        phaseView = TextView(this).apply {
            text = "Bước hiện tại: đang mở Live Translate; không thay đổi ngôn ngữ trên trang"
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

        captureButton = actionButton("1. BẮT ĐẦU GHI R18.3A, ÉP VI TỪ SETUP") { startCapture() }
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
            text = "Chưa bắt phiên R18.3A."
            textSize = 12f
            setTextIsSelectable(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(0, dp(6), 0, dp(6))
        }
        controls.addView(detailView, fullWidth())

        root.addView(ScrollView(this).apply {
            isFillViewport = false
            addView(controls)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)))

        root.addView(
            executor.webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun startCapture() {
        if (!hasMicPermission()) requestMic.launch(Manifest.permission.RECORD_AUDIO)

        val script = """
            JSON.stringify({
              r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.configure('vi'):null,
              r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.startCapture('manual-live-start-network-vi'):null,
              r13:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.reset('r183-before-live'):null,
              r132:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.reset('r183-before-live'):null
            })
        """.trimIndent()
        executor.webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            val r18Ready = root?.isNull("r18") == false
            val languageReady = root?.optJSONObject("r183")?.optBoolean("guardInstalled") == true
            if (!r18Ready || !languageReady) {
                updatePhase("R18.3A chưa được cài đầy đủ. Mở lại trang Live rồi thử lại.")
                log("R183_CAPTURE_START_FAILED", safe(decoded, 18_000))
                return@evaluateJavascript
            }
            captureActive = true
            captureButton.isEnabled = false
            finishButton.isEnabled = true
            updatePhase("KHÔNG chọn ngôn ngữ trên trang; bây giờ chỉ tự bấm Start Live đúng 1 lần")
            detailView.text = "Guard targetLanguage=vi đã bật ở tầng request. Đang chờ request setup của gemini-3.5-live-translate-preview."
            log("R183_CAPTURE_STARTED_NATIVE", safe(decoded, 30_000))
            labLog.snapshot("r18-capture-start", decoded)
            labLog.snapshot("r18-language-state", root.optJSONObject("r183")?.toString().orEmpty())
        }
    }

    private fun finishCapture() {
        stopCapture("manual-live-complete-network-vi")
        snapshotCurrent("capture-finished")
        captureActive = false
        captureButton.isEnabled = true
        finishButton.isEnabled = false
        updatePhase("Đã chụp xong. Hãy chọn XEM / CHIA SẺ NHẬT KÝ ZIP")
    }

    private fun stopCapture(label: String) {
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.stopCapture(${JSONObject.quote(label)}):({ok:false,error:'r18-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            log("R183_CAPTURE_STOPPED_NATIVE", safe(decoded, 32_000))
            labLog.snapshot("r18-final-summary", decoded)
        }
    }

    private fun snapshotCurrent(reason: String) {
        val stateScript = """
            JSON.stringify({
              r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,
              r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.describe():null,
              r13:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.describe():null,
              r132:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.describe():null,
              r16:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null
            })
        """.trimIndent()
        executor.webView.evaluateJavascript(stateScript) { raw ->
            val decoded = decodeEvalValue(raw)
            log("R183_SNAPSHOT_NATIVE", "reason=$reason ${safe(decoded, 46_000)}")
            labLog.snapshot("r18-state-$reason", decoded)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            labLog.snapshot("r18-language-state", root?.optJSONObject("r183")?.toString().orEmpty())
            renderSnapshot(decoded)
        }
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.recent(520):({ok:false,error:'r18-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            labLog.snapshot("r18-causal-timeline", decoded)
            log("R183_CAUSAL_TIMELINE_NATIVE", safe(decoded, 46_000))
        }
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.recent(300):({ok:false,error:'r132-not-installed'}))",
        ) { raw -> labLog.snapshot("r18-r132-deep-recent", decodeEvalValue(raw)) }
    }

    private fun renderSnapshot(decoded: String) {
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return
        val r18 = root.optJSONObject("r18")
        val language = root.optJSONObject("r183")
        val counters = r18?.optJSONObject("counters")
        val trusted = counters?.optInt("trusted", 0) ?: 0
        val gum = counters?.optInt("getUserMedia", 0) ?: 0
        val open = counters?.optInt("bidiOpen", 0) ?: 0
        val send = counters?.optInt("bidiSend", 0) ?: 0
        val verified = language?.optBoolean("targetLanguageVerified") == true
        detailView.text = buildString {
            append("R18.3A: target=").append(language?.optString("targetLanguage", "?") ?: "?")
            append(" | verified=").append(verified)
            append(" | strategy=").append(language?.optString("lastStrategy", "none") ?: "none")
            append("\ntranslateSetup=").append(language?.optInt("translateSetupRequests", 0) ?: 0)
            append(" | rewriteRequests=").append(language?.optInt("rewriteRequests", 0) ?: 0)
            append(" | rewriteCount=").append(language?.optInt("rewriteCount", 0) ?: 0)
            append(" | fallbackCandidates=").append(language?.optInt("lastFallbackCandidates", 0) ?: 0)
            append("\ntrusted=").append(trusted)
            append(" | getUserMedia=").append(gum)
            append(" | BidiOpen=").append(open)
            append(" | BidiSend=").append(send)
            append("\nfirstTrustedMs=").append(r18?.optLong("firstTrustedMs", -1L) ?: -1L)
            append(" | firstGumMs=").append(r18?.optLong("firstGumMs", -1L) ?: -1L)
            append(" | firstBidiSendMs=").append(r18?.optLong("firstBidiSendMs", -1L) ?: -1L)
        }
    }

    private fun scheduleStatusPoll() {
        executor.webView.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed) return
                val script = """
                    JSON.stringify({
                      r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,
                      r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.describe():null
                    })
                """.trimIndent()
                executor.webView.evaluateJavascript(script) { raw ->
                    if (destroyed) return@evaluateJavascript
                    val decoded = decodeEvalValue(raw)
                    val root = runCatching { JSONObject(decoded) }.getOrNull()
                    val r18 = root?.optJSONObject("r18")
                    val language = root?.optJSONObject("r183")
                    if (r18?.optBoolean("ok") == true && language?.optBoolean("ok") == true) {
                        val counters = r18.optJSONObject("counters")
                        val verified = language.optBoolean("targetLanguageVerified")
                        val signature = listOf(
                            r18.optBoolean("captureActive"),
                            counters?.optInt("trusted", 0),
                            counters?.optInt("getUserMedia", 0),
                            counters?.optInt("bidiOpen", 0),
                            counters?.optInt("bidiSend", 0),
                            language.optInt("translateSetupRequests", 0),
                            language.optInt("rewriteRequests", 0),
                            verified,
                            language.optString("lastStrategy", "none"),
                        ).joinToString("|")
                        if (signature != lastStatusSignature) {
                            lastStatusSignature = signature
                            if (captureActive) {
                                renderSnapshot(decoded)
                                val send = counters?.optInt("bidiSend", 0) ?: 0
                                val gum = counters?.optInt("getUserMedia", 0) ?: 0
                                val trusted = counters?.optInt("trusted", 0) ?: 0
                                updatePhase(
                                    when {
                                        verified -> "ĐÃ XÁC MINH setup target=vi; hãy nói tiếng Anh và kiểm tra Gemini trả lời tiếng Việt"
                                        language.optInt("translateSetupRequests", 0) > 0 -> "Đã thấy setup Live Translate nhưng chưa xác minh được trường ngôn ngữ; hãy tiếp tục phiên rồi chụp log"
                                        send > 0 -> "Đã bắt Bidi; đang chờ request setup Live Translate"
                                        gum > 0 -> "Đã bắt getUserMedia; đang chờ Bidi session"
                                        trusted > 0 -> "Đã bắt thao tác Start thật; đang theo dấu setup"
                                        else -> "KHÔNG chọn ngôn ngữ; chỉ tự bấm Start Live đúng 1 lần"
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
        const val VERSION = "2026-09-03-r18.3a-guided-network-language"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val LIVE_URL = "https://aistudio.google.com/live?model=gemini-3.5-live-translate-preview"
        private const val STATUS_POLL_MS = 700L
    }
}
