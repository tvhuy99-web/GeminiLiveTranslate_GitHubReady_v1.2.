package com.oai.geminilivetranslate.network

import android.content.Context
import com.oai.geminilivetranslate.core.AiConnectionModeStore
import com.oai.geminilivetranslate.core.SessionLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Connection-mode aware facade for real-time visual description.
 *
 * API Key mode keeps the proven native Gemini Live WebSocket path. AI Studio mode reuses the
 * authenticated /live WebView session and injects the newest JPEG into that session's existing
 * realtime carrier. There is intentionally no automatic fallback between the two backends.
 */
internal class ScreenDescriptionLiveClient(
    context: Context,
    private val apiKey: String?,
    private val outputLanguageCode: String,
    private val outputLanguageDisplay: String,
    private val logger: SessionLogger,
    private val listener: Listener,
    private val systemPrompt: String? = null,
) {
    interface Listener {
        fun onSetupComplete()
        fun onAudio(pcm24kMono: ByteArray)
        fun onTranscript(text: String)
        fun onTurnComplete() = Unit
        fun onInterrupted() = Unit
        fun onError(error: Throwable)
        fun onClosed(reason: String)
    }

    enum class SendResult {
        SENT,
        NOT_READY,
        BACKPRESSURED,
        CLOSED,
        FAILED,
    }

    private val appContext = context.applicationContext
    private val connectionMode = AiConnectionModeStore(appContext).load()
    private val closed = AtomicBoolean(false)
    private val studioSetupComplete = AtomicBoolean(false)
    private val studioAcceptedFrames = AtomicLong(0L)
    private val studioSubmittedFrames = AtomicLong(0L)
    private val studioBackpressuredFrames = AtomicLong(0L)
    private val studioTurnCompletes = AtomicLong(0L)

    @Volatile private var apiBackend: GeminiScreenDescriptionLiveClient? = null
    @Volatile private var studioBackend: AiStudioWebRealtimeClient? = null

    val backendName: String
        get() = if (connectionMode == AiConnectionModeStore.MODE_AI_STUDIO) "ai_studio" else "api_key"

    fun connect() {
        if (closed.get()) return
        logger.log(
            2,
            TAG,
            "ROUTE connectionMode=$connectionMode backend=$backendName model=${GeminiScreenDescriptionLiveClient.MODEL} " +
                "micInput=false apiFallback=false",
        )
        if (connectionMode == AiConnectionModeStore.MODE_AI_STUDIO) connectAiStudio() else connectApi()
    }

    fun sendVideoFrame(jpeg: ByteArray): SendResult {
        if (closed.get()) return SendResult.CLOSED
        return if (connectionMode == AiConnectionModeStore.MODE_AI_STUDIO) {
            sendStudioVideoFrame(jpeg)
        } else {
            when (apiBackend?.sendVideoFrame(jpeg)) {
                GeminiScreenDescriptionLiveClient.SendResult.SENT -> SendResult.SENT
                GeminiScreenDescriptionLiveClient.SendResult.NOT_READY -> SendResult.NOT_READY
                GeminiScreenDescriptionLiveClient.SendResult.BACKPRESSURED -> SendResult.BACKPRESSURED
                GeminiScreenDescriptionLiveClient.SendResult.CLOSED -> SendResult.CLOSED
                GeminiScreenDescriptionLiveClient.SendResult.FAILED -> SendResult.FAILED
                null -> SendResult.NOT_READY
            }
        }
    }

    fun close(graceful: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        studioSetupComplete.set(false)
        apiBackend?.close(graceful)
        apiBackend = null
        studioBackend?.close(graceful)
        studioBackend = null
        if (connectionMode == AiConnectionModeStore.MODE_AI_STUDIO) {
            logger.log(
                2,
                TAG,
                "CLOSE backend=ai_studio acceptedFrames=${studioAcceptedFrames.get()} " +
                    "submittedFrames=${studioSubmittedFrames.get()} backpressured=${studioBackpressuredFrames.get()} " +
                    "turnCompletes=${studioTurnCompletes.get()} micInput=false",
            )
        }
    }

    private fun connectApi() {
        val key = apiKey?.takeIf(String::isNotBlank)
        if (key == null) {
            listener.onError(IllegalStateException("GEMINI_API_KEY_REQUIRED"))
            return
        }
        val backend = GeminiScreenDescriptionLiveClient(
            apiKey = key,
            outputLanguage = outputLanguageDisplay,
            logger = logger,
            listener = object : GeminiScreenDescriptionLiveClient.Listener {
                override fun onSetupComplete() = listener.onSetupComplete()
                override fun onAudio(pcm24kMono: ByteArray) = listener.onAudio(pcm24kMono)
                override fun onTranscript(text: String) = listener.onTranscript(text)
                override fun onTurnComplete() = listener.onTurnComplete()
                override fun onInterrupted() = listener.onInterrupted()
                override fun onError(error: Throwable) = listener.onError(error)
                override fun onClosed(reason: String) = listener.onClosed(reason)
            },
            systemPrompt = systemPrompt,
        )
        apiBackend = backend
        backend.connect()
    }

    private fun connectAiStudio() {
        val resolvedPrompt = systemPrompt
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: GeminiScreenDescriptionLiveClient.systemInstruction(outputLanguageDisplay)
        val backend = AiStudioWebRealtimeClient(
            targetLanguage = outputLanguageCode,
            operationMode = GeminiLiveClient.OperationMode.TRANSLATE,
            logger = logger,
            listener = object : GeminiLiveClient.Listener {
                override fun onOpen() = Unit

                override fun onSetupComplete() {
                    if (closed.get()) return
                    studioSetupComplete.set(true)
                    logger.log(
                        2,
                        TAG,
                        "AI_STUDIO_SETUP_COMPLETE model=${GeminiScreenDescriptionLiveClient.MODEL} " +
                            "promptChars=${resolvedPrompt.length} micInput=false frameFlow=continuous-latest",
                    )
                    listener.onSetupComplete()
                }

                override fun onText(text: String) {
                    if (text.isNotBlank()) listener.onTranscript(text)
                }

                override fun onAudio(pcm24kMono: ByteArray) = listener.onAudio(pcm24kMono)

                override fun onTurnComplete() {
                    studioTurnCompletes.incrementAndGet()
                    listener.onTurnComplete()
                }

                override fun onInterrupted() {
                    listener.onInterrupted()
                }

                override fun onError(error: Throwable) = listener.onError(error)

                override fun onClosed(reason: String) = listener.onClosed(reason)
            },
            maxQueuedWireBytes = DEFAULT_MAX_QUEUED_WIRE_BYTES,
            screenDescription = true,
            screenDescriptionModel = GeminiScreenDescriptionLiveClient.MODEL,
            screenDescriptionPrompt = resolvedPrompt,
        )
        studioBackend = backend
        backend.connect()
    }

    private fun sendStudioVideoFrame(jpeg: ByteArray): SendResult {
        if (jpeg.isEmpty()) return SendResult.SENT
        studioAcceptedFrames.incrementAndGet()
        if (!studioSetupComplete.get()) return SendResult.NOT_READY

        val result = when (studioBackend?.sendVideoFrame(jpeg)) {
            GeminiLiveClient.SendResult.SENT -> SendResult.SENT
            GeminiLiveClient.SendResult.NOT_READY -> SendResult.NOT_READY
            GeminiLiveClient.SendResult.BACKPRESSURED -> SendResult.BACKPRESSURED
            GeminiLiveClient.SendResult.CLOSED -> SendResult.CLOSED
            GeminiLiveClient.SendResult.FAILED -> SendResult.FAILED
            null -> SendResult.NOT_READY
        }

        when (result) {
            SendResult.SENT -> {
                val sent = studioSubmittedFrames.incrementAndGet()
                if (sent == 1L || sent % 20L == 0L) {
                    logger.log(
                        3,
                        TAG,
                        "AI_STUDIO_VIDEO_SUBMITTED count=$sent accepted=${studioAcceptedFrames.get()} jpegBytes=${jpeg.size}",
                    )
                }
            }
            SendResult.BACKPRESSURED -> studioBackpressuredFrames.incrementAndGet()
            else -> Unit
        }
        return result
    }

    companion object {
        private const val TAG = "ScreenDescriptionRoute"
        private const val DEFAULT_MAX_QUEUED_WIRE_BYTES = 512L * 1024L
    }
}
