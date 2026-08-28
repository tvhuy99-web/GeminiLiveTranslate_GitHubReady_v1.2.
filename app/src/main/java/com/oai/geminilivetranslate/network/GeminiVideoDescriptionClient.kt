package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
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
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun describe(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String,
        durationMs: Long,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
    ): Result {
        require(apiKey.isNotBlank()) { "API Key đang trống" }
        require(mimeType.startsWith("video/")) { "Tệp đã chọn không phải video" }
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
        )
    }

    private fun describe(
        source: UploadSource,
        durationMs: Long,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
    ): Result {
        val startedAt = SystemClock.elapsedRealtime()
        val durationSeconds = durationMs / 1_000.0
        logger.log(
            2,
            TAG,
            "Bắt đầu model=${AppPreferences.VIDEO_DESCRIPTION_MODEL} mode=$mode name=${source.displayName} mime=${source.mimeType} bytes=${source.contentLength} durationMs=$durationMs wholeVideo=true maxDurationMs=$MAX_VIDEO_DURATION_MS",
        )

        var uploadedName: String? = null
        try {
            onProgress("Đang tải nguyên video lên...", 2)
            val uploaded = upload(source, onProgress)
            uploadedName = uploaded.name
            waitUntilActive(uploaded, onProgress)

            val prompt = when (mode) {
                Mode.TIMELINE -> timelinePrompt(durationSeconds)
                Mode.SUMMARY -> summaryPrompt(durationSeconds)
            }
            val responseFormat = when (mode) {
                Mode.TIMELINE -> timelineResponseFormat()
                Mode.SUMMARY -> summaryResponseFormat()
            }
            logger.log(
                2,
                TAG,
                "Chuẩn bị Interactions mode=$mode promptChars=${prompt.length} structuredOutput=true durationSeconds=${formatSeconds(durationSeconds)}",
            )

            var lastError: Throwable? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                val attemptStartedAt = SystemClock.elapsedRealtime()
                try {
                    onProgress(
                        if (mode == Mode.TIMELINE) "Đang mô tả toàn bộ video..." else "Đang tổng hợp toàn bộ video...",
                        58,
                    )
                    logger.log(2, TAG, "Gửi Interactions attempt=$attempt/$MAX_ATTEMPTS mode=$mode")
                    val interaction = createAndAwait(
                        fileUri = uploaded.uri,
                        mimeType = uploaded.mimeType,
                        prompt = prompt,
                        responseFormat = responseFormat,
                        mode = mode,
                        onProgress = onProgress,
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
        } finally {
            uploadedName?.let(::deleteUploadedFile)
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
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
                "", "ACTIVE" -> {
                    onProgress("Video đã sẵn sàng để phân tích", 55)
                    return
                }
                "FAILED" -> error("Gemini xử lý video tải lên thất bại")
            }
            onProgress("Gemini đang chuẩn bị video...", (45 + poll / 6).coerceAtMost(55))
            Thread.sleep(FILE_POLL_INTERVAL_MS)
        }
        error("Hết thời gian chờ Gemini chuẩn bị video")
    }

    private fun createAndAwait(
        fileUri: String,
        mimeType: String,
        prompt: String,
        responseFormat: JSONObject,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
    ): InteractionResult {
        val startedAt = SystemClock.elapsedRealtime()
        val requestJson = JSONObject()
            .put("model", AppPreferences.VIDEO_DESCRIPTION_MODEL)
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
            .put("response_format", responseFormat)

        val request = Request.Builder()
            .url(INTERACTIONS_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        var root = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val requestId = response.header("x-request-id")
                ?: response.header("x-goog-request-id")
                ?: "none"
            logger.log(
                if (response.isSuccessful) 2 else 0,
                TAG,
                "Interactions POST mode=$mode HTTP=${response.code} requestId=$requestId bodyChars=${body.length} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Gemini HTTP ${response.code}: ${sanitizeForLog(body, 1_000)}"
                )
            }
            JSONObject(body)
        }

        val interactionId = root.optString("id").takeIf(String::isNotBlank)
        for (poll in 0..MAX_INTERACTION_POLLS) {
            when (root.optString("status").lowercase()) {
                "", "completed" -> return InteractionResult(
                    root = root,
                    id = interactionId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
                "failed", "cancelled", "incomplete" -> {
                    val message = root.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf(String::isNotBlank)
                        ?: "Gemini kết thúc với status=${root.optString("status")}"
                    throw IllegalStateException(message)
                }
            }
            if (poll >= MAX_INTERACTION_POLLS) break
            val id = interactionId ?: error("Gemini chưa hoàn tất nhưng không trả interaction id")
            Thread.sleep(INTERACTION_POLL_INTERVAL_MS)
            onProgress(
                if (mode == Mode.TIMELINE) "Đang mô tả toàn bộ video..." else "Đang tổng hợp toàn bộ video...",
                (58 + poll / 5).coerceAtMost(96),
            )
            val cleanId = id.substringAfterLast('/')
            val pollRequest = Request.Builder()
                .url("$INTERACTIONS_ENDPOINT/$cleanId")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            root = client.newCall(pollRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Không đọc được tiến trình Gemini: HTTP ${response.code} ${sanitizeForLog(body, 500)}"
                    )
                }
                JSONObject(body)
            }
            if (poll == 0 || (poll + 1) % 10 == 0 || root.optString("status") == "completed") {
                logger.log(
                    3,
                    TAG,
                    "Interaction poll=${poll + 1} mode=$mode status=${root.optString("status")} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
        }
        error("Hết thời gian chờ Gemini phân tích video")
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

        val result = ArrayList<TimelineItem>(items.length())
        val errors = ArrayList<String>()
        var previousStart = -1.0

        for (i in 0 until items.length()) {
            val raw = items.optJSONObject(i) ?: run {
                errors += "item[$i] không phải object"
                continue
            }
            val index = raw.optInt("index", Int.MIN_VALUE)
            val start = raw.optDouble("start_seconds", Double.NaN)
            val end = raw.optDouble("end_seconds", Double.NaN)
            val text = raw.optString("text").trim()

            if (index != i + 1) errors += "index=$index expected=${i + 1}"
            if (!start.isFinite() || start < 0.0) errors += "index=$index start=$start"
            if (!end.isFinite() || end <= start) errors += "index=$index end=$end start=$start"
            if (end > durationSeconds + TIMECODE_TOLERANCE_SECONDS) {
                errors += "index=$index end=$end duration=$durationSeconds"
            }
            if (end - start > MAX_ITEM_SECONDS + TIMECODE_TOLERANCE_SECONDS) {
                errors += "index=$index length=${end - start}"
            }
            if (start + TIMECODE_TOLERANCE_SECONDS < previousStart) {
                errors += "index=$index time-order start=$start previous=$previousStart"
            }
            if (text.isBlank()) errors += "index=$index text-blank"
            previousStart = start

            result += TimelineItem(
                index = index,
                startSeconds = start.coerceAtLeast(0.0),
                endSeconds = end.coerceAtMost(durationSeconds),
                text = text,
            )
        }

        val lastEnd = result.lastOrNull()?.endSeconds ?: 0.0
        val coverage = if (durationSeconds > 0.0) lastEnd / durationSeconds else 0.0
        logger.log(
            if (errors.isEmpty()) 2 else 1,
            TAG_VALIDATE,
            "Timeline validate returned=${items.length()} parsed=${result.size} errors=${errors.size} firstStart=${result.firstOrNull()?.startSeconds ?: -1.0} lastEnd=$lastEnd duration=$durationSeconds endCoverage=${String.format(java.util.Locale.US, "%.3f", coverage)} errorsPreview=${errors.take(12).joinToString(" | ").ifBlank { "none" }}",
        )

        if (errors.isNotEmpty()) {
            error("Kết quả mô tả theo thời gian không hợp lệ: ${errors.take(6).joinToString("; ")}")
        }
        return result
    }

    private fun parseAndValidateSummary(outputText: String): String {
        val root = JSONObject(outputText)
        val text = root.optString("text").trim()
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

    private fun timelinePrompt(durationSeconds: Double): String = """
Bạn là chuyên gia Mô tả Âm thanh (Audio Description Specialist) dành cho người khiếm thị.

NHIỆM VỤ

Quan sát TOÀN BỘ video được cung cấp, từ giây 0 đến hết video, và tạo một chuỗi mô tả hình ảnh theo thời gian bằng tiếng Việt.

Video được gửi nguyên vẹn trong một lần xử lý.
Thời lượng thực tế của video: ${formatSeconds(durationSeconds)} giây.

Mục tiêu là giúp một người không nhìn thấy màn hình có thể hình dung chính xác những gì đang diễn ra: nhân vật, ngoại hình, trang phục, tư thế, hành động, biểu cảm nhìn thấy được, vật thể, không gian, ánh sáng, chuyển động máy quay, thay đổi cảnh và chữ xuất hiện trên màn hình.

TRUNG THỰC VỚI HÌNH ẢNH

Chỉ mô tả những gì thực sự có bằng chứng quan sát được trong video.
Không suy diễn chi tiết không nhìn thấy rõ.
Không phát minh chuyển động, vật thể, biểu cảm, chữ viết hoặc đặc điểm cơ thể chỉ để làm mô tả dài hơn.
Nếu không chắc chắn về một chi tiết nhỏ, hãy bỏ chi tiết đó hoặc diễn đạt mức độ chắc chắn phù hợp.
Không dùng kiến thức bên ngoài để bổ sung điều video không thể hiện.

PHẢI QUAN SÁT TOÀN BỘ VIDEO

Không được chỉ mô tả những phút đầu rồi dừng.
Không được bỏ qua một đoạn giữa có thay đổi hình ảnh đáng kể.
Luôn đối chiếu timestamp với tổng thời lượng đã cung cấp.
Nếu phần cuối video có nội dung hình ảnh đáng mô tả, kết quả phải có sự kiện gần cuối video.

MÔ TẢ CHI TIẾT, KHÔNG TÓM TẮT

Đây không phải nhiệm vụ tóm tắt.
Khi cảnh có nhiều thông tin thị giác, mô tả đầy đủ những chi tiết có ích cho việc hình dung.
Không cắt bớt mô tả chỉ vì khoảng thời gian của cue ngắn. Việc lời đọc có thể kéo dài hơn cue được chấp nhận.

Ưu tiên:
1. Hành động hoặc thay đổi quan trọng.
2. Nhân vật và tương tác.
3. Biểu cảm và ngôn ngữ cơ thể nhìn thấy được.
4. Không gian và vị trí tương đối.
5. Trang phục, ngoại hình và đặc điểm nhận dạng hữu ích.
6. Ánh sáng, màu sắc, bề mặt và chất liệu khi có ý nghĩa.
7. Chuyển động camera, góc nhìn và thay đổi bố cục.
8. Chữ viết quan trọng trên màn hình.

SHOW, DON'T TELL

Không tự gán cảm xúc nếu có thể mô tả dấu hiệu thị giác.
Thay vì nói một người 'tức giận', hãy mô tả những biểu hiện thực sự nhìn thấy như quai hàm siết, bàn tay nắm lại hoặc ánh nhìn căng thẳng.
Chỉ mô tả dấu hiệu có thật trong video.

NGOẠI HÌNH VÀ CƠ THỂ

Khi ngoại hình có ý nghĩa với cảnh hoặc giúp nhận dạng nhân vật, mô tả khách quan khuôn mặt, tóc, vóc dáng, trang phục, tư thế, chuyển động và đặc điểm nổi bật.
Không ưu tiên một giới tính cụ thể.
Không tập trung không cần thiết vào một bộ phận cơ thể nếu nó không quan trọng đối với hình ảnh hoặc diễn biến.
Nếu video có bạo lực, thương tích hoặc nội dung trưởng thành, mô tả trực tiếp và chính xác những gì thực sự nhìn thấy, không né tránh nhưng cũng không phóng đại hay thêm chi tiết.

BỐI CẢNH, OCR VÀ ÂM THANH

Khi bối cảnh xuất hiện hoặc thay đổi đáng kể, mô tả địa điểm, bố cục, vật thể đáng chú ý, vị trí tương đối, ánh sáng, thời tiết, màu sắc và chuyển động.
Không lặp lại chi tiết không thay đổi.

Nếu chữ trên màn hình đọc rõ và hữu ích, ghi trong text theo dạng [OCR: nội dung].
Không đoán chữ bị mờ.

Đây là chế độ MÔ TẢ HÌNH ẢNH, không phải chép lời.
Không chép lại toàn bộ lời thoại.
Chỉ đề cập lời nói hoặc âm thanh khi cần để giải thích hành động thị giác hoặc xác định điều đang xảy ra.
Ưu tiên thông tin mà người chỉ nghe audio gốc sẽ không biết.

PHÂN ĐOẠN VÀ TIMECODE

Mỗi item đại diện cho một khoảng hình ảnh có ý nghĩa.
Một item không được dài quá 15 giây.
Nếu cảnh kéo dài hơn 15 giây và tiếp tục có thay đổi thị giác đáng mô tả, chia thành nhiều item.
Không phát minh chi tiết mới chỉ để tạo đủ số lượng item.
Không cần tạo item cho khoảng hoàn toàn tĩnh hoặc không có thông tin thị giác mới đáng kể.

start_seconds và end_seconds là số giây tính từ đầu video.

Bắt buộc:
- start_seconds >= 0
- end_seconds > start_seconds
- end_seconds <= ${formatSeconds(durationSeconds)}
- end_seconds - start_seconds <= 15
- item theo thứ tự thời gian
- index liên tục từ 1

PHONG CÁCH

Viết tiếng Việt tự nhiên, giàu hình ảnh nhưng chính xác.
Ưu tiên động từ cụ thể và chi tiết quan sát được.
Không biến mô tả thành báo cáo máy móc.
Không biến mô tả thành tiểu thuyết bằng cách thêm chi tiết không có thật.
Không bình luận đạo đức hoặc đánh giá chủ quan.

KIỂM TRA TRƯỚC KHI TRẢ KẾT QUẢ

Tự kiểm tra đã quan sát toàn bộ video, index liên tục, timestamp hợp lệ, không item nào quá 15 giây, không bỏ qua những đoạn có thay đổi hình ảnh đáng kể, không phát minh chi tiết, OCR chỉ chứa chữ nhìn đủ rõ và toàn bộ text bằng tiếng Việt.

Chỉ trả dữ liệu theo JSON Schema do API cung cấp.
Không thêm lời chào, giải thích, markdown hay văn bản ngoài dữ liệu có cấu trúc.
""".trim()

    private fun summaryPrompt(durationSeconds: Double): String = """
Bạn là chuyên gia kể chuyện bằng hình ảnh và âm thanh dành cho người khiếm thị.

NHIỆM VỤ

Quan sát và lắng nghe TOÀN BỘ video được cung cấp, từ đầu đến cuối, sau đó tạo một bản tường thuật tổng hợp bằng tiếng Việt giúp người không nhìn thấy màn hình hiểu đầy đủ chuyện gì đang diễn ra, ai xuất hiện, nhân vật làm gì, họ nói gì quan trọng, các hành động và lời thoại liên hệ với nhau ra sao, bối cảnh thay đổi thế nào và kết quả cuối cùng là gì.

Video được gửi nguyên vẹn trong một lần xử lý.
Thời lượng thực tế: ${formatSeconds(durationSeconds)} giây.

Hãy hình dung bạn đang kể lại video cho một người bạn khiếm thị chưa từng xem nó. Kết quả phải giống một bài kể/review video mạch lạc, giàu hình ảnh và dễ nghe bằng TTS, không phải bản chép lời và cũng không phải danh sách cảnh rời rạc.

HIỂU TOÀN BỘ TRƯỚC KHI KỂ

Trước hết hãy hiểu toàn bộ video: chủ đề hoặc câu chuyện, các nhân vật, mối quan hệ nếu video thể hiện, diễn biến, nguyên nhân và kết quả, cùng những chi tiết hình ảnh hoặc lời thoại có ý nghĩa về sau.
Sau đó mới xây dựng bản kể hoàn chỉnh.
Không được dừng phân tích giữa video.
Có thể dùng ngữ cảnh toàn bộ để tránh hiểu sai nhưng không được tiết lộ trước những điều mà người xem tại thời điểm đó chưa biết.

HÒA TRỘN HÌNH ẢNH VÀ LỜI NÓI

Đây không phải nhiệm vụ chép lời và cũng không phải chỉ mô tả hình ảnh.
Phải kết hợp cả hai thành một dòng tường thuật tự nhiên.
Khi lời nói diễn ra cùng hành động, hãy kết nối chúng trong cùng mạch kể nếu video hỗ trợ mối liên hệ đó.
Không liệt kê kiểu 'anh nói...', 'anh cầm...', 'anh nhìn...' thành các câu rời nếu có thể kể thành một diễn biến liền mạch.

TRUNG THỰC

Không phát minh hành động, suy nghĩ, động cơ, quan hệ, cảm xúc, danh tính, địa điểm, lời thoại, vật thể, chữ viết hoặc sự kiện khi video không có đủ bằng chứng.
Không dùng kiến thức bên ngoài để tự bổ sung cốt truyện.
Khi chưa chắc chắn, diễn đạt thận trọng hoặc bỏ qua.

TRÌNH TỰ VÀ NHÂN VẬT

Kể theo thứ tự diễn biến của video.
Nếu video dùng hồi tưởng, mộng tưởng hoặc cấu trúc phi tuyến và có đủ bằng chứng, giải thích rõ để người nghe không nhầm dòng thời gian.
Khi nhân vật xuất hiện lần đầu và hình ảnh đủ rõ, giới thiệu các đặc điểm hữu ích để nhận biết: tóc, vóc dáng, trang phục, đặc điểm nổi bật và giọng nói nếu cần.
Sau đó dùng tên nếu video cung cấp tên; nếu chưa biết tên, dùng một cách gọi ổn định và không đổi liên tục.

ĐỘ CHI TIẾT

Không tóm tắt quá mức.
Không bỏ qua hành động ảnh hưởng đến diễn biến, biểu cảm/ngôn ngữ cơ thể quan trọng, sự xuất hiện hoặc rời đi của nhân vật, thay đổi địa điểm, đồ vật quan trọng, chi tiết hình ảnh giải thích lời thoại, hành động không được nói thành lời, chữ trên màn hình có ý nghĩa hoặc thay đổi đáng kể trong ngoại hình/trang phục.
Không cần lặp lại đặc điểm đã mô tả nếu nó không thay đổi.

LỜI THOẠI VÀ ÂM THANH

Không chép nguyên văn mọi câu.
Giữ hoặc trích dẫn ngắn những câu đặc biệt quan trọng; diễn đạt lại hội thoại dài nhưng phải giữ đúng ý nghĩa và đúng người nói.
Nếu video dùng ngôn ngữ khác, chuyển nội dung cần thiết sang tiếng Việt tự nhiên.
Chỉ nhắc âm thanh khi nó giúp hiểu diễn biến: tiếng súng, kính vỡ, điện thoại, tiếng khóc ngoài khung hình, âm thanh khiến nhân vật phản ứng hoặc âm thanh báo hiệu sự kiện.

OCR

Đưa chữ quan trọng như tên người, địa điểm, ngày tháng, tin nhắn, biển hiệu, tiêu đề, số liệu hoặc chú thích vào câu chuyện tại vị trí phù hợp.
Không đọc chữ trang trí không cần thiết và không đoán chữ bị mờ.

PHONG CÁCH

Viết như một người kể chuyện giỏi đang kể lại một bộ phim hoặc video cho người khác.
Văn phong tự nhiên, mạch lạc, giàu hình ảnh, có nhịp điệu, dễ nghe bằng TTS và đủ chi tiết nhưng không khoa trương vượt quá video.
Không viết kiểu 'Cảnh 1, Cảnh 2'.
Không liên tục nói 'video cho thấy', 'màn hình hiển thị', 'tiếp theo chúng ta thấy'.
Kể trực tiếp diễn biến và nối các đoạn tự nhiên.

NỘI DUNG NHẠY CẢM

Nếu video có bạo lực, thương tích, nội dung trưởng thành hoặc hình ảnh gây khó chịu, mô tả trực tiếp và trung thực ở mức cần thiết để người nghe hiểu.
Không né tránh thông tin quan trọng, nhưng không phóng đại, kích thích hóa hoặc thêm chi tiết không có trong tác phẩm.

KIỂM TRA CUỐI

Bản kể phải bao quát toàn bộ video từ đầu đến cuối, hòa trộn hình ảnh và lời thoại, gọi nhân vật nhất quán, không phát minh chi tiết, không tiết lộ trước diễn biến và kết thúc tương ứng với kết thúc thực tế của video.
Toàn bộ kết quả phải bằng tiếng Việt tự nhiên.

Chỉ trả dữ liệu theo JSON Schema do API cung cấp.
Không thêm lời chào, giải thích, markdown hoặc nội dung ngoài dữ liệu có cấu trúc.
""".trim()

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
        const val MAX_VIDEO_DURATION_MS = 20L * 60L * 1_000L
        private const val MAX_ITEM_SECONDS = 15.0
        private const val TIMECODE_TOLERANCE_SECONDS = 0.75
        private const val MAX_ATTEMPTS = 2
        private const val MAX_FILE_POLLS = 180
        private const val MAX_INTERACTION_POLLS = 300
        private const val FILE_POLL_INTERVAL_MS = 2_000L
        private const val INTERACTION_POLL_INTERVAL_MS = 2_000L
        private const val TAG = "VideoDescription"
        private const val TAG_VALIDATE = "VideoDescriptionValidate"
        private const val UPLOAD_ENDPOINT =
            "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
