package com.oai.geminilivetranslate.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoHistoryReuseSourceTest {
    private fun source(path: String): String = sequenceOf(
        File(path),
        File("app/$path"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Không tìm thấy source để kiểm tra: $path")

    @Test
    fun filePickerUsesPersistableDocumentAccess() {
        val main = source("src/main/java/com/oai/geminilivetranslate/MainActivity.kt")
        assertTrue(main.contains("Intent.ACTION_OPEN_DOCUMENT"))
        assertTrue(main.contains("takePersistableUriPermission(uri, takeFlags)"))
        assertFalse(main.contains("Intent(Intent.ACTION_GET_CONTENT)"))
    }

    @Test
    fun historyPersistsReusableGeminiFileMetadata() {
        val history = source("src/main/java/com/oai/geminilivetranslate/core/SessionHistoryStore.kt")
        assertTrue(history.contains("val geminiFileName: String?"))
        assertTrue(history.contains("val geminiFileUri: String?"))
        assertTrue(history.contains("val geminiFileMimeType: String?"))
        assertTrue(history.contains("val geminiFileUploadedAtMs: Long"))
        assertTrue(history.contains("!geminiFileUri.isNullOrBlank()"))
    }

    @Test
    fun geminiClientReusesRemoteFileAndDoesNotDeleteItAfterDescription() {
        val client = source("src/main/java/com/oai/geminilivetranslate/network/GeminiVideoDescriptionClient.kt")
        assertTrue(client.contains("data class RemoteFile("))
        assertTrue(client.contains("reusableUploadedFile(remoteFile, source.mimeType)"))
        assertTrue(client.contains("onRemoteFileReady(readyRemote)"))
        assertFalse(client.contains("uploadedName?.let(::deleteUploadedFile)"))
    }

    @Test
    fun successfulUploadIsSavedToHistoryBeforeDescriptionFinishes() {
        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")
        assertTrue(service.contains("GeminiVideoDescriptionClient.RemoteFile("))
        assertTrue(service.contains("onRemoteFileReady = { remote ->"))
        assertTrue(service.contains("saveCurrentHistoryNow(\"video-upload-ready\")"))
    }
}
