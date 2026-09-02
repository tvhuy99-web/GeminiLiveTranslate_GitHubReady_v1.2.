package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR11SubmitTargetSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r11R4TargetsTheAttachmentComposerAndCanLearnAProvenSendControl() {
        val src = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt")
        assertTrue(src.contains("2026-09-02-web-session-r11.4-composer-submit-target"))
        assertTrue(src.contains("findAttachmentSurface"))
        assertTrue(src.contains("promptCandidates"))
        assertTrue(src.contains("findComposerRoot"))
        assertTrue(src.contains("distanceScore"))
        assertTrue(src.contains("R11_SUBMIT_TARGET_DISCOVERY"))
        assertTrue(src.contains("R11_SUBMIT_TARGET_CLICK"))
        assertTrue(src.contains("R11_SUBMIT_TARGET_LISTENER_FALLBACK"))
        assertTrue(src.contains("R11_SUBMIT_TARGET_RESULT"))
        assertTrue(src.contains("R11_SUBMIT_TRUSTED_CLICK_SEEN"))
        assertTrue(src.contains("R11_SUBMIT_TARGET_LEARNED"))
        assertTrue(src.contains("submitIfAttachment"))
        assertTrue(src.contains("provenButton"))
        assertTrue(src.contains("composerRoot.contains"))
        assertFalse(src.contains("document.cookie"))
        assertFalse(src.contains("Authorization="))
        assertFalse(src.contains("X-Goog-Api-Key="))
        assertFalse(src.contains("password="))
    }

    @Test
    fun executorUsesTrustedTouchOnTheComposerTargetBeforeProgrammaticFallback() {
        val src = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(src.contains("AiStudioWebSessionR11SubmitTargetFix.DOCUMENT_START"))
        assertTrue(src.contains("R11_BROAD_FALLBACK_GUARD"))
        assertTrue(src.contains("tryAttachmentSubmitRecovery"))
        assertTrue(src.contains("R11_SUBMIT_TRUSTED_TARGET"))
        assertTrue(src.contains("dispatchTrustedWebViewTap"))
        assertTrue(src.contains("InputDevice.SOURCE_TOUCHSCREEN"))
        assertTrue(src.contains("MotionEvent.ACTION_DOWN"))
        assertTrue(src.contains("MotionEvent.ACTION_UP"))
        assertTrue(src.contains("R11_SUBMIT_TRUSTED_TOUCH"))
        assertTrue(src.contains("trusted attachment submit triggered GenerateContent"))
        assertTrue(src.contains("tryProgrammaticAttachmentSubmitRecovery"))
        assertTrue(src.contains("R11_SUBMIT_RECOVERY_DISPATCH"))
        assertTrue(src.contains("R11_SUBMIT_RECOVERY_RESULT"))

        val recoveryMethodIndex = src.indexOf("private fun tryAttachmentSubmitRecovery")
        val trustedTapIndex = src.indexOf("dispatchTrustedWebViewTap(", recoveryMethodIndex)
        val fallbackCallIndex = src.indexOf("tryProgrammaticAttachmentSubmitRecovery(requestSeq)", trustedTapIndex)
        val handlerFinalIndex = src.indexOf("\"R9_HANDLER_FINAL\"")
        val handlerRecoveryIndex = src.indexOf("tryAttachmentSubmitRecovery(p.seq)", handlerFinalIndex)
        assertTrue(recoveryMethodIndex >= 0)
        assertTrue(trustedTapIndex > recoveryMethodIndex)
        assertTrue(fallbackCallIndex > trustedTapIndex)
        assertTrue(handlerFinalIndex >= 0)
        assertTrue(handlerRecoveryIndex > handlerFinalIndex)
    }
}
