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
import com.oai.geminilivetranslate.ui.AiStudioGoogleAccountBootstrap
import com.oai.geminilivetranslate.ui.AiStudioWebSessionAdaptiveRuntime
import com.oai.geminilivetranslate.ui.AiStudioWebSessionDirectEngine
import com.oai.geminilivetranslate.ui.AiStudioWebSessionHttpStatusGuard
import com.oai.geminilivetranslate.ui.AiStudioWebSessionLabScripts
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11RequestFix
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11SubmitTargetFix
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11Support
import com.oai.geminilivetranslate.ui.AiStudioWebSessionResponseCore
import com.oai.geminilivetranslate.network.AiStudioDebugWebViewHost
import com.oai.geminilivetranslate.network.AiStudioNativeTapController
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R12.1 production-shaped executor for an authenticated AI Studio web session.
 *
 * R12 Direct Engine remains unchanged: the proven R9 adaptive path is first for text, and if it
 * reaches R9_HANDLER_FINAL the executor calls AI Studio's own page-local handlers directly rather
 * than synthesizing a physical tap.
 *
 * R12.1 fixes the response lifetime bug observed on real video generation. A terminal HTTP 2xx
 * GENERATE_RESULT with model text is accepted even when ResponseCore still labels the protobuf
 * payload partial. Long-running requests use a progress-aware watchdog: five minutes for first
 * streamed data, sixty seconds of true idle time after progress begins, and a fifteen-minute hard
 * ceiling. Any growth in responseChars, including the English thinking stream exposed by AI Studio,
 * refreshes the idle watchdog.
 *
 * Auth headers/cookies/API-key values never leave the page. A selected Android Google account may
 * be used only as a one-shot web login hint; no Android auth token is requested.
 */
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
        val markerFound: Boolean = false,
        val complete: Boolean = false,
        val phase: String = "",
        val error: String = "",
    )

    interface Events {
        fun onStateChanged(state: State, detail: String) {}
        fun onLog(name: String, detail: String) {}
    }

    private val appContext = context.applicationContext
    val webView: WebView = WebView(context)

    private val main = Handler(Looper.getMainLooper())
    private var state: State = State.NEW
    private var destroyed = false
    private var pageFinished = false
    private var seq = 0
    private var pending: Pending? = null
    private var directRecoverySeq = -1
    private var attachmentSeq = 0
    private var activeAttachment: PendingAttachment? = null
    private val nativeTapController = AiStudioNativeTapController(webView, null)

    private data class PendingAttachment(
        val token: Int,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val startedAt: Long,
        val callback: (Boolean, String) -> Unit,
        var readyScans: Int = 0,
        var readySince: Long = 0L,
    )

    private data class Pending(
        val seq: Int,
        val prompt: String,
        val marker: String,
        val callback: (Result) -> Unit,
        val startedAt: Long,
        val progressAware: Boolean,
        var firstProgressAt: Long = 0L,
        var lastProgressAt: Long = 0L,
        var lastResponseChars: Int = 0,
    )

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AiStudioWebSessionExecutor must be created on main thread" }
        configureWebView()
    }

    fun currentState(): State = state

    fun start(url: String? = null) {
        if (destroyed) return
        val bootstrapUrl = if (url == null) AiStudioGoogleAccountBootstrap.consumeStartUrl(appContext) else null
        val resolvedUrl = url ?: bootstrapUrl ?: NEW_CHAT_URL
        val source = when {
            url != null -> "explicit"
            bootstrapUrl != null -> "google-account-hint"
            else -> "new-chat"
        }
        events?.onLog("R12_START_URL", "source=$source host=${runCatching { android.net.Uri.parse(resolvedUrl).host }.getOrNull().orEmpty()}")
        setState(State.LOADING, "loading AI Studio source=$source")
        pageFinished = false
        AiStudioDebugWebViewHost.attach(webView, null)
        webView.loadUrl(resolvedUrl)
    }

    fun refreshDiscovery() {
        if (destroyed || !pageFinished) return
        val script = "JSON.stringify(window.__AIS_ADAPTIVE_RUNTIME__ ? window.__AIS_ADAPTIVE_RUNTIME__.discover() : ({ok:false,error:'runtime-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val count = obj?.optInt("candidateCount", 0) ?: 0
            val readyCount = obj?.optInt("readyCandidateCount", 0) ?: 0
            val controllerReady = obj?.optBoolean("controllerReady", false) == true
            events?.onLog("R10_DISCOVERY", decoded.take(8000))
            if (pending == null) {
                if (obj?.optBoolean("ok") == true && controllerReady && readyCount > 0) {
                    setState(State.READY, "ready controllers=$readyCount candidates=$count")
                } else {
                    setState(State.WAITING_FOR_CONTROLLER, "waiting for high-confidence controller candidates=$count")
                }
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

    fun attachFile(
        uri: Uri,
        displayName: String,
        mimeType: String,
        size: Long,
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
                // If the file input is lazily created, ask the page's own attachment control to expose it,
                // then arm the trusted click again. The actual URI is still supplied only by WebChromeClient.
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
            if (ready) {
                item.readyScans += 1
                if (item.readySince == 0L) item.readySince = now
            } else {
                item.readyScans = 0
                item.readySince = 0L
            }
            events?.onLog("R18_ATTACHMENT_STATE", "readyScans=${item.readyScans} ${decoded.take(7000)}")
            if (ready && item.readyScans >= ATTACHMENT_READY_STABLE_SCANS && now - item.readySince >= ATTACHMENT_READY_SETTLE_MS) {
                events?.onLog("R18_ATTACHMENT_UPLOAD_READY", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt}")
                finishAttachment(token, true, decoded)
            } else {
                if (present && !ready) {
                    events?.onLog("R18_ATTACHMENT_WAIT_UPLOAD", "token=$token busy=${obj?.optBoolean("busy", false)} uploadSettled=${obj?.optBoolean("uploadSettled", false)} submitReady=${obj?.optBoolean("submitReady", false)} activeUploads=${obj?.optInt("activeUploads", 0)}")
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

    fun generate(
        prompt: String,
        marker: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        callback: (Result) -> Unit,
    ): Boolean {
        if (destroyed) {
            callback(Result(ok = false, error = "DESTROYED"))
            return false
        }
        if (pending != null) {
            callback(Result(ok = false, error = "BUSY"))
            return false
        }
        if (!pageFinished || state != State.READY) {
            callback(Result(ok = false, error = "NOT_READY"))
            refreshDiscovery()
            return false
        }
        if (prompt.isBlank()) {
            callback(Result(ok = false, error = "EMPTY_PROMPT"))
            return false
        }

        seq += 1
        val progressAware = marker.isBlank()
        val request = Pending(
            seq = seq,
            prompt = prompt,
            marker = marker,
            callback = callback,
            startedAt = SystemClock.uptimeMillis(),
            progressAware = progressAware,
        )
        pending = request
        directRecoverySeq = -1
        setState(State.GENERATING, "request=${request.seq} progressAware=$progressAware")
        events?.onLog(
            "R12_TIMEOUT_POLICY",
            if (progressAware) {
                "seq=${request.seq} firstProgressMs=$FIRST_PROGRESS_TIMEOUT_MS idleMs=$PROGRESS_IDLE_TIMEOUT_MS hardMs=$PROGRESS_HARD_TIMEOUT_MS"
            } else {
                "seq=${request.seq} fixedMs=${timeoutMs.coerceIn(2_000L, FIXED_TIMEOUT_MAX_MS)} markerRequired=true"
            },
        )

        val expression = "window.__AIS_ADAPTIVE_RUNTIME__ ? window.__AIS_ADAPTIVE_RUNTIME__.generate(${JSONObject.quote(prompt)},${JSONObject.quote(marker)}) : ({ok:false,error:'runtime-not-installed'})"
        webView.evaluateJavascript("JSON.stringify($expression)") { raw ->
            if (pending?.seq != request.seq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R10_DISPATCH", decoded.take(8000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") != true) {
                tryDirectEngineRecovery(request.seq, "R9_DISPATCH_FAILED")
            } else {
                schedulePolls(request.seq)
            }
        }

        if (progressAware) {
            scheduleProgressWatchdog(request.seq)
        } else {
            main.postDelayed({
                if (pending?.seq == request.seq) timeoutRequest(request.seq, "FIXED_TIMEOUT")
            }, timeoutMs.coerceIn(2_000L, FIXED_TIMEOUT_MAX_MS))
        }
        return true
    }

    fun cancelCurrent(): Boolean {
        val p = pending ?: return false
        pending = null
        directRecoverySeq = -1
        runCatching { webView.evaluateJavascript("window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.cancel()", null) }
        p.callback(Result(ok = false, error = "CANCELLED"))
        setState(if (pageFinished) State.READY else State.LOADING, "cancelled request=${p.seq}")
        return true
    }

    fun destroy() {
        if (destroyed) return
        cancelCurrent()
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
    }

    private fun scheduleProgressWatchdog(requestSeq: Int) {
        main.postDelayed(object : Runnable {
            override fun run() {
                val p = pending ?: return
                if (p.seq != requestSeq || !p.progressAware) return
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
        if (p.seq != requestSeq || !p.progressAware) return
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
        val markerSatisfied = p.marker.isBlank() || result.markerFound
        if (result.ok && result.complete && markerSatisfied) finish(requestSeq, result)
    }

    private fun finish(requestSeq: Int, result: Result) {
        val p = pending ?: return
        if (p.seq != requestSeq) return
        pending = null
        directRecoverySeq = -1
        p.callback(result)
        if (!destroyed) setState(if (pageFinished) State.READY else State.LOADING, if (result.ok) "completed" else "failed:${result.error}")
    }

    private fun tryDirectEngineRecovery(requestSeq: Int, reason: String) {
        val p = pending ?: return
        if (p.seq != requestSeq || directRecoverySeq == requestSeq) return
        directRecoverySeq = requestSeq
        events?.onLog("R12_DIRECT_RECOVERY_START", "seq=$requestSeq reason=$reason promptChars=${p.prompt.length} markerChars=${p.marker.length}")
        val expression = "window.__AIS_DIRECT_ENGINE__ ? window.__AIS_DIRECT_ENGINE__.invokeDirect(${JSONObject.quote(p.prompt)},${JSONObject.quote(p.marker)}) : ({ok:false,error:'direct-engine-not-installed'})"
        webView.evaluateJavascript("JSON.stringify($expression)") { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R12_DIRECT_DISPATCH", decoded.take(12000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") != true) {
                directRecoverySeq = -1
                tryLegacyProgrammaticFallback(requestSeq, "DIRECT_DISPATCH_FAILED")
            } else {
                setState(State.GENERATING, "R12 Direct Engine invoking AI Studio handlers")
                main.postDelayed({
                    if (pending?.seq == requestSeq && directRecoverySeq == requestSeq) {
                        checkGenerateCapture(requestSeq, obj.optInt("baselineCaptureCount", -1), "r12-watchdog") { started ->
                            if (pending?.seq != requestSeq) return@checkGenerateCapture
                            if (started) {
                                directRecoverySeq = -1
                                setState(State.GENERATING, "R12 Direct Engine triggered GenerateContent")
                                readNormalized(requestSeq, "r12-direct-watchdog")
                            }
                        }
                    }
                }, DIRECT_ENGINE_WATCHDOG_MS)
            }
        }
    }

    private fun tryLegacyProgrammaticFallback(requestSeq: Int, reason: String) {
        tryNativeAttachmentSubmit(requestSeq, reason, 0)
    }

    private fun tryNativeAttachmentSubmit(requestSeq: Int, reason: String, attempt: Int) {
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
            markerFound = obj.optBoolean("markerFound"),
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
                main.post {
                    val p = pending ?: return@post
                    recordProgress(p.seq, chars, kind)
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
                            "seq=${p.seq} ok=${result.ok} status=${result.status} complete=${result.complete} modelChars=${result.modelText.length} markerFound=${result.markerFound} phase=${result.phase}",
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
                "R9_HANDLER_FINAL" -> main.post {
                    val p = pending ?: return@post
                    tryDirectEngineRecovery(p.seq, "R9_HANDLER_FINAL")
                }
                "R9_HANDLER_SUCCESS" -> main.post {
                    if (pending != null) setState(State.GENERATING, "R9 page handler triggered GenerateContent")
                }
                "R12_DIRECT_SUBMIT_SUCCESS" -> main.post {
                    val p = pending ?: return@post
                    directRecoverySeq = -1
                    setState(State.GENERATING, "R12 Direct Engine triggered GenerateContent")
                    readNormalized(p.seq, "r12-direct-success")
                }
                "R12_DIRECT_SUBMIT_FINAL" -> main.post {
                    val p = pending ?: return@post
                    if (directRecoverySeq == p.seq) {
                        directRecoverySeq = -1
                        tryLegacyProgrammaticFallback(p.seq, "R12_DIRECT_SUBMIT_FINAL")
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
            WebViewCompat.addDocumentStartJavaScript(webView, R11_BROAD_FALLBACK_GUARD, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR11Support.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR11RequestFix.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionR11SubmitTargetFix.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionDirectEngine.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))
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
                main.postDelayed({ inspectDirectEngine("page-finished") }, 1_100L)
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
                val mark = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.markFileChooserServed?window.__AIS_R11_SUPPORT__.markFileChooserServed(${JSONObject.quote(item.name)},${JSONObject.quote(item.mimeType)},${item.size}):({ok:false,error:'r11-mark-file-not-installed'}))"
                this@AiStudioWebSessionExecutor.webView.evaluateJavascript(mark) { raw ->
                    events?.onLog("R18_ATTACHMENT_URI_SERVED", decodeEvalValue(raw).take(5000))
                    main.postDelayed({ pollAttachment(item.token) }, 250L)
                }
                return true
            }
        }
    }

    private fun inspectDirectEngine(source: String) {
        if (destroyed || !pageFinished) return
        val script = "JSON.stringify(window.__AIS_DIRECT_ENGINE__ ? window.__AIS_DIRECT_ENGINE__.describe() : ({ok:false,error:'direct-engine-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            events?.onLog("R12_DIRECT_ENGINE_STATE", "source=$source ${decodeEvalValue(raw).take(12000)}")
        }
    }

    companion object {
        const val VERSION = "2026-09-04-web-session-r12.3-upload-ready-native-submit"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val ATTACHMENT_TIMEOUT_MS = 300_000L
        private const val ATTACHMENT_READY_SETTLE_MS = 1_200L
        private const val ATTACHMENT_READY_STABLE_SCANS = 3
        private const val FIXED_TIMEOUT_MAX_MS = 300_000L
        private const val FIRST_PROGRESS_TIMEOUT_MS = 300_000L
        private const val PROGRESS_IDLE_TIMEOUT_MS = 60_000L
        private const val PROGRESS_HARD_TIMEOUT_MS = 900_000L
        private const val WATCHDOG_TICK_MS = 2_000L
        private const val DIRECT_ENGINE_WATCHDOG_MS = 7_500L
        private const val LEGACY_FALLBACK_CHECK_MS = 900L
        private const val NATIVE_SUBMIT_ACK_MS = 1_250L
        private const val NATIVE_SUBMIT_RETRY_MS = 900L
        private const val NATIVE_SUBMIT_MAX_RETRIES = 3
        private const val R11_BROAD_FALLBACK_GUARD = "(function(){try{var r=window.__AIS_ADAPTIVE_RUNTIME__;if(r&&typeof r.generate==='function'){r.generate.__aisR11AttachmentFallback=true;}}catch(_){}})();"
    }
}
