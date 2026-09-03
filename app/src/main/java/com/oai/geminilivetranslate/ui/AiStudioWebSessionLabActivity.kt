package com.oai.geminilivetranslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated experiment for using the authenticated aistudio.google.com web session itself.
 *
 * R1 deliberately does NOT capture or export auth/header values. It injects a network probe at
 * document-start, triggers one trusted WebView touch so AI Studio itself constructs the request,
 * then reads the GenerateContent result directly from the network layer rather than the DOM.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioWebSessionLabActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var liveLogView: TextView
    private lateinit var labLog: AiStudioWebSessionLabLog

    private val uiLog = StringBuilder()
    private val popupDialogs = ConcurrentHashMap<WebView, Dialog>()
    private var destroyed = false
    private var evalSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        WebView.setWebContentsDebuggingEnabled(true)
        buildUi()
        configureMainWebView()
        lab("I", "ACTIVITY_CREATE", "webView=${webViewPackage()} accessibility=${accessibilitySummary()}")
        webView.loadUrl(NEW_CHAT_URL)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        lab("I", "ACTIVITY_RESUME", "url=${safeUrl(webView.url)} accessibility=${accessibilitySummary()}")
    }

    override fun onPause() {
        lab("I", "ACTIVITY_PAUSE", "url=${safeUrl(webView.url)}")
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        popupDialogs.keys.toList().forEach { popup ->
            runCatching { popup.stopLoading(); popup.destroy() }
            popupDialogs.remove(popup)?.dismiss()
        }
        if (::webView.isInitialized) runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Experimental WebView navigation")
    override fun onBackPressed() {
        when {
            popupDialogs.isNotEmpty() -> popupDialogs.entries.lastOrNull()?.let { (popup, dialog) ->
                popupDialogs.remove(popup)
                runCatching { popup.destroy() }
                dialog.dismiss()
            }
            ::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        root.addView(TextView(this).apply {
            text = "AI Studio Web Session Executor Lab"
            textSize = 19f
            contentDescription = "Phòng thử nghiệm dùng trực tiếp phiên web Google AI Studio"
        }, fullWidth())

        val controlsScroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlsScroll.addView(controls)

        controls.addView(TextView(this).apply {
            text = "R1: AI Studio tự tạo request bằng phiên đăng nhập thật. Lab chỉ lấy kết quả từ tầng mạng, không đọc DOM response và không ghi token/key."
        }, fullWidth())

        controls.addView(horizontalButtons(
            actionButton("Mở New Chat") { webView.loadUrl(NEW_CHAT_URL) },
            actionButton("Mở Home") { webView.loadUrl(HOME_URL) },
            actionButton("Reload") { webView.reload() },
            actionButton("Kiểm tra probe") { inspectProbe() },
        ))

        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 4
            hint = "Prompt thử nghiệm"
            contentDescription = "Prompt để AI Studio gửi bằng phiên web thật"
        }
        controls.addView(promptInput, fullWidth())

        controls.addView(horizontalButtons(
            actionButton("1. Gửi thật + bắt network") { trustedSendAndCapture() },
            actionButton("2. Đọc kết quả network") { readLastNetworkResult() },
            actionButton("3. Xem call stack") { inspectProbe() },
            actionButton("Chia sẻ log ZIP") { shareBundle() },
        ))

        resultView = TextView(this).apply {
            text = "Kết quả: chưa thử"
            textSize = 15f
            setTextIsSelectable(true)
            contentDescription = "Kết quả GenerateContent lấy trực tiếp từ network"
        }
        controls.addView(resultView, fullWidth())

        statusView = TextView(this).apply {
            text = "Trạng thái: đang mở AI Studio"
            setTextIsSelectable(true)
            contentDescription = "Trạng thái Web Session Lab"
        }
        controls.addView(statusView, fullWidth())

        root.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(285)))

        webView = WebView(this).apply {
            contentDescription = "Google AI Studio, dùng để duy trì phiên đăng nhập web"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val logScroll = ScrollView(this)
        liveLogView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký Web Session Lab"
        }
        logScroll.addView(liveLogView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(145)))
        setContentView(root)
    }

    private fun configureMainWebView() {
        configureSettings(webView)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(JsBridge(), JS_BRIDGE_NAME)

        val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        lab("I", "DOCUMENT_START_SUPPORT", "supported=$documentStartSupported")
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AiStudioWebSessionLabScripts.DOCUMENT_START,
                setOf("https://aistudio.google.com"),
            )
            lab("I", "DOCUMENT_START_REGISTERED", "version=${AiStudioWebSessionLabScripts.VERSION}")
        } else {
            lab("E", "DOCUMENT_START_UNSUPPORTED", "Current WebView cannot guarantee pre-bootstrap injection")
            status("WebView không hỗ trợ DOCUMENT_START_SCRIPT. Cần cập nhật Android System WebView.")
        }

        webView.webViewClient = createWebViewClient(isPopup = false)
        webView.webChromeClient = createChromeClient(isPopup = false)
    }

    private fun configureSettings(target: WebView) {
        target.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
        }
    }

    private fun createWebViewClient(isPopup: Boolean): WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            lab("I", "PAGE_STARTED", "popup=$isPopup url=${safeUrl(url)}")
            if (!isPopup) status("Đang tải ${safeUrl(url)}")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            lab("I", "PAGE_FINISHED", "popup=$isPopup url=${safeUrl(url)} title=${view?.title.orEmpty().take(300)}")
            if (!isPopup) {
                status("Đã tải ${view?.title.orEmpty().ifBlank { safeUrl(url) }}")
                webView.postDelayed({ inspectProbe() }, 500)
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request != null) {
                val url = request.url.toString()
                if (url.contains("MakerSuiteService/GenerateContent", true) || url.contains("MakerSuiteService/BidiGenerateContent", true)) {
                    val headers = request.requestHeaders.entries.joinToString { (name, value) -> "$name=<${value.length} chars>" }
                    lab("I", "WEB_GENERATE_REQUEST", "popup=$isPopup method=${request.method} gesture=${request.hasGesture()} url=${safeUrl(url)} headers={$headers}")
                }
            }
            return null
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            lab("W", "WEB_ERROR", "popup=$isPopup main=${request?.isForMainFrame} code=${error?.errorCode} desc=${error?.description} url=${safeUrl(request?.url?.toString())}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            lab("W", "HTTP_ERROR", "popup=$isPopup main=${request?.isForMainFrame} status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${safeUrl(request?.url?.toString())}")
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            lab("E", "SSL_ERROR", "popup=$isPopup primary=${error?.primaryError} url=${safeUrl(error?.url)}")
            handler?.cancel()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            lab("E", "RENDER_GONE", "popup=$isPopup crashed=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}")
            if (isPopup && view != null) popupDialogs.remove(view)?.dismiss()
            return true
        }
    }

    private fun createChromeClient(isPopup: Boolean): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null && consoleMessage.messageLevel() != ConsoleMessage.MessageLevel.LOG) {
                lab("D", "JS_CONSOLE", "popup=$isPopup level=${consoleMessage.messageLevel()} source=${safeUrl(consoleMessage.sourceId())}:${consoleMessage.lineNumber()} msg=${consoleMessage.message().take(4000)}")
            }
            return false
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (resultMsg == null) return false
            val dialog = Dialog(this@AiStudioWebSessionLabActivity)
            val popup = WebView(this@AiStudioWebSessionLabActivity)
            configureSettings(popup)
            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
            popup.webViewClient = createWebViewClient(isPopup = true)
            popup.webChromeClient = createChromeClient(isPopup = true)
            dialog.setContentView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.setOnDismissListener {
                popupDialogs.remove(popup)
                runCatching { popup.destroy() }
            }
            popupDialogs[popup] = dialog
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            dialog.show()
            lab("I", "POPUP_CREATE", "gesture=$isUserGesture dialog=$isDialog")
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            if (window != null) {
                popupDialogs.remove(window)?.dismiss()
                runCatching { window.destroy() }
            }
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onJsEvent(json: String) {
            val parsed = runCatching { JSONObject(json) }.getOrNull()
            val kind = parsed?.optString("kind", "JS_EVENT") ?: "JS_EVENT"
            val payload = parsed?.optJSONObject("payload")
            lab(if (kind.contains("ERROR") || kind.contains("FAIL")) "W" else "I", "JS_$kind", json.take(24_000))
            if (kind == "GENERATE_RESULT" && payload != null) {
                val statusCode = payload.optInt("status", -1)
                val ok = payload.optBoolean("ok")
                val markerFound = payload.optBoolean("markerFound")
                val marker = payload.optString("marker")
                runOnUiThread {
                    resultView.text = "Network GenerateContent: HTTP $statusCode, ok=$ok, markerFound=$markerFound, marker=$marker"
                    statusView.text = "Trạng thái: đã nhận kết quả trực tiếp từ tầng mạng"
                }
            }
        }
    }

    private fun trustedSendAndCapture() {
        val prompt = promptInput.text.toString()
        val marker = Regex("AIS_[A-Z0-9_]+").find(prompt)?.value ?: DEFAULT_MARKER
        lab("I", "TRUSTED_CAPTURE_START", "promptChars=${prompt.length} marker=$marker accessibility=${accessibilitySummary()}")
        eval("prepare-trusted-send", "window.__AIS_WEB_SESSION__.prepareTrustedSend(${JSONObject.quote(prompt)},${JSONObject.quote(marker)})") { raw ->
            val root = decodeEvalObject(raw)
            val value = root?.optJSONObject("value")
            if (value == null || !value.optBoolean("ok")) {
                status("Không tìm được prompt/Run: ${raw.orEmpty().take(500)}")
                return@eval
            }
            webView.evaluateJavascript("JSON.stringify({innerWidth:window.innerWidth,innerHeight:window.innerHeight})") { geometryRaw ->
                val geometry = decodeJsonStringObject(geometryRaw)
                dispatchNativeTouch(value, geometry)
                scheduleNetworkReads()
            }
        }
    }

    private fun dispatchNativeTouch(value: JSONObject, geometry: JSONObject?) {
        val innerWidth = geometry?.optDouble("innerWidth", 0.0) ?: 0.0
        val innerHeight = geometry?.optDouble("innerHeight", 0.0) ?: 0.0
        val centerX = value.optDouble("x", -1.0)
        val centerY = value.optDouble("y", -1.0)
        if (innerWidth <= 0.0 || innerHeight <= 0.0 || centerX < 0 || centerY < 0 || webView.width <= 2 || webView.height <= 2) {
            lab("W", "NATIVE_TOUCH_INVALID", "inner=${innerWidth}x$innerHeight center=$centerX,$centerY view=${webView.width}x${webView.height}")
            status("Không quy đổi được tọa độ nút Run.")
            return
        }
        val x = (centerX / innerWidth * webView.width).toFloat().coerceIn(1f, webView.width - 1f)
        val y = (centerY / innerHeight * webView.height).toFloat().coerceIn(1f, webView.height - 1f)
        val now = SystemClock.uptimeMillis()
        webView.requestFocus()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now + 75, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val downHandled = runCatching { webView.dispatchTouchEvent(down) }.getOrDefault(false)
        webView.postDelayed({
            val upHandled = runCatching { webView.dispatchTouchEvent(up) }.getOrDefault(false)
            down.recycle(); up.recycle()
            lab("I", "NATIVE_TOUCH_RESULT", "css=$centerX,$centerY android=$x,$y down=$downHandled up=$upHandled")
        }, 75)
        status("Đã gửi trusted MotionEvent. Đang chờ network GenerateContent...")
    }

    private fun scheduleNetworkReads() {
        listOf(2_500L, 5_000L, 9_000L, 15_000L).forEachIndexed { index, delay ->
            webView.postDelayed({ if (!destroyed) eval("network-result-${index + 1}", "window.__AIS_WEB_SESSION__.getLastSafeResponse()") }, delay)
        }
    }

    private fun readLastNetworkResult() {
        eval("last-network-result", "window.__AIS_WEB_SESSION__.getLastSafeResponse()") { raw ->
            val root = decodeEvalObject(raw)
            val value = root?.optJSONObject("value")
            if (value != null) {
                resultView.text = "Network result: ${value.toString(2)}"
            }
        }
    }

    private fun inspectProbe() {
        eval("inspect", "window.__AIS_WEB_SESSION__.inspect()") { raw ->
            val root = decodeEvalObject(raw)
            val value = root?.optJSONObject("value")
            if (value != null) {
                val stack = value.optString("lastCallStack")
                labLog.snapshot("last-generate-call-stack", stack)
                status("Probe=${value.optString("version")}, captures=${value.optInt("captureCount")}, có call stack=${stack.isNotBlank()}")
            }
        }
    }

    private fun eval(name: String, expression: String, callback: ((String?) -> Unit)? = null) {
        if (destroyed) return
        val seq = ++evalSeq
        webView.evaluateJavascript(AiStudioWebSessionLabScripts.call(expression)) { result ->
            lab("D", "EVAL_RESULT", "name=$name seq=$seq result=${result.orEmpty().take(16_000)}")
            callback?.invoke(result)
        }
    }

    private fun shareBundle() {
        inspectProbe()
        webView.postDelayed({
            runCatching {
                val bundle = labLog.createBundle(diagnosticSummary())
                val uri = FileProvider.getUriForFile(this, "$packageName.files", bundle)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("AI Studio Web Session diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                lab("I", "SHARE_BUNDLE", "file=${bundle.name} bytes=${bundle.length()}")
                startActivity(Intent.createChooser(send, "Chia sẻ AI Studio Web Session Lab log"))
            }.onFailure { lab("E", "SHARE_ERROR", it.toString()) }
        }, 700)
    }

    private fun diagnosticSummary(): String = buildString {
        appendLine("AI Studio Web Session Executor Lab")
        appendLine("version=${AiStudioWebSessionLabScripts.VERSION}")
        appendLine("url=${safeUrl(webView.url)}")
        appendLine("title=${webView.title.orEmpty()}")
        appendLine("webView=${webViewPackage()}")
        appendLine("documentStartSupported=${WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)}")
        appendLine("accessibility=${accessibilitySummary()}")
        appendLine("eventFile=${labLog.eventFile().absolutePath}")
    }

    private fun accessibilitySummary(): String {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
        return "enabled=${manager.isEnabled},touchExploration=${manager.isTouchExplorationEnabled},servicePackages=${services.joinToString().ifBlank { "none" }}"
    }

    private fun decodeEvalObject(raw: String?): JSONObject? = runCatching {
        val first = JSONTokener(raw.orEmpty()).nextValue()
        when (first) {
            is JSONObject -> first
            is String -> JSONObject(first)
            else -> null
        }
    }.getOrNull()

    private fun decodeJsonStringObject(raw: String?): JSONObject? = runCatching {
        val first = JSONTokener(raw.orEmpty()).nextValue()
        when (first) {
            is JSONObject -> first
            is String -> JSONObject(first)
            else -> null
        }
    }.getOrNull()

    private fun webViewPackage(): String {
        val pkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        return "${pkg?.packageName.orEmpty()} ${pkg?.versionName.orEmpty()}"
    }

    private fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val uri = Uri.parse(raw)
            buildString {
                append(uri.scheme.orEmpty()).append("://").append(uri.host.orEmpty())
                if (uri.port != -1) append(':').append(uri.port)
                append(uri.encodedPath.orEmpty().take(1000))
                val keys = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())
                if (keys.isNotEmpty()) append('?').append(keys.joinToString("&") { "$it=<value>" })
            }.take(1600)
        }.getOrElse { raw.take(1600) }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        contentDescription = label
        setOnClickListener {
            lab("I", "UI_ACTION", label)
            runCatching(action).onFailure { lab("E", "UI_ACTION_ERROR", "$label: $it") }
        }
    }

    private fun horizontalButtons(vararg buttons: Button) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = true
        addView(LinearLayout(this@AiStudioWebSessionLabActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it) }
        })
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun status(text: String) {
        if (destroyed || !::statusView.isInitialized) return
        runOnUiThread { if (!destroyed) statusView.text = "Trạng thái: $text" }
    }

    private fun lab(level: String, event: String, detail: String) {
        if (!::labLog.isInitialized) return
        labLog.event(level, event, detail)
        if (!destroyed && ::liveLogView.isInitialized) runOnUiThread {
            uiLog.append("[$level][$event] ").append(detail.take(1600)).append('\n')
            if (uiLog.length > 34_000) uiLog.delete(0, uiLog.length - 34_000)
            liveLogView.text = uiLog.toString()
        }
    }

    companion object {
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val HOME_URL = "https://aistudio.google.com/"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_MARKER = "AIS_WEB_SESSION_NETWORK_OK_20260901"
        private const val DEFAULT_PROMPT = "Chỉ trả lời đúng chuỗi sau, không thêm nội dung nào khác: $DEFAULT_MARKER"
    }
}
