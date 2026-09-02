package com.oai.geminilivetranslate.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.ui.AiStudioWebSessionAdaptiveRuntime
import com.oai.geminilivetranslate.ui.AiStudioWebSessionHttpStatusGuard
import com.oai.geminilivetranslate.ui.AiStudioWebSessionLabScripts
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11SubmitTargetFix
import com.oai.geminilivetranslate.ui.AiStudioWebSessionResponseCore
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R10 production-shaped executor for the authenticated AI Studio web session.
 *
 * Android callers use start() + generate(prompt). The executor owns WebView/JS bridge details,
 * enforces single-flight generation, has bounded timeout/cancel behavior, and never copies auth
 * headers/cookies/API-key values out of the page. The page itself remains the authenticated client.
 *
 * R10.2/R11.4 attachment recovery is deliberately separate from the proven text path. If R9 reaches
 * R9_HANDLER_FINAL while a file is attached, Android asks the composer-aware runtime for the exact
 * submit control that belongs to the prompt+attachment composer. Android then maps its DOM viewport
 * coordinates to WebView coordinates and dispatches a touchscreen DOWN/UP pair. This mirrors the
 * real manual click that device diagnostics proved can start video GenerateContent. Only if that
 * trusted tap fails does the JS button/listener fallback run.
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

    val webView: WebView = WebView(context)

    private val main = Handler(Looper.getMainLooper())
    private var state: State = State.NEW
    private var destroyed = false
    private var pageFinished = false
    private var seq = 0
    private var pending: Pending? = null

    private data class Pending(
        val seq: Int,
        val marker: String,
        val callback: (Result) -> Unit,
    )

    private data class HiddenViewState(
        val view: View,
        val visibility: Int,
        val alpha: Float,
    )

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AiStudioWebSessionExecutor must be created on main thread" }
        configureWebView()
    }

    fun currentState(): State = state

    fun start(url: String = NEW_CHAT_URL) {
        if (destroyed) return
        setState(State.LOADING, "loading AI Studio")
        pageFinished = false
        webView.loadUrl(url)
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
        val request = Pending(seq, marker, callback)
        pending = request
        setState(State.GENERATING, "request=${request.seq}")
        val expression = "window.__AIS_ADAPTIVE_RUNTIME__ ? window.__AIS_ADAPTIVE_RUNTIME__.generate(${JSONObject.quote(prompt)},${JSONObject.quote(marker)}) : ({ok:false,error:'runtime-not-installed'})"
        webView.evaluateJavascript("JSON.stringify($expression)") { raw ->
            if (pending?.seq != request.seq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R10_DISPATCH", decoded.take(8000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") != true) {
                finish(request.seq, Result(ok = false, error = obj?.optString("error").orEmpty().ifBlank { "DISPATCH_FAILED" }))
            } else {
                schedulePolls(request.seq)
            }
        }

        main.postDelayed({
            if (pending?.seq == request.seq) {
                runCatching { webView.evaluateJavascript("window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.cancel()", null) }
                finish(request.seq, Result(ok = false, error = "TIMEOUT"))
            }
        }, timeoutMs.coerceIn(2_000L, 60_000L))
        return true
    }

    fun cancelCurrent(): Boolean {
        val p = pending ?: return false
        pending = null
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
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        events?.onStateChanged(State.DESTROYED, "destroyed")
    }

    private fun schedulePolls(requestSeq: Int) {
        listOf(450L, 900L, 1_500L, 2_500L, 4_000L, 6_500L, 9_000L, 13_000L).forEach { delay ->
            main.postDelayed({ if (pending?.seq == requestSeq) readNormalized(requestSeq, "poll-$delay") }, delay)
        }
    }

    private fun readNormalized(requestSeq: Int, source: String) {
        if (pending?.seq != requestSeq) return
        val script = "JSON.stringify(window.__AIS_RESPONSE_CORE__ ? window.__AIS_RESPONSE_CORE__.getNormalized() : ({ok:false,error:'response-core-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R10_RESPONSE_$source", decoded.take(12000))
            parseNormalized(decoded)?.let { maybeFinish(requestSeq, it) }
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
        p.callback(result)
        if (!destroyed) setState(if (pageFinished) State.READY else State.LOADING, if (result.ok) "completed" else "failed:${result.error}")
    }

    private fun tryAttachmentSubmitRecovery(requestSeq: Int) {
        if (pending?.seq != requestSeq) return
        val expression = """
            JSON.stringify((function(){
              var api=window.__AIS_R11_SUBMIT_TARGET__;
              if(!api||typeof api.discover!=='function')return {ok:false,error:'submit-target-not-installed'};
              var d=api.discover()||{};
              var n=window.__AIS_WEB_SESSION__;
              d.baselineCaptureCount=Number(n&&n.captureCount||0);
              d.viewportWidth=Number(window.innerWidth||0);
              d.viewportHeight=Number(window.innerHeight||0);
              return d;
            })())
        """.trimIndent()
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R11_SUBMIT_TRUSTED_TARGET", decoded.take(12000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            val attachmentPresent = obj?.optBoolean("attachmentPresent") == true
            val candidates = obj?.optJSONArray("candidates")
            val best = candidates?.optJSONObject(0)
            val fingerprint = best?.optJSONObject("fingerprint")
            val score = best?.optInt("score", Int.MIN_VALUE) ?: Int.MIN_VALUE
            val disabled = best?.optBoolean("disabled") == true
            val viewportWidth = obj?.optDouble("viewportWidth", 0.0) ?: 0.0
            val viewportHeight = obj?.optDouble("viewportHeight", 0.0) ?: 0.0
            val cssX = (fingerprint?.optDouble("x", Double.NaN) ?: Double.NaN) +
                (fingerprint?.optDouble("w", Double.NaN) ?: Double.NaN) / 2.0
            val cssY = (fingerprint?.optDouble("y", Double.NaN) ?: Double.NaN) +
                (fingerprint?.optDouble("h", Double.NaN) ?: Double.NaN) / 2.0

            val trustedTargetReady = attachmentPresent && best != null && !disabled && score >= TRUSTED_SUBMIT_MIN_SCORE &&
                cssX.isFinite() && cssY.isFinite() && viewportWidth > 1.0 && viewportHeight > 1.0
            if (!trustedTargetReady) {
                tryProgrammaticAttachmentSubmitRecovery(requestSeq)
                return@evaluateJavascript
            }

            dispatchTrustedWebViewTap(
                cssX = cssX,
                cssY = cssY,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            ) { dispatched ->
                if (pending?.seq != requestSeq) return@dispatchTrustedWebViewTap
                events?.onLog(
                    "R11_SUBMIT_TRUSTED_TOUCH_DISPATCHED",
                    "ok=$dispatched score=$score cssX=$cssX cssY=$cssY viewport=${viewportWidth}x$viewportHeight baseline=$baseline label=${best.optString("label").take(300)}",
                )
                if (!dispatched) {
                    tryProgrammaticAttachmentSubmitRecovery(requestSeq)
                    return@dispatchTrustedWebViewTap
                }
                main.postDelayed({
                    checkCaptureStarted(requestSeq, baseline, "trusted-touch") { started ->
                        if (pending?.seq != requestSeq) return@checkCaptureStarted
                        if (started) {
                            setState(State.GENERATING, "trusted attachment submit triggered GenerateContent")
                            readNormalized(requestSeq, "trusted-attachment-submit")
                        } else {
                            tryProgrammaticAttachmentSubmitRecovery(requestSeq)
                        }
                    }
                }, TRUSTED_SUBMIT_CAPTURE_CHECK_MS)
            }
        }
    }

    private fun tryProgrammaticAttachmentSubmitRecovery(requestSeq: Int) {
        if (pending?.seq != requestSeq) return
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.submitIfAttachment() : ({ok:false,error:'submit-target-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R11_SUBMIT_RECOVERY_DISPATCH", decoded.take(10000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val attempted = obj?.optBoolean("attempted") == true || obj?.optBoolean("pending") == true
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            if (!attempted) {
                finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                return@evaluateJavascript
            }
            main.postDelayed({
                checkCaptureStarted(requestSeq, baseline, "programmatic-fallback") { started ->
                    if (pending?.seq != requestSeq) return@checkCaptureStarted
                    if (started) {
                        setState(State.GENERATING, "attachment composer submit triggered GenerateContent")
                        readNormalized(requestSeq, "attachment-submit-recovery")
                    } else {
                        finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                    }
                }
            }, ATTACHMENT_SUBMIT_RECOVERY_CHECK_MS)
        }
    }

    private fun checkCaptureStarted(
        requestSeq: Int,
        baseline: Int,
        source: String,
        onDone: (Boolean) -> Unit,
    ) {
        if (pending?.seq != requestSeq) return
        val check = "JSON.stringify((function(b){var n=window.__AIS_WEB_SESSION__;return {ok:true,baseline:b,captureCount:Number(n&&n.captureCount||0),started:Number(n&&n.captureCount||0)>b};})($baseline))"
        webView.evaluateJavascript(check) { captureRaw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val captureDecoded = decodeEvalValue(captureRaw)
            events?.onLog("R11_SUBMIT_RECOVERY_RESULT", "source=$source ${captureDecoded.take(4000)}")
            val captureObj = runCatching { JSONObject(captureDecoded) }.getOrNull()
            onDone(captureObj?.optBoolean("started") == true)
        }
    }

    private fun dispatchTrustedWebViewTap(
        cssX: Double,
        cssY: Double,
        viewportWidth: Double,
        viewportHeight: Double,
        onDone: (Boolean) -> Unit,
    ) {
        val hidden = mutableListOf<HiddenViewState>()
        var node: View? = webView
        var guard = 0
        while (node != null && guard++ < 8) {
            if (node.visibility != View.VISIBLE) {
                hidden += HiddenViewState(node, node.visibility, node.alpha)
                node.alpha = 0f
                node.visibility = View.VISIBLE
            }
            node = node.parent as? View
        }
        webView.requestLayout()
        webView.postDelayed({
            val width = webView.width
            val height = webView.height
            if (width <= 2 || height <= 2 || viewportWidth <= 1.0 || viewportHeight <= 1.0) {
                restoreHiddenViews(hidden)
                events?.onLog("R11_SUBMIT_TRUSTED_TOUCH", "ok=false reason=WEBVIEW_NOT_LAID_OUT width=$width height=$height viewport=${viewportWidth}x$viewportHeight")
                onDone(false)
                return@postDelayed
            }
            val x = (cssX / viewportWidth * width).toFloat().coerceIn(1f, (width - 2).toFloat())
            val y = (cssY / viewportHeight * height).toFloat().coerceIn(1f, (height - 2).toFloat())
            val downAt = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val up = MotionEvent.obtain(downAt, downAt + TRUSTED_TAP_DURATION_MS, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val downHandled = runCatching { webView.dispatchTouchEvent(down) }.getOrDefault(false)
            val upHandled = runCatching { webView.dispatchTouchEvent(up) }.getOrDefault(false)
            down.recycle()
            up.recycle()
            val handled = downHandled || upHandled
            events?.onLog(
                "R11_SUBMIT_TRUSTED_TOUCH",
                "ok=$handled x=${x.toInt()} y=${y.toInt()} width=$width height=$height cssX=$cssX cssY=$cssY viewport=${viewportWidth}x$viewportHeight downHandled=$downHandled upHandled=$upHandled",
            )
            main.postDelayed({
                restoreHiddenViews(hidden)
                onDone(handled)
            }, 300L)
        }, 120L)
    }

    private fun restoreHiddenViews(hidden: List<HiddenViewState>) {
        hidden.asReversed().forEach { item ->
            item.view.alpha = item.alpha
            item.view.visibility = item.visibility
        }
    }

    private fun parseNormalized(decoded: String): Result? {
        val obj = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        if (obj.optString("error") == "no-result") return null
        val status = obj.optInt("status", -1)
        val ok = obj.optBoolean("ok")
        val explicitError = obj.optString("error")
        return Result(
            ok = ok,
            status = status,
            modelText = obj.optString("modelText"),
            markerFound = obj.optBoolean("markerFound"),
            complete = obj.optBoolean("complete") || (!ok && status >= 400),
            phase = obj.optString("phase"),
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
            when (kind) {
                "NORMALIZED_GENERATE_RESULT" -> if (payload != null) {
                    main.post {
                        val p = pending ?: return@post
                        maybeFinish(p.seq, parseNormalized(payload.toString()) ?: return@post)
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
                    tryAttachmentSubmitRecovery(p.seq)
                }
                "R9_HANDLER_SUCCESS" -> main.post {
                    if (pending != null) setState(State.GENERATING, "page handler triggered GenerateContent")
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
        webView.webChromeClient = WebChromeClient()
    }

    companion object {
        const val VERSION = "2026-09-02-web-session-r10.3-trusted-attachment-submit"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val TRUSTED_SUBMIT_MIN_SCORE = 2_500
        private const val TRUSTED_SUBMIT_CAPTURE_CHECK_MS = 850L
        private const val ATTACHMENT_SUBMIT_RECOVERY_CHECK_MS = 850L
        private const val TRUSTED_TAP_DURATION_MS = 70L
        private const val R11_BROAD_FALLBACK_GUARD = "(function(){try{var r=window.__AIS_ADAPTIVE_RUNTIME__;if(r&&typeof r.generate==='function'){r.generate.__aisR11AttachmentFallback=true;}}catch(_){}})();"
    }
}
