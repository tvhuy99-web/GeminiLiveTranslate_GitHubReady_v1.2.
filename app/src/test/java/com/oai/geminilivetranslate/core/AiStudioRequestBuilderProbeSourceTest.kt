package com.oai.geminilivetranslate.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioRequestBuilderProbeSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun probeIsInstalledAfterGatewayAndRemainsObservationOnly() {
        val facade = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioRequestGateway.kt")
        val probe = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestBuilderProbeScript.kt")

        val gatewayInstall = facade.indexOf("AiStudioRequestGatewayScript.DOCUMENT_START")
        val probeInstall = facade.indexOf("AiStudioRequestBuilderProbeScript.INSTALL")
        assertTrue(gatewayInstall >= 0)
        assertTrue(probeInstall > gatewayInstall)

        assertTrue(probe.contains("return previousSend.apply(this, arguments)"))
        assertFalse(probe.contains("previousSend.call(this, rewritten"))
        assertFalse(probe.contains("xhr.abort()"))
        assertFalse(probe.contains("startReplay("))
    }

    @Test
    fun probeCapturesOnlyTrustedGenerateContentAndIgnoresGatewayReplay() {
        val probe = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestBuilderProbeScript.kt")

        assertTrue(probe.contains("function trustedGenerateUrl(raw)"))
        assertTrue(probe.contains("host.endsWith('.google.com')"))
        assertTrue(probe.contains("host.endsWith('.googleapis.com')"))
        assertTrue(probe.contains("MakerSuiteService\\/GenerateContent"))
        assertTrue(probe.contains("xhr.__aisRequestGatewayReplay"))
        assertFalse(probe.contains("BidiGenerateContent"))
    }

    @Test
    fun observedDeviceShapesAreClassifiedWithoutExportingOpaquePayloads() {
        val probe = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestBuilderProbeScript.kt")

        assertTrue(probe.contains("root.length === 14"))
        assertTrue(probe.contains("mediaProfile = 'video-attachment'"))
        assertTrue(probe.contains("root.length === 5"))
        assertTrue(probe.contains("mediaProfile = 'stt-attachment'"))
        assertTrue(probe.contains("nonTextPartCount"))
        assertTrue(probe.contains("stackFrames:sanitizeStack()"))

        assertFalse(probe.contains("headers:last"))
        assertFalse(probe.contains("cookie:"))
        assertFalse(probe.contains("cookies:"))
        assertFalse(probe.contains("snapshot:"))
        assertFalse(probe.contains("rawBody"))
        assertFalse(probe.contains("body:String(body)"))
    }

    @Test
    fun stackDiagnosticsStripUrlQueriesAndBoundEveryFrame() {
        val probe = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestBuilderProbeScript.kt")

        assertTrue(probe.contains("u.origin + u.pathname"))
        assertTrue(probe.contains("split(/[?#]/)[0]"))
        assertTrue(probe.contains("token|key|auth|code|session|sig"))
        assertTrue(probe.contains("slice(0,280)"))
        assertTrue(probe.contains("slice(1,14)"))
    }
}
