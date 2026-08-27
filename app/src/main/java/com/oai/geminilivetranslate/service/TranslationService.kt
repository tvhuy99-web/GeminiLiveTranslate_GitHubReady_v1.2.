package com.oai.geminilivetranslate.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.AppSettings
import com.oai.geminilivetranslate.core.DiagnosticContext
import com.oai.geminilivetranslate.core.LanguageCatalog
import com.oai.geminilivetranslate.core.PublicRecordingStore
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SessionUiState
import com.oai.geminilivetranslate.core.SettingsPolicy
import com.oai.geminilivetranslate.core.SourceMode
import com.oai.geminilivetranslate.core.SubtitleStore
import com.oai.geminilivetranslate.core.TimelineWavMixer
import com.oai.geminilivetranslate.core.WavWriter
import com.oai.geminilivetranslate.network.GeminiFileTranscribeClient
import com.oai.geminilivetranslate.network.GeminiLiveClient
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
    private val subtitles = SubtitleStore()

    @Volatile private var settings = AppSettings()
    @Volatile private var client: GeminiLiveClient? = null
    @Volatile private var source: AudioSource? = null
    private var sourceJob: Job? = null
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
    private var selectedUri: Uri? = null
    private var selectedFileName: String? = null
    private var currentMode = SourceMode.FILE
    @Volatile private var processingMode = AppPreferences.PROCESSING_MODE_TRANSLATE
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
        settings = preferences.load()
        processingMode = preferences.loadProcessingMode()
        speakerDiarization = preferences.loadSpeakerDiarization()
        _state.update {
            it.copy(
                aiVoice = settings.aiVoice,
                currentLanguage = settings.targetLanguage,
                sourceMode = currentMode
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
            ACTION_STOP -> stopTranslation("Đã dừng dịch")
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
        currentMode = mode
        _state.update { it.copy(sourceMode = mode) }
    }

    fun setProcessingMode(value: String) {
        if (_state.value.running) return
        processingMode = if (value == AppPreferences.PROCESSING_MODE_TRANSCRIBE) {
            AppPreferences.PROCESSING_MODE_TRANSCRIBE
        } else {
            AppPreferences.PROCESSING_MODE_TRANSLATE
        }
        preferences.setProcessingMode(processingMode)
    }

    fun setSpeakerDiarization(enabled: Boolean) {
        if (_state.value.running) return
        speakerDiarization = enabled
        preferences.setSpeakerDiarization(enabled)
    }

    fun setSelectedFile(uri: Uri, name: String?) {
        if (_state.value.running && currentMode == SourceMode.FILE) stopTranslation("Đã dừng do đổi tệp")
        selectedUri = uri
        selectedFileName = name ?: uri.lastPathSegment
        _state.update { it.copy(selectedUri = uri, selectedFileName = selectedFileName) }
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
        speakerDiarization = preferences.loadSpeakerDiarization()
        currentMode = mode
        val apiKey = keyStore.load().selected
        if (apiKey.isNullOrBlank()) {
            updateError("Chưa có API Key")
            return
        }
        if (mode == SourceMode.FILE && selectedUri == null) {
            updateError("Chưa chọn tệp âm thanh/video")
            return
        }
        if (mode == SourceMode.INTERNAL && (projectionResultCode == null || projectionData == null)) {
            updateError("Chưa cấp quyền thu âm thanh nội bộ")
            return
        }
        val projectionCode = projectionResultCode
        val projectionIntent = projectionData

        resetSessionState()
        sessionStartedAt = SystemClock.elapsedRealtime()
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8)
        DiagnosticContext.clearSession()
        DiagnosticContext.updateAll(mapOf(
            "session.id" to sessionId,
            "session.source" to mode.name,
            "session.model" to activeModelName(),
            "session.processingMode" to processingMode,
            "session.speakerDiarization" to speakerDiarization,
            "session.targetLanguage" to settings.targetLanguage,
            "session.filePlaybackSpeed" to filePlaybackSpeed,
            "session.startedAt" to Date().toString(),
        ))
        subtitles.reset()
        val initialState = _state.value.copy(
            status = "Đang khởi động phiên dịch...",
            health = "",
            running = true,
            paused = false,
            setupComplete = false,
            sourceMode = mode,
            selectedUri = selectedUri,
            selectedFileName = selectedFileName,
            transcript = "",
            progressPercent = 0,
            canSeek = mode == SourceMode.FILE && !isTranscribeMode(),
            aiVoice = settings.aiVoice && !isTranscribeMode(),
            currentLanguage = settings.targetLanguage,
            lastError = null,
        )
        _state.value = initialState
        setupInputSender()
        notificationController.start(this, initialState)

        if (isTranscribeMode() && mode == SourceMode.FILE) {
            runCatching { acquireWakeLock() }.onFailure {
                logger.log(0, "TranscribeFile", "Không tạo được wake lock", it)
            }
            startFileTranscription(apiKey)
            return
        }

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

    fun stopTranslation(message: String = "Đã dừng dịch") {
        if (!stopping.compareAndSet(false, true)) return
        val hadActiveSession = _state.value.running || sessionId.isNotBlank()
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
        updateState { it.copy(paused = false, status = "Đang dịch") }
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

    fun subtitleText(format: String = preferences.load().exportFormat): String =
        if (format == "txt") {
            transcribePlainText.takeIf { isTranscribeMode() && it.isNotBlank() } ?: subtitles.plainText()
        } else {
            subtitles.srtText()
        }

    fun logText(): String = logger.text()

    private fun resetSessionState() {
        reconnectAttempts = 0
        sourceStarted = false
        fileInputEnded = false
        lastRawTranscript = ""
        transcribePlainText = ""
        transcribeRotationJob?.cancel()
        transcribeRotationJob = null
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
                    logger.log(3, "TranscribeLive", "interim chars=${text.length}")
                }

                override fun onTurnComplete() {
                    if (generation != connectionGeneration) return
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

    private data class TranscribeChunk(
        val file: File,
        val offsetMs: Long,
        val durationMs: Long,
    )

    private fun startFileTranscription(apiKey: String) {
        sourceJob?.cancel()
        sourceJob = serviceScope.launch(Dispatchers.IO) {
            val workDir = File(cacheDir, "transcribe-$sessionId").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                updateState { it.copy(status = "Đang chuẩn bị âm thanh...", progressPercent = 0) }
                val chunks = prepareTranscribeChunks(selectedUri ?: error("Chưa chọn tệp"), workDir)
                if (chunks.isEmpty()) error("Không đọc được âm thanh trong tệp")
                val fileClient = GeminiFileTranscribeClient(apiKey, logger)
                val words = ArrayList<GeminiFileTranscribeClient.WordInfo>()
                val textParts = ArrayList<String>()
                try {
                    chunks.forEachIndexed { index, chunk ->
                        if (!_state.value.running) return@launch
                        val base = (20 + index * 80 / chunks.size).coerceIn(20, 95)
                        val span = (80 / chunks.size).coerceAtLeast(1)
                        val result = fileClient.transcribe(
                            file = chunk.file,
                            speakerDiarization = speakerDiarization,
                        ) { status, percent ->
                            val overall = (base + percent * span / 100).coerceIn(20, 98)
                            updateState { it.copy(status = status, progressPercent = overall) }
                        }
                        result.text.trim().takeIf(String::isNotBlank)?.let(textParts::add)
                        result.words.forEach { word ->
                            words += word.copy(
                                startMs = word.startMs + chunk.offsetMs,
                                endMs = word.endMs + chunk.offsetMs,
                            )
                        }
                    }
                } finally {
                    fileClient.close()
                }

                val cues = buildTranscriptionCues(words, speakerDiarization)
                if (cues.isNotEmpty()) {
                    subtitles.replaceTimed(cues)
                } else {
                    subtitles.reset()
                    chunks.zip(textParts).forEach { (chunk, text) ->
                        subtitles.appendTimed(
                            text,
                            chunk.offsetMs,
                            chunk.offsetMs + chunk.durationMs.coerceAtLeast(1_000L),
                        )
                    }
                }
                transcribePlainText = if (speakerDiarization) {
                    subtitles.plainText()
                } else {
                    textParts.joinToString("\n").trim().ifBlank { subtitles.plainText() }
                }
                fileInputEnded = true
                updateState {
                    it.copy(
                        transcript = transcribePlainText.takeLast(MAX_TRANSCRIPT_CHARS),
                        status = "Đã hoàn tất chép lời",
                        progressPercent = 100,
                    )
                }
                stopTranslation("Đã hoàn tất chép lời")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) return@launch
                logger.log(0, "TranscribeFile", "Chép lời tệp thất bại", error)
                if (_state.value.running) {
                    stopTranslation("Lỗi chép lời: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                source = null
                workDir.deleteRecursively()
            }
        }
    }

    private suspend fun prepareTranscribeChunks(uri: Uri, workDir: File): List<TranscribeChunk> {
        val files = ArrayList<File>()
        val durations = ArrayList<Long>()
        var writer: WavWriter? = null
        var currentFile: File? = null
        var chunkBytes = 0L
        var sourceError: Throwable? = null

        fun openChunk() {
            val file = File(workDir, "part-${files.size + 1}.wav")
            currentFile = file
            writer = WavWriter(file, 16_000)
            chunkBytes = 0L
        }

        fun closeChunk() {
            val activeWriter = writer ?: return
            activeWriter.close()
            writer = null
            val file = currentFile
            currentFile = null
            if (file != null && chunkBytes > 0L) {
                files += file
                durations += chunkBytes / PCM_BYTES_PER_MS
            } else {
                file?.delete()
            }
            chunkBytes = 0L
        }

        openChunk()
        val decoder = FileAudioSource(
            context = this,
            uri = uri,
            pacingEnabled = false,
            leadMs = 0,
            initialPlaybackSpeed = 1f,
            logger = logger,
        )
        source = decoder
        decoder.run(object : AudioSource.Listener {
            override fun onPcm16Mono16k(data: ByteArray) {
                if (!_state.value.running || data.isEmpty()) return
                if (chunkBytes > 0L && chunkBytes + data.size > MAX_TRANSCRIBE_CHUNK_PCM_BYTES) {
                    closeChunk()
                    openChunk()
                }
                writer?.write(data)
                chunkBytes += data.size
                totalInputBytes += data.size
            }

            override fun onProgress(percent: Int, positionMs: Long, durationMs: Long) {
                updateState {
                    it.copy(
                        status = "Đang chuẩn bị âm thanh...",
                        progressPercent = (percent.coerceIn(0, 100) / 5).coerceIn(0, 20),
                    )
                }
            }

            override fun onCompleted() = Unit

            override fun onError(error: Throwable) {
                sourceError = error
            }
        })
        closeChunk()
        sourceError?.let { throw it }

        var runningOffset = 0L
        return files.mapIndexed { index, file ->
            val duration = durations[index]
            TranscribeChunk(file, runningOffset, duration).also {
                runningOffset += duration
            }
        }
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
        subtitles.append(cleaned)
        updateState { current ->
            val separator = if (current.transcript.isBlank()) "" else " "
            current.copy(
                transcript = (current.transcript + separator + cleaned).takeLast(MAX_TRANSCRIPT_CHARS),
                status = "Đang chép lời...",
            )
        }
        DiagnosticContext.put("session.transcriptChars", _state.value.transcript.length)
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
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsReady = false
        ttsInitializing = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TRANSCRIBE_LIVE_ROTATE_MS = 9L * 60L * 1_000L
        private const val PCM_BYTES_PER_MS = 32L
        private const val MAX_TRANSCRIBE_CHUNK_PCM_BYTES = 16_000L * 2L * 25L * 60L
        const val ACTION_PAUSE = "com.oai.geminilivetranslate.PAUSE"
        const val ACTION_RESUME = "com.oai.geminilivetranslate.RESUME"
        const val ACTION_STOP = "com.oai.geminilivetranslate.STOP"
        const val ACTION_APPLY_SETTINGS = "com.oai.geminilivetranslate.APPLY_SETTINGS"
        const val ACTION_REFRESH_API_KEY = "com.oai.geminilivetranslate.REFRESH_API_KEY"
        private const val MAX_TRANSCRIPT_CHARS = 20_000
    }
}
