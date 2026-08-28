package com.oai.geminilivetranslate.ui

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
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.VideoDescriptionPromptDefaults
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

    private lateinit var providerSpinner: AccessibleSpinner
    private lateinit var geminiFields: LinearLayout
    private lateinit var proxyFields: LinearLayout
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var proxyUrl: EditText
    private lateinit var proxyKey: EditText
    private lateinit var proxyModel: EditText
    private lateinit var streamingSwitch: Switch
    private lateinit var timeoutInput: EditText
    private lateinit var temperatureInput: EditText
    private lateinit var timelinePrompt: EditText
    private lateinit var summaryPrompt: EditText

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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
    }

    override fun onDestroy() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "THIẾT LẬP API"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityHeading(this, true)
        })

        root.addView(label("Gemini API Key"))
        geminiKey = edit("Nhập Gemini API Key", password = true).apply {
            contentDescription = "Gemini API Key"
        }
        root.addView(geminiKey)
        root.addView(
            help("Khóa Gemini dùng cho Dịch thuật, Chép lời và Mô tả video khi nhà cung cấp mô tả là Google Gemini.")
        )

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
        proxyFields.addView(
            help("Mô tả video dùng chuẩn chat/completions + video_url. Có thể nhập base URL; ứng dụng sẽ tự nối endpoint.")
        )
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
        root.addView(proxyFields)

        streamingSwitch = Switch(this).apply {
            text = "Nhận kết quả theo thời gian thực khi API hỗ trợ"
            textSize = 16f
            minimumHeight = dp(48)
        }
        root.addView(streamingSwitch)

        root.addView(label("Timeout yêu cầu AI (ms)"))
        timeoutInput = numericEdit("300000", decimal = false).apply {
            contentDescription = "Timeout yêu cầu AI tính bằng mili giây"
        }
        root.addView(timeoutInput)

        root.addView(label("Nhiệt độ AI (0.0 - 2.0)"))
        temperatureInput = numericEdit("0.2", decimal = true).apply {
            contentDescription = "Nhiệt độ AI từ 0.0 đến 2.0"
        }
        root.addView(temperatureInput)

        root.addView(section("Lời nhắc: Mô tả theo thời gian"))
        root.addView(help("Biến có thể dùng: {{VIDEO_DURATION_SECONDS}}. Ứng dụng sẽ thay biến này bằng thời lượng video tính theo giây."))
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
        root.addView(help("Biến có thể dùng: {{VIDEO_DURATION_SECONDS}}. Có thể chỉnh toàn bộ lời nhắc theo nhu cầu."))
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
        providerSpinner.setSelection(
            if (settings.provider == AiApiSettingsStore.PROVIDER_OPENAI) 1 else 0
        )
        geminiKey.setText(secretState.selected.orEmpty())
        geminiModel.setText(settings.geminiModel)
        proxyUrl.setText(settings.proxyUrl)
        proxyKey.setText(secretState.proxyKey.orEmpty())
        proxyModel.setText(settings.proxyModel)
        streamingSwitch.isChecked = settings.streamingEnabled
        timeoutInput.setText(settings.requestTimeoutMs.toString())
        temperatureInput.setText(settings.temperature.toString())
        timelinePrompt.setText(settings.timelinePrompt)
        summaryPrompt.setText(settings.summaryPrompt)
        refreshProviderFields()
    }

    private fun saveAndClose() {
        val provider = currentProvider()
        val geminiValue = geminiKey.text.toString().trim()
        val proxyValue = proxyKey.text.toString().trim()
        val proxyUrlValue = proxyUrl.text.toString().trim()
        val timeoutValue = timeoutInput.text.toString().trim().toIntOrNull()
        val temperatureValue = temperatureInput.text.toString().trim().toDoubleOrNull()

        if (geminiValue.isNotBlank() && (geminiValue.length < 20 || geminiValue.any(Char::isWhitespace))) {
            toast("Gemini API Key không hợp lệ")
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
            toast("Nhiệt độ AI phải từ 0.0 đến 2.0")
            return
        }

        runCatching {
            keys.setGeminiKey(geminiValue)
            keys.setProxyKey(proxyValue)
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
            logger.log(
                2,
                "ApiSettings",
                "Đã lưu provider=$provider streaming=${streamingSwitch.isChecked} timeoutMs=$timeoutValue temperature=$temperatureValue geminiModel=${geminiModel.text.toString().trim()} proxyModel=${proxyModel.text.toString().trim()}",
            )
            toast("Đã lưu thiết lập API")
            finish()
        }.onFailure {
            logger.log(0, "ApiSettings", "Không lưu được thiết lập API", it)
            toast("Không lưu được thiết lập API: ${it.message}")
        }
    }

    private fun fetchModels(provider: String) {
        val keyValue = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            geminiKey.text.toString().trim()
        } else {
            proxyKey.text.toString().trim()
        }
        val proxyUrlValue = proxyUrl.text.toString().trim()
        lifecycleScope.launch {
            toast("Đang tải danh sách model...")
            runCatching {
                withContext(Dispatchers.IO) { fetchModelList(provider, keyValue, proxyUrlValue) }
            }.onSuccess { models ->
                if (models.isEmpty()) {
                    toast("API không trả danh sách model")
                } else {
                    val target = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
                        geminiModel
                    } else {
                        proxyModel
                    }
                    AlertDialog.Builder(this@ApiSettingsActivity)
                        .setTitle("CHỌN MODEL")
                        .setItems(models.toTypedArray()) { _, which ->
                            target.setText(models[which])
                        }
                        .setNegativeButton("HỦY", null)
                        .show()
                }
            }.onFailure {
                logger.log(0, "ApiSettings", "Tải danh sách model thất bại provider=$provider", it)
                toast("Không tải được danh sách model: ${it.message}")
            }
        }
    }

    private fun testConnection() {
        val provider = currentProvider()
        val keyValue = if (provider == AiApiSettingsStore.PROVIDER_GEMINI) {
            geminiKey.text.toString().trim()
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
                    testProviderConnection(
                        provider = provider,
                        keyValue = keyValue,
                        modelValue = modelValue,
                        proxyUrlValue = proxyUrlValue,
                    )
                }
            }.onSuccess { info ->
                toast("Kết nối thành công. $info")
                logger.log(
                    2,
                    "ApiSettings",
                    "Kiểm tra kết nối thành công provider=$provider model=$modelValue info=$info",
                )
            }.onFailure {
                logger.log(
                    0,
                    "ApiSettings",
                    "Kiểm tra kết nối thất bại provider=$provider model=$modelValue",
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
            "https://generativelanguage.googleapis.com/v1beta/models" to keyValue
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
                    array.optJSONObject(i)?.optString("name")
                        ?.removePrefix("models/")
                        ?.takeIf(String::isNotBlank)
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
        return output.distinct().sorted()
    }

    private fun currentProvider(): String =
        providerValues.getOrElse(providerSpinner.selectedItemPosition) {
            AiApiSettingsStore.PROVIDER_GEMINI
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

    private fun help(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 12f
        setTextColor(Color.parseColor("#666666"))
        setPadding(0, 0, 0, dp(4))
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
