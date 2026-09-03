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
 * R5 experiment: use page-local references to keydown listeners captured at document-start and
 * invoke those handler functions directly. No keyboard event is dispatched through the DOM, no
 * Run element is located/clicked, and no Android MotionEvent is created. Authentication remains
 * entirely inside the already-authenticated aistudio.google.com page.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioWebSessionR5Activity : AppCompatActivity() {
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
        lab(
            "I",
            "R5_ACTIVITY_CREATE",
            "version=$R5_VERSION probe=${AiStudioWebSessionLabScripts.VERSION} handlers=${AiStudioWebSessionR5HandlerCapture.VERSION}",
        )
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
            text = "AI Studio Web Session R5 - TRỰC TIẾP HANDLER"
            textSize = 20f
            contentDescription = "AI Studio Web Session R5 gọi trực tiếp handler JavaScript"
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "R5 không dispatch Ctrl+Enter, không MotionEvent và không click Run. Nó gọi trực tiếp keydown handler mà AI Studio đã đăng ký trong chính trang."
            textSize = 14f
        }, fullWidth())

        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 5
            contentDescription = "Prompt thử nghiệm R5"
        }
        root.addView(promptInput, fullWidth())

        root.addView(actionButton("R5. Gửi trực tiếp handler") { directHandlerSubmit() }, fullWidth())
        root.addView(actionButton("Kiểm tra handler đã bắt") { inspectHandlers() }, fullWidth())
        root.addView(actionButton("Đọc kết quả network") { readNetworkResult("manual") }, fullWidth())
        root.addView(actionButton("Mở New Chat") { webView.loadUrl(NEW_CHAT_URL) }, fullWidth())
        root.addView(actionButton("Mở Nhật ký AI Studio") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        resultView = TextView(this).apply {
            text = "Kết quả R5: chưa thử"
            textSize = 15f
            setTextIsSelectable(true)
            contentDescription = "Kết quả network R5"
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(resultView, fullWidth())

        statusView = TextView(this).apply {
            text = "Trạng thái R5: đang mở AI Studio"
            setTextIsSelectable(true)
            contentDescription = "Trạng thái R5"
        }
        root.addView(statusView, fullWidth())

        webView = WebView(this).apply {
            contentDescription = "Google AI Studio dùng để giữ phiên đăng nhập cho R5"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val logScroll = ScrollView(this)
        liveLogView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký trực tiếp R5"
        }
        logScroll.addView(liveLogView)
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(150),
        ))

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
        lab("I", "R5_DOCUMENT_START_SUPPORT", "supported=$supported")
        if (supported) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AiStudioWebSessionLabScripts.DOCUMENT_START,
                setOf("https://aistudio.google.com"),
            )
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AiStudioWebSessionR5HandlerCapture.DOCUMENT_START,
                setOf("https://aistudio.google.com"),
            )
            lab(
                "I",
                "R5_DOCUMENT_START_REGISTERED",
                "probe=${AiStudioWebSessionLabScripts.VERSION} handlers=${AiStudioWebSessionR5HandlerCapture.VERSION}",
            )
        } else {
            statusView.text = "Trạng thái R5: WebView không hỗ trợ document-start script"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                lab("I", "R5_PAGE_STARTED", "url=${safeUrl(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lab("I", "R5_PAGE_FINISHED", "url=${safeUrl(url)} title=${view?.title.orEmpty().take(250)}")
                statusView.text = "Trạng thái R5: đã tải ${view?.title.orEmpty().ifBlank { "AI Studio" }}"
                webView.postDelayed({ inspectHandlers() }, 700)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    val url = request.url.toString()
                    if (url.contains("MakerSuiteService/GenerateContent", true)) {
                        val summary = request.requestHeaders.entries.joinToString { (name, value) ->
                            "$name=<${value.length} chars>"
                        }
                        lab(
                            "I",
                            "R5_WEB_GENERATE_REQUEST",
                            "method=${request.method} gesture=${request.hasGesture()} url=${safeUrl(url)} headers={$summary}",
                        )
                    }
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    lab("W", "R5_WEB_ERROR", "code=${error?.errorCode} desc=${error?.description} url=${safeUrl(request.url.toString())}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    lab("W", "R5_HTTP_ERROR", "status=${errorResponse?.statusCode} url=${safeUrl(request.url.toString())}")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                lab("E", "R5_SSL_ERROR", "primary=${error?.primaryError} url=${safeUrl(error?.url)}")
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

            if (kind == "GENERATE_RESULT" && payload != null) {
                renderNetworkPayload(payload, "bridge")
            }
            if (kind == "R5_HANDLER_SUCCESS") {
                runOnUiThread {
                    statusView.text = "Trạng thái R5: handler trực tiếp đã làm AI Studio phát GenerateContent"
                }
            }
        }
    }

    private fun directHandlerSubmit() {
        val prompt = promptInput.text.toString()
        val marker = Regex("AIS_[A-Z0-9_.-]+").find(prompt)?.value ?: DEFAULT_MARKER
        runSeq += 1
        val seq = runSeq
        lab("I", "R5_DIRECT_START", "seq=$seq promptChars=${prompt.length} marker=$marker")
        statusView.text = "Trạng thái R5: đang gọi trực tiếp handler, không dispatch phím"
        resultView.text = "Kết quả R5: đang chờ GenerateContent..."

        val expression = "window.__AIS_R5_HANDLER_CAPTURE__ ? window.__AIS_R5_HANDLER_CAPTURE__.invokeDirect(${JSONObject.quote(prompt)},${JSONObject.quote(marker)}) : ({ok:false,error:'r5-handler-capture-not-installed'})"
        webView.evaluateJavascript("JSON.stringify($expression)") { raw ->
            val decoded = decodeEvalValue(raw)
            lab("I", "R5_DIRECT_DISPATCH_RESULT", "seq=$seq result=${decoded.take(8_000)}")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") == true) {
                val count = obj.optInt("candidateCount", -1)
                statusView.text = "Trạng thái R5: đã lập kế hoạch gọi trực tiếp $count handler, đang chờ network"
            } else {
                statusView.text = "Trạng thái R5: không khởi động được handler trực tiếp: ${obj?.optString("error") ?: decoded.take(300)}"
            }
            scheduleNetworkReads(seq)
        }
    }

    private fun inspectHandlers() {
        if (destroyed) return
        val script = "JSON.stringify(window.__AIS_R5_HANDLER_CAPTURE__ ? window.__AIS_R5_HANDLER_CAPTURE__.describe() : ({ok:false,error:'r5-handler-capture-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            lab("I", "R5_HANDLER_INSPECT", decoded.take(12_000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val active = obj?.optInt("activeCount", -1) ?: -1
            if (active >= 0) {
                statusView.text = "Trạng thái R5: đã bắt $active keydown handler trong AI Studio"
            }
        }
    }

    private fun scheduleNetworkReads(seq: Int) {
        listOf(900L, 1_800L, 3_200L, 5_000L, 7_500L).forEachIndexed { index, delay ->
            webView.postDelayed({
                if (!destroyed && seq == runSeq) readNetworkResult("auto-${index + 1}")
            }, delay)
        }
    }

    private fun readNetworkResult(source: String) {
        if (destroyed) return
        val script = AiStudioWebSessionLabScripts.call("window.__AIS_WEB_SESSION__.getLastSafeResponse()")
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            lab("D", "R5_NETWORK_READ", "source=$source result=${decoded.take(20_000)}")
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
        lab(
            "I",
            "R5_REASSEMBLED_RESULT",
            "source=$source status=$status rawMarkerFound=$rawMarkerFound markerFound=$markerFound assembledChars=${assembled.length}",
        )
        runOnUiThread {
            resultView.text = "R5 network: HTTP $status, ok=$ok, markerFound=$markerFound, phase=$phase\nModel text: ${assembled.ifBlank { "(không tách được)" }}\nRaw: ${response.take(3_000)}"
            if (markerFound) {
                statusView.text = "Trạng thái R5: THÀNH CÔNG, handler trực tiếp tạo request và nhận response"
            } else if (ok) {
                statusView.text = "Trạng thái R5: đã nhận response network, marker chưa khớp"
            }
        }
    }

    private fun extractModelText(raw: String): String {
        if (raw.isBlank()) return ""
        return MODEL_FRAGMENT_REGEX.findAll(raw)
            .mapNotNull { match -> decodeJsonStringFragment(match.groupValues[1]) }
            .joinToString(separator = "")
    }

    private fun decodeJsonStringFragment(escaped: String): String? = runCatching {
        JSONTokener("\"$escaped\"").nextValue() as? String
    }.getOrNull()

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun lab(level: String, name: String, detail: String) {
        labLog.event(level, name, detail)
        runOnUiThread {
            if (uiLog.length > 18_000) uiLog.delete(0, uiLog.length - 12_000)
            uiLog.append("[").append(level).append("][").append(name).append("] ")
                .append(detail.take(2_500)).append('\n')
            liveLogView.text = uiLog.toString()
        }
    }

    private fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val u = android.net.Uri.parse(raw)
            "${u.scheme}://${u.host}${u.path.orEmpty()}"
        }.getOrElse { raw.substringBefore('?').take(500) }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(56)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(5) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val R5_VERSION = "2026-09-02-web-session-r5"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_MARKER = "AIS_WEB_SESSION_R5_OK_20260902"
        private const val DEFAULT_PROMPT = "Reply with exactly AIS_WEB_SESSION_R5_OK_20260902 and nothing else."
        private val MODEL_FRAGMENT_REGEX = Regex("null,\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"\\]\\],\\\"model\\\"")
    }
}
