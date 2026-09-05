package com.oai.geminilivetranslate.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.oai.geminilivetranslate.core.AiApiEndpointRules
import com.oai.geminilivetranslate.core.AiApiSettings
import com.oai.geminilivetranslate.core.AiApiSettingsStore
import com.oai.geminilivetranslate.core.AiConnectionModeStore
import com.oai.geminilivetranslate.core.AiFunctionModelCatalog
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.VideoDescriptionPromptDefaults
import com.oai.geminilivetranslate.service.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiSettingsActivity : AppCompatActivity() {
    private lateinit var store: AiApiSettingsStore
    private lateinit var keys: ApiKeyStore
    private lateinit var logger: SessionLogger

    private lateinit var connectionModeSpinner: AccessibleSpinner
    private lateinit var accountButton: Button
    private lateinit var providerSpinner: AccessibleSpinner
    private lateinit var geminiKeyFields: LinearLayout
    private lateinit var geminiFields: LinearLayout
    private lateinit var proxyFields: LinearLayout
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var proxyUrl: EditText
    private lateinit var proxyKey: EditText
    private lateinit var proxyModel: EditText
    private lateinit var streamingSwitch: Switch
    private lateinit var autoReconnectSwitch: Switch
    private lateinit var reconnectRetriesInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var temperatureInput: EditText
    private lateinit var timelinePrompt: EditText
    private lateinit var summaryPrompt: EditText
    private var modelCatalogLoader: AiStudioModelCatalogLoader? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val connectionModeValues = listOf(
        AiConnectionModeStore.MODE_API_KEY,
        AiConnectionModeStore.MODE_AI_STUDIO,
    )
    private val connectionModeLabels = listOf(
        AiConnectionModeStore.LABEL_API_KEY,
        AiConnectionModeStore.LABEL_AI_STUDIO,
    )

    private val providerValues = listOf(
        AiApiSettingsStore.PROVIDER_GEMINI,
        AiApiSettingsStore.PROVIDER_OPENAI,
    )
    private val providerLabels = listOf(
        "Google Gemini",
        "OpenAI-compatible / Proxy",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AiApiSettingsStore(this)
        keys = ApiKeyStore(this)
        logger = SessionLogger(this, AppPreferences(this))
        setContentView(buildUi())
        loadIntoUi()
        logger.log(
            2,
            "ApiSettings",
            "Mở Thiết lập API connectionMode=${AiConnectionModeStore(this).load()} models={${AiFunctionModelCatalog.summary(store.load().geminiModel)}}",
        )
    }

    override fun onDestroy() {
        modelCatalogLoader?.cancel()
        modelCatalogLoader = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "THIẾT LẬP API"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityHeading(this, true)
        })

        root.addView(label("Chế độ kết nối Gemini Live"))
        connectionModeSpinner = AccessibleSpinner(this).apply {
            adapter = ArrayAdapter(
                this@ApiSettingsActivity,
                android.R.layout.simple_spinner_item,
                connectionModeLabels,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            contentDescription = "Chọn chế độ kết nối Gemini Live: API Key hoặc Tài khoản Google AI Studio"
            minimumHeight = dp(48)
        }
        root.addView(connectionModeSpinner)

        accountButton = Button(this).apply {
            text = "ĐĂNG NHẬP / ĐĂNG XUẤT / CHUYỂN TÀI KHOẢN"
            isAllCaps = false
            minimumHeight = dp(54)
            contentDescription = "Quản lý tài khoản Google dùng cho AI Studio"
            setOnClickListener {
                logger.log(2, "ApiSettings", "Mở quản lý tài khoản AI Studio từ Thiết lập API")
                startActivity(Intent(this@ApiSettingsActivity, AiStudioAccountActivity::class.java))
            }
        }
        root.addView(accountButton)

        geminiKeyFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        geminiKeyFields.addView(label("Gemini API Key"))
        geminiKey = multiKeyEdit().apply {
            contentDescription = "Gemini API Key. Mỗi dòng một khóa"
        }
        geminiKeyFields.addView(geminiKey)
        root.addView(geminiKeyFields)

        root.addView(label("Nhà cung cấp cho Mô tả video"))
        providerSpinner = AccessibleSpinner(this).apply {
            adapter = ArrayAdapter(
                this@ApiSettingsActivity,
                android.R.layout.simple_spinner_item,
                providerLabels,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            contentDescription = "Chọn nhà cung cấp cho Mô tả video"
            minimumHeight = dp(48)
        }
        root.addView(providerSpinner)

        geminiFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        geminiFields.addView(modelRow(
            title = "Model Gemini cho Mô tả video",
            onLoad = { fetchModels(AiApiSettingsStore.PROVIDER_GEMINI) },
        ))
        geminiModel = edit("Ví dụ: gemini-3.7-flash").apply {
            contentDescription = "Model Gemini cho Mô tả video"
        }
        geminiFields.addView(geminiModel)
        root.addView(geminiFields)

        proxyFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        proxyFields.addView(label("OpenAI-compatible URL"))
        proxyUrl = edit(".../v1 hoặc .../v1/chat/completions").apply {
            contentDescription = "OpenAI-compatible URL"
        }
        proxyFields.addView(proxyUrl)
        proxyFields.addView(label("OpenAI-compatible API Key"))
        proxyKey = edit("Bearer key, có thể để trống", password = true).apply {
            contentDescription = "OpenAI-compatible API Key"
        }
        proxyFields.addView(proxyKey)
        proxyFields.addView(modelRow(
            title = "Model OpenAI-compatible cho Mô tả video",
            onLoad = { fetchModels(AiApiSettingsStore.PROVIDER_OPENAI) },
        ))
        proxyModel = edit("Nhập model").apply {
            contentDescription = "Model OpenAI-compatible cho Mô tả video"
        }
        proxyFields.addView(proxyModel)
        proxyFields.addView(label("Nhiệt độ Proxy (0.0 - 2.0)"))
        temperatureInput = numericEdit("0.2", decimal = true).apply {
            contentDescription = "Nhiệt độ Proxy từ 0.0 đến 2.0"
        }
        proxyFields.addView(temperatureInput)
        root.addView(proxyFields)

        streamingSwitch = Switch(this).apply {
            text = "Nhận kết quả theo thời gian thực khi API hỗ trợ"
            textSize = 16f
            minimumHeight = dp(48)
        }
        root.addView(streamingSwitch)

        root.addView(section("Kết nối và tự khôi phục"))
        autoReconnectSwitch = Switch(this).apply {
            text = "Tự kết nối lại khi bị gián đoạn"
            textSize = 16f
            minimumHeight = dp(48)
            contentDescription = "Tự kết nối lại khi mạng hoặc dịch vụ bị gián đoạn"
        }
        root.addView(autoReconnectSwitch)

        root.addView(label("Số lần thử kết nối lại"))
        reconnectRetriesInput = numericEdit("3", decimal = false).apply {
            contentDescription = "Số lần thử kết nối lại, từ 1 đến 10"
        }
        root.addView(reconnectRetriesInput)

        root.addView(label("Timeout yêu cầu AI (ms)"))
        timeoutInput = numericEdit("300000", decimal = false).apply {
            contentDescription = "Timeout yêu cầu AI tính bằng mili giây"
        }
        root.addView(timeoutInput)

        root.addView(section("Lời nhắc: Mô tả theo thời gian"))
        timelinePrompt = promptEdit().apply {
            contentDescription = "Lời nhắc mô tả theo thời gian"
        }
        root.addView(timelinePrompt)
        root.addView(Button(this).apply {
            text = "Khôi phục lời nhắc theo thời gian mặc định"
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener {
                timelinePrompt.setText(VideoDescriptionPromptDefaults.TIMELINE)
                toast("Đã khôi phục lời nhắc mặc định")
            }
        })

        root.addView(section("Lời nhắc: Mô tả tổng hợp"))
        summaryPrompt = promptEdit().apply {
            contentDescription = "Lời nhắc mô tả tổng hợp"
        }
        root.addView(summaryPrompt)
        root.addView(Button(this).apply {
            text = "Khôi phục lời nhắc tổng hợp mặc định"
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener {
                summaryPrompt.setText(VideoDescriptionPromptDefaults.SUMMARY)
                toast("Đã khôi phục lời nhắc mặc định")
            }
        })

        root.addView(Button(this).apply {
            text = "KIỂM TRA KẾT NỐI"
            minimumHeight = dp(54)
            setOnClickListener { testConnection() }
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "LƯU"
            minimumHeight = dp(58)
            setBackgroundColor(Color.parseColor("#34C759"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveAndClose() }
        })
        actions.addView(Button(this).apply {
            text = "HỦY"
            minimumHeight = dp(58)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        root.addView(actions)

        connectionModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshConnectionModeFields()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshProviderFields()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun loadIntoUi() {
        val settings = store.load()
        val secretState = keys.load()
        val connectionMode = AiConnectionModeStore(this).load()
        connectionModeSpinner.setSelection(
            if (connectionMode == AiConnectionModeStore.MODE_AI_STUDIO) 1 else 0,
        )
        providerSpinner.setSelection(
            if (settings.provider == AiApiSettingsStore.PROVIDER_OPENAI) 1 else 0
        )
        geminiKey.setText(secretState.keys.joinToString("\n"))
        geminiModel.setText(settings.geminiModel)
        proxyUrl.setText(settings.proxyUrl)
        proxyKey.setText(secretState.proxyKey.orEmpty())
        proxyModel.setText(settings.proxyModel)
        streamingSwitch.isChecked = settings.streamingEnabled
        val appSettings = AppPreferences(this).load()
        autoReconnectSwitch.isChecked = appSettings.autoReconnect
        reconnectRetriesInput.setText(appSettings.reconnectMaxRetries.toString())
        reconnectRetriesInput.isEnabled = appSettings.autoReconnect
        autoReconnectSwitch.setOnCheckedChangeListener { _, checked ->
            reconnectRetriesInput.isEnabled = checked
        }
        timeoutInput.setText(settings.requestTimeoutMs.toString())
        temperatureInput.setText(settings.temperature.toString())
        timelinePrompt.setText(settings.timelinePrompt)
        summaryPrompt.setText(settings.summaryPrompt)
        refreshConnectionModeFields()
        refreshProviderFields()
    }

    private fun saveAndClose() {
        val provider = currentProvider()
        val connectionMode = currentConnectionMode()
        val geminiValues = geminiKeysFromUi()
        val proxyValue = proxyKey.text.toString().trim()
        val proxyUrlValue = proxyUrl.text.toString().trim()
        val timeoutValue = timeoutInput.text.toString().trim().toIntOrNull()
        val temperatureValue = temperatureInput.text.toString().trim().toDoubleOrNull()
        val reconnectRetriesValue = reconnectRetriesInput.text.toString().trim().toIntOrNull()

        val invalidGeminiKey = if (connectionMode == AiConnectionModeStore.MODE_API_KEY) {
            geminiValues.firstOrNull { it.length < 20 || it.any(Char::isWhitespace) }
        } else null
        if (invalidGeminiKey != null) {
            toast("Có Gemini API Key không hợp lệ")
            return
        }
        if (provider == AiApiSettingsStore.PROVIDER_OPENAI && !proxyUrlValue.startsWith("https://")) {
            toast("URL OpenAI-compatible phải dùng HTTPS")
            return
        }
        if (timeoutValue == null || timeoutValue !in 30_000..900_000) {
            toast("Timeout phải từ 30000 đến 900000 ms")
            return
        }
        if (temperatureValue == null || temperatureValue !in 0.0..2.0) {
            toast("Nhiệt độ Proxy phải từ 0.0 đến 2.0")
            return
        }
        if (reconnectRetriesValue == null || reconnectRetriesValue !in 1..10) {
            toast("Số lần thử kết nối lại phải từ 1 đến 10")
            return
        }

        val previousGeminiKeys = keys.load().keys
        val previousConnectionMode = AiConnectionModeStore(this).load()
        runCatching {
            if (connectionMode == AiConnectionModeStore.MODE_API_KEY) {
                keys.setGeminiKeys(geminiValues)
            }
            keys.setProxyKey(proxyValue)
            AiConnectionModeStore(this).save(connectionMode)
            val appPreferences = AppPreferences(this)
            appPreferences.save(
                appPreferences.load().copy(
                    autoReconnect = autoReconnectSwitch.isChecked,
                    reconnectMaxRetries = reconnectRetriesValue,
                )
            )
            store.save(
                AiApiSettings(
                    provider = provider,
                    geminiModel = geminiModel.text.toString(),
                    proxyUrl = proxyUrlValue,
                    proxyModel = proxyModel.text.toString(),
                    streamingEnabled = streamingSwitch.isChecked,
                    requestTimeoutMs = timeoutValue,
                    temperature = temperatureValue,
                    timelinePrompt = timelinePrompt.text.toString(),
                    summaryPrompt = summaryPrompt.text.toString(),
                )
            )
        }.onSuccess {
            val currentGeminiKeys = keys.load().keys
            val connectionChanged = previousConnectionMode != connectionMode
            startService(
                Intent(this, TranslationService::class.java)
                    .setAction(TranslationService.ACTION_APPLY_SETTINGS)
            )
            if (previousGeminiKeys != currentGeminiKeys || connectionChanged) {
                startService(
                    Intent(this, TranslationService::class.java)
                        .setAction(TranslationService.ACTION_REFRESH_API_KEY)
                )
            }
            logger.log(
                2,
                "ApiSettings",
                "Đã lưu connectionMode=$connectionMode connectionChanged=$connectionChanged provider=$provider streaming=${streamingSwitch.isChecked} autoReconnect=${autoReconnectSwitch.isChecked} reconnectRetries=$reconnectRetriesValue timeoutMs=$timeoutValue proxyTemperature=$temperatureValue geminiModel=${geminiModel.text.toString().trim()} proxyModel=${proxyModel.text.toString().trim()} geminiKeyCount=${currentGeminiKeys.size} geminiKeysChanged=${previousGeminiKeys != currentGeminiKeys} models={${AiFunctionModelCatalog.summary(geminiModel.text.toString())}}",
            )
            toast("Đã lưu thiết lập API")
            finish()
        }.onFailure {
            logger.log(0, "ApiSettings", "Không lưu được thiết lập API connectionMode=$connectionMode", it)
            toast("Không lưu được thiết lập API: ${it.message}")
        }
    }

    private fun fetchModels(provider: String) {
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

    private fun testConnection() {
        val connectionMode = currentConnectionMode()
        if (connectionMode == AiConnectionModeStore.MODE_AI_STUDIO) {
            logger.log(2, "ApiSettings", "KIỂM TRA KẾT NỐI chuyển sang quản lý phiên AI Studio")
            toast("Hãy kiểm tra phiên đăng nhập AI Studio trong màn hình tài khoản")
            startActivity(Intent(this, AiStudioAccountActivity::class.java))
            return
        }

        val provider = currentProvider()
        val geminiValues = geminiKeysFromUi()
        val keyValue = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            ""
        } else {
            proxyKey.text.toString().trim()
        }
        val modelValue = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            geminiModel.text.toString().trim().removePrefix("models/")
                .ifBlank { AppPreferences.VIDEO_DESCRIPTION_MODEL }
        } else {
            proxyModel.text.toString().trim()
        }
        val proxyUrlValue = proxyUrl.text.toString().trim()

        lifecycleScope.launch {
            toast("Đang kiểm tra kết nối...")
            runCatching {
                withContext(Dispatchers.IO) {
                    if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
                        if (geminiValues.isEmpty()) error("Hãy nhập ít nhất một Gemini API Key")
                        var successCount = 0
                        val failedIndexes = ArrayList<Int>()
                        geminiValues.forEachIndexed { index, key ->
                            runCatching {
                                testProviderConnection(
                                    provider = provider,
                                    keyValue = key,
                                    modelValue = modelValue,
                                    proxyUrlValue = proxyUrlValue,
                                )
                            }.onSuccess {
                                successCount++
                            }.onFailure { error ->
                                failedIndexes += index + 1
                                logger.log(
                                    1,
                                    "ApiSettings",
                                    "Kiểm tra Gemini API Key thất bại index=${index + 1}/${geminiValues.size} model=$modelValue",
                                    error,
                                )
                            }
                        }
                        if (successCount == 0) {
                            error("0/${geminiValues.size} API Key hoạt động")
                        }
                        buildString {
                            append("Gemini: $successCount/${geminiValues.size} API Key hoạt động")
                            if (failedIndexes.isNotEmpty()) {
                                append(". Khóa lỗi: ").append(failedIndexes.joinToString())
                            }
                        }
                    } else {
                        testProviderConnection(
                            provider = provider,
                            keyValue = keyValue,
                            modelValue = modelValue,
                            proxyUrlValue = proxyUrlValue,
                        )
                    }
                }
            }.onSuccess { info ->
                toast("Kết nối thành công. $info")
                logger.log(
                    2,
                    "ApiSettings",
                    "Kiểm tra kết nối thành công connectionMode=$connectionMode provider=$provider model=$modelValue info=$info",
                )
            }.onFailure {
                logger.log(
                    0,
                    "ApiSettings",
                    "Kiểm tra kết nối thất bại connectionMode=$connectionMode provider=$provider model=$modelValue",
                    it,
                )
                toast("Kết nối thất bại: ${it.message}")
            }
        }
    }

    private fun testProviderConnection(
        provider: String,
        keyValue: String,
        modelValue: String,
        proxyUrlValue: String,
    ): String {
        if (modelValue.isBlank()) error("Hãy nhập model trước")

        if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            if (keyValue.isBlank()) error("Hãy nhập Gemini API Key trước")
            val payload = JSONObject()
                .put("model", modelValue)
                .put("store", false)
                .put("input", "Chỉ trả lời đúng một từ: OK")
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/interactions")
                .header("x-goog-api-key", keyValue)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            return client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${body.replace(Regex("\\s+"), " ").take(500)}")
                }
                "Gemini HTTP ${response.code}"
            }
        }

        if (!proxyUrlValue.startsWith("https://")) {
            error("URL OpenAI-compatible phải dùng HTTPS")
        }
        val endpoint = AiApiEndpointRules.proxyChatEndpoint(proxyUrlValue)
        val payload = JSONObject()
            .put("model", modelValue)
            .put(
                "messages",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Chỉ trả lời đúng một từ: OK")
                )
            )
        val builder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        if (keyValue.isNotBlank()) {
            builder.header("Authorization", "Bearer $keyValue")
        }
        return client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.replace(Regex("\\s+"), " ").take(500)}")
            }
            "OpenAI-compatible HTTP ${response.code}"
        }
    }

    private fun fetchModelList(
        provider: String,
        keyValue: String,
        proxyUrlValue: String,
    ): List<String> {
        val (url, key) = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" to keyValue
        } else {
            AiApiEndpointRules.proxyModelsEndpoint(proxyUrlValue) to keyValue
        }
        if (provider == AiApiSettingsStore.PROVIDER_GEMINI && key.isBlank()) {
            error("Hãy nhập Gemini API Key trước")
        }

        val builder = Request.Builder().url(url).get()
        if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            builder.header("x-goog-api-key", key)
        } else if (key.isNotBlank()) {
            builder.header("Authorization", "Bearer $key")
        }
        val root = client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.replace(Regex("\\s+"), " ").take(400)}")
            }
            JSONObject(body)
        }
        val output = ArrayList<String>()
        if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
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
            val array = root.optJSONArray("data")
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("id")
                        ?.takeIf(String::isNotBlank)
                        ?.let(output::add)
                }
            }
        }
        val unique = output.distinct().sorted()
        return if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            AiStudioModelCatalogLoader.videoDescriptionCandidates(unique)
        } else {
            unique
        }
    }

    private fun currentConnectionMode(): String =
        connectionModeValues.getOrElse(connectionModeSpinner.selectedItemPosition) {
            AiConnectionModeStore.MODE_API_KEY
        }

    private fun currentProvider(): String =
        providerValues.getOrElse(providerSpinner.selectedItemPosition) {
            AiApiSettingsStore.PROVIDER_GEMINI
        }

    private fun refreshConnectionModeFields() {
        val mode = currentConnectionMode()
        val aiStudio = mode == AiConnectionModeStore.MODE_AI_STUDIO
        accountButton.isVisible = aiStudio
        geminiKeyFields.isVisible = !aiStudio
    }

    private fun refreshProviderFields() {
        val gemini = currentProvider() == AiApiSettingsStore.PROVIDER_GEMINI
        geminiFields.isVisible = gemini
        proxyFields.isVisible = !gemini
    }

    private fun modelRow(title: String, onLoad: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ApiSettingsActivity).apply {
                text = title
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            })
            addView(Button(this@ApiSettingsActivity).apply {
                text = "TẢI DS"
                contentDescription = "Tải danh sách $title"
                textSize = 12f
                minimumHeight = dp(48)
                setOnClickListener { onLoad() }
            })
        }

    private fun label(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 14f
        setPadding(0, dp(8), 0, dp(3))
    }

    private fun section(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 15f
        setTextColor(Color.parseColor("#007AFF"))
        setPadding(0, dp(14), 0, dp(3))
    }

    private fun edit(hintValue: String, password: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintValue
            isSingleLine = true
            minimumHeight = dp(48)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

    private fun multiKeyEdit(): EditText = EditText(this).apply {
        hint = "Mỗi dòng một API Key"
        minLines = 3
        maxLines = 8
        gravity = Gravity.TOP
        minimumHeight = dp(96)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setHorizontallyScrolling(false)
    }

    private fun geminiKeysFromUi(): List<String> =
        geminiKey.text.toString()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    private fun numericEdit(hintValue: String, decimal: Boolean): EditText =
        EditText(this).apply {
            hint = hintValue
            isSingleLine = true
            minimumHeight = dp(48)
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        }

    private fun promptEdit(): EditText = EditText(this).apply {
        minLines = 10
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
