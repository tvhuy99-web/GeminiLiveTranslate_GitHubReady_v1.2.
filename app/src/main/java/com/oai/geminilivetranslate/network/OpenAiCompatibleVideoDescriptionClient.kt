package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
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

        val startedAt = SystemClock.elapsedRealtime()
        logger.log(
            2,
            TAG,
            "Bắt đầu model=$model endpoint=${sanitizeUrl(endpoint)} mode=$mode name=${source.displayName} mime=${source.mimeType} bytes=${source.contentLength} durationMs=$durationMs streaming=$streamingEnabled timeoutMs=$requestTimeoutMs temperature=$temperature",
        )

        onProgress("Đang gửi nguyên video tới API...", 8)
        val output = if (streamingEnabled) {
            try {
                execute(source, prompt, mode, onProgress, onPartial, stream = true)
            } catch (error: Throwable) {
                if (cancelled || !shouldFallbackFromStreaming(error)) throw error
                logger.log(
                    1,
                    TAG,
                    "Streaming Proxy không khả dụng; thử lại non-stream reason=${error.message ?: error.javaClass.simpleName}",
                )
                onProgress("API không hỗ trợ streaming; đang nhận kết quả thông thường...", 65)
                execute(source, prompt, mode, onProgress, onPartial, stream = false)
            }
        } else {
            execute(source, prompt, mode, onProgress, onPartial, stream = false)
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
            "Hoàn tất mode=$mode items=${timelineItems.size} summaryChars=${summary.length} elapsedMs=$elapsed",
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

    private fun execute(
        source: UploadSource,
        prompt: String,
        mode: GeminiVideoDescriptionClient.Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        stream: Boolean,
    ): String {
        throwIfCancelled()
        val url = normalizeEndpoint(endpoint)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                if (stream) header("Accept", "text/event-stream")
            }
            .post(VideoRequestBody(source, model.trim(), prompt, stream, temperature))
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error("OpenAI-compatible HTTP ${response.code}: ${body.replace(Regex("\\s+"), " ").take(900)}")
            }
            val contentType = response.header("Content-Type").orEmpty()
            logger.log(
                2,
                TAG,
                "POST stream=$stream HTTP=${response.code} contentType=$contentType endpoint=${sanitizeUrl(url)}",
            )
            onProgress("Đang nhận kết quả...", 70)
            if (stream && contentType.contains("text/event-stream", ignoreCase = true)) {
                readSse(response.body?.source() ?: error("API không trả luồng dữ liệu"), mode, onPartial)
            } else {
                val text = extractNonStreamText(response.body?.string().orEmpty())
                val preview = if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) timelinePreview(text) else text
                if (preview.isNotBlank()) onPartial(preview)
                text
            }
        }.also {
            onProgress("Đang hoàn tất kết quả...", 96)
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
        ).any(message::contains)
    }

    private fun readSse(
        source: okio.BufferedSource,
        mode: GeminiVideoDescriptionClient.Mode,
        onPartial: (String) -> Unit,
    ): String {
        val output = StringBuilder()
        var lastPreview = ""
        while (true) {
            throwIfCancelled()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substringAfter("data:").trim()
            if (data.isBlank() || data == "[DONE]") continue
            val root = runCatching { JSONObject(data) }.getOrNull() ?: continue
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
        return output.toString().trim().ifBlank { error("API OpenAI-compatible không trả nội dung") }
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
            if (content is String) return content.trim()
        }
        error("API OpenAI-compatible không trả nội dung")
    }

    private fun parseTimeline(
        outputText: String,
        durationSeconds: Double,
    ): List<GeminiVideoDescriptionClient.TimelineItem> {
        val root = JSONObject(outputText.trim())
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

    private fun timelinePreview(partialJson: String): String {
        val regex = Regex("""\"text\"\s*:\s*\"((?:\\\\.|[^\"\\\\])*)\"""")
        return regex.findAll(partialJson)
            .mapNotNull { match ->
                runCatching { JSONArray("[\"${match.groupValues[1]}\"]").getString(0) }.getOrNull()
            }
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    private fun normalizeEndpoint(raw: String): String {
        val url = raw.trim().removeSuffix("/")
        return when {
            url.endsWith("/chat/completions") -> url
            url.endsWith("/responses") -> url.removeSuffix("/responses") + "/chat/completions"
            else -> "$url/chat/completions"
        }
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

    private class VideoRequestBody(
        private val source: UploadSource,
        model: String,
        prompt: String,
        stream: Boolean,
        temperature: Double,
    ) : RequestBody() {
        private val prefix: ByteArray
        private val suffix: ByteArray

        init {
            val modelJson = JSONObject.quote(model)
            val promptJson = JSONObject.quote(prompt)
            val mime = source.mimeType.replace("\"", "")
            val streamText = if (stream) "true" else "false"
            val temperatureText = temperature.coerceIn(0.0, 2.0).toString()
            val beforeData =
                """{"model":$modelJson,"stream":$streamText,"temperature":$temperatureText,"messages":[{"role":"user","content":[{"type":"text","text":$promptJson},{"type":"video_url","video_url":{"url":"data:$mime;base64,"""
            val afterData = "\"}}]}]}"
            prefix = beforeData.toByteArray(Charsets.UTF_8)
            suffix = afterData.toByteArray(Charsets.UTF_8)
        }

        override fun contentType(): MediaType = "application/json; charset=utf-8".toMediaType()

        override fun contentLength(): Long {
            if (source.contentLength < 0L) return -1L
            val encoded = 4L * ((source.contentLength + 2L) / 3L)
            return prefix.size + encoded + suffix.size
        }

        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix)
            source.open().use { input ->
                val buffer = ByteArray(48 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val bytes = if (read == buffer.size) buffer else buffer.copyOf(read)
                    sink.writeUtf8(Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            }
            sink.write(suffix)
        }
    }

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
