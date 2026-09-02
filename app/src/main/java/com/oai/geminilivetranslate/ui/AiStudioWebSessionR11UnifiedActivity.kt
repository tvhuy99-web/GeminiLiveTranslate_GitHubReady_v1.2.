package com.oai.geminilivetranslate.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R11.1 unified device lab.
 *
 * Three production prerequisites are tested in one place:
 * 1) real Google/AI Studio authentication with the WebView hidden after READY;
 * 2) dynamic model catalog + request-layer model selection verified on GenerateContent;
 * 3) one Android Uri delivered to AI Studio through a trusted WebView activation pulse.
 */
class AiStudioWebSessionR11UnifiedActivity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private data class SelectedFile(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
    )

    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var authState: TextView
    private lateinit var webContainer: LinearLayout
    private lateinit var modelSpinner: AccessibleSpinner
    private lateinit var modelState: TextView
    private lateinit var fileState: TextView
    private lateinit var promptInput: EditText
    private lateinit var resultView: TextView
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var modelAdapter: ArrayAdapter<String>

    private val main = Handler(Looper.getMainLooper())
    private val uiLog = StringBuilder()
    private val modelIds = mutableListOf<String>()
    private var selectedFile: SelectedFile? = null
    private var userKeepsWebVisible = false
    private var e2eStartedAt = 0L
    private var lastFileChooserServedAt = 0L

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            log("R11_FILE_PICK_CANCEL", "user cancelled picker")
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val meta = readFileMetadata(uri)
        selectedFile = meta
        fileState.text = "Tệp: ${meta.name}\nMIME: ${meta.mime}\nKích thước: ${meta.size} byte"
        log("R11_FILE_SELECTED", "name=${safe(meta.name, 260)} mime=${safe(meta.mime, 160)} size=${meta.size} uriScheme=${uri.scheme.orEmpty()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installR11Scripts()
        installFileChooserBridge()
        buildUi()
        log(
            "R11_ACTIVITY_CREATE",
            "version=$VERSION executor=${AiStudioWebSessionExecutor.VERSION} support=${AiStudioWebSessionR11Support.VERSION} fix=${AiStudioWebSessionR11RequestFix.VERSION}",
        )
        executor.start()
    }

    override fun onDestroy() {
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread {
            stateView.text = "Executor: $state | $detail"
            when (state) {
                AiStudioWebSessionExecutor.State.READY -> {
                    authState.text = "Tài khoản: đã đăng nhập\nAI Studio: sẵn sàng"
                    if (!userKeepsWebVisible) webContainer.isVisible = false
                    ensureRequestFix()
                    probeSession()
                    refreshModels()
                }
                AiStudioWebSessionExecutor.State.WAITING_FOR_CONTROLLER -> {
                    authState.text = "AI Studio chưa sẵn sàng. Nếu chưa đăng nhập, bấm Đăng nhập / đổi tài khoản."
                }
                else -> Unit
            }
        }
        log("R11_EXECUTOR_STATE", "state=$state detail=${safe(detail, 2000)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) {
        log(name, detail)
    }

    private fun installR11Scripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R11_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionR11Support.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            executor.webView,
            AiStudioWebSessionR11RequestFix.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        log(
            "R11_DOCUMENT_START_REGISTERED",
            "origin=$AI_STUDIO_ORIGIN support=${AiStudioWebSessionR11Support.VERSION} fix=${AiStudioWebSessionR11RequestFix.VERSION}",
        )
    }

    private fun installFileChooserBridge() {
        executor.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val selected = selectedFile
                val accept = fileChooserParams?.acceptTypes.orEmpty().joinToString(",").take(1000)
                log(
                    "R11_FILE_CHOOSER_REQUEST",
                    "hasSelected=${selected != null} accept=${safe(accept, 1000)} capture=${fileChooserParams?.isCaptureEnabled == true} mode=${fileChooserParams?.mode ?: -1}",
                )
                if (filePathCallback == null) return false
                if (selected == null) {
                    filePathCallback.onReceiveValue(null)
                    log("R11_FILE_CHOOSER_REJECTED", "reason=NO_SELECTED_FILE")
                    return true
                }
                lastFileChooserServedAt = SystemClock.uptimeMillis()
                filePathCallback.onReceiveValue(arrayOf(selected.uri))
                evalJson(
                    "window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.markFileChooserServed(" +
                        JSONObject.quote(selected.name) + "," + JSONObject.quote(selected.mime) + "," + selected.size + ") : ({ok:false,error:'r11-support-not-installed'})",
                ) { decoded -> log("R11_FILE_CHOOSER_SERVED_ACK", decoded) }
                log("R11_FILE_CHOOSER_SERVED", "name=${safe(selected.name, 260)} mime=${safe(selected.mime, 160)} size=${selected.size}")
                return true
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(TextView(this).apply {
            text = "AI STUDIO WEB SESSION R11"
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "AI Studio Web Session R11"
        }, fullWidth())

        root.addView(section("1. Tài khoản"))
        authState = TextView(this).apply {
            text = "Tài khoản: đang kiểm tra phiên..."
            textSize = 16f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(authState, fullWidth())
        root.addView(actionButton("Đăng nhập / đổi tài khoản") {
            userKeepsWebVisible = true
            webContainer.alpha = 1f
            webContainer.isVisible = true
            authState.text = "Đang mở trang xác thực Google / AI Studio thật..."
            log("R11_AUTH_UI_SHOW", "reason=user-request url=${safeUrl(executor.webView.url)}")
            executor.start()
        }, fullWidth())
        root.addView(actionButton("Ẩn trang xác thực") {
            userKeepsWebVisible = false
            webContainer.isVisible = false
            log("R11_AUTH_UI_HIDE", "user hidden WebView")
        }, fullWidth())
        root.addView(actionButton("Kiểm tra phiên đăng nhập") { probeSession() }, fullWidth())

        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
            addView(executor.webView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            ))
        }
        root.addView(webContainer, fullWidth())

        root.addView(section("2. Mô hình"))
        modelState = TextView(this).apply {
            text = "Chưa lấy danh sách mô hình"
            textSize = 15f
        }
        root.addView(modelState, fullWidth())
        modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelIds).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner = AccessibleSpinner(this).apply {
            adapter = modelAdapter
            minimumHeight = dp(50)
            contentDescription = "Danh sách mô hình AI Studio"
        }
        root.addView(modelSpinner, fullWidth())
        root.addView(actionButton("Tìm / làm mới mô hình") { refreshModels() }, fullWidth())
        root.addView(actionButton("Chọn mô hình đang đánh dấu") {
            selectCurrentModel { ok ->
                toastStatus(if (ok) "Đã chọn model cho request" else "Chưa chọn được model. Xem nhật ký R11_MODEL_SELECT_RESULT.")
            }
        }, fullWidth())

        root.addView(section("3. Tệp"))
        fileState = TextView(this).apply {
            text = "Chưa chọn tệp"
            textSize = 15f
        }
        root.addView(fileState, fullWidth())
        root.addView(actionButton("Chọn một tệp") {
            pickFile.launch(arrayOf("video/*", "audio/*", "image/*", "application/pdf", "text/*"))
        }, fullWidth())
        root.addView(actionButton("Gắn tệp đã chọn vào AI Studio") {
            attachSelectedFile { ready ->
                toastStatus(if (ready) "AI Studio đã nhận tệp" else "Chưa xác nhận tệp sẵn sàng")
            }
        }, fullWidth())

        root.addView(section("Bài thử kết hợp R11"))
        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 3
            maxLines = 8
            contentDescription = "Prompt kiểm tra video R11"
        }
        root.addView(promptInput, fullWidth())
        root.addView(Button(this).apply {
            text = "CHẠY R11: MODEL + TỆP + TÓM TẮT VIDEO"
            isAllCaps = false
            minimumHeight = dp(60)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            contentDescription = "Chạy bài thử kết hợp R11"
            setOnClickListener { runEndToEnd() }
        }, fullWidth())

        resultView = TextView(this).apply {
            text = "Kết quả: chưa chạy"
            textSize = 15f
            setTextIsSelectable(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(resultView, fullWidth())

        stateView = TextView(this).apply {
            text = "Executor: NEW"
            setTextIsSelectable(true)
        }
        root.addView(stateView, fullWidth())

        root.addView(actionButton("Mở / chia sẻ nhật ký AI Studio") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        val logScroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký R11 trực tiếp"
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(220),
        ))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun ensureRequestFix() {
        evalJson(
            "window.__AIS_R11_REQUEST_FIX__ ? ({ok:window.__AIS_R11_REQUEST_FIX__.ensureInstalled(),state:window.__AIS_R11_REQUEST_FIX__.state()}) : ({ok:false,error:'r11-request-fix-not-installed'})",
        ) { log("R11_REQUEST_FIX_NATIVE", it) }
    }

    private fun probeSession() {
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.probeSession() : ({ok:false,error:'r11-support-not-installed'})") { decoded ->
            log("R11_AUTH_PROBE_NATIVE", decoded)
            val obj = jsonObject(decoded)
            val sessionState = obj?.optString("state").orEmpty()
            val ready = obj?.optBoolean("controllerReady") == true
            authState.text = when {
                ready -> "Tài khoản: đã đăng nhập\nAI Studio: sẵn sàng"
                sessionState == "AUTH_REQUIRED" -> "Tài khoản: cần đăng nhập\nBấm Đăng nhập / đổi tài khoản để mở xác thực Google thật."
                else -> "Phiên: $sessionState\nURL: ${safeUrl(executor.webView.url)}"
            }
        }
    }

    private fun refreshModels() {
        ensureRequestFix()
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.discoverModels() : ({ok:false,error:'r11-support-not-installed'})") { decoded ->
            log("R11_MODEL_DISCOVERY_NATIVE", decoded)
            val obj = jsonObject(decoded)
            val array = obj?.optJSONArray("models") ?: JSONArray()
            val discovered = buildList {
                for (i in 0 until array.length()) {
                    val id = array.optJSONObject(i)?.optString("id").orEmpty().trim()
                    if (isVisibleModelCandidate(id) && id !in this) add(id)
                }
            }
            if (discovered.isNotEmpty()) {
                val previous = modelIds.getOrNull(modelSpinner.selectedItemPosition)
                modelIds.clear()
                modelIds.addAll(discovered)
                modelAdapter.notifyDataSetChanged()
                val restore = previous?.let(modelIds::indexOf)?.takeIf { it >= 0 } ?: 0
                modelSpinner.setSelection(restore)
                modelState.text = "Tìm thấy ${modelIds.size} mô hình từ phiên AI Studio"
                log("R11_MODEL_CATALOG_NATIVE", "count=${modelIds.size} ids=${modelIds.take(100).joinToString(",")}")
            } else {
                modelState.text = "Chưa bắt được catalog mô hình"
                main.postDelayed({ if (executor.currentState() == AiStudioWebSessionExecutor.State.READY) refreshModels() }, 900L)
            }
        }
    }

    private fun isVisibleModelCandidate(id: String): Boolean {
        if (!id.startsWith("gemini-", ignoreCase = true)) return false
        if (id.equals("gemini-api", ignoreCase = true)) return false
        if (id.endsWith("-launch-promo", ignoreCase = true)) return false
        return true
    }

    private fun selectCurrentModel(onDone: (Boolean) -> Unit) {
        val model = modelIds.getOrNull(modelSpinner.selectedItemPosition).orEmpty()
        if (model.isBlank()) {
            log("R11_MODEL_SELECT_REJECTED", "reason=NO_MODEL")
            onDone(false)
            return
        }
        ensureRequestFix()
        log("R11_MODEL_SELECT_NATIVE_START", "modelId=$model path=request-layer")
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.selectModel(${JSONObject.quote(model)}) : ({ok:false,error:'r11-support-not-installed'})") { decoded ->
            log("R11_MODEL_SELECT_NATIVE_DISPATCH", decoded)
            main.postDelayed({ verifyModelSelection(model, 1, onDone) }, 120L)
        }
    }

    private fun verifyModelSelection(model: String, attempt: Int, onDone: (Boolean) -> Unit) {
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.selectionState() : ({ok:false,error:'r11-support-not-installed'})") { decoded ->
            val obj = jsonObject(decoded)
            val selected = obj?.optString("selectedModel").orEmpty()
            val path = obj?.optString("path").orEmpty()
            val ok = selected == model
            modelState.text = if (ok) "Đã chọn cho request: $model" else "Đang xác nhận model: $model"
            log("R11_MODEL_SELECT_VERIFY", "attempt=$attempt expected=$model selected=${safe(selected, 160)} path=${safe(path, 80)} ok=$ok raw=${safe(decoded, 4000)}")
            if (!ok && attempt < 4) main.postDelayed({ verifyModelSelection(model, attempt + 1, onDone) }, 220L)
            else onDone(ok)
        }
    }

    private fun attachSelectedFile(onDone: (Boolean) -> Unit) {
        val selected = selectedFile
        if (selected == null) {
            log("R11_ATTACH_REJECTED", "reason=NO_SELECTED_FILE")
            onDone(false)
            return
        }
        ensureRequestFix()
        val attachStartedAt = SystemClock.uptimeMillis()
        log("R11_ATTACH_NATIVE_START", "name=${safe(selected.name, 260)} mime=${safe(selected.mime, 160)} size=${selected.size} strategy=trusted-activation")

        // Preserve R11 support's attachment-state reset. Its programmatic click is harmless if blocked.
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.attachFile() : ({ok:false,error:'r11-support-not-installed'})") { resetDecoded ->
            log("R11_ATTACH_RESET_DISPATCH", resetDecoded)
            main.postDelayed({
                if (lastFileChooserServedAt >= attachStartedAt) {
                    log("R11_FILE_ACTIVATION_SKIP", "reason=CHOOSER_ALREADY_SERVED")
                    pollAttachment(selected, 1, onDone)
                    return@postDelayed
                }
                evalJson("window.__AIS_R11_SUPPORT__ && window.__AIS_R11_SUPPORT__.armTrustedFileChooser ? window.__AIS_R11_SUPPORT__.armTrustedFileChooser() : ({ok:false,error:'trusted-file-arm-not-installed'})") { armDecoded ->
                    log("R11_FILE_ACTIVATION_ARM_NATIVE", armDecoded)
                    val arm = jsonObject(armDecoded)
                    if (arm?.optBoolean("ok") != true) {
                        onDone(false)
                        return@evalJson
                    }
                    dispatchTrustedFileActivationPulse {
                        pollAttachment(selected, 1, onDone)
                    }
                }
            }, 120L)
        }
    }

    private fun dispatchTrustedFileActivationPulse(afterDispatch: () -> Unit) {
        val wasVisible = webContainer.isVisible
        val previousAlpha = webContainer.alpha
        webContainer.alpha = 0f
        webContainer.isVisible = true
        webContainer.requestLayout()

        executor.webView.postDelayed({
            val web = executor.webView
            val width = web.width
            val height = web.height
            if (width <= 2 || height <= 2) {
                log("R11_FILE_ACTIVATION_PULSE", "ok=false reason=WEBVIEW_NOT_LAID_OUT width=$width height=$height")
                restoreWebVisibility(wasVisible, previousAlpha)
                afterDispatch()
                return@postDelayed
            }

            val x = width / 2f
            val y = height / 2f
            val downAt = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val up = MotionEvent.obtain(downAt, downAt + 70L, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val downHandled = runCatching { web.dispatchTouchEvent(down) }.getOrDefault(false)
            val upHandled = runCatching { web.dispatchTouchEvent(up) }.getOrDefault(false)
            down.recycle()
            up.recycle()
            log(
                "R11_FILE_ACTIVATION_PULSE",
                "ok=${downHandled || upHandled} trustedAndroidTouch=true x=${x.toInt()} y=${y.toInt()} width=$width height=$height downHandled=$downHandled upHandled=$upHandled",
            )
            main.postDelayed({
                restoreWebVisibility(wasVisible, previousAlpha)
                afterDispatch()
            }, 350L)
        }, 100L)
    }

    private fun restoreWebVisibility(wasVisible: Boolean, previousAlpha: Float) {
        webContainer.alpha = previousAlpha
        if (!wasVisible && !userKeepsWebVisible) webContainer.isVisible = false
    }

    private fun pollAttachment(selected: SelectedFile, attempt: Int, onDone: (Boolean) -> Unit) {
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.attachmentState(${JSONObject.quote(selected.name)}) : ({ok:false,error:'r11-support-not-installed'})") { decoded ->
            val obj = jsonObject(decoded)
            val ready = obj?.optBoolean("ready") == true
            val chooser = obj?.optBoolean("fileChooserServed") == true
            val active = obj?.optInt("activeUploads", 0) ?: 0
            val completed = obj?.optInt("uploadCompleted", 0) ?: 0
            val nameVisible = obj?.optBoolean("nameVisible") == true
            fileState.text = "Tệp: ${selected.name}\nchooser=$chooser uploadActive=$active uploadDone=$completed visible=$nameVisible ready=$ready"
            log("R11_ATTACHMENT_POLL", "attempt=$attempt ready=$ready chooser=$chooser active=$active completed=$completed nameVisible=$nameVisible raw=${safe(decoded, 6000)}")
            when {
                ready -> {
                    log("R11_ATTACHMENT_READY", "attempt=$attempt name=${safe(selected.name, 260)} completed=$completed nameVisible=$nameVisible")
                    onDone(true)
                }
                attempt >= 40 -> {
                    log("R11_ATTACHMENT_TIMEOUT", "name=${safe(selected.name, 260)} last=${safe(decoded, 6000)}")
                    onDone(false)
                }
                else -> main.postDelayed({ pollAttachment(selected, attempt + 1, onDone) }, 650L)
            }
        }
    }

    private fun runEndToEnd() {
        if (executor.currentState() != AiStudioWebSessionExecutor.State.READY) {
            resultView.text = "R11 ERROR: AI Studio chưa READY"
            log("R11_E2E_REJECTED", "reason=EXECUTOR_NOT_READY state=${executor.currentState()}")
            probeSession()
            return
        }
        val selected = selectedFile
        if (selected == null) {
            resultView.text = "R11 ERROR: chưa chọn tệp"
            log("R11_E2E_REJECTED", "reason=NO_FILE")
            return
        }
        if (modelIds.isEmpty()) {
            resultView.text = "R11 ERROR: chưa có model"
            log("R11_E2E_REJECTED", "reason=NO_MODEL")
            refreshModels()
            return
        }
        val expectedModel = modelIds.getOrNull(modelSpinner.selectedItemPosition).orEmpty()
        e2eStartedAt = System.currentTimeMillis()
        resultView.text = "R11: đang chọn model cho request..."
        log("R11_E2E_START", "model=$expectedModel file=${safe(selected.name, 260)} mime=${safe(selected.mime, 160)} size=${selected.size} promptChars=${promptInput.text.length}")

        selectCurrentModel { modelOk ->
            if (!modelOk) {
                resultView.text = "R11 ERROR: không xác nhận được model $expectedModel"
                log("R11_E2E_FAIL", "phase=MODEL_SELECTION model=$expectedModel")
                return@selectCurrentModel
            }
            resultView.text = "R11: đang cấp tệp cho AI Studio..."
            attachSelectedFile { attachmentOk ->
                if (!attachmentOk) {
                    resultView.text = "R11 ERROR: AI Studio chưa xác nhận tệp sẵn sàng"
                    log("R11_E2E_FAIL", "phase=ATTACHMENT file=${safe(selected.name, 260)}")
                    return@attachSelectedFile
                }
                resultView.text = "R11: đang gửi prompt và chờ mô hình..."
                val prompt = promptInput.text.toString().trim().ifBlank { DEFAULT_PROMPT }
                val accepted = executor.generate(prompt, marker = "", timeoutMs = 90_000L) { result ->
                    runOnUiThread {
                        resultView.text = if (result.ok) {
                            "HTTP ${result.status} | complete=${result.complete}\n${result.modelText}"
                        } else {
                            "R11 ERROR: ${result.error}"
                        }
                    }
                    evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.selectionState() : ({ok:false,error:'r11-support-not-installed'})") { selectionRaw ->
                        val selection = jsonObject(selectionRaw)
                        val observed = selection?.optString("observedGenerateModel").orEmpty()
                        val rewriteCount = selection?.optInt("rewriteCount", 0) ?: 0
                        val modelVerified = observed == expectedModel && rewriteCount > 0
                        val elapsed = System.currentTimeMillis() - e2eStartedAt
                        log(
                            "R11_E2E_RESULT",
                            "ok=${result.ok} status=${result.status} complete=${result.complete} expectedModel=$expectedModel observedModel=${safe(observed, 160)} rewriteCount=$rewriteCount modelVerified=$modelVerified resultChars=${result.modelText.length} elapsedMs=$elapsed error=${safe(result.error, 500)}",
                        )
                        labLog.snapshot(
                            "r11-e2e-summary",
                            "version=$VERSION\nexpectedModel=$expectedModel\nobservedModel=$observed\nrewriteCount=$rewriteCount\nmodelVerified=$modelVerified\nfileName=${selected.name}\nfileMime=${selected.mime}\nfileSize=${selected.size}\nhttpStatus=${result.status}\nok=${result.ok}\ncomplete=${result.complete}\nresultChars=${result.modelText.length}\nelapsedMs=$elapsed\nerror=${result.error}\n",
                        )
                    }
                }
                if (!accepted) {
                    resultView.text = "R11 ERROR: executor từ chối generate"
                    log("R11_E2E_FAIL", "phase=GENERATE_DISPATCH state=${executor.currentState()}")
                }
            }
        }
    }

    private fun readFileMetadata(uri: Uri): SelectedFile {
        var name = uri.lastPathSegment ?: "selected-file"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { name }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        return SelectedFile(uri = uri, name = name, mime = mime, size = size)
    }

    private fun evalJson(expression: String, callback: (String) -> Unit) {
        executor.webView.evaluateJavascript("JSON.stringify($expression)") { raw -> callback(decodeEvalValue(raw)) }
    }

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun jsonObject(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

    private fun log(name: String, detail: String) {
        labLog.event("I", name, detail)
        runOnUiThread {
            if (::logView.isInitialized) {
                if (uiLog.length > 34_000) uiLog.delete(0, uiLog.length - 24_000)
                uiLog.append('[').append(name).append("] ").append(detail.take(4_000)).append('\n')
                logView.text = uiLog.toString()
            }
        }
    }

    private fun safeUrl(raw: String?): String {
        val uri = runCatching { Uri.parse(raw.orEmpty()) }.getOrNull()
        return if (uri == null) "" else "${uri.scheme.orEmpty()}://${uri.host.orEmpty()}${uri.path.orEmpty()}".take(700)
    }

    private fun safe(text: String, max: Int): String = text.replace('\u0000', ' ').replace('\n', ' ').take(max)

    private fun toastStatus(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, dp(14), 0, dp(5))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minimumHeight = dp(52)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(4) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VERSION = "2026-09-02-web-session-r11.1-unified"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val DEFAULT_PROMPT = "Hãy xem toàn bộ video này và tóm tắt chi tiết nội dung, nhân vật, hành động, bối cảnh và các diễn biến chính."
    }
}
