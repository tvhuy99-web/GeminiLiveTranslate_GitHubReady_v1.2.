package com.oai.geminilivetranslate.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioRequestGatewaySafetySourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun textReplayIsFailClosedOnModelProofMediaAndEnvelopeShape() {
        val facade = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioRequestGateway.kt")
        val script = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestGatewayScript.kt")

        assertTrue(facade.contains("TEMPLATE_MODEL_NOT_CAPTURED"))
        assertTrue(facade.contains("TEXT_REPLAY_REQUIRES_SIMPLE_TEXT_TEMPLATE"))
        assertTrue(facade.contains("PROOF_NOT_READY"))
        assertTrue(facade.contains(".put(\"textOnly\", true)"))

        assertTrue(script.contains("request-gateway-v1.4-safe-envelope"))
        assertTrue(script.contains("function analyzeTextReplaySafety(contents)"))
        assertTrue(script.contains("function analyzeTextReplayEnvelope(root)"))
        assertTrue(script.contains("!systemInstructionPresent && !toolsPresent && !cachedContentPresent"))
        assertTrue(script.contains("const replaySafe = !!textSafety.safe && !!envelopeSafety.safe"))
        assertTrue(script.contains("if (requestedModel) return templates[templateKey(requestedModel)] || null"))
        assertTrue(script.contains("TEMPLATE_MODEL_NOT_CAPTURED"))
        assertTrue(script.contains("TEXT_REPLAY_FLAG_REQUIRED"))
        assertTrue(script.contains("TEXT_REPLAY_REQUIRES_SIMPLE_TEXT_TEMPLATE"))
        assertTrue(script.contains("textReplaySafe:!!(tpl&&tpl.textReplaySafe)"))
        assertFalse(script.contains("MakerSuiteService\\/(?:GenerateContent|BidiGenerateContent)"))
    }

    @Test
    fun captureAndReplayAreRestrictedToGoogleOwnedHosts() {
        val script = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestGatewayScript.kt")

        assertTrue(script.contains("function isTrustedGenerateHost(raw)"))
        assertTrue(script.contains("host === 'aistudio.google.com'"))
        assertTrue(script.contains("host.endsWith('.google.com')"))
        assertTrue(script.contains("host.endsWith('.googleapis.com')"))
        assertTrue(script.contains("if (!isTrustedGenerateHost(raw)) return false"))
    }

    @Test
    fun replayCancellationPersistsWhileProofIsStillPending() {
        val script = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestGatewayScript.kt")

        assertTrue(script.contains("done:false,cancelled:false"))
        assertTrue(script.contains("if (!item || item.done || item.cancelled) return"))
        assertTrue(script.contains("if (item.cancelled || item.done || !active[id]) return"))
        assertTrue(script.contains("item.cancelled = true"))
        assertTrue(script.contains("phase:'gateway-abort',error:'ABORTED'"))
    }

    @Test
    fun nativeFacadeDoesNotExportCapturedRequestSecrets() {
        val facade = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioRequestGateway.kt")

        assertFalse(facade.contains("data class CapturedRequest"))
        assertFalse(facade.contains("val headers:"))
        assertFalse(facade.contains("val body:"))
        assertFalse(facade.contains("val cookie:"))
        assertFalse(facade.contains("val cookies:"))
        assertFalse(facade.contains("val snapshot:"))
    }

    @Test
    fun productionVideoAndSttRemainPassiveGatewayObservers() {
        val video = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt")
        val stt = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt")

        assertTrue(video.contains("AiStudioRequestGateway"))
        assertTrue(stt.contains("AiStudioRequestGateway"))
        assertFalse(video.contains(".replayText("))
        assertFalse(stt.contains(".replayText("))
    }

    @Test
    fun liveTransportRemainsIndependentFromRequestGateway() {
        val realtime = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")
        val liveClient = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebLiveClient.kt")
        val liveOutput = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebLiveOutputBridge.kt")
        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")

        listOf(realtime, liveClient, liveOutput, service).forEach { liveSource ->
            assertFalse(liveSource.contains("AiStudioRequestGateway"))
            assertFalse(liveSource.contains("AiStudioIncrementalJsonArrayParser"))
        }
    }
}
