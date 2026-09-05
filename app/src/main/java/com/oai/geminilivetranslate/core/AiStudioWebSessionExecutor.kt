package com.oai.geminilivetranslate.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.ui.AiStudioWebSessionAdaptiveRuntime
import com.oai.geminilivetranslate.ui.AiStudioWebSessionHttpStatusGuard
import com.oai.geminilivetranslate.ui.AiStudioWebSessionLabScripts
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11RequestFix
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11SubmitTargetFix
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11Support
import com.oai.geminilivetranslate.ui.AiStudioWebSessionResponseCore
import com.oai.geminilivetranslate.ui.AiStudioSttPageBridge
import com.oai.geminilivetranslate.network.AiStudioDebugWebViewHost
import com.oai.geminilivetranslate.network.AiStudioNativeTapController
import org.json.JSONObject
import org.json.JSONTokener


@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioWebSessionExecutor(
    context: Context,
    private val events: Events? = null,
) {
    enum class State { NEW, LOADING, WAITING_FOR_CONTROLLER, READY, GENERATING, ERROR, DESTROYED }

    data class Result(
        val ok: Boolean,
        val status: Int = -1,
        val modelText: String = "",
        val complete: Boolean = false,
        val phase: String = "",
        val error: String = "",
    )

    interface Events {
        fun onStateChanged(state: State, detail: String) {}
        fun onLog(name: String, detail: String) {}
    }

    val webView: WebView = WebView(context)

    private val main = Handler(Looper.getMainLooper())
    private var state: State = State.NEW
    private var destroyed = false
    private var pageFinished = false
    private var seq = 0
    private var pending: Pending? = null
    private var attachmentSeq = 0
    private var activeAttachment: PendingAttachment? = null
    private var sttModeModel: String? = null
    private var attachmentPartialCallback: ((String) -> Unit)? = null
    private var attachmentPartialLastText: String = ""
    private val nativeTapController = AiStudioNativeTapController(webView, null)

    private data class PendingAttachment(
        val token: Int,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val startedAt: Long,
        val callback: (Boolean, String) -> Unit,
        val requireUploadReady: Boolean,
        var readyScans: Int = 0,
        var readySince: Long = 0L,
    )

    private data class Pending(
        val seq: Int,
        val callback: (Result) -> Unit,
        val startedAt: Long,
        val completionValidator: ((String) -> Boolean)? = null,
        var firstProgressAt: Long = 0L,
        var lastProgressAt: Long = 0L,
        var lastResponseChars: Int = 0,
    )

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AiStudioWebSessionExecutor must be created on main thread" }
        configureWebView()
    }

    fun currentState(): State = state

    fun startFileTranscribe(modelId: String) {
        sttModeModel = modelId
        val url = "https://aistudio.google.com/u/0/prompts/new_chat?model=${Uri.encode(modelId)}"
        events?.onLog("R28_STT_DIRECT_START", "model=$modelId path=/u/0/prompts/new_chat")
        start(url)
    }

    fun start(url: String? = null) {
        if (destroyed) return
        val resolvedUrl = url ?: NEW_CHAT_URL
        val source = if (url != null) "explicit" else "new-chat"
        events?.onLog("R12_START_URL", "source=$source host=${runCatching { android.net.Uri.parse(resolvedUrl).host }.getOrNull().orEmpty()}")
        setState(State.LOADING, "loading AI Studio source=$source")
        pageFinished = false
        AiStudioDebugWebViewHost.attach(webView, null)
        webView.loadUrl(resolvedUrl)
    }

    fun refreshDiscovery() {
        if (destroyed || !pageFinished) return
        val sttModel = sttModeModel
        if (sttModel != null) {
            val script = "JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.pageState?window.__AIS_STT_PAGE__.pageState(${JSONObject.quote(sttModel)}):({ok:false,error:'stt-page-bridge-not-installed'}))"
            webView.evaluateJavascript(script) { raw ->
                val decoded = decodeEvalValue(raw)
                val obj = runCatching { JSONObject(decoded) }.getOrNull()
                events?.onLog("R28_STT_PAGE_PROBE", decoded.take(6000))
                if (pending == null) {
                    if (obj?.optBoolean("ready") == true) setState(State.READY, "dedicated STT page ready model=$sttModel")
                    else setState(State.WAITING_FOR_CONTROLLER, "waiting for dedicated STT surface")
                }
            }
            return
        }
        val script = "JSON.stringify(window.__AIS_ADAPTIVE_RUNTIME__ ? window.__AIS_ADAPTIVE_RUNTIME__.discover() : ({ok:false,error:'runtime-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val count = obj?.optInt("candidateCount", 0) ?: 0
            val readyCount = obj?.optInt("readyCandidateCount", 0) ?: 0
            val controllerReady = obj?.optBoolean("controllerReady", false) == true
            events?.onLog("R10_DISCOVERY", decoded.take(8000))
            if (pending == null) {
                if (obj?.optBoolean("ok") == true && controllerReady && readyCount > 0) setState(State.READY, "ready controllers=$readyCount candidates=$count")
                else setState(State.WAITING_FOR_CONTROLLER, "waiting for high-confidence controller candidates=$count")
            }
        }
    }


    fun selectModel(modelId: String, callback: (Boolean, String) -> Unit) {
        main.post {
            if (destroyed || !pageFinished) {
                callback(false, "NOT_READY")
                return@post
            }
            val script = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.selectModel?window.__AIS_R11_SUPPORT__.selectModel(${JSONObject.quote(modelId)}):({ok:false,error:'r11-support-not-installed'}))"
            webView.evaluateJavascript(script) { raw ->
                val decoded = decodeEvalValue(raw)
                val ok = runCatching { JSONObject(decoded).optBoolean("ok", false) }.getOrDefault(false)
                events?.onLog("R18_MODEL_SELECT", decoded.take(6000))
                callback(ok, decoded)
            }
        }
    }

    fun attachSttFile(
        uri: Uri, displayName: String, mimeType: String, size: Long, callback: (Boolean, String) -> Unit,
    ) {
        main.post {
            if (destroyed || !pageFinished || state != State.READY || sttModeModel == null) { callback(false, "NOT_READY"); return@post }
            if (activeAttachment != null) { callback(false, "ATTACHMENT_BUSY"); return@post }
            attachmentSeq += 1
            val item = PendingAttachment(attachmentSeq, uri, displayName.take(260), mimeType.take(180), size, SystemClock.uptimeMillis(), callback, true)
            activeAttachment = item
            events?.onLog("R28_STT_FILE_START", "token=${item.token} name=${item.name} mime=${item.mimeType} size=${item.size}")
            armSttAttachment(item.token, 0)
        }
    }

    private fun armSttAttachment(token: Int, attempt: Int) {
        val item = activeAttachment ?: return
        if (item.token != token || destroyed) return
        if (attempt >= 8) { finishAttachment(token, false, "STT_UPLOAD_TARGET_NOT_FOUND"); return }
        val script = "JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.uploadTarget?window.__AIS_STT_PAGE__.uploadTarget():({ok:false,error:'stt-upload-target-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (activeAttachment?.token != token) return@evaluateJavascript
            val decoded=decodeEvalValue(raw); val obj=runCatching { JSONObject(decoded) }.getOrNull()
            events?.onLog("R28_STT_UPLOAD_TARGET", "attempt=${attempt+1} ${decoded.take(5000)}")
            val x=obj?.optDouble("xRatio", Double.NaN) ?: Double.NaN; val y=obj?.optDouble("yRatio", Double.NaN) ?: Double.NaN
            if (obj?.optBoolean("ok") == true && x.isFinite() && y.isFinite()) {
                nativeTapController.requestNativeTap(JSONObject().put("xRatio",x).put("yRatio",y).put("tag","STT_UPLOAD").put("role","stt-upload").put("purpose","file-transcribe-upload").toString())
                main.postDelayed({ pollSttAttachment(token) }, 450L)
            } else main.postDelayed({ armSttAttachment(token, attempt+1) }, 650L)
        }
    }

    private fun pollSttAttachment(token: Int) {
        val item=activeAttachment ?: return
        if (item.token != token || destroyed) return
        if (SystemClock.uptimeMillis()-item.startedAt > ATTACHMENT_TIMEOUT_MS) { finishAttachment(token,false,"STT_ATTACHMENT_TIMEOUT"); return }
        val script="JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.fileState?window.__AIS_STT_PAGE__.fileState(${JSONObject.quote(item.name)}):({ok:false,error:'stt-file-state-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (activeAttachment?.token != token) return@evaluateJavascript
            val decoded=decodeEvalValue(raw); val obj=runCatching { JSONObject(decoded) }.getOrNull(); val ready=obj?.optBoolean("ready") == true
            if (ready) { item.readyScans += 1; if (item.readySince==0L) item.readySince=SystemClock.uptimeMillis() } else { item.readyScans=0; item.readySince=0L }
            events?.onLog("R28_STT_FILE_POLL", "readyScans=${item.readyScans} ${decoded.take(6000)}")
            if (ready && item.readyScans>=ATTACHMENT_READY_STABLE_SCANS && SystemClock.uptimeMillis()-item.readySince>=ATTACHMENT_READY_SETTLE_MS) finishAttachment(token,true,decoded)
            else main.postDelayed({ pollSttAttachment(token) },500L)
        }
    }

    fun attachFile(
        uri: Uri,
        displayName: String,
        mimeType: String,
        size: Long,
        requireUploadReady: Boolean = true,
        callback: (Boolean, String) -> Unit,
    ) {
        main.post {
            if (destroyed || !pageFinished || state != State.READY) {
                callback(false, "NOT_READY")
                return@post
            }
            if (activeAttachment != null) {
                callback(false, "ATTACHMENT_BUSY")
                return@post
            }
            attachmentSeq += 1
            val item = PendingAttachment(
                token = attachmentSeq,
                uri = uri,
                name = displayName.take(260),
                mimeType = mimeType.take(180),
                size = size,
                startedAt = SystemClock.uptimeMillis(),
                callback = callback,
                requireUploadReady = requireUploadReady,
            )
            activeAttachment = item
            events?.onLog("R18_ATTACHMENT_START", "token=${item.token} name=${item.name} mime=${item.mimeType} size=${item.size}")
            armAttachment(item.token, 0)
        }
    }

    private fun armAttachment(token: Int, attempt: Int) {
        val item = activeAttachment ?: return
        if (item.token != token || destroyed) return
        if (attempt > 7) {
            finishAttachment(token, false, "FILE_INPUT_NOT_FOUND")
            return
        }
        val script = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.armTrustedFileChooser?window.__AIS_R11_SUPPORT__.armTrustedFileChooser():({ok:false,error:'r11-file-arm-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (activeAttachment?.token != token) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            events?.onLog("R18_ATTACHMENT_ARM", "attempt=$attempt ${decoded.take(5000)}")
            if (obj?.optBoolean("ok") == true) {
                main.postDelayed({
                    if (activeAttachment?.token != token) return@postDelayed
                    nativeTapController.requestNativeTap("{\"xRatio\":0.5,\"yRatio\":0.5,\"tag\":\"FILE_CHOOSER\",\"role\":\"attachment\"}")
                    main.postDelayed({ pollAttachment(token) }, 350L)
                }, 120L)
            } else {


                val expose = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachFile?window.__AIS_R11_SUPPORT__.attachFile():({ok:false,error:'r11-attach-not-installed'}))"
                webView.evaluateJavascript(expose) { exposed ->
                    events?.onLog("R18_ATTACHMENT_EXPOSE", decodeEvalValue(exposed).take(5000))
                    main.postDelayed({ armAttachment(token, attempt + 1) }, 550L)
                }
            }
        }
    }

    private fun pollAttachment(token: Int) {
        val item = activeAttachment ?: return
        if (item.token != token || destroyed) return
        if (SystemClock.uptimeMillis() - item.startedAt > ATTACHMENT_TIMEOUT_MS) {
            finishAttachment(token, false, "ATTACHMENT_TIMEOUT")
            return
        }
        val script = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachmentEvidence?window.__AIS_R11_SUPPORT__.attachmentEvidence():({ok:false,error:'r11-attachment-evidence-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (activeAttachment?.token != token) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val present = obj?.optBoolean("present", false) == true
            val ready = obj?.optBoolean("ready", false) == true
            val now = SystemClock.uptimeMillis()
            if (!item.requireUploadReady && present) {
                events?.onLog("R19_ATTACHMENT_PRESENT_MANUAL", "token=$token waitedMs=${now - item.startedAt} detail=${decoded.take(6000)}")
                finishAttachment(token, true, decoded)
                return@evaluateJavascript
            }
            if (ready) {
                item.readyScans += 1
                if (item.readySince == 0L) item.readySince = now
            } else {
                item.readyScans = 0
                item.readySince = 0L
            }
            events?.onLog("R18_ATTACHMENT_STATE", "readyScans=${item.readyScans} ${decoded.take(7000)}")
            if (ready && item.readyScans >= ATTACHMENT_READY_STABLE_SCANS && now - item.readySince >= ATTACHMENT_READY_SETTLE_MS) {
                events?.onLog("R20_ATTACHMENT_PREPARED", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt} localReadReady=${obj?.optBoolean("localReadReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)}")
                finishAttachment(token, true, decoded)
            } else {
                if (present && !ready) {
                    events?.onLog("R20_ATTACHMENT_WAIT_PREPARED", "token=$token busy=${obj?.optBoolean("busy", false)} present=$present localReadReady=${obj?.optBoolean("localReadReady", false)} attachmentPrepared=${obj?.optBoolean("attachmentPrepared", false)} submitReady=${obj?.optBoolean("submitReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)} payloadActive=${obj?.optInt("payloadActive", 0)} payloadStarted=${obj?.optInt("payloadStarted", 0)} payloadCompleted=${obj?.optInt("payloadCompleted", 0)} payloadFailed=${obj?.optInt("payloadFailed", 0)}")
                }
                main.postDelayed({ pollAttachment(token) }, 500L)
            }
        }
    }

    private fun finishAttachment(token: Int, ok: Boolean, detail: String) {
        val item = activeAttachment ?: return
        if (item.token != token) return
        activeAttachment = null
        events?.onLog("R18_ATTACHMENT_DONE", "token=$token ok=$ok detail=${detail.take(5000)}")
        item.callback(ok, detail)
    }

    private fun prepareAttachmentPrompt(prompt: String, callback: (Boolean, String, Int) -> Unit) {
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__&&window.__AIS_R11_SUBMIT_TARGET__.preparePromptIfAttachment?window.__AIS_R11_SUBMIT_TARGET__.preparePromptIfAttachment(${JSONObject.quote(prompt)}):({ok:false,error:'manual-prompt-preparer-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val ok = obj?.optBoolean("ok") == true
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            events?.onLog("R19_PROMPT_PREPARE", decoded.take(10000))
            callback(ok, decoded, baseline)
        }
    }

    private fun beginPreparedAttachmentRequest(
        callback: (Result) -> Unit,
        mode: String,
        completionValidator: ((String) -> Boolean)? = null,
    ): Pending {
        seq += 1
        val request = Pending(
            seq = seq,
            callback = callback,
            startedAt = SystemClock.uptimeMillis(),
            completionValidator = completionValidator,
        )
        pending = request
        setState(State.GENERATING, "request=${request.seq} mode=$mode")
        schedulePolls(request.seq)
        scheduleProgressWatchdog(request.seq)
        return request
    }

    fun generateAttachmentNativeOnly(
        prompt: String,
        onPartial: ((String) -> Unit)? = null,
        completionValidator: ((String) -> Boolean)? = null,
        callback: (Result) -> Unit,
    ): Boolean {
        if (destroyed || !pageFinished || state != State.READY || pending != null || prompt.isBlank()) {
            callback(Result(ok = false, error = if (prompt.isBlank()) "EMPTY_PROMPT" else "NOT_READY_OR_BUSY"))
            return false
        }
        attachmentPartialCallback = onPartial
        attachmentPartialLastText = ""
        prepareAttachmentPrompt(prompt) { ok, detail, _ ->
            if (!ok) {
                clearAttachmentPartial()
                callback(Result(ok = false, error = "PROMPT_PREPARE_FAILED", phase = detail.take(500)))
                return@prepareAttachmentPrompt
            }
            val request = beginPreparedAttachmentRequest(
                callback = callback,
                mode = "attachment-native-only",
                completionValidator = completionValidator,
            )
            events?.onLog("R19_NATIVE_FILE_SUBMIT_ARMED", "seq=${request.seq} promptChars=${prompt.length}")
            events?.onLog("R23_VIDEO_AUTO_SUBMIT_POLICY", "seq=${request.seq} nativeHitTest=true cachedPreparedTarget=true programmaticFallback=true")
            tryNativeAttachmentSubmit(request.seq, "native-file-primary", 0)
        }
        return true
    }

    fun generateSttFileNative(callback: (Result) -> Unit): Boolean {
        if (destroyed || !pageFinished || state != State.READY || pending != null || sttModeModel == null) { callback(Result(ok=false,error="NOT_READY_OR_BUSY")); return false }
        val request=beginPreparedAttachmentRequest(callback,"stt-direct-page-file")
        events?.onLog("R28_STT_AUTO_RUN_ARMED", "seq=${request.seq} model=${sttModeModel} prompt=false")
        trySttRunSubmit(request.seq,0)
        return true
    }

    private fun trySttRunSubmit(requestSeq: Int, attempt: Int) {
        if (pending?.seq != requestSeq) return
        if (attempt >= NATIVE_SUBMIT_MAX_RETRIES) { finish(requestSeq,Result(ok=false,error="STT_RUN_NO_CAPTURE")); return }
        val script="JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.runTarget?window.__AIS_STT_PAGE__.runTarget():({ok:false,error:'stt-run-target-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded=decodeEvalValue(raw); val obj=runCatching { JSONObject(decoded) }.getOrNull(); events?.onLog("R28_STT_RUN_TARGET", "attempt=${attempt+1} ${decoded.take(5000)}")
            val x=obj?.optDouble("xRatio",Double.NaN) ?: Double.NaN; val y=obj?.optDouble("yRatio",Double.NaN) ?: Double.NaN; val base=obj?.optInt("baselineCaptureCount",-1) ?: -1
            if (obj?.optBoolean("ok") != true || !x.isFinite() || !y.isFinite() || base < 0) { main.postDelayed({trySttRunSubmit(requestSeq,attempt+1)},NATIVE_SUBMIT_RETRY_MS); return@evaluateJavascript }
            nativeTapController.requestNativeTap(JSONObject().put("xRatio",x).put("yRatio",y).put("tag","STT_RUN").put("role","stt-run").put("purpose","file-transcribe-run").toString())
            main.postDelayed({ checkGenerateCapture(requestSeq,base,"stt-run-${attempt+1}") { started ->
                if (pending?.seq != requestSeq) return@checkGenerateCapture
                if (started) { events?.onLog("R28_STT_RUN_ACK", "seq=$requestSeq attempt=${attempt+1} captureStarted=true"); setState(State.GENERATING,"dedicated STT Run triggered GenerateContent"); pollSttResult(requestSeq,0) }
                else main.postDelayed({trySttRunSubmit(requestSeq,attempt+1)},NATIVE_SUBMIT_RETRY_MS)
            } },NATIVE_SUBMIT_ACK_MS)
        }
    }

    private fun pollSttResult(requestSeq: Int, attempt: Int) {
        if (pending?.seq != requestSeq) return
        val script="JSON.stringify(window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.resultState?window.__AIS_STT_PAGE__.resultState():({ok:false,error:'stt-result-state-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded=decodeEvalValue(raw); val obj=runCatching { JSONObject(decoded) }.getOrNull(); val text=obj?.optString("text").orEmpty(); val chars=text.length
            events?.onLog("R28_STT_RESULT_POLL", "attempt=$attempt chars=$chars source=${obj?.optString("source").orEmpty()} status=${obj?.optInt("status",-1)} responseChars=${obj?.optInt("responseChars",0)} terminal=${obj?.optBoolean("terminal",false)} elapsedMs=${obj?.optLong("elapsedMs",-1)}")
            if (text.isNotBlank()) { recordProgress(requestSeq,chars,"stt-dom"); finish(requestSeq,Result(ok=true,status=obj?.optInt("status",200) ?: 200,modelText=text,complete=true,phase="stt-dom-result")); return@evaluateJavascript }
            main.postDelayed({pollSttResult(requestSeq,attempt+1)}, if(attempt<10) 900L else 1800L)
        }
    }

    fun cancelCurrent(): Boolean {
        val p = pending ?: return false
        pending = null
        clearAttachmentPartial()
        runCatching { webView.evaluateJavascript("window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.cancel()", null) }
        p.callback(Result(ok = false, error = "CANCELLED"))
        setState(if (pageFinished) State.READY else State.LOADING, "cancelled request=${p.seq}")
        return true
    }

    fun destroy() {
        if (destroyed) return
        cancelCurrent()
        clearAttachmentPartial()
        destroyed = true
        state = State.DESTROYED
        activeAttachment?.let { it.callback(false, "DESTROYED") }
        activeAttachment = null
        runCatching { webView.stopLoading() }
        runCatching { webView.onPause() }
        runCatching { webView.removeJavascriptInterface(JS_BRIDGE_NAME) }
        AiStudioDebugWebViewHost.retain(webView, null, "executor-destroy")
        events?.onStateChanged(State.DESTROYED, "destroyed-debug-webview-retained")
    }

    private fun schedulePolls(requestSeq: Int) {
        listOf(450L, 900L, 1_500L, 2_500L, 4_000L, 6_500L, 9_000L, 13_000L, 20_000L, 30_000L).forEach { delay ->
            main.postDelayed({ if (pending?.seq == requestSeq) readNormalized(requestSeq, "poll-$delay") }, delay)
        }
        main.postDelayed(object : Runnable {
            override fun run() {
                if (pending?.seq != requestSeq || attachmentPartialCallback == null) return
                readNormalized(requestSeq, "video-partial-live")
                main.postDelayed(this, ATTACHMENT_PARTIAL_POLL_MS)
            }
        }, ATTACHMENT_PARTIAL_POLL_MS)
    }

    private fun scheduleProgressWatchdog(requestSeq: Int) {
        main.postDelayed(object : Runnable {
            override fun run() {
                val p = pending ?: return
                if (p.seq != requestSeq) return
                val now = SystemClock.uptimeMillis()
                val total = now - p.startedAt
                val noProgressYet = p.firstProgressAt == 0L
                val idle = if (p.lastProgressAt > 0L) now - p.lastProgressAt else total

                when {
                    total >= PROGRESS_HARD_TIMEOUT_MS -> {
                        timeoutRequest(requestSeq, "HARD_TIMEOUT totalMs=$total responseChars=${p.lastResponseChars}")
                    }
                    noProgressYet && total >= FIRST_PROGRESS_TIMEOUT_MS -> {
                        timeoutRequest(requestSeq, "FIRST_PROGRESS_TIMEOUT totalMs=$total")
                    }
                    !noProgressYet && idle >= PROGRESS_IDLE_TIMEOUT_MS -> {
                        timeoutRequest(requestSeq, "IDLE_TIMEOUT idleMs=$idle totalMs=$total responseChars=${p.lastResponseChars}")
                    }
                    else -> {
                        if (total % 10_000L < WATCHDOG_TICK_MS) {
                            events?.onLog(
                                "R12_PROGRESS_WATCHDOG",
                                "seq=$requestSeq totalMs=$total firstProgress=${p.firstProgressAt > 0L} idleMs=$idle responseChars=${p.lastResponseChars}",
                            )
                        }
                        main.postDelayed(this, WATCHDOG_TICK_MS)
                    }
                }
            }
        }, WATCHDOG_TICK_MS)
    }

    private fun recordProgress(requestSeq: Int, responseChars: Int, source: String) {
        val p = pending ?: return
        if (p.seq != requestSeq) return
        if (responseChars <= p.lastResponseChars) return
        val now = SystemClock.uptimeMillis()
        val previous = p.lastResponseChars
        p.lastResponseChars = responseChars
        p.lastProgressAt = now
        if (p.firstProgressAt == 0L) p.firstProgressAt = now
        events?.onLog(
            "R12_PROGRESS_ACTIVITY",
            "seq=$requestSeq source=$source chars=$responseChars delta=${responseChars - previous} totalMs=${now - p.startedAt}",
        )
    }

    private fun publishAttachmentPartial(requestSeq: Int, text: String, source: String) {
        val p = pending ?: return
        val callback = attachmentPartialCallback ?: return
        if (p.seq != requestSeq) return
        val normalized = text.trim()
        if (normalized.isBlank() || normalized.length <= attachmentPartialLastText.length) return
        val previous = attachmentPartialLastText.length
        attachmentPartialLastText = normalized
        events?.onLog(
            "R35_VIDEO_PARTIAL_RAW",
            "seq=$requestSeq source=$source chars=${normalized.length} delta=${normalized.length - previous}",
        )
        callback(normalized)
    }

    private fun clearAttachmentPartial() {
        attachmentPartialCallback = null
        attachmentPartialLastText = ""
    }

    private fun timeoutRequest(requestSeq: Int, reason: String) {
        if (pending?.seq != requestSeq) return
        events?.onLog("R12_TIMEOUT_FIRED", "seq=$requestSeq reason=$reason")
        runCatching { webView.evaluateJavascript("window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.cancel()", null) }
        finish(requestSeq, Result(ok = false, error = "TIMEOUT", phase = reason))
    }

    private fun readNormalized(requestSeq: Int, source: String) {
        if (pending?.seq != requestSeq) return
        val script = "JSON.stringify(window.__AIS_RESPONSE_CORE__ ? window.__AIS_RESPONSE_CORE__.getNormalized() : ({ok:false,error:'response-core-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R10_RESPONSE_$source", decoded.take(12000))
            parseNormalized(decoded)?.let {
                recordProgress(requestSeq, it.modelText.length, "normalized-$source")
                publishAttachmentPartial(requestSeq, it.modelText, "normalized-$source")
                maybeFinish(requestSeq, it)
            }
        }
    }

    private fun maybeFinish(requestSeq: Int, result: Result) {
        val p = pending ?: return
        if (p.seq != requestSeq) return
        if (!result.ok && result.error.isNotBlank() && result.error != "no-result") {
            finish(requestSeq, result)
            return
        }
        if (!result.ok && result.status >= 400) {
            finish(requestSeq, result.copy(error = httpErrorName(result.status), complete = true))
            return
        }
        if (sttModeModel != null && result.ok && result.complete && result.modelText.isBlank()) {
            events?.onLog("R28_STT_NETWORK_COMPLETE_EMPTY", "seq=$requestSeq status=${result.status} phase=${result.phase}; waiting for dedicated STT DOM result")
            return
        }
        if (result.ok && result.complete) {
            val validator = p.completionValidator
            if (validator != null && !validator(result.modelText)) {
                events?.onLog(
                    "R36_VIDEO_COMPLETION_DEFERRED",
                    "seq=$requestSeq chars=${result.modelText.length} status=${result.status} phase=${result.phase}",
                )
                return
            }
            finish(requestSeq, result)
        }
    }

    private fun finish(requestSeq: Int, result: Result) {
        val p = pending ?: return
        if (p.seq != requestSeq) return
        pending = null
        clearAttachmentPartial()
        p.callback(result)
        if (!destroyed) setState(if (pageFinished) State.READY else State.LOADING, if (result.ok) "completed" else "failed:${result.error}")
    }

    private fun tryLegacyProgrammaticFallback(requestSeq: Int, reason: String) {
        tryNativeAttachmentSubmit(requestSeq, reason, 0)
    }

    private fun tryNativeAttachmentSubmit(
        requestSeq: Int,
        reason: String,
        attempt: Int,
    ) {
        if (pending?.seq != requestSeq) return
        events?.onLog("R12_NATIVE_SUBMIT_START", "seq=$requestSeq reason=$reason attempt=${attempt + 1}")
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.nativeTargetIfAttachment() : ({ok:false,error:'native-submit-target-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R12_NATIVE_SUBMIT_TARGET", "attempt=${attempt + 1} ${decoded.take(10000)}")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") != true) {
                if (attempt < NATIVE_SUBMIT_MAX_RETRIES - 1) {
                    main.postDelayed({ tryNativeAttachmentSubmit(requestSeq, "target-rescan", attempt + 1) }, NATIVE_SUBMIT_RETRY_MS)
                } else {
                    tryProgrammaticAttachmentFallback(requestSeq, "native-target-unavailable")
                }
                return@evaluateJavascript
            }
            val xRatio = obj.optDouble("xRatio", Double.NaN)
            val yRatio = obj.optDouble("yRatio", Double.NaN)
            val baseline = obj.optInt("baselineCaptureCount", -1)
            if (!xRatio.isFinite() || !yRatio.isFinite() || baseline < 0) {
                if (attempt < NATIVE_SUBMIT_MAX_RETRIES - 1) main.postDelayed({ tryNativeAttachmentSubmit(requestSeq, "invalid-native-target", attempt + 1) }, NATIVE_SUBMIT_RETRY_MS)
                else tryProgrammaticAttachmentFallback(requestSeq, "invalid-native-target")
                return@evaluateJavascript
            }
            nativeTapController.requestNativeTap(
                JSONObject()
                    .put("xRatio", xRatio)
                    .put("yRatio", yRatio)
                    .put("tag", "VIDEO_SEND")
                    .put("role", "composer-submit")
                    .put("purpose", "video-generate")
                    .toString(),
            )
            main.postDelayed({
                checkGenerateCapture(requestSeq, baseline, "native-submit-${attempt + 1}") { started ->
                    if (pending?.seq != requestSeq) return@checkGenerateCapture
                    if (started) {
                        events?.onLog("R12_NATIVE_SUBMIT_ACK", "seq=$requestSeq attempt=${attempt + 1} captureStarted=true")
                        setState(State.GENERATING, "native composer tap triggered GenerateContent")
                        readNormalized(requestSeq, "native-submit")
                    } else if (attempt < NATIVE_SUBMIT_MAX_RETRIES - 1) {
                        events?.onLog("R12_NATIVE_SUBMIT_RETRY", "seq=$requestSeq attempt=${attempt + 1} reason=no-capture")
                        main.postDelayed({ tryNativeAttachmentSubmit(requestSeq, "no-capture", attempt + 1) }, NATIVE_SUBMIT_RETRY_MS)
                    } else {
                        tryProgrammaticAttachmentFallback(requestSeq, "native-no-capture")
                    }
                }
            }, NATIVE_SUBMIT_ACK_MS)
        }
    }

    private fun tryProgrammaticAttachmentFallback(requestSeq: Int, reason: String) {
        if (pending?.seq != requestSeq) return
        events?.onLog("R12_LEGACY_FALLBACK_START", "seq=$requestSeq reason=$reason")
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.submitIfAttachment() : ({ok:false,error:'submit-target-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R12_LEGACY_FALLBACK_DISPATCH", decoded.take(10000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val attempted = obj?.optBoolean("attempted") == true || obj?.optBoolean("pending") == true
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            if (!attempted) {
                finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                return@evaluateJavascript
            }
            main.postDelayed({
                checkGenerateCapture(requestSeq, baseline, "legacy-programmatic") { started ->
                    if (pending?.seq != requestSeq) return@checkGenerateCapture
                    if (started) {
                        setState(State.GENERATING, "legacy diagnostic fallback triggered GenerateContent")
                        readNormalized(requestSeq, "legacy-fallback")
                    } else {
                        finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                    }
                }
            }, LEGACY_FALLBACK_CHECK_MS)
        }
    }

    private fun checkGenerateCapture(
        requestSeq: Int,
        baseline: Int,
        source: String,
        onDone: (Boolean) -> Unit,
    ) {
        if (pending?.seq != requestSeq) return
        val check = "JSON.stringify((function(b){var n=window.__AIS_WEB_SESSION__;var c=Number(n&&n.captureCount||0);return {ok:true,baseline:b,captureCount:c,started:b>=0&&c>b};})($baseline))"
        webView.evaluateJavascript(check) { captureRaw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(captureRaw)
            events?.onLog("R12_CAPTURE_CHECK", "source=$source ${decoded.take(4000)}")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            onDone(obj?.optBoolean("started") == true)
        }
    }

    private fun parseNormalized(decoded: String): Result? {
        val obj = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        if (obj.optString("error") == "no-result") return null
        val status = obj.optInt("status", -1)
        val ok = obj.optBoolean("ok")
        val explicitError = obj.optString("error")
        val modelText = obj.optString("modelText")
        val phase = obj.optString("phase")
        val terminalHttpSuccess = ok && status in 200..299 && modelText.isNotBlank() &&
            (phase == "reset-after-stream" || phase == "loadend" || phase == "done" || phase == "complete")
        return Result(
            ok = ok,
            status = status,
            modelText = modelText,
            complete = obj.optBoolean("complete") || terminalHttpSuccess || (!ok && status >= 400),
            phase = phase,
            error = explicitError.ifBlank { if (!ok && status >= 400) httpErrorName(status) else "" },
        )
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onJsEvent(json: String) {
            val parsed = runCatching { JSONObject(json) }.getOrNull()
            val kind = parsed?.optString("kind").orEmpty()
            val payload = parsed?.optJSONObject("payload")
            events?.onLog("JS_$kind", json.take(16000))

            if (payload != null && (kind == "GENERATE_PROGRESS" || kind == "NORMALIZED_GENERATE_RESULT" || kind == "GENERATE_RESULT")) {
                val chars = payload.optInt("responseChars", payload.optString("modelText").length)
                val partialText = payload.optString("modelText")
                main.post {
                    val p = pending ?: return@post
                    recordProgress(p.seq, chars, kind)
                    publishAttachmentPartial(p.seq, partialText, "js-$kind")
                }
            }

            when (kind) {
                "NORMALIZED_GENERATE_RESULT" -> if (payload != null) {
                    main.post {
                        val p = pending ?: return@post
                        maybeFinish(p.seq, parseNormalized(payload.toString()) ?: return@post)
                    }
                }
                "GENERATE_RESULT" -> if (payload != null) {
                    main.post {
                        val p = pending ?: return@post
                        val parsedResult = parseNormalized(payload.toString()) ?: return@post
                        val terminal2xx = parsedResult.ok && parsedResult.status in 200..299 && parsedResult.modelText.isNotBlank()
                        val result = if (terminal2xx) parsedResult.copy(complete = true) else parsedResult
                        events?.onLog(
                            "R12_TERMINAL_RESULT",
                            "seq=${p.seq} ok=${result.ok} status=${result.status} complete=${result.complete} modelChars=${result.modelText.length} phase=${result.phase}",
                        )
                        maybeFinish(p.seq, result)
                    }
                }
                "GENERATE_HTTP_ERROR" -> if (payload != null) {
                    val status = payload.optInt("status", -1)
                    if (status >= 400) main.post {
                        val p = pending ?: return@post
                        finish(
                            p.seq,
                            Result(
                                ok = false,
                                status = status,
                                complete = true,
                                phase = "http-error",
                                error = httpErrorName(status),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun httpErrorName(status: Int): String = when (status) {
        403 -> "HTTP_403"
        429 -> "HTTP_429"
        in 500..599 -> "HTTP_5XX"
        else -> "HTTP_$status"
    }

    private fun setState(next: State, detail: String) {
        if (destroyed && next != State.DESTROYED) return
        state = next
        events?.onStateChanged(next, detail)
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

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionLabScripts.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionHttpStatusGuard.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionResponseCore.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionAdaptiveRuntime.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR11Support.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioSttPageBridge.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR11RequestFix.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR11SubmitTargetFix.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
        } else {
            setState(State.ERROR, "DOCUMENT_START_SCRIPT unsupported")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageFinished = false
                if (pending != null) cancelCurrent()
                setState(State.LOADING, "page started")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageFinished = true
                setState(State.WAITING_FOR_CONTROLLER, "page finished")
                listOf(350L, 800L, 1_500L, 2_500L).forEach { main.postDelayed({ refreshDiscovery() }, it) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) setState(State.ERROR, "web error ${error?.errorCode}: ${error?.description}")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) setState(State.ERROR, "HTTP ${errorResponse?.statusCode}")
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                setState(State.ERROR, "SSL error ${error?.primaryError}")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val item = activeAttachment ?: return false
                val callback = filePathCallback ?: return false
                callback.onReceiveValue(arrayOf(item.uri))
                val stt = sttModeModel != null
                val mark = if (stt) {
                    "JSON.stringify((function(){var a=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.markFileChooserServed?window.__AIS_R11_SUPPORT__.markFileChooserServed(${JSONObject.quote(item.name)},${JSONObject.quote(item.mimeType)},${item.size}):null;var b=window.__AIS_STT_PAGE__&&window.__AIS_STT_PAGE__.markFileChooserServed?window.__AIS_STT_PAGE__.markFileChooserServed(${JSONObject.quote(item.name)},${JSONObject.quote(item.mimeType)},${item.size}):null;return {ok:true,r11:a,stt:b};})())"
                } else {
                    "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.markFileChooserServed?window.__AIS_R11_SUPPORT__.markFileChooserServed(${JSONObject.quote(item.name)},${JSONObject.quote(item.mimeType)},${item.size}):({ok:false,error:'r11-mark-file-not-installed'}))"
                }
                this@AiStudioWebSessionExecutor.webView.evaluateJavascript(mark) { raw ->
                    events?.onLog(if(stt) "R28_STT_URI_SERVED" else "R18_ATTACHMENT_URI_SERVED", decodeEvalValue(raw).take(5000))
                    main.postDelayed({ if(stt) pollSttAttachment(item.token) else pollAttachment(item.token) }, 250L)
                }
                return true
            }
        }
    }

    companion object {
        const val VERSION = "2026-09-06-web-session-r12.9-json-completion-guard"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val ATTACHMENT_TIMEOUT_MS = 300_000L
        private const val ATTACHMENT_READY_SETTLE_MS = 1_200L
        private const val ATTACHMENT_READY_STABLE_SCANS = 3
        private const val FIRST_PROGRESS_TIMEOUT_MS = 300_000L
        private const val PROGRESS_IDLE_TIMEOUT_MS = 60_000L
        private const val PROGRESS_HARD_TIMEOUT_MS = 900_000L
        private const val WATCHDOG_TICK_MS = 2_000L
        private const val ATTACHMENT_PARTIAL_POLL_MS = 850L
        private const val LEGACY_FALLBACK_CHECK_MS = 900L
        private const val NATIVE_SUBMIT_ACK_MS = 1_250L
        private const val NATIVE_SUBMIT_RETRY_MS = 900L
        private const val NATIVE_SUBMIT_MAX_RETRIES = 3
    }
}
