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
        onProgress("Đang đưa tệp vào trang chép lời AI Studio và chờ trang đọc xong...", 8)
        attachAndWait(exec, uri, displayName, mimeType, size)
        logger.log(2, TAG, "CONFIG model=$model prompt=false autoLanguage=true diarizationRequested=$speakerDiarization transport=aistudio-stt-direct-page manualRun=false autoSubmit=true")
        onProgress("Tệp đã tải/xử lý xong. Ứng dụng đang tự nhấn Run để bắt đầu chép lời...", 55)
        logger.log(2, TAG, "R24_FILE_TRANSCRIBE_AUTO_SUBMIT_START model=$model prompt=false fileOnly=true")
        val result = generateFileOnly(exec)
        val parsed = parsePlainTranscript(result.modelText)
        logger.log(2, TAG, "DONE backend=aistudio-file model=$model chars=${parsed.text.length} words=${parsed.words.size} elapsedMs=${SystemClock.elapsedRealtime()-startedAt}")
        onProgress("Đang tạo kết quả...", 98)
        return parsed
    }

    fun cancel() {
        cancelled = true
        val current = executor
        executor = null
        main.post {
            current?.destroy()
            // File Transcribe is intentionally inspectable after every outcome. destroy() retains the
            // real session WebView; force it back on-screen even if the general debug visibility
            // preference is off so success/failure never erases the user's inspection surface.
            AiStudioDebugWebViewHost.setVisibleForActive(true, logger)
            logger.log(2, TAG, "R28_STT_WEBVIEW_FORCE_VISIBLE stage=retained-after-close")
        }
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
                    val level = if (name.startsWith("R29_") || name.startsWith("JS_R29_") || name.startsWith("R28_") || name.startsWith("JS_R28_") || name.startsWith("R27_") || name.startsWith("JS_R27_") || name.startsWith("R26_") || name.startsWith("JS_R26_") || name.startsWith("R25_") || name.startsWith("JS_R25_") || name.startsWith("R24_") || name.startsWith("JS_R24_") || name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3
                    logger.log(level, TAG, "$name ${detail.take(5000)}")
                }
            })
            holder.set(created)
            executor = created
            created.startFileTranscribe(model)
            // Unlike the generic hidden debug mode, dedicated File Transcribe always shows the exact
            // AI Studio page being automated, from initial load through its retained final state.
            AiStudioDebugWebViewHost.setVisibleForActive(true, logger)
            logger.log(2, TAG, "R28_STT_WEBVIEW_FORCE_VISIBLE stage=start")
        }
        if (!latch.await(45, TimeUnit.SECONDS)) error("AI Studio file transcribe chưa sẵn sàng")
        failure.get()?.let { error(it) }
        return holder.get() ?: error("Không tạo được AI Studio file session")
    }

    private fun attachAndWait(exec: AiStudioWebSessionExecutor, uri: Uri, name: String, mime: String, size: Long) {
        var lastDetail = ""
        repeat(12) { attempt ->
            val latch = CountDownLatch(1)
            val ok = AtomicReference(false)
            val detail = AtomicReference("")
            exec.attachSttFile(uri, name, mime, size) { yes, d ->
                ok.set(yes); detail.set(d); latch.countDown()
            }
            if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải tệp chép lời")
            lastDetail = detail.get()
            if (ok.get()) {
                logger.log(2, TAG, "ATTACHMENT_PREPARED model=$model name=$name attempt=${attempt + 1}")
                return
            }
            if (lastDetail != "NOT_READY") error("AI Studio chưa xác nhận tệp sẵn sàng: ${lastDetail.take(700)}")
            logger.log(2, TAG, "R24_FILE_TRANSCRIBE_ATTACH_RETRY attempt=${attempt + 1}/12 reason=NOT_READY")
            Thread.sleep(500)
        }
        error("AI Studio chưa sẵn sàng để nhận tệp sau retry: ${lastDetail.take(700)}")
    }

    private fun generateFileOnly(exec: AiStudioWebSessionExecutor): AiStudioWebSessionExecutor.Result {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()
        main.post {
            val accepted = exec.generateSttFileNative { r -> ref.set(r); latch.countDown() }
            if (!accepted && ref.get() == null) {
                ref.set(AiStudioWebSessionExecutor.Result(ok = false, error = "AUTO_FILE_TRANSCRIBE_NOT_ARMED"))
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio chép lời tệp tự động")
        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp tự động")
        if (!r.ok) error("AI Studio file transcribe tự động thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")
        return r
    }

    private fun parsePlainTranscript(raw: String): GeminiFileTranscribeClient.Result {
        val clean = raw.trim()
            .removePrefix("```text")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (clean.isBlank()) error("AI Studio trả bản chép lời rỗng")
        return GeminiFileTranscribeClient.Result(clean, emptyList())
    }

    private companion object { const val TAG = "AiStudioFileTranscribe" }
}
