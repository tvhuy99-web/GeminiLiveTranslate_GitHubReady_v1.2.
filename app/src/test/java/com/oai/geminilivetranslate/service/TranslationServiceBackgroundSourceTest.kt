package com.oai.geminilivetranslate.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationServiceBackgroundSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun everyStartedSessionOwnsForegroundServiceLifecycle() {
        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")
        assertTrue(service.contains("ensureStartedForActiveSession()"))
        assertTrue(service.contains("ACTION_SESSION_KEEP_ALIVE"))
        assertTrue(service.contains("ContextCompat.startForegroundService"))
        assertTrue(service.contains("R37_BACKGROUND_SESSION_OWNED"))
    }

    @Test
    fun rebindingSameFileCannotStopRunningFileSession() {
        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")
        val main = source("src/main/java/com/oai/geminilivetranslate/MainActivity.kt")
        assertTrue(service.contains("selectedUri == uri"))
        assertTrue(service.contains("R37_SELECTED_FILE_REAPPLY_IGNORED"))
        assertTrue(main.contains("applyToService = !activeSession"))
        assertTrue(main.contains("R37_SERVICE_REBIND_ACTIVE"))
    }
}
