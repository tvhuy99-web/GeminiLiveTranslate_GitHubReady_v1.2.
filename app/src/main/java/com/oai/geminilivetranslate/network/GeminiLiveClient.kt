package com.oai.geminilivetranslate.network

import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.AiStudioLiveBackendPolicy
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stable Live client facade used by TranslationService.
 *
 * R17 deliberately preserves this public surface so the mature service queue/player/history logic
 * does not need a broad rewrite. The facade prefers the authenticated AI Studio Web Session on the
 * experiment branch and falls back to the original API-key WebSocket backend when a real API key
 * exists and the Web Session cannot bootstrap.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val targetLanguage: String,
    private val echoTargetLanguage: Boolean,
    private val logger: SessionLogger,
    private val listener: Listener,
    private val resumeHandle: String? = null,
    private val maxQueuedWireBytes: Long = DEFAULT_MAX_QUEUED_WIRE_BYTES,
    private val operationMode: OperationMode = OperationMode.TRANSLATE,
) {
    enum class OperationMode { TRANSLATE, TRANSCRIBE }

    interface Listener {
        fun onOpen() = Unit
        fun onSetupComplete()
        fun onText(text: String)
        fun onAudio(pcm24kMono: ByteArray)
        fun onInputTranscript(text: String) = Unit
        fun onInterimTranscript(text: String) = Unit
        fun onTurnComplete() = Unit
        fun onInterrupted() = Unit
        fun onSessionResumptionUpdate(resumable: Boolean, newHandle: String?) = Unit
        fun onGoAway(timeLeft: String?) = Unit
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

    data class BackpressureStats(
        val queuedWireBytes: Long,
        val maxObservedWireBytes: Long,
        val backpressureEvents: Long,
    )

    data class ResponseStats(
        val serverContentEvents: Long,
        val inputTranscriptEvents: Long,
        val outputTranscriptionObjects: Long,
        val outputTextEvents: Long,
        val modelTextEvents: Long,
        val textDispatchEvents: Long,
        val audioChunks: Long,
        val audioBytes: Long,
        val turnCompleteEvents: Long,
    )

    private val closed = AtomicBoolean(false)
    private val appContext = GeminiTranslateApp.requireAppContext()
    @Volatile private var apiBackend: GeminiApiLiveClient? = null
    @Volatile private var webBackend: AiStudioWebRealtimeClient? = null
    @Volatile private var aiStudioSetupComplete = false
    @Volatile private var backendName = "none"

    fun connect() {
        if (closed.get()) return
        val hasRealApiKey = hasRealApiKey()
        val configuredForWeb = AiStudioLiveBackendPolicy.configuredToPreferAiStudio(appContext)
        val preferWeb = AiStudioLiveBackendPolicy.preferAiStudio(appContext) ||
            (!hasRealApiKey && configuredForWeb)
        when {
            preferWeb -> connectAiStudio()
            hasRealApiKey -> connectApi()
            else -> listener.onError(IllegalStateException("AI_STUDIO_BACKEND_UNAVAILABLE_AND_NO_API_KEY"))
        }
    }

    fun sendAudio(pcm16kMono: ByteArray): SendResult = when (backendName) {
        "aistudio" -> webBackend?.sendAudio(pcm16kMono) ?: SendResult.NOT_READY
        "api" -> apiBackend?.sendAudio(pcm16kMono) ?: SendResult.NOT_READY
        else -> SendResult.NOT_READY
    }

    fun sendAudioStreamEnd(): SendResult = when (backendName) {
        "aistudio" -> webBackend?.sendAudioStreamEnd() ?: SendResult.NOT_READY
        "api" -> apiBackend?.sendAudioStreamEnd() ?: SendResult.NOT_READY
        else -> SendResult.NOT_READY
    }

    fun backpressureStats(): BackpressureStats = when (backendName) {
        "aistudio" -> webBackend?.backpressureStats()
        "api" -> apiBackend?.backpressureStats()
        else -> null
    } ?: BackpressureStats(0L, 0L, 0L)

    fun responseStats(): ResponseStats = when (backendName) {
        "aistudio" -> webBackend?.responseStats()
        "api" -> apiBackend?.responseStats()
        else -> null
    } ?: ResponseStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

    fun close(graceful: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        webBackend?.close(graceful)
        webBackend = null
        apiBackend?.close(graceful)
        apiBackend = null
        logger.log(2, "LiveBackend", "Đóng facade backend=$backendName graceful=$graceful")
        backendName = "closed"
    }

    private fun connectAiStudio() {
        if (closed.get()) return
        backendName = "aistudio"
        aiStudioSetupComplete = false
        val apiFallback = hasRealApiKey() && AiStudioLiveBackendPolicy.allowApiFallback(appContext)
        logger.log(
            2,
            "LiveBackend",
            "Chọn AI Studio Web Session version=${AiStudioWebRealtimeClient.VERSION} target=$targetLanguage mode=$operationMode apiFallback=$apiFallback webOnly=${!hasRealApiKey()}",
        )
        val backend = AiStudioWebRealtimeClient(
            targetLanguage = targetLanguage,
            operationMode = operationMode,
            logger = logger,
            maxQueuedWireBytes = maxQueuedWireBytes,
            listener = object : Listener {
                override fun onOpen() = listener.onOpen()

                override fun onSetupComplete() {
                    aiStudioSetupComplete = true
                    AiStudioLiveBackendPolicy.clearCircuitBreaker()
                    logger.log(2, "LiveBackend", "AI Studio Web Session setup thành công")
                    listener.onSetupComplete()
                }

                override fun onText(text: String) = listener.onText(text)
                override fun onAudio(pcm24kMono: ByteArray) = listener.onAudio(pcm24kMono)
                override fun onInputTranscript(text: String) = listener.onInputTranscript(text)
                override fun onInterimTranscript(text: String) = listener.onInterimTranscript(text)
                override fun onTurnComplete() = listener.onTurnComplete()
                override fun onInterrupted() = listener.onInterrupted()
                override fun onSessionResumptionUpdate(resumable: Boolean, newHandle: String?) =
                    listener.onSessionResumptionUpdate(resumable, newHandle)
                override fun onGoAway(timeLeft: String?) = listener.onGoAway(timeLeft)

                override fun onError(error: Throwable) {
                    handleAiStudioTerminal(error, closedReason = null)
                }

                override fun onClosed(reason: String) {
                    handleAiStudioTerminal(null, closedReason = reason)
                }
            },
        )
        webBackend = backend
        backend.connect()
    }

    private fun handleAiStudioTerminal(error: Throwable?, closedReason: String?) {
        if (closed.get()) return
        val detail = error?.message ?: closedReason ?: "AI Studio closed"
        val canFallback = !aiStudioSetupComplete && hasRealApiKey() &&
            AiStudioLiveBackendPolicy.allowApiFallback(appContext)
        AiStudioLiveBackendPolicy.recordAiStudioFailure(hasApiFallback = canFallback)
        if (canFallback) {
            logger.log(1, "LiveBackend", "AI Studio chưa setup được ($detail); chuyển sang Gemini API fallback")
            webBackend?.close(false)
            webBackend = null
            connectApi()
            return
        }
        if (!hasRealApiKey()) {
            logger.log(1, "LiveBackend", "AI Studio là backend Live duy nhất; giữ quyền thử lại qua reconnect/backoff reason=$detail")
        }
        if (error != null) listener.onError(error) else listener.onClosed(detail)
    }

    private fun connectApi() {
        if (closed.get()) return
        if (!hasRealApiKey()) {
            listener.onError(IllegalStateException("Không có Gemini API Key để fallback"))
            return
        }
        backendName = "api"
        logger.log(2, "LiveBackend", "Chọn Gemini API WebSocket fallback model=$model mode=$operationMode")
        val backend = GeminiApiLiveClient(
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            echoTargetLanguage = echoTargetLanguage,
            logger = logger,
            listener = listener,
            resumeHandle = resumeHandle,
            maxQueuedWireBytes = maxQueuedWireBytes,
            operationMode = operationMode,
        )
        apiBackend = backend
        backend.connect()
    }

    private fun hasRealApiKey(): Boolean =
        apiKey.isNotBlank() && !AiStudioLiveBackendPolicy.isSentinel(apiKey)

    class GeminiApiException(val code: Int, message: String) : Exception("Gemini API $code: $message")

    companion object {
        const val VERSION = "2026-09-03-r17.3-realtime-session-facade"
        private const val DEFAULT_MAX_QUEUED_WIRE_BYTES = 512L * 1024L

        internal fun createSetupMessage(
            model: String,
            targetLanguage: String,
            echoTargetLanguage: Boolean,
            resumeHandle: String? = null,
            operationMode: OperationMode = OperationMode.TRANSLATE,
        ): String {
            val setup = JSONObject()
                .put("model", "models/${model.trim().removePrefix("models/")}")
            if (operationMode == OperationMode.TRANSCRIBE) {
                setup.put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("TEXT")),
                )
                setup.put(
                    "inputAudioTranscription",
                    JSONObject().put("languageCodes", JSONArray()),
                )
            } else {
                val translationConfig = JSONObject()
                    .put("targetLanguageCode", targetLanguage)
                    .put("echoTargetLanguage", echoTargetLanguage)
                setup.put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("translationConfig", translationConfig),
                )
                setup.put(
                    "contextWindowCompression",
                    JSONObject().put("slidingWindow", JSONObject()),
                )
                resumeHandle?.trim()?.takeIf(String::isNotBlank)?.let { handle ->
                    setup.put("sessionResumption", JSONObject().put("handle", handle))
                }
            }
            return JSONObject().put("setup", setup).toString()
        }

        suspend fun testConnection(
            apiKey: String,
            model: String,
            targetLanguage: String,
            echoTargetLanguage: Boolean,
            logger: SessionLogger,
            operationMode: OperationMode = OperationMode.TRANSLATE,
        ): Long {
            val started = System.nanoTime()
            val result = CompletableDeferred<Result<Unit>>()
            lateinit var testClient: GeminiApiLiveClient
            testClient = GeminiApiLiveClient(
                apiKey = apiKey,
                model = model,
                targetLanguage = targetLanguage,
                echoTargetLanguage = echoTargetLanguage,
                logger = logger,
                listener = object : Listener {
                    override fun onSetupComplete() { result.complete(Result.success(Unit)) }
                    override fun onText(text: String) = Unit
                    override fun onAudio(pcm24kMono: ByteArray) = Unit
                    override fun onError(error: Throwable) { result.complete(Result.failure(error)) }
                    override fun onClosed(reason: String) {
                        if (!result.isCompleted) result.complete(Result.failure(IllegalStateException(reason)))
                    }
                },
                resumeHandle = null,
                maxQueuedWireBytes = DEFAULT_MAX_QUEUED_WIRE_BYTES,
                operationMode = operationMode,
            )
            try {
                testClient.connect()
                withTimeout(15_000) { result.await().getOrThrow() }
            } finally {
                testClient.close()
            }
            return (System.nanoTime() - started) / 1_000_000L
        }
    }
}
