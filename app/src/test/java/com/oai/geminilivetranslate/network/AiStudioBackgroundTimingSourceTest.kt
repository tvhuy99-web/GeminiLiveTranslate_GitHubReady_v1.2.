package com.oai.geminilivetranslate.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioBackgroundTimingSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun attachmentExecutorResyncsBeforeTimingOutVideoAndStt() {
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(executor.contains("GeminiTranslateApp.currentActivity() == null"))
        assertTrue(executor.contains("""resyncPendingRequest(requestSeq, "timeout-probe")"""))
        assertTrue(executor.contains("R38_ATTACHMENT_TIMEOUT_CONFIRMED"))
        assertTrue(executor.contains("R38_STT_TIMEOUT_RESYNC"))
        assertTrue(executor.contains("""put("tag","STT_RUN")"""))
        assertTrue(executor.contains("""put("role","stt-run")"""))
        assertFalse(executor.contains("performAccessibilityAction"))
    }

    @Test
    fun realtimeAiStudioModesProtectWebViewAndSuppressBackgroundStaleTimeouts() {
        val live = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")
        assertTrue(live.contains("2026-09-06-production-ai-studio-live-r8-background-resync"))
        assertTrue(live.contains("RENDERER_PRIORITY_IMPORTANT"))
        assertTrue(live.contains("R38_LIVE_WEBVIEW_BACKGROUND_POLICY"))
        assertTrue(live.contains("R38_LIVE_BACKGROUND_DEFER"))
        assertTrue(live.contains("R38_LIVE_SCHEDULER_GAP"))
        assertTrue(live.contains("!suppressTimeouts && !setupDelivered.get()"))
        assertTrue(live.contains("!suppressTimeouts && setupDelivered.get()"))
    }
}
