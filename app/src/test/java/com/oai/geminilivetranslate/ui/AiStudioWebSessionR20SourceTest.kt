package com.oai.geminilivetranslate.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioWebSessionR20SourceTest {
    @Test
    fun screenForensicDiagnosticsAreWiredBeforeNativeTapScript() {
        val root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val nativeTap = File(root, "src/main/java/com/oai/geminilivetranslate/network/AiStudioNativeTapDebugSupport.kt").readText()
        val forensic = File(root, "src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR20ForensicDiagnostics.kt").readText()
        val logs = File(root, "src/main/java/com/oai/geminilivetranslate/core/AppLogRepository.kt").readText()

        val forensicWire = nativeTap.indexOf("AiStudioWebSessionR20ForensicDiagnostics.DOCUMENT_START")
        val nativeTapScript = nativeTap.indexOf("window.__AIS_NATIVE_START_TAP__", forensicWire.coerceAtLeast(0))
        assertTrue(forensicWire >= 0)
        assertTrue(nativeTapScript > forensicWire)
        assertTrue(nativeTap.contains("gemini-3"))
        assertTrue(nativeTap.contains("8-live"))
        assertTrue(forensic.contains("XHR_ABORT_CALL"))
        assertTrue(forensic.contains("XHR_REQUEST_BODY"))
        assertTrue(forensic.contains("XHR_RESPONSE_DELTA"))
        assertTrue(forensic.contains("XHR_HANDSHAKE_RESPONSE"))
        assertTrue(forensic.contains("XHR_HANDSHAKE_RESPONSE_META"))
        assertTrue(forensic.contains("safeShape"))
        assertTrue(forensic.contains("payloads:[]"))
        assertTrue(forensic.contains("ABORT_CONTROLLER"))
        assertTrue(forensic.contains("MEDIA_TRACK"))
        assertTrue(forensic.contains("RESOURCE_TIMING"))
        assertTrue(forensic.contains("STATE_SNAPSHOT"))
        assertTrue(forensic.contains("r20.5-hot-upgrade-output-state"))
        assertTrue(forensic.contains("OUTPUT_TURN_STATE"))
        assertTrue(forensic.contains("ENGINE_UPGRADE"))
        assertTrue(forensic.contains("setInterval(snapshot,2000)"))
        assertTrue(logs.contains("MAX_MEMORY_ENTRIES = 12_000"))
        assertTrue(logs.contains("MAX_FILE_BYTES = 8L * 1024L * 1024L"))
        assertTrue(logs.contains("MAX_ROTATED_FILES = 3"))
        assertTrue(logs.contains("MAX_CLIPBOARD_CHARS = 220_000"))
        assertTrue(logs.contains("clipboardExport"))
    }
}
