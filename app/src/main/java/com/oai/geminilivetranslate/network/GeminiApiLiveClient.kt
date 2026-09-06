package com.oai.geminilivetranslate.network

import android.os.SystemClock
import android.util.Base64
import com.oai.geminilivetranslate.core.SessionLogger
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


internal class GeminiApiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val targetLanguage: String,
    private val echoTargetLanguage: Boolean,
    private val logger: SessionLogger,
    private val listener: GeminiLiveClient.Listener,
    private val resumeHandle: String? = null,
    private val maxQueuedWireBytes: Long,
    private val operationMode: GeminiLiveClient.OperationMode,
) {
    private val explicitlyClosed = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val maxObservedWireBytes = AtomicLong(0L)
    private val backpressureEvents = AtomicLong(0L)
    private val lastBackpressureLogElapsed = AtomicLong(0L)
    private val serverContentEvents = AtomicLong(0L)
    private val inputTranscriptEvents = AtomicLong(0L)
    private val outputTranscriptionObjects = AtomicLong(0L)
    private val outputTextEvents = AtomicLong(0L)
    private val modelTextEvents = AtomicLong(0L)
    private val textDispatchEvents = AtomicLong(0L)
    private val audioChunks = AtomicLong(0L)
    private val audioBytes = AtomicLong(0L)
    private val turnCompleteEvents = AtomicLong(0L)

    @Volatile private var lastServerContentShape = ""

    private val httpClient = OkHttpClient.Builder()
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
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val mode = if (resumeHandle.isNullOrBlank()) "phiên mới" else "khôi phục phiên"
                logger.log(2, "GeminiWS", "WebSocket API đã mở; gửi setup model=$model ($mode)")
                listener.onOpen()
                if (!webSocket.send(GeminiLiveClient.createSetupMessage(model, targetLanguage, echoTargetLanguage, resumeHandle, operationMode))) {
                    deliverError(IllegalStateException("Không gửi được cấu hình setup"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = parseMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = parseMessage(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.log(1, "GeminiWS", "WebSocket API đang đóng code=$code reason=${reason.take(200)}")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setupComplete.set(false)
                logger.log(if (explicitlyClosed.get()) 2 else 1, "GeminiWS", "WebSocket API đã đóng code=$code reason=${reason.take(200)} explicit=${explicitlyClosed.get()}")
                if (!explicitlyClosed.get() && terminalDelivered.compareAndSet(false, true)) {
                    listener.onClosed("$code: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setupComplete.set(false)
                if (explicitlyClosed.get()) return
                logger.log(0, "GeminiWS", "Kết nối API thất bại HTTP=${response?.code}", t)
                deliverError(normalizeError(t, response))
            }
        })
    }

    fun sendAudio(pcm16kMono: ByteArray): GeminiLiveClient.SendResult {
        if (pcm16kMono.isEmpty()) return GeminiLiveClient.SendResult.SENT
        val audio = JSONObject()
            .put("mimeType", INPUT_MIME)
            .put("data", Base64.encodeToString(pcm16kMono, Base64.NO_WRAP))
        return sendRealtimePayload(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
    }

    fun sendAudioStreamEnd(): GeminiLiveClient.SendResult =
        sendRealtimePayload(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())

    fun backpressureStats(): GeminiLiveClient.BackpressureStats {
        val queued = socket?.queueSize() ?: 0L
        updateMaxObserved(queued)
        return GeminiLiveClient.BackpressureStats(
            queuedWireBytes = queued,
            maxObservedWireBytes = maxObservedWireBytes.get(),
            backpressureEvents = backpressureEvents.get(),
        )
    }

    fun responseStats(): GeminiLiveClient.ResponseStats = GeminiLiveClient.ResponseStats(
        serverContentEvents = serverContentEvents.get(),
        inputTranscriptEvents = inputTranscriptEvents.get(),
        outputTranscriptionObjects = outputTranscriptionObjects.get(),
        outputTextEvents = outputTextEvents.get(),
        modelTextEvents = modelTextEvents.get(),
        textDispatchEvents = textDispatchEvents.get(),
        audioChunks = audioChunks.get(),
        audioBytes = audioBytes.get(),
        turnCompleteEvents = turnCompleteEvents.get(),
    )

    fun close(graceful: Boolean = true) {
        if (!explicitlyClosed.compareAndSet(false, true)) return
        terminalDelivered.set(true)
        setupComplete.set(false)
        val current = socket
        socket = null
        val stats = responseStats()
        logger.log(
            2,
            "GeminiResponse",
            "Đóng API parser server=${stats.serverContentEvents} inputText=${stats.inputTranscriptEvents} outputObjects=${stats.outputTranscriptionObjects} outputText=${stats.outputTextEvents} modelText=${stats.modelTextEvents} dispatch=${stats.textDispatchEvents} audioChunks=${stats.audioChunks} audioBytes=${stats.audioBytes} turns=${stats.turnCompleteEvents}",
        )
        if (graceful) current?.close(1000, "client stop") else current?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun sendRealtimePayload(payload: String): GeminiLiveClient.SendResult {
        if (explicitlyClosed.get()) return GeminiLiveClient.SendResult.CLOSED
        if (!setupComplete.get()) return GeminiLiveClient.SendResult.NOT_READY
        val current = socket ?: return GeminiLiveClient.SendResult.CLOSED
        val queuedBytes = current.queueSize()
        updateMaxObserved(queuedBytes)
        if (queuedBytes >= maxQueuedWireBytes.coerceAtLeast(MIN_QUEUED_WIRE_BYTES)) {
            val events = backpressureEvents.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            val previous = lastBackpressureLogElapsed.get()
            if (events == 1L || now - previous >= 5_000L) {
                lastBackpressureLogElapsed.set(now)
                logger.log(1, "GeminiWS", "API WebSocket backpressure events=$events queuedBytes=$queuedBytes limit=$maxQueuedWireBytes")
            }
            return GeminiLiveClient.SendResult.BACKPRESSURED
        }
        return if (current.send(payload)) GeminiLiveClient.SendResult.SENT else GeminiLiveClient.SendResult.FAILED
    }

    private fun parseMessage(text: String) {
        runCatching parse@{
            val root = JSONObject(text)
            root.optJSONObject("error")?.let { errorObject ->
                throw GeminiLiveClient.GeminiApiException(
                    errorObject.optInt("code", 0),
                    errorObject.optString("message", "Gemini API error"),
                )
            }
            if (root.has("setupComplete")) {
                setupComplete.set(true)
                listener.onSetupComplete()
                return@parse
            }
            root.optJSONObject("sessionResumptionUpdate")?.let { update ->
                listener.onSessionResumptionUpdate(
                    update.optBoolean("resumable", false),
                    update.optString("newHandle").takeIf(String::isNotBlank),
                )
                return@parse
            }
            root.optJSONObject("goAway")?.let { goAway ->
                val raw = goAway.opt("timeLeft")
                val value = when (raw) {
                    null, JSONObject.NULL -> null
                    is String -> raw.takeIf(String::isNotBlank)
                    else -> raw.toString().takeIf(String::isNotBlank)
                }
                listener.onGoAway(value)
                return@parse
            }
            val serverContent = root.optJSONObject("serverContent") ?: return@parse
            serverContentEvents.incrementAndGet()
            logServerContentShape(serverContent)

            if (serverContent.optBoolean("interrupted", false)) listener.onInterrupted()
            serverContent.optJSONObject("interimInputTranscription")
                ?.optString("text")?.takeIf(String::isNotBlank)?.let(listener::onInterimTranscript)

            serverContent.optJSONObject("inputTranscription")
                ?.optString("text")?.takeIf(String::isNotBlank)?.let { inputText ->
                    inputTranscriptEvents.incrementAndGet()
                    listener.onInputTranscript(inputText)
                }

            val outputObject = serverContent.optJSONObject("outputTranscription")
            if (serverContent.has("outputTranscription")) outputTranscriptionObjects.incrementAndGet()
            val outputText = outputObject?.optString("text")?.takeIf(String::isNotBlank)
            if (outputText != null) {
                outputTextEvents.incrementAndGet()
                dispatchText(outputText)
            }

            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    part.optJSONObject("inlineData")?.let { inline ->
                        val mime = inline.optString("mimeType").lowercase()
                        val data = inline.optString("data")
                        if (data.isNotBlank() && (mime.isBlank() || mime.startsWith("audio/"))) {
                            runCatching { Base64.decode(data, Base64.DEFAULT) }
                                .onSuccess { decoded ->
                                    audioChunks.incrementAndGet()
                                    audioBytes.addAndGet(decoded.size.toLong())
                                    listener.onAudio(decoded)
                                }
                                .onFailure { logger.log(1, "GeminiWS", "Không giải mã được audio phản hồi API", it) }
                        }
                    }
                    if (outputText == null) {
                        part.optString("text").takeIf(String::isNotBlank)?.let { modelText ->
                            modelTextEvents.incrementAndGet()
                            dispatchText(modelText)
                        }
                    }
                }
            }
            if (serverContent.optBoolean("turnComplete", false) || serverContent.optBoolean("generationComplete", false)) {
                turnCompleteEvents.incrementAndGet()
                listener.onTurnComplete()
            }
        }.onFailure {
            logger.log(0, "GeminiWS", "Không phân tích được thông điệp API length=${text.length}", it)
            deliverError(it)
        }
    }

    private fun dispatchText(text: String) {
        textDispatchEvents.incrementAndGet()
        listener.onText(text)
    }

    private fun logServerContentShape(serverContent: JSONObject) {
        val keys = ArrayList<String>()
        val iterator = serverContent.keys()
        while (iterator.hasNext()) keys += iterator.next()
        keys.sort()
        val shape = keys.joinToString(",")
        if (shape != lastServerContentShape) {
            lastServerContentShape = shape
            logger.log(3, "GeminiResponse", "API serverContent keys=$shape")
        }
    }

    private fun deliverError(error: Throwable) {
        if (!explicitlyClosed.get() && terminalDelivered.compareAndSet(false, true)) listener.onError(error)
    }

    private fun updateMaxObserved(value: Long) {
        var previous = maxObservedWireBytes.get()
        while (value > previous && !maxObservedWireBytes.compareAndSet(previous, value)) {
            previous = maxObservedWireBytes.get()
        }
    }

    private fun normalizeError(error: Throwable, response: Response?): Throwable {
        if (response == null) return error
        return GeminiLiveClient.GeminiApiException(response.code, response.message.ifBlank { error.message.orEmpty() })
    }

    companion object {
        const val VERSION = "2026-09-03-r17-gemini-api-live-fallback"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val INPUT_MIME = "audio/pcm;rate=16000"
        private const val MIN_QUEUED_WIRE_BYTES = 64L * 1024L
    }
}
