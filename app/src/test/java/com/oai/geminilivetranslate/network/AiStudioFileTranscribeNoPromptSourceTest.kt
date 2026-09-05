package com.oai.geminilivetranslate.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioFileTranscribeNoPromptSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun authenticatedFileTranscribeUsesFileOnlyModelInvocation() {
        val src = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt")
        assertTrue(src.contains("generateSttFileNative"))
        assertTrue(src.contains("manualRun=false autoSubmit=true"))
        assertTrue(src.contains("startFileTranscribe(model)"))
        assertTrue(src.contains("attachSttFile"))
        assertTrue(src.contains("transport=aistudio-stt-direct-page"))
        assertTrue(src.contains("prompt=false"))
        assertFalse(src.contains("awaitManualAttachmentFileOnlyGenerate"))
        assertFalse(src.contains("manualRun=true"))
        assertTrue(src.contains("parsePlainTranscript"))
        assertFalse(src.contains("buildPrompt("))
        assertFalse(src.contains("Hãy chép lời CHÍNH XÁC"))
        assertFalse(src.contains("Chỉ trả về một JSON object"))
    }
}
