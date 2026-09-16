package com.oai.geminilivetranslate.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioRequestGatewaySafetySourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun textReplayIsFailClosedOnModelProofAndMediaShape() {
        val facade = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioRequestGateway.kt")
        val script = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioRequestGatewayScript.kt")

        assertTrue(facade.contains("TEMPLATE_MODEL_NOT_CAPTURED"))
        assertTrue(facade.contains("TEXT_REPLAY_REQUIRES_SIMPLE_TEXT_TEMPLATE"))
        assertTrue(facade.contains("PROOF_NOT_READY"))
        assertTrue(facade.contains(".put(\"textOnly\", true)"))

        assertTrue(script.contains("request-gateway-v1.1-safe-text-replay"))
        assertTrue(script.contains("function analyzeTextReplaySafety(contents)"))
        assertTrue(script.contains("if (requestedModel) return templates[templateKey(requestedModel)] || null"))
        assertTrue(script.contains("TEMPLATE_MODEL_NOT_CAPTURED"))
        assertTrue(script.contains("TEXT_REPLAY_FLAG_REQUIRED"))
        assertTrue(script.contains("TEXT_REPLAY_REQUIRES_SIMPLE_TEXT_TEMPLATE"))
        assertTrue(script.contains("textReplaySafe:!!(tpl&&tpl.textReplaySafe)"))
        assertFalse(script.contains("MakerSuiteService\\/(?:GenerateContent|BidiGenerateContent)"))
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
}
