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
import org.json.JSONTokener

/**
 * R13 device lab: observe the transport AI Studio uses for Live without trying to control it yet.
 *
 * The user starts/stops AI Studio Live manually in the real WebView. The document-start probe records
 * only transport metadata and message shape information, never credentials or media payload bytes.
 */
class AiStudioWebSessionR13Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var stateView: TextView
    private lateinit var probeView: TextView
    private lateinit var micView: TextView

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::micView.isInitialized) micView.text = if (granted) {
            "Mic Android: đã cấp quyền"
        } else {
            "Mic Android: chưa được cấp quyền"
        }
        log("R13_ANDROID_MIC_PERMISSION", "granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installLiveProbe()
        installLiveWebPermissions()
        buildUi()
        log("R13_ACTIVITY_CREATE", "version=$VERSION probe=${AiStudioWebSessionLiveProbe.VERSION} executor=${AiStudioWebSessionExecutor.VERSION}")
        executor.start(AI_STUDIO_NEW_CHAT)
    }

    override fun onDestroy() {
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread {
            if (::stateView.isInitialized) stateView.text = "Web Session: $state | $detail"
        }
        log("R13_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        log(name, detail)
    }

    private fun installLiveProbe() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R13_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionLiveProbe.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        log("R13_DOCUMENT_START_REGISTERED", "origin=$AI_STUDIO_ORIGIN probe=${AiStudioWebSessionLiveProbe.VERSION}")
    }

    private fun installLiveWebPermissions() {
        executor.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val asksAudio = req.resources?.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) == true
                val androidGranted = ContextCompat.checkSelfPermission(
                    this@AiStudioWebSessionR13Activity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                log(
                    "R13_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio androidGranted=$androidGranted resources=${req.resources?.size ?: 0}",
                )
                runOnUiThread {
                    if (asksAudio && androidGranted) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R13_WEB_PERMISSION_RESULT", "audioCapture=granted")
                    } else {
                        req.deny()
                        log("R13_WEB_PERMISSION_RESULT", "audioCapture=denied")
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

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        controls.addView(TextView(this).apply {
            text = "AI STUDIO WEB SESSION R13 - LIVE TRANSPORT PROBE"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())
        controls.addView(TextView(this).apply {
            text = "Mục tiêu: mở AI Studio Live thủ công, nói khoảng 10–20 giây rồi dừng. R13 chỉ quan sát transport, chưa tự điều khiển Live."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }, fullWidth())

        stateView = TextView(this).apply {
            text = "Web Session: NEW"
            textSize = 14f
            setTextIsSelectable(true)
        }
        controls.addView(stateView, fullWidth())

        micView = TextView(this).apply {
            text = if (hasMicPermission()) "Mic Android: đã cấp quyền" else "Mic Android: chưa được cấp quyền"
            textSize = 14f
        }
        controls.addView(micView, fullWidth())

        controls.addView(actionButton("Cấp quyền microphone cho thử nghiệm Live") {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }, fullWidth())
        controls.addView(actionButton("1. Xóa probe và đánh dấu bắt đầu") {
            evalProbe("window.__AIS_LIVE_PROBE__ ? window.__AIS_LIVE_PROBE__.reset('before-live') : ({ok:false,error:'live-probe-not-installed'})", "R13_PROBE_RESET_NATIVE")
        }, fullWidth())
        controls.addView(actionButton("2. Đánh dấu ngay trước khi bật Live") {
            evalProbe("window.__AIS_LIVE_PROBE__ ? window.__AIS_LIVE_PROBE__.mark('before-live-start') : ({ok:false,error:'live-probe-not-installed'})", "R13_PROBE_MARK_NATIVE")
        }, fullWidth())
        controls.addView(actionButton("Tải lại AI Studio") {
            executor.start(AI_STUDIO_NEW_CHAT)
        }, fullWidth())
        controls.addView(actionButton("3. Chụp trạng thái sau khi dừng Live") {
            snapshotProbe()
        }, fullWidth())
        controls.addView(actionButton("Mở / chia sẻ nhật ký AI Studio") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        probeView = TextView(this).apply {
            text = "Probe: chưa chụp trạng thái"
            textSize = 12f
            setTextIsSelectable(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(0, dp(8), 0, dp(8))
        }
        controls.addView(probeView, fullWidth())

        root.addView(ScrollView(this).apply {
            isFillViewport = false
            addView(controls)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)))

        root.addView(executor.webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        setContentView(root)
    }

    private fun snapshotProbe() {
        evalProbe(
            "window.__AIS_LIVE_PROBE__ ? window.__AIS_LIVE_PROBE__.describe() : ({ok:false,error:'live-probe-not-installed'})",
            "R13_PROBE_STATE_NATIVE",
        )
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_LIVE_PROBE__ ? window.__AIS_LIVE_PROBE__.recent(160) : ({ok:false,error:'live-probe-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            log("R13_PROBE_RECENT_NATIVE", safe(decoded, 30000))
        }
    }

    private fun evalProbe(expression: String, logName: String) {
        executor.webView.evaluateJavascript("JSON.stringify($expression)") { raw ->
            val decoded = decodeEvalValue(raw)
            probeView.text = "Probe: ${safe(decoded, 9000)}"
            log(logName, safe(decoded, 12000))
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

    private fun log(name: String, detail: String) {
        labLog.event("I", name, detail)
    }

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
        const val VERSION = "2026-09-02-web-session-r13-live-transport-probe-activity"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_NEW_CHAT = "https://aistudio.google.com/prompts/new_chat"
    }
}
