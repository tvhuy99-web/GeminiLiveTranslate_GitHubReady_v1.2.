package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR11SubmitTargetSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r12DirectEngineCapturesPageHandlersAndKeepsRequestTemplatePageLocal() {
        val src = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionDirectEngine.kt")
        assertTrue(src.contains("2026-09-02-web-session-r12.0-direct-engine"))
        assertTrue(src.contains("t==='click'||t==='submit'"))
        assertTrue(src.contains("invokeDirect"))
        assertTrue(src.contains("R12_DIRECT_PLAN"))
        assertTrue(src.contains("R12_DIRECT_HANDLER_ATTEMPT"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_SUCCESS"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_FINAL"))
        assertTrue(src.contains("R12_REQUEST_TEMPLATE_CAPTURED"))
        assertTrue(src.contains("replayLastTemplate"))
        assertTrue(src.contains("form.requestSubmit"))
        assertTrue(src.contains("type=\"submit\""))
        assertFalse(src.contains("document.cookie"))
        assertFalse(src.contains("Authorization="))
        assertFalse(src.contains("X-Goog-Api-Key="))
        assertFalse(src.contains("password="))
    }

    @Test
    fun executorUsesDirectEngineBeforeLegacyUiFallbackAndContainsNoTouchSimulation() {
        val src = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(src.contains("2026-09-02-web-session-r12.0-direct-engine-executor"))
        assertTrue(src.contains("AiStudioWebSessionDirectEngine.DOCUMENT_START"))
        assertTrue(src.contains("tryDirectEngineRecovery"))
        assertTrue(src.contains("R12_DIRECT_RECOVERY_START"))
        assertTrue(src.contains("R12_DIRECT_DISPATCH"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_SUCCESS"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_FINAL"))
        assertTrue(src.contains("tryLegacyProgrammaticFallback"))
        assertFalse(src.contains("dispatchTouchEvent"))
        assertFalse(src.contains("MotionEvent"))
        assertFalse(src.contains("InputDevice.SOURCE_TOUCHSCREEN"))

        val handlerFinalIndex = src.indexOf("\"R9_HANDLER_FINAL\"")
        val directIndex = src.indexOf("tryDirectEngineRecovery(p.seq", handlerFinalIndex)
        val legacyMethodIndex = src.indexOf("private fun tryLegacyProgrammaticFallback")
        assertTrue(handlerFinalIndex >= 0)
        assertTrue(directIndex > handlerFinalIndex)
        assertTrue(legacyMethodIndex >= 0)
    }
}
