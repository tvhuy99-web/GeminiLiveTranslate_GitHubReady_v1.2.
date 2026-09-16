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

/**
 * Dedicated Gemini Live client for visual-only, real-time screen description.
 *
 * Important invariant: this client has no audio-input API. The only realtime media input it can
 * send is an image frame under realtimeInput.video. Model audio output stays enabled.
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
    private val frameCount = AtomicLong(0L)
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
                if (!explicitlyClosed.get() && terminalDelivered.compareAndSet(false, true)) {
                    listener.onClosed("$code: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setupComplete.set(false)
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

    /** Sends exactly one JPEG screen frame. No audio input exists in this client. */
    fun sendVideoFrame(jpeg: ByteArray): SendResult {
        if (jpeg.isEmpty()) return SendResult.SENT
        val result = sendRealtimePayload(createVideoMessage(jpeg), dropOnBackpressure = true)
        if (result == SendResult.SENT) {
            val count = frameCount.incrementAndGet()
            if (count == 1L || count % 30L == 0L) {
                logger.log(3, TAG, "Đã gửi frame=$count jpegBytes=${jpeg.size} micInput=false")
            }
        }
        return result
    }

    fun close(graceful: Boolean = true) {
        if (!explicitlyClosed.compareAndSet(false, true)) return
        terminalDelivered.set(true)
        setupComplete.set(false)
        val current = socket
        socket = null
        logger.log(
            2,
            TAG,
            "Đóng Live visual-only frames=${frameCount.get()} droppedBackpressure=${droppedByBackpressure.get()} " +
                "audioChunks=${audioChunks.get()} transcriptEvents=${transcriptEvents.get()} maxQueued=${maxObservedWireBytes.get()}",
        )
        if (graceful) current?.close(1000, "client stop") else current?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
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
                    logger.log(1, TAG, "Bỏ frame cũ do backpressure dropped=$dropped queuedBytes=$queued")
                }
            }
            return SendResult.BACKPRESSURED
        }
        return if (current.send(payload)) SendResult.SENT else SendResult.FAILED
    }

    private fun sendActivationInstruction() {
        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "text",
                    "Bắt đầu quan sát màn hình ngay bây giờ. Không thông báo rằng bạn đã sẵn sàng. " +
                        "Chỉ lên tiếng khi có thông tin hình ảnh mới đáng mô tả và tiếp tục theo dõi các khung hình sau.",
                ),
            )
            .toString()
        val result = sendRealtimePayload(message, dropOnBackpressure = false)
        logger.log(3, TAG, "Gửi lệnh kích hoạt visual narrator result=$result")
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
                logger.log(2, TAG, "Setup hoàn tất; visual frames có thể bắt đầu, micInput=false")
                listener.onSetupComplete()
                sendActivationInstruction()
                return@parse
            }
            val serverContent = root.optJSONObject("serverContent") ?: return@parse
            if (serverContent.optBoolean("interrupted", false)) listener.onInterrupted()

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
            if (serverContent.optBoolean("turnComplete", false) || serverContent.optBoolean("generationComplete", false)) {
                listener.onTurnComplete()
            }
        }.onFailure {
            logger.log(0, TAG, "Không phân tích được thông điệp Live length=${text.length}", it)
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

    companion object {
        const val MODEL = "gemini-3.8-live"
        const val VERSION = "2026-09-16-gemini-3.8-live-visual-only-v1"
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
            - Chữ, phụ đề, thông báo hoặc giao diện quan trọng đối với diễn biến; chỉ đọc/tóm tắt phần cần thiết.
            - Biểu cảm và cử chỉ chỉ khi chúng nhìn thấy đủ rõ và thực sự có ý nghĩa.

            PHONG CÁCH
            Nói trực tiếp: “Người đàn ông bước vào phòng và nhìn quanh.”
            Tránh mở đầu lặp đi lặp lại bằng “Tôi thấy”, “Trong hình ảnh này”, “Ở khung hình hiện tại”.
            Mục tiêu là nhanh, chính xác, tự nhiên, không lặp và luôn bám sát điều đang xảy ra ngay lúc này.
        """.trimIndent()
    }
}
