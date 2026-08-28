package com.oai.geminilivetranslate.network

import android.os.SystemClock
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SubtitleStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SubtitleTranslationClient(
    private val apiKey: String,
    private val logger: SessionLogger,
    private val includeTranscriptInLogs: Boolean,
) {
    data class Item(
        val id: Int,
        val text: String,
    )

    data class Result(
        val items: List<Item>,
        val interactionId: String?,
        val attempts: Int,
        val elapsedMs: Long,
        val inputTokens: Int,
        val outputTokens: Int,
        val thoughtTokens: Int,
        val totalTokens: Int,
    )

    private data class InteractionResult(
        val root: JSONObject,
        val interactionId: String?,
        val elapsedMs: Long,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun translate(cues: List<SubtitleStore.Cue>): Result {
        require(apiKey.isNotBlank()) { "API Key đang trống" }
        require(cues.isNotEmpty()) { "Chưa có phụ đề để dịch" }

        val startedAt = SystemClock.elapsedRealtime()
        val sourceChars = cues.sumOf { it.text.length }
        val sourceWords = cues.sumOf { cue ->
            cue.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        }
        val payload = JSONArray().apply {
            cues.forEach { cue ->
                put(
                    JSONObject()
                        .put("id", cue.index)
                        .put("text", cue.text)
                )
            }
        }
        val prompt = buildPrompt(payload)
        val schema = buildSchema(cues.size)
        val requestJson = JSONObject()
            .put("model", AppPreferences.SUBTITLE_TRANSLATE_MODEL)
            .put("store", false)
            .put("input", prompt)
            .put(
                "response_format",
                JSONObject()
                    .put("type", "text")
                    .put("mime_type", "application/json")
                    .put("schema", schema)
            )

        logger.log(
            2,
            TAG,
            "Bắt đầu dịch toàn bộ phụ đề model=${AppPreferences.SUBTITLE_TRANSLATE_MODEL} cues=${cues.size} sourceChars=$sourceChars sourceWords=$sourceWords promptChars=${prompt.length} requestChars=${requestJson.toString().length} structuredOutput=true store=false",
        )
        if (includeTranscriptInLogs) {
            logger.log(
                3,
                TAG,
                "Nguồn dịch preview=${sanitizeForLog(cues.joinToString(" | ") { "${it.index}:${it.text}" }, 1_500)}",
            )
        }

        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val attemptStartedAt = SystemClock.elapsedRealtime()
            try {
                logger.log(2, TAG, "Gửi request attempt=$attempt/$MAX_ATTEMPTS")
                val interaction = createAndAwait(requestJson, attempt)
                val outputText = extractOutputText(interaction.root)
                val usage = interaction.root.optJSONObject("usage")
                val inputTokens = usage?.optInt("total_input_tokens", 0) ?: 0
                val outputTokens = usage?.optInt("total_output_tokens", 0) ?: 0
                val thoughtTokens = usage?.optInt("total_thought_tokens", 0) ?: 0
                val totalTokens = usage?.optInt("total_tokens", 0) ?: 0

                logger.log(
                    2,
                    TAG,
                    "Nhận output attempt=$attempt interactionId=${interaction.interactionId ?: "none"} responseChars=${outputText.length} inputTokens=$inputTokens outputTokens=$outputTokens thoughtTokens=$thoughtTokens totalTokens=$totalTokens apiElapsedMs=${interaction.elapsedMs}",
                )
                if (includeTranscriptInLogs) {
                    logger.log(3, TAG, "Output JSON preview=${sanitizeForLog(outputText, 1_500)}")
                }

                val items = parseAndValidate(outputText, cues)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                logger.log(
                    2,
                    TAG,
                    "Dịch phụ đề hợp lệ attempt=$attempt cues=${items.size}/${cues.size} translatedChars=${items.sumOf { it.text.length }} validateMs=${SystemClock.elapsedRealtime() - attemptStartedAt - interaction.elapsedMs} totalElapsedMs=$elapsedMs",
                )
                return Result(
                    items = items,
                    interactionId = interaction.interactionId,
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
                    "Dịch phụ đề thất bại attempt=$attempt/$MAX_ATTEMPTS elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt} reason=${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
        }

        throw lastError ?: IllegalStateException("Không dịch được phụ đề")
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun createAndAwait(
        requestJson: JSONObject,
        attempt: Int,
    ): InteractionResult {
        val startedAt = SystemClock.elapsedRealtime()
        val requestBody = requestJson.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(INTERACTIONS_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        var root = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val requestId = response.header("x-request-id")
                ?: response.header("x-goog-request-id")
                ?: "none"
            logger.log(
                if (response.isSuccessful) 2 else 0,
                TAG,
                "Interactions POST attempt=$attempt HTTP=${response.code} requestId=$requestId bodyChars=${body.length} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Gemini HTTP ${response.code}: ${sanitizeForLog(body, 800)}"
                )
            }
            JSONObject(body)
        }

        val interactionId = root.optString("id").takeIf(String::isNotBlank)
        var pollCount = 0
        while (true) {
            when (root.optString("status").lowercase()) {
                "", "completed" -> break
                "failed", "cancelled", "incomplete" -> {
                    val message = root.optJSONObject("error")?.optString("message")
                        ?.takeIf(String::isNotBlank)
                        ?: "Gemini kết thúc với status=${root.optString("status")}"
                    throw IllegalStateException(message)
                }
            }

            val id = interactionId ?: error("Gemini chưa hoàn tất nhưng không trả interaction id")
            if (++pollCount > MAX_POLLS) error("Hết thời gian chờ Gemini dịch phụ đề")
            Thread.sleep(POLL_INTERVAL_MS)
            val pollStartedAt = SystemClock.elapsedRealtime()
            val pollRequest = Request.Builder()
                .url("$INTERACTIONS_ENDPOINT/${id.substringAfterLast('/')}")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            root = client.newCall(pollRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Không đọc được tiến trình dịch: HTTP ${response.code} ${sanitizeForLog(body, 500)}"
                    )
                }
                JSONObject(body)
            }
            if (pollCount == 1 || pollCount % 5 == 0 || root.optString("status") == "completed") {
                logger.log(
                    3,
                    TAG,
                    "Poll interaction count=$pollCount status=${root.optString("status")} elapsedMs=${SystemClock.elapsedRealtime() - pollStartedAt}",
                )
            }
        }

        return InteractionResult(
            root = root,
            interactionId = interactionId,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun extractOutputText(root: JSONObject): String {
        root.optString("output_text").takeIf(String::isNotBlank)?.let { return it.trim() }

        val blocks = ArrayList<String>()
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val item = content.optJSONObject(j) ?: continue
                    if (item.optString("type") == "text") {
                        item.optString("text").takeIf(String::isNotBlank)?.let(blocks::add)
                    }
                }
            }
        }
        return blocks.joinToString("").trim().ifBlank {
            error("Gemini không trả nội dung bản dịch")
        }
    }

    private fun parseAndValidate(
        outputText: String,
        source: List<SubtitleStore.Cue>,
    ): List<Item> {
        val parsed = JSONObject(outputText)
        val translations = parsed.optJSONArray("translations")
            ?: error("Kết quả thiếu trường translations")

        val expectedById = source.associateBy { it.index }
        val resultById = LinkedHashMap<Int, String>()
        val duplicateIds = ArrayList<Int>()
        val unknownIds = ArrayList<Int>()
        val blankIds = ArrayList<Int>()

        for (i in 0 until translations.length()) {
            val item = translations.optJSONObject(i)
                ?: error("translations[$i] không phải object")
            val id = item.optInt("id", Int.MIN_VALUE)
            val text = item.optString("text").trim()
            if (id !in expectedById) {
                unknownIds += id
                continue
            }
            if (resultById.containsKey(id)) {
                duplicateIds += id
                continue
            }
            if (expectedById.getValue(id).text.isNotBlank() && text.isBlank()) {
                blankIds += id
            }
            resultById[id] = text
        }

        val missingIds = expectedById.keys.filterNot(resultById::containsKey)
        val valid = translations.length() == source.size &&
            duplicateIds.isEmpty() &&
            unknownIds.isEmpty() &&
            missingIds.isEmpty() &&
            blankIds.isEmpty()

        logger.log(
            if (valid) 2 else 1,
            TAG,
            "Validate structured output expected=${source.size} returned=${translations.length()} unique=${resultById.size} missing=${missingIds.size} duplicate=${duplicateIds.size} unknown=${unknownIds.size} blank=${blankIds.size} missingIds=${compactIds(missingIds)} duplicateIds=${compactIds(duplicateIds)} unknownIds=${compactIds(unknownIds)} blankIds=${compactIds(blankIds)}",
        )

        if (!valid) {
            error(
                "Kết quả dịch không hợp lệ: expected=${source.size}, returned=${translations.length()}, missing=${missingIds.size}, duplicate=${duplicateIds.size}, unknown=${unknownIds.size}, blank=${blankIds.size}"
            )
        }

        return source.map { cue ->
            Item(cue.index, resultById.getValue(cue.index))
        }
    }

    private fun buildSchema(cueCount: Int): JSONObject =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "translations",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", cueCount)
                        .put("maxItems", cueCount)
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put(
                                            "id",
                                            JSONObject()
                                                .put("type", "integer")
                                                .put("description", "ID gốc của mục phụ đề; phải giữ nguyên.")
                                        )
                                        .put(
                                            "text",
                                            JSONObject()
                                                .put("type", "string")
                                                .put("description", "Bản dịch tiếng Việt của text tương ứng.")
                                        )
                                )
                                .put("required", JSONArray().put("id").put("text"))
                                .put("additionalProperties", false)
                        )
                )
            )
            .put("required", JSONArray().put("translations"))
            .put("additionalProperties", false)

    private fun buildPrompt(payload: JSONArray): String = """
Bạn là một biên dịch viên phụ đề chuyên nghiệp.

Nhiệm vụ của bạn là dịch toàn bộ nội dung phụ đề được cung cấp sang tiếng Việt tự nhiên, chính xác và phù hợp với ngữ cảnh của toàn bộ cuộc hội thoại.

QUY TẮC DỊCH:

1. Đọc và hiểu TOÀN BỘ phụ đề trước khi dịch. Sử dụng ngữ cảnh của các mục trước và sau để xác định đúng ý nghĩa, chủ thể, đại từ, cách xưng hô, thuật ngữ và sắc thái.

2. Dịch theo ý nghĩa trong ngữ cảnh, không dịch máy móc từng từ. Tiếng Việt phải tự nhiên như phụ đề được biên dịch bởi người thành thạo cả ngôn ngữ nguồn và tiếng Việt.

3. Giữ đầy đủ nội dung và ý nghĩa của bản gốc. Không tóm tắt, không lược bỏ, không thêm thông tin, không giải thích và không bình luận.

4. Giữ đúng sắc thái của người nói, bao gồm mức độ trang trọng, thân mật, hài hước, tức giận, mỉa mai, xúc động, thô tục hoặc các sắc thái khác nếu có. Không tự ý làm nhẹ hoặc làm mạnh nội dung.

5. Duy trì cách dịch nhất quán cho tên gọi, thuật ngữ, chức danh, vật thể, địa điểm và các khái niệm xuất hiện nhiều lần trong toàn bộ phụ đề.

6. Giữ nguyên tên riêng khi không có cách gọi tiếng Việt thông dụng. Không phiên âm hoặc dịch tên riêng một cách tùy tiện.

7. Các con số, đơn vị, ngày tháng, ký hiệu, URL, mã và thông tin kỹ thuật phải giữ đúng giá trị và ý nghĩa của bản gốc.

8. Một câu có thể bị chia giữa nhiều mục phụ đề. Hãy dùng ngữ cảnh liền trước và liền sau để hiểu câu hoàn chỉnh và tạo tiếng Việt tự nhiên. Có thể điều chỉnh trật tự từ giữa các mục liền kề khi cần cho tiếng Việt, nhưng mỗi mục nguồn không rỗng phải có một mục dịch không rỗng và không được làm mất, lặp hoặc chuyển sai ý nghĩa sang thời điểm khác.

9. PHẢI giữ nguyên số lượng mục và ID. Mỗi ID đầu vào phải có đúng một ID tương ứng trong đầu ra. Không gộp mục, không tách thêm mục, không tạo ID mới, không đổi ID và không bỏ ID.

10. Chỉ dịch trường "text". Không thay đổi "id".

11. Nếu một đoạn đã là tiếng Việt và không cần dịch, hãy giữ nguyên nội dung đó. Nếu nội dung có nhiều ngôn ngữ, hãy dịch các phần cần dịch sang tiếng Việt dựa trên ngữ cảnh.

12. Không tự thêm nhãn người nói, chú thích, dấu ngoặc giải thích hoặc nội dung không tồn tại trong bản gốc. Nếu nhãn người nói đã có trong text đầu vào thì giữ ý nghĩa và cấu trúc của nhãn đó.

13. Ưu tiên câu tiếng Việt rõ ràng, tự nhiên, súc tích và dễ đọc trên màn hình, nhưng không được hy sinh ý nghĩa để rút ngắn.

14. Trước khi trả kết quả, tự kiểm tra rằng không thiếu ID, không trùng ID, không có nội dung bị bỏ sót hoặc dịch lặp, cách xưng hô và thuật ngữ nhất quán từ đầu đến cuối, và mỗi bản dịch phù hợp với ngữ cảnh toàn bộ phụ đề.

Chỉ trả về dữ liệu theo schema đã được yêu cầu. Không thêm lời mở đầu, lời kết, giải thích hoặc bất kỳ văn bản nào ngoài kết quả có cấu trúc.

DỮ LIỆU PHỤ ĐỀ:
${payload}
""".trim()

    private fun compactIds(ids: List<Int>): String =
        if (ids.isEmpty()) "none" else ids.take(20).joinToString(",") +
            if (ids.size > 20) "...(+${ids.size - 20})" else ""

    private fun sanitizeForLog(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)

    companion object {
        private const val TAG = "SubtitleTranslate"
        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val MAX_ATTEMPTS = 2
        private const val MAX_POLLS = 120
        private const val POLL_INTERVAL_MS = 1_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
