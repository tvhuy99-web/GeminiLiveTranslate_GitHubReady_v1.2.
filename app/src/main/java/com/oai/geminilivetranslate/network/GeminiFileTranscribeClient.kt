package com.oai.geminilivetranslate.network

import com.oai.geminilivetranslate.core.SessionLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun transcribe(
        file: File,
        speakerDiarization: Boolean,
        onProgress: (String, Int) -> Unit,
    ): Result {
        require(apiKey.isNotBlank()) { "API Key đang trống" }
        require(file.isFile && file.length() > 44L) { "Tệp âm thanh không hợp lệ" }

        var uploadedName: String? = null
        try {
            onProgress("Đang tải tệp lên...", 5)
            val uploaded = upload(file, onProgress)
            uploadedName = uploaded.name
            onProgress("Đang chép lời...", 55)
            val interaction = createInteraction(uploaded.uri, uploaded.mimeType, speakerDiarization)
            val completed = awaitCompletion(interaction, onProgress)
            val result = parseResult(completed)
            onProgress("Đang tạo kết quả...", 98)
            return result
        } finally {
            uploadedName?.let(::deleteUploadedFile)
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private data class UploadedFile(
        val name: String?,
        val uri: String,
        val mimeType: String,
    )

    private fun upload(file: File, onProgress: (String, Int) -> Unit): UploadedFile {
        val startJson = JSONObject()
            .put("file", JSONObject().put("display_name", file.name))
            .toString()
        val startRequest = Request.Builder()
            .url(UPLOAD_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", WAV_MIME)
            .post(startJson.toRequestBody(JSON_MEDIA))
            .build()

        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Không bắt đầu được tải tệp: HTTP ${response.code} ${response.body?.string().orEmpty().take(500)}")
            }
            response.header("x-goog-upload-url")
                ?: response.header("X-Goog-Upload-URL")
                ?: error("Gemini không trả về địa chỉ tải tệp")
        }

        val uploadBody = ProgressFileRequestBody(file, WAV_MIME.toMediaType()) { sent, total ->
            val ratio = if (total <= 0L) 0.0 else sent.toDouble() / total.toDouble()
            val percent = (5 + ratio * 45.0).toInt().coerceIn(5, 50)
            onProgress("Đang tải tệp lên...", percent)
        }
        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", file.length().toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
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
            ?: WAV_MIME
        logger.log(2, "TranscribeFile", "Đã tải tệp bytes=${file.length()} hasName=${!name.isNullOrBlank()}")
        return UploadedFile(name, uri, mime)
    }

    private fun createInteraction(
        fileUri: String,
        mimeType: String,
        speakerDiarization: Boolean,
    ): JSONObject {
        val mode = JSONObject()
            .put("type", "verbatim")
            .put("timestamp_granularities", org.json.JSONArray().put("word"))
        if (speakerDiarization) mode.put("diarization_mode", "speaker")

        val requestJson = JSONObject()
            .put("model", MODEL)
            .put(
                "input",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("type", "audio")
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
                    throw IllegalStateException("Không đọc được tiến trình chép lời: HTTP ${response.code} ${body.take(500)}")
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

    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType,
        private val progress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType = mediaType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    progress(sent, file.length())
                }
            }
        }
    }

    companion object {
        private const val MODEL = "gemini-3.5-transcribe"
        private const val WAV_MIME = "audio/wav"
        private const val UPLOAD_ENDPOINT = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
