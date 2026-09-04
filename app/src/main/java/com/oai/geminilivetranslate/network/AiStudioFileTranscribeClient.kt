package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Authenticated AI Studio FILE transcription. This is intentionally not Gemini Live. */
class AiStudioFileTranscribeClient(
    context: Context,
    private val logger: SessionLogger,
    private val model: String = AppPreferences.TRANSCRIBE_FILE_MODEL,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var executor: AiStudioWebSessionExecutor? = null

    fun transcribe(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String,
        speakerDiarization: Boolean,
        onProgress: (String, Int) -> Unit,
    ): GeminiFileTranscribeClient.Result {
        val size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { d -> d.length.takeIf { it >= 0L } ?: d.parcelFileDescriptor.statSize.takeIf { it >= 0L } }
        }.getOrNull() ?: -1L
        val startedAt = SystemClock.elapsedRealtime()
        logger.log(2, TAG, "START backend=aistudio-file model=$model name=$displayName mime=$mimeType bytes=$size live=false")
        onProgress("Đang mở AI Studio cho chép lời tệp...", 2)
        val exec = createAndAwaitReady()
        selectModel(exec)
        onProgress("Đang đưa tệp vào AI Studio và chờ trang đọc xong...", 8)
        attachAndWait(exec, uri, displayName, mimeType, size)
        val prompt = buildPrompt(speakerDiarization)
        onProgress("Tệp đã sẵn sàng; đang chép lời bằng model tệp...", 55)
        val result = generateNative(exec, prompt)
        val parsed = parse(result.modelText)
        logger.log(2, TAG, "DONE backend=aistudio-file model=$model chars=${parsed.text.length} words=${parsed.words.size} elapsedMs=${SystemClock.elapsedRealtime()-startedAt}")
        onProgress("Đang tạo kết quả...", 98)
        return parsed
    }

    fun cancel() {
        cancelled = true
        val current = executor
        executor = null
        main.post { current?.destroy() }
    }
    fun close() = cancel()

    private fun createAndAwaitReady(): AiStudioWebSessionExecutor {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val holder = AtomicReference<AiStudioWebSessionExecutor?>()
        main.post {
            val context = GeminiTranslateApp.currentActivity() ?: appContext
            val created = AiStudioWebSessionExecutor(context, object : AiStudioWebSessionExecutor.Events {
                override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
                    logger.log(3, TAG, "EXECUTOR state=$state detail=${detail.take(500)}")
                    if (state == AiStudioWebSessionExecutor.State.READY) latch.countDown()
                    if (state == AiStudioWebSessionExecutor.State.ERROR || state == AiStudioWebSessionExecutor.State.DESTROYED) {
                        failure.compareAndSet(null, "$state: $detail"); latch.countDown()
                    }
                }
                override fun onLog(name: String, detail: String) {
                    val level = if (name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3
                    logger.log(level, TAG, "$name ${detail.take(5000)}")
                }
            })
            holder.set(created); executor = created; created.start()
        }
        if (!latch.await(45, TimeUnit.SECONDS)) error("AI Studio file transcribe chưa sẵn sàng")
        failure.get()?.let { error(it) }
        return holder.get() ?: error("Không tạo được AI Studio file session")
    }

    private fun selectModel(exec: AiStudioWebSessionExecutor) {
        var last = ""
        repeat(12) { attempt ->
            val latch = CountDownLatch(1); val ok = AtomicReference(false); val detail = AtomicReference("")
            exec.selectModel(model) { yes, d -> ok.set(yes); detail.set(d); latch.countDown() }
            latch.await(4, TimeUnit.SECONDS)
            last = detail.get()
            if (ok.get()) { logger.log(2, TAG, "MODEL_READY model=$model attempt=${attempt+1}"); return }
            Thread.sleep(500)
        }
        error("Không chọn được model tệp $model: ${last.take(500)}")
    }

    private fun attachAndWait(exec: AiStudioWebSessionExecutor, uri: Uri, name: String, mime: String, size: Long) {
        val latch = CountDownLatch(1); val ok = AtomicReference(false); val detail = AtomicReference("")
        exec.attachFile(uri, name, mime, size, requireUploadReady = true) { yes, d -> ok.set(yes); detail.set(d); latch.countDown() }
        if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải tệp chép lời")
        if (!ok.get()) error("AI Studio chưa xác nhận tệp sẵn sàng: ${detail.get().take(700)}")
        logger.log(2, TAG, "ATTACHMENT_PREPARED model=$model name=$name")
    }

    private fun generateNative(exec: AiStudioWebSessionExecutor, prompt: String): AiStudioWebSessionExecutor.Result {
        val latch = CountDownLatch(1); val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()
        main.post {
            val accepted = exec.generateAttachmentNativeOnly(prompt) { r -> ref.set(r); latch.countDown() }
            if (!accepted && ref.get() == null) { ref.set(AiStudioWebSessionExecutor.Result(ok=false,error="NATIVE_FILE_GENERATE_NOT_ARMED")); latch.countDown() }
        }
        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio chép lời tệp")
        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp")
        if (!r.ok) error("AI Studio file transcribe thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")
        return r
    }

    private fun buildPrompt(diarization: Boolean): String = """
Hãy chép lời CHÍNH XÁC toàn bộ lời nói trong tệp đính kèm bằng model chuyên chép lời tệp.
Không dịch, không tóm tắt, không thêm nội dung không nghe thấy.
${if (diarization) "Phân biệt người nói khi có thể." else "Không cần phân biệt người nói."}
Chỉ trả về một JSON object hợp lệ, không markdown:
{"text":"toàn bộ bản chép lời","words":[{"text":"từ hoặc cụm từ","start_seconds":0.0,"end_seconds":0.5,"speaker":""}]}
Nếu không thể cung cấp timestamp từng từ, vẫn phải trả text đầy đủ và có thể để words là mảng rỗng.
""".trimIndent()

    private fun parse(raw: String): GeminiFileTranscribeClient.Result {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = clean.indexOf('{'); val b = clean.lastIndexOf('}')
        val root = if (a >= 0 && b > a) JSONObject(clean.substring(a, b + 1)) else JSONObject().put("text", clean)
        val text = root.optString("text").trim()
        val words = ArrayList<GeminiFileTranscribeClient.WordInfo>()
        val arr = root.optJSONArray("words")
        if (arr != null) for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val body = w.optString("text").trim(); if (body.isBlank()) continue
            val start = (w.optDouble("start_seconds", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
            val end = (w.optDouble("end_seconds", start / 1000.0) * 1000.0).toLong().coerceAtLeast(start)
            words += GeminiFileTranscribeClient.WordInfo(body, w.optString("speaker").takeIf(String::isNotBlank), start, end)
        }
        if (text.isBlank() && words.isEmpty()) error("AI Studio trả bản chép lời rỗng")
        return GeminiFileTranscribeClient.Result(text.ifBlank { words.joinToString(" ") { it.text } }, words)
    }

    private companion object { const val TAG = "AiStudioFileTranscribe" }
}
