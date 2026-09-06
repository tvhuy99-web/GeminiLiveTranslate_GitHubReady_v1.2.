package com.oai.geminilivetranslate.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.core.SessionLogger
import org.json.JSONObject
import org.json.JSONTokener


internal class AiStudioModelCatalogLoader(
    private val activity: AppCompatActivity,
    private val logger: SessionLogger,
) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var host: FrameLayout? = null
    private var callback: ((List<String>, String) -> Unit)? = null
    private var discoveryStarted = false

    @SuppressLint("SetJavaScriptEnabled")
    fun load(onDone: (List<String>, String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AI Studio model catalog must start on main thread"
        }
        cancel()
        callback = onDone
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            finish(emptyList(), "DOCUMENT_START_SCRIPT unsupported")
            return
        }

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root == null) {
            finish(emptyList(), "no activity content root")
            return
        }

        val view = WebView(activity)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        WebViewCompat.addDocumentStartJavaScript(
            view,
            AiStudioWebSessionR11Support.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )

        val container = FrameLayout(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isFocusableInTouchMode = false
        }
        host = container
        val screenHeight = activity.resources.displayMetrics.heightPixels.coerceAtLeast(800)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                screenHeight,
                Gravity.BOTTOM,
            ),
        )
        root.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenHeight),
        )
        container.translationY = (screenHeight * 2).toFloat()

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                logger.log(3, "ApiSettings", "R34_MODEL_WEB_PAGE_STARTED host=${safeHost(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val hostName = safeHost(url)
                logger.log(3, "ApiSettings", "R34_MODEL_WEB_PAGE_FINISHED host=$hostName")
                if (hostName == "aistudio.google.com" && !discoveryStarted) {
                    discoveryStarted = true
                    main.postDelayed({ scanCatalog(0) }, 700L)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    logger.log(1, "ApiSettings", "R34_MODEL_WEB_ERROR code=${error?.errorCode}")
                }
            }
        }

        logger.log(2, "ApiSettings", "R34_VIDEO_MODEL_LIST_WEB_START host=aistudio.google.com")
        view.loadUrl(AI_STUDIO_MODELS_URL)
        main.postDelayed({
            if (callback != null && !discoveryStarted) {
                discoveryStarted = true
                scanCatalog(0)
            }
        }, 3_500L)
        main.postDelayed({
            if (callback != null) finish(emptyList(), "AI Studio model catalog timeout")
        }, TIMEOUT_MS)
    }

    fun cancel() {
        callback = null
        cleanup()
    }

    private fun scanCatalog(attempt: Int) {
        val view = webView ?: return
        if (callback == null) return
        val discover = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.discoverModels?window.__AIS_R11_SUPPORT__.discoverModels():({ok:false,error:'r11-model-discovery-not-installed'}))"
        view.evaluateJavascript(discover) { raw ->
            if (callback == null) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            val models = parseModels(decoded)
            logger.log(
                3,
                "ApiSettings",
                "R34_VIDEO_MODEL_LIST_WEB_SCAN attempt=${attempt + 1} usable=${models.size}",
            )
            if (attempt == 0) {
                val openPicker = "JSON.stringify(window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.openModelPicker?window.__AIS_R11_SUPPORT__.openModelPicker():({ok:false,error:'r11-model-picker-not-installed'}))"
                view.evaluateJavascript(openPicker) { pickerRaw ->
                    logger.log(
                        3,
                        "ApiSettings",
                        "R34_VIDEO_MODEL_PICKER ${decodeEvalValue(pickerRaw).take(500)}",
                    )
                    main.postDelayed({ scanCatalog(1) }, 900L)
                }
            } else if (models.size >= MIN_WEB_MODELS) {
                finish(models, "")
            } else if (attempt < MAX_SCANS - 1) {
                main.postDelayed({ scanCatalog(attempt + 1) }, 900L)
            } else {
                finish(emptyList(), "AI Studio returned only ${models.size} usable model(s)")
            }
        }
    }

    private fun parseModels(decoded: String): List<String> {
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("models") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until array.length()) {
            val id = array.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) ids += id
        }
        return videoDescriptionCandidates(ids)
    }

    private fun finish(models: List<String>, error: String) {
        val done = callback ?: return
        callback = null
        val safe = videoDescriptionCandidates(models)
        cleanup()
        done(safe, error)
    }

    private fun cleanup() {
        main.removeCallbacksAndMessages(null)
        val view = webView
        val container = host
        webView = null
        host = null
        discoveryStarted = false
        runCatching { view?.stopLoading() }
        runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
        runCatching { (container?.parent as? ViewGroup)?.removeView(container) }
        runCatching { view?.destroy() }
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

    private fun safeHost(raw: String?): String = runCatching {
        android.net.Uri.parse(raw.orEmpty()).host.orEmpty().lowercase()
    }.getOrDefault("")

    companion object {
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_MODELS_URL = "https://aistudio.google.com/u/0/prompts/new_chat"
        private const val TIMEOUT_MS = 15_000L
        private const val MAX_SCANS = 5
        private const val MIN_WEB_MODELS = 2

        internal fun videoDescriptionCandidates(rawModels: Collection<String>): List<String> =
            rawModels.asSequence()
                .map { it.trim().removePrefix("models/") }
                .filter {
                    it.matches(
                        Regex(
                            "^gemini-[a-z0-9][a-z0-9._-]{2,110}$",
                            RegexOption.IGNORE_CASE,
                        ),
                    )
                }
                .filterNot { id ->
                    val value = id.lowercase()
                    value.contains("transcribe") ||
                        value.contains("embedding") ||
                        value.contains("-tts") ||
                        value.contains("tts-") ||
                        value.contains("-live") ||
                        value.startsWith("gemini-live") ||
                        value.contains("imagen") ||
                        value.contains("-image") ||
                        value.contains("image-") ||
                        value.contains("computer-use")
                }
                .distinct()
                .sorted()
                .toList()
    }
}
