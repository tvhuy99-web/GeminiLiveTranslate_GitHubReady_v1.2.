package com.oai.geminilivetranslate.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import com.oai.geminilivetranslate.ui.AiStudioRequestGatewayScript
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Native facade for [AiStudioRequestGatewayScript].
 *
 * The raw captured request, request headers and Google session credentials remain inside WebView.
 * Native code sends semantic rewrite instructions and polls only sanitized result/progress objects.
 */
class AiStudioRequestGateway(
    private val webView: WebView,
) {
    data class Status(
        val available: Boolean,
        val version: String = "",
        val templateReady: Boolean = false,
        val templateModel: String = "",
        val templateFingerprint: String = "",
        val templateBodyChars: Int = 0,
        val proofReady: Boolean = false,
        val proofFunctionDetected: Boolean = false,
        val activeRequests: Int = 0,
        val error: String = "",
    )

    data class ReplayResult(
        val ok: Boolean,
        val status: Int = 0,
        val modelText: String = "",
        val responseChars: Int = 0,
        val phase: String = "",
        val error: String = "",
        val model: String = "",
        val fingerprint: String = "",
    )

    private val main = Handler(Looper.getMainLooper())
    private val activeIds = LinkedHashSet<String>()

    /** Install the browser hooks before the first GenerateContent request that should be captured. */
    fun install(callback: (Status) -> Unit = {}) {
        main.post {
            webView.evaluateJavascript(AiStudioRequestGatewayScript.DOCUMENT_START) {
                readStatus(callback)
            }
        }
    }

    fun status(callback: (Status) -> Unit) {
        main.post { readStatus(callback) }
    }

    /**
     * Replays a captured GenerateContent template with a new model/prompt.
     *
     * A fresh BotGuard snapshot is required by default. If the browser has not observed the
     * AI Studio proof service yet, the request fails closed with PROOF_NOT_READY instead of
     * sending a stale proof.
     *
     * While the legacy R11 request rewriter remains installed, replay is also fail-closed when
     * R11 has a different selected model. This prevents a replay from being silently rewritten
     * to another model by the older compatibility layer.
     */
    fun replayText(
        model: String,
        prompt: String,
        timeoutMs: Long = 120_000L,
        onPartial: (String) -> Unit = {},
        callback: (ReplayResult) -> Unit,
    ) {
        main.post {
            val normalizedModel = model.trim().removePrefix("models/")
            if (normalizedModel.isBlank()) {
                callback(ReplayResult(ok = false, error = "MODEL_BLANK"))
                return@post
            }
            val args = JSONObject()
                .put("model", normalizedModel)
                .put("prompt", prompt)
                .put("timeoutMs", timeoutMs.coerceIn(1_000L, 20 * 60_000L))
                .put("refreshSnapshot", true)
            val expression =
                """
                JSON.stringify((function(args){
                  var r11=window.__AIS_R11_REQUEST_FIX__;
                  var selected=String(r11&&r11.selectedModel||'').trim().replace(/^models\//i,'');
                  if(selected && selected!==String(args.model||'')) {
                    return {ok:false,error:'R11_MODEL_CONFLICT',selectedModel:selected,requestedModel:String(args.model||'')};
                  }
                  return window.__AIS_REQUEST_GATEWAY__
                    ? window.__AIS_REQUEST_GATEWAY__.startReplay(args)
                    : {ok:false,error:'GATEWAY_NOT_INSTALLED'};
                })($args))
                """.trimIndent()
            evalJson(expression) { started ->
                val id = started?.optString("id").orEmpty()
                if (started?.optBoolean("ok") != true || id.isBlank()) {
                    callback(
                        ReplayResult(
                            ok = false,
                            error = started?.optString("error").orEmpty().ifBlank { "GATEWAY_START_FAILED" },
                            model = started?.optString("requestedModel").orEmpty(),
                        ),
                    )
                    return@evalJson
                }
                activeIds += id
                pollResult(
                    id = id,
                    deadlineAt = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(1_000L) + RESULT_GRACE_MS,
                    previousPartialChars = 0,
                    onPartial = onPartial,
                    callback = callback,
                )
            }
        }
    }

    fun abortAll() {
        main.post {
            val ids = activeIds.toList()
            activeIds.clear()
            ids.forEach { id ->
                webView.evaluateJavascript(
                    "window.__AIS_REQUEST_GATEWAY__ && window.__AIS_REQUEST_GATEWAY__.abort(${JSONObject.quote(id)})",
                    null,
                )
            }
        }
    }

    fun clearTemplates(callback: (Boolean) -> Unit = {}) {
        main.post {
            webView.evaluateJavascript(
                "Boolean(window.__AIS_REQUEST_GATEWAY__ && window.__AIS_REQUEST_GATEWAY__.clearTemplates())",
            ) { raw -> callback(raw == "true") }
        }
    }

    private fun readStatus(callback: (Status) -> Unit) {
        evalJson(
            "JSON.stringify(window.__AIS_REQUEST_GATEWAY__ ? window.__AIS_REQUEST_GATEWAY__.status() : ({ok:false,error:'GATEWAY_NOT_INSTALLED'}))",
        ) { obj ->
            if (obj == null) {
                callback(Status(available = false, error = "INVALID_STATUS"))
                return@evalJson
            }
            callback(
                Status(
                    available = obj.optBoolean("ok", false),
                    version = obj.optString("version"),
                    templateReady = obj.optBoolean("templateReady", false),
                    templateModel = obj.optString("templateModel"),
                    templateFingerprint = obj.optString("templateFingerprint"),
                    templateBodyChars = obj.optInt("templateBodyChars", 0),
                    proofReady = obj.optBoolean("proofReady", false),
                    proofFunctionDetected = obj.optBoolean("proofFunctionDetected", false),
                    activeRequests = obj.optInt("activeRequests", 0),
                    error = obj.optString("error"),
                ),
            )
        }
    }

    private fun pollResult(
        id: String,
        deadlineAt: Long,
        previousPartialChars: Int,
        onPartial: (String) -> Unit,
        callback: (ReplayResult) -> Unit,
    ) {
        if (SystemClock.uptimeMillis() >= deadlineAt) {
            activeIds.remove(id)
            webView.evaluateJavascript(
                "window.__AIS_REQUEST_GATEWAY__ && window.__AIS_REQUEST_GATEWAY__.abort(${JSONObject.quote(id)})",
                null,
            )
            callback(ReplayResult(ok = false, error = "NATIVE_RESULT_TIMEOUT"))
            return
        }

        val expression =
            "JSON.stringify(window.__AIS_REQUEST_GATEWAY__ ? window.__AIS_REQUEST_GATEWAY__.takeResult(${JSONObject.quote(id)}) : ({ok:false,error:'GATEWAY_NOT_INSTALLED'}))"
        evalJson(expression) { obj ->
            if (obj == null || obj.optBoolean("ok") != true) {
                activeIds.remove(id)
                callback(ReplayResult(ok = false, error = obj?.optString("error").orEmpty().ifBlank { "INVALID_GATEWAY_RESULT" }))
                return@evalJson
            }

            if (obj.optBoolean("pending", false)) {
                val progress = obj.optJSONObject("progress")
                val partial = progress?.optString("modelText").orEmpty()
                val nextChars = partial.length
                if (nextChars > previousPartialChars && partial.isNotBlank()) onPartial(partial)
                main.postDelayed(
                    {
                        pollResult(
                            id,
                            deadlineAt,
                            maxOf(previousPartialChars, nextChars),
                            onPartial,
                            callback,
                        )
                    },
                    POLL_MS,
                )
                return@evalJson
            }

            activeIds.remove(id)
            val result = obj.optJSONObject("result")
            if (result == null) {
                callback(ReplayResult(ok = false, error = "MISSING_GATEWAY_RESULT"))
                return@evalJson
            }
            callback(
                ReplayResult(
                    ok = result.optBoolean("ok", false),
                    status = result.optInt("status", 0),
                    modelText = result.optString("modelText"),
                    responseChars = result.optInt("responseChars", 0),
                    phase = result.optString("phase"),
                    error = result.optString("error"),
                    model = result.optString("model"),
                    fingerprint = result.optString("fingerprint"),
                ),
            )
        }
    }

    private fun evalJson(expression: String, callback: (JSONObject?) -> Unit) {
        webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            callback(runCatching { JSONObject(decoded) }.getOrNull())
        }
    }

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val value = JSONTokener(raw).nextValue()) {
                is String -> value
                else -> value.toString()
            }
        }.getOrElse { raw }
    }

    private companion object {
        const val POLL_MS = 120L
        const val RESULT_GRACE_MS = 5_000L
    }
}
