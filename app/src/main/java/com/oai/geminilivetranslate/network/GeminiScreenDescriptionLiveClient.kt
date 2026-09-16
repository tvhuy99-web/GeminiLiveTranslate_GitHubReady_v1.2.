package com.oai.geminilivetranslate.network

import android.os.SystemClock
import com.oai.geminilivetranslate.core.SessionLogger
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Dedicated Gemini Live client for visual-only, real-time screen description.
 *
 * Important invariant: this client has no audio-input API. Model audio output stays enabled.
 *
 * Gemini Live video frames do not start reasoning by themselves, so every server-bound frame is
 * immediately followed by a short text heartbeat. A second pair is never sent until the previous
 * model turn reports turnComplete. Frames captured while the model is speaking replace one local
 * pending frame, so stale visual frames never build up in the WebSocket queue.
 */
internal class GeminiScreenDescriptionLiveClient(
    private val apiKey: String,
    private val outputLanguage: String,
    private val logger: SessionLogger,
    private val listener: Listener,
    private val maxQueuedWireBytes: Long = DEFAULT_MAX_QUEUED_WIRE_BYTES,
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

    private val explicitlyClosed = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val turnInFlight = AtomicBoolean(false)
    private val latestPendingFrame = AtomicReference<ByteArray?>(null)
    private val acceptedFrames = AtomicLong(0L)
    private val serverFrames = AtomicLong(0L)
    private val heartbeatCount = AtomicLong(0L)
    private val replacedWhileBusy = AtomicLong(0L)
    private val droppedByBackpressure = AtomicLong(0L)
    private val audioChunks = AtomicLong(0L)
    private val transcriptEvents = AtomicLong(0L)
    private val maxObservedWireBytes = AtomicLong(0L)
    private val lastBackpressureLogAt = AtomicLong(0L)

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
        logger.log(2, TAG, "Mở Gemini Live visual-only model=$MODEL output=AUDIO micInput=false")
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.log(2, TAG, "WebSocket đã mở; gửi setup visual-only")
                if (!webSocket.send(createSetupMessage(outputLanguage))) {
                    deliverError(IllegalStateException("Không gửi được cấu hình Gemini Live"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = parseMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = parseMessage(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.log(1, TAG, "WebSocket đang đóng code=$code reason=${reason.take(200)}")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setupComplete.set(false)
                turnInFlight.set(false)
                latestPendingFrame.set(null)
                if (!explicitlyClosed.get() && terminalDelivered.compareAndSet(false, true)) {
                    listener.onClosed("$code: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setupComplete.set(false)
                turnInFlight.set(false)
                latestPendingFrame.set(null)
                if (explicitlyClosed.get()) return
                val error = if (response != null) {
                    GeminiLiveClient.GeminiApiException(
                        response.code,
                        response.message.ifBlank { t.message.orEmpty() },
                    )
                } else {
                    t
                }
                logger.log(0, TAG, "Kết nối Gemini Live visual-only thất bại HTTP=${response?.code}", error)
                deliverError(error)
            }
        })
    }

    /**
     * Accepts the newest JPEG screen frame. If the model is idle the frame is sent immediately
     * with its text heartbeat. If the model is still speaking, only the newest local frame is kept.
     * There is intentionally no audio-input method in this client.
     */
    fun sendVideoFrame(jpeg: ByteArray): SendResult {
        if (jpeg.isEmpty()) return SendResult.SENT
        if (explicitlyClosed.get()) return SendResult.CLOSED
        if (!setupComplete.get()) return SendResult.NOT_READY

        acceptedFrames.incrementAndGet()
        val previous = latestPendingFrame.getAndSet(jpeg)
        if (previous != null) replacedWhileBusy.incrementAndGet()
        return drainLatestFrameIfIdle()
    }

    fun close(graceful: Boolean = true) {
        if (!explicitlyClosed.compareAndSet(false, true)) return
        terminalDelivered.set(true)
        setupComplete.set(false)
        turnInFlight.set(false)
        latestPendingFrame.set(null)
        val current = socket
        socket = null
        logger.log(
            2,
            TAG,
            "Đóng Live visual-only acceptedFrames=${acceptedFrames.get()} serverFrames=${serverFrames.get()} " +
                "heartbeats=${heartbeatCount.get()} replacedWhileBusy=${replacedWhileBusy.get()} " +
                "droppedBackpressure=${droppedByBackpressure.get()} audioChunks=${audioChunks.get()} " +
                "transcriptEvents=${transcriptEvents.get()} maxQueued=${maxObservedWireBytes.get()}",
        )
        if (graceful) current?.close(1000, "client stop") else current?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun drainLatestFrameIfIdle(): SendResult {
        if (explicitlyClosed.get()) return SendResult.CLOSED
        if (!setupComplete.get()) return SendResult.NOT_READY
        if (!turnInFlight.compareAndSet(false, true)) return SendResult.SENT

        val frame = latestPendingFrame.getAndSet(null)
        if (frame == null) {
            turnInFlight.set(false)
            return SendResult.SENT
        }

        val frameResult = sendRealtimePayload(createVideoMessage(frame), dropOnBackpressure = true)
        if (frameResult != SendResult.SENT) {
            turnInFlight.set(false)
            latestPendingFrame.compareAndSet(null, frame)
            return frameResult
        }

        val sentFrames = serverFrames.incrementAndGet()
        if (sentFrames == 1L || sentFrames % 30L == 0L) {
            logger.log(
                3,
                TAG,
                "Đã gửi serverFrame=$sentFrames accepted=${acceptedFrames.get()} jpegBytes=${frame.size} micInput=false",
            )
        }

        // Keep the frame and its trigger adjacent in WebSocket order. Once the frame was accepted,
        // the tiny heartbeat is intentionally not rejected by the frame backpressure threshold.
        val heartbeatResult = sendHeartbeatDirect()
        if (heartbeatResult != SendResult.SENT) {
            turnInFlight.set(false)
            logger.log(1, TAG, "Frame đã gửi nhưng heartbeat thất bại result=$heartbeatResult")
            return heartbeatResult
        }

        val heartbeats = heartbeatCount.incrementAndGet()
        if (heartbeats == 1L || heartbeats % 20L == 0L) {
            logger.log(3, TAG, "Heartbeat visual=$heartbeats; chờ turnComplete trước cặp kế tiếp")
        }
        return SendResult.SENT
    }

    private fun sendHeartbeatDirect(): SendResult {
        if (explicitlyClosed.get()) return SendResult.CLOSED
        if (!setupComplete.get()) return SendResult.NOT_READY
        val current = socket ?: return SendResult.CLOSED
        return if (current.send(createHeartbeatMessage())) SendResult.SENT else SendResult.FAILED
    }

    private fun sendRealtimePayload(payload: String, dropOnBackpressure: Boolean): SendResult {
        if (explicitlyClosed.get()) return SendResult.CLOSED
        if (!setupComplete.get()) return SendResult.NOT_READY
        val current = socket ?: return SendResult.CLOSED
        val queued = current.queueSize()
        updateMaxObserved(queued)
        if (queued >= maxQueuedWireBytes.coerceAtLeast(MIN_QUEUED_WIRE_BYTES)) {
            if (dropOnBackpressure) {
                val dropped = droppedByBackpressure.incrementAndGet()
                val now = SystemClock.elapsedRealtime()
                val previous = lastBackpressureLogAt.get()
                if (dropped == 1L || now - previous >= 5_000L) {
                    lastBackpressureLogAt.set(now)
                    logger.log(1, TAG, "Giữ frame mới nhất tại RAM do backpressure dropped=$dropped queuedBytes=$queued")
                }
            }
            return SendResult.BACKPRESSURED
        }
        return if (current.send(payload)) SendResult.SENT else SendResult.FAILED
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
                turnInFlight.set(false)
                logger.log(2, TAG, "Setup hoàn tất; chờ frame đầu tiên rồi gửi frame+heartbeat, micInput=false")
                listener.onSetupComplete()
                return@parse
            }
            val serverContent = root.optJSONObject("serverContent") ?: return@parse
            if (serverContent.optBoolean("interrupted", false)) {
                // Gemini documents interrupted -> turnComplete. Flush stale output now, but keep the
                // turn gate closed until turnComplete so a new heartbeat cannot race the old turn.
                listener.onInterrupted()
            }

            serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf(String::isNotBlank)
                ?.let { transcript ->
                    transcriptEvents.incrementAndGet()
                    listener.onTranscript(transcript)
                }

            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    part.optJSONObject("inlineData")?.let { inline ->
                        val mime = inline.optString("mimeType").lowercase()
                        val data = inline.optString("data")
                        if (data.isNotBlank() && (mime.isBlank() || mime.startsWith("audio/"))) {
                            runCatching { Base64.getDecoder().decode(data) }
                                .onSuccess { decoded ->
                                    if (decoded.isNotEmpty()) {
                                        audioChunks.incrementAndGet()
                                        listener.onAudio(decoded)
                                    }
                                }
                                .onFailure { logger.log(1, TAG, "Không giải mã được audio output", it) }
                        }
                    }
                    part.optString("text")
                        .takeIf(String::isNotBlank)
                        ?.let { modelText ->
                            transcriptEvents.incrementAndGet()
                            listener.onTranscript(modelText)
                        }
                }
            }
            if (serverContent.optBoolean("turnComplete", false)) {
                turnInFlight.set(false)
                listener.onTurnComplete()
                drainPendingAfterTurn()
            }
        }.onFailure {
            logger.log(0, TAG, "Không phân tích được thông điệp Live length=${text.length}", it)
            deliverError(it)
        }
    }

    private fun drainPendingAfterTurn() {
        if (latestPendingFrame.get() == null || explicitlyClosed.get()) return
        val result = drainLatestFrameIfIdle()
        if (result != SendResult.SENT && result != SendResult.NOT_READY) {
            logger.log(1, TAG, "Chưa gửi được frame mới nhất sau turnComplete result=$result; giữ lại để thử lại")
        }
    }

    private fun deliverError(error: Throwable) {
        turnInFlight.set(false)
        latestPendingFrame.set(null)
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

    companion object {
        const val MODEL = "gemini-3.8-live"
        const val VERSION = "2026-09-16-gemini-3.8-live-visual-only-v4"
        private const val TAG = "LiveScreenDescription"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val DEFAULT_MAX_QUEUED_WIRE_BYTES = 512L * 1024L
        private const val MIN_QUEUED_WIRE_BYTES = 64L * 1024L

        internal fun createSetupMessage(outputLanguage: String): String {
            val setup = JSONObject()
                .put("model", "models/$MODEL")
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemInstruction(outputLanguage))),
                    ),
                )
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "contextWindowCompression",
                    JSONObject().put("slidingWindow", JSONObject()),
                )
            return JSONObject().put("setup", setup).toString()
        }

        internal fun createVideoMessage(jpeg: ByteArray): String {
            val video = JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.getEncoder().encodeToString(jpeg))
            return JSONObject()
                .put("realtimeInput", JSONObject().put("video", video))
                .toString()
        }

        internal fun createHeartbeatMessage(): String = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "text",
                    "Quan sát hình ảnh mới nhất và diễn biến kể từ lần mô tả trước. " +
                        "Nếu có thay đổi quan trọng chưa được mô tả, hãy mô tả ngay theo đúng quy tắc. " +
                        "Nếu không có thay đổi đáng kể, không cần nói gì và hãy kết thúc lượt.",
                ),
            )
            .toString()

        private fun systemInstruction(outputLanguage: String): String = """
            Bạn là hệ thống thuyết minh hình ảnh theo thời gian thực dành cho người đang xem màn hình.

            MỤC TIÊU
            - Chủ động quan sát luồng hình ảnh liên tục và mô tả diễn biến quan trọng mà không chờ câu hỏi.
            - Trả lời bằng ${outputLanguage.ifBlank { "tiếng Việt" }}.
            - Mặc định mỗi lượt chỉ một câu ngắn; chỉ dùng tối đa hai câu khi thật sự cần để hiểu cảnh.
            - Phản hồi sớm, không tích lũy nhiều sự kiện rồi mới nói, và luôn ưu tiên diễn biến mới nhất để không tụt sau video.

            QUY TẮC BẮT BUỘC
            1. Bạn chỉ nhận thông tin hình ảnh. Không giả định rằng bạn nghe được lời thoại, âm nhạc, tiếng động hoặc microphone.
            2. Không suy đoán nội dung âm thanh từ chuyển động môi hay từ bối cảnh hình ảnh.
            3. Xem các khung hình là một video liên tục. Giữ ngữ cảnh giữa các khung hình thay vì mô tả mỗi ảnh như ảnh độc lập.
            4. Chỉ nói khi xuất hiện thông tin mới hoặc thay đổi có ý nghĩa. Nếu không có gì đáng kể, hãy im lặng.
            5. Không lặp lại điều vừa mô tả nếu cảnh chưa thay đổi.
            6. Nếu cảnh thay đổi nhanh, tóm tắt diễn biến chính thay vì cố kể mọi chi tiết.
            7. Nếu nhiều việc xảy ra đồng thời, chọn sự kiện quan trọng nhất.
            8. Không chờ người dùng nói, không yêu cầu người dùng phản hồi và không thông báo trạng thái sẵn sàng.
            9. Không tự nhận dạng danh tính, mục đích, suy nghĩ hoặc nguyên nhân khi hình ảnh không đủ bằng chứng.
            10. Khi không chắc, dùng cách diễn đạt thận trọng và không bịa thêm chi tiết.

            THỨ TỰ ƯU TIÊN
            - Hành động đang xảy ra và thay đổi hành động.
            - Người hoặc vật thể vừa xuất hiện, biến mất, di chuyển hoặc tương tác.
            - Chuyển cảnh, thay đổi địa điểm, góc nhìn hoặc tình huống.
            - Sự kiện bất ngờ, nguy hiểm, cảnh báo hoặc thông tin cần chú ý ngay.
            - Chữ, phụ đề, thông báo hoặc giao diện quan trọng đối với diễn biến; chỉ đọc hoặc tóm tắt phần cần thiết.
            - Biểu cảm và cử chỉ chỉ khi chúng nhìn thấy đủ rõ và thực sự có ý nghĩa.

            NHỊP THUYẾT MINH
            - Mỗi heartbeat chỉ là một nhịp quan sát, không phải yêu cầu bắt buộc phải nói.
            - Nếu cảnh gần như không đổi so với mô tả gần nhất, hãy kết thúc lượt mà không tạo lời nói.
            - Nếu đang có nhiều thay đổi, ưu tiên thông tin mới nhất và quan trọng nhất.
            - Không kéo dài một mô tả cũ khi cảnh đã chuyển sang sự kiện mới.

            PHONG CÁCH
            Nói trực tiếp: “Người đàn ông bước vào phòng và nhìn quanh.”
            Tránh mở đầu lặp đi lặp lại bằng “Tôi thấy”, “Trong hình ảnh này”, “Ở khung hình hiện tại”.
            Mục tiêu là nhanh, chính xác, tự nhiên, không lặp và luôn bám sát điều đang xảy ra ngay lúc này.
        """.trimIndent()
    }
}
