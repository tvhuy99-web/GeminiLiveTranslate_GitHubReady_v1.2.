package com.oai.geminilivetranslate.core

import android.net.Uri

enum class SourceMode(val label: String) {
    FILE("Tệp âm thanh/video"),
    MICROPHONE("Microphone"),
    INTERNAL("Âm thanh nội bộ")
}

data class AppSettings(
    val model: String = "gemini-3.5-live-translate-preview",
    val targetLanguage: String = "vi",
    val echoTargetLanguage: Boolean = false,
    val aiVoice: Boolean = true,
    val aiAudioStreamType: String = "accessibility",
    val autoDucking: Boolean = false,
    val duckVolumeFactor: Float = 0.2f,
    val muteOriginalInInternal: Boolean = false,
    val uiMode: String = "advanced",
    val performanceProfile: String = "balanced",
    val autoReconnect: Boolean = true,
    val reconnectMaxRetries: Int = 3,
    val qualityMode: Boolean = false,
    val inputBufferMs: Int = 800,
    val outputJitterTarget: Int = 8,
    val fileSyncDelayMs: Int = 2_000,
    val pacingEnabled: Boolean = true,
    val pacingTargetLatencyMs: Int = 500,
    val pacingMaxBuffer: Int = 5,
    val translatedBufferBytes: Int = 96_000,
    val translatedQueueMax: Int = 20,
    val ttsSmoothEnabled: Boolean = true,
    val ttsSmoothTimeoutMs: Int = 2_200,
    val ttsSmoothMinChars: Int = 60,
    val ttsSmoothMinWords: Int = 8,
    val saveAudioEnabled: Boolean = false,
    val saveAudioMode: String = "translated",
    val exportFormat: String = "srt",
    val logLevel: Int = 2,
    val logToFile: Boolean = true,
    val logIncludeTranscript: Boolean = false,
    val originalVolume: Int = 50,
    val translatedVolume: Int = 100,
    val micLanguages: List<String> = listOf("vi"),
    val micLanguageIndex: Int = 0,
) : java.io.Serializable

data class SessionUiState(
    val status: String = "Sẵn sàng",
    val health: String = "",
    val running: Boolean = false,
    val paused: Boolean = false,
    val setupComplete: Boolean = false,
    val sourceMode: SourceMode = SourceMode.FILE,
    val selectedUri: Uri? = null,
    val selectedFileName: String? = null,
    val transcript: String = "",
    val progressPercent: Int = 0,
    val canSeek: Boolean = false,
    val aiVoice: Boolean = true,
    val currentLanguage: String = "vi",
    val lastError: String? = null,
    val subtitleTranslationAvailable: Boolean = false,
    val subtitleTranslationInProgress: Boolean = false,
    val subtitleShowingVietnamese: Boolean = false,
)
