package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.VideoDescriptionPromptDefaults
import com.oai.geminilivetranslate.core.VideoDescriptionTimelineRules
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Video description through the user's authenticated AI Studio web session. No API key is used. */
class AiStudioVideoDescriptionClient(
    context: Context,
    private val logger: SessionLogger,
    private val includeOutputInLogs: Boolean,
    private val model: String,
    private val timelinePromptTemplate: String,
    private val summaryPromptTemplate: String,
    private val requestTimeoutMs: Int,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var executor: AiStudioWebSessionExecutor? = null

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
        require(durationMs in 1..GeminiVideoDescriptionClient.MAX_VIDEO_DURATION_MS) {
            "Video phải dài tối đa 20 phút"
        }
        throwIfCancelled()
        val startedAt = SystemClock.elapsedRealtime()
        val sourceBytes = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { d ->
                d.length.takeIf { it >= 0L } ?: d.parcelFileDescriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull() ?: -1L
        logger.log(2, TAG, "Bắt đầu authenticatedWeb=true model=$model mode=$mode name=$displayName mime=$mimeType bytes=$sourceBytes durationMs=$durationMs")
        onProgress("Đang mở phiên AI Studio đã đăng nhập...", 2)

        val exec = createAndAwaitReady()
        throwIfCancelled()
        onProgress("Đang chọn model mô tả video...", 8)
        selectModel(exec)
        throwIfCancelled()
        onProgress("Đang tải và chờ AI Studio xử lý nguyên video...", 12)
        attachVideo(exec, uri, displayName, mimeType, sourceBytes)
        throwIfCancelled()

        val durationSeconds = durationMs / 1_000.0
        val basePrompt = VideoDescriptionPromptDefaults.render(
            if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) timelinePromptTemplate else summaryPromptTemplate,
            durationSeconds,
        )
        val formatInstruction = if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {
            """

YÊU CẦU ĐỊNH DẠNG BẮT BUỘC CHO ỨNG DỤNG
Chỉ trả về MỘT JSON object hợp lệ, không markdown, không code fence, không văn bản ngoài JSON.
Cấu trúc chính xác:
{"items":[{"index":1,"start_seconds":0.0,"end_seconds":5.0,"type":"description","text":"Mô tả tiếng Việt"}]}
index phải liên tục từ 1; type luôn là "description"; start_seconds/end_seconds là số; mỗi item tối đa 15 giây.
            """.trimIndent()
        } else {
            """

YÊU CẦU ĐỊNH DẠNG BẮT BUỘC CHO ỨNG DỤNG
Chỉ trả về MỘT JSON object hợp lệ, không markdown, không code fence, không văn bản ngoài JSON.
Cấu trúc chính xác: {"text":"Bản tường thuật tổng hợp bằng tiếng Việt"}
            """.trimIndent()
        }
        val prompt = basePrompt + "\n\n" + formatInstruction
        onProgress(
            if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) "Đang mô tả toàn bộ video..." else "Đang tổng hợp toàn bộ video...",
            25,
        )
        logger.log(2, TAG, "R22_VIDEO_AUTO_SUBMIT_AFTER_READY promptChars=${prompt.length} model=$model mode=$mode autoSubmit=true readinessGate=attachment-prepared")
        onProgress("Video đã tải và xử lý xong; ứng dụng đang tự nhấn Run...", 25)
        val webResult = generateAndAwaitAuto(exec, prompt)
        val output = webResult.modelText.trim()
        if (output.isBlank()) error("AI Studio không trả nội dung mô tả")
        onPartial(output)
        if (includeOutputInLogs) logger.log(3, TAG, "Output preview=${output.replace('\n', ' ').take(2000)}")

        val timelineItems: List<GeminiVideoDescriptionClient.TimelineItem>
        val summaryText: String
        if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {
            timelineItems = parseTimeline(output, durationSeconds)
            summaryText = ""
        } else {
            timelineItems = emptyList()
            summaryText = parseSummary(output)
        }
        onProgress("Đang tạo kết quả...", 98)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        logger.log(2, TAG, "Hoàn tất mode=$mode items=${timelineItems.size} summaryChars=${summaryText.length} elapsedMs=$elapsed authenticatedWeb=true")
        return GeminiVideoDescriptionClient.Result(
            timelineItems = timelineItems,
            summaryText = summaryText,
            interactionId = "aistudio-web-session",
            attempts = 1,
            elapsedMs = elapsed,
            inputTokens = 0,
            outputTokens = 0,
            thoughtTokens = 0,
            totalTokens = 0,
        )
    }

    fun cancel() {
        cancelled = true
        val current = executor
        executor = null
        main.post { current?.destroy() }
    }

    fun close() = cancel()

    private fun createAndAwaitReady(): AiStudioWebSessionExecutor {
        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val holder = AtomicReference<AiStudioWebSessionExecutor?>()
        main.post {
            if (cancelled) {
                failure.set("CANCELLED")
                ready.countDown()
                return@post
            }
            val context = GeminiTranslateApp.currentActivity() ?: appContext
            val created = AiStudioWebSessionExecutor(
                context,
                object : AiStudioWebSessionExecutor.Events {
                    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
                        logger.log(3, TAG, "EXECUTOR state=$state detail=${detail.take(500)}")
                        when (state) {
                            AiStudioWebSessionExecutor.State.READY -> ready.countDown()
                            AiStudioWebSessionExecutor.State.ERROR,
                            AiStudioWebSessionExecutor.State.DESTROYED -> {
                                failure.compareAndSet(null, "$state: $detail")
                                ready.countDown()
                            }
                            else -> Unit
                        }
                    }
                    override fun onLog(name: String, detail: String) {
                        val level = when {
                            name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2
                            name.contains("ERROR") || name.contains("TIMEOUT") -> 1
                            else -> 3
                        }
                        logger.log(level, "AiStudioVideoWeb", "$name ${detail.take(4000)}")
                    }
                },
            )
            holder.set(created)
            executor = created
            created.start()
        }
        if (!ready.await(45, TimeUnit.SECONDS)) error("AI Studio chưa sẵn sàng sau 45 giây")
        throwIfCancelled()
        failure.get()?.let { error(it) }
        return holder.get() ?: error("Không tạo được AI Studio Web Session")
    }

    private fun selectModel(exec: AiStudioWebSessionExecutor) {
        var last = ""
        repeat(12) { attempt ->
            throwIfCancelled()
            val latch = CountDownLatch(1)
            val okRef = AtomicReference(false)
            val detailRef = AtomicReference("")
            exec.selectModel(model) { ok, detail ->
                okRef.set(ok); detailRef.set(detail); latch.countDown()
            }
            if (!latch.await(4, TimeUnit.SECONDS)) last = "MODEL_SELECT_TIMEOUT"
            else {
                last = detailRef.get()
                if (okRef.get()) {
                    logger.log(2, TAG, "Model selected model=$model attempt=${attempt + 1}")
                    return
                }
            }
            Thread.sleep(500)
        }
        error("Không chọn được model $model: ${last.take(500)}")
    }

    private fun attachVideo(
        exec: AiStudioWebSessionExecutor,
        uri: Uri,
        displayName: String,
        mimeType: String,
        size: Long,
    ) {
        val latch = CountDownLatch(1)
        val okRef = AtomicReference(false)
        val detailRef = AtomicReference("")
        exec.attachFile(uri, displayName, mimeType, size, requireUploadReady = true) { ok, detail ->
            okRef.set(ok); detailRef.set(detail); latch.countDown()
        }
        if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải/xử lý video")
        throwIfCancelled()
        if (!okRef.get()) error("AI Studio chưa xác nhận video sẵn sàng: ${detailRef.get().take(500)}")
        logger.log(2, TAG, "R22_VIDEO_ATTACHMENT_READY name=$displayName size=$size readiness=server-payload-settled+ready-after-busy")
    }

    private fun generateAndAwaitAuto(
        exec: AiStudioWebSessionExecutor,
        prompt: String,
    ): AiStudioWebSessionExecutor.Result {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<AiStudioWebSessionExecutor.Result?>()
        main.post {
            val accepted = exec.generateAttachmentNativeOnly(prompt = prompt) { result ->
                resultRef.set(result)
                latch.countDown()
            }
            if (!accepted && resultRef.get() == null) {
                resultRef.set(AiStudioWebSessionExecutor.Result(ok = false, error = "AUTO_GENERATE_NOT_ARMED"))
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio mô tả video")
        throwIfCancelled()
        val result = resultRef.get() ?: error("AI Studio không trả trạng thái mô tả video")
        if (!result.ok) error("AI Studio GenerateContent tự động thất bại: ${result.error.ifBlank { "HTTP ${result.status}" }}")
        return result
    }

    private fun parseTimeline(raw: String, durationSeconds: Double): List<GeminiVideoDescriptionClient.TimelineItem> {
        val root = JSONObject(extractJsonObject(raw))
        val array = root.optJSONArray("items") ?: error("Kết quả AI Studio thiếu trường items")
        if (array.length() == 0) error("AI Studio không tạo mục mô tả nào")
        val rules = ArrayList<VideoDescriptionTimelineRules.Item>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("items[$i] không phải object")
            rules += VideoDescriptionTimelineRules.Item(
                index = item.optInt("index", Int.MIN_VALUE),
                startSeconds = item.optDouble("start_seconds", Double.NaN),
                endSeconds = item.optDouble("end_seconds", Double.NaN),
                text = item.optString("text").trim(),
            )
        }
        val validation = VideoDescriptionTimelineRules.validate(
            items = rules,
            durationSeconds = durationSeconds,
            maxItemSeconds = 15.0,
            toleranceSeconds = 0.10,
        )
        if (!validation.valid) error("Timeline AI Studio không hợp lệ: ${validation.errors.take(8).joinToString("; ")}")
        return rules.map { GeminiVideoDescriptionClient.TimelineItem(it.index, it.startSeconds, it.endSeconds, it.text) }
    }

    private fun parseSummary(raw: String): String {
        val normalized = raw.trim()
        val jsonText = runCatching { extractJsonObject(normalized) }.getOrNull()
        val parsed = jsonText?.let { runCatching { JSONObject(it).optString("text").trim() }.getOrNull() }
        return parsed?.takeIf(String::isNotBlank) ?: normalized.takeIf(String::isNotBlank)
            ?: error("AI Studio trả mô tả tổng hợp rỗng")
    }

    private fun extractJsonObject(raw: String): String {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("Kết quả không chứa JSON object hợp lệ")
        return text.substring(start, end + 1)
    }

    private fun throwIfCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Đã hủy mô tả video AI Studio")
    }

    companion object {
        private const val TAG = "AiStudioVideo"
    }
}
