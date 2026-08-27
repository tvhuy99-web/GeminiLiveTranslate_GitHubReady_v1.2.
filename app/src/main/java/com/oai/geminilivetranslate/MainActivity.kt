package com.oai.geminilivetranslate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.oai.geminilivetranslate.audio.FileAudioSource
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.LanguageCatalog
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SessionUiState
import com.oai.geminilivetranslate.core.SourceMode
import com.oai.geminilivetranslate.databinding.ActivityMainBinding
import com.oai.geminilivetranslate.network.GeminiLiveClient
import com.oai.geminilivetranslate.service.TranslationService
import com.oai.geminilivetranslate.ui.LogViewerActivity
import com.oai.geminilivetranslate.ui.MiniBrowserActivity
import com.oai.geminilivetranslate.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var logger: SessionLogger
    private var translationService: TranslationService? = null
    private var bound = false
    private var spinnerReady = false
    private var micSpinnerReady = false
    private var aiStreamSpinnerReady = false
    private var pendingStartMode: SourceMode? = null
    private var pendingProjectionResultCode: Int? = null
    private var pendingProjectionData: Intent? = null
    private var pendingSelectedUri: Uri? = null
    private var pendingSelectedFileName: String? = null
    private var permissionPendingMode: SourceMode? = null
    private var legacyStoragePendingMode: SourceMode? = null
    private var stateJob: Job? = null
    private var subtitleRenderEvents = 0L
    private var lastRenderedTranscriptChars = -1
    private var selectedFilePlaybackSpeed = 1f
    private val uiPrefs by lazy { getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    private val aiStreamValues = listOf(
        "media",
        "accessibility",
        "alarm",
        "notification",
        "ring",
        "system",
        "voice_call",
        "dtmf",
        "voice_communication",
        "assistant",
    )
    private val aiStreamLabels = listOf(
        "Phương tiện / nhạc (Music)",
        "Trợ năng (Accessibility)",
        "Báo thức (Alarm)",
        "Thông báo (Notification)",
        "Nhạc chuông (Ring)",
        "Hệ thống (System)",
        "Cuộc gọi (Voice Call)",
        "DTMF",
        "Giao tiếp bằng giọng nói",
        "Trợ lý Android",
    )
    private var pendingExportText: String? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val name = displayName(uri)
        logger.log(2, "UI", "Đã chọn tệp name=${name ?: uri.lastPathSegment} uriScheme=${uri.scheme}")
        val service = translationService
        if (service != null) {
            service.setSelectedFile(uri, name)
        } else {
            pendingSelectedUri = uri
            pendingSelectedFileName = name
        }
        binding.selectFileButton.text = name ?: "Tệp đã chọn"
        toast("Đã chọn: ${name ?: uri.lastPathSegment}")
    }

    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = permissionPendingMode ?: SourceMode.MICROPHONE
        permissionPendingMode = null
        logger.log(if (granted) 2 else 1, "Permission", "Kết quả quyền microphone granted=$granted mode=$mode")
        if (granted) startMode(mode) else toast("Cần quyền Microphone để bắt đầu")
    }

    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = legacyStoragePendingMode
        legacyStoragePendingMode = null
        logger.log(if (granted) 2 else 1, "Permission", "Quyền lưu tệp công khai Android 8/9 granted=$granted")
        if (granted && mode != null) startMode(mode)
        else if (!granted) toast("Cần quyền bộ nhớ để lưu WAV vào thư mục Music trên Android 8/9")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val projectionData = result.data
        logger.log(if (result.resultCode == Activity.RESULT_OK && projectionData != null) 2 else 1, "Permission", "Kết quả MediaProjection resultCode=${result.resultCode} hasData=${projectionData != null}")
        if (result.resultCode == Activity.RESULT_OK && projectionData != null) {
            ensureServiceStarted()
            val service = translationService
            if (service != null) {
                service.startTranslation(SourceMode.INTERNAL, result.resultCode, projectionData)
            } else {
                pendingStartMode = SourceMode.INTERNAL
                pendingProjectionResultCode = result.resultCode
                pendingProjectionData = projectionData
            }
        } else toast("Bạn chưa cấp quyền thu âm thanh nội bộ")
    }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri == null || text == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                ?: error("Không mở được tệp đích")
        }.onSuccess {
            logger.log(2, "Export", "Đã xuất transcript uriScheme=${uri.scheme} chars=${text.length}")
            toast("Đã xuất tệp")
        }.onFailure {
            logger.log(0, "Export", "Lỗi xuất transcript", it)
            toast("Lỗi xuất tệp: ${it.message}")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            translationService = (binder as? TranslationService.LocalBinder)?.getService()
            bound = translationService != null
            logger.log(2, "Service", "Đã bind TranslationService success=$bound")
            translationService?.let { service ->
                val restoredMode = loadSourceMode()
                service.setSourceMode(restoredMode)
                service.setProcessingMode(preferences.loadProcessingMode())
                service.setSpeakerDiarization(preferences.loadSpeakerDiarization())
                selectedFilePlaybackSpeed = loadFilePlaybackSpeed()
                service.setFilePlaybackSpeed(selectedFilePlaybackSpeed)
                syncFileSpeedUi(selectedFilePlaybackSpeed)
            }
            pendingSelectedUri?.let { uri ->
                translationService?.setSelectedFile(uri, pendingSelectedFileName)
                pendingSelectedUri = null
                pendingSelectedFileName = null
            }
            observeService()
            pendingStartMode?.let { mode ->
                pendingStartMode = null
                if (mode == SourceMode.INTERNAL) {
                    val resultCode = pendingProjectionResultCode
                    val data = pendingProjectionData
                    pendingProjectionResultCode = null
                    pendingProjectionData = null
                    if (resultCode != null && data != null) {
                        translationService?.startTranslation(SourceMode.INTERNAL, resultCode, data)
                    } else {
                        startMode(SourceMode.INTERNAL)
                    }
                } else {
                    startMode(mode)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logger.log(1, "Service", "TranslationService bị ngắt component=$name")
            bound = false
            translationService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)
        apiKeyStore = ApiKeyStore(this)
        logger = SessionLogger(this, preferences)
        selectedFilePlaybackSpeed = loadFilePlaybackSpeed()
        logger.log(2, "UI", "MainActivity onCreate source=${loadSourceMode()} fileSpeed=${String.format(Locale.US, "%.1f", selectedFilePlaybackSpeed)}x")
        setupUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TranslationService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        if (bound) unbindService(connection)
        bound = false
        translationService = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        restorePreferencesUi()
    }

    private fun setupUi() = with(binding) {
        titleText.text = "Gemini Live Translate v${BuildConfig.VERSION_NAME}"
        processingModeButton.setOnClickListener {
            if (translationService?.state?.value?.running == true) return@setOnClickListener
            val next = if (isTranscribeSelected()) {
                AppPreferences.PROCESSING_MODE_TRANSLATE
            } else {
                AppPreferences.PROCESSING_MODE_TRANSCRIBE
            }
            preferences.setProcessingMode(next)
            translationService?.setProcessingMode(next)
            restoreProcessingModeUi()
        }
        speakerDiarizationSwitch.setOnCheckedChangeListener { _, checked ->
            if (speakerDiarizationSwitch.isPressed) {
                preferences.setSpeakerDiarization(checked)
                translationService?.setSpeakerDiarization(checked)
            }
        }
        audioSourceSpinner.adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_item,
            listOf("Tệp âm thanh/video", "Microphone", "Ghi âm nội bộ (Android 10+)")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        audioSourceSpinner.onItemSelectedListener = simpleSelection { position ->
            if (!spinnerReady) return@simpleSelection
            val mode = SourceMode.entries.getOrElse(position) { SourceMode.FILE }
            if (mode == SourceMode.INTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                toast("Yêu cầu Android 10 trở lên")
                saveSourceMode(SourceMode.FILE)
                audioSourceSpinner.setSelection(SourceMode.FILE.ordinal)
                return@simpleSelection
            }
            saveSourceMode(mode)
            translationService?.setSourceMode(mode)
            translationService?.setSpeakerDiarization(preferences.loadSpeakerDiarization())
            updateModeUi(mode, translationService?.state?.value?.running == true)
        }
        audioSourceSpinner.post { spinnerReady = true }

        selectFileButton.setOnClickListener { launchFilePicker() }
        miniBrowserButton.setOnClickListener { startActivity(Intent(this@MainActivity, MiniBrowserActivity::class.java)) }
        apiKeyButton.setOnClickListener { showApiKeyManager() }
        testConnectionButton.setOnClickListener { testConnection() }
        startButton.setOnClickListener {
            val service = translationService
            if (service?.state?.value?.running == true) service.stopTranslation()
            else startMode(SourceMode.entries.getOrElse(audioSourceSpinner.selectedItemPosition) { SourceMode.FILE })
        }
        playPauseButton.setOnClickListener { translationService?.togglePause() }
        rewindButton.setOnClickListener { translationService?.seekBy(-10_000) }
        forwardButton.setOnClickListener { translationService?.seekBy(10_000) }

        fileSpeedSeekBar.max = FILE_SPEED_STEPS
        fileSpeedSeekBar.setOnSeekBarChangeListener(seekListener(onChange = { value ->
            val speed = (1f + value / 10f).coerceIn(
                FileAudioSource.MIN_PLAYBACK_SPEED,
                FileAudioSource.MAX_PLAYBACK_SPEED,
            )
            selectedFilePlaybackSpeed = speed
            saveFilePlaybackSpeed(speed)
            syncFileSpeedUi(speed)
            translationService?.setFilePlaybackSpeed(speed)
        }))
        syncFileSpeedUi(selectedFilePlaybackSpeed)

        progressSeekBar.setOnSeekBarChangeListener(seekListener(onStop = { value ->
            if (translationService?.state?.value?.running == true) translationService?.seekToPercent(value)
        }))
        originalVolumeSeekBar.setOnSeekBarChangeListener(seekListener(onChange = { value ->
            originalVolumeSeekBar.contentDescription = "Âm lượng gốc: $value%"
            translationService?.setVolumes(value, translatedVolumeSeekBar.progress)
        }))
        translatedVolumeSeekBar.setOnSeekBarChangeListener(seekListener(onChange = { value ->
            translatedVolumeSeekBar.contentDescription = "Âm lượng dịch: $value%"
            translationService?.setVolumes(originalVolumeSeekBar.progress, value)
        }))
        aiAudioStreamSpinner.adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_item,
            aiStreamLabels,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        aiAudioStreamSpinner.onItemSelectedListener = simpleSelection { position ->
            if (!aiStreamSpinnerReady) return@simpleSelection
            val value = aiStreamValues.getOrElse(position) { "accessibility" }
            val before = preferences.load().aiAudioStreamType
            if (before == value) return@simpleSelection
            preferences.setAiAudioStreamType(value)
            logger.log(2, "Settings", "Đổi luồng phát giọng AI từ $before sang $value")
            if (translationService?.state?.value?.running == true) {
                startService(Intent(this@MainActivity, TranslationService::class.java).setAction(TranslationService.ACTION_APPLY_SETTINGS))
                toast("Đã đổi luồng phát giọng AI")
            }
        }
        aiVoiceSwitch.setOnCheckedChangeListener { _, checked ->
            translationService?.setAiVoice(checked) ?: preferences.setAiVoice(checked)
            applyUiMode()
        }
        autoDuckingSwitch.setOnCheckedChangeListener { _, checked -> translationService?.setAutoDucking(checked) ?: preferences.setAutoDucking(checked) }
        exportButton.setOnClickListener { exportTranscript() }
        settingsButton.setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        logButton.setOnClickListener { startActivity(Intent(this@MainActivity, LogViewerActivity::class.java)) }
        manageLanguagesButton.setOnClickListener { showMicLanguageManager() }
        nextLanguageButton.setOnClickListener {
            val code = translationService?.switchToNextMicLanguage() ?: return@setOnClickListener
            toast("Đã chuyển sang ${LanguageCatalog.displayName(code)}")
            restoreMicLanguageSpinner()
        }
        micLanguageSpinner.onItemSelectedListener = simpleSelection { position ->
            if (!micSpinnerReady) return@simpleSelection
            val settings = preferences.load()
            val languages = settings.micLanguages
            if (position !in languages.indices) return@simpleSelection
            val code = languages[position]
            preferences.setMicLanguages(languages, position)
            preferences.setTargetLanguage(code)
            logger.log(2, "Settings", "Chọn ngôn ngữ microphone code=$code index=$position")
            if (translationService?.state?.value?.running == true) {
                startService(Intent(this@MainActivity, TranslationService::class.java).setAction(TranslationService.ACTION_APPLY_SETTINGS))
                toast("Đang áp dụng ${LanguageCatalog.displayName(code)}")
            }
        }
        restorePreferencesUi()
    }

    private fun observeService() {
        val service = translationService ?: return
        stateJob?.cancel()
        subtitleRenderEvents = 0L
        lastRenderedTranscriptChars = -1
        logger.log(2, "SubtitleUI", "Bắt đầu collect StateFlow lifecycle=${lifecycle.currentState} running=${service.state.value.running} transcriptChars=${service.state.value.transcript.length}")
        stateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                logger.log(2, "SubtitleUI", "Collector ACTIVE lifecycle=${lifecycle.currentState}")
                service.state.collect(::render)
            }
        }
    }

    private fun render(state: SessionUiState) = with(binding) {
        statusText.text = buildString {
            append("Trạng thái: ").append(state.status)
            if (state.health.isNotBlank()) append('\n').append(state.health)
        }
        statusText.contentDescription = statusText.text

        val transcriptChars = state.transcript.length
        subtitleRenderEvents++
        if (transcriptChars != lastRenderedTranscriptChars) {
            logger.log(
                2,
                "SubtitleUI",
                "render event=$subtitleRenderEvents transcriptChars=$transcriptChars previousChars=$lastRenderedTranscriptChars running=${state.running} paused=${state.paused} setup=${state.setupComplete} lifecycle=${lifecycle.currentState}",
            )
            lastRenderedTranscriptChars = transcriptChars
        }
        val emptyTranscript = if (isTranscribeSelected()) "Chưa có nội dung chép lời" else "Chưa có nội dung dịch"
        subtitleText.text = state.transcript.ifBlank { emptyTranscript }
        val expectedChars = if (transcriptChars == 0) emptyTranscript.length else transcriptChars
        val actualChars = subtitleText.text.length
        if (actualChars != expectedChars) {
            logger.log(1, "SubtitleUI", "TextView mismatch stateChars=$transcriptChars expectedChars=$expectedChars actualChars=$actualChars")
        }
        if (transcriptChars > 0) {
            logger.log(
                3,
                "SubtitleUI",
                "TextView committed stateChars=$transcriptChars viewChars=$actualChars shown=${subtitleText.isShown} visibility=${subtitleText.visibility} alpha=${subtitleText.alpha} width=${subtitleText.width} height=${subtitleText.height}",
            )
        }
        subtitleScroll.post { subtitleScroll.fullScroll(View.FOCUS_DOWN) }
        if (!progressSeekBar.isPressed) progressSeekBar.progress = state.progressPercent
        progressSeekBar.contentDescription = if (isTranscribeSelected()) {
            "Tiến trình xử lý: ${state.progressPercent}%"
        } else {
            "Tiến trình phát: ${state.progressPercent}%"
        }
        if (!isTranscribeSelected()) aiVoiceSwitch.isChecked = state.aiVoice
        if (audioSourceSpinner.selectedItemPosition != state.sourceMode.ordinal) {
            spinnerReady = false
            audioSourceSpinner.setSelection(state.sourceMode.ordinal)
            audioSourceSpinner.post { spinnerReady = true }
        }
        state.selectedFileName?.let { selectFileButton.text = it }
        startButton.text = when {
            state.running && isTranscribeSelected() -> "Dừng chép lời"
            state.running -> "Dừng dịch"
            isTranscribeSelected() -> "Bắt đầu chép lời"
            state.sourceMode == SourceMode.MICROPHONE -> "Bắt đầu thu âm"
            state.sourceMode == SourceMode.INTERNAL -> "Bắt đầu thu nội bộ"
            else -> "Bắt đầu"
        }
        playPauseButton.text = if (state.paused) "Phát" else "Tạm dừng"
        playPauseButton.isEnabled = state.running && state.setupComplete
        updateModeUi(state.sourceMode, state.running)
    }

    private fun startMode(mode: SourceMode) {
        saveSourceMode(mode)
        logger.log(2, "UI", "Yêu cầu bắt đầu source=$mode serviceBound=${translationService != null}")
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            preferences.load().saveAudioEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyStoragePendingMode = mode
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val service = translationService
        if (service == null) {
            pendingStartMode = mode
            ensureServiceStarted()
            return
        }
        service.setSourceMode(mode)
        service.setProcessingMode(preferences.loadProcessingMode())
        service.setSpeakerDiarization(preferences.loadSpeakerDiarization())
        if (mode == SourceMode.FILE && !isTranscribeSelected()) service.setFilePlaybackSpeed(selectedFilePlaybackSpeed)
        startService(Intent(this, TranslationService::class.java))
        when (mode) {
            SourceMode.FILE -> service.startTranslation(mode)
            SourceMode.MICROPHONE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    service.startTranslation(mode)
                } else {
                    permissionPendingMode = mode
                    recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            SourceMode.INTERNAL -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    toast("Thu âm nội bộ yêu cầu Android 10 trở lên")
                    saveSourceMode(SourceMode.FILE)
                    return
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permissionPendingMode = mode
                    recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionPermission.launch(manager.createScreenCaptureIntent())
            }
        }
    }

    private fun updateModeUi(mode: SourceMode, running: Boolean) = with(binding) {
        val transcribe = isTranscribeSelected()
        val fileMode = mode == SourceMode.FILE
        val micMode = mode == SourceMode.MICROPHONE
        processingModeButton.isEnabled = !running
        processingModeButton.text = if (transcribe) "Chế độ: Chép lời" else "Chế độ: Dịch thuật"
        speakerDiarizationSwitch.isVisible = transcribe && fileMode
        speakerDiarizationSwitch.isEnabled = !running
        selectFileButton.isVisible = fileMode
        miniBrowserButton.isVisible = !transcribe || mode == SourceMode.INTERNAL
        fileControls.isVisible = fileMode && !transcribe
        fileSpeedLayout.isVisible = fileMode && !transcribe
        progressSeekBar.isVisible = fileMode
        progressSeekBar.isEnabled = !transcribe
        originalVolumeSeekBar.isVisible = !transcribe && mode != SourceMode.MICROPHONE
        translatedVolumeLabel.isVisible = !transcribe
        translatedVolumeSeekBar.isVisible = !transcribe
        aiVoiceSwitch.isVisible = !transcribe
        aiAudioStreamLayout.isVisible = !transcribe
        autoDuckingSwitch.isVisible = !transcribe
        micLanguageLayout.isVisible = micMode && !transcribe
        nextLanguageButton.isVisible = micMode && !transcribe && preferences.load().micLanguages.size > 1
        if (!running) {
            startButton.text = if (transcribe) {
                "Bắt đầu chép lời"
            } else {
                when (mode) {
                    SourceMode.FILE -> "Bắt đầu"
                    SourceMode.MICROPHONE -> "Bắt đầu thu âm"
                    SourceMode.INTERNAL -> "Bắt đầu thu nội bộ"
                }
            }
        }
        applyUiMode()
    }

    private fun restorePreferencesUi() = with(binding) {
        val settings = preferences.load()
        val restoredMode = loadSourceMode()
        selectedFilePlaybackSpeed = loadFilePlaybackSpeed()
        spinnerReady = false
        audioSourceSpinner.setSelection(restoredMode.ordinal)
        audioSourceSpinner.post { spinnerReady = true }
        translationService?.setSourceMode(restoredMode)
        translationService?.setProcessingMode(preferences.loadProcessingMode())
        translationService?.setSpeakerDiarization(preferences.loadSpeakerDiarization())
        translationService?.setFilePlaybackSpeed(selectedFilePlaybackSpeed)
        originalVolumeSeekBar.progress = settings.originalVolume
        translatedVolumeSeekBar.progress = settings.translatedVolume
        aiVoiceSwitch.isChecked = settings.aiVoice
        aiStreamSpinnerReady = false
        aiAudioStreamSpinner.setSelection(aiStreamValues.indexOf(settings.aiAudioStreamType).coerceAtLeast(0))
        aiAudioStreamSpinner.post { aiStreamSpinnerReady = true }
        autoDuckingSwitch.isChecked = settings.autoDucking
        speakerDiarizationSwitch.isChecked = preferences.loadSpeakerDiarization()
        exportButton.text = if (settings.exportFormat == "txt") "Xuất văn bản (.txt)" else "Xuất phụ đề (.srt)"
        settingsButton.text = if (settings.uiMode == "simple") "Cài đặt" else "Cài đặt nâng cao"
        syncFileSpeedUi(selectedFilePlaybackSpeed)
        restoreMicLanguageSpinner()
        restoreProcessingModeUi()
        updateModeUi(restoredMode, translationService?.state?.value?.running == true)
        applyUiMode()
    }

    private fun syncFileSpeedUi(speed: Float) = with(binding) {
        val safe = speed.coerceIn(FileAudioSource.MIN_PLAYBACK_SPEED, FileAudioSource.MAX_PLAYBACK_SPEED)
        val progress = ((safe - 1f) * 10f).roundToInt().coerceIn(0, FILE_SPEED_STEPS)
        if (fileSpeedSeekBar.progress != progress) fileSpeedSeekBar.progress = progress
        val display = String.format(Locale.US, "%.1f", safe)
        fileSpeedLabel.text = "Tốc độ phát tệp: ${display}×"
        fileSpeedSeekBar.contentDescription = "Tốc độ phát tệp: $display lần"
    }

    private fun restoreMicLanguageSpinner() {
        val settings = preferences.load()
        val labels = settings.micLanguages.map(LanguageCatalog::displayName)
        binding.micLanguageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        micSpinnerReady = false
        binding.micLanguageSpinner.setSelection(settings.micLanguageIndex.coerceIn(0, labels.lastIndex.coerceAtLeast(0)))
        binding.micLanguageSpinner.post { micSpinnerReady = true }
        binding.nextLanguageButton.isVisible = labels.size > 1 && binding.micLanguageLayout.isVisible
    }

    private fun applyUiMode() = with(binding) {
        val simple = preferences.load().uiMode == "simple"
        val transcribe = isTranscribeSelected()
        val fileMode = audioSourceSpinner.selectedItemPosition == SourceMode.FILE.ordinal
        logButton.isVisible = !simple
        if (transcribe) {
            autoDuckingSwitch.isVisible = false
            aiAudioStreamLayout.isVisible = false
            fileSpeedLayout.isVisible = false
            rewindButton.isVisible = false
            forwardButton.isVisible = false
            originalVolumeSeekBar.isVisible = false
            translatedVolumeLabel.isVisible = false
            translatedVolumeSeekBar.isVisible = false
            aiVoiceSwitch.isVisible = false
            progressSeekBar.isVisible = fileMode
        } else {
            autoDuckingSwitch.isVisible = !simple
            aiAudioStreamLayout.isVisible = !simple && aiVoiceSwitch.isChecked
            fileSpeedLayout.isVisible = fileMode
            if (simple && fileMode) {
                rewindButton.isVisible = false
                forwardButton.isVisible = false
                progressSeekBar.isVisible = false
                originalVolumeSeekBar.isVisible = false
            } else if (fileMode) {
                rewindButton.isVisible = true
                forwardButton.isVisible = true
                progressSeekBar.isVisible = true
                originalVolumeSeekBar.isVisible = true
            }
        }
    }

    private fun showApiKeyManager() {
        val dialog = AlertDialog.Builder(this).setTitle("Quản lý API Key").create()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
        }
        fun rebuild() {
            root.removeAllViews()
            val state = apiKeyStore.load()
            if (state.keys.isEmpty()) root.addView(TextView(this).apply { text = "Chưa có API Key nào." })
            state.keys.forEachIndexed { index, key ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply {
                    text = "Key ${index + 1}${if (state.selected == key) " (đang dùng)" else ""}\n${apiKeyStore.masked(key)}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = "Chọn"
                    setOnClickListener {
                        val previous = apiKeyStore.load().selected
                        val updated = if (previous == key) apiKeyStore.load() else apiKeyStore.select(key)
                        logger.log(2, "ApiKey", "Đã chọn API Key index=${index + 1} changed=${previous != updated.selected}")
                        applySelectedApiKeyIfRunning(previous, updated.selected)
                        rebuild()
                        toast("Đã chọn Key ${index + 1}")
                    }
                })
                row.addView(Button(this).apply {
                    text = "Xóa"
                    setOnClickListener {
                        val previous = apiKeyStore.load().selected
                        val updated = apiKeyStore.remove(key)
                        logger.log(1, "ApiKey", "Đã xóa API Key index=${index + 1} selectedChanged=${previous != updated.selected}")
                        applySelectedApiKeyIfRunning(previous, updated.selected)
                        rebuild()
                    }
                })
                root.addView(row)
            }
            val input = EditText(this).apply {
                hint = "Nhập API Key mới vào đây..."
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            root.addView(input)
            root.addView(Button(this).apply {
                text = "Thêm mới"
                setOnClickListener {
                    runCatching { apiKeyStore.add(input.text.toString()) }
                        .onSuccess { updated ->
                            val previous = state.selected
                            logger.log(2, "ApiKey", "Đã thêm API Key; total=${updated.keys.size}")
                            applySelectedApiKeyIfRunning(previous, updated.selected)
                            input.text.clear()
                            rebuild()
                            toast("Đã thêm API Key mới")
                        }
                        .onFailure { toast(it.message ?: "API Key không hợp lệ") }
                }
            })
            root.addView(Button(this).apply { text = "Đóng"; setOnClickListener { dialog.dismiss() } })
        }
        rebuild()
        scroll.addView(root)
        dialog.setView(scroll)
        dialog.show()
    }

    private fun applySelectedApiKeyIfRunning(previous: String?, current: String?) {
        if (previous == current || translationService?.state?.value?.running != true) return
        startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_REFRESH_API_KEY))
        toast(if (current == null) "API Key đã hết; đang dừng phiên" else "Đang áp dụng API Key mới")
    }

    private fun testConnection() {
        val key = apiKeyStore.load().selected
        if (key.isNullOrBlank()) { toast("Chưa có API Key để kiểm tra"); return }
        val settings = preferences.load()
        binding.testConnectionButton.isEnabled = false
        binding.testConnectionButton.text = "Đang kiểm tra..."
        binding.statusText.text = "Trạng thái: Đang kiểm tra API Key, model và kết nối Gemini..."
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val transcribe = isTranscribeSelected()
                    GeminiLiveClient.testConnection(
                        apiKey = key,
                        model = if (transcribe) AppPreferences.TRANSCRIBE_LIVE_MODEL else settings.model,
                        targetLanguage = settings.targetLanguage,
                        echoTargetLanguage = settings.echoTargetLanguage,
                        logger = logger,
                        operationMode = if (transcribe) {
                            GeminiLiveClient.OperationMode.TRANSCRIBE
                        } else {
                            GeminiLiveClient.OperationMode.TRANSLATE
                        },
                    )
                }
            }.onSuccess { elapsed ->
                logger.log(2, "Settings", "Kiểm tra API từ màn hình chính thành công latencyMs=$elapsed")
                binding.statusText.text = "Trạng thái: Kiểm tra thành công: API và Gemini hoạt động tốt ($elapsed ms)"
                toast("Kết nối tốt - $elapsed ms")
            }.onFailure {
                logger.log(0, "Settings", "Kiểm tra API từ màn hình chính thất bại", it)
                binding.statusText.text = "Trạng thái: Kiểm tra thất bại: ${it.message}"
                toast("Kiểm tra thất bại; mở Nhật ký để xem chi tiết")
            }
            binding.testConnectionButton.isEnabled = true
            binding.testConnectionButton.text = "Kiểm tra API và kết nối"
        }
    }

    private fun exportTranscript() {
        val format = preferences.load().exportFormat
        val text = translationService?.subtitleText(format).orEmpty()
        if (text.isBlank()) { toast("Chưa có nội dung để xuất"); return }
        pendingExportText = text
        val extension = if (format == "txt") "txt" else "srt"
        val prefix = if (isTranscribeSelected()) "gemini_transcribe" else "gemini_translate"
        exportDocument.launch("${prefix}_${System.currentTimeMillis()}.$extension")
    }

    private fun showMicLanguageManager() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, LanguageCatalog.labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        root.addView(listContainer)
        root.addView(addSpinner)
        root.addView(Button(this).apply {
            text = "Thêm ngôn ngữ đã chọn"
            setOnClickListener {
                val code = LanguageCatalog.codes[addSpinner.selectedItemPosition]
                val loaded = preferences.load()
                preferences.setMicLanguages((loaded.micLanguages + code).distinct(), loaded.micLanguageIndex)
                rebuildLanguageRows(listContainer)
            }
        })
        val custom = EditText(this).apply { hint = "Mã BCP-47, ví dụ: fr-CA"; isSingleLine = true }
        root.addView(custom)
        root.addView(Button(this).apply {
            text = "Thêm mã tùy chỉnh"
            setOnClickListener {
                val code = LanguageCatalog.normalize(custom.text.toString())
                if (code == null) toast("Mã ngôn ngữ không hợp lệ") else {
                    val loaded = preferences.load()
                    preferences.setMicLanguages((loaded.micLanguages + code).distinct(), loaded.micLanguageIndex)
                    custom.text.clear(); rebuildLanguageRows(listContainer)
                }
            }
        })
        rebuildLanguageRows(listContainer)
        AlertDialog.Builder(this).setTitle("Ngôn ngữ Microphone").setView(root)
            .setPositiveButton("Đóng") { _, _ -> restoreMicLanguageSpinner() }.show()
    }

    private fun rebuildLanguageRows(container: LinearLayout) {
        container.removeAllViews()
        val loaded = preferences.load()
        loaded.micLanguages.forEach { code ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = LanguageCatalog.displayName(code)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Xóa"
                isEnabled = loaded.micLanguages.size > 1
                setOnClickListener {
                    val newList = preferences.load().micLanguages.filterNot { it == code }.ifEmpty { listOf("vi") }
                    preferences.setMicLanguages(newList, 0)
                    rebuildLanguageRows(container)
                }
            })
            container.addView(row)
        }
    }

    private fun showTextDialog(title: String, text: String) {
        val view = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            setPadding(24, 20, 24, 20)
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Đóng", null).show()
    }

    private fun isTranscribeSelected(): Boolean =
        preferences.loadProcessingMode() == AppPreferences.PROCESSING_MODE_TRANSCRIBE

    private fun restoreProcessingModeUi() = with(binding) {
        val transcribe = isTranscribeSelected()
        processingModeButton.text = if (transcribe) "Chế độ: Chép lời" else "Chế độ: Dịch thuật"
        speakerDiarizationSwitch.isChecked = preferences.loadSpeakerDiarization()
        val mode = SourceMode.entries.getOrElse(audioSourceSpinner.selectedItemPosition) { SourceMode.FILE }
        speakerDiarizationSwitch.isVisible = transcribe && mode == SourceMode.FILE
    }

    private fun loadSourceMode(): SourceMode {
        val saved = uiPrefs.getString(KEY_SOURCE_MODE, SourceMode.FILE.name).orEmpty()
        val mode = runCatching { SourceMode.valueOf(saved) }.getOrDefault(SourceMode.FILE)
        return if (mode == SourceMode.INTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) SourceMode.FILE else mode
    }

    private fun saveSourceMode(mode: SourceMode) {
        val safe = if (mode == SourceMode.INTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) SourceMode.FILE else mode
        uiPrefs.edit().putString(KEY_SOURCE_MODE, safe.name).apply()
    }

    private fun loadFilePlaybackSpeed(): Float = uiPrefs
        .getFloat(KEY_FILE_PLAYBACK_SPEED, 1f)
        .coerceIn(FileAudioSource.MIN_PLAYBACK_SPEED, FileAudioSource.MAX_PLAYBACK_SPEED)

    private fun saveFilePlaybackSpeed(speed: Float) {
        uiPrefs.edit()
            .putFloat(
                KEY_FILE_PLAYBACK_SPEED,
                speed.coerceIn(FileAudioSource.MIN_PLAYBACK_SPEED, FileAudioSource.MAX_PLAYBACK_SPEED),
            )
            .apply()
    }

    private fun ensureServiceStarted() {
        startService(Intent(this, TranslationService::class.java))
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        filePicker.launch(Intent.createChooser(intent, "Chọn tệp âm thanh hoặc video"))
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun simpleSelection(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun seekListener(
        onChange: (Int) -> Unit = {},
        onStop: (Int) -> Unit = {},
    ) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
            onStop(seekBar?.progress ?: 0)
        }
    }

    companion object {
        private const val FILE_SPEED_STEPS = 20
        private const val KEY_SOURCE_MODE = "lastSourceMode"
        private const val KEY_FILE_PLAYBACK_SPEED = "filePlaybackSpeed"
    }
}
