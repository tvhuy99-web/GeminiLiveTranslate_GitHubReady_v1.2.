package com.oai.geminilivetranslate.core.aistudio

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener

/** Android-side controller for the experimental AI Studio request gateway. */
object AiStudioRequestGateway {
    data class Status(
        val installed: Boolean,
        val templateCount: Int = 0,
        val models: List<String> = emptyList(),
        val proofCandidateCount: Int = 0,
        val raw: String = "",
    )

    data class ReplayStart(
        val ok: Boolean,
        val replayId: String = "",
        val error: String = "",
        val detail: String = "",
        val raw: String = "",
    )

    data class ReplayEvent(
        val type: String,
        val status: Int = 0,
        val text: String = "",
        val message: String = "",
        val raw: String = "",
    )

    fun installDocumentStart(webView: WebView): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        return WebViewCompat.addDocumentStartJavaScript(
            webView,
            AiStudioRequestGatewayScript.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
    }

    fun readStatus(webView: WebView, callback: (Status) -> Unit) {
        val expression = "JSON.stringify(window.__AIS_REQUEST_GATEWAY__ ? window.__AIS_REQUEST_GATEWAY__.describe() : ({ok:false,error:'gateway-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj == null || obj.optBoolean("ok") != true) {
                callback(Status(installed = false, raw = decoded))
                return@evaluateJavascript
            }
            val modelsArray = obj.optJSONArray("models")
            val models = buildList {
                if (modelsArray != null) {
                    for (i in 0 until modelsArray.length()) {
                        modelsArray.optString(i).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            callback(
                Status(
                    installed = true,
                    templateCount = obj.optInt("templateCount", 0),
                    models = models,
                    proofCandidateCount = obj.optJSONObject("proof")?.optInt("candidateCount", 0) ?: 0,
                    raw = decoded,
                ),
            )
        }
    }

    fun readCapturedBody(webView: WebView, model: String? = null, callback: (String) -> Unit) {
        val modelArg = model?.let(JSONObject::quote) ?: "null"
        val expression = "window.__AIS_REQUEST_GATEWAY__ ? JSON.stringify(window.__AIS_REQUEST_GATEWAY__.getCapturedBody($modelArg)) : JSON.stringify('')"
        webView.evaluateJavascript(expression) { raw -> callback(decodeEvalValue(raw)) }
    }

    fun startReplay(
        webView: WebView,
        body: String,
        model: String? = null,
        timeoutMs: Long = 120_000L,
        callback: (ReplayStart) -> Unit,
    ) {
        val modelArg = model?.let(JSONObject::quote) ?: "null"
        val expression = buildString {
            append("JSON.stringify(window.__AIS_REQUEST_GATEWAY__ ? window.__AIS_REQUEST_GATEWAY__.startReplay(")
            append(JSONObject.quote(body))
            append(',')
            append(timeoutMs.coerceAtLeast(1_000L))
            append(',')
            append(modelArg)
            append(") : ({ok:false,error:'gateway-not-installed'}))")
        }
        webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            callback(
                ReplayStart(
                    ok = obj?.optBoolean("ok") == true,
                    replayId = obj?.optString("rid").orEmpty(),
                    error = obj?.optString("error").orEmpty(),
                    detail = obj?.optString("detail").orEmpty(),
                    raw = decoded,
                ),
            )
        }
    }

    fun nextReplayEvent(webView: WebView, replayId: String, callback: (ReplayEvent) -> Unit) {
        val expression = "JSON.stringify(window.__AIS_REQUEST_GATEWAY__ ? window.__AIS_REQUEST_GATEWAY__.nextEvent(${JSONObject.quote(replayId)}) : ({type:'missing'}))"
        webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            callback(
                ReplayEvent(
                    type = obj?.optString("type").orEmpty().ifBlank { "missing" },
                    status = obj?.optInt("status", 0) ?: 0,
                    text = obj?.optString("text").orEmpty(),
                    message = obj?.optString("message").orEmpty(),
                    raw = decoded,
                ),
            )
        }
    }

    fun abortReplay(webView: WebView, replayId: String, callback: (Boolean) -> Unit = {}) {
        val expression = "Boolean(window.__AIS_REQUEST_GATEWAY__ && window.__AIS_REQUEST_GATEWAY__.abortReplay(${JSONObject.quote(replayId)}))"
        webView.evaluateJavascript(expression) { raw -> callback(raw == "true") }
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

    private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
}
