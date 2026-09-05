package com.oai.geminilivetranslate.service

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AiStudioAllModesRoutingSourceTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun aiStudioBypassesGlobalGeminiKeyGateAndStreamsFileTranscribe() {
        val text = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")
        assertTrue(text.contains("!aiStudioMode && apiKey.isNullOrBlank()"))
        assertTrue(text.contains("liveCredential(apiKey)"))
        assertTrue(text.contains("useLiveFileTranscribe"))
        assertTrue(text.contains("mode == SourceMode.FILE && !transcribeFileViaLive"))
        assertTrue(text.contains("AiStudioVideoDescriptionClient("))
    }

    @Test
    fun executorHasProductionAttachmentAndModelSupport() {
        val text = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(text.contains("AiStudioWebSessionR11Support.DOCUMENT_START"))
        assertTrue(text.contains("AiStudioWebSessionR11RequestFix.DOCUMENT_START"))
        assertTrue(text.contains("override fun onShowFileChooser"))
        assertTrue(text.contains("armTrustedFileChooser"))
    }

    @Test
    fun languageGuardDoesNotSilentlyDefaultToVietnamese() {
        val text = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18LanguageGuard.kt")
        assertTrue(text.contains("TARGET_LANGUAGE_MISSING_OR_INVALID"))
        assertFalse(text.contains("String(value||'vi')"))
    }
}
