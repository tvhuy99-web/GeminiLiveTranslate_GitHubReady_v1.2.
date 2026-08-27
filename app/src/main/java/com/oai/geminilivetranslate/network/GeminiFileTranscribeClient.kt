package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
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
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class GeminiFileTranscribeClient(
    private val apiKey: String,
    private val logger: SessionLogger,
) {
    data class WordInfo(
        val text: String,
        val speaker: String?,
        val startMs: Long,
        val endMs: Long,
    )

    data class Result(
        val text: String,
        val words: List<WordInfo>,
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun transcribe(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String,
        speakerDiarization: Boolean,
        inputType: String = if (mimeType.startsWith("video/")) "video" else "audio",
        onProgress: (String, Int) -> Unit,
    ): Result {
        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull() ?: -1L
        return transcribe(
            UploadSource(
                displayName = displayName,
                mimeType = mimeType,
                contentLength = length,
                open = { resolver.openInputStream(uri) ?: error("Không mở được tệp đã chọn") },
            ),
            speakerDiarization,
            inputType,
            onProgress,
        )
    }

    fun transcribe(
        file: File,
        mimeType: String,
        speakerDiarization: Boolean,
        inputType: String = if (mimeType.startsWith("video/")) "video" else "audio",
        onProgress: (String, Int) -> Unit,
    ): Result {
        require(file.isFile && file.length() > 0L) { "Tệp âm thanh không hợp lệ" }
        return transcribe(
            UploadSource(
                displayName = file.name,
                mimeType = mimeType,
                contentLength = file.length(),
                open = { file.inputStream() },
            ),
            speakerDiarization,
            inputType,
            onProgress,
        )
    }

    private fun transcribe(
        source: UploadSource,
        speakerDiarization: Boolean,
        inputType: String,
        onProgress: (String, Int) -> Unit,
    ): Result {
        require(apiKey.isNotBlank()) { "API Key đang trống" }
        val totalStartedAt = SystemClock.elapsedRealtime()
        logger.log(
            2,
            "TranscribeFile",
            "Bắt đầu Files API name=${source.displayName} bytes=${source.contentLength} mime=${source.mimeType} inputType=$inputType diarization=$speakerDiarization",
        )
        var uploadedName: String? = null
        try {
            onProgress("Đang tải tệp lên...", 2)
            val uploadStartedAt = SystemClock.elapsedRealtime()
            val uploaded = upload(source, onProgress)
            uploadedName = uploaded.name
            logger.log(
                2,
                "TranscribeFile",
                "Upload hoàn tất elapsedMs=${SystemClock.elapsedRealtime() - uploadStartedAt} fileUri=${uploaded.uri.take(80)}",
            )
            onProgress("Đang chép lời...", 55)
            val requestStartedAt = SystemClock.elapsedRealtime()
            val interaction = createInteraction(uploaded.uri, uploaded.mimeType, speakerDiarization, inputType)
            logger.log(
                2,
                "TranscribeFile",
                "Interactions API đã nhận yêu cầu elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt} status=${interaction.optString("status")} id=${interaction.optString("id")}",
            )
            val waitStartedAt = SystemClock.elapsedRealtime()
            val completed = awaitCompletion(interaction, onProgress)
            logger.log(
                2,
                "TranscribeFile",
                "Gemini hoàn tất xử lý elapsedMs=${SystemClock.elapsedRealtime() - waitStartedAt} status=${completed.optString("status")}",
            )
            val result = parseResult(completed)
            onProgress("Đang tạo kết quả...", 98)
            logger.log(
                2,
                "TranscribeFile",
                "Kết thúc Files/Interactions totalElapsedMs=${SystemClock.elapsedRealtime() - totalStartedAt} chars=${result.text.length} words=${result.words.size}",
            )
            return result
        } finally {
            uploadedName?.let(::deleteUploadedFile)
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun upload(source: UploadSource, onProgress: (String, Int) -> Unit): UploadedFile {
        val uploadStartedAt = SystemClock.elapsedRealtime()
        logger.log(
            2,
            "TranscribeFile",
            "Khởi tạo resumable upload name=${source.displayName} bytes=${source.contentLength} mime=${source.mimeType}",
        )
        val startJson = JSONObject()
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
            .post(startJson.toRequestBody(JSON_MEDIA))
            .build()

        val startRequestAt = SystemClock.elapsedRealtime()
        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Không bắt đầu được tải tệp: HTTP ${response.code} ${response.body?.string().orEmpty().take(500)}"
                )
            }
            response.header("x-goog-upload-url")
                ?: response.header("X-Goog-Upload-URL")
                ?: error("Gemini không trả về địa chỉ tải tệp")
        }
        logger.log(
            3,
            "TranscribeFile",
            "Resumable upload URL sẵn sàng elapsedMs=${SystemClock.elapsedRealtime() - startRequestAt}",
        )

        var lastLoggedUploadBucket = -1
        val uploadBody = ProgressStreamRequestBody(
            source = source,
            mediaType = source.mimeType.toMediaType(),
        ) { sent, total ->
            val ratio = if (total <= 0L) 0.0 else sent.toDouble() / total.toDouble()
            val percent = if (total <= 0L) 25 else (2 + ratio * 48.0).toInt().coerceIn(2, 50)
            onProgress("Đang tải tệp lên...", percent)
            if (total > 0L) {
                val bucket = ((sent * 4L) / total).toInt().coerceIn(0, 4)
                if (bucket > lastLoggedUploadBucket && bucket > 0) {
                    lastLoggedUploadBucket = bucket
                    logger.log(
                        3,
                        "TranscribeFile",
                        "Upload progress=${bucket * 25}% sent=$sent total=$total elapsedMs=${SystemClock.elapsedRealtime() - uploadStartedAt}",
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
        val uploadRequest = uploadBuilder
            .post(uploadBody)
            .build()

        val root = client.newCall(uploadRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Tải tệp thất bại: HTTP ${response.code} ${body.take(500)}")
            }
            JSONObject(body)
        }
        val fileObject = root.optJSONObject("file") ?: root
        val uri = fileObject.optString("uri").takeIf(String::isNotBlank)
            ?: error("Gemini không trả về URI tệp")
        val name = fileObject.optString("name").takeIf(String::isNotBlank)
        val mime = fileObject.optString("mimeType")
            .takeIf(String::isNotBlank)
            ?: fileObject.optString("mime_type").takeIf(String::isNotBlank)
            ?: source.mimeType
        val uploadElapsedMs = SystemClock.elapsedRealtime() - uploadStartedAt
        val throughputMbps = if (source.contentLength > 0L && uploadElapsedMs > 0L) {
            source.contentLength * 8.0 / uploadElapsedMs / 1_000.0
        } else {
            0.0
        }
        logger.log(
            2,
            "TranscribeFile",
            "Đã tải tệp name=${source.displayName} bytes=${source.contentLength} mime=$mime elapsedMs=$uploadElapsedMs avgMbps=${String.format(java.util.Locale.US, "%.2f", throughputMbps)}",
        )
        return UploadedFile(name, uri, mime)
    }

    private fun createInteraction(
        fileUri: String,
        mimeType: String,
        speakerDiarization: Boolean,
        inputType: String,
    ): JSONObject {
        val mode = JSONObject()
            .put("type", "verbatim")
            .put("timestamp_granularities", JSONArray().put("word"))
        if (speakerDiarization) mode.put("diarization_mode", "speaker")

        val requestJson = JSONObject()
            .put("model", MODEL)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("type", inputType)
                        .put("uri", fileUri)
                        .put("mime_type", mimeType)
                )
            )
            .put(
                "generation_config",
                JSONObject().put(
                    "transcription_config",
                    JSONObject().put("mode", mode)
                )
            )

        val request = Request.Builder()
            .url(INTERACTIONS_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA))
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Không chép lời được: HTTP ${response.code} ${body.take(800)}")
            }
            JSONObject(body)
        }
    }

    private fun awaitCompletion(
        initial: JSONObject,
        onProgress: (String, Int) -> Unit,
    ): JSONObject {
        var current = initial
        var attempt = 0
        while (true) {
            when (current.optString("status").lowercase()) {
                "", "completed" -> return current
                "failed", "cancelled", "incomplete" -> {
                    val error = current.optJSONObject("error")?.optString("message")
                        ?: "Gemini không hoàn tất được bản chép lời"
                    throw IllegalStateException(error)
                }
            }
            val id = current.optString("id").takeIf(String::isNotBlank)
                ?: return current
            if (++attempt > 180) error("Hết thời gian chờ Gemini chép lời")
            Thread.sleep(2_000)
            onProgress("Đang chép lời...", (55 + attempt / 5).coerceAtMost(95))
            val cleanId = id.substringAfterLast('/')
            val request = Request.Builder()
                .url("$INTERACTIONS_ENDPOINT/$cleanId")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            current = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Không đọc được tiến trình chép lời: HTTP ${response.code} ${body.take(500)}"
                    )
                }
                JSONObject(body)
            }
        }
    }

    private fun parseResult(root: JSONObject): Result {
        val textParts = ArrayList<String>()
        val words = ArrayList<WordInfo>()
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val item = content.optJSONObject(j) ?: continue
                    if (item.optString("type") == "text") {
                        item.optString("text").trim().takeIf(String::isNotBlank)?.let(textParts::add)
                    }
                    val annotations = item.optJSONArray("annotations") ?: continue
                    for (k in 0 until annotations.length()) {
                        val annotation = annotations.optJSONObject(k) ?: continue
                        if (annotation.optString("type") != "word_info") continue
                        val word = annotation.optString("text").trim()
                        if (word.isBlank()) continue
                        words += WordInfo(
                            text = word,
                            speaker = annotation.optString("speaker").takeIf(String::isNotBlank),
                            startMs = parseOffsetMs(annotation.optString("start_offset")),
                            endMs = parseOffsetMs(annotation.optString("end_offset")),
                        )
                    }
                }
            }
        }
        val text = textParts.joinToString("\n").trim()
        logger.log(2, "TranscribeFile", "Nhận kết quả chars=${text.length} words=${words.size}")
        return Result(text, words)
    }

    private fun deleteUploadedFile(name: String) {
        val clean = name.removePrefix("/")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$clean")
            .header("x-goog-api-key", apiKey)
            .delete()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                logger.log(3, "TranscribeFile", "Xóa tệp tạm Gemini HTTP=${response.code}")
            }
        }
    }

    private fun parseOffsetMs(raw: String): Long {
        val value = raw.trim().removeSuffix("s").toDoubleOrNull() ?: return 0L
        return (value * 1_000.0).toLong().coerceAtLeast(0L)
    }

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
        private const val MODEL = "gemini-3.5-transcribe"
        private const val UPLOAD_ENDPOINT = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
