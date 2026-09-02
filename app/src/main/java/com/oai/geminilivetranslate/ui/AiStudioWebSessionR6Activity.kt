package com.oai.geminilivetranslate.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R6 experiment: directly invoke AI Studio input/change handlers to sync prompt state, then invoke
 * its proven keydown handler directly. No DOM event dispatch, Run click, Android MotionEvent,
 * credential export, or authenticated request replay.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioWebSessionR6Activity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var liveLogView: TextView
    private lateinit var labLog: AiStudioWebSessionLabLog
    private val uiLog = StringBuilder()
    private var destroyed = false
    private var runSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        buildUi()
        configureWebView()
        lab("I", "R6_ACTIVITY_CREATE", "version=$R6_VERSION probe=${AiStudioWebSessionLabScripts.VERSION} handlers=${AiStudioWebSessionR6HandlerCapture.VERSION}")
        webView.loadUrl(NEW_CHAT_URL)
    }

    override fun onDestroy() {
        destroyed = true
        if (::webView.isInitialized) runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "AI Studio Web Session R6 - TRỰC TIẾP PROMPT STATE"
            textSize = 20f
            contentDescription = "AI Studio Web Session R6 gọi trực tiếp prompt state handlers"
        }, fullWidth())
        root.addView(TextView(this).apply {
            text = "R6 không dispatch input/change hay Ctrl+Enter qua DOM. Nó gọi trực tiếp input/change handler rồi keydown handler mà AI Studio đã đăng ký."
            textSize = 14f
        }, fullWidth())
        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 5
            contentDescription = "Prompt thử nghiệm R6"
        }
        root.addView(promptInput, fullWidth())
        root.addView(actionButton("R6. Gửi trực tiếp prompt state") { directSubmit() }, fullWidth())
        root.addView(actionButton("Kiểm tra handler R6") { inspectHandlers() }, fullWidth())
        root.addView(actionButton("Đọc kết quả network") { readNetworkResult("manual") }, fullWidth())
        root.addView(actionButton("Mở New Chat") { webView.loadUrl(NEW_CHAT_URL) }, fullWidth())
        root.addView(actionButton("Mở Nhật ký AI Studio") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        resultView = TextView(this).apply {
            text = "Kết quả R6: chưa thử"
            textSize = 15f
            setTextIsSelectable(true)
            contentDescription = "Kết quả network R6"
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(resultView, fullWidth())
        statusView = TextView(this).apply {
            text = "Trạng thái R6: đang mở AI Studio"
            setTextIsSelectable(true)
            contentDescription = "Trạng thái R6"
        }
        root.addView(statusView, fullWidth())

        webView = WebView(this).apply {
            contentDescription = "Google AI Studio giữ phiên đăng nhập cho R6"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val logScroll = ScrollView(this)
        liveLogView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký trực tiếp R6"
        }
        logScroll.addView(liveLogView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        setContentView(root)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(JsBridge(), JS_BRIDGE_NAME)
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        lab("I", "R6_DOCUMENT_START_SUPPORT", "supported=$supported")
        if (supported) {
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionLabScripts.DOCUMENT_START, setOf("https://aistudio.google.com"))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR6HandlerCapture.DOCUMENT_START, setOf("https://aistudio.google.com"))
            lab("I", "R6_DOCUMENT_START_REGISTERED", "probe=${AiStudioWebSessionLabScripts.VERSION} handlers=${AiStudioWebSessionR6HandlerCapture.VERSION}")
        } else {
            statusView.text = "Trạng thái R6: WebView không hỗ trợ document-start script"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                lab("I", "R6_PAGE_STARTED", "url=${safeUrl(url)}")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                lab("I", "R6_PAGE_FINISHED", "url=${safeUrl(url)} title=${view?.title.orEmpty().take(250)}")
                statusView.text = "Trạng thái R6: đã tải ${view?.title.orEmpty().ifBlank { "AI Studio" }}"
                webView.postDelayed({ inspectHandlers() }, 700)
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    val url = request.url.toString()
                    if (url.contains("MakerSuiteService/GenerateContent", true)) {
                        val summary = request.requestHeaders.entries.joinToString { (name, value) -> "$name=<${value.length} chars>" }
                        lab("I", "R6_WEB_GENERATE_REQUEST", "method=${request.method} gesture=${request.hasGesture()} url=${safeUrl(url)} headers={$summary}")
                    }
                }
                return null
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) lab("W", "R6_WEB_ERROR", "code=${error?.errorCode} desc=${error?.description} url=${safeUrl(request.url.toString())}")
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) lab("W", "R6_HTTP_ERROR", "status=${errorResponse?.statusCode} url=${safeUrl(request.url.toString())}")
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                lab("E", "R6_SSL_ERROR", "primary=${error?.primaryError} url=${safeUrl(error?.url)}")
                handler?.cancel()
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onJsEvent(json: String) {
            val parsed = runCatching { JSONObject(json) }.getOrNull()
            val kind = parsed?.optString("kind", "JS_EVENT") ?: "JS_EVENT"
            val payload = parsed?.optJSONObject("payload")
            lab(if (kind.contains("ERROR") || kind.contains("FAIL")) "W" else "I", "JS_$kind", json.take(24_000))
            if (kind == "GENERATE_RESULT" && payload != null) renderNetworkPayload(payload, "bridge")
            if (kind == "R6_HANDLER_SUCCESS") runOnUiThread {
                statusView.text = "Trạng thái R6: input state + keydown handler trực tiếp đã tạo GenerateContent"
            }
        }
    }

    private fun directSubmit() {
        val prompt = promptInput.text.toString()
        val marker = Regex("AIS_[A-Z0-9_.-]+").find(prompt)?.value ?: DEFAULT_MARKER
        runSeq += 1
        val seq = runSeq
        lab("I", "R6_DIRECT_START", "seq=$seq promptChars=${prompt.length} marker=$marker")
        statusView.text = "Trạng thái R6: đang gọi trực tiếp input/change + keydown handler"
        resultView.text = "Kết quả R6: đang chờ GenerateContent..."
        val expression = "window.__AIS_R6_HANDLER_CAPTURE__ ? window.__AIS_R6_HANDLER_CAPTURE__.invokeDirect(${JSONObject.quote(prompt)},${JSONObject.quote(marker)}) : ({ok:false,error:'r6-handler-capture-not-installed'})"
        webView.evaluateJavascript("JSON.stringify($expression)") { raw ->
            val decoded = decodeEvalValue(raw)
            lab("I", "R6_DIRECT_DISPATCH_RESULT", "seq=$seq result=${decoded.take(8_000)}")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") == true) {
                statusView.text = "Trạng thái R6: input=${obj.optInt("inputCandidateCount", -1)}, keydown=${obj.optInt("keyCandidateCount", -1)}, đang chờ network"
            } else {
                statusView.text = "Trạng thái R6: không khởi động được: ${obj?.optString("error") ?: decoded.take(300)}"
            }
            scheduleNetworkReads(seq)
        }
    }

    private fun inspectHandlers() {
        if (destroyed) return
        val script = "JSON.stringify(window.__AIS_R6_HANDLER_CAPTURE__ ? window.__AIS_R6_HANDLER_CAPTURE__.describe() : ({ok:false,error:'r6-handler-capture-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            lab("I", "R6_HANDLER_INSPECT", decoded.take(12_000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val counts = obj?.optJSONObject("counts")
            if (counts != null) statusView.text = "Trạng thái R6: handlers input=${counts.optInt("input")}, change=${counts.optInt("change")}, keydown=${counts.optInt("keydown")}" 
        }
    }

    private fun scheduleNetworkReads(seq: Int) {
        listOf(900L, 1_800L, 3_200L, 5_000L, 7_500L).forEachIndexed { index, delay ->
            webView.postDelayed({ if (!destroyed && seq == runSeq) readNetworkResult("auto-${index + 1}") }, delay)
        }
    }

    private fun readNetworkResult(source: String) {
        if (destroyed) return
        val script = AiStudioWebSessionLabScripts.call("window.__AIS_WEB_SESSION__.getLastSafeResponse()")
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            lab("D", "R6_NETWORK_READ", "source=$source result=${decoded.take(20_000)}")
            val outer = runCatching { JSONObject(decoded) }.getOrNull()
            val value = outer?.optJSONObject("value")
            if (value != null) renderNetworkPayload(value, source)
        }
    }

    private fun renderNetworkPayload(value: JSONObject, source: String) {
        val ok = value.optBoolean("ok")
        val status = value.optInt("status", -1)
        val rawMarkerFound = value.optBoolean("markerFound")
        val marker = value.optString("marker")
        val phase = value.optString("phase")
        val response = value.optString("responseText")
        val assembled = extractModelText(response)
        val markerFound = rawMarkerFound || (marker.isNotBlank() && assembled.contains(marker))
        lab("I", "R6_REASSEMBLED_RESULT", "source=$source status=$status rawMarkerFound=$rawMarkerFound markerFound=$markerFound assembledChars=${assembled.length}")
        runOnUiThread {
            resultView.text = "R6 network: HTTP $status, ok=$ok, markerFound=$markerFound, phase=$phase\nModel text: ${assembled.ifBlank { "(không tách được)" }}\nRaw: ${response.take(3_000)}"
            if (markerFound) statusView.text = "Trạng thái R6: THÀNH CÔNG, prompt state và gửi đều qua handler trực tiếp"
            else if (ok) statusView.text = "Trạng thái R6: đã nhận response network, marker chưa khớp"
        }
    }

    private fun extractModelText(raw: String): String {
        if (raw.isBlank()) return ""
        return MODEL_FRAGMENT_REGEX.findAll(raw).mapNotNull { decodeJsonStringFragment(it.groupValues[1]) }.joinToString("")
    }
    private fun decodeJsonStringFragment(escaped: String): String? = runCatching { JSONTokener("\"$escaped\"").nextValue() as? String }.getOrNull()
    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { when (val first = JSONTokener(raw).nextValue()) { is String -> first else -> first.toString() } }.getOrElse { raw }
    }
    private fun lab(level: String, name: String, detail: String) {
        labLog.event(level, name, detail)
        runOnUiThread {
            if (uiLog.length > 18_000) uiLog.delete(0, uiLog.length - 12_000)
            uiLog.append("[").append(level).append("][").append(name).append("] ").append(detail.take(2_500)).append('\n')
            liveLogView.text = uiLog.toString()
        }
    }
    private fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching { val u = android.net.Uri.parse(raw); "${u.scheme}://${u.host}${u.path.orEmpty()}" }.getOrElse { raw.substringBefore('?').take(500) }
    }
    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(56); contentDescription = label; setOnClickListener { action() }
    }
    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val R6_VERSION = "2026-09-02-web-session-r6"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_MARKER = "AIS_WEB_SESSION_R6_OK_20260902"
        private const val DEFAULT_PROMPT = "Reply with exactly AIS_WEB_SESSION_R6_OK_20260902 and nothing else."
        private val MODEL_FRAGMENT_REGEX = Regex("null,\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"\\]\\],\\\"model\\\"")
    }
}
