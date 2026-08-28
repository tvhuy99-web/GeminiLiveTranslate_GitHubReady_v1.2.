package com.oai.geminilivetranslate.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import com.oai.geminilivetranslate.audio.AudioSource
import com.oai.geminilivetranslate.audio.FileAudioSource
import com.oai.geminilivetranslate.audio.InternalAudioSource
import com.oai.geminilivetranslate.audio.MicAudioSource
import com.oai.geminilivetranslate.audio.RobustTtsEngine
import com.oai.geminilivetranslate.audio.StreamingPcmPlayer
import com.oai.geminilivetranslate.audio.VideoAudioExtractor
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.AppSettings
import com.oai.geminilivetranslate.core.DiagnosticContext
import com.oai.geminilivetranslate.core.LanguageCatalog
import com.oai.geminilivetranslate.core.HistorySession
import com.oai.geminilivetranslate.core.PublicRecordingStore
import com.oai.geminilivetranslate.core.SessionHistoryStore
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SessionUiState
import com.oai.geminilivetranslate.core.SettingsPolicy
import com.oai.geminilivetranslate.core.SrtParser
import com.oai.geminilivetranslate.core.SourceMode
import com.oai.geminilivetranslate.core.SubtitleStore
import com.oai.geminilivetranslate.core.TimelineWavMixer
import com.oai.geminilivetranslate.core.WavWriter
import com.oai.geminilivetranslate.network.GeminiFileTranscribeClient
import com.oai.geminilivetranslate.network.GeminiLiveClient
import com.oai.geminilivetranslate.network.GeminiVideoDescriptionClient
import com.oai.geminilivetranslate.network.SubtitleTranslationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class TranslationService : LifecycleService() {
    inner class LocalBinder : Binder() { fun getService(): TranslationService = this@TranslationService }

    private sealed class InputFrame(open val epoch: Long) {
        data class Audio(val data: ByteArray, override val epoch: Long) : InputFrame(epoch)
        data class StreamEnd(override val epoch: Long) : InputFrame(epoch)
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private lateinit var preferences: AppPreferences
    private lateinit var keyStore: ApiKeyStore
    private lateinit var logger: SessionLogger
    private lateinit var notificationController: NotificationController
    private lateinit var recordingStore: PublicRecordingStore
    private lateinit var historyStore: SessionHistoryStore
    private val subtitles = SubtitleStore()
    private val vietnameseSubtitles = SubtitleStore()

    @Volatile private var settings = AppSettings()
    @Volatile private var client: GeminiLiveClient? = null
    @Volatile private var source: AudioSource? = null
    private var sourceJob: Job? = null
    private var subtitleTranslationJob: Job? = null
    private var historySaveJob: Job? = null
    private var currentHistorySession: HistorySession? = null
    private var reconnectJob: Job? = null
    private var healthJob: Job? = null
    private var fileFinishFallbackJob: Job? = null
    private var transcribeRotationJob: Job? = null
    private var originalQueueJob: Job? = null
    private var originalQueue: Channel<ByteArray>? = null
    private var inputSendJob: Job? = null
    private var inputSendQueue: LinkedBlockingDeque<InputFrame>? = null
    private var duckRestoreJob: Job? = null
    private var ttsFlushJob: Job? = null

    private var aiPlayer: StreamingPcmPlayer? = null
    private var originalPlayer: StreamingPcmPlayer? = null
    private var mediaProjection: MediaProjection? = null
    private var ttsEngine: RobustTtsEngine? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsInitializing = false
    private val ttsBuffer = StringBuilder()
    @Volatile private var filePlaybackSpeed = 1f

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionGeneration = 0L
    private val inputEpoch = AtomicLong(0L)
    private var reconnectAttempts = 0
    @Volatile private var sessionResumptionHandle: String? = null
    private val resumedConnections = AtomicLong(0L)
    private val goAwayCount = AtomicLong(0L)
    private val droppedInputChunks = AtomicLong(0L)
    private val lastLoggedDroppedInputChunks = AtomicLong(0L)
    private val subtitleCallbackEvents = AtomicLong(0L)
    private val subtitleAppliedEvents = AtomicLong(0L)
    private val subtitleFilteredEvents = AtomicLong(0L)
    private var activeInputQueueCapacity = 0
    private var lastBackpressureEvents = 0L
    private var setupStartedAt = 0L
    private var sessionId = ""
    private var sourceStarted = false
    private var fileInputEnded = false
    private var lastRawTranscript = ""
    @Volatile private var transcribePlainText = ""
    @Volatile private var liveCommittedTranscript = ""
    @Volatile private var liveInterimTranscript = ""
    @Volatile private var videoSummaryText = ""
    private var liveInterimEvents = 0L
    private var liveFinalEvents = 0L
    private var selectedUri: Uri? = null
    private var selectedFileName: String? = null
    private var currentMode = SourceMode.FILE
    @Volatile private var processingMode = AppPreferences.PROCESSING_MODE_TRANSLATE
    @Volatile private var videoDescriptionMode = AppPreferences.VIDEO_DESCRIPTION_TIMELINE
    @Volatile private var speakerDiarization = false
    private var sessionStartedAt = 0L
    private var totalInputBytes = 0L
    private var totalOutputBytes = 0L
    private var savedMediaVolume: Int? = null

    private val inputLock = Any()
    private val inputAccumulator = ByteArrayOutputStream()
    private var originalWriter: WavWriter? = null
    private var translatedWriter: WavWriter? = null
    private var mixedWriter: TimelineWavMixer? = null
    private var originalRecording: PublicRecordingStore.Pending? = null
    private var translatedRecording: PublicRecordingStore.Pending? = null
    private var mixedRecording: PublicRecordingStore.Pending? = null
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        keyStore = ApiKeyStore(this)
        logger = SessionLogger(this, preferences)
        notificationController = NotificationController(this)
        recordingStore = PublicRecordingStore(this, logger)
        historyStore = SessionHistoryStore(this)
        settings = preferences.load()
        processingMode = preferences.loadProcessingMode()
        videoDescriptionMode = preferences.loadVideoDescriptionMode()
        speakerDiarization = preferences.loadSpeakerDiarization()
        _state.update {
            it.copy(
                aiVoice = settings.aiVoice,
                currentLanguage = settings.targetLanguage,
                sourceMode = currentMode,
                videoDescriptionMode = videoDescriptionMode,
            )
        }
        if (!settings.aiVoice) ensureTtsInitialized()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopTranslation()
            ACTION_APPLY_SETTINGS -> {
                applySettingsFromPreferences()
                if (!_state.value.running) stopSelf(startId)
            }
            ACTION_REFRESH_API_KEY -> {
                refreshConnectionWithSelectedApiKey()
                if (!_state.value.running) stopSelf(startId)
            }
        }
        super.onStartCommand(intent, flags, startId)
        return Service.START_NOT_STICKY
    }

    fun setSourceMode(mode: SourceMode) {
        if (_state.value.running) return
        if (currentMode != mode) saveCurrentHistoryNow("source-mode-change")
        currentMode = mode
        _state.update { it.copy(sourceMode = mode) }
    }

    fun setProcessingMode(value: String) {
        if (_state.value.running) return
        processingMode = when (value) {
            AppPreferences.PROCESSING_MODE_TRANSCRIBE -> AppPreferences.PROCESSING_MODE_TRANSCRIBE
            AppPreferences.PROCESSING_MODE_VIDEO_DESCRIPTION -> AppPreferences.PROCESSING_MODE_VIDEO_DESCRIPTION
            else -> AppPreferences.PROCESSING_MODE_TRANSLATE
        }
        preferences.setProcessingMode(processingMode)
    }

    fun setVideoDescriptionMode(value: String) {
        if (_state.value.running) return
        videoDescriptionMode = if (value == AppPreferences.VIDEO_DESCRIPTION_SUMMARY) {
            AppPreferences.VIDEO_DESCRIPTION_SUMMARY
        } else {
            AppPreferences.VIDEO_DESCRIPTION_TIMELINE
        }
        preferences.setVideoDescriptionMode(videoDescriptionMode)
        _state.update { it.copy(videoDescriptionMode = videoDescriptionMode) }
    }

    fun setSpeakerDiarization(enabled: Boolean) {
        if (_state.value.running) return
        speakerDiarization = enabled
        preferences.setSpeakerDiarization(enabled)
    }

    fun setSelectedFile(uri: Uri, name: String?) {
        if (_state.value.running && currentMode == SourceMode.FILE) {
            stopTranslation("Đã dừng do đổi tệp")
        } else {
            saveCurrentHistoryNow("before-file-change")
        }
        selectedUri = uri
        selectedFileName = name ?: uri.lastPathSegment
        _state.update { it.copy(selectedUri = uri, selectedFileName = selectedFileName) }
        beginHistorySession(SourceMode.FILE, "file-selected")
    }

    fun startTranslation(
        mode: SourceMode = currentMode,
        projectionResultCode: Int? = null,
        projectionData: Intent? = null,
    ) {
        if (_state.value.running) return
        stopping.set(false)
        settings = preferences.load()
        processingMode = preferences.loadProcessingMode()
        videoDescriptionMode = preferences.loadVideoDescriptionMode()
        speakerDiarization = preferences.loadSpeakerDiarization()
        currentMode = mode
        val apiKey = keyStore.load().selected
        if (apiKey.isNullOrBlank()) {
            updateError("Chưa có API Key")
            return
        }
        if (mode == SourceMode.FILE && selectedUri == null) {
            updateError(if (isVideoDescriptionMode()) "Chưa chọn video" else "Chưa chọn tệp âm thanh/video")
            return
        }
        if (isVideoDescriptionMode() && mode != SourceMode.FILE) {
            updateError("Mô tả video chỉ hỗ trợ tệp video")
            return
        }
        if (mode == SourceMode.INTERNAL && (projectionResultCode == null || projectionData == null)) {
            updateError("Chưa cấp quyền thu âm thanh nội bộ")
            return
        }
        val projectionCode = projectionResultCode
        val projectionIntent = projectionData

        if (mode == SourceMode.MICROPHONE || mode == SourceMode.INTERNAL) {
            beginHistorySession(mode, "recording-start")
        } else {
            val currentHistory = currentHistorySession
            val selected = selectedUri?.toString()
            if (
                currentHistory == null ||
                currentHistory.sourceMode != SourceMode.FILE.name ||
                currentHistory.mediaUri != selected
            ) {
                beginHistorySession(SourceMode.FILE, "file-start")
            } else {
                currentHistorySession = currentHistory.copy(
                    processingMode = processingMode,
                    videoDescriptionMode = videoDescriptionMode,
                    speakerDiarization = speakerDiarization,
                )
            }
        }

        resetSessionState()
        sessionStartedAt = SystemClock.elapsedRealtime()
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8)
        DiagnosticContext.clearSession()
        DiagnosticContext.updateAll(mapOf(
            "session.id" to sessionId,
            "session.source" to mode.name,
            "session.model" to activeModelName(),
            "session.processingMode" to processingMode,
            "session.videoDescriptionMode" to videoDescriptionMode,
            "session.speakerDiarization" to speakerDiarization,
            "session.targetLanguage" to settings.targetLanguage,
            "session.filePlaybackSpeed" to filePlaybackSpeed,
            "session.startedAt" to Date().toString(),
        ))
        subtitles.reset()
        val initialState = _state.value.copy(
            status = when {
                isVideoDescriptionMode() && isVideoDescriptionSummary() -> "Đang khởi động mô tả tổng hợp..."
                isVideoDescriptionMode() -> "Đang khởi động mô tả theo thời gian..."
                isTranscribeMode() -> "Đang khởi động chép lời..."
                else -> "Đang khởi động phiên dịch..."
            },
            health = "",
            running = true,
            paused = false,
            setupComplete = false,
            sourceMode = mode,
            selectedUri = selectedUri,
            selectedFileName = selectedFileName,
            transcript = "",
            progressPercent = 0,
            canSeek = mode == SourceMode.FILE && !isTranscribeMode() && !isVideoDescriptionMode(),
            aiVoice = settings.aiVoice && !isTranscribeMode() && !isVideoDescriptionMode(),
            currentLanguage = settings.targetLanguage,
            lastError = null,
            subtitleTranslationAvailable = false,
            subtitleTranslationInProgress = false,
            subtitleShowingVietnamese = false,
            videoDescriptionMode = videoDescriptionMode,
        )
        _state.value = initialState
        notificationController.start(this, initialState)

        if (isVideoDescriptionMode()) {
            runCatching { acquireWakeLock() }.onFailure {
                logger.log(0, "VideoDescription", "Không tạo được wake lock", it)
            }
            startVideoDescription(apiKey)
            return
        }

        if (isTranscribeMode() && mode == SourceMode.FILE) {
            runCatching { acquireWakeLock() }.onFailure {
                logger.log(0, "TranscribeFile", "Không tạo được wake lock", it)
            }
            startFileTranscription(apiKey)
            return
        }

        setupInputSender()

        if (mode == SourceMode.INTERNAL) {
            val projection = runCatching {
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(requireNotNull(projectionCode), requireNotNull(projectionIntent))
            }.getOrElse {
                logger.log(0, "MediaProjection", "Không tạo được MediaProjection", it)
                null
            }
            if (projection == null) {
                stopTranslation("Không tạo được MediaProjection")
                return
            }
            mediaProjection = projection
        }

        runCatching {
            setupPlayers()
            setupRecorders()
            acquireWakeLock()
            applyInternalMuteIfNeeded()
        }.onFailure {
            logger.log(0, "Session", "Không khởi tạo được audio pipeline", it)
            stopTranslation("Không khởi tạo được audio: ${it.message}")
            return
        }
        updateState { it.copy(status = "Đang kết nối Gemini Live API...") }
        logger.log(
            2,
            "Session",
            "Bắt đầu session=$sessionId nguồn=$mode model=${activeModelName()} processing=$processingMode đích=${settings.targetLanguage} profile=${settings.performanceProfile} queue=${settings.pacingMaxBuffer} quality=${settings.qualityMode} fileSpeed=${formatFileSpeed()}x",
        )
        startHealthMonitor()
        connectGemini(apiKey)
    }

    fun stopTranslation(message: String = if (isTranscribeMode()) "Đã dừng chép lời" else "Đã dừng dịch") {
        if (!stopping.compareAndSet(false, true)) return
        val hadActiveSession = _state.value.running || sessionId.isNotBlank()
        finalizeLiveTranscriptionFallback("stop")
        historySaveJob?.cancel()
        historySaveJob = null
        saveCurrentHistoryNow("stop")
        connectionGeneration++
        inputEpoch.incrementAndGet()
        reconnectJob?.cancel(); reconnectJob = null
        fileFinishFallbackJob?.cancel(); fileFinishFallbackJob = null
        transcribeRotationJob?.cancel(); transcribeRotationJob = null
        healthJob?.cancel(); healthJob = null
        source?.stop(); source = null
        sourceJob?.cancel(); sourceJob = null
        sourceStarted = false
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        inputSendJob?.cancel(); inputSendJob = null
        inputSendQueue?.clear(); inputSendQueue = null
        client?.close(false); client = null
        closeInputAccumulator(send = false)
        originalQueue?.close(); originalQueue = null
        originalQueueJob?.cancel(); originalQueueJob = null
        aiPlayer?.stop(); aiPlayer = null
        originalPlayer?.stop(); originalPlayer = null
        ttsFlushJob?.cancel(); ttsFlushJob = null
        synchronized(ttsBuffer) { ttsBuffer.clear() }
        ttsEngine?.stop()
        duckRestoreJob?.cancel(); duckRestoreJob = null
        restoreSystemMediaVolume()
        closeRecorders()
        releaseWakeLock()
        sessionResumptionHandle = null
        val final = _state.value.copy(
            status = message,
            health = "",
            running = false,
            paused = false,
            setupComplete = false,
            progressPercent = if (fileInputEnded) 100 else _state.value.progressPercent,
        )
        _state.value = final
        notificationController.cancel()
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        stopSelf()
        val durationMs = if (sessionStartedAt > 0L && sessionId.isNotBlank()) elapsedMs() else 0L
        DiagnosticContext.updateAll(mapOf(
            "session.running" to false,
            "session.stopReason" to message,
            "session.durationMs" to durationMs,
            "session.inputBytes" to totalInputBytes,
            "session.outputBytes" to totalOutputBytes,
            "session.droppedInputChunks" to droppedInputChunks.get(),
            "session.resumedConnections" to resumedConnections.get(),
            "session.goAwayCount" to goAwayCount.get(),
            "session.filePlaybackSpeed" to filePlaybackSpeed,
        ))
        if (hadActiveSession) {
            logger.log(2, "Session", "$message; session=$sessionId durationMs=$durationMs inputBytes=$totalInputBytes outputBytes=$totalOutputBytes drop=${droppedInputChunks.get()} resume=${resumedConnections.get()} goAway=${goAwayCount.get()} subtitleCallbacks=${subtitleCallbackEvents.get()} subtitleApplied=${subtitleAppliedEvents.get()} subtitleFiltered=${subtitleFilteredEvents.get()} transcriptChars=${_state.value.transcript.length} fileSpeed=${formatFileSpeed()}x")
        } else {
            logger.log(2, "Service", "$message khi không có phiên đang chạy")
        }
        sessionStartedAt = 0L
        sessionId = ""
        stopping.set(false)
    }

    fun pause() {
        if (!_state.value.running || _state.value.paused) return
        if (isTranscribeMode() && currentMode != SourceMode.FILE) {
            finalizeLiveTranscriptionFallback("reconnect")
        }
        source?.pause()
        aiPlayer?.pause()
        originalPlayer?.pause()
        ttsEngine?.stop()
        updateState { it.copy(paused = true, status = "Đã tạm dừng") }
        logger.log(2, "Session", "Tạm dừng session=$sessionId source=$currentMode")
    }

    fun resume() {
        if (!_state.value.running || !_state.value.paused) return
        if (!_state.value.setupComplete) {
            updateState { it.copy(status = "Đang chờ Gemini kết nối lại...") }
            return
        }
        source?.resume()
        aiPlayer?.resume()
        originalPlayer?.resume()
        updateState { it.copy(paused = false, status = if (isTranscribeMode()) "Đang chép lời..." else "Đang dịch") }
        logger.log(2, "Session", "Tiếp tục session=$sessionId source=$currentMode fileSpeed=${formatFileSpeed()}x")
    }

    fun togglePause() = if (_state.value.paused) resume() else pause()

    fun seekBy(deltaMs: Long) {
        val audioSource = source ?: return
        if (!audioSource.supportsSeek || !_state.value.running) return
        source?.pause()
        logger.log(2, "FileAudio", "Yêu cầu seek deltaMs=$deltaMs")
        audioSource.seekBy(deltaMs)
        resetAfterSeek()
    }

    fun seekToPercent(percent: Int) {
        val audioSource = source ?: return
        if (!audioSource.supportsSeek || !_state.value.running) return
        source?.pause()
        logger.log(2, "FileAudio", "Yêu cầu seek percent=${percent.coerceIn(0, 100)}")
        audioSource.seekToPercent(percent)
        resetAfterSeek()
    }

    fun setVolumes(original: Int, translated: Int) {
        preferences.setVolumes(original, translated)
        settings = settings.copy(originalVolume = original.coerceIn(0, 100), translatedVolume = translated.coerceIn(0, 100))
        originalPlayer?.setVolume(settings.originalVolume)
        aiPlayer?.setVolume(settings.translatedVolume)
    }

    fun setFilePlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(FileAudioSource.MIN_PLAYBACK_SPEED, FileAudioSource.MAX_PLAYBACK_SPEED)
        if (kotlin.math.abs(filePlaybackSpeed - safe) < 0.001f) return
        filePlaybackSpeed = safe
        (source as? FileAudioSource)?.setPlaybackSpeed(safe)
        if (currentMode == SourceMode.FILE) {
            originalPlayer?.setPlaybackSpeed(safe)
            aiPlayer?.setPlaybackSpeed(safe)
        }
        DiagnosticContext.put("session.filePlaybackSpeed", safe)
        logger.log(2, "PlaybackSpeed", "Đổi tốc độ File speed=${formatFileSpeed()}x running=${_state.value.running} mode=$currentMode")
    }

    fun currentFilePlaybackSpeed(): Float = filePlaybackSpeed

    fun selectedMediaUri(): Uri? = selectedUri

    fun selectedMediaName(): String? = selectedFileName

    fun currentHistorySessionId(): String? = currentHistorySession?.id

    fun setAiVoice(enabled: Boolean) {
        preferences.setAiVoice(enabled)
        settings = settings.copy(aiVoice = enabled)
        if (_state.value.running) {
            if (enabled && aiPlayer == null) {
                aiPlayer = buildAiPlayer().also {
                    it.start()
                    it.setVolume(settings.translatedVolume)
                    if (currentMode == SourceMode.FILE) it.setPlaybackSpeed(filePlaybackSpeed)
                }
            } else if (!enabled) {
                aiPlayer?.stop(); aiPlayer = null
            }
        }
        if (enabled) {
            ttsEngine?.stop()
            synchronized(ttsBuffer) { ttsBuffer.clear() }
        } else {
            ensureTtsInitialized()
        }
        updateState { it.copy(aiVoice = enabled) }
    }

    fun setAutoDucking(enabled: Boolean) {
        preferences.setAutoDucking(enabled)
        settings = settings.copy(autoDucking = enabled)
        if (!enabled) restoreDucking()
    }

    private fun applySettingsFromPreferences() {
        val before = settings
        val persistedAfter = preferences.load()
        val diff = SettingsPolicy.diff(before, persistedAfter)
        val running = _state.value.running
        val activeAfter = if (running) {
            SettingsPolicy.activeSessionSettings(before, persistedAfter)
        } else {
            persistedAfter
        }
        settings = activeAfter
        if (!activeAfter.aiVoice) ensureTtsInitialized()
        DiagnosticContext.updateAll(mapOf(
            "settings.model" to activeAfter.model,
            "settings.targetLanguage" to activeAfter.targetLanguage,
            "settings.profile" to persistedAfter.performanceProfile,
            "settings.logLevel" to persistedAfter.logLevel,
            "settings.logToFile" to persistedAfter.logToFile,
            "settings.pendingNextSession" to if (running) diff.nextSession.joinToString() else "",
        ))
        if (diff.isEmpty) return
        logger.log(2, "Settings", "Áp dụng thay đổi changed=${diff.changed.joinToString()} immediate=${diff.immediate.joinToString()} reconnect=${diff.reconnect.joinToString()} playback=${diff.playbackRebuild.joinToString()} nextSession=${diff.nextSession.joinToString()}")

        originalPlayer?.setVolume(activeAfter.originalVolume)
        aiPlayer?.setVolume(activeAfter.translatedVolume)
        if (!activeAfter.autoDucking) restoreDucking()
        if (currentMode == SourceMode.INTERNAL && before.muteOriginalInInternal != activeAfter.muteOriginalInInternal) {
            if (activeAfter.muteOriginalInInternal) lowerSystemMediaVolume(0f) else restoreSystemMediaVolume()
        }

        if (running && (before.aiVoice != activeAfter.aiVoice || diff.requiresPlaybackRebuild)) {
            aiPlayer?.stop()
            aiPlayer = null
            if (activeAfter.aiVoice) {
                runCatching {
                    aiPlayer = buildAiPlayer().also { player ->
                        player.start()
                        player.setVolume(activeAfter.translatedVolume)
                        if (currentMode == SourceMode.FILE) player.setPlaybackSpeed(filePlaybackSpeed)
                        if (_state.value.paused) player.pause()
                    }
                }.onFailure { logger.log(0, "AudioPlayer", "Không tạo lại được bộ phát giọng dịch sau khi đổi cài đặt", it) }
            }
            logger.log(2, "AudioPlayer", "Đã tạo lại bộ phát aiVoice=${activeAfter.aiVoice} buffer=${activeAfter.translatedBufferBytes} queue=${activeAfter.translatedQueueMax} jitter=${activeAfter.outputJitterTarget}")
        }

        if (activeAfter.aiVoice) {
            ttsEngine?.stop()
            synchronized(ttsBuffer) { ttsBuffer.clear() }
        }

        updateState { state ->
            state.copy(aiVoice = activeAfter.aiVoice, currentLanguage = activeAfter.targetLanguage)
        }

        if (running && diff.requiresReconnect) {
            logger.log(1, "Settings", "Cấu hình Gemini thay đổi; tạo phiên mới và xóa audio đang chờ để tránh trộn ngữ cảnh")
            source?.pause()
            clearPendingInputForFreshSession()
            updateState { it.copy(setupComplete = false, status = "Đang áp dụng model/ngôn ngữ mới...") }
            connectGemini(keyStore.load().selected.orEmpty())
        }
        if (running && diff.nextSession.isNotEmpty()) {
            logger.log(1, "Settings", "Đã lưu cho phiên tiếp theo: ${diff.nextSession.joinToString()}; phiên hiện tại giữ queue=$activeInputQueueCapacity quality=${activeAfter.qualityMode}")
        }
    }

    private fun refreshConnectionWithSelectedApiKey() {
        if (!_state.value.running) return
        val selectedKey = keyStore.load().selected
        if (selectedKey.isNullOrBlank()) {
            stopTranslation("API Key đã bị xóa; phiên dịch đã dừng")
            return
        }
        logger.log(1, "ApiKey", "API Key đang dùng đã thay đổi; tạo kết nối Gemini mới")
        source?.pause()
        clearPendingInputForFreshSession()
        updateState { it.copy(setupComplete = false, status = "Đang áp dụng API Key mới...") }
        connectGemini(selectedKey)
    }

    fun switchToNextMicLanguage(): String {
        val loaded = preferences.load()
        val languages = loaded.micLanguages.ifEmpty { listOf("vi") }
        val next = (loaded.micLanguageIndex + 1) % languages.size
        val code = languages[next]
        preferences.setMicLanguages(languages, next)
        preferences.setTargetLanguage(code)
        settings = settings.copy(targetLanguage = code, micLanguageIndex = next, micLanguages = languages)
        updateState { it.copy(currentLanguage = code, status = "Đang chuyển sang ${LanguageCatalog.displayName(code)}") }
        if (_state.value.running && currentMode == SourceMode.MICROPHONE) {
            source?.pause()
            clearPendingInputForFreshSession()
            connectGemini(keyStore.load().selected.orEmpty())
        }
        return code
    }

    fun subtitleText(format: String = preferences.load().exportFormat): String {
        val useVietnamese = _state.value.subtitleTranslationAvailable &&
            _state.value.subtitleShowingVietnamese
        val store = if (useVietnamese) vietnameseSubtitles else subtitles
        return if (format == "txt") {
            if (useVietnamese) {
                store.plainText()
            } else {
                transcribePlainText.takeIf { isTranscribeMode() && it.isNotBlank() }
                    ?: store.plainText()
            }
        } else {
            store.srtText()
        }
    }

    fun translateSubtitlesToVietnamese() {
        if (!isTranscribeMode()) {
            logger.log(1, "SubtitleTranslate", "Bỏ yêu cầu dịch vì processingMode=$processingMode")
            return
        }
        if (_state.value.running) {
            logger.log(1, "SubtitleTranslate", "Bỏ yêu cầu dịch vì phiên chép lời vẫn đang chạy")
            updateState { it.copy(status = "Hãy chờ chép lời hoàn tất trước khi dịch phụ đề") }
            return
        }
        if (_state.value.subtitleTranslationInProgress) return

        val sourceCues = subtitles.snapshot()
        if (sourceCues.isEmpty()) {
            logger.log(1, "SubtitleTranslate", "Không có cue nguồn để dịch")
            updateState { it.copy(status = "Chưa có phụ đề để dịch") }
            return
        }
        if (_state.value.subtitleTranslationAvailable) {
            showVietnameseSubtitles(true)
            return
        }

        val apiKey = keyStore.load().selected
        if (apiKey.isNullOrBlank()) {
            logger.log(1, "SubtitleTranslate", "Không thể dịch vì chưa có API Key")
            updateState { it.copy(status = "Chưa có API Key để dịch phụ đề") }
            return
        }

        val sourceSignature = subtitleSignature(sourceCues)
        val sourceChars = sourceCues.sumOf { it.text.length }
        val startedAt = SystemClock.elapsedRealtime()
        logger.log(
            2,
            "SubtitleTranslate",
            "Bắt đầu tác vụ UI model=${AppPreferences.SUBTITLE_TRANSLATE_MODEL} cues=${sourceCues.size} chars=$sourceChars signature=$sourceSignature",
        )
        DiagnosticContext.updateAll(
            mapOf(
                "subtitleTranslate.model" to AppPreferences.SUBTITLE_TRANSLATE_MODEL,
                "subtitleTranslate.cues" to sourceCues.size,
                "subtitleTranslate.sourceChars" to sourceChars,
                "subtitleTranslate.sourceSignature" to sourceSignature,
                "subtitleTranslate.running" to true,
            )
        )
        updateState {
            it.copy(
                status = "Đang dịch phụ đề sang tiếng Việt...",
                subtitleTranslationInProgress = true,
                subtitleShowingVietnamese = false,
            )
        }

        subtitleTranslationJob?.cancel()
        subtitleTranslationJob = serviceScope.launch(Dispatchers.IO) {
            val client = SubtitleTranslationClient(
                apiKey = apiKey,
                logger = logger,
                includeTranscriptInLogs = settings.logIncludeTranscript,
            )
            try {
                val result = client.translate(sourceCues)
                val currentCues = subtitles.snapshot()
                val currentSignature = subtitleSignature(currentCues)
                if (currentSignature != sourceSignature || currentCues.size != sourceCues.size) {
                    logger.log(
                        1,
                        "SubtitleTranslate",
                        "Hủy áp dụng bản dịch vì phụ đề nguồn đã thay đổi expectedSignature=$sourceSignature currentSignature=$currentSignature expectedCues=${sourceCues.size} currentCues=${currentCues.size}",
                    )
                    updateState {
                        it.copy(
                            status = "Phụ đề đã thay đổi; bản dịch vừa tạo không được áp dụng",
                            subtitleTranslationInProgress = false,
                            subtitleShowingVietnamese = false,
                        )
                    }
                    return@launch
                }

                val translatedById = result.items.associateBy { it.id }
                val translatedCues = sourceCues.map { cue ->
                    SubtitleStore.Cue(
                        index = cue.index,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        text = translatedById.getValue(cue.index).text,
                    )
                }
                vietnameseSubtitles.replaceTimed(translatedCues)
                val translatedText = vietnameseSubtitles.plainText()
                val applyElapsedMs = SystemClock.elapsedRealtime() - startedAt
                logger.log(
                    2,
                    "SubtitleTranslate",
                    "Áp dụng bản dịch thành công cues=${translatedCues.size} translatedChars=${translatedText.length} attempts=${result.attempts} interactionId=${result.interactionId ?: "none"} inputTokens=${result.inputTokens} outputTokens=${result.outputTokens} thoughtTokens=${result.thoughtTokens} totalTokens=${result.totalTokens} clientElapsedMs=${result.elapsedMs} totalElapsedMs=$applyElapsedMs",
                )
                DiagnosticContext.updateAll(
                    mapOf(
                        "subtitleTranslate.running" to false,
                        "subtitleTranslate.success" to true,
                        "subtitleTranslate.translatedChars" to translatedText.length,
                        "subtitleTranslate.attempts" to result.attempts,
                        "subtitleTranslate.inputTokens" to result.inputTokens,
                        "subtitleTranslate.outputTokens" to result.outputTokens,
                        "subtitleTranslate.thoughtTokens" to result.thoughtTokens,
                        "subtitleTranslate.totalTokens" to result.totalTokens,
                        "subtitleTranslate.elapsedMs" to applyElapsedMs,
                    )
                )
                updateState {
                    it.copy(
                        transcript = translatedText.takeLast(MAX_TRANSCRIPT_CHARS),
                        status = "Đã dịch phụ đề sang tiếng Việt",
                        subtitleTranslationAvailable = true,
                        subtitleTranslationInProgress = false,
                        subtitleShowingVietnamese = true,
                        lastError = null,
                    )
                }
                scheduleHistorySave("subtitle-translate-success")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) return@launch
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                logger.log(
                    0,
                    "SubtitleTranslate",
                    "Tác vụ dịch thất bại totalElapsedMs=$elapsedMs sourceCues=${sourceCues.size} sourceChars=$sourceChars",
                    error,
                )
                DiagnosticContext.updateAll(
                    mapOf(
                        "subtitleTranslate.running" to false,
                        "subtitleTranslate.success" to false,
                        "subtitleTranslate.elapsedMs" to elapsedMs,
                        "subtitleTranslate.error" to (error.message ?: error.javaClass.simpleName),
                    )
                )
                updateState {
                    it.copy(
                        transcript = originalTranscriptForDisplay().takeLast(MAX_TRANSCRIPT_CHARS),
                        status = "Lỗi dịch phụ đề: ${error.message ?: error.javaClass.simpleName}",
                        subtitleTranslationInProgress = false,
                        subtitleShowingVietnamese = false,
                        lastError = error.message ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                client.close()
                subtitleTranslationJob = null
            }
        }
    }

    fun toggleSubtitleLanguage() {
        if (!_state.value.subtitleTranslationAvailable || _state.value.subtitleTranslationInProgress) return
        showVietnameseSubtitles(!_state.value.subtitleShowingVietnamese)
    }

    private fun showVietnameseSubtitles(showVietnamese: Boolean) {
        if (showVietnamese && !_state.value.subtitleTranslationAvailable) return
        val text = if (showVietnamese) {
            vietnameseSubtitles.plainText()
        } else {
            originalTranscriptForDisplay()
        }
        logger.log(
            2,
            "SubtitleTranslate",
            "Chuyển hiển thị language=${if (showVietnamese) "vi" else "original"} chars=${text.length}",
        )
        updateState {
            it.copy(
                transcript = text.takeLast(MAX_TRANSCRIPT_CHARS),
                subtitleShowingVietnamese = showVietnamese,
                status = if (showVietnamese) "Đang xem bản dịch tiếng Việt" else "Đang xem bản gốc",
            )
        }
        scheduleHistorySave("subtitle-view-toggle")
    }

    private fun originalTranscriptForDisplay(): String =
        transcribePlainText.takeIf { it.isNotBlank() } ?: subtitles.plainText()

    private fun subtitleSignature(cues: List<SubtitleStore.Cue>): String {
        var hash = 1125899906842597L
        cues.forEach { cue ->
            hash = hash * 31L + cue.index
            hash = hash * 31L + cue.startMs
            hash = hash * 31L + cue.endMs
            hash = hash * 31L + cue.text.hashCode()
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    fun restoreHistorySession(id: String): Boolean {
        if (_state.value.running) stopTranslation("Đã dừng để mở phiên lịch sử")
        historySaveJob?.cancel()
        historySaveJob = null

        val loaded = historyStore.load(id)
        if (loaded == null) {
            logger.log(1, "History", "Không tìm thấy phiên id=$id")
            updateState { it.copy(status = "Không tìm thấy phiên lịch sử") }
            return false
        }

        resetSessionState()
        subtitles.reset()
        vietnameseSubtitles.reset()

        processingMode = if (loaded.processingMode == AppPreferences.PROCESSING_MODE_TRANSCRIBE) {
            AppPreferences.PROCESSING_MODE_TRANSCRIBE
        } else {
            AppPreferences.PROCESSING_MODE_TRANSLATE
        }
        preferences.setProcessingMode(processingMode)

        currentMode = runCatching { SourceMode.valueOf(loaded.sourceMode) }
            .getOrDefault(SourceMode.FILE)
        speakerDiarization = loaded.speakerDiarization
        preferences.setSpeakerDiarization(speakerDiarization)
        selectedUri = loaded.mediaUri?.let(Uri::parse)
        selectedFileName = loaded.mediaName

        restoreStoreFromHistory(subtitles, loaded.primarySrt, loaded.primaryTranscript)
        restoreStoreFromHistory(
            vietnameseSubtitles,
            loaded.vietnameseSrt,
            loaded.vietnameseTranscript,
        )

        transcribePlainText = if (processingMode == AppPreferences.PROCESSING_MODE_TRANSCRIBE) {
            loaded.primaryTranscript.ifBlank { subtitles.plainText() }
        } else {
            ""
        }
        liveCommittedTranscript = transcribePlainText
        liveInterimTranscript = ""

        val hasVietnamese = loaded.hasVietnamese && vietnameseSubtitles.plainText().isNotBlank()
        val showVietnamese = loaded.showingVietnamese && hasVietnamese
        val displayText = if (showVietnamese) {
            loaded.vietnameseTranscript.ifBlank { vietnameseSubtitles.plainText() }
        } else {
            loaded.primaryTranscript.ifBlank { subtitles.plainText() }
        }

        currentHistorySession = loaded
        sessionId = ""
        sessionStartedAt = 0L
        fileInputEnded = false
        _state.value = _state.value.copy(
            status = "Đã mở phiên lịch sử",
            health = "",
            running = false,
            paused = false,
            setupComplete = false,
            sourceMode = currentMode,
            selectedUri = selectedUri,
            selectedFileName = selectedFileName,
            transcript = displayText.takeLast(MAX_TRANSCRIPT_CHARS),
            progressPercent = 0,
            canSeek = false,
            aiVoice = settings.aiVoice && processingMode != AppPreferences.PROCESSING_MODE_TRANSCRIBE,
            currentLanguage = settings.targetLanguage,
            lastError = null,
            subtitleTranslationAvailable = hasVietnamese,
            subtitleTranslationInProgress = false,
            subtitleShowingVietnamese = showVietnamese,
        )
        logger.log(
            2,
            "History",
            "Khôi phục phiên id=${loaded.id} title=${loaded.title} source=${loaded.sourceMode} processing=${loaded.processingMode} media=${loaded.mediaName ?: "none"} primaryChars=${loaded.primaryTranscript.length} primarySrtChars=${loaded.primarySrt.length} viChars=${loaded.vietnameseTranscript.length} viSrtChars=${loaded.vietnameseSrt.length} showVi=$showVietnamese",
        )
        return true
    }

    private fun restoreStoreFromHistory(
        store: SubtitleStore,
        srt: String,
        transcript: String,
    ) {
        if (srt.isNotBlank()) {
            val parsed = SrtParser.parse(srt)
            if (parsed.cues.isNotEmpty()) {
                store.replaceTimed(parsed.cues)
                return
            }
        }
        transcript.trim().takeIf(String::isNotBlank)?.let(store::append)
    }

    private fun beginHistorySession(mode: SourceMode, reason: String) {
        saveCurrentHistoryNow("before-$reason")
        historySaveJob?.cancel()
        historySaveJob = null
        currentHistorySession = historyStore.newSession(
            sourceMode = mode.name,
            processingMode = processingMode,
            mediaUri = if (mode == SourceMode.FILE) selectedUri?.toString() else null,
            mediaName = if (mode == SourceMode.FILE) selectedFileName else null,
            speakerDiarization = speakerDiarization,
        )
        logger.log(
            2,
            "History",
            "Bắt đầu phiên nháp id=${currentHistorySession?.id} reason=$reason source=$mode processing=$processingMode media=${selectedFileName ?: "none"}",
        )
    }

    private fun scheduleHistorySave(reason: String) {
        val id = currentHistorySession?.id ?: return
        historySaveJob?.cancel()
        historySaveJob = serviceScope.launch(Dispatchers.IO) {
            delay(HISTORY_SAVE_DEBOUNCE_MS)
            if (currentHistorySession?.id == id) saveCurrentHistoryNow(reason)
        }
    }

    private fun saveCurrentHistoryNow(reason: String) {
        val base = currentHistorySession ?: return
        val primaryTranscript = if (processingMode == AppPreferences.PROCESSING_MODE_TRANSCRIBE) {
            transcribePlainText.trim().ifBlank { subtitles.plainText() }
        } else {
            subtitles.plainText().ifBlank { _state.value.transcript }
        }
        val primarySrt = subtitles.srtText()
        val viTranscript = vietnameseSubtitles.plainText()
        val viSrt = vietnameseSubtitles.srtText()
        val candidate = base.copy(
            primaryTranscript = primaryTranscript,
            primarySrt = primarySrt,
            vietnameseTranscript = viTranscript,
            vietnameseSrt = viSrt,
            showingVietnamese = _state.value.subtitleShowingVietnamese,
        )
        if (!candidate.hasValue) {
            logger.log(3, "History", "Bỏ lưu phiên rỗng id=${base.id} reason=$reason")
            return
        }
        runCatching { historyStore.save(candidate) }
            .onSuccess { saved ->
                currentHistorySession = saved
                logger.log(
                    2,
                    "History",
                    "Đã lưu phiên id=${saved.id} reason=$reason title=${saved.title} source=${saved.sourceMode} primaryChars=${saved.primaryTranscript.length} primarySrtChars=${saved.primarySrt.length} viChars=${saved.vietnameseTranscript.length} viSrtChars=${saved.vietnameseSrt.length} showVi=${saved.showingVietnamese} historyCount=${historyStore.count()}",
                )
            }
            .onFailure { error ->
                logger.log(0, "History", "Lưu phiên thất bại id=${base.id} reason=$reason", error)
            }
    }

    fun logText(): String = logger.text()

    private fun resetSessionState() {
        reconnectAttempts = 0
        sourceStarted = false
        fileInputEnded = false
        lastRawTranscript = ""
        transcribePlainText = ""
        liveCommittedTranscript = ""
        liveInterimTranscript = ""
        liveInterimEvents = 0L
        liveFinalEvents = 0L
        transcribeRotationJob?.cancel()
        transcribeRotationJob = null
        subtitleTranslationJob?.cancel()
        subtitleTranslationJob = null
        vietnameseSubtitles.reset()
        totalInputBytes = 0L
        totalOutputBytes = 0L
        sessionResumptionHandle = null
        resumedConnections.set(0L)
        goAwayCount.set(0L)
        droppedInputChunks.set(0L)
        lastLoggedDroppedInputChunks.set(0L)
        subtitleCallbackEvents.set(0L)
        subtitleAppliedEvents.set(0L)
        subtitleFilteredEvents.set(0L)
        lastBackpressureEvents = 0L
        activeInputQueueCapacity = 0
        setupStartedAt = 0L
        inputEpoch.incrementAndGet()
        synchronized(inputLock) { inputAccumulator.reset() }
    }

    private fun connectGemini(apiKey: String) {
        if (apiKey.isBlank() || !_state.value.running) return
        reconnectJob?.cancel()
        reconnectJob = null
        val generation = ++connectionGeneration
        val handleForThisConnection = if (isTranscribeMode()) null else sessionResumptionHandle
        setupStartedAt = SystemClock.elapsedRealtime()
        logger.log(2, "GeminiWS", "Mở kết nối generation=$generation resume=${!handleForThisConnection.isNullOrBlank()} maxWireBytes=${calculateMaxQueuedWireBytes()}")
        client?.close(false)
        updateState {
            it.copy(
                setupComplete = false,
                status = if (isTranscribeMode()) {
                    "Đang kết nối chép lời..."
                } else if (handleForThisConnection.isNullOrBlank()) {
                    "Đang kết nối Gemini Live API..."
                } else {
                    "Đang khôi phục phiên Gemini..."
                }
            )
        }
        val liveClient = GeminiLiveClient(
            apiKey = apiKey,
            model = activeModelName(),
            targetLanguage = settings.targetLanguage,
            echoTargetLanguage = settings.echoTargetLanguage,
            logger = logger,
            resumeHandle = handleForThisConnection,
            maxQueuedWireBytes = calculateMaxQueuedWireBytes(),
            operationMode = if (isTranscribeMode()) {
                GeminiLiveClient.OperationMode.TRANSCRIBE
            } else {
                GeminiLiveClient.OperationMode.TRANSLATE
            },
            listener = object : GeminiLiveClient.Listener {
                override fun onSetupComplete() {
                    if (generation != connectionGeneration || !_state.value.running) return
                    reconnectAttempts = 0
                    val setupLatencyMs = (SystemClock.elapsedRealtime() - setupStartedAt).coerceAtLeast(0L)
                    if (!handleForThisConnection.isNullOrBlank()) resumedConnections.incrementAndGet()
                    logger.log(2, "GeminiWS", "Setup hoàn tất generation=$generation latencyMs=$setupLatencyMs resumed=${!handleForThisConnection.isNullOrBlank()}")
                    DiagnosticContext.updateAll(mapOf(
                        "session.connectionGeneration" to generation,
                        "session.setupLatencyMs" to setupLatencyMs,
                        "session.resumptionUsed" to !handleForThisConnection.isNullOrBlank(),
                    ))
                    updateState {
                        it.copy(
                            setupComplete = true,
                            status = if (isTranscribeMode()) {
                                "Đang chép lời..."
                            } else if (handleForThisConnection.isNullOrBlank()) {
                                "Setup hoàn tất! Bắt đầu truyền audio..."
                            } else {
                                "Đã khôi phục phiên; tiếp tục truyền audio..."
                            }
                        )
                    }
                    if (isTranscribeMode()) scheduleTranscribeRotation(apiKey)
                    if (sourceStarted) {
                        if (!_state.value.paused) {
                            source?.resume()
                            aiPlayer?.resume()
                            originalPlayer?.resume()
                        }
                    } else {
                        launchSource()
                    }
                }

                override fun onText(text: String) {
                    val event = subtitleCallbackEvents.incrementAndGet()
                    val currentGeneration = connectionGeneration
                    val currentState = _state.value
                    logger.log(
                        2,
                        "SubtitlePipe",
                        "onText event=$event chars=${text.length} callbackGeneration=$generation currentGeneration=$currentGeneration running=${currentState.running} setup=${currentState.setupComplete} transcriptChars=${currentState.transcript.length}",
                    )
                    if (generation != currentGeneration) {
                        subtitleFilteredEvents.incrementAndGet()
                        logger.log(1, "SubtitlePipe", "Bỏ onText event=$event do generation cũ callback=$generation current=$currentGeneration")
                        return
                    }
                    if (!isTranscribeMode()) appendTranslation(text, event)
                }

                override fun onAudio(pcm24kMono: ByteArray) {
                    if (generation != connectionGeneration || !_state.value.running || isTranscribeMode()) return
                    totalOutputBytes += pcm24kMono.size
                    if (settings.aiVoice) aiPlayer?.enqueue(pcm24kMono)
                    translatedWriter?.write(pcm24kMono)
                    mixedWriter?.mix(pcm24kMono, 24_000, elapsedMs(), translatedGain())
                    applyDucking(pcm24kMono.size * 1_000L / (24_000L * 2L))
                }

                override fun onInputTranscript(text: String) {
                    if (generation != connectionGeneration || !_state.value.running) return
                    if (isTranscribeMode()) {
                        appendTranscriptionSegment(text)
                    } else {
                        logger.log(3, "GeminiInput", text)
                    }
                }

                override fun onInterimTranscript(text: String) {
                    if (generation != connectionGeneration || !_state.value.running || !isTranscribeMode()) return
                    updateLiveInterimTranscript(text)
                }

                override fun onTurnComplete() {
                    if (generation != connectionGeneration) return
                    if (isTranscribeMode() && currentMode != SourceMode.FILE) {
                        finalizeLiveTranscriptionFallback("turnComplete")
                    }
                    lastRawTranscript = ""
                    flushTtsBuffer()
                    if (fileInputEnded) stopTranslation("Đã hoàn tất tệp")
                }

                override fun onInterrupted() {
                    if (generation != connectionGeneration) return
                    aiPlayer?.flush()
                    lastRawTranscript = ""
                }

                override fun onSessionResumptionUpdate(resumable: Boolean, newHandle: String?) {
                    if (generation != connectionGeneration || !_state.value.running) return
                    if (resumable && !newHandle.isNullOrBlank()) {
                        sessionResumptionHandle = newHandle
                        logger.log(3, "GeminiWS", "Đã cập nhật session resumption handle")
                    } else {
                        logger.log(3, "GeminiWS", "Phiên tạm thời chưa thể resume tại điểm hiện tại")
                    }
                }

                override fun onGoAway(timeLeft: String?) {
                    if (generation != connectionGeneration || !_state.value.running) return
                    goAwayCount.incrementAndGet()
                    val reconnectDelay = goAwayReconnectDelayMs(timeLeft)
                    logger.log(2, "GeminiWS", "Nhận GoAway timeLeft=${timeLeft ?: "không rõ"}; chuẩn bị resume")
                    scheduleReconnect(reconnectDelay, false, "Server sắp đóng kết nối; đang chuyển sang kết nối mới")
                }

                override fun onError(error: Throwable) {
                    if (generation == connectionGeneration) handleConnectionError(error)
                }

                override fun onClosed(reason: String) {
                    if (generation == connectionGeneration && _state.value.running) {
                        handleConnectionError(IllegalStateException(reason))
                    }
                }
            }
        )
        client = liveClient
        liveClient.connect()
    }

    private fun startVideoDescription(apiKey: String) {
        sourceJob?.cancel()
        sourceJob = serviceScope.launch(Dispatchers.IO) {
            val uri = selectedUri ?: run {
                stopTranslation("Chưa chọn video")
                return@launch
            }
            val startedAt = SystemClock.elapsedRealtime()
            val name = selectedFileName ?: uri.lastPathSegment ?: "video"
            try {
                val mimeType = selectedMimeType(uri, name)
                val isVideo = mimeType.startsWith("video/") || isVideoFileName(name)
                if (!isVideo) error("Tệp đã chọn không phải video")

                val durationStartedAt = SystemClock.elapsedRealtime()
                val durationMs = mediaDurationMs(uri)
                logger.log(
                    2,
                    "VideoDescription",
                    "Đọc metadata name=$name mime=$mimeType durationMs=$durationMs metadataElapsedMs=${SystemClock.elapsedRealtime() - durationStartedAt} sourceBytes=${sourceSizeBytes(uri)} submode=$videoDescriptionMode",
                )
                if (durationMs <= 0L) error("Không đọc được thời lượng video")
                if (durationMs > GeminiVideoDescriptionClient.MAX_VIDEO_DURATION_MS) {
                    error("Video dài quá 20 phút. Hãy chọn video tối đa 20 phút")
                }

                val resolvedMime = if (mimeType.startsWith("video/")) {
                    mimeType
                } else {
                    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                        "webm" -> "video/webm"
                        "3gp", "3gpp" -> "video/3gpp"
                        "mkv" -> "video/x-matroska"
                        else -> "video/mp4"
                    }
                }
                val client = GeminiVideoDescriptionClient(
                    apiKey = apiKey,
                    logger = logger,
                    includeOutputInLogs = settings.logIncludeTranscript,
                )
                val result = try {
                    updateState {
                        it.copy(
                            status = "Đang tải nguyên video lên...",
                            progressPercent = 0,
                        )
                    }
                    client.describe(
                        resolver = contentResolver,
                        uri = uri,
                        displayName = name,
                        mimeType = resolvedMime,
                        durationMs = durationMs,
                        mode = if (isVideoDescriptionSummary()) {
                            GeminiVideoDescriptionClient.Mode.SUMMARY
                        } else {
                            GeminiVideoDescriptionClient.Mode.TIMELINE
                        },
                    ) { status, percent ->
                        updateState {
                            it.copy(
                                status = status,
                                progressPercent = percent.coerceIn(0, 98),
                            )
                        }
                    }
                } finally {
                    client.close()
                }

                if (isVideoDescriptionSummary()) {
                    subtitles.reset()
                    videoSummaryText = result.summaryText
                    updateState {
                        it.copy(
                            transcript = videoSummaryText.takeLast(MAX_TRANSCRIPT_CHARS),
                            status = "Đã hoàn tất mô tả tổng hợp",
                            progressPercent = 100,
                        )
                    }
                } else {
                    videoSummaryText = ""
                    val cues = result.timelineItems.map { item ->
                        SubtitleStore.Cue(
                            index = item.index,
                            startMs = (item.startSeconds * 1_000.0).toLong().coerceAtLeast(0L),
                            endMs = (item.endSeconds * 1_000.0).toLong().coerceAtLeast(1L),
                            text = item.text,
                        )
                    }
                    subtitles.replaceTimed(cues)
                    updateState {
                        it.copy(
                            transcript = subtitles.plainText().takeLast(MAX_TRANSCRIPT_CHARS),
                            status = "Đã hoàn tất mô tả theo thời gian",
                            progressPercent = 100,
                        )
                    }
                }

                fileInputEnded = true
                val outputChars = if (isVideoDescriptionSummary()) {
                    videoSummaryText.length
                } else {
                    subtitles.plainText().length
                }
                logger.log(
                    2,
                    "VideoDescription",
                    "Áp dụng kết quả thành công mode=$videoDescriptionMode durationMs=$durationMs items=${result.timelineItems.size} chars=$outputChars attempts=${result.attempts} interactionId=${result.interactionId ?: "none"} inputTokens=${result.inputTokens} outputTokens=${result.outputTokens} thoughtTokens=${result.thoughtTokens} totalTokens=${result.totalTokens} clientElapsedMs=${result.elapsedMs} totalElapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
                scheduleHistorySave("video-description-success")
                stopTranslation(
                    if (isVideoDescriptionSummary()) {
                        "Đã hoàn tất mô tả tổng hợp"
                    } else {
                        "Đã hoàn tất mô tả theo thời gian"
                    }
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) return@launch
                logger.log(
                    0,
                    "VideoDescription",
                    "Mô tả video thất bại mode=$videoDescriptionMode elapsedMs=${SystemClock.elapsedRealtime() - startedAt} name=$name",
                    error,
                )
                if (_state.value.running) {
                    stopTranslation("Lỗi mô tả video: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun startFileTranscription(apiKey: String) {
        sourceJob?.cancel()
        sourceJob = serviceScope.launch(Dispatchers.IO) {
            val uri = selectedUri ?: run {
                stopTranslation("Chưa chọn tệp")
                return@launch
            }
            val workDir = File(cacheDir, "transcribe-$sessionId").apply {
                deleteRecursively()
                mkdirs()
            }
            val totalStartedAt = SystemClock.elapsedRealtime()
            try {
                val name = selectedFileName ?: uri.lastPathSegment ?: "audio"
                val mimeType = selectedMimeType(uri, name)
                val video = mimeType.startsWith("video/") || isVideoFileName(name)
                val sourceBytes = sourceSizeBytes(uri)
                logger.log(
                    2,
                    "TranscribeFile",
                    "Bắt đầu xử lý name=$name mime=$mimeType video=$video sourceBytes=$sourceBytes",
                )

                var durationMs: Long
                val fileClient = GeminiFileTranscribeClient(apiKey, logger)
                val result = try {
                    if (video) {
                        val extractStartedAt = SystemClock.elapsedRealtime()
                        logger.log(2, "TranscribeFile", "Bắt đầu tách audio track từ video")
                        updateState { it.copy(status = "Đang tách âm thanh...", progressPercent = 0) }
                        val extracted = VideoAudioExtractor.extract(
                            context = this@TranslationService,
                            uri = uri,
                            output = File(workDir, "audio.m4a"),
                            maxDurationMs = MAX_TRANSCRIBE_FILE_DURATION_MS,
                        ) { percent ->
                            updateState {
                                it.copy(
                                    status = "Đang tách âm thanh...",
                                    progressPercent = (percent / 10).coerceIn(0, 10),
                                )
                            }
                        }
                        durationMs = extracted.durationMs
                        val extractElapsedMs = SystemClock.elapsedRealtime() - extractStartedAt
                        logger.log(
                            2,
                            "TranscribeFile",
                            "Tách audio xong elapsedMs=$extractElapsedMs durationMs=$durationMs samples=${extracted.sampleCount} outputBytes=${extracted.outputBytes} trackMime=${extracted.trackMimeType} outputMime=${extracted.mimeType} strategy=${extracted.strategy}",
                        )
                        fileClient.transcribe(
                            file = extracted.file,
                            mimeType = extracted.mimeType,
                            speakerDiarization = speakerDiarization,
                        ) { status, percent ->
                            updateState {
                                it.copy(
                                    status = status,
                                    progressPercent = (10 + percent * 90 / 100).coerceIn(10, 98),
                                )
                            }
                        }
                    } else {
                        val metadataStartedAt = SystemClock.elapsedRealtime()
                        durationMs = mediaDurationMs(uri)
                        val metadataElapsedMs = SystemClock.elapsedRealtime() - metadataStartedAt
                        logger.log(
                            2,
                            "TranscribeFile",
                            "Đọc metadata audio xong elapsedMs=$metadataElapsedMs durationMs=$durationMs",
                        )
                        if (durationMs <= 0L) error("Không đọc được thời lượng tệp")
                        if (durationMs > MAX_TRANSCRIBE_FILE_DURATION_MS) {
                            error("Tệp dài quá 30 phút. Hãy cắt tệp ngắn hơn rồi thử lại")
                        }
                        updateState { it.copy(status = "Đang tải tệp lên...", progressPercent = 0) }
                        fileClient.transcribe(
                            resolver = contentResolver,
                            uri = uri,
                            displayName = name,
                            mimeType = mimeType,
                            speakerDiarization = speakerDiarization,
                        ) { status, percent ->
                            updateState {
                                it.copy(
                                    status = status,
                                    progressPercent = percent.coerceIn(0, 98),
                                )
                            }
                        }
                    }
                } finally {
                    fileClient.close()
                }

                val resultBuildStartedAt = SystemClock.elapsedRealtime()
                val cues = buildTranscriptionCues(result.words, speakerDiarization)
                if (cues.isNotEmpty()) {
                    subtitles.replaceTimed(cues)
                } else {
                    subtitles.reset()
                    result.text.trim().takeIf(String::isNotBlank)?.let {
                        subtitles.appendTimed(it, 0L, durationMs.coerceAtLeast(1_000L))
                    }
                }
                transcribePlainText = if (speakerDiarization && cues.isNotEmpty()) {
                    subtitles.plainText()
                } else {
                    result.text.trim().ifBlank { subtitles.plainText() }
                }
                val resultBuildElapsedMs = SystemClock.elapsedRealtime() - resultBuildStartedAt
                fileInputEnded = true
                updateState {
                    it.copy(
                        transcript = transcribePlainText.takeLast(MAX_TRANSCRIPT_CHARS),
                        status = "Đã hoàn tất chép lời",
                        progressPercent = 100,
                    )
                }
                logger.log(
                    2,
                    "TranscribeFile",
                    "Hoàn tất toàn bộ totalElapsedMs=${SystemClock.elapsedRealtime() - totalStartedAt} resultBuildMs=$resultBuildElapsedMs chars=${transcribePlainText.length} words=${result.words.size} cues=${cues.size}",
                )
                stopTranslation("Đã hoàn tất chép lời")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) return@launch
                logger.log(
                    0,
                    "TranscribeFile",
                    "Chép lời tệp thất bại elapsedMs=${SystemClock.elapsedRealtime() - totalStartedAt}",
                    error,
                )
                if (_state.value.running) {
                    stopTranslation("Lỗi chép lời: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    private fun mediaDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("Không mở được tệp để đọc thời lượng")
        return try {
            retriever.setDataSource(descriptor.fileDescriptor)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } finally {
            retriever.release()
            descriptor.close()
        }
    }

    private fun sourceSizeBytes(uri: Uri): Long = runCatching {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
                ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0L }
                ?: -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun selectedMimeType(uri: Uri, name: String): String {
        contentResolver.getType(uri)?.takeIf(String::isNotBlank)?.let { return it }
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a", "mp4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "webm" -> "video/webm"
            "mp4", "m4v", "mov" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "3gp", "3gpp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }

    private fun isVideoFileName(name: String): Boolean =
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3gpp" -> true
            else -> false
        }

    private fun buildTranscriptionCues(
        words: List<GeminiFileTranscribeClient.WordInfo>,
        diarization: Boolean,
    ): List<SubtitleStore.Cue> {
        if (words.isEmpty()) return emptyList()
        val speakerLabels = linkedMapOf<String, String>()

        fun label(raw: String?): String? {
            if (!diarization || raw.isNullOrBlank()) return null
            return speakerLabels.getOrPut(raw) { "Người nói ${speakerLabels.size + 1}" }
        }

        val cues = ArrayList<SubtitleStore.Cue>()
        var group = ArrayList<GeminiFileTranscribeClient.WordInfo>()
        var groupSpeaker: String? = null

        fun flush() {
            if (group.isEmpty()) return
            val body = normalizeWordSpacing(group.joinToString(" ") { it.text })
            val speaker = label(groupSpeaker)
            val text = if (speaker == null) body else "$speaker: $body"
            cues += SubtitleStore.Cue(
                index = cues.size + 1,
                startMs = group.first().startMs,
                endMs = group.last().endMs.coerceAtLeast(group.first().startMs + 1L),
                text = text,
            )
            group = ArrayList()
            groupSpeaker = null
        }

        words.sortedBy { it.startMs }.forEach { word ->
            val speakerChanged = diarization && group.isNotEmpty() && word.speaker != groupSpeaker
            val tooLong = group.isNotEmpty() && (
                word.endMs - group.first().startMs >= 5_500L ||
                    group.size >= 14 ||
                    group.sumOf { it.text.length + 1 } >= 84
                )
            if (speakerChanged || tooLong) flush()
            if (group.isEmpty()) groupSpeaker = word.speaker
            group += word
        }
        flush()
        return cues
    }

    private fun normalizeWordSpacing(value: String): String = value
        .replace(Regex("\\s+([,.;:!?%])"), "\$1")
        .replace(Regex("([\\(\\[\\{])\\s+"), "\$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun launchSource() {
        if (sourceStarted || !_state.value.running) return
        val created = when (currentMode) {
            SourceMode.FILE -> FileAudioSource(
                context = this,
                uri = selectedUri ?: return,
                pacingEnabled = settings.pacingEnabled,
                leadMs = settings.pacingTargetLatencyMs,
                initialPlaybackSpeed = filePlaybackSpeed,
                logger = logger,
            )
            SourceMode.MICROPHONE -> MicAudioSource(this, logger)
            SourceMode.INTERNAL -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    stopTranslation("Thu âm thanh nội bộ yêu cầu Android 10 trở lên")
                    return
                }
                InternalAudioSource(this, mediaProjection ?: return, logger)
            }
        }
        source = created
        sourceStarted = true
        sourceJob = serviceScope.launch(Dispatchers.IO) {
            created.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    if (!_state.value.running || _state.value.paused) return
                    totalInputBytes += data.size
                    recordOriginal(data)
                    if (currentMode == SourceMode.FILE) originalQueue?.trySend(data.copyOf())
                    sendInput(data)
                }

                override fun onProgress(percent: Int, positionMs: Long, durationMs: Long) {
                    updateState { it.copy(progressPercent = percent) }
                }

                override fun onCompleted() {
                    if (currentMode != SourceMode.FILE || !_state.value.running) return
                    fileInputEnded = true
                    closeInputAccumulator(send = true)
                    enqueueInputStreamEnd()
                    updateState { it.copy(status = "Đã gửi hết audio; đang chờ Gemini hoàn tất bản dịch...", progressPercent = 100) }
                    fileFinishFallbackJob?.cancel()
                    fileFinishFallbackJob = serviceScope.launch {
                        delay(60_000)
                        if (_state.value.running && fileInputEnded) stopTranslation("Đã hoàn tất tệp (hết thời gian chờ phản hồi cuối)")
                    }
                }

                override fun onError(error: Throwable) {
                    if (_state.value.running) {
                        logger.log(0, "AudioSource", "Nguồn âm thanh gặp lỗi", error)
                        stopTranslation("Lỗi nguồn âm thanh: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            })
        }
        updateState {
            it.copy(
                status = when (currentMode) {
                    SourceMode.FILE -> "Đã sẵn sàng; tốc độ ${formatFileSpeed()}×; nhấn Phát để tạm dừng/tiếp tục"
                    SourceMode.MICROPHONE -> "Đang thu microphone..."
                    SourceMode.INTERNAL -> "Đang thu âm thanh nội bộ..."
                }
            )
        }
    }

    private fun setupInputSender() {
        inputSendJob?.cancel()
        inputSendQueue?.clear()
        val capacity = settings.pacingMaxBuffer.coerceIn(1, 50)
        activeInputQueueCapacity = capacity
        val queue = LinkedBlockingDeque<InputFrame>(capacity)
        logger.log(2, "InputQueue", "Khởi tạo hàng đợi gửi capacity=$capacity source=$currentMode quality=${settings.qualityMode}")
        inputSendQueue = queue
        inputSendJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && _state.value.running) {
                val frame = queue.pollFirst(250, TimeUnit.MILLISECONDS) ?: continue
                if (frame.epoch != inputEpoch.get()) continue
                var delivered = false
                while (isActive && _state.value.running && frame.epoch == inputEpoch.get() && !delivered) {
                    val currentClient = client
                    val result = when (frame) {
                        is InputFrame.Audio -> currentClient?.sendAudio(frame.data)
                        is InputFrame.StreamEnd -> currentClient?.sendAudioStreamEnd()
                    } ?: GeminiLiveClient.SendResult.NOT_READY
                    when (result) {
                        GeminiLiveClient.SendResult.SENT -> delivered = true
                        GeminiLiveClient.SendResult.BACKPRESSURED -> delay(12)
                        GeminiLiveClient.SendResult.NOT_READY,
                        GeminiLiveClient.SendResult.CLOSED -> delay(30)
                        GeminiLiveClient.SendResult.FAILED -> {
                            scheduleReconnect(250L, true, "Không thể ghi dữ liệu vào WebSocket")
                            delay(50)
                        }
                    }
                }
            }
        }
    }

    private fun sendInput(data: ByteArray) {
        val useAccumulator = settings.qualityMode && currentMode != SourceMode.MICROPHONE
        if (!useAccumulator) {
            enqueueInputAudio(data)
            return
        }
        val threshold = (16_000 * 2 * settings.inputBufferMs / 1_000).coerceAtLeast(3_200)
        var chunk: ByteArray? = null
        synchronized(inputLock) {
            inputAccumulator.write(data)
            if (inputAccumulator.size() >= threshold) {
                chunk = inputAccumulator.toByteArray()
                inputAccumulator.reset()
            }
        }
        chunk?.let(::enqueueInputAudio)
    }

    private fun enqueueInputAudio(data: ByteArray): Boolean {
        if (data.isEmpty() || !_state.value.running) return false
        val queue = inputSendQueue ?: return false
        val epoch = inputEpoch.get()
        val frame = InputFrame.Audio(data.copyOf(), epoch)
        if (currentMode == SourceMode.FILE) {
            while (_state.value.running && epoch == inputEpoch.get()) {
                if (queue.offerLast(frame, 100, TimeUnit.MILLISECONDS)) return true
            }
            return false
        }

        if (queue.offerLast(frame)) return true
        while (_state.value.running && epoch == inputEpoch.get()) {
            when (val removed = queue.pollFirst()) {
                is InputFrame.Audio -> {
                    val dropped = droppedInputChunks.incrementAndGet()
                    val lastLogged = lastLoggedDroppedInputChunks.get()
                    if (dropped == 1L || dropped - lastLogged >= 25L) {
                        lastLoggedDroppedInputChunks.set(dropped)
                        logger.log(1, "InputQueue", "Bỏ audio cũ do queue đầy dropped=$dropped capacity=$activeInputQueueCapacity source=$currentMode")
                    }
                }
                is InputFrame.StreamEnd -> {
                    queue.offerFirst(removed)
                    return false
                }
                null -> Unit
            }
            if (queue.offerLast(frame)) return true
        }
        return false
    }

    private fun enqueueInputStreamEnd(): Boolean {
        val queue = inputSendQueue ?: return false
        val epoch = inputEpoch.get()
        val frame = InputFrame.StreamEnd(epoch)
        while (_state.value.running && epoch == inputEpoch.get()) {
            if (queue.offerLast(frame, 100, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun closeInputAccumulator(send: Boolean) {
        val remainder = synchronized(inputLock) {
            inputAccumulator.toByteArray().also { inputAccumulator.reset() }
        }
        if (send && remainder.isNotEmpty()) enqueueInputAudio(remainder)
    }

    private fun clearPendingInputForFreshSession() {
        inputEpoch.incrementAndGet()
        inputSendQueue?.clear()
        synchronized(inputLock) { inputAccumulator.reset() }
        sessionResumptionHandle = null
    }

    private fun calculateMaxQueuedWireBytes(): Long {
        val rawChunkBytes = if (settings.qualityMode && currentMode != SourceMode.MICROPHONE) {
            (16_000L * 2L * settings.inputBufferMs / 1_000L).coerceAtLeast(3_200L)
        } else {
            3_200L
        }
        val estimatedJsonBytes = rawChunkBytes * 4L / 3L + 2_048L
        return (estimatedJsonBytes * settings.pacingMaxBuffer.coerceAtLeast(1))
            .coerceIn(64L * 1_024L, 2L * 1_024L * 1_024L)
    }

    private fun goAwayReconnectDelayMs(timeLeft: String?): Long {
        if (timeLeft.isNullOrBlank()) return 250L
        val seconds = Regex("([0-9]+(?:\\.[0-9]+)?)s")
            .find(timeLeft)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Regex("\\\"seconds\\\"\\s*:\\s*\\\"?([0-9]+)")
                .find(timeLeft)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: return 250L
        return (seconds * 1_000.0 - 1_000.0).toLong().coerceIn(250L, 3_000L)
    }

    private fun appendTranscriptionSegment(raw: String) {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return
        liveFinalEvents++
        liveCommittedTranscript = mergeLiveTranscript(liveCommittedTranscript, cleaned)
        liveInterimTranscript = ""
        transcribePlainText = liveCommittedTranscript
        subtitles.reset()
        subtitles.append(liveCommittedTranscript)
        updateState {
            it.copy(
                transcript = liveCommittedTranscript.takeLast(MAX_TRANSCRIPT_CHARS),
                status = "Đang chép lời...",
            )
        }
        scheduleHistorySave("transcribe-final")
        logger.log(
            2,
            "TranscribeLive",
            "final event=$liveFinalEvents chars=${cleaned.length} committedChars=${liveCommittedTranscript.length} interimEvents=$liveInterimEvents",
        )
        DiagnosticContext.updateAll(
            mapOf(
                "session.transcriptChars" to _state.value.transcript.length,
                "session.transcribeInterimEvents" to liveInterimEvents,
                "session.transcribeFinalEvents" to liveFinalEvents,
            )
        )
    }

    private fun updateLiveInterimTranscript(raw: String) {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return
        liveInterimTranscript = cleaned
        liveInterimEvents++
        val preview = mergeLiveTranscript(liveCommittedTranscript, cleaned)
        transcribePlainText = preview
        updateState {
            it.copy(
                transcript = preview.takeLast(MAX_TRANSCRIPT_CHARS),
                status = "Đang chép lời...",
            )
        }
        scheduleHistorySave("transcribe-interim")
        if (liveInterimEvents == 1L || liveInterimEvents % 10L == 0L) {
            logger.log(
                3,
                "TranscribeLive",
                "interim event=$liveInterimEvents chars=${cleaned.length} previewChars=${preview.length} committedChars=${liveCommittedTranscript.length} finals=$liveFinalEvents",
            )
        }
        DiagnosticContext.updateAll(
            mapOf(
                "session.transcriptChars" to _state.value.transcript.length,
                "session.transcribeInterimEvents" to liveInterimEvents,
                "session.transcribeFinalEvents" to liveFinalEvents,
            )
        )
    }

    private fun finalizeLiveTranscriptionFallback(reason: String) {
        if (!isTranscribeMode() || currentMode == SourceMode.FILE) return
        val preview = mergeLiveTranscript(liveCommittedTranscript, liveInterimTranscript).trim()
        if (preview.isBlank()) {
            logger.log(
                2,
                "TranscribeLive",
                "Không có nội dung để chốt reason=$reason interimEvents=$liveInterimEvents finalEvents=$liveFinalEvents",
            )
            return
        }
        val usedInterimFallback = preview != liveCommittedTranscript
        liveCommittedTranscript = preview
        liveInterimTranscript = ""
        transcribePlainText = preview
        subtitles.reset()
        subtitles.append(preview)
        _state.update {
            it.copy(
                transcript = preview.takeLast(MAX_TRANSCRIPT_CHARS),
                status = "Đã chốt nội dung chép lời",
            )
        }
        scheduleHistorySave("transcribe-fallback-$reason")
        logger.log(
            if (usedInterimFallback) 1 else 2,
            "TranscribeLive",
            "Chốt transcript reason=$reason chars=${preview.length} usedInterimFallback=$usedInterimFallback interimEvents=$liveInterimEvents finalEvents=$liveFinalEvents",
        )
    }

    private fun mergeLiveTranscript(baseRaw: String, candidateRaw: String): String {
        val base = baseRaw.trim()
        val candidate = candidateRaw.trim()
        if (base.isBlank()) return candidate
        if (candidate.isBlank()) return base
        if (candidate == base || base.endsWith(candidate)) return base
        if (candidate.startsWith(base)) return candidate
        if (base.startsWith(candidate)) return base
        val maxOverlap = minOf(base.length, candidate.length)
        for (length in maxOverlap downTo 1) {
            if (base.regionMatches(base.length - length, candidate, 0, length, ignoreCase = false)) {
                val suffix = candidate.substring(length).trimStart()
                return if (suffix.isBlank()) base else "$base $suffix"
            }
        }
        return "$base $candidate"
    }

    private fun appendTranslation(raw: String, callbackEvent: Long) {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) {
            subtitleFilteredEvents.incrementAndGet()
            logger.log(1, "SubtitlePipe", "Lọc callback=$callbackEvent reason=blank rawChars=${raw.length}")
            return
        }
        val previousRaw = lastRawTranscript
        val deltaKind: String
        val delta = when {
            previousRaw.isBlank() -> {
                deltaKind = "first"
                cleaned
            }
            cleaned.startsWith(previousRaw) -> {
                deltaKind = "cumulative"
                cleaned.removePrefix(previousRaw).trim()
            }
            previousRaw.startsWith(cleaned) -> {
                deltaKind = "older-prefix"
                ""
            }
            else -> {
                deltaKind = "independent"
                cleaned
            }
        }
        lastRawTranscript = cleaned
        if (delta.isBlank()) {
            subtitleFilteredEvents.incrementAndGet()
            logger.log(2, "SubtitlePipe", "Lọc callback=$callbackEvent reason=$deltaKind cleanedChars=${cleaned.length} previousRawChars=${previousRaw.length}")
            return
        }
        val beforeChars = _state.value.transcript.length
        subtitles.append(delta)
        updateState { current ->
            val separator = if (current.transcript.isBlank()) "" else " "
            val combined = (current.transcript + separator + delta)
                .takeLast(MAX_TRANSCRIPT_CHARS)
            current.copy(transcript = combined, status = "Đang dịch")
        }
        val applied = subtitleAppliedEvents.incrementAndGet()
        val afterState = _state.value
        logger.log(
            2,
            "SubtitlePipe",
            "Áp dụng callback=$callbackEvent applied=$applied kind=$deltaKind deltaChars=${delta.length} beforeChars=$beforeChars afterChars=${afterState.transcript.length} running=${afterState.running} paused=${afterState.paused}",
        )
        DiagnosticContext.updateAll(mapOf(
            "session.subtitleCallbacks" to subtitleCallbackEvents.get(),
            "session.subtitleApplied" to applied,
            "session.subtitleFiltered" to subtitleFilteredEvents.get(),
            "session.transcriptChars" to afterState.transcript.length,
        ))
        scheduleHistorySave("live-translation")
        if (!settings.aiVoice) queueTts(delta)
    }

    private fun handleConnectionError(error: Throwable) {
        logger.log(0, "Gemini", "Mất kết nối", error)
        val apiError = error as? GeminiLiveClient.GeminiApiException
        when (apiError?.code) {
            400, 401, 403 -> stopTranslation("Lỗi xác thực Gemini: hãy kiểm tra API Key và cấu hình")
            404 -> stopTranslation("Không tìm thấy model ${activeModelName()}")
            429 -> scheduleReconnect(60_000L, true, "Gemini đang giới hạn lưu lượng")
            else -> scheduleReconnect(null, true, error.message ?: "Mất kết nối")
        }
    }

    private fun scheduleTranscribeRotation(apiKey: String) {
        transcribeRotationJob?.cancel()
        transcribeRotationJob = serviceScope.launch {
            delay(TRANSCRIBE_LIVE_ROTATE_MS)
            if (!_state.value.running || !isTranscribeMode() || currentMode == SourceMode.FILE) return@launch
            logger.log(2, "TranscribeLive", "Xoay phiên trước giới hạn Live")
            finalizeLiveTranscriptionFallback("rotation")
            source?.pause()
            clearPendingInputForFreshSession()
            connectGemini(apiKey)
        }
    }

    private fun scheduleReconnect(delayOverride: Long?, countFailure: Boolean, reason: String) {
        if (!_state.value.running || reconnectJob?.isActive == true) return
        if (!settings.autoReconnect && countFailure) {
            stopTranslation("Mất kết nối và tự động kết nối lại đang tắt")
            return
        }
        if (countFailure) reconnectAttempts++
        if (reconnectAttempts > settings.reconnectMaxRetries) {
            stopTranslation("Đã hết ${settings.reconnectMaxRetries} lần thử kết nối lại")
            return
        }
        source?.pause()
        aiPlayer?.pause()
        originalPlayer?.pause()
        val delayMs = delayOverride ?: (2_000L * reconnectAttempts.coerceAtLeast(1))
        logger.log(1, "GeminiWS", "Lên lịch reconnect attempt=$reconnectAttempts delayMs=$delayMs countFailure=$countFailure reason=${reason.take(200)} resumeHandle=${!sessionResumptionHandle.isNullOrBlank()}")
        updateState {
            it.copy(
                setupComplete = false,
                status = "${reason.take(120)}; kết nối lại sau ${"%.1f".format(Locale.US, delayMs / 1_000f)} giây..."
            )
        }
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (_state.value.running) connectGemini(keyStore.load().selected.orEmpty())
        }
    }

    private fun resetAfterSeek() {
        subtitles.reset()
        lastRawTranscript = ""
        fileInputEnded = false
        fileFinishFallbackJob?.cancel()
        fileFinishFallbackJob = null
        clearPendingInputForFreshSession()
        while (originalQueue?.tryReceive()?.isSuccess == true) Unit
        aiPlayer?.flush()
        originalPlayer?.flush()
        ttsEngine?.stop()
        synchronized(ttsBuffer) { ttsBuffer.clear() }
        updateState { it.copy(transcript = "", status = "Đang tạo phiên Gemini mới sau khi tua...") }
        connectGemini(keyStore.load().selected.orEmpty())
    }

    private fun setupPlayers() {
        if (isTranscribeMode()) {
            logger.log(3, "AudioPlayer", "Bỏ qua output player trong chế độ chép lời")
            return
        }
        if (settings.aiVoice) aiPlayer = buildAiPlayer().also {
            it.start()
            it.setVolume(settings.translatedVolume)
            if (currentMode == SourceMode.FILE) it.setPlaybackSpeed(filePlaybackSpeed)
        }
        if (currentMode == SourceMode.FILE) {
            originalPlayer = StreamingPcmPlayer(
                sampleRate = 16_000,
                bufferBytes = 64_000,
                queueCapacity = 100,
                initialJitterChunks = 1,
                usage = AudioAttributes.USAGE_MEDIA,
                logger = logger,
                diagnosticName = "OriginalPlayer",
            ).also {
                it.start()
                it.setVolume(settings.originalVolume)
                it.setPlaybackSpeed(filePlaybackSpeed)
            }
            originalQueue = Channel(100, BufferOverflow.DROP_OLDEST)
            originalQueueJob = serviceScope.launch(Dispatchers.IO) {
                val queue = originalQueue ?: return@launch
                val first = queue.receiveCatching().getOrNull() ?: return@launch
                if (settings.fileSyncDelayMs > 0) delay(settings.fileSyncDelayMs.toLong())
                originalPlayer?.enqueue(first)
                while (isActive) {
                    val next = queue.receiveCatching().getOrNull() ?: break
                    originalPlayer?.enqueue(next)
                }
            }
        }
    }

    private fun buildAiPlayer(): StreamingPcmPlayer = StreamingPcmPlayer(
        sampleRate = 24_000,
        bufferBytes = settings.translatedBufferBytes,
        queueCapacity = settings.translatedQueueMax,
        initialJitterChunks = if (settings.qualityMode) settings.outputJitterTarget else 1,
        usage = when (settings.aiAudioStreamType) {
            "media" -> AudioAttributes.USAGE_MEDIA
            "voice_communication" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            "assistant" -> AudioAttributes.USAGE_ASSISTANT
            else -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        },
        logger = logger,
        diagnosticName = "TranslatedPlayer-${settings.aiAudioStreamType}",
    )

    private fun setupRecorders() {
        if (isTranscribeMode()) {
            logger.log(3, "Recorder", "Bỏ qua recorder đầu ra trong chế độ chép lời")
            return
        }
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
    }

    private fun recordOriginal(data: ByteArray) {
        originalWriter?.write(data)
        mixedWriter?.mix(data, 16_000, elapsedMs(), originalGain())
    }

    private fun closeRecorders() {
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
    }

    private fun ensureTtsInitialized() {
        if (ttsReady || ttsInitializing || settings.aiVoice) return
        ttsInitializing = true
        val engine = ttsEngine ?: RobustTtsEngine(applicationContext, logger).also { ttsEngine = it }
        logger.log(2, "TTS", "Bắt đầu dò engine TTS robust cho thiết bị ${Build.MANUFACTURER}/${Build.MODEL}")
        engine.initialize { success ->
            ttsInitializing = false
            ttsReady = success
            if (success) {
                logger.log(2, "TTS", "Robust TTS đã sẵn sàng engine=${engine.currentEngine() ?: "DEFAULT"}")
                if (!settings.aiVoice) flushTtsBuffer()
            } else {
                logger.log(1, "TTS", "Không tìm được engine TTS hoạt động; giữ văn bản trong buffer để thử lại")
            }
        }
    }

    private fun queueTts(text: String) {
        if (text.isBlank() || settings.aiVoice) return
        if (!ttsReady) ensureTtsInitialized()
        var flushNow = false
        synchronized(ttsBuffer) {
            if (ttsBuffer.isNotEmpty()) ttsBuffer.append(' ')
            ttsBuffer.append(text)
            val words = ttsBuffer.toString().trim().split(Regex("\\s+")).size
            flushNow = !settings.ttsSmoothEnabled || ttsBuffer.length >= settings.ttsSmoothMinChars ||
                words >= settings.ttsSmoothMinWords || text.lastOrNull() in listOf('.', '!', '?', '。', '！', '？')
        }
        ttsFlushJob?.cancel()
        if (flushNow) flushTtsBuffer() else {
            ttsFlushJob = serviceScope.launch {
                delay(settings.ttsSmoothTimeoutMs.toLong())
                flushTtsBuffer()
            }
        }
    }

    private fun flushTtsBuffer() {
        if (settings.aiVoice) return
        if (!ttsReady) {
            ensureTtsInitialized()
            return
        }
        val text = synchronized(ttsBuffer) {
            ttsBuffer.toString().also { ttsBuffer.clear() }
        }.trim()
        if (text.isBlank()) return
        val rate = if (currentMode == SourceMode.FILE) filePlaybackSpeed else 1f
        val spoken = ttsEngine?.speak(
            text = text,
            languageTag = settings.targetLanguage,
            rate = rate,
            pitch = 1f,
            volume = settings.translatedVolume.coerceIn(0, 100) / 100f,
            queue = true,
        ) == true
        if (spoken) {
            val estimatedMs = ((text.length * 65L) / rate.coerceAtLeast(0.5f)).toLong().coerceIn(500L, 8_000L)
            applyDucking(estimatedMs)
        } else {
            synchronized(ttsBuffer) {
                val pending = ttsBuffer.toString()
                ttsBuffer.clear()
                ttsBuffer.append(text)
                if (pending.isNotBlank()) ttsBuffer.append(' ').append(pending)
            }
            ttsReady = false
            logger.log(1, "TTS", "Engine từ chối speak; hoàn text chars=${text.length} về buffer và khởi tạo lại")
            ensureTtsInitialized()
        }
    }

    private fun applyDucking(durationMs: Long) {
        if (!settings.autoDucking || currentMode == SourceMode.MICROPHONE) return
        when (currentMode) {
            SourceMode.FILE -> originalPlayer?.setVolume(
                (settings.originalVolume * settings.duckVolumeFactor).roundToInt()
            )
            SourceMode.INTERNAL -> lowerSystemMediaVolume(settings.duckVolumeFactor)
            SourceMode.MICROPHONE -> Unit
        }
        duckRestoreJob?.cancel()
        duckRestoreJob = serviceScope.launch {
            delay(durationMs.coerceAtLeast(250L) + 300L)
            restoreDucking()
        }
    }

    private fun restoreDucking() {
        if (currentMode == SourceMode.FILE) originalPlayer?.setVolume(settings.originalVolume)
        if (currentMode == SourceMode.INTERNAL && !settings.muteOriginalInInternal) restoreSystemMediaVolume()
    }

    private fun applyInternalMuteIfNeeded() {
        if (currentMode == SourceMode.INTERNAL && settings.muteOriginalInInternal) lowerSystemMediaVolume(0f)
    }

    private fun lowerSystemMediaVolume(factor: Float) {
        val manager = getSystemService(AudioManager::class.java)
        if (savedMediaVolume == null) savedMediaVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val base = savedMediaVolume ?: return
        val target = (base * factor.coerceIn(0f, 1f)).roundToInt()
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    private fun restoreSystemMediaVolume() {
        val saved = savedMediaVolume ?: return
        val manager = getSystemService(AudioManager::class.java)
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        savedMediaVolume = null
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:translation").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            while (isActive && _state.value.running) {
                val elapsed = elapsedMs().coerceAtLeast(1)
                val inputKbps = totalInputBytes * 8f / elapsed
                val outputKbps = totalOutputBytes * 8f / elapsed
                val wire = client?.backpressureStats()
                val pending = inputSendQueue?.size ?: 0
                val wireKb = (wire?.queuedWireBytes ?: 0L) / 1_024L
                val pressureEvents = wire?.backpressureEvents ?: 0L
                if (pressureEvents > lastBackpressureEvents) {
                    logger.log(1, "GeminiWS", "Backpressure tăng events=$pressureEvents queuedWireBytes=${wire?.queuedWireBytes ?: 0L} maxObserved=${wire?.maxObservedWireBytes ?: 0L}")
                    lastBackpressureEvents = pressureEvents
                }
                DiagnosticContext.updateAll(mapOf(
                    "session.running" to true,
                    "session.status" to _state.value.status,
                    "session.inputKbps" to "%.1f".format(Locale.US, inputKbps),
                    "session.outputKbps" to "%.1f".format(Locale.US, outputKbps),
                    "session.inputQueue" to "$pending/$activeInputQueueCapacity",
                    "session.websocketQueuedBytes" to (wire?.queuedWireBytes ?: 0L),
                    "session.websocketMaxQueuedBytes" to (wire?.maxObservedWireBytes ?: 0L),
                    "session.backpressureEvents" to pressureEvents,
                    "session.droppedInputChunks" to droppedInputChunks.get(),
                    "session.resumedConnections" to resumedConnections.get(),
                    "session.goAwayCount" to goAwayCount.get(),
                    "session.subtitleCallbacks" to subtitleCallbackEvents.get(),
                    "session.subtitleApplied" to subtitleAppliedEvents.get(),
                    "session.subtitleFiltered" to subtitleFilteredEvents.get(),
                    "session.transcriptChars" to _state.value.transcript.length,
                    "session.filePlaybackSpeed" to filePlaybackSpeed,
                    "session.ttsReady" to ttsReady,
                    "session.ttsEngine" to (ttsEngine?.currentEngine() ?: ""),
                ))
                updateState {
                    it.copy(
                        health = "Vào: %.1f kb/s | Ra: %.1f kb/s | Gửi: %d/%d | WS: %d KB | Drop: %d | Resume: %d | GoAway: %d | Sub: %d | Speed: %sx | %s".format(
                            Locale.US,
                            inputKbps,
                            outputKbps,
                            pending,
                            activeInputQueueCapacity.takeIf { capacity -> capacity > 0 } ?: settings.pacingMaxBuffer,
                            wireKb,
                            droppedInputChunks.get(),
                            resumedConnections.get(),
                            goAwayCount.get(),
                            it.transcript.length,
                            formatFileSpeed(),
                            if (it.setupComplete) "OK" else "đang nối"
                        ) + if (pressureEvents > 0) " | BP: $pressureEvents" else ""
                    )
                }
                delay(1_000)
            }
        }
    }

    private fun updateError(message: String) {
        logger.log(0, "UI", message)
        _state.update { it.copy(status = message, lastError = message) }
    }

    private fun updateState(block: (SessionUiState) -> SessionUiState) {
        _state.update(block)
        if (_state.value.running) notificationController.update(_state.value)
    }

    private fun isTranscribeMode(): Boolean =
        processingMode == AppPreferences.PROCESSING_MODE_TRANSCRIBE

    private fun activeModelName(): String = if (isTranscribeMode()) {
        if (currentMode == SourceMode.FILE) AppPreferences.TRANSCRIBE_FILE_MODEL
        else AppPreferences.TRANSCRIBE_LIVE_MODEL
    } else {
        settings.model
    }

    private fun elapsedMs(): Long = (SystemClock.elapsedRealtime() - sessionStartedAt).coerceAtLeast(0)
    private fun originalGain(): Float = settings.originalVolume.coerceIn(0, 100) / 100f
    private fun translatedGain(): Float = settings.translatedVolume.coerceIn(0, 100) / 100f
    private fun formatFileSpeed(): String = String.format(Locale.US, "%.1f", filePlaybackSpeed)

    override fun onDestroy() {
        if (_state.value.running) stopTranslation("Dịch vụ đã dừng")
        historySaveJob?.cancel()
        historySaveJob = null
        saveCurrentHistoryNow("service-destroy")
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsReady = false
        ttsInitializing = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TRANSCRIBE_LIVE_ROTATE_MS = 9L * 60L * 1_000L
        private const val MAX_TRANSCRIBE_FILE_DURATION_MS = 30L * 60L * 1_000L
        private const val HISTORY_SAVE_DEBOUNCE_MS = 750L
        const val ACTION_PAUSE = "com.oai.geminilivetranslate.PAUSE"
        const val ACTION_RESUME = "com.oai.geminilivetranslate.RESUME"
        const val ACTION_STOP = "com.oai.geminilivetranslate.STOP"
        const val ACTION_APPLY_SETTINGS = "com.oai.geminilivetranslate.APPLY_SETTINGS"
        const val ACTION_REFRESH_API_KEY = "com.oai.geminilivetranslate.REFRESH_API_KEY"
        private const val MAX_TRANSCRIPT_CHARS = 20_000
    }
}
