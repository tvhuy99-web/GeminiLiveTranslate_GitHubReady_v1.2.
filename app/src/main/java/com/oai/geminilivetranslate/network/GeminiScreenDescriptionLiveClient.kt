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
 *
 * Live WebSocket connections are periodically rotated by the server. Session resumption is always
 * enabled, the newest resumable handle is retained in memory, and GoAway rotates only after the
 * current model turn completes. Screen capture and output audio therefore stay alive across the
 * WebSocket replacement instead of ending the user session at the connection lifetime boundary.
 */
internal class GeminiScreenDescriptionLiveClient(
    private val apiKey: String,
    private val outputLanguage: String,
    private val logger: SessionLogger,
    private val listener: Listener,
    private val systemPrompt: String? = null,
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
    private val latestResumptionHandle = AtomicReference<String?>(null)
    private val goAwayRequested = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0L)
    private val acceptedFrames = AtomicLong(0L)
    private val serverFrames = AtomicLong(0L)
    private val heartbeatCount = AtomicLong(0L)
    private val replacedWhileBusy = AtomicLong(0L)
    private val droppedByBackpressure = AtomicLong(0L)
    private val audioChunks = AtomicLong(0L)
    private val transcriptEvents = AtomicLong(0L)
    private val goAwayCount = AtomicLong(0L)
    private val resumedConnections = AtomicLong(0L)
    private val resumptionUpdates = AtomicLong(0L)
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
        openConnection(resumptionHandle = null, reason = "initial")
    }

    private fun openConnection(resumptionHandle: String?, reason: String) {
        if (explicitlyClosed.get()) return
        val generation = connectionGeneration.incrementAndGet()
        setupComplete.set(false)
        turnInFlight.set(false)

        val url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).build()
        logger.log(
            2,
            TAG,
            "Mở Gemini Live visual-only generation=$generation reason=$reason model=$MODEL " +
                "resume=${!resumptionHandle.isNullOrBlank()} output=AUDIO micInput=false",
        )

        val newSocket = runCatching {
            httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrentConnection(generation)) {
                        webSocket.close(1000, "superseded")
                        return
                    }
                    logger.log(
                        2,
                        TAG,
                        "WebSocket đã mở generation=$generation; gửi setup visual-only " +
                            "resume=${!resumptionHandle.isNullOrBlank()}",
                    )
                    if (!webSocket.send(createSetupMessage(outputLanguage, resumptionHandle, systemPrompt))) {
                        deliverError(IllegalStateException("Không gửi được cấu hình Gemini Live"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isCurrentConnection(generation)) parseMessage(text, generation)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (isCurrentConnection(generation)) parseMessage(bytes.utf8(), generation)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentConnection(generation)) {
                        webSocket.close(code, reason)
                        return
                    }
                    logger.log(
                        1,
                        TAG,
                        "WebSocket đang đóng generation=$generation code=$code reason=${reason.take(200)}",
                    )
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentConnection(generation)) return
                    setupComplete.set(false)
                    turnInFlight.set(false)
                    if (explicitlyClosed.get()) return
                    if (goAwayRequested.get()) {
                        logger.log(
                            1,
                            TAG,
                            "Kết nối hết hạn sau GoAway generation=$generation; nối lại phiên",
                        )
                        reconnectFromGoAway("server-closed-after-goaway")
                        return
                    }
                    latestPendingFrame.set(null)
                    if (terminalDelivered.compareAndSet(false, true)) {
                        listener.onClosed("$code: $reason")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentConnection(generation)) return
                    setupComplete.set(false)
                    turnInFlight.set(false)
                    if (explicitlyClosed.get()) return
                    if (goAwayRequested.get()) {
                        logger.log(
                            1,
                            TAG,
                            "Kết nối lỗi trong lúc GoAway generation=$generation; thử nối lại bằng session handle",
                            t,
                        )
                        reconnectFromGoAway("failure-after-goaway")
                        return
                    }
                    val error = if (response != null) {
                        GeminiLiveClient.GeminiApiException(
                            response.code,
                            response.message.ifBlank { t.message.orEmpty() },
                        )
                    } else {
                        t
                    }
                    logger.log(
                        0,
                        TAG,
                        "Kết nối Gemini Live visual-only thất bại generation=$generation HTTP=${response?.code}",
                        error,
                    )
                    deliverError(error)
                }
            })
        }.getOrElse { error ->
            reconnecting.set(false)
            deliverError(error)
            return
        }
        socket = newSocket
    }

    private fun isCurrentConnection(generation: Long): Boolean =
        !explicitlyClosed.get() && connectionGeneration.get() == generation

    /**
     * Accepts the newest JPEG screen frame. If the model is idle the frame is sent immediately
     * with its text heartbeat. If the model is still speaking or the socket is rotating, only the
     * newest local frame is kept. There is intentionally no audio-input method in this client.
     */
    fun sendVideoFrame(jpeg: ByteArray): SendResult {
        if (jpeg.isEmpty()) return SendResult.SENT
        if (explicitlyClosed.get()) return SendResult.CLOSED

        acceptedFrames.incrementAndGet()
        val previous = latestPendingFrame.getAndSet(jpeg)
        if (previous != null) replacedWhileBusy.incrementAndGet()
        if (!setupComplete.get()) return SendResult.NOT_READY
        return drainLatestFrameIfIdle()
    }

    fun close(graceful: Boolean = true) {
        if (!explicitlyClosed.compareAndSet(false, true)) return
        terminalDelivered.set(true)
        connectionGeneration.incrementAndGet()
        setupComplete.set(false)
        turnInFlight.set(false)
        goAwayRequested.set(false)
        reconnecting.set(false)
        latestPendingFrame.set(null)
        latestResumptionHandle.set(null)
        val current = socket
        socket = null
        logger.log(
            2,
            TAG,
            "Đóng Live visual-only acceptedFrames=${acceptedFrames.get()} serverFrames=${serverFrames.get()} " +
                "heartbeats=${heartbeatCount.get()} replacedWhileBusy=${replacedWhileBusy.get()} " +
                "droppedBackpressure=${droppedByBackpressure.get()} audioChunks=${audioChunks.get()} " +
                "transcriptEvents=${transcriptEvents.get()} goAway=${goAwayCount.get()} " +
                "resumptionUpdates=${resumptionUpdates.get()} resumedConnections=${resumedConnections.get()} " +
                "maxQueued=${maxObservedWireBytes.get()}",
        )
        if (graceful) current?.close(1000, "client stop") else current?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun drainLatestFrameIfIdle(): SendResult {
        if (explicitlyClosed.get()) return SendResult.CLOSED
        if (!setupComplete.get()) return SendResult.NOT_READY
        if (goAwayRequested.get()) return SendResult.NOT_READY
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

    private fun parseMessage(text: String, generation: Long) {
        runCatching parse@{
            if (!isCurrentConnection(generation)) return@parse
            val root = JSONObject(text)
            root.optJSONObject("error")?.let { errorObject ->
                throw GeminiLiveClient.GeminiApiException(
                    errorObject.optInt("code", 0),
                    errorObject.optString("message", "Gemini API error"),
                )
            }

            root.optJSONObject("sessionResumptionUpdate")?.let { update ->
                handleSessionResumptionUpdate(update, generation)
            }
            root.optJSONObject("goAway")?.let { goAway ->
                handleGoAway(goAway, generation)
            }

            if (root.has("setupComplete")) {
                setupComplete.set(true)
                turnInFlight.set(false)
                val wasReconnect = reconnecting.getAndSet(false)
                if (wasReconnect) resumedConnections.incrementAndGet()
                logger.log(
                    2,
                    TAG,
                    "Setup hoàn tất generation=$generation resumed=$wasReconnect; " +
                        "chờ frame rồi gửi frame+heartbeat, micInput=false",
                )
                listener.onSetupComplete()
                drainPendingAfterTurn()
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
                if (goAwayRequested.get()) {
                    maybeReconnectAfterGoAway("turn-complete")
                    return@parse
                }
                drainPendingAfterTurn()
            }
        }.onFailure {
            logger.log(0, TAG, "Không phân tích được thông điệp Live length=${text.length}", it)
            deliverError(it)
        }
    }

    private fun handleSessionResumptionUpdate(update: JSONObject, generation: Long) {
        val count = resumptionUpdates.incrementAndGet()
        val resumable = update.optBoolean("resumable", false)
        val newHandle = update.optString("newHandle").takeIf(String::isNotBlank)
        if (resumable && newHandle != null) {
            latestResumptionHandle.set(newHandle)
            if (count == 1L || count % 20L == 0L || goAwayRequested.get()) {
                logger.log(
                    3,
                    TAG,
                    "Session resumption update generation=$generation count=$count resumable=true " +
                        "handleChars=${newHandle.length}",
                )
            }
            if (goAwayRequested.get()) maybeReconnectAfterGoAway("resumption-handle-ready")
        } else if (goAwayRequested.get()) {
            logger.log(
                2,
                TAG,
                "GoAway đang chờ nhưng session hiện chưa resumable generation=$generation count=$count",
            )
        }
    }

    private fun handleGoAway(goAway: JSONObject, generation: Long) {
        val count = goAwayCount.incrementAndGet()
        goAwayRequested.set(true)
        val timeLeft = goAway.opt("timeLeft")?.toString()?.takeIf(String::isNotBlank) ?: "unknown"
        logger.log(
            1,
            TAG,
            "GO_AWAY generation=$generation count=$count timeLeft=$timeLeft turnInFlight=${turnInFlight.get()} " +
                "hasResumeHandle=${!latestResumptionHandle.get().isNullOrBlank()}",
        )
        maybeReconnectAfterGoAway("goaway-idle")
    }

    private fun maybeReconnectAfterGoAway(reason: String): Boolean {
        if (!goAwayRequested.get() || explicitlyClosed.get()) return false
        if (turnInFlight.get()) return false
        val handle = latestResumptionHandle.get()
        if (handle.isNullOrBlank()) {
            logger.log(2, TAG, "GoAway chờ session handle mới; chưa mở lượt hình ảnh tiếp theo")
            return false
        }
        reconnectFromGoAway(reason)
        return true
    }

    private fun reconnectFromGoAway(reason: String) {
        if (explicitlyClosed.get()) return
        if (!reconnecting.compareAndSet(false, true)) return

        val handle = latestResumptionHandle.get()
        val oldSocket = socket
        goAwayRequested.set(false)
        setupComplete.set(false)
        turnInFlight.set(false)
        logger.log(
            1,
            TAG,
            "Đổi WebSocket Live reason=$reason resume=${!handle.isNullOrBlank()} " +
                "pendingFrame=${latestPendingFrame.get() != null}",
        )

        // openConnection increments connectionGeneration before the old socket is closed, so late
        // callbacks from the superseded socket are ignored and cannot terminate the resumed session.
        openConnection(resumptionHandle = handle, reason = reason)
        val replacement = socket
        if (oldSocket != null && oldSocket !== replacement) {
            runCatching { oldSocket.close(1000, "session rotation") }
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
        reconnecting.set(false)
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
        const val VERSION = "2026-09-16-gemini-3.8-live-visual-only-v7-custom-prompt"
        private const val TAG = "LiveScreenDescription"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val DEFAULT_MAX_QUEUED_WIRE_BYTES = 512L * 1024L
        private const val MIN_QUEUED_WIRE_BYTES = 64L * 1024L

        internal fun createSetupMessage(
            outputLanguage: String,
            resumptionHandle: String? = null,
            customPrompt: String? = null,
        ): String {
            val sessionResumption = JSONObject().apply {
                resumptionHandle?.takeIf(String::isNotBlank)?.let { put("handle", it) }
            }
            val generationConfig = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
            val instruction = customPrompt
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: systemInstruction(outputLanguage)
            val setup = JSONObject()
                .put("model", "models/$MODEL")
                .put("generationConfig", generationConfig)
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", instruction)),
                    ),
                )
                .put("outputAudioTranscription", JSONObject())
                .put("sessionResumption", sessionResumption)
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
