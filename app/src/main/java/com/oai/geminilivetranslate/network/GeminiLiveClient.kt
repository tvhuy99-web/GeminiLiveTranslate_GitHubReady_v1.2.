package com.oai.geminilivetranslate.network

import android.os.SystemClock
import android.util.Base64
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val targetLanguage: String,
    private val echoTargetLanguage: Boolean,
    private val logger: SessionLogger,
    private val listener: Listener,
    private val resumeHandle: String? = null,
    private val maxQueuedWireBytes: Long = DEFAULT_MAX_QUEUED_WIRE_BYTES,
) {
    interface Listener {
        fun onOpen() = Unit
        fun onSetupComplete()
        fun onText(text: String)
        fun onAudio(pcm24kMono: ByteArray)
        fun onInputTranscript(text: String) = Unit
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

    private val explicitlyClosed = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val maxObservedWireBytes = AtomicLong(0L)
    private val backpressureEvents = AtomicLong(0L)
    private val lastBackpressureLogElapsed = AtomicLong(0L)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null

    fun connect() {
        check(apiKey.isNotBlank()) { "API Key đang trống" }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val mode = if (resumeHandle.isNullOrBlank()) "phiên mới" else "khôi phục phiên"
                logger.log(2, "GeminiWS", "WebSocket đã mở; gửi setup model=$model ($mode)")
                listener.onOpen()
                if (!webSocket.send(buildSetupMessage())) {
                    deliverError(IllegalStateException("Không gửi được cấu hình setup"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parseMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.log(1, "GeminiWS", "WebSocket đang đóng code=$code reason=${reason.take(200)}")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setupComplete.set(false)
                logger.log(if (explicitlyClosed.get()) 2 else 1, "GeminiWS", "WebSocket đã đóng code=$code reason=${reason.take(200)} explicit=${explicitlyClosed.get()}")
                if (!explicitlyClosed.get() && terminalDelivered.compareAndSet(false, true)) {
                    listener.onClosed("$code: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setupComplete.set(false)
                if (explicitlyClosed.get()) {
                    logger.log(3, "GeminiWS", "WebSocket đã dừng theo yêu cầu")
                    return
                }
                logger.log(0, "GeminiWS", "Kết nối thất bại HTTP=${response?.code}", t)
                deliverError(normalizeError(t, response))
            }
        })
    }

    fun sendAudio(pcm16kMono: ByteArray): SendResult {
        if (pcm16kMono.isEmpty()) return SendResult.SENT
        val audio = JSONObject()
            .put("mimeType", INPUT_MIME)
            .put("data", Base64.encodeToString(pcm16kMono, Base64.NO_WRAP))
        val root = JSONObject().put("realtimeInput", JSONObject().put("audio", audio))
        return sendRealtimePayload(root.toString())
    }

    fun sendAudioStreamEnd(): SendResult {
        val root = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true))
        return sendRealtimePayload(root.toString())
    }

    fun backpressureStats(): BackpressureStats {
        val queued = socket?.queueSize() ?: 0L
        updateMaxObserved(queued)
        return BackpressureStats(
            queuedWireBytes = queued,
            maxObservedWireBytes = maxObservedWireBytes.get(),
            backpressureEvents = backpressureEvents.get(),
        )
    }

    fun close(graceful: Boolean = true) {
        if (!explicitlyClosed.compareAndSet(false, true)) return
        terminalDelivered.set(true)
        setupComplete.set(false)
        val current = socket
        socket = null
        logger.log(2, "GeminiWS", "Đóng client graceful=$graceful queuedBytes=${current?.queueSize() ?: 0L}")
        if (graceful) current?.close(1000, "client stop") else current?.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun sendRealtimePayload(payload: String): SendResult {
        if (explicitlyClosed.get()) return SendResult.CLOSED
        if (!setupComplete.get()) return SendResult.NOT_READY
        val current = socket ?: return SendResult.CLOSED
        val queuedBytes = current.queueSize()
        updateMaxObserved(queuedBytes)
        if (queuedBytes >= maxQueuedWireBytes.coerceAtLeast(MIN_QUEUED_WIRE_BYTES)) {
            val events = backpressureEvents.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            val previous = lastBackpressureLogElapsed.get()
            if (events == 1L || now - previous >= 5_000L) {
                lastBackpressureLogElapsed.set(now)
                logger.log(1, "GeminiWS", "WebSocket backpressure events=$events queuedBytes=$queuedBytes limit=$maxQueuedWireBytes")
            }
            return SendResult.BACKPRESSURED
        }
        return if (current.send(payload)) SendResult.SENT else SendResult.FAILED
    }

    private fun buildSetupMessage(): String {
        return createSetupMessage(
            model = model,
            targetLanguage = targetLanguage,
            echoTargetLanguage = echoTargetLanguage,
            resumeHandle = resumeHandle,
        )
    }

    private fun parseMessage(text: String) {
        runCatching parse@{
            val root = JSONObject(text)
            root.optJSONObject("error")?.let { errorObject ->
                val code = errorObject.optInt("code", 0)
                val message = errorObject.optString("message", "Gemini API error")
                throw GeminiApiException(code, message)
            }
            if (root.has("setupComplete")) {
                setupComplete.set(true)
                logger.log(2, "GeminiWS", "Nhận setupComplete")
                listener.onSetupComplete()
                return@parse
            }
            root.optJSONObject("sessionResumptionUpdate")?.let { update ->
                val resumable = update.optBoolean("resumable", false)
                val newHandle = update.optString("newHandle").takeIf(String::isNotBlank)
                logger.log(3, "GeminiWS", "Nhận sessionResumptionUpdate resumable=$resumable hasHandle=${!newHandle.isNullOrBlank()}")
                listener.onSessionResumptionUpdate(resumable, newHandle)
                return@parse
            }
            root.optJSONObject("goAway")?.let { goAway ->
                val rawTimeLeft = goAway.opt("timeLeft")
                val timeLeft = when (rawTimeLeft) {
                    null, JSONObject.NULL -> null
                    is String -> rawTimeLeft.takeIf(String::isNotBlank)
                    else -> rawTimeLeft.toString().takeIf(String::isNotBlank)
                }
                logger.log(1, "GeminiWS", "Nhận GoAway timeLeft=${timeLeft ?: "unknown"}")
                listener.onGoAway(timeLeft)
                return@parse
            }
            val serverContent = root.optJSONObject("serverContent") ?: return@parse
            if (serverContent.optBoolean("interrupted", false)) listener.onInterrupted()
            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")?.takeIf(String::isNotBlank)?.let(listener::onInputTranscript)

            val outputText = serverContent.optJSONObject("outputTranscription")
                ?.optString("text")?.takeIf(String::isNotBlank)
            outputText?.let(listener::onText)

            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    part.optJSONObject("inlineData")?.let { inline ->
                        val mime = inline.optString("mimeType").lowercase()
                        val data = inline.optString("data")
                        if (data.isNotBlank() && (mime.isBlank() || mime.startsWith("audio/"))) {
                            runCatching { Base64.decode(data, Base64.DEFAULT) }
                                .onSuccess(listener::onAudio)
                                .onFailure { logger.log(1, "GeminiWS", "Không giải mã được audio phản hồi", it) }
                        }
                    }
                    if (outputText == null) {
                        part.optString("text").takeIf(String::isNotBlank)?.let(listener::onText)
                    }
                }
            }
            if (serverContent.optBoolean("turnComplete", false) ||
                serverContent.optBoolean("generationComplete", false)
            ) listener.onTurnComplete()
        }.onFailure {
            logger.log(0, "GeminiWS", "Không phân tích được thông điệp server length=${text.length}", it)
            deliverError(it)
        }
    }

    private fun deliverError(error: Throwable) {
        if (!explicitlyClosed.get() && terminalDelivered.compareAndSet(false, true)) {
            listener.onError(error)
        }
    }

    private fun updateMaxObserved(value: Long) {
        var previous = maxObservedWireBytes.get()
        while (value > previous && !maxObservedWireBytes.compareAndSet(previous, value)) {
            previous = maxObservedWireBytes.get()
        }
    }

    private fun normalizeError(error: Throwable, response: Response?): Throwable {
        if (response == null) return error
        return GeminiApiException(response.code, response.message.ifBlank { error.message.orEmpty() })
    }

    class GeminiApiException(val code: Int, message: String) : Exception("Gemini API $code: $message")

    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val INPUT_MIME = "audio/pcm;rate=16000"
        private const val MIN_QUEUED_WIRE_BYTES = 64L * 1024L
        private const val DEFAULT_MAX_QUEUED_WIRE_BYTES = 512L * 1024L

        /**
         * Builds the raw WebSocket setup message used by the working Lua tool.
         *
         * The translation model only needs responseModalities and translationConfig inside
         * generationConfig. Transcription objects are intentionally omitted: the server may
         * still return input/output transcription, and parseMessage handles those opportunistically.
         */
        internal fun createSetupMessage(
            model: String,
            targetLanguage: String,
            echoTargetLanguage: Boolean,
            resumeHandle: String? = null,
        ): String {
            val translationConfig = JSONObject()
                .put("targetLanguageCode", targetLanguage)
                .put("echoTargetLanguage", echoTargetLanguage)
            val generationConfig = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("translationConfig", translationConfig)
            val setup = JSONObject()
                .put("model", "models/${model.trim().removePrefix("models/")}")
                .put("generationConfig", generationConfig)
            resumeHandle?.trim()?.takeIf(String::isNotBlank)?.let { handle ->
                setup.put("sessionResumption", JSONObject().put("handle", handle))
            }
            return JSONObject().put("setup", setup).toString()
        }

        suspend fun testConnection(
            apiKey: String,
            model: String,
            targetLanguage: String,
            echoTargetLanguage: Boolean,
            logger: SessionLogger,
        ): Long {
            val started = System.nanoTime()
            val result = CompletableDeferred<Result<Unit>>()
            lateinit var testClient: GeminiLiveClient
            testClient = GeminiLiveClient(
                apiKey, model, targetLanguage, echoTargetLanguage, logger,
                object : Listener {
                    override fun onSetupComplete() { result.complete(Result.success(Unit)) }
                    override fun onText(text: String) = Unit
                    override fun onAudio(pcm24kMono: ByteArray) = Unit
                    override fun onError(error: Throwable) { result.complete(Result.failure(error)) }
                    override fun onClosed(reason: String) {
                        if (!result.isCompleted) result.complete(Result.failure(IllegalStateException(reason)))
                    }
                }
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
