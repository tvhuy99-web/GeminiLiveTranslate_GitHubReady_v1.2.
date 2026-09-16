package com.oai.geminilivetranslate.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.oai.geminilivetranslate.MainActivity
import com.oai.geminilivetranslate.R
import com.oai.geminilivetranslate.audio.StreamingPcmPlayer
import com.oai.geminilivetranslate.core.AiConnectionModeStore
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.LanguageCatalog
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.network.GeminiScreenDescriptionLiveClient
import com.oai.geminilivetranslate.network.ScreenDescriptionLiveClient
import com.oai.geminilivetranslate.video.ScreenFrameCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground owner for a visual-only Gemini Live screen-description session. */
class LiveVideoDescriptionService : Service() {
    private lateinit var preferences: AppPreferences
    private lateinit var logger: SessionLogger
    private var mediaProjection: MediaProjection? = null
    private var frameCapture: ScreenFrameCapture? = null
    private var liveClient: ScreenDescriptionLiveClient? = null
    private var outputPlayer: StreamingPcmPlayer? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val stopping = AtomicBoolean(false)
    private val transcript = StringBuilder()
    private var lastTranscriptChunk = ""

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        logger = SessionLogger(this, preferences)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession("Đã dừng mô tả thời gian thực")
            ACTION_START -> startSession(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession("Đã dừng mô tả thời gian thực", stopSelfAfter = false)
        super.onDestroy()
    }

    private fun startSession(intent: Intent) {
        if (uiState.value.running) return
        stopping.set(false)
        transcript.setLength(0)
        lastTranscriptChunk = ""
        publish("Đang khởi động...", running = true, transcriptText = "", error = null)
        startForegroundNow("Đang khởi động...")

        val connectionMode = AiConnectionModeStore(this).load()
        val apiKey = if (connectionMode == AiConnectionModeStore.MODE_API_KEY) {
            val keyState = ApiKeyStore(this).load()
            keyState.selected?.takeIf { it in keyState.keys } ?: keyState.keys.firstOrNull()
        } else {
            null
        }
        if (connectionMode == AiConnectionModeStore.MODE_API_KEY && apiKey.isNullOrBlank()) {
            failSession("Chưa có Gemini API Key")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
        val projectionData = projectionIntent(intent)
        if (resultCode == Int.MIN_VALUE || projectionData == null) {
            failSession("Thiếu quyền chia sẻ màn hình")
            return
        }

        val projection = runCatching {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, projectionData)
        }.getOrElse {
            failSession("Không tạo được phiên chia sẻ màn hình: ${it.message ?: it.javaClass.simpleName}", it)
            return
        } ?: run {
            failSession("Android không trả về phiên chia sẻ màn hình")
            return
        }
        mediaProjection = projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping.get()) stopSession("Quyền chia sẻ màn hình đã kết thúc")
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, null)

        val settings = preferences.load()
        val customPrompt = preferences.loadLiveDescriptionPrompt()
            .trim()
            .takeIf(String::isNotBlank)
        val player = StreamingPcmPlayer(
            sampleRate = 24_000,
            bufferBytes = settings.translatedBufferBytes,
            queueCapacity = settings.translatedQueueMax,
            initialJitterChunks = if (settings.qualityMode) settings.outputJitterTarget else 1,
            logger = logger,
            diagnosticName = "TranslatedPlayer-${settings.aiAudioStreamType}",
        )
        runCatching {
            player.setVolume(settings.translatedVolume)
            player.start()
        }.onFailure {
            failSession("Không khởi tạo được audio output: ${it.message ?: it.javaClass.simpleName}", it)
            return
        }
        outputPlayer = player

        val capture = ScreenFrameCapture(
            context = this,
            mediaProjection = projection,
            logger = logger,
        ) { jpeg ->
            when (liveClient?.sendVideoFrame(jpeg)) {
                ScreenDescriptionLiveClient.SendResult.FAILED,
                ScreenDescriptionLiveClient.SendResult.CLOSED -> {
                    logger.log(1, TAG, "Frame không gửi được vì Live socket đã đóng")
                }
                ScreenDescriptionLiveClient.SendResult.BACKPRESSURED -> {
                    logger.log(3, TAG, "Bỏ frame hiện tại do backpressure; không xếp hàng frame cũ")
                }
                else -> Unit
            }
        }
        frameCapture = capture

        val outputLanguage = LanguageCatalog.displayName(settings.targetLanguage)
        val client = ScreenDescriptionLiveClient(
            context = this,
            apiKey = apiKey,
            outputLanguageCode = settings.targetLanguage,
            outputLanguageDisplay = outputLanguage,
            logger = logger,
            listener = object : ScreenDescriptionLiveClient.Listener {
                override fun onSetupComplete() {
                    if (stopping.get()) return
                    runCatching { capture.start() }
                        .onSuccess {
                            publish("Đang mô tả thời gian thực", running = true)
                            logger.log(
                                2,
                                TAG,
                                "LIVE_READY model=${GeminiScreenDescriptionLiveClient.MODEL} screen=true micInput=false " +
                                    "audioOutput=true language=$outputLanguage connectionMode=$connectionMode backend=${client.backendName} " +
                                        "promptSource=${if (customPrompt == null) "default" else "custom"} " +
                                    "promptChars=${customPrompt?.length ?: 0}",
                            )
                        }
                        .onFailure {
                            failSession("Không bắt đầu được screen capture: ${it.message ?: it.javaClass.simpleName}", it)
                        }
                }

                override fun onAudio(pcm24kMono: ByteArray) {
                    if (!stopping.get()) outputPlayer?.enqueue(pcm24kMono)
                }

                override fun onTranscript(text: String) {
                    appendTranscript(text)
                }

                override fun onTurnComplete() {
                    logger.log(3, TAG, "Model turn complete; tiếp tục quan sát màn hình")
                }

                override fun onInterrupted() {
                    outputPlayer?.flush()
                    logger.log(3, TAG, "Model output bị ngắt bởi cập nhật Live; đã xả audio output cũ")
                }

                override fun onError(error: Throwable) {
                    failSession("Lỗi Gemini Live: ${error.message ?: error.javaClass.simpleName}", error)
                }

                override fun onClosed(reason: String) {
                    if (!stopping.get()) failSession("Gemini Live đã đóng: $reason")
                }
            },
            systemPrompt = customPrompt,
        )
        liveClient = client
        logger.log(
            2,
            TAG,
            "START model=${GeminiScreenDescriptionLiveClient.MODEL} input=screen-jpeg maxFps=1 micInput=false " +
                "audioOutput=true connectionMode=$connectionMode backend=${client.backendName} " +
                    "promptSource=${if (customPrompt == null) "default" else "custom"} " +
                "promptChars=${customPrompt?.length ?: 0}",
        )
        client.connect()
    }

    private fun appendTranscript(raw: String) {
        if (stopping.get()) return
        val text = raw.trim()
        if (text.isBlank() || text == lastTranscriptChunk) return
        lastTranscriptChunk = text
        if (transcript.isNotEmpty()) transcript.append('\n')
        transcript.append(text)
        if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            transcript.delete(0, transcript.length - MAX_TRANSCRIPT_CHARS)
        }
        publish(
            "Đang mô tả thời gian thực",
            running = true,
            transcriptText = transcript.toString(),
        )
    }

    private fun failSession(message: String, error: Throwable? = null) {
        logger.log(0, TAG, message, error)
        publish(message, running = false, error = message)
        stopSession(message, preserveError = true)
    }

    private fun stopSession(
        message: String,
        stopSelfAfter: Boolean = true,
        preserveError: Boolean = false,
    ) {
        if (!stopping.compareAndSet(false, true)) {
            if (stopSelfAfter) stopSelf()
            return
        }

        runCatching { frameCapture?.close() }
        frameCapture = null
        runCatching { liveClient?.close() }
        liveClient = null
        runCatching { outputPlayer?.stop() }
        outputPlayer = null

        val projection = mediaProjection
        val callback = projectionCallback
        projectionCallback = null
        mediaProjection = null
        if (projection != null && callback != null) runCatching { projection.unregisterCallback(callback) }
        runCatching { projection?.stop() }

        val current = uiState.value
        publish(
            message,
            running = false,
            transcriptText = current.transcript,
            error = if (preserveError) current.lastError else null,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        logger.log(2, TAG, "STOP reason=${message.take(240)} micInput=false")
        if (stopSelfAfter) stopSelf()
    }

    private fun publish(
        status: String,
        running: Boolean,
        transcriptText: String = uiState.value.transcript,
        error: String? = uiState.value.lastError,
    ) {
        _uiState.value = UiState(
            status = status,
            running = running,
            transcript = transcriptText,
            lastError = error,
        )
        if (running) updateNotification(status)
    }

    private fun startForegroundNow(status: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_app)
        .setContentTitle("Mô tả thời gian thực")
        .setContentText(status)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                4101,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "Dừng",
            PendingIntent.getService(
                this,
                4102,
                Intent(this, LiveVideoDescriptionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mô tả thời gian thực",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
    }

    data class UiState(
        val status: String = "Sẵn sàng",
        val running: Boolean = false,
        val transcript: String = "",
        val lastError: String? = null,
    )

    companion object {
        const val ACTION_START = "com.oai.geminilivetranslate.action.START_LIVE_VIDEO_DESCRIPTION"
        const val ACTION_STOP = "com.oai.geminilivetranslate.action.STOP_LIVE_VIDEO_DESCRIPTION"
        const val EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        private const val TAG = "LiveVideoDescription"
        private const val CHANNEL_ID = "gemini_live_screen_description"
        private const val NOTIFICATION_ID = 23038
        private const val MAX_TRANSCRIPT_CHARS = 24_000

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    }
}
