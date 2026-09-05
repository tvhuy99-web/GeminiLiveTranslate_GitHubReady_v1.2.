package com.oai.geminilivetranslate.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioDirectSttPageSourceTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/oai/geminilivetranslate/$path"),
        File("app/src/main/java/com/oai/geminilivetranslate/$path"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test fun fileTranscribeUsesDedicatedSttPage() {
        val client = source("network/AiStudioFileTranscribeClient.kt")
        val executor = source("core/AiStudioWebSessionExecutor.kt")
        val bridge = source("ui/AiStudioSttPageBridge.kt")
        assertTrue(client.contains("startFileTranscribe(model)"))
        assertTrue(client.contains("attachSttFile"))
        assertTrue(client.contains("generateSttFileNative"))
        assertFalse(client.contains("selectModel(exec)"))
        assertTrue(executor.contains("/u/0/prompts/new_chat?model="))
        assertTrue(executor.contains("AiStudioSttPageBridge.DOCUMENT_START"))
        assertTrue(executor.contains("R28_STT_NETWORK_COMPLETE_EMPTY"))
        assertTrue(bridge.contains("ms-stt-zero-state"))
        assertTrue(bridge.contains("data-test-upload-file-input"))
        assertTrue(bridge.contains("R28_STT_RESULT_STATE"))
    }
}
