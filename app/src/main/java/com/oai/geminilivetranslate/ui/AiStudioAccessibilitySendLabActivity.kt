package com.oai.geminilivetranslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
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
import com.oai.geminilivetranslate.core.AiStudioBridgeLabLog
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Lab chuyên biệt để xác định vì sao TalkBack / screen reader làm AI Studio GenerateContent trả 403.
 *
 * Mỗi cách gửi được gắn nhãn vào request RPC. Mục tiêu là tìm một phương thức gửi vẫn hoạt động
 * khi TalkBack đang bật, không yêu cầu người dùng tắt trình đọc màn hình.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioAccessibilitySendLabActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView
    private lateinit var accessibilityView: TextView
    private lateinit var resultView: TextView
    private lateinit var liveLogView: TextView
    private lateinit var labLog: AiStudioBridgeLabLog

    private val uiLog = StringBuilder()
    private val outcomes = linkedMapOf<String, String>()
    private var armedMethod = "UNARMED"
    private var destroyed = false
    private var evalSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioBridgeLabLog(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)
        buildUi()
        configureWebView()
        logAccessibilityState("create")
        lab("I", "A11Y_LAB_CREATE", "webViewPackage=${webViewPackage()}")
        webView.loadUrl(NEW_CHAT_URL)
    }

    override fun onResume() {
        super.onResume()
        lab("I", "ACTIVITY_RESUME", "focus=${hasWindowFocus()} url=${safeUrl(webView.url)}")
        logAccessibilityState("resume")
        webView.onResume()
        installProbe("resume")
    }

    override fun onPause() {
        lab("I", "ACTIVITY_PAUSE", "focus=${hasWindowFocus()} armed=$armedMethod url=${safeUrl(webView.url)}")
        eval("pause-js-state", "({visibility:document.visibilityState,hasFocus:document.hasFocus(),active:window.__AIS_A11Y_LAB__.describe(document.activeElement),method:window.__AIS_A11Y_LAB__.currentMethod})")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::labLog.isInitialized) {
            lab("I", "WINDOW_FOCUS", "hasFocus=$hasFocus armed=$armedMethod")
            logAccessibilityState("window-focus-$hasFocus")
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                webView.removeJavascriptInterface(JS_BRIDGE_NAME)
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        root.addView(TextView(this).apply {
            text = "AI Studio TalkBack Send Lab"
            textSize = 19f
            contentDescription = "Phòng thử nghiệm lỗi TalkBack khi gửi AI Studio"
        }, fullWidth())

        accessibilityView = TextView(this).apply {
            text = "Trình đọc màn hình: đang kiểm tra"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(4))
            contentDescription = "Trạng thái trợ năng và TalkBack"
        }
        root.addView(accessibilityView, fullWidth())

        val controlsScroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlsScroll.addView(controls)

        controls.addView(horizontalButtons(
            actionButton("Mở New Chat") { webView.loadUrl(NEW_CHAT_URL) },
            actionButton("Reload") { webView.reload() },
            actionButton("Cài theo dõi") { installProbe("button") },
            actionButton("Quét prompt/nút gửi") { eval("scan", "window.__AIS_A11Y_LAB__.scan()") },
            actionButton("Kiểm tra TalkBack") { logAccessibilityState("button") },
        ))

        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 4
            hint = "Prompt dùng chung cho mọi phương thức gửi"
            contentDescription = "Prompt dùng để so sánh các phương thức gửi"
        }
        controls.addView(promptInput, fullWidth())

        controls.addView(section("Mốc đối chứng với TalkBack"))
        controls.addView(TextView(this).apply {
            text = "Bật TalkBack. Bấm “Chuẩn bị TalkBack”, sau đó tự double-tap nút Send/Run bên trong AI Studio. Lab sẽ gắn nhãn request đó là TALKBACK_MANUAL."
        }, fullWidth())
        controls.addView(horizontalButtons(
            actionButton("Chuẩn bị TalkBack") { prepareManualTalkBack() },
            actionButton("Chỉ điền prompt") { fillOnly() },
        ))

        controls.addView(section("Gửi tự động khi TalkBack vẫn bật"))
        controls.addView(horizontalButtons(
            actionButton("1. HTMLElement.click") { sendWithJsClick() },
            actionButton("2. MouseEvent") { sendWithMouseEvents() },
            actionButton("3. PointerEvent") { sendWithPointerEvents() },
            actionButton("4. Native MotionEvent") { sendWithNativeMotionEvent() },
        ))

        controls.addView(TextView(this).apply {
            text = "Nên thử từng cách ở một New Chat hoặc Reload mới. Không cần tắt TalkBack. Native MotionEvent được bơm thẳng vào WebView để tránh đường ACTION_CLICK của AccessibilityService."
        }, fullWidth())

        controls.addView(section("Kết quả GenerateContent"))
        resultView = TextView(this).apply {
            text = "Chưa có kết quả."
            setTextIsSelectable(true)
            contentDescription = "Bảng kết quả từng phương thức gửi"
        }
        controls.addView(resultView, fullWidth())

        controls.addView(horizontalButtons(
            actionButton("Snapshot") { saveSnapshot() },
            actionButton("Chia sẻ log ZIP") { shareBundle() },
        ))

        statusView = TextView(this).apply {
            text = "Trạng thái: đang mở AI Studio"
            setPadding(0, dp(5), 0, dp(5))
            contentDescription = "Trạng thái phép thử TalkBack"
        }
        controls.addView(statusView, fullWidth())

        root.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        webView = WebView(this).apply {
            contentDescription = "Google AI Studio để thử lỗi TalkBack"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> lab("I", "WEBVIEW_FOCUS", "hasFocus=$hasFocus armed=$armedMethod") }
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val liveScroll = ScrollView(this)
        liveLogView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký trực tiếp TalkBack Lab"
        }
        liveScroll.addView(liveLogView)
        root.addView(liveScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        setContentView(root)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(7), 0, dp(2))
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        contentDescription = label
        setOnClickListener {
            lab("I", "UI_ACTION", label)
            runCatching(action).onFailure { labLog.exception("UI_ACTION_ERROR", it, "action=$label") }
        }
    }

    private fun horizontalButtons(vararg buttons: Button) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = true
        addView(LinearLayout(this@AiStudioAccessibilitySendLabActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it) }
        })
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                lab("I", "PAGE_STARTED", "url=${safeUrl(url)}")
                status("Đang tải ${safeUrl(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lab("I", "PAGE_FINISHED", "url=${safeUrl(url)} title=${view?.title.orEmpty()} progress=${view?.progress}")
                status("Đã tải ${view?.title.orEmpty().ifBlank { safeUrl(url) }}")
                installProbe("page-finished")
                webView.postDelayed({ eval("auto-scan", "window.__AIS_A11Y_LAB__.scan()") }, 700)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    val url = request.url.toString()
                    if (url.contains("MakerSuiteService", true) || url.contains("GenerateContent", true) || url.contains("CountTokens", true)) {
                        val headers = request.requestHeaders.entries.joinToString { (k, v) ->
                            when (k.lowercase(Locale.US)) {
                                "authorization", "cookie", "x-goog-api-key" -> "$k=<${v.length} chars>"
                                else -> "$k=${v.take(180)}"
                            }
                        }
                        lab("I", "WEB_NET_REQUEST", "method=${request.method} gesture=${request.hasGesture()} url=${safeUrl(url)} headers={$headers} armed=$armedMethod")
                    }
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                lab("W", "WEB_ERROR", "main=${request?.isForMainFrame} code=${error?.errorCode} desc=${error?.description} url=${safeUrl(request?.url?.toString())}")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                lab("W", "HTTP_ERROR", "main=${request?.isForMainFrame} status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${safeUrl(request?.url?.toString())}")
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                lab("E", "SSL_ERROR", "primary=${error?.primaryError} url=${safeUrl(error?.url)}")
                handler?.cancel()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                lab("E", "RENDER_GONE", "crashed=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}")
                status("Renderer WebView đã dừng. Hãy mở lại Lab.")
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null && consoleMessage.messageLevel() != ConsoleMessage.MessageLevel.LOG) {
                    lab("D", "JS_CONSOLE", "level=${consoleMessage.messageLevel()} ${consoleMessage.sourceId().take(300)}:${consoleMessage.lineNumber()} ${consoleMessage.message().take(4000)}")
                }
                return false
            }
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onJsEvent(json: String) {
            val parsed = runCatching { JSONObject(json) }.getOrNull()
            val kind = parsed?.optString("kind", "JS_EVENT") ?: "JS_EVENT"
            val payload = parsed?.optJSONObject("payload")
            lab(if (kind.contains("FAIL") || kind.contains("ERROR") || kind == "RPC_ERROR") "W" else "I", "JS_$kind", json.take(30000))

            if (kind == "RPC_END" && payload != null) {
                val op = payload.optString("op")
                val statusCode = payload.optInt("status", -1)
                val method = payload.optString("sendMethod", armedMethod).ifBlank { armedMethod }
                if (op.contains("GenerateContent", ignoreCase = true)) {
                    outcomes[method] = if (statusCode in 200..299) "THÀNH CÔNG HTTP $statusCode" else "LỖI HTTP $statusCode"
                    runOnUiThread {
                        updateResultView()
                        status("$method → GenerateContent HTTP $statusCode")
                    }
                    lab("I", "GENERATE_OUTCOME", "method=$method status=$statusCode preview=${payload.optString("preview").take(12000)}")
                }
            }
        }
    }

    private fun installProbe(reason: String) {
        if (!::webView.isInitialized || destroyed) return
        val seq = ++evalSeq
        lab("I", "PROBE_INSTALL", "reason=$reason seq=$seq url=${safeUrl(webView.url)}")
        webView.evaluateJavascript(AiStudioAccessibilitySendLabScripts.INSTALL) { result ->
            lab("I", "PROBE_RESULT", "seq=$seq result=${result.orEmpty().take(16000)}")
        }
    }

    private fun eval(name: String, expression: String, callback: ((String?) -> Unit)? = null) {
        if (destroyed) return
        val seq = ++evalSeq
        lab("I", "EVAL_START", "name=$name seq=$seq expression=${expression.take(1200)}")
        webView.evaluateJavascript(AiStudioAccessibilitySendLabScripts.call(expression)) { result ->
            lab("I", "EVAL_RESULT", "name=$name seq=$seq result=${result.orEmpty().take(22000)}")
            callback?.invoke(result)
        }
    }

    private fun fillOnly() {
        installProbe("fill-only")
        webView.postDelayed({ eval("fill-only", "window.__AIS_A11Y_LAB__.fill(${JSONObject.quote(promptInput.text.toString())})") }, 300)
    }

    private fun prepareManualTalkBack() {
        armAndFill(METHOD_TALKBACK) {
            status("Đã chuẩn bị. Bây giờ dùng TalkBack double-tap nút Send/Run bên trong AI Studio.")
        }
    }

    private fun sendWithJsClick() {
        armAndFill(METHOD_JS_CLICK) {
            eval("send-js-click", "window.__AIS_A11Y_LAB__.sendClick()")
        }
    }

    private fun sendWithMouseEvents() {
        armAndFill(METHOD_MOUSE) {
            eval("send-mouse", "window.__AIS_A11Y_LAB__.sendMouse()")
        }
    }

    private fun sendWithPointerEvents() {
        armAndFill(METHOD_POINTER) {
            eval("send-pointer", "window.__AIS_A11Y_LAB__.sendPointer()")
        }
    }

    private fun sendWithNativeMotionEvent() {
        armAndFill(METHOD_NATIVE_TOUCH) {
            eval("send-rect", "window.__AIS_A11Y_LAB__.sendRect()") { raw ->
                val root = decodeEvalObject(raw)
                val value = root?.optJSONObject("value")
                if (value == null || !value.optBoolean("ok")) {
                    lab("W", "NATIVE_TOUCH_FAIL", "Không lấy được rect: ${raw.orEmpty().take(8000)}")
                    status("Không xác định được vị trí nút Send. Hãy bấm Quét prompt/nút gửi.")
                    return@eval
                }
                dispatchNativeTouch(value)
            }
        }
    }

    private fun armAndFill(method: String, afterFill: () -> Unit) {
        armedMethod = method
        outcomes[method] = "ĐANG CHỜ"
        updateResultView()
        logAccessibilityState("before-$method")
        installProbe("method-$method")
        webView.postDelayed({
            eval("arm-$method", "window.__AIS_A11Y_LAB__.arm(${JSONObject.quote(method)})")
        }, 250)
        webView.postDelayed({
            eval("fill-$method", "window.__AIS_A11Y_LAB__.fill(${JSONObject.quote(promptInput.text.toString())})")
        }, 500)
        webView.postDelayed({
            lab("I", "SEND_METHOD_READY", "method=$method accessibility=${accessibilitySummary()}")
            afterFill()
        }, 950)
    }

    private fun dispatchNativeTouch(value: JSONObject) {
        val innerWidth = value.optDouble("innerWidth", 0.0)
        val innerHeight = value.optDouble("innerHeight", 0.0)
        val centerX = value.optDouble("centerX", -1.0)
        val centerY = value.optDouble("centerY", -1.0)
        if (innerWidth <= 0.0 || innerHeight <= 0.0 || centerX < 0.0 || centerY < 0.0 || webView.width <= 0 || webView.height <= 0) {
            lab("W", "NATIVE_TOUCH_FAIL", "invalid geometry inner=${innerWidth}x$innerHeight center=$centerX,$centerY view=${webView.width}x${webView.height}")
            return
        }
        val x = (centerX / innerWidth * webView.width).toFloat().coerceIn(1f, webView.width - 1f)
        val y = (centerY / innerHeight * webView.height).toFloat().coerceIn(1f, webView.height - 1f)
        val now = SystemClock.uptimeMillis()
        lab("I", "NATIVE_TOUCH_DISPATCH", "method=$armedMethod cssCenter=$centerX,$centerY inner=${innerWidth}x$innerHeight android=$x,$y view=${webView.width}x${webView.height} selected=${value.optJSONObject("selected")}")

        webView.requestFocus()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now + 75, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val downHandled = runCatching { webView.dispatchTouchEvent(down) }.getOrDefault(false)
        webView.postDelayed({
            val upHandled = runCatching { webView.dispatchTouchEvent(up) }.getOrDefault(false)
            down.recycle()
            up.recycle()
            lab("I", "NATIVE_TOUCH_RESULT", "method=$armedMethod downHandled=$downHandled upHandled=$upHandled x=$x y=$y")
        }, 75)
    }

    private fun logAccessibilityState(reason: String) {
        val summary = accessibilitySummary()
        lab("I", "ACCESSIBILITY_STATE", "reason=$reason $summary")
        if (::accessibilityView.isInitialized) {
            runOnUiThread { accessibilityView.text = "Trình đọc màn hình: $summary" }
        }
    }

    private fun accessibilitySummary(): String {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { info ->
                val service = info.resolveInfo?.serviceInfo
                val id = info.id.orEmpty()
                "${service?.packageName.orEmpty()}/${service?.name.orEmpty()} id=$id feedback=${info.feedbackType} flags=${info.flags}"
            }
        }.getOrDefault(emptyList())
        return "enabled=${manager.isEnabled}, touchExploration=${manager.isTouchExplorationEnabled}, services=${services.joinToString(" | ").ifBlank { "none" }}"
    }

    private fun updateResultView() {
        if (!::resultView.isInitialized) return
        val text = if (outcomes.isEmpty()) "Chưa có kết quả." else outcomes.entries.joinToString("\n") { (method, result) -> "$method → $result" }
        runOnUiThread { resultView.text = text }
    }

    private fun saveSnapshot() {
        labLog.snapshot("a11y-android-state", buildString {
            appendLine("url=${safeUrl(webView.url)}")
            appendLine("title=${webView.title.orEmpty()}")
            appendLine("webViewPackage=${webViewPackage()}")
            appendLine("armedMethod=$armedMethod")
            appendLine("accessibility=${accessibilitySummary()}")
            appendLine("outcomes=$outcomes")
        })
        eval("snapshot-js", "window.__AIS_A11Y_LAB__.scan()") { raw ->
            labLog.snapshot("a11y-js-state", raw.orEmpty().take(900000))
            status("Đã lưu snapshot TalkBack Lab.")
        }
    }

    private fun shareBundle() {
        saveSnapshot()
        webView.postDelayed({
            runCatching {
                val summary = "accessibility=${accessibilitySummary()}\narmedMethod=$armedMethod\noutcomes=$outcomes\nurl=${safeUrl(webView.url)}\nwebView=${webViewPackage()}"
                val bundle = labLog.createBundle(summary)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", bundle)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("AI Studio TalkBack diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                lab("I", "SHARE_BUNDLE", "file=${bundle.name} bytes=${bundle.length()}")
                startActivity(Intent.createChooser(send, "Chia sẻ nhật ký AI Studio TalkBack Lab"))
            }.onFailure { labLog.exception("SHARE_ERROR", it) }
        }, 700)
    }

    private fun decodeEvalObject(raw: String?): JSONObject? = runCatching {
        val first = JSONTokener(raw.orEmpty()).nextValue()
        when (first) {
            is JSONObject -> first
            is String -> JSONObject(first)
            else -> null
        }
    }.getOrNull()

    private fun status(text: String) {
        if (destroyed || !::statusView.isInitialized) return
        runOnUiThread { if (!destroyed) statusView.text = "Trạng thái: $text" }
    }

    private fun lab(level: String, event: String, detail: String) {
        if (!::labLog.isInitialized) return
        labLog.event(level, event, detail)
        appendUi("[$level][$event] ${detail.take(2200)}")
    }

    private fun appendUi(text: String) {
        if (destroyed) return
        runOnUiThread {
            if (!::liveLogView.isInitialized || destroyed) return@runOnUiThread
            uiLog.append(text).append('\n')
            if (uiLog.length > 42000) uiLog.delete(0, uiLog.length - 42000)
            liveLogView.text = uiLog.toString()
        }
    }

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
                append(uri.encodedPath.orEmpty().take(1200))
                val keys = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())
                if (keys.isNotEmpty()) append('?').append(keys.joinToString("&") { "$it=<value>" })
            }.take(1800)
        }.getOrElse { raw.take(1800) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val JS_BRIDGE_NAME = "AIStudioA11yLab"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_PROMPT = "Chỉ trả lời đúng chuỗi sau, không thêm nội dung khác: AIS_TALKBACK_TEST_OK_20260901"
        private const val METHOD_TALKBACK = "TALKBACK_MANUAL"
        private const val METHOD_JS_CLICK = "JS_HTMLELEMENT_CLICK"
        private const val METHOD_MOUSE = "JS_MOUSE_EVENTS"
        private const val METHOD_POINTER = "JS_POINTER_EVENTS"
        private const val METHOD_NATIVE_TOUCH = "ANDROID_NATIVE_MOTION_EVENT"
    }
}
