package com.oai.geminilivetranslate.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.oai.geminilivetranslate.core.AiStudioBridgeLabLog
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phòng thí nghiệm độc lập cho ý tưởng dùng Google AI Studio như một browser bridge.
 *
 * Activity này cố ý không nối vào luồng dịch/mô tả chính. Nó có launcher riêng để có thể
 * phá, reload, đổi user-agent, bật hook mạng và làm renderer crash mà không làm hỏng UI chính.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioBridgeLabActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView
    private lateinit var liveLogView: TextView
    private lateinit var userAgentSpinner: Spinner
    private lateinit var keepAliveCheck: CheckBox
    private lateinit var autoProbeCheck: CheckBox

    private lateinit var labLog: AiStudioBridgeLabLog
    private val uiLog = StringBuilder()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val popupDialogs = ConcurrentHashMap<WebView, Dialog>()
    private val evaluateSequence = AtomicInteger(0)

    private var defaultUserAgent: String = ""
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var destroyed = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@registerForActivityResult
        val uris = if (result.resultCode == Activity.RESULT_OK) extractUris(result.data) else emptyArray()
        lab("I", "FILE_CHOOSER_RESULT", "result=${result.resultCode} count=${uris.size} ${uris.joinToString { describeUri(it) }}")
        callback.onReceiveValue(uris.takeIf { it.isNotEmpty() })
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        lab("I", "ANDROID_PERMISSION_RESULT", results.entries.joinToString { "${it.key}=${it.value}" })
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            val allGranted = requiredAndroidPermissions(request.resources).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                lab("I", "WEB_PERMISSION_GRANT", "resources=${request.resources.joinToString()}")
                request.grant(request.resources)
            } else {
                lab("W", "WEB_PERMISSION_DENY", "Android permission missing; resources=${request.resources.joinToString()}")
                request.deny()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioBridgeLabLog(this)
        lab("I", "ACTIVITY_CREATE", "saved=${savedInstanceState != null}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)
        defaultUserAgent = WebSettings.getDefaultUserAgent(this)
        buildUi()
        configureWebView(webView, isPopup = false)
        logWebViewEnvironment("startup")
        if (savedInstanceState != null) {
            runCatching { webView.restoreState(savedInstanceState) }
                .onSuccess { lab("I", "WEBVIEW_RESTORE", "restored=${it != null}") }
                .onFailure { labLog.exception("WEBVIEW_RESTORE_ERROR", it) }
        } else {
            loadUrl(HOME_URL, "initial")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lab("I", "ACTIVITY_SAVE_STATE", "url=${safeUrl(webView.url)}")
        runCatching { webView.saveState(outState) }
            .onFailure { labLog.exception("WEBVIEW_SAVE_ERROR", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        lab("D", "ACTIVITY_START", lifecycleState())
    }

    override fun onResume() {
        super.onResume()
        lab("I", "ACTIVITY_RESUME", lifecycleState())
        if (!keepAliveCheck.isChecked) runCatching { webView.onResume() }
        eval("visibility-resume", "window.__AIS_LAB__ ? window.__AIS_LAB__.environment() : ({ready:false})")
    }

    override fun onPause() {
        lab("I", "ACTIVITY_PAUSE", "keepAlive=${keepAliveCheck.isChecked} ${lifecycleState()}")
        eval("visibility-pause", "window.__AIS_LAB__ ? window.__AIS_LAB__.environment() : ({ready:false})")
        if (!keepAliveCheck.isChecked) runCatching { webView.onPause() }
        super.onPause()
    }

    override fun onStop() {
        lab("I", "ACTIVITY_STOP", "keepAlive=${keepAliveCheck.isChecked} ${lifecycleState()}")
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::labLog.isInitialized) lab("D", "WINDOW_FOCUS", "hasFocus=$hasFocus")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::labLog.isInitialized) {
            val runtime = Runtime.getRuntime()
            lab(
                "W",
                "TRIM_MEMORY",
                "level=$level usedMb=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576} maxMb=${runtime.maxMemory() / 1_048_576}",
            )
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::labLog.isInitialized) lab("E", "LOW_MEMORY", lifecycleState())
    }

    override fun onDestroy() {
        destroyed = true
        lab("I", "ACTIVITY_DESTROY", "finishing=$isFinishing changingConfig=$isChangingConfigurations")
        popupDialogs.keys.toList().forEach { popup ->
            runCatching { popup.stopLoading(); popup.destroy() }
            popupDialogs.remove(popup)?.dismiss()
        }
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.loadUrl("about:blank")
            webView.destroy()
        }.onFailure { labLog.exception("WEBVIEW_DESTROY_ERROR", it) }
        super.onDestroy()
    }

    @Deprecated("Experimental activity intentionally keeps classic back navigation for WebView history")
    override fun onBackPressed() {
        when {
            popupDialogs.isNotEmpty() -> popupDialogs.entries.lastOrNull()?.let { (view, dialog) ->
                lab("I", "BACK_POPUP_CLOSE", "url=${safeUrl(view.url)}")
                popupDialogs.remove(view)
                runCatching { view.destroy() }
                dialog.dismiss()
            }
            webView.canGoBack() -> {
                lab("I", "BACK_WEBVIEW", "from=${safeUrl(webView.url)}")
                webView.goBack()
            }
            else -> super.onBackPressed()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val title = TextView(this).apply {
            text = "AI Studio Browser Bridge Lab • bản thử nghiệm"
            textSize = 18f
            setPadding(dp(4), dp(2), dp(4), dp(4))
            contentDescription = "Phòng thử nghiệm AI Studio Browser Bridge"
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val controlsScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(4))
        }
        controlsScroll.addView(controls)

        urlInput = EditText(this).apply {
            setSingleLine(true)
            setText(HOME_URL)
            hint = "URL AI Studio"
            contentDescription = "Địa chỉ trang AI Studio cần thử nghiệm"
        }
        controls.addView(urlInput, fullWidth())

        controls.addView(horizontalButtons(
            actionButton("Mở Home") { loadUrl(HOME_URL, "button-home") },
            actionButton("Mở New Chat") { loadUrl(NEW_CHAT_URL, "button-new-chat") },
            actionButton("Mở URL") { loadUrl(urlInput.text.toString(), "button-custom-url") },
            actionButton("Reload") { lab("I", "RELOAD", safeUrl(webView.url)); webView.reload() },
            actionButton("Stop") { lab("W", "STOP_LOADING", safeUrl(webView.url)); webView.stopLoading() },
            actionButton("Mở ngoài") { openExternalBrowser() },
        ))

        val uaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        uaRow.addView(TextView(this).apply { text = "User-Agent: "; contentDescription = "Chiến lược user agent" })
        userAgentSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AiStudioBridgeLabActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("WebView mặc định", "Chrome Android-like", "Chrome Desktop-like"),
            )
            contentDescription = "Chọn user agent cho WebView"
        }
        uaRow.addView(userAgentSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        uaRow.addView(actionButton("Áp dụng + reload") { applyUserAgent(reload = true) })
        controls.addView(uaRow, fullWidth())

        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 4
            hint = "Prompt thử nghiệm"
            contentDescription = "Nội dung prompt dùng cho các chiến lược tự động"
        }
        controls.addView(promptInput, fullWidth())

        keepAliveCheck = CheckBox(this).apply {
            text = "Giữ WebView hoạt động khi Activity xuống nền"
            isChecked = true
            contentDescription = "Giữ WebView hoạt động khi ứng dụng xuống nền để kiểm tra giới hạn background"
        }
        autoProbeCheck = CheckBox(this).apply {
            text = "Tự cài probe + observer sau mỗi lần tải trang"
            isChecked = true
            contentDescription = "Tự động cài JavaScript probe và MutationObserver sau khi trang tải xong"
        }
        controls.addView(keepAliveCheck, fullWidth())
        controls.addView(autoProbeCheck, fullWidth())

        controls.addView(sectionLabel("Quan sát và chẩn đoán"))
        controls.addView(horizontalButtons(
            actionButton("Cài probe JS") { installProbe("manual") },
            actionButton("Test cầu JS") { testJsBridge() },
            actionButton("Quét DOM") { eval("dom-scan", "window.__AIS_LAB__.domScan()") },
            actionButton("Highlight") { eval("highlight", "window.__AIS_LAB__.highlight()") },
            actionButton("Observer") { eval("observer", "window.__AIS_LAB__.installObserver()") },
            actionButton("Đọc response") { eval("response-scan", "window.__AIS_LAB__.readResponse()") },
            actionButton("Snapshot") { saveSnapshot() },
        ))

        controls.addView(sectionLabel("Theo dõi giao tiếp mạng phía trang"))
        controls.addView(horizontalButtons(
            actionButton("Hook fetch/XHR/WS") { eval("network-hooks", "window.__AIS_LAB__.installNetworkHooks()") },
            actionButton("Quét resource") { eval("resource-scan", "window.__AIS_LAB__.resourceScan()") },
            actionButton("Cookie trạng thái") { logCookieState("button") },
            actionButton("Môi trường JS") { eval("environment", "window.__AIS_LAB__.environment()") },
        ))

        controls.addView(sectionLabel("Điều khiển prompt nhiều phương án"))
        controls.addView(horizontalButtons(
            actionButton("Điền A: semantic") { fillPromptSemantic() },
            actionButton("Điền B: execCommand") { fillPromptExecCommand() },
            actionButton("Gửi A: nút") { eval("send-button", "window.__AIS_LAB__.sendByButton()") },
            actionButton("Gửi B: form") { eval("send-form", "window.__AIS_LAB__.sendByForm()") },
            actionButton("Gửi C: Enter") { eval("send-enter", "window.__AIS_LAB__.sendByEnter()") },
        ))

        controls.addView(sectionLabel("Kịch bản tự động"))
        controls.addView(horizontalButtons(
            actionButton("AUTO A") { runAutoStrategyA() },
            actionButton("AUTO B") { runAutoStrategyB() },
            actionButton("Chỉ quan sát thủ công") { prepareManualObservation() },
            actionButton("Xin mic/camera") { requestMediaPermissions() },
            actionButton("Chia sẻ log ZIP") { shareLabBundle() },
        ))

        statusView = TextView(this).apply {
            text = "Trạng thái: khởi tạo"
            setPadding(dp(4), dp(6), dp(4), dp(6))
            contentDescription = "Trạng thái thử nghiệm"
        }
        controls.addView(statusView, fullWidth())

        root.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        webView = WebView(this).apply {
            contentDescription = "Trình duyệt thử nghiệm Google AI Studio"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val liveLogScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        liveLogView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký trực tiếp của phòng thử nghiệm"
        }
        liveLogScroll.addView(liveLogView)
        root.addView(liveLogScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        setContentView(root)
        appendUi("Lab launcher sẵn sàng. Log file: ${labLog.currentEventFile().absolutePath}")
    }

    private fun sectionLabel(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 15f
        setPadding(dp(4), dp(7), dp(4), dp(2))
    }

    private fun horizontalButtons(vararg buttons: Button): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.forEach { row.addView(it) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(row)
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        contentDescription = label
        setOnClickListener {
            lab("I", "UI_ACTION", label)
            runCatching(action).onFailure { labLog.exception("UI_ACTION_ERROR", it, "action=$label") }
        }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    @SuppressLint("JavascriptInterface")
    private fun configureWebView(target: WebView, isPopup: Boolean) {
        val settings = target.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.userAgentString = currentUserAgent()

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(target, true)

        target.addJavascriptInterface(JsBridge(isPopup), JS_BRIDGE_NAME)
        target.webViewClient = createWebViewClient(isPopup)
        target.webChromeClient = createChromeClient(target, isPopup)
        lab(
            "I",
            "WEBVIEW_CONFIG",
            "popup=$isPopup js=${settings.javaScriptEnabled} dom=${settings.domStorageEnabled} thirdPartyCookies=true ua=${settings.userAgentString.take(500)}",
        )
    }

    private fun createWebViewClient(isPopup: Boolean): WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            lab("I", "PAGE_STARTED", "popup=$isPopup url=${safeUrl(url)}")
            status("Đang tải: ${safeUrl(url)}")
            logCookieState("page-start")
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            lab("I", "PAGE_COMMIT_VISIBLE", "popup=$isPopup url=${safeUrl(url)}")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            lab("I", "PAGE_FINISHED", "popup=$isPopup url=${safeUrl(url)} title=${view?.title.orEmpty().take(500)} progress=${view?.progress}")
            status("Đã tải: ${view?.title.orEmpty().ifBlank { safeUrl(url) }}")
            logCookieState("page-finish")
            if (!isPopup && autoProbeCheck.isChecked) {
                installProbe("page-finished")
                webView.postDelayed({ eval("auto-observer", "window.__AIS_LAB__.installObserver()") }, 350)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            lab("D", "HISTORY_UPDATE", "popup=$isPopup reload=$isReload url=${safeUrl(url)}")
            if (!isPopup && !url.isNullOrBlank()) urlInput.setText(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url
            lab(
                "D",
                "NAV_REQUEST",
                "popup=$isPopup method=${request?.method} main=${request?.isForMainFrame} gesture=${request?.hasGesture()} redirect=${if (Build.VERSION.SDK_INT >= 24) request?.isRedirect else false} url=${safeUrl(uri?.toString())}",
            )
            if (uri == null) return false
            return when (uri.scheme?.lowercase(Locale.US)) {
                "http", "https", "about", "data" -> false
                else -> {
                    lab("W", "NAV_EXTERNAL_SCHEME", "scheme=${uri.scheme} url=${safeUrl(uri.toString())}")
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { labLog.exception("NAV_EXTERNAL_ERROR", it) }
                    true
                }
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request != null) logNetworkRequest(request, isPopup)
            return null
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            lab(
                "E",
                "WEB_ERROR",
                "popup=$isPopup main=${request?.isForMainFrame} code=${error?.errorCode} description=${error?.description} url=${safeUrl(request?.url?.toString())}",
            )
            if (request?.isForMainFrame == true) status("Lỗi WebView ${error?.errorCode}: ${error?.description}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            lab(
                if ((errorResponse?.statusCode ?: 0) >= 400) "W" else "D",
                "HTTP_ERROR",
                "popup=$isPopup main=${request?.isForMainFrame} status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} mime=${errorResponse?.mimeType} url=${safeUrl(request?.url?.toString())}",
            )
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            lab("E", "SSL_ERROR", "popup=$isPopup primary=${error?.primaryError} url=${safeUrl(error?.url)}")
            handler?.cancel()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val runtime = Runtime.getRuntime()
            lab(
                "E",
                "RENDER_PROCESS_GONE",
                "popup=$isPopup crashed=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()} usedMb=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}",
            )
            status("Renderer WebView đã chết. crashed=${detail?.didCrash()}")
            if (view != null && isPopup) {
                popupDialogs.remove(view)?.dismiss()
                runCatching { view.destroy() }
            }
            return true
        }
    }

    private fun createChromeClient(owner: WebView, isPopup: Boolean): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress == 0 || newProgress == 25 || newProgress == 50 || newProgress == 75 || newProgress == 100) {
                lab("D", "PAGE_PROGRESS", "popup=$isPopup progress=$newProgress url=${safeUrl(view?.url)}")
            }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            lab("I", "PAGE_TITLE", "popup=$isPopup title=${title.orEmpty().take(1000)} url=${safeUrl(view?.url)}")
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null) {
                lab(
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "E"
                        ConsoleMessage.MessageLevel.WARNING -> "W"
                        ConsoleMessage.MessageLevel.DEBUG -> "D"
                        else -> "I"
                    },
                    "JS_CONSOLE",
                    "popup=$isPopup level=${consoleMessage.messageLevel()} ${consoleMessage.sourceId().take(500)}:${consoleMessage.lineNumber()} ${consoleMessage.message().take(12_000)}",
                )
            }
            return false
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            runOnUiThread {
                lab("W", "WEB_PERMISSION_REQUEST", "origin=${safeUrl(request.origin?.toString())} resources=${request.resources.joinToString()}")
                val required = requiredAndroidPermissions(request.resources)
                val missing = required.filter { ContextCompat.checkSelfPermission(this@AiStudioBridgeLabActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    lab("I", "WEB_PERMISSION_GRANT", "No Android permission missing")
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest?.deny()
                    pendingPermissionRequest = request
                    lab("I", "ANDROID_PERMISSION_REQUEST", "permissions=${missing.joinToString()}")
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
            lab("W", "WEB_PERMISSION_CANCELED", "origin=${safeUrl(request?.origin?.toString())} resources=${request?.resources?.joinToString()}")
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            if (filePathCallback == null) return false
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            val accept = fileChooserParams?.acceptTypes?.filter(String::isNotBlank).orEmpty()
            val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            lab(
                "I",
                "FILE_CHOOSER_OPEN",
                "popup=$isPopup accept=${accept.joinToString()} multiple=$multiple capture=${fileChooserParams?.isCaptureEnabled}",
            )
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (accept.size == 1 && accept[0].contains('/')) accept[0] else "*/*"
                if (accept.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accept.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return runCatching {
                filePicker.launch(intent)
                true
            }.getOrElse {
                labLog.exception("FILE_CHOOSER_LAUNCH_ERROR", it)
                pendingFileCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            if (resultMsg == null) return false
            lab("I", "POPUP_CREATE", "dialog=$isDialog gesture=$isUserGesture parent=${safeUrl(view?.url)}")
            val dialog = Dialog(this@AiStudioBridgeLabActivity).apply {
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val popup = WebView(this@AiStudioBridgeLabActivity)
            configureWebView(popup, isPopup = true)
            dialog.setContentView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.setOnDismissListener {
                popupDialogs.remove(popup)
                lab("I", "POPUP_DISMISS", "url=${safeUrl(popup.url)}")
                runCatching { popup.destroy() }
            }
            popupDialogs[popup] = dialog
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            dialog.show()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            if (window != null) {
                lab("I", "POPUP_CLOSE", "url=${safeUrl(window.url)}")
                popupDialogs.remove(window)?.dismiss()
                runCatching { window.destroy() }
            }
        }
    }

    private inner class JsBridge(private val popup: Boolean) {
        @JavascriptInterface
        fun onJsEvent(json: String) {
            val kind = runCatching { JSONObject(json).optString("kind", "JS_EVENT") }.getOrDefault("JS_EVENT")
            lab(
                when {
                    kind.contains("ERROR") || kind.contains("FATAL") || kind.contains("FAIL") -> "W"
                    kind.contains("WS_") || kind.contains("FETCH_") || kind.contains("XHR_") -> "D"
                    else -> "I"
                },
                "JS_$kind",
                "popup=$popup ${json.take(24_000)}",
            )
        }

        @JavascriptInterface
        fun onStreamChunk(source: String, text: String) {
            val normalized = text.replace("\u0000", "").take(16_000)
            lab("I", "STREAM_CHUNK", "popup=$popup source=${source.take(100)} chars=${normalized.length}\n$normalized")
            runOnUiThread {
                if (!destroyed) status("Có thay đổi/stream: ${source.take(60)} (${normalized.length} ký tự)")
            }
        }
    }

    private fun loadUrl(rawUrl: String, reason: String) {
        val normalized = rawUrl.trim().let {
            when {
                it.isBlank() -> HOME_URL
                it.startsWith("http://") || it.startsWith("https://") -> it
                else -> "https://$it"
            }
        }
        urlInput.setText(normalized)
        applyUserAgent(reload = false)
        lab("I", "LOAD_URL", "reason=$reason url=${safeUrl(normalized)}")
        webView.loadUrl(normalized)
    }

    private fun applyUserAgent(reload: Boolean) {
        val index = userAgentSpinner.selectedItemPosition
        val ua = when (index) {
            1 -> chromeLikeAndroidUserAgent(defaultUserAgent)
            2 -> DESKTOP_USER_AGENT
            else -> defaultUserAgent
        }
        webView.settings.userAgentString = ua
        lab("I", "USER_AGENT", "strategy=$index reload=$reload ua=${ua.take(1000)}")
        if (reload) webView.reload()
    }

    private fun currentUserAgent(): String = if (::userAgentSpinner.isInitialized) {
        when (userAgentSpinner.selectedItemPosition) {
            1 -> chromeLikeAndroidUserAgent(defaultUserAgent)
            2 -> DESKTOP_USER_AGENT
            else -> defaultUserAgent
        }
    } else defaultUserAgent

    private fun chromeLikeAndroidUserAgent(input: String): String =
        input.replace("; wv", "")
            .replace("Version/4.0 ", "")
            .ifBlank { "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 Chrome/151.0.0.0 Mobile Safari/537.36" }

    private fun installProbe(reason: String) {
        val seq = evaluateSequence.incrementAndGet()
        lab("I", "PROBE_INSTALL", "reason=$reason seq=$seq url=${safeUrl(webView.url)}")
        webView.evaluateJavascript(AiStudioBridgeLabScripts.INSTALL) { result ->
            lab("I", "EVAL_RESULT", "name=install seq=$seq result=${result.orEmpty().take(16_000)}")
        }
    }

    private fun testJsBridge() {
        val marker = "ANDROID_JS_BRIDGE_${System.currentTimeMillis()}"
        val script = """
            (function(){
              var data={marker:${JSONObject.quote(marker)},href:location.href,title:document.title,ready:document.readyState};
              try { window.$JS_BRIDGE_NAME.onJsEvent(JSON.stringify({kind:'ROUNDTRIP',payload:data})); }
              catch(e) { return JSON.stringify({ok:false,error:String(e),data:data}); }
              return JSON.stringify({ok:true,data:data});
            })();
        """.trimIndent()
        val seq = evaluateSequence.incrementAndGet()
        lab("I", "JS_ROUNDTRIP_START", "seq=$seq marker=$marker")
        webView.evaluateJavascript(script) { result ->
            lab("I", "JS_ROUNDTRIP_RESULT", "seq=$seq result=${result.orEmpty().take(16_000)}")
            status("Test cầu JS đã trả kết quả. Xem log ROUNDTRIP.")
        }
    }

    private fun eval(name: String, expression: String) {
        val seq = evaluateSequence.incrementAndGet()
        val script = AiStudioBridgeLabScripts.call(expression)
        lab("I", "EVAL_START", "name=$name seq=$seq expression=${expression.take(1000)}")
        webView.evaluateJavascript(script) { result ->
            lab("I", "EVAL_RESULT", "name=$name seq=$seq result=${result.orEmpty().take(20_000)}")
        }
    }

    private fun fillPromptSemantic() {
        val prompt = promptInput.text.toString()
        eval("fill-semantic", "window.__AIS_LAB__.fillSemantic(${JSONObject.quote(prompt)})")
    }

    private fun fillPromptExecCommand() {
        val prompt = promptInput.text.toString()
        eval("fill-exec", "window.__AIS_LAB__.fillExecCommand(${JSONObject.quote(prompt)})")
    }

    private fun runAutoStrategyA() {
        lab("I", "AUTO_A_START", "fill=semantic send=button observer=yes promptChars=${promptInput.text.length}")
        installProbe("auto-a")
        webView.postDelayed({ eval("auto-a-hooks", "window.__AIS_LAB__.installNetworkHooks()") }, 300)
        webView.postDelayed({ eval("auto-a-observer", "window.__AIS_LAB__.installObserver()") }, 600)
        webView.postDelayed({ fillPromptSemantic() }, 900)
        webView.postDelayed({ eval("auto-a-send", "window.__AIS_LAB__.sendByButton()") }, 1_400)
        scheduleResponseReads("AUTO_A")
    }

    private fun runAutoStrategyB() {
        lab("I", "AUTO_B_START", "fill=execCommand send=form->enter fallback observer=yes promptChars=${promptInput.text.length}")
        installProbe("auto-b")
        webView.postDelayed({ eval("auto-b-hooks", "window.__AIS_LAB__.installNetworkHooks()") }, 300)
        webView.postDelayed({ eval("auto-b-observer", "window.__AIS_LAB__.installObserver()") }, 600)
        webView.postDelayed({ fillPromptExecCommand() }, 900)
        webView.postDelayed({ eval("auto-b-form", "window.__AIS_LAB__.sendByForm()") }, 1_400)
        webView.postDelayed({ eval("auto-b-enter-fallback", "window.__AIS_LAB__.sendByEnter()") }, 2_200)
        scheduleResponseReads("AUTO_B")
    }

    private fun prepareManualObservation() {
        lab("I", "MANUAL_OBSERVE_START", "No automatic fill/send. User can interact directly with AI Studio while hooks record activity.")
        installProbe("manual-observation")
        webView.postDelayed({ eval("manual-hooks", "window.__AIS_LAB__.installNetworkHooks()") }, 300)
        webView.postDelayed({ eval("manual-observer", "window.__AIS_LAB__.installObserver()") }, 600)
        webView.postDelayed({ eval("manual-dom", "window.__AIS_LAB__.domScan()") }, 900)
        status("Chế độ quan sát thủ công đã bật. Hãy thao tác trực tiếp trong AI Studio.")
    }

    private fun scheduleResponseReads(prefix: String) {
        listOf(2_500L, 5_000L, 8_000L, 12_000L, 18_000L, 25_000L).forEachIndexed { index, delay ->
            webView.postDelayed({
                if (!destroyed) {
                    eval("${prefix.lowercase(Locale.US)}-response-${index + 1}", "window.__AIS_LAB__.readResponse()")
                    if (index == 2 || index == 5) eval("${prefix.lowercase(Locale.US)}-resources-${index + 1}", "window.__AIS_LAB__.resourceScan()")
                }
            }, delay)
        }
    }

    private fun saveSnapshot() {
        val summary = diagnosticSummary()
        labLog.snapshot("android-state", summary)
        val script = """
            (function(){
              try {
                var data = window.__AIS_LAB__ ? {env:window.__AIS_LAB__.environment(),dom:window.__AIS_LAB__.domScan(),response:window.__AIS_LAB__.readResponse(),resources:window.__AIS_LAB__.resourceScan()} : {error:'probe-not-installed'};
                return JSON.stringify(data);
              } catch(e) { return JSON.stringify({error:String(e),stack:String(e && e.stack || '')}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            val normalized = result.orEmpty().take(900_000)
            labLog.snapshot("javascript-state", normalized)
            lab("I", "SNAPSHOT_COMPLETE", "jsChars=${normalized.length}")
            status("Đã lưu snapshot Android + JavaScript vào bundle lab.")
        }
    }

    private fun shareLabBundle() {
        saveSnapshot()
        webView.postDelayed({
            runCatching {
                val bundle = labLog.createBundle(diagnosticSummary())
                val uri = FileProvider.getUriForFile(this, "$packageName.files", bundle)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("AI Studio Bridge Lab diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                lab("I", "SHARE_BUNDLE", "file=${bundle.name} bytes=${bundle.length()}")
                startActivity(Intent.createChooser(intent, "Chia sẻ nhật ký AI Studio Bridge Lab"))
            }.onFailure { labLog.exception("SHARE_BUNDLE_ERROR", it) }
        }, 800)
    }

    private fun requestMediaPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        lab("I", "MEDIA_PERMISSION_BUTTON", "missing=${missing.joinToString()}")
        if (missing.isEmpty()) status("Mic và camera đã được cấp quyền cho APK.")
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredAndroidPermissions(resources: Array<String>): List<String> = buildList {
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add(Manifest.permission.RECORD_AUDIO)
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add(Manifest.permission.CAMERA)
    }

    private fun openExternalBrowser() {
        val uri = Uri.parse(webView.url ?: urlInput.text.toString().ifBlank { HOME_URL })
        lab("I", "OPEN_EXTERNAL", "url=${safeUrl(uri.toString())}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { labLog.exception("OPEN_EXTERNAL_ERROR", it) }
    }

    private fun logNetworkRequest(request: WebResourceRequest, popup: Boolean) {
        val uri = request.url ?: return
        val host = uri.host.orEmpty().lowercase(Locale.US)
        val path = uri.path.orEmpty()
        val key = "$host$path"
        val count = requestCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        val important = host.contains("aistudio") || host.contains("googleapis") || host.contains("generativelanguage") ||
            host.contains("accounts.google") || path.contains("stream", ignoreCase = true) || path.contains("generate", ignoreCase = true) ||
            path.contains("live", ignoreCase = true) || path.contains("socket", ignoreCase = true)
        val milestone = count <= 2 || count == 5 || count == 10 || count == 25 || count == 50 || count % 100 == 0
        if (!important && !milestone) return
        val headers = request.requestHeaders.entries.joinToString(limit = 40) { (name, value) ->
            when (name.lowercase(Locale.US)) {
                "cookie", "authorization", "x-goog-api-key" -> "$name=<${value.length} chars>"
                else -> "$name=${value.take(260)}"
            }
        }
        lab(
            if (important) "I" else "D",
            "NET_REQUEST",
            "popup=$popup count=$count main=${request.isForMainFrame} method=${request.method} gesture=${request.hasGesture()} url=${safeUrl(uri.toString())} headers={$headers}",
        )
    }

    private fun logCookieState(reason: String) {
        val rawUrl = webView.url ?: urlInput.text.toString().ifBlank { HOME_URL }
        val cookie = runCatching { CookieManager.getInstance().getCookie(rawUrl) }.getOrNull().orEmpty()
        val metadata = cookie.split(';').mapNotNull { segment ->
            val trimmed = segment.trim()
            if (trimmed.isBlank()) null else {
                val idx = trimmed.indexOf('=')
                val name = if (idx >= 0) trimmed.substring(0, idx) else trimmed
                val valueLength = if (idx >= 0) trimmed.length - idx - 1 else 0
                "${name.take(80)}(${valueLength})"
            }
        }
        lab(
            "I",
            "COOKIE_STATE",
            "reason=$reason url=${safeUrl(rawUrl)} count=${metadata.size} namesAndValueLengths=${metadata.take(80).joinToString()}",
        )
    }

    private fun logWebViewEnvironment(reason: String) {
        val pkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        lab(
            "I",
            "WEBVIEW_ENV",
            "reason=$reason package=${pkg?.packageName} version=${pkg?.versionName} defaultUA=${defaultUserAgent.take(1200)}",
        )
    }

    private fun diagnosticSummary(): String {
        val runtime = Runtime.getRuntime()
        val pkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        return buildString {
            appendLine("AI Studio Browser Bridge Lab - Android state")
            appendLine("url=${safeUrl(webView.url)}")
            appendLine("title=${webView.title.orEmpty()}")
            appendLine("progress=${webView.progress}")
            appendLine("webViewPackage=${pkg?.packageName} ${pkg?.versionName}")
            appendLine("uaStrategy=${userAgentSpinner.selectedItemPosition}")
            appendLine("userAgent=${webView.settings.userAgentString}")
            appendLine("keepAliveInBackground=${keepAliveCheck.isChecked}")
            appendLine("autoProbe=${autoProbeCheck.isChecked}")
            appendLine("canGoBack=${webView.canGoBack()} canGoForward=${webView.canGoForward()}")
            appendLine("popupCount=${popupDialogs.size}")
            appendLine("requestRouteCount=${requestCounts.size}")
            appendLine("jvmUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}")
            appendLine("jvmMaxMb=${runtime.maxMemory() / 1_048_576}")
            appendLine("sessionDir=${labLog.currentSessionDirectory().absolutePath}")
        }
    }

    private fun lifecycleState(): String =
        "finishing=$isFinishing changingConfig=$isChangingConfigurations focus=${hasWindowFocus()} url=${if (::webView.isInitialized) safeUrl(webView.url) else "not-created"}"

    private fun status(text: String) {
        if (destroyed || !::statusView.isInitialized) return
        runOnUiThread {
            if (!destroyed) statusView.text = "Trạng thái: $text"
        }
    }

    private fun lab(level: String, event: String, detail: String) {
        if (!::labLog.isInitialized) return
        labLog.event(level, event, detail)
        appendUi("[$level][$event] ${detail.take(1800)}")
    }

    private fun appendUi(text: String) {
        if (destroyed) return
        runOnUiThread {
            if (destroyed || !::liveLogView.isInitialized) return@runOnUiThread
            uiLog.append(text).append('\n')
            if (uiLog.length > MAX_UI_LOG_CHARS) uiLog.delete(0, uiLog.length - MAX_UI_LOG_CHARS)
            liveLogView.text = uiLog.toString()
        }
    }

    private fun extractUris(data: Intent?): Array<Uri> {
        if (data == null) return emptyArray()
        val out = LinkedHashSet<Uri>()
        data.data?.let(out::add)
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add)
        return out.toTypedArray()
    }

    private fun describeUri(uri: Uri): String {
        var name = ""
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return "{scheme=${uri.scheme} authority=${uri.authority} name=${name.take(200)} size=$size mime=${contentResolver.getType(uri).orEmpty()}}"
    }

    /** URL logger intentionally keeps host/path/query-key names but not query values. */
    private fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val uri = Uri.parse(raw)
            if (uri.scheme == "data") return@runCatching "data:<${raw.length} chars>"
            val keys = runCatching { uri.queryParameterNames }.getOrDefault(emptySet())
            buildString {
                append(uri.scheme.orEmpty()).append("://").append(uri.host.orEmpty())
                if (uri.port != -1) append(':').append(uri.port)
                append(uri.encodedPath.orEmpty().take(1200))
                if (keys.isNotEmpty()) append("?").append(keys.take(60).joinToString("&") { "$it=<value>" })
                if (!uri.fragment.isNullOrBlank()) append("#<fragment>")
            }.take(2_000)
        }.getOrElse { raw.take(2_000) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val JS_BRIDGE_NAME = "AIStudioLab"
        private const val HOME_URL = "https://aistudio.google.com/"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_PROMPT = "Chỉ trả lời đúng chuỗi sau, không thêm bất kỳ nội dung nào khác: AIS_BRIDGE_TEST_OK_20260901"
        private const val MAX_UI_LOG_CHARS = 36_000
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    }
}
