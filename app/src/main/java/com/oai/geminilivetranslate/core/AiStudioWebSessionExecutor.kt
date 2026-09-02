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
import com.oai.geminilivetranslate.ui.AiStudioWebSessionDirectEngine
import com.oai.geminilivetranslate.ui.AiStudioWebSessionHttpStatusGuard
import com.oai.geminilivetranslate.ui.AiStudioWebSessionLabScripts
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR11SubmitTargetFix
import com.oai.geminilivetranslate.ui.AiStudioWebSessionResponseCore
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R12 production-shaped executor for an authenticated AI Studio web session.
 *
 * The proven R9 adaptive path remains first for text because it already generates real
 * MakerSuiteService/GenerateContent requests reliably. If R9 reaches R9_HANDLER_FINAL, R12 no
 * longer tries to synthesize a physical tap. It invokes the page-local Direct Engine, which calls
 * AI Studio's own prompt/input and submit/click handlers directly. Only if that deep path cannot
 * create GenerateContent do we retain the old programmatic composer click as a diagnostic fallback.
 *
 * Auth headers/cookies/API-key values never leave the page. R12 may retain a GenerateContent request
 * template privately in JavaScript memory for research/replay, but native logs receive metadata only.
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
    private var directRecoverySeq = -1

    private data class Pending(
        val seq: Int,
        val prompt: String,
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
        val request = Pending(seq, prompt, marker, callback)
        pending = request
        directRecoverySeq = -1
        setState(State.GENERATING, "request=${request.seq}")
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
        webView.webChromeClient = WebChromeClient()
    }

    private fun inspectDirectEngine(source: String) {
        if (destroyed || !pageFinished) return
        val script = "JSON.stringify(window.__AIS_DIRECT_ENGINE__ ? window.__AIS_DIRECT_ENGINE__.describe() : ({ok:false,error:'direct-engine-not-installed'}))"
        webView.evaluateJavascript(script) { raw ->
            events?.onLog("R12_DIRECT_ENGINE_STATE", "source=$source ${decodeEvalValue(raw).take(12000)}")
        }
    }

    companion object {
        const val VERSION = "2026-09-02-web-session-r12.0-direct-engine-executor"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val DIRECT_ENGINE_WATCHDOG_MS = 7_500L
        private const val LEGACY_FALLBACK_CHECK_MS = 900L
        private const val R11_BROAD_FALLBACK_GUARD = "(function(){try{var r=window.__AIS_ADAPTIVE_RUNTIME__;if(r&&typeof r.generate==='function'){r.generate.__aisR11AttachmentFallback=true;}}catch(_){}})();"
    }
}
