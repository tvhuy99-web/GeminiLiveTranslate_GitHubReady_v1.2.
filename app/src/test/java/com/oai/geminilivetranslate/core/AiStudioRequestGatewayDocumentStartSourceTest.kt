package com.oai.geminilivetranslate.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioRequestGatewayDocumentStartSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun gatewayIsInstalledInsideTheFirstNetworkDocumentStartHook() {
        val lab = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLabScripts.kt")
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")

        assertTrue(lab.contains("AiStudioRequestGatewayScript.DOCUMENT_START"))
        assertTrue(lab.contains("trimIndent() + \"\\n\" + AiStudioRequestGatewayScript.DOCUMENT_START"))

        val labHook = executor.indexOf("AiStudioWebSessionLabScripts.DOCUMENT_START")
        val r11Hook = executor.indexOf("AiStudioWebSessionR11RequestFix.DOCUMENT_START")
        assertTrue("Lab hook must be registered", labHook >= 0)
        assertTrue("R11 hook must be registered", r11Hook >= 0)
        assertTrue("Gateway/Lab must wrap XHR before R11 so it sees R11's effective body", labHook < r11Hook)
    }
}
