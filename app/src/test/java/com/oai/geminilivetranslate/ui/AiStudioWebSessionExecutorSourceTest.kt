package com.oai.geminilivetranslate.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioWebSessionExecutorSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun executorContainsOnlyCurrentVideoAndDedicatedSttGenerationPaths() {
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(executor.contains("2026-09-06-web-session-r12.11-background-resync"))
        assertTrue(executor.contains("RENDERER_PRIORITY_IMPORTANT"))
        assertTrue(executor.contains("R37_WEBVIEW_BACKGROUND_POLICY"))
        assertTrue(executor.contains("R38_WATCHDOG_BACKGROUND_DEFER"))
        assertTrue(executor.contains("R38_WATCHDOG_SCHEDULER_GAP"))
        assertTrue(executor.contains("R38_TIMEOUT_RESYNC"))
        assertTrue(executor.contains("R38_TIMEOUT_CONFIRMED"))
        assertTrue(executor.contains("R38_ATTACHMENT_SCHEDULER_GAP"))
        assertTrue(executor.contains("R38_STT_TIMEOUT_RESYNC"))
        assertTrue(executor.contains("startFileTranscribe"))
        assertTrue(executor.contains("attachSttFile"))
        assertTrue(executor.contains("generateSttFileNative"))
        assertTrue(executor.contains("generateAttachmentNativeOnly"))
        assertTrue(executor.contains("R35_VIDEO_PARTIAL_RAW"))
        assertTrue(executor.contains("nativeTapController.requestNativeTap"))
        assertFalse(executor.contains("generateAttachmentFileOnlyNative"))
        assertFalse(executor.contains("awaitManualAttachment"))
        assertFalse(executor.contains("AiStudioWebSessionDirectEngine"))
        assertFalse(executor.contains("AiStudioGoogleAccountBootstrap"))
    }

    @Test
    fun retainedWebScriptsExposeOnlyCurrentAttachmentSubmitAndDiscoveryApis() {
        val requestFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        val submitFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt")
        val discovery = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionAdaptiveRuntime.kt")
        assertTrue(requestFix.contains("2026-09-05-web-session-r11.15-video-attachment"))
        assertTrue(requestFix.contains("attachmentEvidence"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_START"))
        assertFalse(requestFix.contains("stripUnsupportedTranscribe"))
        assertFalse(requestFix.contains("installAdaptiveFallback"))
        assertFalse(requestFix.contains("submitAttachmentViaButton"))
        assertTrue(submitFix.contains("2026-09-05-web-session-r11.11-video-submit"))
        assertTrue(submitFix.contains("nativeTargetIfAttachment"))
        assertTrue(submitFix.contains("submitIfAttachment"))
        assertFalse(submitFix.contains("nativeTargetIfAttachmentFileOnly"))
        assertTrue(discovery.contains("discover:function"))
        assertFalse(discovery.contains("generate:function"))
        assertFalse(discovery.contains("cancel:function"))
    }
}
