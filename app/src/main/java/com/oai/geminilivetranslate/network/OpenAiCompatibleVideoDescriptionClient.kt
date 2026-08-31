package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Base64OutputStream
import com.oai.geminilivetranslate.core.AiApiEndpointRules
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.VideoDescriptionPromptDefaults
import com.oai.geminilivetranslate.core.VideoDescriptionTimelineRules
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class OpenAiCompatibleVideoDescriptionClient(
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val logger: SessionLogger,
    private val includeOutputInLogs: Boolean,
    private val timelinePromptTemplate: String,
    private val summaryPromptTemplate: String,
    private val streamingEnabled: Boolean,
    private val requestTimeoutMs: Int,
    private val temperature: Double,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(requestTimeoutMs.coerceIn(30_000, 900_000).toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(requestTimeoutMs.coerceIn(30_000, 900_000).toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        client.dispatcher.cancelAll()
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    fun describe(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String,
        durationMs: Long,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit = {},
    ): GeminiVideoDescriptionClient.Result {
        require(endpoint.trim().startsWith("https://")) { "URL OpenAI-compatible phải dùng HTTPS" }
        require(model.trim().isNotBlank()) { "Chưa nhập model OpenAI-compatible" }
        require(durationMs in 1..GeminiVideoDescriptionClient.MAX_VIDEO_DURATION_MS) {
            "Video phải dài tối đa 20 phút"
        }

        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull() ?: -1L

        val source = UploadSource(
            displayName = displayName,
            mimeType = mimeType.takeIf { it.startsWith("video/") } ?: "video/mp4",
            contentLength = length,
            open = { resolver.openInputStream(uri) ?: error("Không mở được video đã chọn") },
        )
        val durationSeconds = durationMs / 1_000.0
        val prompt = VideoDescriptionPromptDefaults.render(
            if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) timelinePromptTemplate else summaryPromptTemplate,
            durationSeconds,
        ) + if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) TIMELINE_JSON_CONTRACT else ""

        val useGeminiNative = shouldUseGeminiNativeVideoProtocol()
        val protocol = if (useGeminiNative) "gemini-native" else "openai-compatible"
        val startedAt = SystemClock.elapsedRealtime()
        logger.log(
            2,
            TAG,
            "Bắt đầu model=$model endpoint=${sanitizeUrl(endpoint)} protocol=$protocol mode=$mode name=${source.displayName} mime=${source.mimeType} bytes=${source.contentLength} durationMs=$durationMs streaming=$streamingEnabled timeoutMs=$requestTimeoutMs temperature=$temperature",
        )

        onProgress("Đang chuẩn bị gửi video tới API...", 8)
        val output = if (useGeminiNative) {
            executeGeminiNativeWithFallback(source, prompt, mode, onProgress, onPartial)
        } else {
            executeOpenAiWithFallback(source, prompt, mode, onProgress, onPartial)
        }

        val timelineItems = if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {
            parseTimeline(output, durationSeconds)
        } else {
            emptyList()
        }
        val summary = if (mode == GeminiVideoDescriptionClient.Mode.SUMMARY) output.trim() else ""
        if (mode == GeminiVideoDescriptionClient.Mode.SUMMARY && summary.isBlank()) {
            error("API OpenAI-compatible trả bản tổng hợp rỗng")
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (includeOutputInLogs) {
            logger.log(3, TAG, "Output preview=${output.replace(Regex("\\s+"), " ").take(2_000)}")
        }
        logger.log(
            2,
            TAG,
            "Hoàn tất protocol=$protocol mode=$mode items=${timelineItems.size} summaryChars=${summary.length} elapsedMs=$elapsed",
        )
        return GeminiVideoDescriptionClient.Result(
            timelineItems = timelineItems,
            summaryText = summary,
            interactionId = null,
            attempts = 1,
            elapsedMs = elapsed,
            inputTokens = 0,
            outputTokens = 0,
            thoughtTokens = 0,
            totalTokens = 0,
        )
    }

    private fun executeOpenAiWithFallback(
        source: UploadSource,
        prompt: String,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
    ): String {
        if (!streamingEnabled) {
            return executeOpenAi(source, prompt, mode, onProgress, onPartial, stream = false)
        }
        return try {
            executeOpenAi(source, prompt, mode, onProgress, onPartial, stream = true)
        } catch (error: Throwable) {
            if (cancelled || !shouldFallbackFromStreaming(error)) throw error
            logger.log(
                1,
                TAG,
                "OPENAI_STREAM_FALLBACK reason=${error.message ?: error.javaClass.simpleName}",
            )
            onProgress("Streaming lỗi; đang thử lại chế độ thường...", 55)
            executeOpenAi(source, prompt, mode, onProgress, onPartial, stream = false)
        }
    }

    private fun executeGeminiNativeWithFallback(
        source: UploadSource,
        prompt: String,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
    ): String {
        if (!streamingEnabled) {
            return executeGeminiNative(source, prompt, mode, onProgress, onPartial, stream = false)
        }
        return try {
            executeGeminiNative(source, prompt, mode, onProgress, onPartial, stream = true)
        } catch (error: Throwable) {
            if (cancelled || !shouldFallbackFromStreaming(error)) throw error
            logger.log(
                1,
                TAG,
                "GEMINI_NATIVE_STREAM_FALLBACK reason=${error.message ?: error.javaClass.simpleName}",
            )
            onProgress("Luồng Gemini lỗi; đang thử lại chế độ thường...", 55)
            executeGeminiNative(source, prompt, mode, onProgress, onPartial, stream = false)
        }
    }

    private fun executeOpenAi(
        source: UploadSource,
        prompt: String,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        stream: Boolean,
    ): String {
        throwIfCancelled()
        val url = AiApiEndpointRules.proxyChatEndpoint(endpoint)
        val uploadProgress = createUploadProgressReporter("openai-compatible", stream, source, onProgress)
        val body = OpenAiVideoRequestBody(source, model.trim(), prompt, stream, temperature, uploadProgress)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                if (stream) header("Accept", "text/event-stream")
            }
            .post(body)
            .build()

        return executeRequest(
            request = request,
            protocol = "openai-compatible",
            stream = stream,
            bodyBytes = body.contentLength(),
            mode = mode,
            onProgress = onProgress,
            onPartial = onPartial,
            parseNonStream = ::extractNonStreamText,
            parseStream = { responseSource -> readOpenAiSse(responseSource, mode, onPartial) },
        )
    }

    private fun executeGeminiNative(
        source: UploadSource,
        prompt: String,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        stream: Boolean,
    ): String {
        throwIfCancelled()
        val url = geminiNativeEndpoint(stream)
        val uploadProgress = createUploadProgressReporter("gemini-native", stream, source, onProgress)
        val body = GeminiNativeVideoRequestBody(source, prompt, temperature, uploadProgress)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
                if (stream) header("Accept", "text/event-stream, application/json")
            }
            .post(body)
            .build()

        return executeRequest(
            request = request,
            protocol = "gemini-native",
            stream = stream,
            bodyBytes = body.contentLength(),
            mode = mode,
            onProgress = onProgress,
            onPartial = onPartial,
            parseNonStream = ::extractGeminiNativeText,
            parseStream = { responseSource -> readGeminiNativeStream(responseSource, mode, onPartial) },
        )
    }

    private fun executeRequest(
        request: Request,
        protocol: String,
        stream: Boolean,
        bodyBytes: Long,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        parseNonStream: (String) -> String,
        parseStream: (okio.BufferedSource) -> String,
    ): String {
        val started = SystemClock.elapsedRealtime()
        logger.log(
            2,
            TAG,
            "REQUEST_PREPARED protocol=$protocol stream=$stream bodyBytes=$bodyBytes endpoint=${sanitizeUrl(request.url.toString())}",
        )
        logger.log(2, TAG, "REQUEST_SEND protocol=$protocol stream=$stream")

        return client.newCall(request).execute().use { response ->
            val headersElapsed = SystemClock.elapsedRealtime() - started
            val contentType = response.header("Content-Type").orEmpty()
            logger.log(
                2,
                TAG,
                "RESPONSE_HEADERS protocol=$protocol stream=$stream HTTP=${response.code} contentType=$contentType elapsedMs=$headersElapsed",
            )
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                error("$protocol HTTP ${response.code}: ${errorBody.replace(Regex("\\s+"), " ").take(900)}")
            }

            onProgress("Máy chủ đã nhận video; đang xử lý kết quả...", 70)
            val responseBody = response.body ?: error("$protocol không trả response body")
            val output = if (stream && contentType.contains("text/event-stream", ignoreCase = true)) {
                parseStream(responseBody.source())
            } else {
                val raw = responseBody.string()
                val text = if (stream && protocol == "gemini-native") {
                    extractGeminiNativeStreamingBody(raw)
                } else {
                    parseNonStream(raw)
                }
                val preview = if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) timelinePreview(text) else text
                if (preview.isNotBlank()) onPartial(preview)
                text
            }
            onProgress("Đang hoàn tất kết quả...", 96)
            output
        }
    }

    private fun createUploadProgressReporter(
        protocol: String,
        stream: Boolean,
        source: UploadSource,
        onProgress: (String, Int) -> Unit,
    ): (Long, Long) -> Unit {
        var lastProgress = -1
        var uploadDoneLogged = false
        return { written, total ->
            if (total > 0L) {
                val progress = (8L + (written.coerceIn(0L, total) * 42L / total)).toInt().coerceIn(8, 50)
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress("Đang tải video lên API...", progress)
                }
                if (written >= total && !uploadDoneLogged) {
                    uploadDoneLogged = true
                    logger.log(
                        2,
                        TAG,
                        "UPLOAD_DONE protocol=$protocol stream=$stream sourceBytes=${source.contentLength}",
                    )
                    onProgress("Đã tải video lên; đang chờ AI xử lý...", 52)
                }
            } else if (lastProgress < 0) {
                lastProgress = 8
                onProgress("Đang tải video lên API...", 8)
            }
        }
    }

    private fun shouldFallbackFromStreaming(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return listOf(
            "http 400",
            "http 404",
            "http 405",
            "http 406",
            "http 415",
            "http 422",
            "không trả luồng dữ liệu",
            "không trả nội dung",
            "bị ngắt trước tín hiệu hoàn tất",
            "stream",
        ).any(message::contains)
    }

    private fun readOpenAiSse(
        source: okio.BufferedSource,
        mode: GeminiVideoDescriptionClient.Mode,
        onPartial: (String) -> Unit,
    ): String {
        val output = StringBuilder()
        var lastPreview = ""
        var completed = false
        var firstDataLogged = false
        while (true) {
            throwIfCancelled()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substringAfter("data:").trim()
            if (data.isBlank()) continue
            if (!firstDataLogged) {
                firstDataLogged = true
                logger.log(2, TAG, "FIRST_STREAM_DATA protocol=openai-compatible")
            }
            if (data == "[DONE]") {
                completed = true
                continue
            }
            val root = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (root.optString("type") == "response.completed") completed = true
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val finishReason = choices.optJSONObject(0)?.opt("finish_reason")
                if (finishReason != null && finishReason != JSONObject.NULL) completed = true
            }
            val delta = streamDelta(root)
            if (delta.isEmpty()) continue
            output.append(delta)
            val preview = if (mode == GeminiVideoDescriptionClient.Mode.SUMMARY) {
                output.toString()
            } else {
                timelinePreview(output.toString())
            }
            if (preview.isNotBlank() && preview != lastPreview) {
                lastPreview = preview
                onPartial(preview)
            }
        }
        if (!completed) error("Luồng OpenAI-compatible bị ngắt trước tín hiệu hoàn tất")
        return output.toString().trim().ifBlank { error("API OpenAI-compatible không trả nội dung") }
    }

    private fun readGeminiNativeStream(
        source: okio.BufferedSource,
        mode: GeminiVideoDescriptionClient.Mode,
        onPartial: (String) -> Unit,
    ): String {
        val output = StringBuilder()
        var lastPreview = ""
        var firstDataLogged = false
        var completed = false
        while (true) {
            throwIfCancelled()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substringAfter("data:").trim()
            if (data.isBlank()) continue
            if (!firstDataLogged) {
                firstDataLogged = true
                logger.log(2, TAG, "FIRST_STREAM_DATA protocol=gemini-native")
            }
            if (data == "[DONE]") {
                completed = true
                continue
            }
            val root = runCatching { JSONObject(data) }.getOrNull() ?: continue
            val delta = geminiTextFromObject(root)
            if (delta.isNotEmpty()) {
                output.append(delta)
                val preview = if (mode == GeminiVideoDescriptionClient.Mode.SUMMARY) {
                    output.toString()
                } else {
                    timelinePreview(output.toString())
                }
                if (preview.isNotBlank() && preview != lastPreview) {
                    lastPreview = preview
                    onPartial(preview)
                }
            }
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason").orEmpty()
                .ifBlank { candidate?.optString("finish_reason").orEmpty() }
            if (finishReason.isNotBlank()) completed = true
        }
        if (!completed && output.isBlank()) error("Luồng Gemini-native bị ngắt trước khi có dữ liệu")
        if (!completed) {
            logger.log(1, TAG, "GEMINI_NATIVE_STREAM_EOF_WITHOUT_FINISH outputChars=${output.length}")
        }
        return output.toString().trim().ifBlank { error("API Gemini-native không trả nội dung") }
    }

    private fun streamDelta(root: JSONObject): String {
        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val content = choices.optJSONObject(0)?.optJSONObject("delta")?.opt("content")
            if (content is String) return content
            if (content is JSONArray) {
                val parts = ArrayList<String>()
                for (i in 0 until content.length()) {
                    content.optJSONObject(i)?.optString("text")
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                }
                if (parts.isNotEmpty()) return parts.joinToString("")
            }
        }
        if (root.optString("type") == "response.output_text.delta") return root.optString("delta")
        return ""
    }

    private fun extractNonStreamText(body: String): String {
        val root = JSONObject(body)
        root.optString("output_text").takeIf(String::isNotBlank)?.let { return it.trim() }
        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val content = choices.optJSONObject(0)?.optJSONObject("message")?.opt("content")
            when (content) {
                is String -> if (content.isNotBlank()) return content.trim()
                is JSONArray -> {
                    val parts = ArrayList<String>()
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        item.optString("text").takeIf(String::isNotBlank)?.let(parts::add)
                    }
                    if (parts.isNotEmpty()) return parts.joinToString("").trim()
                }
            }
        }
        val output = root.optJSONArray("output")
        if (output != null) {
            val parts = ArrayList<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    content.optJSONObject(j)?.optString("text")
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                }
            }
            if (parts.isNotEmpty()) return parts.joinToString("").trim()
        }
        error("API OpenAI-compatible không trả nội dung")
    }

    private fun extractGeminiNativeStreamingBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val parts = ArrayList<String>()
            for (i in 0 until array.length()) {
                val text = array.optJSONObject(i)?.let(::geminiTextFromObject).orEmpty()
                if (text.isNotBlank()) parts += text
            }
            return parts.joinToString("").trim().ifBlank { error("API Gemini-native không trả nội dung") }
        }
        return extractGeminiNativeText(trimmed)
    }

    private fun extractGeminiNativeText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return extractGeminiNativeStreamingBody(trimmed)
        val root = JSONObject(trimmed)
        return geminiTextFromObject(root).trim().ifBlank { error("API Gemini-native không trả nội dung") }
    }

    private fun geminiTextFromObject(root: JSONObject): String {
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val textParts = ArrayList<String>()
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.optString("text")
                ?.takeIf(String::isNotBlank)
                ?.let(textParts::add)
        }
        return textParts.joinToString("")
    }

    private fun parseTimeline(
        outputText: String,
        durationSeconds: Double,
    ): List<GeminiVideoDescriptionClient.TimelineItem> {
        val root = JSONObject(stripJsonFence(outputText))
        val array = root.optJSONArray("items") ?: error("Kết quả thiếu trường items")
        val parsed = ArrayList<VideoDescriptionTimelineRules.Item>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parsed += VideoDescriptionTimelineRules.Item(
                index = item.optInt("index", Int.MIN_VALUE),
                startSeconds = item.optDouble("start_seconds", Double.NaN),
                endSeconds = item.optDouble("end_seconds", Double.NaN),
                text = item.optString("text").trim(),
            )
        }
        val validation = VideoDescriptionTimelineRules.validate(
            items = parsed,
            durationSeconds = durationSeconds,
            maxItemSeconds = 15.0,
            toleranceSeconds = 0.10,
        )
        if (!validation.valid) {
            error("Kết quả mô tả theo thời gian không hợp lệ: ${validation.errors.take(6).joinToString("; ")}")
        }
        return parsed.map {
            GeminiVideoDescriptionClient.TimelineItem(
                index = it.index,
                startSeconds = it.startSeconds,
                endSeconds = it.endSeconds,
                text = it.text,
            )
        }
    }

    private fun stripJsonFence(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun timelinePreview(partialJson: String): String {
        val regex = Regex("""\"text\"\s*:\s*\"((?:\\\\.|[^\"\\\\])*)\"""")
        return regex.findAll(partialJson)
            .mapNotNull { match ->
                runCatching { JSONArray("[\"${match.groupValues[1]}\"]").getString(0) }.getOrNull()
            }
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    private fun shouldUseGeminiNativeVideoProtocol(): Boolean {
        val host = runCatching { URI(endpoint.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        val raw = endpoint.lowercase()
        return host == "gcli.ggchan.dev" ||
            host.startsWith("gcli.") ||
            raw.contains("gcli2api")
    }

    private fun geminiNativeEndpoint(stream: Boolean): String {
        var base = endpoint.trim().removeSuffix("/")
        val suffixes = listOf("/chat/completions", "/responses", "/models")
        suffixes.forEach { suffix ->
            if (base.endsWith(suffix)) base = base.removeSuffix(suffix).removeSuffix("/")
        }
        val path = runCatching { URI(base).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/") base += "/v1"
        val encodedModel = URLEncoder.encode(
            model.trim().removePrefix("models/"),
            Charsets.UTF_8.name(),
        ).replace("+", "%20")
        val method = if (stream) "streamGenerateContent" else "generateContent"
        return "$base/models/$encodedModel:$method"
    }

    private fun sanitizeUrl(raw: String): String = raw.substringBefore('?').take(180)

    private fun throwIfCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Đã hủy mô tả video")
    }

    private data class UploadSource(
        val displayName: String,
        val mimeType: String,
        val contentLength: Long,
        val open: () -> InputStream,
    )

    private abstract class StreamingVideoRequestBody(
        private val source: UploadSource,
        private val prefix: ByteArray,
        private val suffix: ByteArray,
        private val onUploadProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = "application/json; charset=utf-8".toMediaType()

        override fun contentLength(): Long {
            if (source.contentLength < 0L) return -1L
            val encoded = 4L * ((source.contentLength + 2L) / 3L)
            return prefix.size.toLong() + encoded + suffix.size.toLong()
        }

        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix)
            val base64Out = Base64OutputStream(
                sink.outputStream(),
                Base64.NO_WRAP or Base64.NO_CLOSE,
            )
            var written = 0L
            onUploadProgress(0L, source.contentLength)
            source.open().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    base64Out.write(buffer, 0, read)
                    written += read
                    onUploadProgress(written, source.contentLength)
                }
            }
            base64Out.close()
            sink.write(suffix)
            if (source.contentLength > 0L) onUploadProgress(source.contentLength, source.contentLength)
        }
    }

    private class OpenAiVideoRequestBody(
        source: UploadSource,
        model: String,
        prompt: String,
        stream: Boolean,
        temperature: Double,
        onUploadProgress: (Long, Long) -> Unit,
    ) : StreamingVideoRequestBody(
        source = source,
        prefix = run {
            val modelJson = JSONObject.quote(model)
            val promptJson = JSONObject.quote(prompt)
            val mime = source.mimeType.replace("\"", "")
            val streamText = if (stream) "true" else "false"
            val temperatureText = temperature.coerceIn(0.0, 2.0).toString()
            (
                "{\"model\":$modelJson,\"stream\":$streamText,\"temperature\":$temperatureText," +
                    "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":$promptJson}," +
                    "{\"type\":\"video_url\",\"video_url\":{\"url\":\"data:$mime;base64,"
                ).toByteArray(Charsets.UTF_8)
        },
        suffix = "\"}}]}]}".toByteArray(Charsets.UTF_8),
        onUploadProgress = onUploadProgress,
    )

    private class GeminiNativeVideoRequestBody(
        source: UploadSource,
        prompt: String,
        temperature: Double,
        onUploadProgress: (Long, Long) -> Unit,
    ) : StreamingVideoRequestBody(
        source = source,
        prefix = run {
            val promptJson = JSONObject.quote(prompt)
            val mimeJson = JSONObject.quote(source.mimeType.replace("\"", ""))
            (
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":$promptJson}," +
                    "{\"inline_data\":{\"mime_type\":$mimeJson,\"data\":\""
                ).toByteArray(Charsets.UTF_8)
        },
        suffix = (
            "\"}}]}],\"generationConfig\":{\"temperature\":" +
                temperature.coerceIn(0.0, 2.0).toString() + "}}"
            ).toByteArray(Charsets.UTF_8),
        onUploadProgress = onUploadProgress,
    )

    companion object {
        private const val TAG = "VideoDescriptionProxy"
        private const val TIMELINE_JSON_CONTRACT = """

ĐỊNH DẠNG ĐẦU RA BẮT BUỘC:
Chỉ trả một JSON object dạng:
{"items":[{"index":1,"start_seconds":0.0,"end_seconds":10.0,"type":"description","text":"..."}]}
Không thêm markdown hoặc văn bản ngoài JSON.
"""
    }
}
