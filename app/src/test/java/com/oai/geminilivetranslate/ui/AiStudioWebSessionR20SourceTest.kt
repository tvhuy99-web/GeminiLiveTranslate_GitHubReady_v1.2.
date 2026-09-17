package com.oai.geminilivetranslate.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioWebSessionR20SourceTest {
    @Test
    fun screenForensicDiagnosticsAreWiredBeforeNativeTapScript() {
        val root = File(System.getProperty("user.dir")).canonicalFile
        val nativeTap = File(root, "src/main/java/com/oai/geminilivetranslate/network/AiStudioNativeTapDebugSupport.kt").readText()
        val forensic = File(root, "src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR20ForensicDiagnostics.kt").readText()
        val logs = File(root, "src/main/java/com/oai/geminilivetranslate/core/AppLogRepository.kt").readText()

        assertTrue(nativeTap.contains("AiStudioWebSessionR20ForensicDiagnostics.DOCUMENT_START"))
        assertTrue(nativeTap.contains("gemini-3\\.8-live"))
        assertTrue(forensic.contains("XHR_ABORT_CALL"))
        assertTrue(forensic.contains("XHR_REQUEST_BODY"))
        assertTrue(forensic.contains("XHR_RESPONSE_DELTA"))
        assertTrue(forensic.contains("ABORT_CONTROLLER"))
        assertTrue(forensic.contains("MEDIA_TRACK"))
        assertTrue(forensic.contains("RESOURCE_TIMING"))
        assertTrue(forensic.contains("STATE_SNAPSHOT"))
        assertTrue(logs.contains("MAX_MEMORY_ENTRIES = 30_000"))
        assertTrue(logs.contains("MAX_FILE_BYTES = 16L * 1024L * 1024L"))
        assertTrue(logs.contains("MAX_ROTATED_FILES = 7"))
    }
}
