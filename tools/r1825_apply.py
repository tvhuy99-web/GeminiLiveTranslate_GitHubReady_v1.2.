from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt")
main = main_path.read_text()

main = replace_once(
    main,
    '''        val service = translationService
        if (service != null) {
            service.setSelectedFile(uri, name)
        } else {
            pendingSelectedUri = uri
            pendingSelectedFileName = name
        }
        binding.selectFileButton.text = name ?: "Tệp đã chọn"
        toast("Đã chọn: ${name ?: uri.lastPathSegment}")
''',
    '''        rememberSelectedFile(uri, name)
        toast("Đã chọn: ${name ?: uri.lastPathSegment}")
''',
    "file-picker persistence",
)

main = replace_once(
    main,
    '''            pendingSelectedUri?.let { uri ->
                translationService?.setSelectedFile(uri, pendingSelectedFileName)
                pendingSelectedUri = null
                pendingSelectedFileName = null
            }
''',
    '''            restorePersistedSelectedFile("service-connected", applyToService = true)
''',
    "service reconnect restore",
)

main = replace_once(
    main,
    '''        setupUi()
        requestNotificationPermissionIfNeeded()
''',
    '''        setupUi()
        restorePersistedSelectedFile("create", applyToService = false)
        requestNotificationPermissionIfNeeded()
''',
    "onCreate restore",
)

main = replace_once(
    main,
    '''    override fun onResume() {
        super.onResume()
        restorePreferencesUi()
    }
''',
    '''    override fun onResume() {
        super.onResume()
        restorePreferencesUi()
        restorePersistedSelectedFile("resume", applyToService = false)
    }
''',
    "onResume restore",
)

main = replace_once(
    main,
    '''        service.setSourceMode(mode)
        service.setProcessingMode(preferences.loadProcessingMode())
''',
    '''        if (mode == SourceMode.FILE) {
            restorePersistedSelectedFile("before-start", applyToService = true)
        }
        service.setSourceMode(mode)
        service.setProcessingMode(preferences.loadProcessingMode())
''',
    "before start restore",
)

main = replace_once(
    main,
    '''    private fun displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun requestNotificationPermissionIfNeeded() {
''',
    '''    private fun displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun rememberSelectedFile(uri: Uri, name: String?) {
        pendingSelectedUri = uri
        pendingSelectedFileName = name
        uiPrefs.edit()
            .putString(KEY_SELECTED_FILE_URI, uri.toString())
            .putString(KEY_SELECTED_FILE_NAME, name)
            .apply()
        translationService?.setSelectedFile(uri, name)
        binding.selectFileButton.text = name ?: displayName(uri) ?: "Tệp đã chọn"
        logger.log(
            2,
            "UI",
            "R33_SELECTED_FILE_PERSISTED name=${name ?: uri.lastPathSegment ?: "unknown"} uriScheme=${uri.scheme}",
        )
    }

    private fun restorePersistedSelectedFile(reason: String, applyToService: Boolean): Boolean {
        val raw = uiPrefs.getString(KEY_SELECTED_FILE_URI, null)?.takeIf(String::isNotBlank)
            ?: return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val storedName = uiPrefs.getString(KEY_SELECTED_FILE_NAME, null)?.takeIf(String::isNotBlank)
        val hasPersistedReadGrant = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        val readable = hasPersistedReadGrant || runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) {
            uiPrefs.edit()
                .remove(KEY_SELECTED_FILE_URI)
                .remove(KEY_SELECTED_FILE_NAME)
                .apply()
            pendingSelectedUri = null
            pendingSelectedFileName = null
            logger.log(
                1,
                "UI",
                "R33_SELECTED_FILE_RESTORE_FAILED reason=$reason uriScheme=${uri.scheme} persistedGrant=$hasPersistedReadGrant",
            )
            return false
        }
        val name = storedName ?: displayName(uri)
        pendingSelectedUri = uri
        pendingSelectedFileName = name
        if (applyToService) translationService?.setSelectedFile(uri, name)
        if (::binding.isInitialized) {
            binding.selectFileButton.text = name ?: "Tệp đã chọn"
        }
        logger.log(
            2,
            "UI",
            "R33_SELECTED_FILE_RESTORED reason=$reason applyToService=$applyToService name=${name ?: uri.lastPathSegment ?: "unknown"} uriScheme=${uri.scheme} persistedGrant=$hasPersistedReadGrant",
        )
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
''',
    "selected file helpers",
)

main = replace_once(
    main,
    '''        private const val KEY_SOURCE_MODE = "lastSourceMode"
        private const val STATE_PLAYBACK_RETURN_SESSION_ID = "state.playbackReturnSessionId"
        private const val KEY_FILE_PLAYBACK_SPEED = "filePlaybackSpeed"
''',
    '''        private const val KEY_SOURCE_MODE = "lastSourceMode"
        private const val STATE_PLAYBACK_RETURN_SESSION_ID = "state.playbackReturnSessionId"
        private const val KEY_FILE_PLAYBACK_SPEED = "filePlaybackSpeed"
        private const val KEY_SELECTED_FILE_URI = "selectedFileUri"
        private const val KEY_SELECTED_FILE_NAME = "selectedFileName"
''',
    "selected file prefs keys",
)
main_path.write_text(main)


api_path = Path("app/src/main/java/com/oai/geminilivetranslate/ui/ApiSettingsActivity.kt")
api = api_path.read_text()

api = replace_once(
    api,
    '''    private lateinit var summaryPrompt: EditText

    private val client = OkHttpClient.Builder()
''',
    '''    private lateinit var summaryPrompt: EditText
    private var modelCatalogLoader: AiStudioModelCatalogLoader? = null

    private val client = OkHttpClient.Builder()
''',
    "model loader field",
)

api = replace_once(
    api,
    '''    override fun onDestroy() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        super.onDestroy()
    }
''',
    '''    override fun onDestroy() {
        modelCatalogLoader?.cancel()
        modelCatalogLoader = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        super.onDestroy()
    }
''',
    "model loader cleanup",
)

start = api.index("    private fun fetchModels(provider: String) {")
end = api.index("    private fun testConnection()", start)
replacement = '''    private fun fetchModels(provider: String) {
        if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            fetchGeminiModelsWebFirst()
            return
        }
        fetchModelsFromApi(
            provider = provider,
            keyValue = proxyKey.text.toString().trim(),
            proxyUrlValue = proxyUrl.text.toString().trim(),
            sourceLabel = "OpenAI-compatible API",
        )
    }

    private fun fetchGeminiModelsWebFirst() {
        val fallbackKey = geminiKeysFromUi().firstOrNull().orEmpty()
        modelCatalogLoader?.cancel()
        val loader = AiStudioModelCatalogLoader(this, logger)
        modelCatalogLoader = loader
        toast("Đang tải danh sách model từ AI Studio...")
        loader.load { models, error ->
            if (modelCatalogLoader === loader) modelCatalogLoader = null
            if (isFinishing || isDestroyed) return@load
            if (models.isNotEmpty()) {
                logger.log(
                    2,
                    "ApiSettings",
                    "R34_VIDEO_MODEL_LIST source=ai-studio-web count=${models.size}",
                )
                showModelDialog(models, geminiModel, "AI Studio")
            } else {
                logger.log(
                    1,
                    "ApiSettings",
                    "R34_VIDEO_MODEL_LIST_WEB_FAILED error=${error.take(240)} fallbackApiKey=${fallbackKey.isNotBlank()}",
                )
                if (fallbackKey.isBlank()) {
                    toast("AI Studio chưa trả được danh sách model và chưa có Gemini API Key để dùng phương án dự phòng")
                } else {
                    toast("AI Studio chưa trả danh sách. Đang thử Gemini API...")
                    fetchModelsFromApi(
                        provider = AiApiSettingsStore.PROVIDER_GEMINI,
                        keyValue = fallbackKey,
                        proxyUrlValue = "",
                        sourceLabel = "Gemini API dự phòng",
                    )
                }
            }
        }
    }

    private fun fetchModelsFromApi(
        provider: String,
        keyValue: String,
        proxyUrlValue: String,
        sourceLabel: String,
    ) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { fetchModelList(provider, keyValue, proxyUrlValue) }
            }.onSuccess { models ->
                if (models.isEmpty()) {
                    toast("$sourceLabel không trả danh sách model phù hợp")
                } else {
                    val target = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) geminiModel else proxyModel
                    logger.log(
                        2,
                        "ApiSettings",
                        "R34_VIDEO_MODEL_LIST source=${sourceLabel.replace(' ', '-').lowercase()} count=${models.size}",
                    )
                    showModelDialog(models, target, sourceLabel)
                }
            }.onFailure {
                logger.log(0, "ApiSettings", "Tải danh sách model thất bại source=$sourceLabel provider=$provider", it)
                toast("Không tải được danh sách model từ $sourceLabel: ${it.message}")
            }
        }
    }

    private fun showModelDialog(models: List<String>, target: EditText, sourceLabel: String) {
        toast("Đã tải ${models.size} model từ $sourceLabel")
        AlertDialog.Builder(this@ApiSettingsActivity)
            .setTitle("CHỌN MODEL - $sourceLabel")
            .setItems(models.toTypedArray()) { _, which -> target.setText(models[which]) }
            .setNegativeButton("HỦY", null)
            .show()
    }

'''
api = api[:start] + replacement + api[end:]

api = replace_once(
    api,
    '''        if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            val array = root.optJSONArray("models")
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("name")
                        ?.removePrefix("models/")
                        ?.takeIf(String::isNotBlank)
                        ?.let(output::add)
                }
            }
        } else {
''',
    '''        if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            val array = root.optJSONArray("models")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val methods = item.optJSONArray("supportedGenerationMethods")
                    var supportsGenerateContent = methods == null
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j).equals("generateContent", ignoreCase = true)) {
                                supportsGenerateContent = true
                                break
                            }
                        }
                    }
                    if (!supportsGenerateContent) continue
                    item.optString("name")
                        .removePrefix("models/")
                        .takeIf(String::isNotBlank)
                        ?.let(output::add)
                }
            }
        } else {
''',
    "Gemini API capability filter",
)

api = replace_once(
    api,
    '''        return output.distinct().sorted()
    }
''',
    '''        val unique = output.distinct().sorted()
        return if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            AiStudioModelCatalogLoader.videoDescriptionCandidates(unique)
        } else {
            unique
        }
    }
''',
    "video model normalization",
)
api_path.write_text(api)


loader_path = Path("app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioModelCatalogLoader.kt")
loader_path.write_text(r'''package com.oai.geminilivetranslate.ui

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

/**
 * Reads model ids from the authenticated AI Studio page using the existing R11 model discovery
 * bridge. This WebView is off-screen and hidden from accessibility so it never steals screen-reader
 * focus from Settings. It does not copy cookies, auth headers, tokens, or page text into app storage.
 */
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
''')

print("R18.25 patch applied")
