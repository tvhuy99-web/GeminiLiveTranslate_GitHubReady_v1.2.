package com.oai.geminilivetranslate.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.AppSettings
import com.oai.geminilivetranslate.core.DiagnosticContext
import com.oai.geminilivetranslate.core.LanguageCatalog
import com.oai.geminilivetranslate.core.PublicRecordingStore
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SettingsPolicy
import com.oai.geminilivetranslate.network.GeminiLiveClient
import com.oai.geminilivetranslate.service.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var apiKeys: ApiKeyStore
    private lateinit var logger: SessionLogger
    private lateinit var content: LinearLayout
    private lateinit var tabButtons: Map<String, Button>

    private var activeTab = "basic"
    private var original = AppSettings()
    private var draft = AppSettings()
    private var modelInput: EditText? = null
    private var languageInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        apiKeys = ApiKeyStore(this)
        logger = SessionLogger(this, preferences)
        original = preferences.load()

        @Suppress("DEPRECATION")
        val restoredDraft = savedInstanceState?.getSerializable(STATE_DRAFT) as? AppSettings
        draft = restoredDraft?.let(SettingsPolicy::sanitize) ?: original
        activeTab = savedInstanceState?.getString(STATE_TAB).orEmpty().ifBlank { "basic" }

        setContentView(buildRoot())
        showTab(activeTab, announce = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureTextFields()
        outState.putSerializable(STATE_DRAFT, SettingsPolicy.sanitize(draft))
        outState.putString(STATE_TAB, activeTab)
        super.onSaveInstanceState(outState)
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "Cài đặt"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabButtons = TAB_LABELS.mapValues { (key, label) ->
            Button(this).apply {
                text = label
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener {
                    captureTextFields()
                    showTab(key, announce = true)
                }
                bar.addView(this)
            }
        }

        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(Button(this).apply {
            text = "Lưu thay đổi"
            isAllCaps = false
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveAndClose() }
        })
        actions.addView(Button(this).apply {
            text = "Đóng"
            isAllCaps = false
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        root.addView(actions)

        return root
    }

    private fun showTab(key: String, announce: Boolean) {
        activeTab = key
        tabButtons.forEach { (tab, button) ->
            val selected = tab == key
            button.isSelected = selected
            button.isActivated = selected
            button.contentDescription = if (selected) {
                "${TAB_LABELS.getValue(tab)}, đang chọn"
            } else {
                TAB_LABELS.getValue(tab)
            }
        }

        content.removeAllViews()
        modelInput = null
        languageInput = null

        when (key) {
            "basic" -> buildBasic()
            "audio" -> buildAudio()
            "latency" -> buildLatency()
            "save" -> buildSave()
            else -> buildSystem()
        }

        if (announce) {
            content.post {
                content.announceForAccessibility("Đã mở mục ${TAB_LABELS.getValue(key)}")
            }
        }
    }

    private fun buildBasic() {
        title("Cách ứng dụng ưu tiên")
        val profiles = listOf("realtime", "balanced", "stable", "custom")
        val profileLabels = listOf("Phản hồi nhanh", "Cân bằng", "Ổn định hơn", "Tự điều chỉnh")
        spinner(
            labels = profileLabels,
            selected = profiles.indexOf(draft.performanceProfile).coerceAtLeast(0),
            accessibilityLabel = "Cách ứng dụng ưu tiên",
        ) { position ->
            captureTextFields()
            draft = SettingsPolicy.applyProfile(profiles[position], draft)
            toast("Đã chọn ${profileLabels[position]}")
            showTab(activeTab, announce = false)
        }
        description("Phản hồi nhanh giảm thời gian chờ. Cân bằng phù hợp với đa số trường hợp. Ổn định hơn hữu ích khi mạng yếu.")

        title("Giao diện")
        spinner(
            labels = listOf("Đơn giản", "Đầy đủ"),
            selected = if (draft.uiMode == "simple") 0 else 1,
            accessibilityLabel = "Kiểu giao diện",
        ) {
            draft = draft.copy(uiMode = if (it == 0) "simple" else "advanced")
        }
        description("Chế độ Đơn giản chỉ hiện các nút thường dùng.")

        title("Bộ máy dịch")
        modelInput = EditText(this).apply {
            setText(draft.model)
            hint = "Giữ nguyên nếu không được hướng dẫn thay đổi"
            isSingleLine = true
            minHeight = dp(48)
            textSize = 16f
        }.also(content::addView)
        description("Giữ nguyên lựa chọn này nếu ứng dụng đang dịch bình thường.")

        title("Ngôn ngữ cần dịch sang")
        val currentIndex = LanguageCatalog.codes.indexOf(draft.targetLanguage).coerceAtLeast(0)
        spinner(
            labels = LanguageCatalog.labels,
            selected = currentIndex,
            accessibilityLabel = "Ngôn ngữ cần dịch sang",
        ) { position ->
            val code = LanguageCatalog.codes[position]
            draft = draft.copy(targetLanguage = code)
            languageInput?.setText(code)
        }
        languageInput = EditText(this).apply {
            setText(draft.targetLanguage)
            hint = "Ví dụ: vi, en-US"
            isSingleLine = true
            minHeight = dp(48)
            textSize = 16f
        }.also(content::addView)
        description("Chỉ nhập tay khi ngôn ngữ bạn cần chưa có trong danh sách.")

        title("Khi dịch")
        check(
            label = "Đọc lại câu đã đúng ngôn ngữ cần dịch",
            checked = draft.echoTargetLanguage,
            detail = "Bật mục này khi bạn vẫn muốn nghe lại những câu đã ở đúng ngôn ngữ.",
        ) {
            draft = draft.copy(echoTargetLanguage = it)
        }
        check(
            label = "Tắt âm gốc khi nghe âm thanh trong máy",
            checked = draft.muteOriginalInInternal,
            detail = "Âm gốc sẽ tắt trong lúc dịch và tự trở lại khi dừng.",
        ) {
            draft = draft.copy(muteOriginalInInternal = it)
        }
    }

    private fun buildAudio() {
        title("Độ ổn định của giọng dịch")
        slider(
            label = "Dung lượng phát âm thanh",
            current = draft.translatedBufferBytes / 1_000,
            minValue = 48,
            maxValue = 192,
            step = 4,
            unit = " KB",
        ) {
            draft = draft.copy(
                translatedBufferBytes = it * 1_000,
                performanceProfile = "custom",
            )
        }
        slider(
            label = "Số đoạn âm thanh chờ phát",
            current = draft.translatedQueueMax,
            minValue = 5,
            maxValue = 100,
            step = 1,
            unit = " đoạn",
        ) {
            draft = draft.copy(
                translatedQueueMax = it,
                performanceProfile = "custom",
            )
        }
        description("Tăng các mức này khi giọng dịch bị đứt quãng. Mức cao hơn có thể làm giọng phát chậm hơn.")

        title("Âm lượng nền")
        check(
            label = "Tự giảm âm gốc khi giọng dịch phát",
            checked = draft.autoDucking,
            detail = "Giúp nghe giọng dịch rõ hơn mà không cần tự chỉnh âm lượng.",
        ) {
            draft = draft.copy(autoDucking = it)
        }
        slider(
            label = "Âm lượng gốc còn lại",
            current = (draft.duckVolumeFactor * 10).toInt(),
            minValue = 0,
            maxValue = 10,
            step = 1,
            unit = "/10",
        ) {
            draft = draft.copy(duckVolumeFactor = it / 10f)
        }

        title("Giọng đọc của điện thoại")
        check(
            label = "Ghép các câu ngắn trước khi đọc",
            checked = draft.ttsSmoothEnabled,
            detail = "Giọng đọc sẽ chờ thêm một chút để câu nghe tự nhiên hơn.",
        ) {
            draft = draft.copy(ttsSmoothEnabled = it)
            showTab(activeTab, announce = false)
        }
        if (draft.ttsSmoothEnabled) {
            slider(
                label = "Thời gian chờ ghép câu",
                current = draft.ttsSmoothTimeoutMs,
                minValue = 500,
                maxValue = 5_000,
                step = 100,
                unit = " mili giây",
            ) {
                draft = draft.copy(ttsSmoothTimeoutMs = it)
            }
            slider(
                label = "Độ dài câu tối thiểu",
                current = draft.ttsSmoothMinChars,
                minValue = 20,
                maxValue = 200,
                step = 5,
                unit = " ký tự",
            ) {
                draft = draft.copy(ttsSmoothMinChars = it)
            }
            slider(
                label = "Số từ tối thiểu",
                current = draft.ttsSmoothMinWords,
                minValue = 3,
                maxValue = 20,
                step = 1,
                unit = " từ",
            ) {
                draft = draft.copy(ttsSmoothMinWords = it)
            }
        }
    }

    private fun buildLatency() {
        title("Độ ổn định khi dịch")
        check(
            label = "Ưu tiên âm thanh ổn định hơn",
            checked = draft.qualityMode,
            detail = "Ứng dụng sẽ chờ thêm một chút để giảm tình trạng âm thanh bị ngắt.",
        ) {
            draft = draft.copy(
                qualityMode = it,
                performanceProfile = "custom",
            )
            showTab(activeTab, announce = false)
        }
        if (draft.qualityMode) {
            slider(
                label = "Thời gian chuẩn bị âm thanh đầu vào",
                current = draft.inputBufferMs,
                minValue = 200,
                maxValue = 10_000,
                step = 100,
                unit = " mili giây",
            ) {
                draft = draft.copy(
                    inputBufferMs = it,
                    performanceProfile = "custom",
                )
            }
            slider(
                label = "Số đoạn chờ trước khi phát",
                current = draft.outputJitterTarget,
                minValue = 3,
                maxValue = 20,
                step = 1,
                unit = " đoạn",
            ) {
                draft = draft.copy(
                    outputJitterTarget = it,
                    performanceProfile = "custom",
                )
            }
        }
        slider(
            label = "Thời gian chờ để khớp với tệp",
            current = draft.fileSyncDelayMs,
            minValue = 0,
            maxValue = 20_000,
            step = 250,
            unit = " mili giây",
        ) {
            draft = draft.copy(
                fileSyncDelayMs = it,
                performanceProfile = "custom",
            )
        }

        title("Tốc độ gửi âm thanh")
        check(
            label = "Gửi theo đúng tốc độ phát",
            checked = draft.pacingEnabled,
            detail = "Giúp ứng dụng không gửi cả tệp quá nhanh trong một lần.",
        ) {
            draft = draft.copy(
                pacingEnabled = it,
                performanceProfile = "custom",
            )
            showTab(activeTab, announce = false)
        }
        slider(
            label = "Số đoạn âm thanh chờ gửi",
            current = draft.pacingMaxBuffer,
            minValue = 1,
            maxValue = 50,
            step = 1,
            unit = " đoạn",
        ) {
            draft = draft.copy(
                pacingMaxBuffer = it,
                performanceProfile = "custom",
            )
        }
        description("Khi mạng chậm, âm thanh trực tiếp có thể bỏ bớt đoạn cũ để tránh bị trễ. Tệp sẽ chờ để không mất nội dung.")
        if (draft.pacingEnabled) {
            slider(
                label = "Khoảng chuẩn bị trước",
                current = draft.pacingTargetLatencyMs,
                minValue = 100,
                maxValue = 2_000,
                step = 50,
                unit = " mili giây",
            ) {
                draft = draft.copy(
                    pacingTargetLatencyMs = it,
                    performanceProfile = "custom",
                )
            }
        }
    }

    private fun buildSave() {
        title("Lưu bản ghi âm")
        check(
            label = "Lưu âm thanh sau mỗi lần dịch",
            checked = draft.saveAudioEnabled,
        ) {
            draft = draft.copy(saveAudioEnabled = it)
            showTab(activeTab, announce = false)
        }
        if (draft.saveAudioEnabled) {
            spinner(
                labels = listOf(
                    "Chỉ giọng dịch",
                    "Chỉ âm thanh gốc",
                    "Âm thanh gốc và giọng dịch",
                ),
                selected = listOf("translated", "original", "mixed")
                    .indexOf(draft.saveAudioMode)
                    .coerceAtLeast(0),
                accessibilityLabel = "Nội dung cần lưu",
            ) {
                draft = draft.copy(
                    saveAudioMode = listOf("translated", "original", "mixed")[it],
                )
            }
        }

        title("Định dạng khi xuất lời dịch")
        spinner(
            labels = listOf("Phụ đề có thời gian", "Văn bản thường"),
            selected = if (draft.exportFormat == "txt") 1 else 0,
            accessibilityLabel = "Định dạng khi xuất lời dịch",
        ) {
            draft = draft.copy(exportFormat = if (it == 1) "txt" else "srt")
        }
    }

    private fun buildSystem() {
        title("Khi mất kết nối")
        check(
            label = "Tự kết nối lại",
            checked = draft.autoReconnect,
            detail = "Ứng dụng sẽ tự thử lại khi mạng hoặc dịch vụ bị gián đoạn.",
        ) {
            draft = draft.copy(autoReconnect = it)
            showTab(activeTab, announce = false)
        }
        if (draft.autoReconnect) {
            slider(
                label = "Số lần thử lại",
                current = draft.reconnectMaxRetries,
                minValue = 1,
                maxValue = 10,
                step = 1,
                unit = " lần",
            ) {
                draft = draft.copy(reconnectMaxRetries = it)
            }
        }

        title("Kiểm tra kết nối")
        content.addView(Button(this).apply {
            text = "Kiểm tra kết nối dịch"
            isAllCaps = false
            minHeight = dp(48)
            contentDescription = "Kiểm tra kết nối dịch"
            setOnClickListener {
                captureTextFields()
                testConnection(this)
            }
        })

        title("Khôi phục và xóa dữ liệu")
        rowButton("Đưa cài đặt về mặc định") { confirmRestoreSettings() }
        rowButton("Xóa các bản ghi âm") { confirmDeleteRecordings() }
        rowButton("Xóa tất cả khóa truy cập") { confirmDeleteApiKeys() }
        content.addView(Button(this).apply {
            text = "Xóa toàn bộ dữ liệu ứng dụng"
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(themeColor(android.R.attr.colorError))
            contentDescription = "Xóa toàn bộ dữ liệu ứng dụng"
            setOnClickListener { confirmFullReset() }
        })
    }

    private fun saveAndClose() {
        captureTextFields()
        val normalizedLanguage = LanguageCatalog.normalize(draft.targetLanguage)
        if (normalizedLanguage == null) {
            AlertDialog.Builder(this)
                .setTitle("Ngôn ngữ chưa đúng")
                .setMessage("Hãy nhập dạng ngắn như vi, en-US hoặc fr-CA.")
                .setPositiveButton("Đóng", null)
                .show()
            return
        }

        val safe = SettingsPolicy.sanitize(
            draft.copy(targetLanguage = normalizedLanguage),
        )
        val diff = SettingsPolicy.diff(original, safe)
        preferences.save(safe)
        logger.log(
            2,
            "Settings",
            "Đã lưu cài đặt; changed=${diff.changed.joinToString().ifBlank { "none" }}",
        )
        runCatching {
            startService(
                Intent(this, TranslationService::class.java)
                    .setAction(TranslationService.ACTION_APPLY_SETTINGS),
            )
        }.onFailure {
            logger.log(1, "Settings", "Không gửi được yêu cầu áp dụng cài đặt", it)
        }

        if (diff.isEmpty) {
            toast("Không có thay đổi")
            finish()
            return
        }

        val message = buildString {
            appendLine("Đã lưu cài đặt.")
            if (diff.immediate.isNotEmpty()) {
                appendLine("• Đã dùng ngay: ${friendly(diff.immediate)}")
            }
            if (diff.reconnect.isNotEmpty()) {
                appendLine("• Ứng dụng sẽ kết nối lại để dùng: ${friendly(diff.reconnect)}")
            }
            if (diff.playbackRebuild.isNotEmpty()) {
                appendLine("• Giọng phát sẽ khởi động lại để dùng: ${friendly(diff.playbackRebuild)}")
            }
            if (diff.nextSession.isNotEmpty()) {
                appendLine("• Sẽ dùng từ lần dịch tiếp theo: ${friendly(diff.nextSession)}")
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle("Đã lưu")
            .setMessage(message)
            .setPositiveButton("Đóng") { _, _ -> finish() }
            .show()
    }

    private fun captureTextFields() {
        modelInput?.text?.toString()?.let { draft = draft.copy(model = it) }
        languageInput?.text?.toString()?.let {
            draft = draft.copy(targetLanguage = it)
        }
    }

    private fun testConnection(button: Button) {
        val safeDraft = SettingsPolicy.sanitize(draft)
        val key = apiKeys.load().selected
        if (key.isNullOrBlank()) {
            toast("Chưa có khóa truy cập")
            return
        }

        button.isEnabled = false
        button.text = "Đang kiểm tra..."
        logger.log(
            2,
            "Settings",
            "Bắt đầu kiểm tra API model=${safeDraft.model} target=${safeDraft.targetLanguage}",
        )

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    GeminiLiveClient.testConnection(
                        key,
                        safeDraft.model,
                        safeDraft.targetLanguage,
                        safeDraft.echoTargetLanguage,
                        logger,
                    )
                }
            }.onSuccess {
                logger.log(2, "Settings", "Kiểm tra API thành công latencyMs=$it")
                toast("Kết nối tốt")
            }.onFailure {
                logger.log(0, "Settings", "Kiểm tra API thất bại", it)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Không thể kết nối")
                    .setMessage(
                        "Hãy kiểm tra mạng, khóa truy cập và bộ máy dịch, rồi thử lại.",
                    )
                    .setPositiveButton("Đóng", null)
                    .show()
            }

            button.isEnabled = true
            button.text = "Kiểm tra kết nối dịch"
        }
    }

    private fun confirmRestoreSettings() = confirm(
        title = "Đưa cài đặt về mặc định?",
        message = "Khóa truy cập và các bản ghi âm vẫn được giữ lại.",
        positive = "Khôi phục",
    ) {
        val restored = preferences.restoreDefaultsPreservingKeys()
        draft = restored
        original = restored
        logger.log(1, "Settings", "Đã khôi phục cài đặt mặc định")
        startService(
            Intent(this, TranslationService::class.java)
                .setAction(TranslationService.ACTION_APPLY_SETTINGS),
        )
        showTab(activeTab, announce = false)
        toast("Đã khôi phục cài đặt mặc định")
    }

    private fun confirmDeleteRecordings() = confirm(
        title = "Xóa tất cả bản ghi âm?",
        message = "Các tệp âm thanh do ứng dụng đã lưu sẽ bị xóa.",
        positive = "Xóa",
    ) {
        startService(
            Intent(this, TranslationService::class.java)
                .setAction(TranslationService.ACTION_STOP),
        )
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                PublicRecordingStore(this@SettingsActivity, logger).deleteAll()
            }
            toast(
                if (deleted >= 0) {
                    "Đã xóa $deleted bản ghi âm"
                } else {
                    "Không thể xóa hết các bản ghi âm"
                },
            )
        }
    }

    private fun confirmDeleteApiKeys() = confirm(
        title = "Xóa tất cả khóa truy cập?",
        message = "Bạn phải nhập lại khóa truy cập trước khi dịch.",
        positive = "Xóa khóa",
    ) {
        startService(
            Intent(this, TranslationService::class.java)
                .setAction(TranslationService.ACTION_STOP),
        )
        apiKeys.clear()
        logger.log(1, "Settings", "Đã xóa toàn bộ API Key và yêu cầu dừng phiên")
        toast("Đã xóa khóa truy cập")
    }

    private fun confirmFullReset() = confirm(
        title = "Xóa toàn bộ dữ liệu ứng dụng?",
        message = "Cài đặt, khóa truy cập và các bản ghi âm sẽ bị xóa. Không thể hoàn tác.",
        positive = "Xóa toàn bộ",
    ) {
        startService(
            Intent(this, TranslationService::class.java)
                .setAction(TranslationService.ACTION_STOP),
        )
        apiKeys.clear()
        logger.clear()
        preferences.clear()
        PublicRecordingStore(this, logger).deleteAll()
        File(cacheDir, "diagnostic-share").deleteRecursively()
        DiagnosticContext.clearAll()
        toast("Đã xóa toàn bộ dữ liệu ứng dụng")
        finish()
    }

    private fun confirm(
        title: String,
        message: String,
        positive: String,
        action: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun title(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 19f
            setPadding(0, dp(20), 0, dp(6))
            ViewCompat.setAccessibilityHeading(this, true)
        })
    }

    private fun description(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, dp(12))
        })
    }

    private fun check(
        label: String,
        checked: Boolean,
        detail: String? = null,
        onChanged: (Boolean) -> Unit,
    ) {
        content.addView(CheckBox(this).apply {
            text = label
            isChecked = checked
            minHeight = dp(48)
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
        if (!detail.isNullOrBlank()) {
            description(detail)
        }
    }

    private fun spinner(
        labels: List<String>,
        selected: Int,
        accessibilityLabel: String,
        onSelected: (Int) -> Unit,
    ) {
        val safeSelected = selected.coerceIn(0, labels.lastIndex.coerceAtLeast(0))
        val view = Spinner(this).apply {
            minimumHeight = dp(48)
            contentDescription = "$accessibilityLabel. Đang chọn ${labels.getOrElse(safeSelected) { "" }}"
        }
        view.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        view.setSelection(safeSelected)

        var ready = false
        view.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                item: View?,
                position: Int,
                id: Long,
            ) {
                view.contentDescription =
                    "$accessibilityLabel. Đang chọn ${labels.getOrElse(position) { "" }}"
                if (ready) {
                    onSelected(position)
                }
            }

            override fun onNothingSelected(
                parent: android.widget.AdapterView<*>?,
            ) = Unit
        }
        view.post { ready = true }
        content.addView(view)
    }

    private fun slider(
        label: String,
        current: Int,
        minValue: Int,
        maxValue: Int,
        step: Int,
        unit: String,
        onChanged: (Int) -> Unit,
    ) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val seek = SeekBar(this).apply {
            id = View.generateViewId()
            val steps = ((maxValue - minValue) / step).coerceAtLeast(1)
            max = steps
            progress = ((current - minValue) / step).coerceIn(0, steps)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                .43f,
            )
            contentDescription = "$label: $current$unit"
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 14f
            labelFor = seek.id
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                .35f,
            )
        }
        val valueView = TextView(this).apply {
            text = "$current$unit"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                .22f,
            )
        }

        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    val value = (minValue + progress * step).coerceAtMost(maxValue)
                    valueView.text = "$value$unit"
                    seek.contentDescription = "$label: $value$unit"
                    if (fromUser) {
                        onChanged(value)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            },
        )

        top.addView(labelView)
        top.addView(seek)
        top.addView(valueView)
        wrapper.addView(top)
        content.addView(wrapper)
    }

    private fun rowButton(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { action() }
        })
    }

    private fun friendly(keys: Set<String>): String = keys.joinToString { key ->
        FRIENDLY_NAMES[key] ?: key
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) {
            getColor(value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATE_DRAFT = "settings_draft"
        private const val STATE_TAB = "settings_tab"

        private val TAB_LABELS = linkedMapOf(
            "basic" to "Cơ bản",
            "audio" to "Âm thanh",
            "latency" to "Độ ổn định",
            "save" to "Lưu và xuất",
            "system" to "Hệ thống",
        )

        private val FRIENDLY_NAMES = mapOf(
            "model" to "bộ máy dịch",
            "targetLanguage" to "ngôn ngữ cần dịch sang",
            "echoTargetLanguage" to "đọc lại câu đã đúng ngôn ngữ",
            "aiVoice" to "giọng dịch",
            "aiAudioStreamType" to "cách phát giọng dịch",
            "autoDucking" to "tự giảm âm gốc",
            "duckVolumeFactor" to "âm lượng gốc còn lại",
            "muteOriginalInInternal" to "tắt âm gốc khi nghe trong máy",
            "uiMode" to "giao diện",
            "performanceProfile" to "cách ứng dụng ưu tiên",
            "autoReconnect" to "tự kết nối lại",
            "reconnectMaxRetries" to "số lần thử lại",
            "qualityMode" to "ưu tiên âm thanh ổn định",
            "inputBufferMs" to "thời gian chuẩn bị âm thanh",
            "outputJitterTarget" to "số đoạn chờ trước khi phát",
            "fileSyncDelayMs" to "thời gian chờ khớp với tệp",
            "pacingEnabled" to "gửi theo tốc độ phát",
            "pacingTargetLatencyMs" to "khoảng chuẩn bị trước",
            "pacingMaxBuffer" to "số đoạn chờ gửi",
            "translatedBufferBytes" to "dung lượng phát âm thanh",
            "translatedQueueMax" to "số đoạn chờ phát",
            "ttsSmoothEnabled" to "ghép câu trước khi đọc",
            "ttsSmoothTimeoutMs" to "thời gian chờ ghép câu",
            "ttsSmoothMinChars" to "độ dài câu tối thiểu",
            "ttsSmoothMinWords" to "số từ tối thiểu",
            "saveAudioEnabled" to "lưu bản ghi âm",
            "saveAudioMode" to "nội dung cần lưu",
            "exportFormat" to "định dạng lời dịch",
            "logLevel" to "mức ghi lỗi",
            "logToFile" to "lưu nhật ký lỗi",
            "logIncludeTranscript" to "ghi nội dung hội thoại",
            "originalVolume" to "âm lượng gốc",
            "translatedVolume" to "âm lượng giọng dịch",
            "micLanguages" to "danh sách ngôn ngữ",
            "micLanguageIndex" to "ngôn ngữ đang dùng",
        )
    }
}
