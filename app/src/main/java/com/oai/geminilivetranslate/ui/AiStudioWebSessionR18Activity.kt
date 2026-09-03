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
 * R18.4 causal discovery lab.
 *
 * The native activity itself never automates AI Studio UI. It arms the R17.4-derived LAB oracle,
 * while the R18.4 document-start probe records the exact Start listener/component neighborhood and
 * correlates it with the genuine Live setup call stack. The oracle is discovery-only and must not
 * become the production bootstrap.
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
        log("R184_ANDROID_MIC_PERMISSION", "granted=$granted")
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
            "R184_ACTIVITY_CREATE",
            "version=$VERSION oracle=${AiStudioWebSessionR18StartOracle.VERSION} probe=${AiStudioWebSessionR18StartOracleProbe.VERSION} " +
                "language=${AiStudioWebSessionR18LanguageGuard.VERSION} causal=${AiStudioWebSessionR18CausalProbe.VERSION}",
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
        log("R184_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        val important = name.startsWith("JS_R184") ||
            name.startsWith("JS_R183") ||
            name.startsWith("JS_R18_") ||
            name.startsWith("JS_R132_") ||
            name.startsWith("JS_R13_") ||
            name.startsWith("JS_R16_") ||
            name.startsWith("R184_")
        if (important) log(name, safe(detail, 46_000))
    }

    private fun installDocumentStartProbes() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R184_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18StartOracleProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18StartOracle.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18CausalProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18LanguageGuard.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR18RuntimeBootstrap.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionLiveProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR13DeepProbe.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(executor.webView, AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        log(
            "R184_DOCUMENT_START_REGISTERED",
            "origin=$AI_STUDIO_ORIGIN oracle=${AiStudioWebSessionR18StartOracle.VERSION} probe=${AiStudioWebSessionR18StartOracleProbe.VERSION}",
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
                    "R184_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$androidGranted resources=${resources.size}",
                )
                runOnUiThread {
                    if (asksAudio && androidGranted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R184_WEB_PERMISSION_RESULT", "audioCapture=granted videoCapture=false")
                    } else {
                        req.deny()
                        log("R184_WEB_PERMISSION_RESULT", "denied asksAudio=$asksAudio asksVideo=$asksVideo")
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
            text = "R18.4 - ORACLE R17.4 + HỌC ĐƯỜNG START"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())

        controls.addView(TextView(this).apply {
            text = "Không chạm vào trang AI Studio. Nút thử bên dưới sẽ dùng đường R17.4 chỉ trong LAB để kích hoạt đúng Start, đồng thời R18.4 bắt listener/component/runtime bên dưới. Target tiếng Việt được ép ở setup."
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }, fullWidth())

        phaseView = TextView(this).apply {
            text = "Bước hiện tại: đang mở AI Studio; chưa chạy oracle"
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
            if (captureActive) stopCapture("reload-before-complete") { executor.start(LIVE_URL) }
            else executor.start(LIVE_URL)
        }, fullWidth())

        captureButton = actionButton("1. CHẠY ORACLE R18.4, TỰ ÉP VI + TỰ START LAB") { startOracleCapture() }
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
            text = "Chưa chạy R18.4."
            textSize = 12f
            setTextIsSelectable(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(0, dp(6), 0, dp(6))
        }
        controls.addView(detailView, fullWidth())

        root.addView(ScrollView(this).apply {
            isFillViewport = false
            addView(controls)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        root.addView(executor.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun startOracleCapture() {
        if (!hasMicPermission()) requestMic.launch(Manifest.permission.RECORD_AUDIO)
        val script = """
            JSON.stringify((function(){
              var p=window.__AIS_R184_ORACLE_PROBE__,o=window.__AIS_R184_START_ORACLE__,
                  g=window.__AIS_R183_LANGUAGE__,c=window.__AIS_R18_CAUSAL__,
                  r13=window.__AIS_LIVE_PROBE__,r132=window.__AIS_LIVE_DEEP_PROBE__,
                  b=window.__AIS_R183B_BOOTSTRAP__;
              var out={};
              out.r184p=p&&p.reset?p.reset():null;
              out.r184s=o&&o.reset?o.reset():null;
              out.r183=g&&g.configure?g.configure('vi'):null;
              out.r18=c&&c.startCapture?c.startCapture('r184-r174-oracle-network-vi'):null;
              out.r13=r13&&r13.reset?r13.reset('r184-before-oracle-start'):null;
              out.r132=r132&&r132.reset?r132.reset('r184-before-oracle-start'):null;
              out.r183b=b&&b.reset?b.reset():null;
              out.oracleStart=o&&o.start?o.start('vi'):null;
              return out;
            })())
        """.trimIndent()
        executor.webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            val probeReady = root?.optJSONObject("r184p")?.optBoolean("ok") == true
            val oracleReady = root?.optJSONObject("oracleStart")?.optBoolean("ok") == true
            val languageReady = root?.optJSONObject("r183")?.optBoolean("guardInstalled") == true
            val causalReady = root?.isNull("r18") == false
            if (!probeReady || !oracleReady || !languageReady || !causalReady) {
                updatePhase("R18.4 chưa được cài đầy đủ. Hãy mở lại trang Live rồi thử lại.")
                log("R184_CAPTURE_START_FAILED", safe(decoded, 28_000))
                return@evaluateJavascript
            }
            captureActive = true
            captureButton.isEnabled = false
            finishButton.isEnabled = true
            updatePhase("ORACLE ĐANG CHẠY. Không chạm vào trang AI Studio; chờ Start và setup tự diễn ra")
            detailView.text = "R17.4 oracle LAB đang tìm đúng Start. Probe giữ listener/component refs page-local; R18.3A đang ép targetLanguage=vi."
            log("R184_CAPTURE_STARTED_NATIVE", safe(decoded, 40_000))
            labLog.snapshot("r18-capture-start", decoded)
        }
    }

    private fun finishCapture() {
        stopCapture("r184-r174-oracle-complete-network-vi") {
            snapshotCurrent("capture-finished")
            captureActive = false
            captureButton.isEnabled = true
            finishButton.isEnabled = false
            updatePhase("Đã chụp xong R18.4. Hãy chọn XEM / CHIA SẺ NHẬT KÝ ZIP")
        }
    }

    private fun stopCapture(label: String, onDone: (() -> Unit)? = null) {
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.stopCapture(${JSONObject.quote(label)}):({ok:false,error:'r18-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            log("R184_CAPTURE_STOPPED_NATIVE", safe(decoded, 32_000))
            labLog.snapshot("r18-final-summary", decoded)
            onDone?.invoke()
        }
    }

    private fun snapshotCurrent(reason: String) {
        val stateScript = """
            JSON.stringify({
              r184p:window.__AIS_R184_ORACLE_PROBE__?window.__AIS_R184_ORACLE_PROBE__.describe():null,
              r184s:window.__AIS_R184_START_ORACLE__?window.__AIS_R184_START_ORACLE__.describe():null,
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
            log("R184_SNAPSHOT_NATIVE", "reason=$reason ${safe(decoded, 46_000)}")
            labLog.snapshot("r18-state-$reason", decoded)
            labLog.snapshot("r18-bootstrap-state", decoded)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            labLog.snapshot("r18-language-state", root?.optJSONObject("r183")?.toString().orEmpty())
            renderSnapshot(decoded)
        }
        val timeline = """
            JSON.stringify({
              causal:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.recent(420):null,
              oracle:window.__AIS_R184_ORACLE_PROBE__?window.__AIS_R184_ORACLE_PROBE__.recent(200):null
            })
        """.trimIndent()
        executor.webView.evaluateJavascript(timeline) { raw ->
            val decoded = decodeEvalValue(raw)
            labLog.snapshot("r18-causal-timeline", decoded)
            log("R184_CAUSAL_TIMELINE_NATIVE", safe(decoded, 46_000))
        }
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.recent(260):({ok:false,error:'r132-not-installed'}))",
        ) { raw -> labLog.snapshot("r18-r132-deep-recent", decodeEvalValue(raw)) }
    }

    private fun renderSnapshot(decoded: String) {
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return
        val probe = root.optJSONObject("r184p")
        val oracle = root.optJSONObject("r184s")
        val language = root.optJSONObject("r183")
        val r18 = root.optJSONObject("r18")
        val r16 = root.optJSONObject("r16")
        val counters = r18?.optJSONObject("counters")
        val setup = r16?.optInt("setupCompleteEvents", 0) ?: 0
        val verified = language?.optBoolean("targetLanguageVerified") == true
        val open = counters?.optInt("bidiOpen", 0) ?: 0
        val send = counters?.optInt("bidiSend", 0) ?: 0
        val oracleMarks = probe?.optInt("oracleMarks", 0) ?: 0
        val related = probe?.optInt("relatedListeners", 0) ?: 0
        val invoked = probe?.optInt("listenerInvocations", 0) ?: 0
        val frames = probe?.optInt("setupFrameCount", 0) ?: 0
        val listenerLinks = probe?.optInt("listenerFrameLinks", 0) ?: 0
        val graphLinks = probe?.optInt("graphFrameLinks", 0) ?: 0
        val attempts = oracle?.optInt("attempts", 0) ?: 0
        detailView.text = buildString {
            append("R18.4: oracleAttempts=").append(attempts)
            append(" | targetMarked=").append(oracleMarks)
            append(" | relatedListeners=").append(related)
            append(" | listenerInvoked=").append(invoked)
            append("\nsetupFrames=").append(frames)
            append(" | listenerLinks=").append(listenerLinks)
            append(" | graphLinks=").append(graphLinks)
            append("\nBidiOpen=").append(open).append(" | BidiSend=").append(send)
            append(" | setupComplete=").append(setup).append(" | languageVi=").append(verified)
        }
    }

    private fun scheduleStatusPoll() {
        executor.webView.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed) return
                val script = """
                    JSON.stringify({
                      r184p:window.__AIS_R184_ORACLE_PROBE__?window.__AIS_R184_ORACLE_PROBE__.describe():null,
                      r184s:window.__AIS_R184_START_ORACLE__?window.__AIS_R184_START_ORACLE__.describe():null,
                      r183:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,
                      r18:window.__AIS_R18_CAUSAL__?window.__AIS_R18_CAUSAL__.describe():null,
                      r16:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null
                    })
                """.trimIndent()
                executor.webView.evaluateJavascript(script) { raw ->
                    if (destroyed) return@evaluateJavascript
                    val decoded = decodeEvalValue(raw)
                    val root = runCatching { JSONObject(decoded) }.getOrNull()
                    val probe = root?.optJSONObject("r184p")
                    val oracle = root?.optJSONObject("r184s")
                    val language = root?.optJSONObject("r183")
                    val r18 = root?.optJSONObject("r18")
                    val r16 = root?.optJSONObject("r16")
                    if (probe?.optBoolean("ok") == true && oracle?.optBoolean("ok") == true && r18?.optBoolean("ok") == true) {
                        val setup = r16?.optInt("setupCompleteEvents", 0) ?: 0
                        val verified = language?.optBoolean("targetLanguageVerified") == true
                        val frames = probe.optInt("setupFrameCount", 0)
                        val related = probe.optInt("relatedListeners", 0)
                        val invoked = probe.optInt("listenerInvocations", 0)
                        val attempts = oracle.optInt("attempts", 0)
                        val stage = oracle.optString("stage")
                        val signature = "$stage|$attempts|$related|$invoked|$frames|$setup|$verified"
                        if (signature != lastStatusSignature) {
                            lastStatusSignature = signature
                            if (captureActive) {
                                renderSnapshot(decoded)
                                updatePhase(
                                    when {
                                        setup > 0 && verified && frames > 0 ->
                                            "THÀNH CÔNG: R17.4 oracle mở Live, target=vi và R18.4 đã bắt $frames setup frame. Hãy kết thúc và gửi ZIP"
                                        frames > 0 -> "Đã bắt $frames setup frame; đang chờ setupComplete/target vi"
                                        invoked > 0 -> "Đã thấy listener Start chạy; đang chờ Bidi setup"
                                        related > 0 -> "Đã đánh dấu Start và tìm $related listener liên quan"
                                        attempts > 0 -> "Oracle đã kích hoạt Start LAB; đang theo dõi đường gọi"
                                        else -> "Oracle đang tìm Start theo dấu vết R17.4"
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
        const val VERSION = "2026-09-03-r18.4-r174-oracle-causal-learning"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val LIVE_URL = "https://aistudio.google.com/live?model=gemini-3.5-live-translate-preview"
        private const val STATUS_POLL_MS = 700L
    }
}
