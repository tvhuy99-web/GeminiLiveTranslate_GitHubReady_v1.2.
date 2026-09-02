package com.oai.geminilivetranslate.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
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
 * R10.2 adds one attachment-only recovery hook: if the proven text handler route reaches
 * R9_HANDLER_FINAL, Android asks the R11.4 composer-aware submit runtime to press the submit control
 * that belongs to the active prompt+attachment composer before declaring failure.
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
                if (pending?.seq != requestSeq) return@postDelayed
                val check = "JSON.stringify((function(b){var n=window.__AIS_WEB_SESSION__;return {ok:true,baseline:b,captureCount:Number(n&&n.captureCount||0),started:Number(n&&n.captureCount||0)>b};})($baseline))"
                webView.evaluateJavascript(check) check@{ captureRaw ->
                    if (pending?.seq != requestSeq) return@check
                    val captureDecoded = decodeEvalValue(captureRaw)
                    events?.onLog("R11_SUBMIT_RECOVERY_RESULT", captureDecoded.take(4000))
                    val captureObj = runCatching { JSONObject(captureDecoded) }.getOrNull()
                    if (captureObj?.optBoolean("started") == true) {
                        setState(State.GENERATING, "attachment composer submit triggered GenerateContent")
                        readNormalized(requestSeq, "attachment-submit-recovery")
                    } else {
                        finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                    }
                }
            }, ATTACHMENT_SUBMIT_RECOVERY_CHECK_MS)
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
        const val VERSION = "2026-09-02-web-session-r10.2-attachment-submit-target"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val ATTACHMENT_SUBMIT_RECOVERY_CHECK_MS = 850L
    }
}
