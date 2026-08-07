package com.oai.geminilivetranslate.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.oai.geminilivetranslate.audio.TtsCatalogScanner
import com.oai.geminilivetranslate.audio.TtsEngineCatalog
import com.oai.geminilivetranslate.audio.TtsEngineInfo
import com.oai.geminilivetranslate.audio.TtsLanguageInfo
import com.oai.geminilivetranslate.audio.TtsPreferences
import com.oai.geminilivetranslate.audio.TtsPreviewSpeaker
import com.oai.geminilivetranslate.audio.TtsSelection
import com.oai.geminilivetranslate.audio.TtsVoiceInfo
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger

class TtsSettingsActivity : AppCompatActivity() {
    private lateinit var preferences: TtsPreferences
    private lateinit var logger: SessionLogger
    private lateinit var scanner: TtsCatalogScanner
    private lateinit var previewSpeaker: TtsPreviewSpeaker

    private lateinit var engineSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var previewButton: Button
    private lateinit var saveButton: Button

    private var engines: List<TtsEngineInfo> = emptyList()
    private var currentCatalog: TtsEngineCatalog? = null
    private var currentLanguages: List<TtsLanguageInfo> = emptyList()
    private var currentVoices: List<TtsVoiceInfo> = emptyList()
    private var draft = TtsSelection()
    private var catalogGeneration = 0L
    private var initialEngineLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = TtsPreferences(this)
        logger = SessionLogger(this, AppPreferences(this))
        scanner = TtsCatalogScanner(this, logger)
        previewSpeaker = TtsPreviewSpeaker(this, logger)
        draft = TtsPreferences.sanitize(preferences.load())
        setContentView(buildRoot())
        loadEngines()
    }

    override fun onDestroy() {
        scanner.cancel()
        previewSpeaker.shutdown()
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        outer.addView(TextView(this).apply {
            text = "Cài đặt TTS"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        outer.addView(TextView(this).apply {
            text = "Ba lựa chọn liên kết với nhau: bộ đọc quyết định ngôn ngữ có thể dùng, ngôn ngữ quyết định các giọng đọc có thể chọn. Mặc định ưu tiên Tiếng Việt (vi-VN)."
            textSize = 14f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(12))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        fun sectionLabel(text: String, target: View) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 17f
                setPadding(0, dp(14), 0, dp(4))
                labelFor = target.id
            })
        }

        engineSpinner = Spinner(this).apply {
            id = View.generateViewId()
            minimumHeight = dp(52)
            isEnabled = false
        }
        sectionLabel("Bộ đọc", engineSpinner)
        content.addView(engineSpinner)
        content.addView(TextView(this).apply {
            text = "Chỉ hiển thị các bộ đọc TTS mà Android nhìn thấy trên thiết bị."
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })

        languageSpinner = Spinner(this).apply {
            id = View.generateViewId()
            minimumHeight = dp(52)
            isEnabled = false
        }
        sectionLabel("Ngôn ngữ", languageSpinner)
        content.addView(languageSpinner)
        content.addView(TextView(this).apply {
            text = "Danh sách này được lấy từ bộ đọc đang chọn. Tiếng Việt được ưu tiên làm mặc định khi bộ đọc hỗ trợ."
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })

        voiceSpinner = Spinner(this).apply {
            id = View.generateViewId()
            minimumHeight = dp(52)
            isEnabled = false
        }
        sectionLabel("Giọng đọc", voiceSpinner)
        content.addView(voiceSpinner)
        content.addView(TextView(this).apply {
            text = "Chỉ các giọng thuộc đúng ngôn ngữ đang chọn mới xuất hiện. Giọng cần mạng sẽ được ghi rõ."
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })

        statusText = TextView(this).apply {
            text = "Đang tìm bộ đọc TTS..."
            textSize = 14f
            setPadding(0, dp(18), 0, dp(10))
        }
        content.addView(statusText)

        previewButton = Button(this).apply {
            text = "Nghe thử"
            isAllCaps = false
            minHeight = dp(52)
            isEnabled = false
            setOnClickListener { previewSelection() }
        }
        content.addView(previewButton)

        outer.addView(scroll)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        saveButton = Button(this).apply {
            text = "Lưu TTS"
            isAllCaps = false
            minHeight = dp(52)
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveSelection() }
        }
        actions.addView(saveButton)
        actions.addView(Button(this).apply {
            text = "Đóng"
            isAllCaps = false
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        outer.addView(actions)
        return outer
    }

    private fun loadEngines() {
        engines = scanner.discoverEngines()
        if (engines.isEmpty()) {
            status("Không tìm thấy bộ đọc TTS nào trên thiết bị.", error = true)
            return
        }

        engineSpinner.adapter = adapter(engines.map { it.label })
        val selectedIndex = engines.indexOfFirst { it.packageName == draft.enginePackage }
            .takeIf { it >= 0 } ?: 0
        engineSpinner.setSelection(selectedIndex)
        engineSpinner.isEnabled = true
        engineSpinner.contentDescription = "Bộ đọc TTS. ${engines[selectedIndex].label}"

        var ready = false
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = engines.getOrNull(position) ?: return
                engineSpinner.contentDescription = "Bộ đọc TTS. ${selected.label}"
                if (!ready) return
                draft = draft.copy(
                    enginePackage = selected.packageName,
                    languageTag = TtsPreferences.DEFAULT_TTS_LANGUAGE,
                    voiceName = "",
                )
                initialEngineLoad = false
                inspectEngine(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        engineSpinner.post { ready = true }
        inspectEngine(engines[selectedIndex])
    }

    private fun inspectEngine(engine: TtsEngineInfo) {
        val generation = ++catalogGeneration
        languageSpinner.isEnabled = false
        voiceSpinner.isEnabled = false
        previewButton.isEnabled = false
        saveButton.isEnabled = false
        status("Đang đọc ngôn ngữ và giọng của ${engine.label}...")

        scanner.inspect(engine) { result ->
            if (generation != catalogGeneration || isFinishing || isDestroyed) return@inspect
            result.onFailure {
                currentCatalog = null
                currentLanguages = emptyList()
                currentVoices = emptyList()
                languageSpinner.adapter = adapter(listOf("Không có dữ liệu"))
                voiceSpinner.adapter = adapter(listOf("Không có dữ liệu"))
                status("Không đọc được dữ liệu của bộ đọc này: ${it.message ?: "lỗi không xác định"}", error = true)
            }.onSuccess { catalog ->
                currentCatalog = catalog
                currentLanguages = catalog.languages
                if (currentLanguages.isEmpty()) {
                    languageSpinner.adapter = adapter(listOf("Bộ đọc không báo ngôn ngữ hỗ trợ"))
                    voiceSpinner.adapter = adapter(listOf("Không có giọng đọc"))
                    status("Bộ đọc đã mở nhưng không cung cấp danh sách ngôn ngữ.", error = true)
                    return@onSuccess
                }

                val wantedLanguage = if (initialEngineLoad && engine.packageName == draft.enginePackage) {
                    draft.languageTag
                } else {
                    TtsPreferences.DEFAULT_TTS_LANGUAGE
                }
                val selectedLanguage = catalog.preferredLanguage(wantedLanguage) ?: currentLanguages.first()
                draft = draft.copy(
                    enginePackage = engine.packageName,
                    languageTag = selectedLanguage.languageTag,
                    voiceName = if (initialEngineLoad && engine.packageName == preferences.load().enginePackage) draft.voiceName else "",
                )
                initialEngineLoad = false
                bindLanguages(selectedLanguage.languageTag)
                status(
                    "Đã đọc ${catalog.languages.size} ngôn ngữ và ${catalog.voices.size} giọng từ ${catalog.resolvedEnginePackage.ifBlank { engine.label }}.",
                )
                saveButton.isEnabled = true
            }
        }
    }

    private fun bindLanguages(selectedTag: String) {
        val labels = currentLanguages.map { it.label }
        val selectedIndex = currentLanguages.indexOfFirst {
            it.languageTag.equals(selectedTag, ignoreCase = true)
        }.coerceAtLeast(0)
        languageSpinner.adapter = adapter(labels)
        languageSpinner.setSelection(selectedIndex)
        languageSpinner.isEnabled = currentLanguages.isNotEmpty()
        languageSpinner.contentDescription = "Ngôn ngữ TTS. ${labels.getOrElse(selectedIndex) { "" }}"

        var ready = false
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val language = currentLanguages.getOrNull(position) ?: return
                languageSpinner.contentDescription = "Ngôn ngữ TTS. ${language.label}"
                if (!ready) return
                draft = draft.copy(languageTag = language.languageTag, voiceName = "")
                bindVoices(language.languageTag, "")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        languageSpinner.post { ready = true }
        bindVoices(currentLanguages[selectedIndex].languageTag, draft.voiceName)
    }

    private fun bindVoices(languageTag: String, savedVoiceName: String) {
        val catalog = currentCatalog ?: return
        val explicit = catalog.voicesForLanguage(languageTag)
        currentVoices = buildList {
            add(
                TtsVoiceInfo(
                    name = "",
                    languageTag = languageTag,
                    label = "Mặc định của bộ đọc",
                    networkRequired = false,
                ),
            )
            addAll(explicit)
        }
        val selectedIndex = currentVoices.indexOfFirst { it.name == savedVoiceName }
            .takeIf { it >= 0 } ?: 0
        voiceSpinner.adapter = adapter(currentVoices.map { it.label })
        voiceSpinner.setSelection(selectedIndex)
        voiceSpinner.isEnabled = true
        voiceSpinner.contentDescription = "Giọng đọc TTS. ${currentVoices[selectedIndex].label}"
        draft = draft.copy(languageTag = languageTag, voiceName = currentVoices[selectedIndex].name)
        previewButton.isEnabled = true
        saveButton.isEnabled = true

        var ready = false
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val voice = currentVoices.getOrNull(position) ?: return
                voiceSpinner.contentDescription = "Giọng đọc TTS. ${voice.label}"
                if (!ready) return
                draft = draft.copy(voiceName = voice.name)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        voiceSpinner.post { ready = true }
    }

    private fun previewSelection() {
        val safe = TtsPreferences.sanitize(draft)
        previewButton.isEnabled = false
        status("Đang phát giọng thử ${safe.languageTag}...")
        previewSpeaker.speak(safe) { success, message ->
            if (isFinishing || isDestroyed) return@speak
            previewButton.isEnabled = currentCatalog != null
            status(message, error = !success)
        }
    }

    private fun saveSelection() {
        val safe = TtsPreferences.sanitize(draft)
        preferences.save(safe)
        draft = safe
        logger.log(
            2,
            "TTSSettings",
            "Đã lưu TTS engine=${safe.enginePackage.ifBlank { "DEFAULT" }} language=${safe.languageTag} voice=${safe.voiceName.ifBlank { "DEFAULT" }}",
        )
        toast("Đã lưu bộ đọc, ngôn ngữ và giọng TTS")
        status("Đã lưu. TTS sẽ dùng lựa chọn này từ câu đọc tiếp theo.")
    }

    private fun status(message: String, error: Boolean = false) {
        statusText.text = message
        statusText.setTextColor(
            if (error) themeColor(android.R.attr.colorError)
            else themeColor(android.R.attr.textColorPrimary),
        )
        statusText.contentDescription = message
    }

    private fun adapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
