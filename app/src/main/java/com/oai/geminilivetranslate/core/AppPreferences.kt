package com.oai.geminilivetranslate.core

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = SettingsPolicy.sanitize(AppSettings(
        model = DEFAULT_MODEL,
        targetLanguage = prefs.getString(KEY_TARGET_LANGUAGE, "vi").orEmpty(),
        echoTargetLanguage = prefs.getBoolean(KEY_ECHO_TARGET, false),
        aiVoice = prefs.getBoolean(KEY_AI_VOICE, true),
        aiAudioStreamType = prefs.getString(KEY_AI_AUDIO_STREAM_TYPE, "accessibility").orEmpty(),
        autoDucking = prefs.getBoolean(KEY_AUTO_DUCKING, false),
        duckVolumeFactor = prefs.getFloat(KEY_DUCK_FACTOR, 0.2f),
        muteOriginalInInternal = prefs.getBoolean(KEY_MUTE_INTERNAL, false),
        uiMode = prefs.getString(KEY_UI_MODE, "advanced").orEmpty(),
        performanceProfile = prefs.getString(KEY_PROFILE, "balanced").orEmpty(),
        autoReconnect = prefs.getBoolean(KEY_RECONNECT, true),
        reconnectMaxRetries = prefs.getInt(KEY_RECONNECT_RETRIES, 3),
        qualityMode = prefs.getBoolean(KEY_QUALITY_MODE, false),
        inputBufferMs = prefs.getInt(KEY_INPUT_BUFFER, 800),
        outputJitterTarget = prefs.getInt(KEY_JITTER, 8),
        fileSyncDelayMs = prefs.getInt(KEY_FILE_SYNC_DELAY, 2_000),
        pacingEnabled = prefs.getBoolean(KEY_PACING, true),
        pacingTargetLatencyMs = prefs.getInt(KEY_PACING_LATENCY, 500),
        pacingMaxBuffer = prefs.getInt(KEY_PACING_MAX, 5),
        translatedBufferBytes = prefs.getInt(KEY_AI_BUFFER, 96_000),
        translatedQueueMax = prefs.getInt(KEY_AI_QUEUE, 20),
        ttsSmoothEnabled = prefs.getBoolean(KEY_TTS_SMOOTH, true),
        ttsSmoothTimeoutMs = prefs.getInt(KEY_TTS_TIMEOUT, 2_200),
        ttsSmoothMinChars = prefs.getInt(KEY_TTS_CHARS, 60),
        ttsSmoothMinWords = prefs.getInt(KEY_TTS_WORDS, 8),
        saveAudioEnabled = prefs.getBoolean(KEY_SAVE_AUDIO, false),
        saveAudioMode = prefs.getString(KEY_SAVE_MODE, "translated").orEmpty(),
        exportFormat = prefs.getString(KEY_EXPORT_FORMAT, "srt").orEmpty(),
        logLevel = prefs.getInt(KEY_LOG_LEVEL, 2),
        logToFile = prefs.getBoolean(KEY_LOG_FILE, true),
        logIncludeTranscript = prefs.getBoolean(KEY_LOG_TRANSCRIPT, false),
        originalVolume = prefs.getInt(KEY_ORIGINAL_VOLUME, 50),
        translatedVolume = prefs.getInt(KEY_TRANSLATED_VOLUME, 100),
        micLanguages = loadMicLanguages(),
        micLanguageIndex = prefs.getInt(KEY_MIC_LANGUAGE_INDEX, 0),
    ))

    fun save(settings: AppSettings) {
        val safe = SettingsPolicy.sanitize(settings)
        prefs.edit()
            .remove("model")
            .putString(KEY_TARGET_LANGUAGE, safe.targetLanguage)
            .putBoolean(KEY_ECHO_TARGET, safe.echoTargetLanguage)
            .putBoolean(KEY_AI_VOICE, safe.aiVoice)
            .putString(KEY_AI_AUDIO_STREAM_TYPE, safe.aiAudioStreamType)
            .putBoolean(KEY_AUTO_DUCKING, safe.autoDucking)
            .putFloat(KEY_DUCK_FACTOR, safe.duckVolumeFactor)
            .putBoolean(KEY_MUTE_INTERNAL, safe.muteOriginalInInternal)
            .putString(KEY_UI_MODE, safe.uiMode)
            .putString(KEY_PROFILE, safe.performanceProfile)
            .putBoolean(KEY_RECONNECT, safe.autoReconnect)
            .putInt(KEY_RECONNECT_RETRIES, safe.reconnectMaxRetries)
            .putBoolean(KEY_QUALITY_MODE, safe.qualityMode)
            .putInt(KEY_INPUT_BUFFER, safe.inputBufferMs)
            .putInt(KEY_JITTER, safe.outputJitterTarget)
            .putInt(KEY_FILE_SYNC_DELAY, safe.fileSyncDelayMs)
            .putBoolean(KEY_PACING, safe.pacingEnabled)
            .putInt(KEY_PACING_LATENCY, safe.pacingTargetLatencyMs)
            .putInt(KEY_PACING_MAX, safe.pacingMaxBuffer)
            .putInt(KEY_AI_BUFFER, safe.translatedBufferBytes)
            .putInt(KEY_AI_QUEUE, safe.translatedQueueMax)
            .putBoolean(KEY_TTS_SMOOTH, safe.ttsSmoothEnabled)
            .putInt(KEY_TTS_TIMEOUT, safe.ttsSmoothTimeoutMs)
            .putInt(KEY_TTS_CHARS, safe.ttsSmoothMinChars)
            .putInt(KEY_TTS_WORDS, safe.ttsSmoothMinWords)
            .putBoolean(KEY_SAVE_AUDIO, safe.saveAudioEnabled)
            .putString(KEY_SAVE_MODE, safe.saveAudioMode)
            .putString(KEY_EXPORT_FORMAT, safe.exportFormat)
            .putInt(KEY_LOG_LEVEL, safe.logLevel)
            .putBoolean(KEY_LOG_FILE, safe.logToFile)
            .putBoolean(KEY_LOG_TRANSCRIPT, safe.logIncludeTranscript)
            .putInt(KEY_ORIGINAL_VOLUME, safe.originalVolume)
            .putInt(KEY_TRANSLATED_VOLUME, safe.translatedVolume)
            .putString(KEY_MIC_LANGUAGES, safe.micLanguages.joinToString(","))
            .putInt(KEY_MIC_LANGUAGE_INDEX, safe.micLanguageIndex)
            .apply()
    }

    fun setVolumes(original: Int, translated: Int) {
        val current = load().copy(originalVolume = original, translatedVolume = translated)
        save(current)
    }

    fun setAiVoice(enabled: Boolean) = save(load().copy(aiVoice = enabled))
    fun setAiAudioStreamType(value: String) = save(load().copy(aiAudioStreamType = value))
    fun setAutoDucking(enabled: Boolean) = save(load().copy(autoDucking = enabled))
    fun setTargetLanguage(code: String) = save(load().copy(targetLanguage = code))
    fun setMicLanguages(codes: List<String>, index: Int) = save(
        load().copy(micLanguages = codes, micLanguageIndex = index)
    )

    fun loadProcessingMode(): String = prefs.getString(KEY_PROCESSING_MODE, PROCESSING_MODE_TRANSLATE)
        .orEmpty()
        .takeIf {
            it == PROCESSING_MODE_TRANSLATE ||
                it == PROCESSING_MODE_TRANSCRIBE ||
                it == PROCESSING_MODE_VIDEO_DESCRIPTION
        }
        ?: PROCESSING_MODE_TRANSLATE

    fun setProcessingMode(value: String) {
        val safe = when (value) {
            PROCESSING_MODE_TRANSCRIBE -> PROCESSING_MODE_TRANSCRIBE
            PROCESSING_MODE_VIDEO_DESCRIPTION -> PROCESSING_MODE_VIDEO_DESCRIPTION
            else -> PROCESSING_MODE_TRANSLATE
        }
        prefs.edit().putString(KEY_PROCESSING_MODE, safe).apply()
    }

    fun loadVideoDescriptionMode(): String =
        prefs.getString(KEY_VIDEO_DESCRIPTION_MODE, VIDEO_DESCRIPTION_TIMELINE)
            .orEmpty()
            .takeIf { it == VIDEO_DESCRIPTION_TIMELINE || it == VIDEO_DESCRIPTION_SUMMARY }
            ?: VIDEO_DESCRIPTION_TIMELINE

    fun setVideoDescriptionMode(value: String) {
        val safe = if (value == VIDEO_DESCRIPTION_SUMMARY) {
            VIDEO_DESCRIPTION_SUMMARY
        } else {
            VIDEO_DESCRIPTION_TIMELINE
        }
        prefs.edit().putString(KEY_VIDEO_DESCRIPTION_MODE, safe).apply()
    }

    fun loadSpeakerDiarization(): Boolean = prefs.getBoolean(KEY_SPEAKER_DIARIZATION, false)

    fun setSpeakerDiarization(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAKER_DIARIZATION, enabled).apply()
    }

    fun restoreDefaultsPreservingKeys(): AppSettings = AppSettings().let(SettingsPolicy::sanitize).also(::save)

    fun clear(): Boolean = prefs.edit().clear().commit()

    fun applyProfile(name: String): AppSettings = SettingsPolicy.applyProfile(name, load()).also(::save)

    private fun loadMicLanguages(): List<String> {
        val raw = prefs.getString(KEY_MIC_LANGUAGES, "vi").orEmpty()
        return raw.split(',').mapNotNull(LanguageCatalog::normalize).distinct().ifEmpty { listOf("vi") }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-live-translate-preview"
        const val PREFS_NAME = "gemini_translate_prefs"
        private const val KEY_TARGET_LANGUAGE = "targetLanguage"
        private const val KEY_ECHO_TARGET = "echoTargetLanguage"
        private const val KEY_AI_VOICE = "useAIVoice"
        private const val KEY_AI_AUDIO_STREAM_TYPE = "aiAudioStreamType"
        private const val KEY_AUTO_DUCKING = "autoDucking"
        private const val KEY_DUCK_FACTOR = "duckVolumeFactor"
        private const val KEY_MUTE_INTERNAL = "muteOriginalInInternal"
        private const val KEY_UI_MODE = "uiMode"
        private const val KEY_PROFILE = "performanceProfile"
        private const val KEY_RECONNECT = "autoReconnect"
        private const val KEY_RECONNECT_RETRIES = "reconnectMaxRetries"
        private const val KEY_QUALITY_MODE = "qualityMode"
        private const val KEY_INPUT_BUFFER = "inputBufferMs"
        private const val KEY_JITTER = "outputJitterTarget"
        private const val KEY_FILE_SYNC_DELAY = "fileSyncDelayMs"
        private const val KEY_PACING = "pacingEnable"
        private const val KEY_PACING_LATENCY = "pacingTargetLatency"
        private const val KEY_PACING_MAX = "pacingMaxBuffer"
        private const val KEY_AI_BUFFER = "aiBufferSize"
        private const val KEY_AI_QUEUE = "aiQueueMax"
        private const val KEY_TTS_SMOOTH = "ttsSmoothEnabled"
        private const val KEY_TTS_TIMEOUT = "ttsSmoothTimeoutMs"
        private const val KEY_TTS_CHARS = "ttsSmoothMinChars"
        private const val KEY_TTS_WORDS = "ttsSmoothMinWords"
        private const val KEY_SAVE_AUDIO = "saveAudioEnabled"
        private const val KEY_SAVE_MODE = "saveAudioMode"
        private const val KEY_EXPORT_FORMAT = "exportFormat"
        private const val KEY_LOG_LEVEL = "logLevel"
        private const val KEY_LOG_FILE = "logToFile"
        private const val KEY_LOG_TRANSCRIPT = "logIncludeTranscript"
        private const val KEY_ORIGINAL_VOLUME = "volumeOriginal"
        private const val KEY_TRANSLATED_VOLUME = "volumeTranslate"
        private const val KEY_MIC_LANGUAGES = "micLanguages"
        private const val KEY_MIC_LANGUAGE_INDEX = "micLanguageIndex"
        private const val KEY_PROCESSING_MODE = "processingMode"
        private const val KEY_VIDEO_DESCRIPTION_MODE = "videoDescriptionMode"
        private const val KEY_SPEAKER_DIARIZATION = "speakerDiarization"
        const val PROCESSING_MODE_TRANSLATE = "translate"
        const val PROCESSING_MODE_TRANSCRIBE = "transcribe"
        const val PROCESSING_MODE_VIDEO_DESCRIPTION = "video_description"
        const val VIDEO_DESCRIPTION_TIMELINE = "timeline"
        const val VIDEO_DESCRIPTION_SUMMARY = "summary"
        const val TRANSCRIBE_FILE_MODEL = "gemini-3.5-transcribe"
        const val TRANSCRIBE_LIVE_MODEL = "gemini-3.5-transcribe-live"
        const val SUBTITLE_TRANSLATE_MODEL = "gemini-3.5-flash-lite"
        const val VIDEO_DESCRIPTION_MODEL = "gemini-3.7-flash"
    }
}
