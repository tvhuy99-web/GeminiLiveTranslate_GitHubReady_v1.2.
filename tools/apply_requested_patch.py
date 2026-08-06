#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# 1) Persisted setting: aiAudioStreamType.
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/Models.kt",
    '    val aiVoice: Boolean = true,\n    val autoDucking: Boolean = false,',
    '    val aiVoice: Boolean = true,\n'
    '    val aiAudioStreamType: String = "accessibility",\n'
    '    val autoDucking: Boolean = false,',
)

replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/AppPreferences.kt",
    '        aiVoice = prefs.getBoolean(KEY_AI_VOICE, true),\n'
    '        autoDucking = prefs.getBoolean(KEY_AUTO_DUCKING, false),',
    '        aiVoice = prefs.getBoolean(KEY_AI_VOICE, true),\n'
    '        aiAudioStreamType = prefs.getString(KEY_AI_AUDIO_STREAM_TYPE, "accessibility").orEmpty(),\n'
    '        autoDucking = prefs.getBoolean(KEY_AUTO_DUCKING, false),',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/AppPreferences.kt",
    '            .putBoolean(KEY_AI_VOICE, safe.aiVoice)\n'
    '            .putBoolean(KEY_AUTO_DUCKING, safe.autoDucking)',
    '            .putBoolean(KEY_AI_VOICE, safe.aiVoice)\n'
    '            .putString(KEY_AI_AUDIO_STREAM_TYPE, safe.aiAudioStreamType)\n'
    '            .putBoolean(KEY_AUTO_DUCKING, safe.autoDucking)',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/AppPreferences.kt",
    '    fun setAiVoice(enabled: Boolean) = save(load().copy(aiVoice = enabled))\n'
    '    fun setAutoDucking(enabled: Boolean) = save(load().copy(autoDucking = enabled))',
    '    fun setAiVoice(enabled: Boolean) = save(load().copy(aiVoice = enabled))\n'
    '    fun setAiAudioStreamType(value: String) = save(load().copy(aiAudioStreamType = value))\n'
    '    fun setAutoDucking(enabled: Boolean) = save(load().copy(autoDucking = enabled))',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/AppPreferences.kt",
    '        private const val KEY_AI_VOICE = "useAIVoice"\n'
    '        private const val KEY_AUTO_DUCKING = "autoDucking"',
    '        private const val KEY_AI_VOICE = "useAIVoice"\n'
    '        private const val KEY_AI_AUDIO_STREAM_TYPE = "aiAudioStreamType"\n'
    '        private const val KEY_AUTO_DUCKING = "autoDucking"',
)

replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/SettingsPolicy.kt",
    '    val validExportFormats = setOf("srt", "txt")',
    '    val validExportFormats = setOf("srt", "txt")\n'
    '    val validAiAudioStreamTypes = setOf("accessibility", "media", "voice_communication", "assistant")',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/SettingsPolicy.kt",
    '            targetLanguage = target,\n'
    '            duckVolumeFactor = input.duckVolumeFactor.coerceIn(0f, 1f),',
    '            targetLanguage = target,\n'
    '            aiAudioStreamType = input.aiAudioStreamType.takeIf(validAiAudioStreamTypes::contains) ?: "accessibility",\n'
    '            duckVolumeFactor = input.duckVolumeFactor.coerceIn(0f, 1f),',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/SettingsPolicy.kt",
    '        mark("aiVoice", before.aiVoice != after.aiVoice)\n'
    '        mark("autoDucking", before.autoDucking != after.autoDucking)',
    '        mark("aiVoice", before.aiVoice != after.aiVoice)\n'
    '        mark("aiAudioStreamType", before.aiAudioStreamType != after.aiAudioStreamType)\n'
    '        mark("autoDucking", before.autoDucking != after.autoDucking)',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/core/SettingsPolicy.kt",
    '            "translatedBufferBytes", "translatedQueueMax", "outputJitterTarget"\n'
    '        ))',
    '            "translatedBufferBytes", "translatedQueueMax", "outputJitterTarget", "aiAudioStreamType"\n'
    '        ))',
)

# 2) Public recording store. Final WAV files go to Music/GeminiLiveTranslate.
public_store = r'''package com.oai.geminilivetranslate.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/**
 * Records to a short-lived cache file, then publishes the completed WAV to the user's public
 * Music/GeminiLiveTranslate folder. API keys, preferences and diagnostic logs remain private.
 */
class PublicRecordingStore(
    private val context: Context,
    private val logger: SessionLogger,
) {
    data class Pending(
        val tempFile: File,
        val displayName: String,
    )

    fun create(displayName: String): Pending {
        val dir = File(context.cacheDir, "recording-pending").apply {
            mkdirs()
            listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > STALE_MS }?.forEach(File::delete)
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val temp = File(dir, "${UUID.randomUUID()}-$safeName")
        return Pending(temp, safeName)
    }

    fun publish(pending: Pending): Uri {
        check(pending.tempFile.isFile) { "Không tìm thấy tệp ghi tạm" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(pending)
        } else {
            publishLegacy(pending)
        }
    }

    fun discard(pending: Pending?) {
        pending?.tempFile?.delete()
    }

    private fun publishWithMediaStore(pending: Pending): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, pending.displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/GeminiLiveTranslate")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Không tạo được mục MediaStore")
        try {
            resolver.openOutputStream(uri, "w")?.buffered(COPY_BUFFER)?.use { output ->
                pending.tempFile.inputStream().buffered(COPY_BUFFER).use { input -> input.copyTo(output, COPY_BUFFER) }
            } ?: error("Không mở được tệp công khai")
            ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                .also { resolver.update(uri, it, null, null) }
            pending.tempFile.delete()
            logger.log(2, "Recorder", "Đã lưu WAV công khai uri=$uri")
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(pending: Pending): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "GeminiLiveTranslate",
        )
        check(dir.exists() || dir.mkdirs()) { "Không tạo được thư mục ${dir.absolutePath}" }
        val target = uniqueFile(dir, pending.displayName)
        pending.tempFile.inputStream().buffered(COPY_BUFFER).use { input ->
            target.outputStream().buffered(COPY_BUFFER).use { output -> input.copyTo(output, COPY_BUFFER) }
        }
        pending.tempFile.delete()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/wav"), null)
        logger.log(2, "Recorder", "Đã lưu WAV công khai path=${target.absolutePath}")
        return Uri.fromFile(target)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 2
        while (true) {
            val candidate = File(dir, "${base}_$index$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    companion object {
        private const val COPY_BUFFER = 256 * 1024
        private const val STALE_MS = 24L * 60L * 60L * 1_000L
    }
}
'''
write(
    "app/src/main/java/com/oai/geminilivetranslate/core/PublicRecordingStore.kt",
    public_store,
)

# 3) Translation service: stream routing and public recording publication.
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    'import com.oai.geminilivetranslate.core.LanguageCatalog\n'
    'import com.oai.geminilivetranslate.core.SessionLogger',
    'import com.oai.geminilivetranslate.core.LanguageCatalog\n'
    'import com.oai.geminilivetranslate.core.PublicRecordingStore\n'
    'import com.oai.geminilivetranslate.core.SessionLogger',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    '    private lateinit var notificationController: NotificationController\n'
    '    private val subtitles = SubtitleStore()',
    '    private lateinit var notificationController: NotificationController\n'
    '    private lateinit var recordingStore: PublicRecordingStore\n'
    '    private val subtitles = SubtitleStore()',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    '    private var originalWriter: WavWriter? = null\n'
    '    private var translatedWriter: WavWriter? = null\n'
    '    private var mixedWriter: TimelineWavMixer? = null',
    '    private var originalWriter: WavWriter? = null\n'
    '    private var translatedWriter: WavWriter? = null\n'
    '    private var mixedWriter: TimelineWavMixer? = null\n'
    '    private var originalRecording: PublicRecordingStore.Pending? = null\n'
    '    private var translatedRecording: PublicRecordingStore.Pending? = null\n'
    '    private var mixedRecording: PublicRecordingStore.Pending? = null',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    '        logger = SessionLogger(this, preferences)\n'
    '        notificationController = NotificationController(this)\n'
    '        settings = preferences.load()',
    '        logger = SessionLogger(this, preferences)\n'
    '        notificationController = NotificationController(this)\n'
    '        recordingStore = PublicRecordingStore(this, logger)\n'
    '        settings = preferences.load()',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    '    private fun buildAiPlayer(): StreamingPcmPlayer = StreamingPcmPlayer(\n'
    '        sampleRate = 24_000,\n'
    '        bufferBytes = settings.translatedBufferBytes,\n'
    '        queueCapacity = settings.translatedQueueMax,\n'
    '        initialJitterChunks = if (settings.qualityMode) settings.outputJitterTarget else 1,\n'
    '        usage = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,\n'
    '        logger = logger,\n'
    '        diagnosticName = "TranslatedPlayer",\n'
    '    )',
    '    private fun buildAiPlayer(): StreamingPcmPlayer = StreamingPcmPlayer(\n'
    '        sampleRate = 24_000,\n'
    '        bufferBytes = settings.translatedBufferBytes,\n'
    '        queueCapacity = settings.translatedQueueMax,\n'
    '        initialJitterChunks = if (settings.qualityMode) settings.outputJitterTarget else 1,\n'
    '        usage = when (settings.aiAudioStreamType) {\n'
    '            "media" -> AudioAttributes.USAGE_MEDIA\n'
    '            "voice_communication" -> AudioAttributes.USAGE_VOICE_COMMUNICATION\n'
    '            "assistant" -> AudioAttributes.USAGE_ASSISTANT\n'
    '            else -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY\n'
    '        },\n'
    '        logger = logger,\n'
    '        diagnosticName = "TranslatedPlayer-${settings.aiAudioStreamType}",\n'
    '    )',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    '''    private fun setupRecorders() {
        if (!settings.saveAudioEnabled) return
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "GeminiLiveTranslate")
        dir.mkdirs()
        logger.log(2, "Recorder", "Bật ghi audio mode=${settings.saveAudioMode} dir=${dir.absolutePath}")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        when (settings.saveAudioMode) {
            "original" -> originalWriter = WavWriter(File(dir, "original_$stamp.wav"), 16_000)
            "mixed" -> {
                originalWriter = WavWriter(File(dir, "original_$stamp.wav"), 16_000)
                translatedWriter = WavWriter(File(dir, "translated_$stamp.wav"), 24_000)
                mixedWriter = TimelineWavMixer(File(dir, "mixed_$stamp.wav"))
            }
            else -> translatedWriter = WavWriter(File(dir, "translated_$stamp.wav"), 24_000)
        }
    }''',
    '''    private fun setupRecorders() {
        if (!settings.saveAudioEnabled) return
        logger.log(2, "Recorder", "Bật ghi audio mode=${settings.saveAudioMode}; đích công khai Music/GeminiLiveTranslate")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        fun original() {
            originalRecording = recordingStore.create("original_$stamp.wav")
            originalWriter = WavWriter(requireNotNull(originalRecording).tempFile, 16_000)
        }
        fun translated() {
            translatedRecording = recordingStore.create("translated_$stamp.wav")
            translatedWriter = WavWriter(requireNotNull(translatedRecording).tempFile, 24_000)
        }
        when (settings.saveAudioMode) {
            "original" -> original()
            "mixed" -> {
                original()
                translated()
                mixedRecording = recordingStore.create("mixed_$stamp.wav")
                mixedWriter = TimelineWavMixer(requireNotNull(mixedRecording).tempFile)
            }
            else -> translated()
        }
    }''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    '''    private fun closeRecorders() {
        if (originalWriter != null || translatedWriter != null || mixedWriter != null) {
            logger.log(2, "Recorder", "Đóng các tệp ghi audio")
        }
        runCatching { originalWriter?.close() }.onFailure { logger.log(0, "Recorder", "Lỗi đóng WAV gốc", it) }; originalWriter = null
        runCatching { translatedWriter?.close() }.onFailure { logger.log(0, "Recorder", "Lỗi đóng WAV dịch", it) }; translatedWriter = null
        runCatching { mixedWriter?.close() }.onFailure { logger.log(0, "Recorder", "Lỗi đóng WAV trộn", it) }; mixedWriter = null
    }''',
    '''    private fun closeRecorders() {
        if (originalWriter != null || translatedWriter != null || mixedWriter != null) {
            logger.log(2, "Recorder", "Đóng và xuất các tệp ghi ra Music/GeminiLiveTranslate")
        }
        runCatching { originalWriter?.close() }.onFailure { logger.log(0, "Recorder", "Lỗi đóng WAV gốc", it) }
        runCatching { translatedWriter?.close() }.onFailure { logger.log(0, "Recorder", "Lỗi đóng WAV dịch", it) }
        runCatching { mixedWriter?.close() }.onFailure { logger.log(0, "Recorder", "Lỗi đóng WAV trộn", it) }
        originalWriter = null
        translatedWriter = null
        mixedWriter = null

        fun publish(label: String, pending: PublicRecordingStore.Pending?) {
            if (pending == null) return
            runCatching { recordingStore.publish(pending) }
                .onSuccess { uri -> logger.log(2, "Recorder", "Đã xuất WAV $label tới $uri") }
                .onFailure {
                    logger.log(0, "Recorder", "Không xuất được WAV $label", it)
                    recordingStore.discard(pending)
                }
        }
        publish("gốc", originalRecording)
        publish("dịch", translatedRecording)
        publish("trộn", mixedRecording)
        originalRecording = null
        translatedRecording = null
        mixedRecording = null
    }''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    'import android.os.Environment\n',
    '',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    'import java.io.File\n',
    '',
)

# 4) Main screen: Android ACTION_GET_CONTENT picker, legacy storage permission, stream selector.
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '    private var spinnerReady = false\n'
    '    private var micSpinnerReady = false',
    '    private var spinnerReady = false\n'
    '    private var micSpinnerReady = false\n'
    '    private var aiStreamSpinnerReady = false',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '    private var permissionPendingMode: SourceMode? = null\n'
    '    private var stateJob: Job? = null',
    '    private var permissionPendingMode: SourceMode? = null\n'
    '    private var legacyStoragePendingMode: SourceMode? = null\n'
    '    private var stateJob: Job? = null\n'
    '    private val aiStreamValues = listOf("accessibility", "media", "voice_communication", "assistant")\n'
    '    private val aiStreamLabels = listOf(\n'
    '        "Trợ năng - ưu tiên nghe rõ",\n'
    '        "Đa phương tiện / nhạc",\n'
    '        "Giao tiếp bằng giọng nói",\n'
    '        "Trợ lý Android",\n'
    '    )',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName(uri)''',
    '''    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val name = displayName(uri)''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = permissionPendingMode ?: SourceMode.MICROPHONE
        permissionPendingMode = null
        logger.log(if (granted) 2 else 1, "Permission", "Kết quả quyền microphone granted=$granted mode=$mode")
        if (granted) startMode(mode) else toast("Cần quyền Microphone để bắt đầu")
    }

    private val notificationPermission''',
    '''    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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

    private val notificationPermission''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '        selectFileButton.setOnClickListener { filePicker.launch(arrayOf("audio/*", "video/*")) }',
    '        selectFileButton.setOnClickListener { launchFilePicker() }',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''        aiVoiceSwitch.setOnCheckedChangeListener { _, checked -> translationService?.setAiVoice(checked) ?: preferences.setAiVoice(checked) }
        autoDuckingSwitch.setOnCheckedChangeListener''',
    '''        aiAudioStreamSpinner.adapter = ArrayAdapter(
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
        autoDuckingSwitch.setOnCheckedChangeListener''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''        aiVoiceSwitch.isChecked = settings.aiVoice
        autoDuckingSwitch.isChecked = settings.autoDucking''',
    '''        aiVoiceSwitch.isChecked = settings.aiVoice
        aiStreamSpinnerReady = false
        aiAudioStreamSpinner.setSelection(aiStreamValues.indexOf(settings.aiAudioStreamType).coerceAtLeast(0))
        aiAudioStreamSpinner.post { aiStreamSpinnerReady = true }
        autoDuckingSwitch.isChecked = settings.autoDucking''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''    private fun applyUiMode() = with(binding) {
        val simple = preferences.load().uiMode == "simple"
        autoDuckingSwitch.isVisible = !simple
        logButton.isVisible = !simple''',
    '''    private fun applyUiMode() = with(binding) {
        val simple = preferences.load().uiMode == "simple"
        autoDuckingSwitch.isVisible = !simple
        aiAudioStreamLayout.isVisible = !simple && aiVoiceSwitch.isChecked
        logButton.isVisible = !simple''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''    private fun startMode(mode: SourceMode) {
        logger.log(2, "UI", "Yêu cầu bắt đầu source=$mode serviceBound=${translationService != null}")
        val service = translationService''',
    '''    private fun startMode(mode: SourceMode) {
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
        val service = translationService''',
)
replace_once(
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    '''    private fun ensureServiceStarted() {
        startService(Intent(this, TranslationService::class.java))
    }

    private fun displayName''',
    '''    private fun ensureServiceStarted() {
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

    private fun displayName''',
)

replace_once(
    "app/src/main/res/layout/activity_main.xml",
    '''        <Switch
            android:id="@+id/aiVoiceSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:checked="true"
            android:text="Giọng AI" />

        <Switch
            android:id="@+id/autoDuckingSwitch"''',
    '''        <Switch
            android:id="@+id/aiVoiceSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:checked="true"
            android:text="Giọng AI" />

        <LinearLayout
            android:id="@+id/aiAudioStreamLayout"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Luồng phát giọng AI (aiAudioStreamType):"
                android:textSize="15sp" />

            <Spinner
                android:id="@+id/aiAudioStreamSpinner"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>

        <Switch
            android:id="@+id/autoDuckingSwitch"''',
)

replace_once(
    "app/src/main/AndroidManifest.xml",
    '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n',
    '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n'
    '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />\n',
)

replace_once(
    "app/build.gradle.kts",
    '        versionCode = 10201\n        versionName = "1.2.1"',
    '        versionCode = 10202\n        versionName = "1.2.2"',
)
replace_once(
    "tools/verify_project.py",
    '            \'versionName = "1.2.1"\',\n            "versionCode = 10201",',
    '            \'versionName = "1.2.2"\',\n            "versionCode = 10202",',
)
replace_once(
    "tools/verify_project.py",
    '    print("[OK] Version 1.2.1 and stable update-signing configuration")',
    '    print("[OK] Version 1.2.2, public recordings and stable update-signing configuration")',
)
replace_once(
    "tools/verify_project.py",
    '            "logIncludeTranscript: Boolean = false",',
    '            "logIncludeTranscript: Boolean = false",\n'
    '            "aiAudioStreamType",\n'
    '            "PublicRecordingStore",\n'
    '            "MediaStore.Audio.Media.RELATIVE_PATH",',
)

changelog = read("CHANGELOG.md")
if "## 1.2.2" not in changelog:
    header = '''# Changelog

## 1.2.2

### Âm thanh và tệp người dùng

- Bổ sung `aiAudioStreamType` với bốn chế độ: trợ năng, đa phương tiện, giao tiếp giọng nói và trợ lý.
- Đổi bộ chọn nguồn sang `ACTION_GET_CONTENT` cho tệp audio/video.
- WAV hoàn tất được xuất ra thư mục công khai `Music/GeminiLiveTranslate` qua MediaStore.
- Android 8/9 yêu cầu quyền bộ nhớ cũ chỉ khi tính năng ghi WAV được bật.
- API Key, cài đặt và nhật ký chẩn đoán vẫn ở vùng dữ liệu riêng tư.

'''
    if changelog.startswith("# Changelog\n\n"):
        changelog = header + changelog[len("# Changelog\n\n"):]
    else:
        changelog = header + changelog
    write("CHANGELOG.md", changelog)

readme = read("README.md")
readme = readme.replace("# Gemini Live Translate Native v1.2.0", "# Gemini Live Translate Native v1.2.2")
needle = "- AI voice, Android TTS dự phòng, auto-ducking và điều khiển âm lượng."
if needle in readme and "aiAudioStreamType" not in readme:
    readme = readme.replace(
        needle,
        needle + "\n- Chọn luồng phát giọng AI (`aiAudioStreamType`) và lưu WAV công khai trong `Music/GeminiLiveTranslate`.",
    )
write("README.md", readme)

final_workflow = r'''name: Android CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: write

concurrency:
  group: android-ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Test, lint and build updateable debug APK
    runs-on: ubuntu-24.04
    timeout-minutes: 45

    steps:
      - name: Checkout source
        uses: actions/checkout@v7

      - name: Set up Java 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'

      - name: Configure Gradle cache
        uses: gradle/actions/setup-gradle@v6
        with:
          cache-provider: basic
          validate-wrappers: false

      - name: Verify repository and wrapper
        run: |
          python3 tools/check_no_secrets.py
          python3 tools/verify_project.py
          python3 tools/verify_github_ready.py
          chmod +x gradlew
          ./gradlew --version

      - name: Require stable update-signing secrets on main
        if: github.event_name != 'pull_request'
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_UPDATE_KEYSTORE_BASE64 }}
          STORE_PASSWORD: ${{ secrets.ANDROID_UPDATE_KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.ANDROID_UPDATE_KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.ANDROID_UPDATE_KEY_PASSWORD }}
        shell: bash
        run: |
          set -euo pipefail
          for name in KEYSTORE_BASE64 STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
            if [[ -z "${!name}" ]]; then
              echo "Missing stable update-signing secret: $name" >&2
              echo "See docs/UPDATE_SIGNING.md" >&2
              exit 1
            fi
          done

      - name: Restore stable update keystore on main
        if: github.event_name != 'pull_request'
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_UPDATE_KEYSTORE_BASE64 }}
        run: |
          printf '%s' "$KEYSTORE_BASE64" | base64 --decode > update-keystore.jks
          test -s update-keystore.jks

      - name: Unit test and assemble PR debug APK
        if: github.event_name == 'pull_request'
        run: ./gradlew --no-daemon --stacktrace clean testDebugUnitTest assembleDebug

      - name: Unit test and assemble updateable debug APK
        if: github.event_name != 'pull_request'
        env:
          UPDATE_STORE_FILE: ${{ github.workspace }}/update-keystore.jks
          UPDATE_STORE_PASSWORD: ${{ secrets.ANDROID_UPDATE_KEYSTORE_PASSWORD }}
          UPDATE_KEY_ALIAS: ${{ secrets.ANDROID_UPDATE_KEY_ALIAS }}
          UPDATE_KEY_PASSWORD: ${{ secrets.ANDROID_UPDATE_KEY_PASSWORD }}
        run: ./gradlew --no-daemon --stacktrace clean testDebugUnitTest assembleDebug

      - name: Verify APK integrity, package, version and signature
        shell: bash
        run: |
          set -euo pipefail
          APK=app/build/outputs/apk/debug/app-debug.apk
          python3 tools/verify_apk.py "$APK"
          BUILD_TOOLS="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
          "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK" | tee apksigner.txt
          "$BUILD_TOOLS/aapt" dump badging "$APK" | tee badging.txt
          grep -q "package: name='com.oai.geminilivetranslate.debug' versionCode='10202' versionName='1.2.2-debug'" badging.txt

      - name: Enforce stable update certificate on main
        if: github.event_name != 'pull_request'
        env:
          EXPECTED_CERT_SHA256: 46e4178b4f1b8ca9a0b480db261ed31ca53dbc2a0a62225d3a97a0ecf2cb034b
        shell: bash
        run: |
          set -euo pipefail
          ACTUAL_CERT="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p; s/^V2 Signer: certificate SHA-256 digest: //p' apksigner.txt | head -n1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
          test "$ACTUAL_CERT" = "$EXPECTED_CERT_SHA256"

      - name: Run strict Android lint
        id: lint
        continue-on-error: true
        run: ./gradlew --no-daemon --stacktrace lintDebug

      - name: Print and enforce lint result
        if: steps.lint.outcome == 'failure'
        shell: bash
        run: |
          REPORT=app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
          if [[ -f "$REPORT" ]]; then
            cat "$REPORT"
          else
            echo "Lint failed but text report was not found." >&2
          fi
          exit 1

      - name: Prepare rolling release files
        if: github.event_name != 'pull_request'
        run: |
          mkdir -p out
          cp app/build/outputs/apk/debug/app-debug.apk out/GeminiLiveTranslate-debug-latest.apk
          (cd out && sha256sum GeminiLiveTranslate-debug-latest.apk > GeminiLiveTranslate-debug-latest.apk.sha256)
          printf '%s\n' 'versionName=1.2.2-debug' 'versionCode=10202' 'applicationId=com.oai.geminilivetranslate.debug' > out/VERSION.txt

      - name: Publish rolling debug GitHub Release
        if: github.event_name != 'pull_request'
        env:
          GH_TOKEN: ${{ github.token }}
        shell: bash
        run: |
          set -euo pipefail
          TAG="debug-latest"
          TITLE="Gemini Live Translate 1.2.2 Debug (latest)"
          NOTES="Verified updateable debug build from commit ${GITHUB_SHA}. Adds aiAudioStreamType, ACTION_GET_CONTENT and public WAV output. Unit tests, APK integrity, stable signature and Android Lint passed."

          if gh release view "$TAG" >/dev/null 2>&1; then
            gh release edit "$TAG" --title "$TITLE" --notes "$NOTES" --prerelease
            gh release view "$TAG" --json assets --jq '.assets[].name' | while IFS= read -r asset; do
              [[ -z "$asset" ]] || gh release delete-asset "$TAG" "$asset" --yes
            done
            gh release upload "$TAG" out/* --clobber
          else
            gh release create "$TAG" out/* \
              --target "$GITHUB_SHA" \
              --title "$TITLE" \
              --notes "$NOTES" \
              --prerelease
          fi

      - name: Remove update keystore from runner
        if: always()
        run: rm -f update-keystore.jks
'''
write(".github/workflows/android-ci.yml", final_workflow)

print("REQUESTED_PATCH_APPLIED")
