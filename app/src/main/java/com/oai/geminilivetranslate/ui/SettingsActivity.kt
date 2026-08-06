package com.oai.geminilivetranslate.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.AppSettings
import com.oai.geminilivetranslate.core.DiagnosticContext
import com.oai.geminilivetranslate.core.LanguageCatalog
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
        showTab(activeTab)
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
            setPadding(12, 12, 12, 12)
        }
        root.addView(TextView(this).apply {
            text = "Cài đặt"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Các thay đổi an toàn được áp dụng ngay. Model và ngôn ngữ sẽ tự nối lại Gemini; một số bộ đệm nguồn áp dụng từ phiên tiếp theo."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 8)
        })
        val tabs = listOf(
            "basic" to "Cơ bản",
            "audio" to "Âm thanh",
            "latency" to "Độ trễ",
            "save" to "Lưu & xuất",
            "system" to "Hệ thống",
        )
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabButtons = tabs.associate { (key, label) ->
            key to Button(this).apply {
                text = label
                setOnClickListener { captureTextFields(); showTab(key) }
                bar.addView(this)
            }
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        })
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 20)
        }
        root.addView(ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Lưu & áp dụng"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveAndClose() }
        })
        actions.addView(Button(this).apply {
            text = "Đóng"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        root.addView(actions)
        return root
    }

    private fun showTab(key: String) {
        activeTab = key
        tabButtons.forEach { (tab, button) ->
            val plain = button.text.toString().trim('[', ']')
            button.text = if (tab == key) "[$plain]" else plain
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
    }

    private fun buildBasic() {
        title("Hồ sơ hoạt động")
        val profiles = listOf("realtime", "balanced", "stable", "custom")
        spinner(
            labels = listOf("Thời gian thực", "Cân bằng", "Ổn định", "Tùy chỉnh"),
            selected = profiles.indexOf(draft.performanceProfile).coerceAtLeast(0),
        ) { position ->
            captureTextFields()
            draft = SettingsPolicy.applyProfile(profiles[position], draft)
            toast("Đã nạp hồ sơ ${listOf("Thời gian thực", "Cân bằng", "Ổn định", "Tùy chỉnh")[position]}")
            showTab(activeTab)
        }
        description("Thời gian thực ưu tiên phản hồi nhanh; Cân bằng là mặc định; Ổn định tăng bộ đệm và khả năng phục hồi.")

        title("Chế độ giao diện")
        spinner(listOf("Đơn giản", "Nâng cao"), if (draft.uiMode == "simple") 0 else 1) {
            draft = draft.copy(uiMode = if (it == 0) "simple" else "advanced")
        }
        description("Đơn giản ẩn điều khiển ít dùng trên màn hình chính nhưng không xóa tính năng.")

        title("Model Gemini Live Translate")
        modelInput = EditText(this).apply {
            setText(draft.model)
            hint = AppPreferences.DEFAULT_MODEL
            isSingleLine = true
        }.also(content::addView)
        description("Tên models/ ở đầu sẽ tự loại bỏ. Thay model trong lúc dịch sẽ tạo kết nối Gemini mới.")

        title("Ngôn ngữ đích")
        val currentIndex = LanguageCatalog.codes.indexOf(draft.targetLanguage).coerceAtLeast(0)
        spinner(LanguageCatalog.labels, currentIndex) { position ->
            val code = LanguageCatalog.codes[position]
            draft = draft.copy(targetLanguage = code)
            languageInput?.setText(code)
        }
        languageInput = EditText(this).apply {
            setText(draft.targetLanguage)
            hint = "Mã BCP-47, ví dụ: fr-CA"
            isSingleLine = true
        }.also(content::addView)
        description("Có thể chọn danh sách hoặc nhập mã BCP-47. Khi lưu, ứng dụng tự kiểm tra và chuẩn hóa.")

        title("Tùy chọn dịch")
        check("Lặp lại lời nói đã ở ngôn ngữ đích", draft.echoTargetLanguage,
            "Bật để Gemini vẫn phát lại khi đầu vào đã đúng ngôn ngữ đích.") {
            draft = draft.copy(echoTargetLanguage = it)
        }
        check("Tắt âm media khi thu nội bộ", draft.muteOriginalInInternal,
            "Đưa âm lượng media hệ thống về 0 trong phiên thu nội bộ và phục hồi khi dừng.") {
            draft = draft.copy(muteOriginalInInternal = it)
        }
    }

    private fun buildAudio() {
        title("Bộ đệm giọng dịch")
        slider("Kích thước AudioTrack", draft.translatedBufferBytes, 48_000, 192_000, 4_000, " bytes") {
            draft = draft.copy(translatedBufferBytes = it, performanceProfile = "custom")
        }
        slider("Số chunk tối đa", draft.translatedQueueMax, 5, 100, 1, " chunks") {
            draft = draft.copy(translatedQueueMax = it, performanceProfile = "custom")
        }
        description("Hai mục này có thể tạo lại bộ phát giọng dịch khi phiên đang chạy; một ít audio đang chờ có thể bị xả để tránh phát sai thời điểm.")

        title("Tự động giảm âm nền")
        check("Bật tự động giảm âm nền", draft.autoDucking,
            "Giảm âm gốc khi giọng dịch đang phát.") { draft = draft.copy(autoDucking = it) }
        slider("Mức âm gốc khi ducking", (draft.duckVolumeFactor * 10).toInt(), 0, 10, 1, "/10") {
            draft = draft.copy(duckVolumeFactor = it / 10f)
        }

        title("TTS thiết bị")
        check("Làm mượt TTS thiết bị", draft.ttsSmoothEnabled,
            "Khi tắt Giọng AI, gom các mảnh dịch ngắn trước khi đọc.") {
            draft = draft.copy(ttsSmoothEnabled = it)
            showTab(activeTab)
        }
        if (draft.ttsSmoothEnabled) {
            slider("Timeout TTS mượt", draft.ttsSmoothTimeoutMs, 500, 5_000, 100, " ms") {
                draft = draft.copy(ttsSmoothTimeoutMs = it)
            }
            slider("Ký tự tối thiểu TTS", draft.ttsSmoothMinChars, 20, 200, 5, "") {
                draft = draft.copy(ttsSmoothMinChars = it)
            }
            slider("Số từ tối thiểu TTS", draft.ttsSmoothMinWords, 3, 20, 1, "") {
                draft = draft.copy(ttsSmoothMinWords = it)
            }
        }
    }

    private fun buildLatency() {
        title("Chất lượng và bộ đệm")
        check("Chế độ chất lượng (Tệp/Âm thanh nội bộ)", draft.qualityMode,
            "Tích lũy nhiều audio hơn để ưu tiên ổn định, đổi lại độ trễ cao hơn.") {
            draft = draft.copy(qualityMode = it, performanceProfile = "custom")
            showTab(activeTab)
        }
        if (draft.qualityMode) {
            slider("Buffer đầu vào", draft.inputBufferMs, 200, 10_000, 100, " ms") {
                draft = draft.copy(inputBufferMs = it, performanceProfile = "custom")
            }
            slider("Buffer đầu ra", draft.outputJitterTarget, 3, 20, 1, " chunks") {
                draft = draft.copy(outputJitterTarget = it, performanceProfile = "custom")
            }
        }
        slider("Độ trễ đồng bộ tệp", draft.fileSyncDelayMs, 0, 20_000, 250, " ms") {
            draft = draft.copy(fileSyncDelayMs = it, performanceProfile = "custom")
        }

        title("Điều tiết gửi mạng")
        check("Đồng bộ tốc độ đọc tệp", draft.pacingEnabled,
            "Giữ tốc độ giải mã tệp gần thời gian thực để không đẩy audio đi quá nhanh.") {
            draft = draft.copy(pacingEnabled = it, performanceProfile = "custom")
            showTab(activeTab)
        }
        slider("Hàng đợi gửi tối đa", draft.pacingMaxBuffer, 1, 50, 1, " chunks") {
            draft = draft.copy(pacingMaxBuffer = it, performanceProfile = "custom")
        }
        description("Giới hạn thật số chunk chờ gửi. Microphone/nội bộ bỏ chunk cũ khi nghẽn; tệp sẽ chờ để không mất nội dung. Thay đổi áp dụng từ phiên tiếp theo để không cắt audio đang chờ.")
        if (draft.pacingEnabled) {
            slider("Độ dẫn trước khi đọc tệp", draft.pacingTargetLatencyMs, 100, 2_000, 50, " ms") {
                draft = draft.copy(pacingTargetLatencyMs = it, performanceProfile = "custom")
            }
        }
    }

    private fun buildSave() {
        title("Lưu file audio")
        check("Bật lưu file audio", draft.saveAudioEnabled,
            "WAV được lưu trong thư mục Music riêng của ứng dụng. Thay đổi có hiệu lực từ phiên tiếp theo.") {
            draft = draft.copy(saveAudioEnabled = it)
            showTab(activeTab)
        }
        if (draft.saveAudioEnabled) {
            spinner(
                listOf("Audio dịch (cần Giọng AI)", "Audio gốc", "Audio trộn (cần Giọng AI)"),
                listOf("translated", "original", "mixed").indexOf(draft.saveAudioMode).coerceAtLeast(0),
            ) { draft = draft.copy(saveAudioMode = listOf("translated", "original", "mixed")[it]) }
            description("Chế độ trộn giữ cả file gốc và dịch riêng, đồng thời tạo bản trộn theo timeline.")
        }
        title("Xuất phụ đề / văn bản")
        spinner(listOf("Phụ đề SRT", "Văn bản TXT"), if (draft.exportFormat == "txt") 1 else 0) {
            draft = draft.copy(exportFormat = if (it == 1) "txt" else "srt")
        }
    }

    private fun buildSystem() {
        title("Kết nối lại")
        check("Tự động kết nối lại", draft.autoReconnect,
            "Tự thử lại khi WebSocket bị mất.") {
            draft = draft.copy(autoReconnect = it)
            showTab(activeTab)
        }
        if (draft.autoReconnect) {
            slider("Số lần thử lại", draft.reconnectMaxRetries, 1, 10, 1, "") {
                draft = draft.copy(reconnectMaxRetries = it)
            }
        }

        title("Nhật ký chẩn đoán")
        spinner(listOf("Chỉ lỗi", "Lỗi + cảnh báo", "Thông thường", "Chi tiết"), draft.logLevel) {
            draft = draft.copy(logLevel = it)
        }
        check("Ghi log xoay vòng ra file", draft.logToFile,
            "Khuyến nghị bật. Tối đa 5 tệp, mỗi tệp khoảng 2 MB; tệp cũ tự bị thay thế.") {
            draft = draft.copy(logToFile = it)
        }
        check("Cho phép ghi nội dung hội thoại", draft.logIncludeTranscript,
            "Mặc định tắt để bảo vệ riêng tư. Chỉ bật tạm thời khi cần phân tích lỗi transcript.") {
            draft = draft.copy(logIncludeTranscript = it)
        }
        rowButton("Mở nhật ký") { startActivity(Intent(this, LogViewerActivity::class.java)) }
        rowButton("Tạo và gửi báo cáo chẩn đoán") { shareDiagnostics() }
        rowButton("Xóa riêng nhật ký") { confirmClearLogs() }
        description("Báo cáo gồm log, stack trace, cấu hình đã khử dữ liệu nhạy cảm và trạng thái phiên. API Key/token được che tự động.")

        title("Kiểm tra")
        content.addView(Button(this).apply {
            text = "Kiểm tra API và kết nối"
            setOnClickListener { captureTextFields(); testConnection(this) }
        })

        title("Khôi phục và dọn dữ liệu")
        rowButton("Khôi phục cài đặt mặc định") { confirmRestoreSettings() }
        rowButton("Xóa bản ghi audio") { confirmDeleteRecordings() }
        rowButton("Xóa toàn bộ API Key") { confirmDeleteApiKeys() }
        content.addView(Button(this).apply {
            text = "XÓA TOÀN BỘ DỮ LIỆU ỨNG DỤNG"
            setTextColor(Color.RED)
            setOnClickListener { confirmFullReset() }
        })
        description("Các nút được tách riêng để tránh xóa nhầm. Xóa toàn bộ chỉ dùng khi cần đưa ứng dụng về trạng thái mới cài.")
    }

    private fun saveAndClose() {
        captureTextFields()
        val normalizedLanguage = LanguageCatalog.normalize(draft.targetLanguage)
        if (normalizedLanguage == null) {
            AlertDialog.Builder(this).setTitle("Mã ngôn ngữ không hợp lệ")
                .setMessage("Hãy nhập mã BCP-47 như vi, en-US hoặc fr-CA.")
                .setPositiveButton("Đóng", null).show()
            return
        }
        val safe = SettingsPolicy.sanitize(draft.copy(targetLanguage = normalizedLanguage))
        val diff = SettingsPolicy.diff(original, safe)
        preferences.save(safe)
        logger.log(2, "Settings", "Đã lưu cài đặt; changed=${diff.changed.joinToString().ifBlank { "none" }}")
        runCatching {
            startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_APPLY_SETTINGS))
        }.onFailure { logger.log(1, "Settings", "Không gửi được yêu cầu áp dụng cài đặt", it) }

        if (diff.isEmpty) {
            toast("Không có thay đổi")
            finish()
            return
        }
        val message = buildString {
            appendLine("Đã lưu cài đặt.")
            if (diff.immediate.isNotEmpty()) appendLine("• Đã áp dụng ngay: ${friendly(diff.immediate)}")
            if (diff.reconnect.isNotEmpty()) appendLine("• Gemini sẽ tự kết nối lại: ${friendly(diff.reconnect)}")
            if (diff.playbackRebuild.isNotEmpty()) appendLine("• Bộ phát sẽ được tạo lại: ${friendly(diff.playbackRebuild)}")
            if (diff.nextSession.isNotEmpty()) appendLine("• Có hiệu lực từ phiên tiếp theo: ${friendly(diff.nextSession)}")
        }.trim()
        AlertDialog.Builder(this)
            .setTitle("Đã lưu & áp dụng")
            .setMessage(message)
            .setPositiveButton("Đóng") { _, _ -> finish() }
            .show()
    }

    private fun captureTextFields() {
        modelInput?.text?.toString()?.let { draft = draft.copy(model = it) }
        languageInput?.text?.toString()?.let { draft = draft.copy(targetLanguage = it) }
    }

    private fun testConnection(button: Button) {
        val safeDraft = SettingsPolicy.sanitize(draft)
        val key = apiKeys.load().selected
        if (key.isNullOrBlank()) { toast("Chưa có API Key để kiểm tra"); return }
        button.isEnabled = false
        button.text = "Đang kiểm tra..."
        logger.log(2, "Settings", "Bắt đầu kiểm tra API model=${safeDraft.model} target=${safeDraft.targetLanguage}")
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
                toast("Kết nối tốt - $it ms")
            }.onFailure {
                logger.log(0, "Settings", "Kiểm tra API thất bại", it)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Kiểm tra thất bại")
                    .setMessage("${it.javaClass.simpleName}: ${it.message}\n\nMở Nhật ký & chẩn đoán để gửi báo cáo nếu cần.")
                    .setPositiveButton("Mở nhật ký") { _, _ -> startActivity(Intent(this@SettingsActivity, LogViewerActivity::class.java)) }
                    .setNegativeButton("Đóng", null)
                    .show()
            }
            button.isEnabled = true
            button.text = "Kiểm tra API và kết nối"
        }
    }

    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { logger.createDiagnosticBundle() } }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@SettingsActivity, "$packageName.files", file)
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Gemini Live Translate - báo cáo chẩn đoán")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Gửi báo cáo chẩn đoán"))
                }.onFailure {
                    logger.log(0, "Diagnostics", "Không mở được bảng chia sẻ báo cáo", it)
                    toast("Thiết bị không có ứng dụng nhận tệp ZIP")
                }
            }.onFailure { toast("Không tạo được báo cáo: ${it.message}") }
        }
    }

    private fun confirmClearLogs() = confirm(
        "Xóa nhật ký?",
        "Xóa log trong bộ nhớ và tất cả tệp log xoay vòng. Không ảnh hưởng cài đặt, API Key hay bản ghi.",
        "Xóa",
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { logger.clear() }
            toast("Đã xóa nhật ký")
        }
    }

    private fun confirmRestoreSettings() = confirm(
        "Khôi phục cài đặt mặc định?",
        "API Key, log và bản ghi audio được giữ nguyên. Phiên đang chạy sẽ nhận các thay đổi có thể áp dụng an toàn.",
        "Khôi phục",
    ) {
        val restored = preferences.restoreDefaultsPreservingKeys()
        draft = restored
        original = restored
        logger.log(1, "Settings", "Đã khôi phục cài đặt mặc định")
        startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_APPLY_SETTINGS))
        showTab(activeTab)
        toast("Đã khôi phục cài đặt mặc định")
    }

    private fun confirmDeleteRecordings() = confirm(
        "Xóa tất cả bản ghi audio?",
        "Chỉ xóa thư mục Music/GeminiLiveTranslate của ứng dụng.",
        "Xóa",
    ) {
        startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
        val recordings = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "GeminiLiveTranslate")
        val deleted = !recordings.exists() || recordings.deleteRecursively()
        logger.log(if (deleted) 1 else 0, "Settings", "Xóa bản ghi audio result=$deleted path=${recordings.absolutePath}")
        toast(if (deleted) "Đã xóa bản ghi" else "Không xóa hết được bản ghi")
    }

    private fun confirmDeleteApiKeys() = confirm(
        "Xóa toàn bộ API Key?",
        "Cài đặt, log và bản ghi được giữ nguyên. Bạn phải nhập lại API Key để dịch.",
        "Xóa API Key",
    ) {
        startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
        apiKeys.clear()
        logger.log(1, "Settings", "Đã xóa toàn bộ API Key và yêu cầu dừng phiên")
        toast("Đã xóa API Key")
    }

    private fun confirmFullReset() = confirm(
        "Xóa toàn bộ dữ liệu ứng dụng?",
        "Thao tác này xóa API Key, cài đặt, log và bản ghi audio. Không thể hoàn tác.",
        "Xóa toàn bộ",
    ) {
        startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
        apiKeys.clear()
        logger.clear()
        preferences.clear()
        File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "GeminiLiveTranslate").deleteRecursively()
        File(cacheDir, "diagnostic-share").deleteRecursively()
        DiagnosticContext.clearAll()
        toast("Đã xóa toàn bộ dữ liệu do ứng dụng quản lý")
        finish()
    }

    private fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 16, 0, 4)
    })

    private fun description(text: String) = content.addView(TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.DKGRAY)
        setPadding(0, 2, 0, 10)
    })

    private fun check(label: String, checked: Boolean, detail: String, onChanged: (Boolean) -> Unit) {
        content.addView(CheckBox(this).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
        description(detail)
    }

    private fun spinner(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val view = Spinner(this)
        view.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        view.setSelection(selected.coerceIn(0, labels.lastIndex.coerceAtLeast(0)))
        var ready = false
        view.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, item: View?, position: Int, id: Long) {
                if (ready) onSelected(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
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
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labelView = TextView(this).apply {
            text = "$label:"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, .35f)
        }
        val valueView = TextView(this).apply {
            text = "$current$unit"
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, .22f)
        }
        val seek = SeekBar(this).apply {
            val steps = ((maxValue - minValue) / step).coerceAtLeast(1)
            max = steps
            progress = ((current - minValue) / step).coerceIn(0, steps)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, .43f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = (minValue + progress * step).coerceAtMost(maxValue)
                    valueView.text = "$value$unit"
                    if (fromUser) onChanged(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        top.addView(labelView); top.addView(seek); top.addView(valueView)
        wrapper.addView(top)
        content.addView(wrapper)
    }

    private fun rowButton(label: String, action: () -> Unit) = content.addView(Button(this).apply {
        text = label
        setOnClickListener { action() }
    })

    private fun friendly(keys: Set<String>): String = keys.joinToString { key ->
        FRIENDLY_NAMES[key] ?: key
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_DRAFT = "settings_draft"
        private const val STATE_TAB = "settings_tab"
        private val FRIENDLY_NAMES = mapOf(
            "model" to "model",
            "targetLanguage" to "ngôn ngữ đích",
            "echoTargetLanguage" to "lặp ngôn ngữ đích",
            "aiVoice" to "giọng AI",
            "autoDucking" to "tự giảm âm",
            "duckVolumeFactor" to "mức giảm âm",
            "muteOriginalInInternal" to "tắt âm nội bộ",
            "uiMode" to "chế độ giao diện",
            "performanceProfile" to "hồ sơ hoạt động",
            "autoReconnect" to "tự kết nối lại",
            "reconnectMaxRetries" to "số lần kết nối lại",
            "qualityMode" to "chế độ chất lượng",
            "inputBufferMs" to "buffer đầu vào",
            "outputJitterTarget" to "jitter đầu ra",
            "fileSyncDelayMs" to "đồng bộ tệp",
            "pacingEnabled" to "điều tiết tệp",
            "pacingTargetLatencyMs" to "độ dẫn trước",
            "pacingMaxBuffer" to "hàng đợi gửi",
            "translatedBufferBytes" to "buffer phát",
            "translatedQueueMax" to "queue phát",
            "ttsSmoothEnabled" to "làm mượt TTS",
            "ttsSmoothTimeoutMs" to "timeout TTS",
            "ttsSmoothMinChars" to "ngưỡng ký tự TTS",
            "ttsSmoothMinWords" to "ngưỡng từ TTS",
            "saveAudioEnabled" to "lưu audio",
            "saveAudioMode" to "kiểu lưu audio",
            "exportFormat" to "định dạng xuất",
            "logLevel" to "mức log",
            "logToFile" to "ghi log file",
            "logIncludeTranscript" to "ghi hội thoại",
            "originalVolume" to "âm lượng gốc",
            "translatedVolume" to "âm lượng dịch",
            "micLanguages" to "danh sách ngôn ngữ microphone",
            "micLanguageIndex" to "ngôn ngữ microphone hiện tại",
        )
    }
}
