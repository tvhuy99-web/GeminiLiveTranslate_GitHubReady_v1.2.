package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.VideoDescriptionPromptDefaults
import com.oai.geminilivetranslate.core.VideoDescriptionTimelineRules
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

class GeminiVideoDescriptionClient(
    private val apiKey: String,
    private val logger: SessionLogger,
    private val includeOutputInLogs: Boolean,
    private val model: String = AppPreferences.VIDEO_DESCRIPTION_MODEL,
    private val timelinePromptTemplate: String = VideoDescriptionPromptDefaults.TIMELINE,
    private val summaryPromptTemplate: String = VideoDescriptionPromptDefaults.SUMMARY,
    private val streamingEnabled: Boolean = true,
    private val requestTimeoutMs: Int = 300_000,
) {
    enum class Mode {
        TIMELINE,
        SUMMARY,
    }

    data class TimelineItem(
        val index: Int,
        val startSeconds: Double,
        val endSeconds: Double,
        val text: String,
    )

    data class RemoteFile(
        val name: String?,
        val uri: String,
        val mimeType: String,
        val uploadedAtMs: Long,
    )

    data class Result(
        val timelineItems: List<TimelineItem>,
        val summaryText: String,
        val interactionId: String?,
        val attempts: Int,
        val elapsedMs: Long,
        val inputTokens: Int,
        val outputTokens: Int,
        val thoughtTokens: Int,
        val totalTokens: Int,
    )

    private data class UploadSource(
        val displayName: String,
        val mimeType: String,
        val contentLength: Long,
        val open: () -> InputStream,
    )

    private data class UploadedFile(
        val name: String?,
        val uri: String,
        val mimeType: String,
    )

    private data class InteractionResult(
        val root: JSONObject,
        val id: String?,
        val elapsedMs: Long,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(requestTimeoutMs.coerceIn(30_000, 900_000).toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(requestTimeoutMs.coerceIn(30_000, 900_000).toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    @Volatile private var cancelled = false

    fun describe(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String,
        durationMs: Long,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit = {},
        remoteFile: RemoteFile? = null,
        onRemoteFileReady: (RemoteFile) -> Unit = {},
    ): Result {
        require(apiKey.isNotBlank()) { "API Key đang trống" }
        require(mimeType in SUPPORTED_VIDEO_MIME_TYPES) {
            "Định dạng video chưa được Gemini Interactions API hỗ trợ trực tiếp: $mimeType"
        }
        require(durationMs in 1..MAX_VIDEO_DURATION_MS) {
            "Video phải dài tối đa 20 phút"
        }

        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull() ?: -1L

        return describe(
            source = UploadSource(
                displayName = displayName,
                mimeType = mimeType,
                contentLength = length,
                open = { resolver.openInputStream(uri) ?: error("Không mở được video đã chọn") },
            ),
            durationMs = durationMs,
            mode = mode,
            onProgress = onProgress,
            onPartial = onPartial,
            remoteFile = remoteFile,
            onRemoteFileReady = onRemoteFileReady,
        )
    }

    private fun describe(
        source: UploadSource,
        durationMs: Long,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        remoteFile: RemoteFile?,
        onRemoteFileReady: (RemoteFile) -> Unit,
    ): Result {
        val startedAt = SystemClock.elapsedRealtime()
        val durationSeconds = durationMs / 1_000.0
        logger.log(
            2,
            TAG,
            "Bắt đầu model=$model mode=$mode name=${source.displayName} mime=${source.mimeType} bytes=${source.contentLength} durationMs=$durationMs wholeVideo=true maxDurationMs=$MAX_VIDEO_DURATION_MS streaming=$streamingEnabled timeoutMs=$requestTimeoutMs",
        )

        val reusable = reusableUploadedFile(remoteFile, source.mimeType)
        val uploaded = if (reusable != null) {
            onProgress("Đang dùng lại video đã tải lên Gemini...", 55)
            logger.log(
                2,
                TAG,
                "Dùng lại video đã tải fileName=${reusable.name ?: "none"} fileUri=${reusable.uri.take(100)} mime=${reusable.mimeType}",
            )
            reusable
        } else {
            onProgress("Đang tải nguyên video lên...", 2)
            upload(source, onProgress).also { waitUntilActive(it, onProgress) }
        }
        val readyRemote = RemoteFile(
            name = uploaded.name,
            uri = uploaded.uri,
            mimeType = uploaded.mimeType,
            uploadedAtMs = if (reusable != null) {
                remoteFile?.uploadedAtMs ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            },
        )
        onRemoteFileReady(readyRemote)

            val prompt = VideoDescriptionPromptDefaults.render(
                if (mode == Mode.TIMELINE) timelinePromptTemplate else summaryPromptTemplate,
                durationSeconds,
            )
            val responseFormat = when {
                mode == Mode.TIMELINE -> timelineResponseFormat()
                streamingEnabled -> null
                else -> summaryResponseFormat()
            }
            logger.log(
                2,
                TAG,
                "Chuẩn bị Interactions mode=$mode promptChars=${prompt.length} structuredOutput=${responseFormat != null} durationSeconds=${formatSeconds(durationSeconds)} streaming=$streamingEnabled",
            )

            var lastError: Throwable? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                throwIfCancelled()
                val attemptStartedAt = SystemClock.elapsedRealtime()
                try {
                    onProgress(
                        if (mode == Mode.TIMELINE) "Đang mô tả toàn bộ video..." else "Đang tổng hợp toàn bộ video...",
                        58,
                    )
                    val useStreaming = streamingEnabled && attempt == 1
                    logger.log(2, TAG, "Gửi Interactions attempt=$attempt/$MAX_ATTEMPTS mode=$mode stream=$useStreaming")
                    val interaction = createAndAwait(
                        fileUri = uploaded.uri,
                        mimeType = uploaded.mimeType,
                        prompt = prompt,
                        responseFormat = responseFormat,
                        mode = mode,
                        onProgress = onProgress,
                        onPartial = onPartial,
                        useStreaming = useStreaming,
                    )
                    val outputText = extractOutputText(interaction.root)
                    val usage = interaction.root.optJSONObject("usage")
                    val inputTokens = usage?.optInt("total_input_tokens", 0) ?: 0
                    val outputTokens = usage?.optInt("total_output_tokens", 0) ?: 0
                    val thoughtTokens = usage?.optInt("total_thought_tokens", 0) ?: 0
                    val totalTokens = usage?.optInt("total_tokens", 0) ?: 0

                    logger.log(
                        2,
                        TAG,
                        "Nhận kết quả attempt=$attempt mode=$mode interactionId=${interaction.id ?: "none"} outputChars=${outputText.length} inputTokens=$inputTokens outputTokens=$outputTokens thoughtTokens=$thoughtTokens totalTokens=$totalTokens apiElapsedMs=${interaction.elapsedMs}",
                    )
                    if (includeOutputInLogs) {
                        logger.log(3, TAG, "Output preview=${sanitizeForLog(outputText, 2_000)}")
                    }

                    val timelineItems: List<TimelineItem>
                    val summaryText: String
                    when (mode) {
                        Mode.TIMELINE -> {
                            timelineItems = parseAndValidateTimeline(outputText, durationSeconds)
                            summaryText = ""
                        }
                        Mode.SUMMARY -> {
                            timelineItems = emptyList()
                            summaryText = parseAndValidateSummary(outputText)
                        }
                    }

                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    onProgress("Đang tạo kết quả...", 98)
                    logger.log(
                        2,
                        TAG,
                        "Hoàn tất mode=$mode attempt=$attempt items=${timelineItems.size} summaryChars=${summaryText.length} attemptElapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt} totalElapsedMs=$elapsedMs",
                    )
                    return Result(
                        timelineItems = timelineItems,
                        summaryText = summaryText,
                        interactionId = interaction.id,
                        attempts = attempt,
                        elapsedMs = elapsedMs,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        thoughtTokens = thoughtTokens,
                        totalTokens = totalTokens,
                    )
                } catch (error: Throwable) {
                    if (cancelled) throw java.util.concurrent.CancellationException(
                        "Đã hủy mô tả video"
                    )
                    if (GeminiApiErrorClassifier.requiresKeyFailover(error)) throw error
                    lastError = error
                    logger.log(
                        if (attempt < MAX_ATTEMPTS) 1 else 0,
                        TAG,
                        "Xử lý video thất bại attempt=$attempt/$MAX_ATTEMPTS mode=$mode elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt} reason=${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            }
            throw lastError ?: IllegalStateException("Gemini không tạo được mô tả video")
    }

    fun cancel() {
        cancelled = true
        client.dispatcher.cancelAll()
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun upload(
        source: UploadSource,
        onProgress: (String, Int) -> Unit,
    ): UploadedFile {
        val startedAt = SystemClock.elapsedRealtime()
        val metadata = JSONObject()
            .put("file", JSONObject().put("display_name", source.displayName))
            .toString()
        val startBuilder = Request.Builder()
            .url(UPLOAD_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Type", source.mimeType)
        if (source.contentLength >= 0L) {
            startBuilder.header("X-Goog-Upload-Header-Content-Length", source.contentLength.toString())
        }
        val startRequest = startBuilder
            .post(metadata.toRequestBody(JSON_MEDIA))
            .build()

        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Không bắt đầu được tải video: HTTP ${response.code} ${sanitizeForLog(body, 600)}"
                )
            }
            response.header("x-goog-upload-url")
                ?: response.header("X-Goog-Upload-URL")
                ?: error("Gemini không trả về địa chỉ tải video")
        }

        var lastBucket = -1
        val requestBody = ProgressStreamRequestBody(
            source = source,
            mediaType = source.mimeType.toMediaType(),
        ) { sent, total ->
            val ratio = if (total <= 0L) 0.0 else sent.toDouble() / total.toDouble()
            val percent = if (total <= 0L) 25 else (2 + ratio * 43.0).toInt().coerceIn(2, 45)
            onProgress("Đang tải nguyên video lên...", percent)
            if (total > 0L) {
                val bucket = ((sent * 4L) / total).toInt().coerceIn(0, 4)
                if (bucket > lastBucket && bucket > 0) {
                    lastBucket = bucket
                    logger.log(
                        3,
                        TAG,
                        "Upload progress=${bucket * 25}% sent=$sent total=$total elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                }
            }
        }

        val uploadBuilder = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
        if (source.contentLength >= 0L) {
            uploadBuilder.header("Content-Length", source.contentLength.toString())
        }
        val uploadRequest = uploadBuilder.post(requestBody).build()

        val root = client.newCall(uploadRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Tải video thất bại: HTTP ${response.code} ${sanitizeForLog(body, 600)}"
                )
            }
            JSONObject(body)
        }
        val file = root.optJSONObject("file") ?: root
        val uri = file.optString("uri").takeIf(String::isNotBlank)
            ?: error("Gemini không trả URI video")
        val name = file.optString("name").takeIf(String::isNotBlank)
        val mimeType = file.optString("mimeType").takeIf(String::isNotBlank)
            ?: file.optString("mime_type").takeIf(String::isNotBlank)
            ?: source.mimeType
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val throughputMbps = if (source.contentLength > 0L && elapsedMs > 0L) {
            source.contentLength * 8.0 / elapsedMs / 1_000.0
        } else 0.0

        logger.log(
            2,
            TAG,
            "Upload hoàn tất name=${source.displayName} fileName=${name ?: "none"} fileUri=${uri.take(100)} mime=$mimeType bytes=${source.contentLength} elapsedMs=$elapsedMs avgMbps=${String.format(java.util.Locale.US, "%.2f", throughputMbps)}",
        )
        return UploadedFile(name, uri, mimeType)
    }

    private fun waitUntilActive(
        uploaded: UploadedFile,
        onProgress: (String, Int) -> Unit,
    ) {
        val name = uploaded.name ?: return
        val clean = name.removePrefix("/")
        val startedAt = SystemClock.elapsedRealtime()
        for (poll in 1..MAX_FILE_POLLS) {
            throwIfCancelled()
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$clean")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            val root = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Không đọc được trạng thái video: HTTP ${response.code} ${sanitizeForLog(body, 500)}"
                    )
                }
                JSONObject(body)
            }
            val state = root.optString("state").uppercase()
            if (poll == 1 || poll % 5 == 0 || state == "ACTIVE") {
                logger.log(
                    3,
                    TAG,
                    "File processing poll=$poll state=${state.ifBlank { "UNKNOWN" }} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
            when (state) {
                "ACTIVE" -> {
                    onProgress("Video đã sẵn sàng để phân tích", 55)
                    return
                }
                "FAILED" -> error("Gemini xử lý video tải lên thất bại")
            }
            onProgress("Gemini đang chuẩn bị video...", (45 + poll / 6).coerceAtMost(55))
            Thread.sleep(FILE_POLL_INTERVAL_MS)
            throwIfCancelled()
        }
        error("Hết thời gian chờ Gemini chuẩn bị video")
    }

    private fun reusableUploadedFile(
        remote: RemoteFile?,
        fallbackMimeType: String,
    ): UploadedFile? {
        if (remote == null || remote.uri.isBlank() || remote.uploadedAtMs <= 0L) return null
        val ageMs = (System.currentTimeMillis() - remote.uploadedAtMs).coerceAtLeast(0L)
        if (ageMs >= REMOTE_FILE_MAX_AGE_MS) {
            logger.log(2, TAG, "Video Gemini đã quá thời gian tái sử dụng ageMs=$ageMs; sẽ tải lại")
            return null
        }
        val name = remote.name?.takeIf(String::isNotBlank) ?: return null
        val uploaded = UploadedFile(
            name = name,
            uri = remote.uri,
            mimeType = remote.mimeType.takeIf(String::isNotBlank) ?: fallbackMimeType,
        )
        val clean = name.removePrefix("/")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$clean")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                logger.log(
                    if (response.code == 400 || response.code == 403 || response.code == 404) 2 else 1,
                    TAG,
                    "Không tái sử dụng được video Gemini HTTP=${response.code} fileName=$name; ${if (response.code == 400 || response.code == 403 || response.code == 404) "sẽ tải lại" else "không thể xác minh"}",
                )
                if (response.code == 400 || response.code == 403 || response.code == 404) {
                    return@use null
                }
                throw IllegalStateException(
                    "Không kiểm tra được video đã tải: HTTP ${response.code} ${sanitizeForLog(body, 500)}"
                )
            }
            val state = runCatching { JSONObject(body).optString("state").uppercase() }
                .getOrDefault("")
            if (state == "ACTIVE") {
                uploaded
            } else {
                logger.log(2, TAG, "Video Gemini cache state=${state.ifBlank { "UNKNOWN" }}; sẽ tải lại")
                null
            }
        }
    }

    private fun createAndAwait(
        fileUri: String,
        mimeType: String,
        prompt: String,
        responseFormat: JSONObject?,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        useStreaming: Boolean,
    ): InteractionResult {
        if (useStreaming) {
            return createStreaming(
                fileUri = fileUri,
                mimeType = mimeType,
                prompt = prompt,
                responseFormat = responseFormat,
                mode = mode,
                onProgress = onProgress,
                onPartial = onPartial,
            )
        }

        throwIfCancelled()
        val startedAt = SystemClock.elapsedRealtime()
        val requestJson = baseInteractionRequest(fileUri, mimeType, prompt, responseFormat)
        onProgress(
            if (mode == Mode.TIMELINE) "Đang mô tả toàn bộ video..." else "Đang tổng hợp toàn bộ video...",
            58,
        )
        val request = Request.Builder()
            .url(INTERACTIONS_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        val root = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val requestId = response.header("x-request-id")
                ?: response.header("x-goog-request-id")
                ?: "none"
            logger.log(
                if (response.isSuccessful) 2 else 0,
                TAG,
                "Interactions POST direct=true stream=false store=false mode=$mode model=$model HTTP=${response.code} requestId=$requestId bodyChars=${body.length} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Gemini HTTP ${response.code}: ${sanitizeForLog(body, 1_000)}"
                )
            }
            JSONObject(body)
        }
        throwIfCancelled()
        onProgress("Đang tạo kết quả...", 96)
        return InteractionResult(
            root = root,
            id = root.optString("id").takeIf(String::isNotBlank),
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun createStreaming(
        fileUri: String,
        mimeType: String,
        prompt: String,
        responseFormat: JSONObject?,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
    ): InteractionResult {
        throwIfCancelled()
        val startedAt = SystemClock.elapsedRealtime()
        val requestJson = baseInteractionRequest(fileUri, mimeType, prompt, responseFormat)
            .put("stream", true)
        val request = Request.Builder()
            .url(INTERACTIONS_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Api-Revision", INTERACTIONS_STREAM_REVISION)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        onProgress(
            if (mode == Mode.TIMELINE) "Đang nhận mô tả theo thời gian..." else "Đang nhận mô tả tổng hợp...",
            58,
        )

        val output = StringBuilder()
        var interactionId: String? = null
        var usage: JSONObject? = null
        var lastPreview = ""
        var completed = false
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException(
                    "Gemini HTTP ${response.code}: ${sanitizeForLog(body, 1_000)}"
                )
            }
            logger.log(
                2,
                TAG,
                "Interactions POST direct=true stream=true store=false mode=$mode model=$model HTTP=${response.code} contentType=${response.header("Content-Type") ?: "none"} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            val source = response.body?.source() ?: error("Gemini không trả luồng dữ liệu")
            var eventName = ""
            while (true) {
                throwIfCancelled()
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("event:")) {
                    eventName = line.substringAfter("event:").trim()
                    continue
                }
                if (!line.startsWith("data:")) continue
                val data = line.substringAfter("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val eventType = event.optString("event_type").ifBlank { eventName }
                when (eventType) {
                    "interaction.created" -> {
                        interactionId = event.optJSONObject("interaction")
                            ?.optString("id")
                            ?.takeIf(String::isNotBlank)
                    }
                    "step.delta" -> {
                        val delta = event.optJSONObject("delta")
                        if (delta?.optString("type") == "text") {
                            val text = delta.optString("text")
                            if (text.isNotEmpty()) {
                                output.append(text)
                                val preview = if (mode == Mode.SUMMARY) {
                                    output.toString()
                                } else {
                                    timelinePreview(output.toString())
                                }
                                if (preview.isNotBlank() && preview != lastPreview) {
                                    lastPreview = preview
                                    onPartial(preview)
                                }
                            }
                        }
                    }
                    "interaction.completed" -> {
                        val interaction = event.optJSONObject("interaction")
                        if (interactionId.isNullOrBlank()) {
                            interactionId = interaction?.optString("id")?.takeIf(String::isNotBlank)
                        }
                        usage = interaction?.optJSONObject("usage") ?: event.optJSONObject("usage")
                        completed = true
                    }
                }
            }
        }
        throwIfCancelled()
        if (!completed) {
            error("Luồng Gemini bị ngắt trước khi interaction.completed")
        }
        val finalText = output.toString().trim()
        if (finalText.isBlank()) error("Gemini không trả nội dung mô tả video")
        val root = JSONObject().put("output_text", finalText)
        usage?.let { root.put("usage", it) }
        onPartial(if (mode == Mode.SUMMARY) finalText else timelinePreview(finalText))
        onProgress("Đang hoàn tất kết quả...", 96)
        return InteractionResult(
            root = root,
            id = interactionId,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun baseInteractionRequest(
        fileUri: String,
        mimeType: String,
        prompt: String,
        responseFormat: JSONObject?,
    ): JSONObject {
        val request = JSONObject()
            .put("model", model)
            .put("store", false)
            .put(
                "input",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "video")
                            .put("uri", fileUri)
                            .put("mime_type", mimeType)
                    )
                    .put(JSONObject().put("type", "text").put("text", prompt))
            )
        if (responseFormat != null) request.put("response_format", responseFormat)
        return request
    }

    private fun timelinePreview(partialJson: String): String {
        val regex = Regex("""\"text\"\s*:\s*\"((?:\\\\.|[^\"\\\\])*)\"""")
        return regex.findAll(partialJson)
            .mapNotNull { match ->
                val encoded = match.groupValues[1]
                runCatching { JSONArray("[\"$encoded\"]").getString(0) }.getOrNull()
            }
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    private fun extractOutputText(root: JSONObject): String {
        root.optString("output_text").takeIf(String::isNotBlank)?.let { return it.trim() }
        val parts = ArrayList<String>()
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type").isNotBlank() && step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val item = content.optJSONObject(j) ?: continue
                    if (item.optString("type") == "text") {
                        item.optString("text").takeIf(String::isNotBlank)?.let(parts::add)
                    }
                }
            }
        }
        return parts.joinToString("").trim().ifBlank {
            error("Gemini không trả nội dung mô tả video")
        }
    }

    private fun parseAndValidateTimeline(
        outputText: String,
        durationSeconds: Double,
    ): List<TimelineItem> {
        val root = JSONObject(outputText)
        val items = root.optJSONArray("items") ?: error("Kết quả thiếu trường items")
        if (items.length() == 0) error("Gemini không tạo mục mô tả nào")

        val parsed = ArrayList<VideoDescriptionTimelineRules.Item>(items.length())
        val structuralErrors = ArrayList<String>()
        for (i in 0 until items.length()) {
            val raw = items.optJSONObject(i)
            if (raw == null) {
                structuralErrors += "item[$i] không phải object"
                continue
            }
            parsed += VideoDescriptionTimelineRules.Item(
                index = raw.optInt("index", Int.MIN_VALUE),
                startSeconds = raw.optDouble("start_seconds", Double.NaN),
                endSeconds = raw.optDouble("end_seconds", Double.NaN),
                text = raw.optString("text").trim(),
            )
        }

        val validation = VideoDescriptionTimelineRules.validate(
            items = parsed,
            durationSeconds = durationSeconds,
            maxItemSeconds = MAX_ITEM_SECONDS,
            toleranceSeconds = TIMECODE_TOLERANCE_SECONDS,
        )
        val errors = structuralErrors + validation.errors
        logger.log(
            if (errors.isEmpty()) 2 else 1,
            TAG_VALIDATE,
            "Timeline validate returned=${items.length()} parsed=${parsed.size} errors=${errors.size} firstStart=${parsed.firstOrNull()?.startSeconds ?: -1.0} lastEnd=${validation.lastEndSeconds} duration=$durationSeconds endCoverage=${String.format(java.util.Locale.US, "%.3f", validation.endCoverage)} errorsPreview=${errors.take(12).joinToString(" | ").ifBlank { "none" }}",
        )

        if (errors.isNotEmpty()) {
            error("Kết quả mô tả theo thời gian không hợp lệ: ${errors.take(6).joinToString("; ")}")
        }

        return parsed.map { item ->
            TimelineItem(
                index = item.index,
                startSeconds = item.startSeconds.coerceAtLeast(0.0),
                endSeconds = item.endSeconds.coerceAtMost(durationSeconds),
                text = item.text,
            )
        }
    }

    private fun parseAndValidateSummary(outputText: String): String {
        val text = runCatching { JSONObject(outputText).optString("text").trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: outputText.trim()
        if (text.isBlank()) error("Gemini trả bản tổng hợp rỗng")
        logger.log(2, TAG_VALIDATE, "Summary validate chars=${text.length} words=${text.split(Regex("\\s+")).count { it.isNotBlank() }}")
        return text
    }

    private fun timelineResponseFormat(): JSONObject =
        JSONObject()
            .put("type", "text")
            .put("mime_type", "application/json")
            .put(
                "schema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "items",
                            JSONObject()
                                .put("type", "array")
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put("index", JSONObject().put("type", "integer"))
                                                .put("start_seconds", JSONObject().put("type", "number"))
                                                .put("end_seconds", JSONObject().put("type", "number"))
                                                .put(
                                                    "type",
                                                    JSONObject()
                                                        .put("type", "string")
                                                        .put("enum", JSONArray().put("description"))
                                                )
                                                .put("text", JSONObject().put("type", "string"))
                                        )
                                        .put(
                                            "required",
                                            JSONArray()
                                                .put("index")
                                                .put("start_seconds")
                                                .put("end_seconds")
                                                .put("type")
                                                .put("text")
                                        )
                                        .put("additionalProperties", false)
                                )
                        )
                    )
                    .put("required", JSONArray().put("items"))
                    .put("additionalProperties", false)
            )

    private fun summaryResponseFormat(): JSONObject =
        JSONObject()
            .put("type", "text")
            .put("mime_type", "application/json")
            .put(
                "schema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("text"))
                    .put("additionalProperties", false)
            )

    private fun deleteUploadedFile(name: String) {
        val clean = name.removePrefix("/")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$clean")
            .header("x-goog-api-key", apiKey)
            .delete()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                logger.log(3, TAG, "Xóa video tạm Gemini HTTP=${response.code}")
            }
        }.onFailure {
            logger.log(1, TAG, "Không xóa được video tạm Gemini name=$clean", it)
        }
    }

    private fun throwIfCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Đã hủy mô tả video")
    }

    private fun sanitizeForLog(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)

    private fun formatSeconds(value: Double): String =
        String.format(java.util.Locale.US, "%.3f", value)

    private class ProgressStreamRequestBody(
        private val source: UploadSource,
        private val mediaType: MediaType,
        private val progress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = source.contentLength

        override fun writeTo(sink: BufferedSink) {
            source.open().use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    progress(sent, source.contentLength)
                }
            }
        }
    }

    companion object {
        private val SUPPORTED_VIDEO_MIME_TYPES = setOf(
            "video/mp4",
            "video/mpeg",
            "video/mpg",
            "video/mov",
            "video/avi",
            "video/x-flv",
            "video/webm",
            "video/wmv",
            "video/3gpp",
        )
        const val MAX_VIDEO_DURATION_MS = 20L * 60L * 1_000L
        private const val MAX_ITEM_SECONDS = 15.0
        private const val TIMECODE_TOLERANCE_SECONDS = 0.10
        private const val MAX_ATTEMPTS = 2
        private const val REMOTE_FILE_MAX_AGE_MS = 48L * 60L * 60L * 1_000L
        private const val MAX_FILE_POLLS = 180
        private const val FILE_POLL_INTERVAL_MS = 2_000L
        private const val TAG = "VideoDescription"
        private const val TAG_VALIDATE = "VideoDescriptionValidate"
        private const val UPLOAD_ENDPOINT =
            "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val INTERACTIONS_STREAM_REVISION = "2026-05-20"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
