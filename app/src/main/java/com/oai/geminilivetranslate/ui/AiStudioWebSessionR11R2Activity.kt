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
 * R11.2 device lab.
 *
 * It keeps the proven R10/R11.1 layers and fixes the three device findings from R11.1:
 * 1) model selection is verified by a real text-only GenerateContent before any attachment exists;
 * 2) a visible filename is no longer enough to call an attachment ready. The composer must remain
 *    stable, not busy, and expose a ready prompt controller for several consecutive polls;
 * 3) after attachment stabilization the adaptive controller is explicitly rediscovered and must
 *    become READY again before the video prompt is dispatched. A bounded recovery retry is allowed
 *    only for NO_HANDLER_TRIGGERED_REQUEST.
 */
class AiStudioWebSessionR11R2Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
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
    private var lastPreflightVerified = false
    private var lastPreflightRewriteCount = 0

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
            text = "AI STUDIO WEB SESSION R11.2"
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "AI Studio Web Session R11.2"
        }, fullWidth())

        root.addView(section("1. Tài khoản"))
        authState = TextView(this).apply {
            text = "Tài khoản: đang kiểm tra phiên..."
            textSize = 16f
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
            addView(
                executor.webView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)),
            )
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
                toastStatus(if (ok) "Đã chọn model cho request" else "Chưa chọn được model")
            }
        }, fullWidth())
        root.addView(actionButton("Kiểm tra model bằng prompt text") {
            val model = selectedModelId()
            if (model.isBlank()) {
                toastStatus("Chưa có model")
            } else {
                selectCurrentModel { selectedOk ->
                    if (selectedOk) verifySelectedModelWithText(model) { verified, _ ->
                        toastStatus(if (verified) "Model đã được xác minh bằng request thật" else "Model chưa được xác minh")
                    }
                }
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
        root.addView(actionButton("Gắn tệp và chờ composer ổn định") {
            attachSelectedFileStrict { ready ->
                toastStatus(if (ready) "Attachment đã ổn định" else "Attachment chưa ổn định")
            }
        }, fullWidth())

        root.addView(section("Bài thử kết hợp R11.2"))
        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 3
            maxLines = 8
            contentDescription = "Prompt kiểm tra video R11.2"
        }
        root.addView(promptInput, fullWidth())
        root.addView(Button(this).apply {
            text = "CHẠY R11.2: VERIFY MODEL + TỆP + TÓM TẮT VIDEO"
            isAllCaps = false
            minimumHeight = dp(60)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            contentDescription = "Chạy bài thử kết hợp R11.2"
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
            contentDescription = "Nhật ký R11.2 trực tiếp"
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

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

    private fun selectedModelId(): String = modelIds.getOrNull(modelSpinner.selectedItemPosition).orEmpty()

    private fun selectCurrentModel(onDone: (Boolean) -> Unit) {
        val model = selectedModelId()
        if (model.isBlank()) {
            log("R11_MODEL_SELECT_REJECTED", "reason=NO_MODEL")
            onDone(false)
            return
        }
        ensureRequestFix()
        log("R11_MODEL_SELECT_NATIVE_START", "modelId=$model path=request-layer")
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.selectModel(${JSONObject.quote(model)}) : ({ok:false,error:'r11-support-not-installed'})") { decoded ->
            log("R11_MODEL_SELECT_NATIVE_DISPATCH", decoded)
            main.postDelayed({ verifyModelSelectionState(model, 1, onDone) }, 120L)
        }
    }

    private fun verifyModelSelectionState(model: String, attempt: Int, onDone: (Boolean) -> Unit) {
        readSelectionState { obj, raw ->
            val selected = obj?.optString("selectedModel").orEmpty()
            val path = obj?.optString("path").orEmpty()
            val ok = selected == model
            modelState.text = if (ok) "Đã chọn cho request: $model" else "Đang xác nhận model: $model"
            log("R11_MODEL_SELECT_VERIFY", "attempt=$attempt expected=$model selected=${safe(selected, 160)} path=${safe(path, 80)} ok=$ok raw=${safe(raw, 4000)}")
            if (!ok && attempt < 4) main.postDelayed({ verifyModelSelectionState(model, attempt + 1, onDone) }, 220L)
            else onDone(ok)
        }
    }

    private fun verifySelectedModelWithText(model: String, onDone: (Boolean, Int) -> Unit) {
        readSelectionState { before, beforeRaw ->
            val baselineRewrite = before?.optInt("rewriteCount", 0) ?: 0
            log("R11_MODEL_PREFLIGHT_START", "model=$model baselineRewrite=$baselineRewrite state=${executor.currentState()} before=${safe(beforeRaw, 3000)}")
            waitForControllerReady("MODEL_PREFLIGHT", 1, 0) { ready ->
                if (!ready) {
                    lastPreflightVerified = false
                    onDone(false, baselineRewrite)
                    return@waitForControllerReady
                }
                val accepted = executor.generate(
                    MODEL_PREFLIGHT_PROMPT,
                    marker = MODEL_PREFLIGHT_MARKER,
                    timeoutMs = 45_000L,
                ) { result ->
                    readSelectionState { after, afterRaw ->
                        val observed = after?.optString("observedGenerateModel").orEmpty()
                        val rewriteCount = after?.optInt("rewriteCount", 0) ?: 0
                        val verified = result.ok && result.complete && result.markerFound && observed == model && rewriteCount > baselineRewrite
                        lastPreflightVerified = verified
                        lastPreflightRewriteCount = rewriteCount
                        modelState.text = if (verified) "Model đã xác minh bằng request thật: $model" else "Model preflight chưa đạt: $model"
                        log(
                            "R11_MODEL_PREFLIGHT_RESULT",
                            "ok=${result.ok} status=${result.status} complete=${result.complete} markerFound=${result.markerFound} expected=$model observed=${safe(observed, 160)} baselineRewrite=$baselineRewrite rewriteCount=$rewriteCount verified=$verified error=${safe(result.error, 500)} after=${safe(afterRaw, 3500)}",
                        )
                        onDone(verified, rewriteCount)
                    }
                }
                if (!accepted) {
                    lastPreflightVerified = false
                    log("R11_MODEL_PREFLIGHT_REJECTED", "state=${executor.currentState()} model=$model")
                    onDone(false, baselineRewrite)
                }
            }
        }
    }

    private fun readSelectionState(callback: (JSONObject?, String) -> Unit) {
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.selectionState() : ({ok:false,error:'r11-support-not-installed'})") { raw ->
            callback(jsonObject(raw), raw)
        }
    }

    private fun attachSelectedFileStrict(onDone: (Boolean) -> Unit) {
        val selected = selectedFile
        if (selected == null) {
            log("R11_ATTACH_REJECTED", "reason=NO_SELECTED_FILE")
            onDone(false)
            return
        }
        ensureRequestFix()
        val attachStartedAt = SystemClock.uptimeMillis()
        log("R11_ATTACH_NATIVE_START", "name=${safe(selected.name, 260)} mime=${safe(selected.mime, 160)} size=${selected.size} strategy=trusted-activation-strict-stability")
        evalJson("window.__AIS_R11_SUPPORT__ ? window.__AIS_R11_SUPPORT__.attachFile() : ({ok:false,error:'r11-support-not-installed'})") { resetDecoded ->
            log("R11_ATTACH_RESET_DISPATCH", resetDecoded)
            main.postDelayed({
                if (lastFileChooserServedAt >= attachStartedAt) {
                    log("R11_FILE_ACTIVATION_SKIP", "reason=CHOOSER_ALREADY_SERVED")
                    pollAttachmentStrict(selected, 1, 0, onDone)
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
                        pollAttachmentStrict(selected, 1, 0, onDone)
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

    private fun strictAttachmentProbeExpression(selected: SelectedFile): String {
        val expected = JSONObject.quote(selected.name)
        return """
            (function(expectedName){
              const support=window.__AIS_R11_SUPPORT__;
              const runtime=window.__AIS_ADAPTIVE_RUNTIME__;
              if(!support)return {ok:false,error:'r11-support-not-installed'};
              const base=support.attachmentState(expectedName)||{};
              const discovery=runtime&&runtime.discover?runtime.discover():{};
              let inputCount=0,fileCount=0,fileMatch=false,inputName='';
              try{
                const inputs=document.querySelectorAll('input[type="file"]');inputCount=inputs.length;
                for(let i=0;i<inputs.length;i++){
                  const files=inputs[i].files;if(!files)continue;fileCount+=files.length;
                  for(let j=0;j<files.length;j++){
                    const n=String(files[j].name||'');if(!inputName)inputName=n;if(n===expectedName)fileMatch=true;
                  }
                }
              }catch(_){}
              let surfaceFound=false,busyVisual=false,surfaceTextChars=0;
              try{
                const nodes=document.querySelectorAll('div,span,[aria-label],[title]');
                let best=null,bestChars=100000000;
                for(let i=0;i<nodes.length&&i<3500;i++){
                  const t=String(nodes[i].textContent||nodes[i].getAttribute&&nodes[i].getAttribute('aria-label')||'');
                  if(t.indexOf(expectedName)>=0&&t.length<bestChars){best=nodes[i];bestChars=t.length;}
                }
                if(best){
                  surfaceFound=true;surfaceTextChars=bestChars;
                  let n=best;
                  for(let depth=0;depth<6&&n;depth++,n=n.parentElement){
                    if(n.getAttribute&&String(n.getAttribute('aria-busy')||'').toLowerCase()==='true')busyVisual=true;
                    if(n.querySelector&&n.querySelector('[aria-busy="true"],[role="progressbar"],progress,mat-progress-spinner,.mat-mdc-progress-spinner'))busyVisual=true;
                  }
                }
              }catch(_){}
              let sendCandidates=0,sendEnabled=null;
              try{
                const buttons=document.querySelectorAll('button,[role="button"]');
                for(let i=0;i<buttons.length&&i<1200;i++){
                  const b=buttons[i];
                  const label=[b.textContent||'',b.getAttribute&&b.getAttribute('aria-label')||'',b.getAttribute&&b.getAttribute('title')||''].join(' ').replace(/\s+/g,' ').trim();
                  if(/(^|\b)(send|run|submit|gửi|chạy)(\b|$)/i.test(label)){
                    sendCandidates+=1;
                    const disabled=!!b.disabled||String(b.getAttribute&&b.getAttribute('aria-disabled')||'').toLowerCase()==='true';
                    if(!disabled)sendEnabled=true;else if(sendEnabled===null)sendEnabled=false;
                  }
                }
              }catch(_){}
              return Object.assign({},base,{
                ok:true,
                controllerReady:!!discovery.controllerReady,
                readyCandidateCount:Number(discovery.readyCandidateCount||0),
                discoveryGeneration:Number(discovery.generation||0),
                inputCount:inputCount,fileCount:fileCount,fileMatch:fileMatch,inputName:String(inputName||'').slice(0,260),
                attachmentSurfaceFound:surfaceFound,attachmentBusyVisual:busyVisual,surfaceTextChars:surfaceTextChars,
                sendCandidates:sendCandidates,sendEnabled:sendEnabled
              });
            })($expected)
        """.trimIndent()
    }

    private fun pollAttachmentStrict(selected: SelectedFile, attempt: Int, stableCount: Int, onDone: (Boolean) -> Unit) {
        evalJson(strictAttachmentProbeExpression(selected)) { decoded ->
            val obj = jsonObject(decoded)
            val chooser = obj?.optBoolean("fileChooserServed") == true
            val nameVisible = obj?.optBoolean("nameVisible") == true
            val busyText = obj?.optBoolean("busy") == true
            val busyVisual = obj?.optBoolean("attachmentBusyVisual") == true
            val controllerReady = obj?.optBoolean("controllerReady") == true
            val readyCandidates = obj?.optInt("readyCandidateCount", 0) ?: 0
            val fileCount = obj?.optInt("fileCount", 0) ?: 0
            val fileMatch = obj?.optBoolean("fileMatch") == true
            val sendCandidates = obj?.optInt("sendCandidates", 0) ?: 0
            val sendEnabled = if (obj?.has("sendEnabled") == true && !obj.isNull("sendEnabled")) obj.optBoolean("sendEnabled") else null
            val elapsed = if (lastFileChooserServedAt > 0L) SystemClock.uptimeMillis() - lastFileChooserServedAt else 0L
            val composerCandidate = chooser && nameVisible && !busyText && !busyVisual && controllerReady && readyCandidates > 0 &&
                (sendCandidates == 0 || sendEnabled != false)
            val nextStable = if (composerCandidate) stableCount + 1 else 0
            val strictReady = composerCandidate && nextStable >= REQUIRED_STABLE_POLLS && elapsed >= MIN_ATTACHMENT_STABLE_MS
            fileState.text = "Tệp: ${selected.name}\nchooser=$chooser visible=$nameVisible fileCount=$fileCount fileMatch=$fileMatch busy=${busyText || busyVisual} controller=$controllerReady stable=$nextStable/$REQUIRED_STABLE_POLLS elapsed=${elapsed}ms"
            log(
                "R11_ATTACHMENT_STRICT_POLL",
                "attempt=$attempt strictReady=$strictReady stable=$nextStable chooser=$chooser nameVisible=$nameVisible busyText=$busyText busyVisual=$busyVisual controllerReady=$controllerReady readyCandidates=$readyCandidates fileCount=$fileCount fileMatch=$fileMatch sendCandidates=$sendCandidates sendEnabled=$sendEnabled elapsedMs=$elapsed raw=${safe(decoded, 7000)}",
            )
            when {
                strictReady -> {
                    log("R11_ATTACHMENT_STABLE_READY", "attempt=$attempt stable=$nextStable elapsedMs=$elapsed name=${safe(selected.name, 260)} fileCount=$fileCount fileMatch=$fileMatch")
                    waitForControllerReady("POST_ATTACHMENT", 1, 0) { rediscovered ->
                        log("R11_ATTACHMENT_CONTROLLER_RESULT", "ready=$rediscovered state=${executor.currentState()}")
                        onDone(rediscovered)
                    }
                }
                attempt >= MAX_ATTACHMENT_POLLS -> {
                    log("R11_ATTACHMENT_TIMEOUT", "strict=true name=${safe(selected.name, 260)} last=${safe(decoded, 7000)}")
                    onDone(false)
                }
                else -> main.postDelayed({ pollAttachmentStrict(selected, attempt + 1, nextStable, onDone) }, ATTACHMENT_POLL_MS)
            }
        }
    }

    private fun waitForControllerReady(phase: String, attempt: Int, stableCount: Int, onDone: (Boolean) -> Unit) {
        executor.refreshDiscovery()
        evalJson("window.__AIS_ADAPTIVE_RUNTIME__ ? window.__AIS_ADAPTIVE_RUNTIME__.discover() : ({ok:false,error:'runtime-not-installed'})") { decoded ->
            val obj = jsonObject(decoded)
            val controllerReady = obj?.optBoolean("controllerReady") == true
            val readyCount = obj?.optInt("readyCandidateCount", 0) ?: 0
            val executorReady = executor.currentState() == AiStudioWebSessionExecutor.State.READY
            val candidate = controllerReady && readyCount > 0 && executorReady
            val nextStable = if (candidate) stableCount + 1 else 0
            val ready = nextStable >= REQUIRED_CONTROLLER_STABLE_POLLS
            log(
                "R11_CONTROLLER_REDISCOVERY_POLL",
                "phase=$phase attempt=$attempt ready=$ready stable=$nextStable controllerReady=$controllerReady readyCount=$readyCount executorState=${executor.currentState()} raw=${safe(decoded, 4500)}",
            )
            when {
                ready -> onDone(true)
                attempt >= MAX_CONTROLLER_POLLS -> {
                    log("R11_CONTROLLER_REDISCOVERY_TIMEOUT", "phase=$phase state=${executor.currentState()} last=${safe(decoded, 4500)}")
                    onDone(false)
                }
                else -> main.postDelayed({ waitForControllerReady(phase, attempt + 1, nextStable, onDone) }, CONTROLLER_POLL_MS)
            }
        }
    }

    private fun runEndToEnd() {
        if (executor.currentState() != AiStudioWebSessionExecutor.State.READY) {
            resultView.text = "R11.2 ERROR: AI Studio chưa READY"
            log("R11_E2E_REJECTED", "reason=EXECUTOR_NOT_READY state=${executor.currentState()}")
            probeSession()
            return
        }
        val selected = selectedFile
        if (selected == null) {
            resultView.text = "R11.2 ERROR: chưa chọn tệp"
            log("R11_E2E_REJECTED", "reason=NO_FILE")
            return
        }
        val expectedModel = selectedModelId()
        if (expectedModel.isBlank()) {
            resultView.text = "R11.2 ERROR: chưa có model"
            log("R11_E2E_REJECTED", "reason=NO_MODEL")
            refreshModels()
            return
        }
        e2eStartedAt = System.currentTimeMillis()
        lastPreflightVerified = false
        lastPreflightRewriteCount = 0
        resultView.text = "R11.2: đang xác minh model bằng request text..."
        log("R11_E2E_START", "model=$expectedModel file=${safe(selected.name, 260)} mime=${safe(selected.mime, 160)} size=${selected.size} promptChars=${promptInput.text.length}")

        selectCurrentModel { modelSelected ->
            if (!modelSelected) {
                failE2e("MODEL_SELECTION", "không xác nhận được model $expectedModel")
                return@selectCurrentModel
            }
            verifySelectedModelWithText(expectedModel) { modelVerified, preflightRewrite ->
                if (!modelVerified) {
                    failE2e("MODEL_PREFLIGHT", "model chưa được xác minh bằng GenerateContent thật")
                    return@verifySelectedModelWithText
                }
                resultView.text = "R11.2: model đã xác minh. Đang chờ executor READY lại..."
                waitForControllerReady("AFTER_MODEL_PREFLIGHT", 1, 0) { readyAfterPreflight ->
                    if (!readyAfterPreflight) {
                        failE2e("AFTER_MODEL_PREFLIGHT", "controller chưa READY lại")
                        return@waitForControllerReady
                    }
                    resultView.text = "R11.2: đang gắn tệp và chờ composer ổn định..."
                    attachSelectedFileStrict { attachmentReady ->
                        if (!attachmentReady) {
                            failE2e("ATTACHMENT_STABILITY", "attachment/composer chưa ổn định")
                            return@attachSelectedFileStrict
                        }
                        val prompt = promptInput.text.toString().trim().ifBlank { DEFAULT_PROMPT }
                        resultView.text = "R11.2: attachment ổn định. Đang gửi prompt video..."
                        generateVideoWithRecovery(prompt, expectedModel, selected, preflightRewrite, 0)
                    }
                }
            }
        }
    }

    private fun generateVideoWithRecovery(
        prompt: String,
        expectedModel: String,
        selected: SelectedFile,
        preflightRewrite: Int,
        retry: Int,
    ) {
        log("R11_VIDEO_GENERATE_ATTEMPT", "attempt=${retry + 1} expectedModel=$expectedModel preflightRewrite=$preflightRewrite state=${executor.currentState()}")
        val accepted = executor.generate(prompt, marker = "", timeoutMs = 60_000L) { result ->
            if (!result.ok && result.error == "NO_HANDLER_TRIGGERED_REQUEST" && retry < MAX_GENERATE_RECOVERY_RETRIES) {
                log("R11_VIDEO_GENERATE_RECOVERY", "reason=NO_HANDLER_TRIGGERED_REQUEST nextAttempt=${retry + 2}")
                main.postDelayed({
                    waitForControllerReady("GENERATE_RECOVERY_${retry + 2}", 1, 0) { ready ->
                        if (ready) generateVideoWithRecovery(prompt, expectedModel, selected, preflightRewrite, retry + 1)
                        else finishE2e(result, expectedModel, selected, preflightRewrite, retry + 1)
                    }
                }, 1_200L)
            } else {
                finishE2e(result, expectedModel, selected, preflightRewrite, retry + 1)
            }
        }
        if (!accepted) {
            log("R11_VIDEO_GENERATE_REJECTED", "attempt=${retry + 1} state=${executor.currentState()}")
            failE2e("GENERATE_DISPATCH", "executor từ chối generate")
        }
    }

    private fun finishE2e(
        result: AiStudioWebSessionExecutor.Result,
        expectedModel: String,
        selected: SelectedFile,
        preflightRewrite: Int,
        attempts: Int,
    ) {
        runOnUiThread {
            resultView.text = if (result.ok) {
                "HTTP ${result.status} | complete=${result.complete}\n${result.modelText}"
            } else {
                "R11.2 ERROR: ${result.error}"
            }
        }
        readSelectionState { selection, raw ->
            val observed = selection?.optString("observedGenerateModel").orEmpty()
            val rewriteCount = selection?.optInt("rewriteCount", 0) ?: 0
            val finalRewriteObserved = rewriteCount > preflightRewrite
            val modelVerified = lastPreflightVerified && observed == expectedModel && finalRewriteObserved
            val elapsed = System.currentTimeMillis() - e2eStartedAt
            log(
                "R11_E2E_RESULT",
                "ok=${result.ok} status=${result.status} complete=${result.complete} expectedModel=$expectedModel observedModel=${safe(observed, 160)} preflightVerified=$lastPreflightVerified preflightRewrite=$preflightRewrite rewriteCount=$rewriteCount finalRewriteObserved=$finalRewriteObserved modelVerified=$modelVerified resultChars=${result.modelText.length} generateAttempts=$attempts elapsedMs=$elapsed error=${safe(result.error, 500)} selection=${safe(raw, 3500)}",
            )
            labLog.snapshot(
                "r11-e2e-summary",
                "version=$VERSION\nexpectedModel=$expectedModel\nobservedModel=$observed\npreflightVerified=$lastPreflightVerified\npreflightRewrite=$preflightRewrite\nrewriteCount=$rewriteCount\nfinalRewriteObserved=$finalRewriteObserved\nmodelVerified=$modelVerified\nfileName=${selected.name}\nfileMime=${selected.mime}\nfileSize=${selected.size}\nhttpStatus=${result.status}\nok=${result.ok}\ncomplete=${result.complete}\nresultChars=${result.modelText.length}\ngenerateAttempts=$attempts\nelapsedMs=$elapsed\nerror=${result.error}\n",
            )
        }
    }

    private fun failE2e(phase: String, detail: String) {
        resultView.text = "R11.2 ERROR [$phase]: $detail"
        log("R11_E2E_FAIL", "phase=$phase detail=${safe(detail, 1200)} state=${executor.currentState()}")
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
        const val VERSION = "2026-09-02-web-session-r11.2-preflight-stable-attachment"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val DEFAULT_PROMPT = "Hãy xem toàn bộ video này và tóm tắt chi tiết nội dung, nhân vật, hành động, bối cảnh và các diễn biến chính."
        private const val MODEL_PREFLIGHT_MARKER = "AIS_R11_MODEL_PREFLIGHT_OK"
        private const val MODEL_PREFLIGHT_PROMPT = "Trả lời chính xác một dòng, không thêm gì khác: AIS_R11_MODEL_PREFLIGHT_OK"
        private const val REQUIRED_STABLE_POLLS = 4
        private const val MIN_ATTACHMENT_STABLE_MS = 5_000L
        private const val ATTACHMENT_POLL_MS = 700L
        private const val MAX_ATTACHMENT_POLLS = 70
        private const val REQUIRED_CONTROLLER_STABLE_POLLS = 2
        private const val CONTROLLER_POLL_MS = 300L
        private const val MAX_CONTROLLER_POLLS = 40
        private const val MAX_GENERATE_RECOVERY_RETRIES = 2
    }
}
