package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR11SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r11SupportCoversAuthModelsAndFileWithoutReadingSecrets() {
        val support = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11Support.kt")
        assertTrue(support.contains("2026-09-02-web-session-r11.0-auth-model-file"))
        assertTrue(support.contains("probeSession"))
        assertTrue(support.contains("discoverModels"))
        assertTrue(support.contains("openModelPicker"))
        assertTrue(support.contains("selectModel"))
        assertTrue(support.contains("R11_GENERATE_MODEL_OBSERVED"))
        assertTrue(support.contains("markFileChooserServed"))
        assertTrue(support.contains("attachFile"))
        assertTrue(support.contains("attachmentState"))
        assertTrue(support.contains("R11_UPLOAD_START"))
        assertTrue(support.contains("R11_UPLOAD_COMPLETE"))
        assertFalse(support.contains("document.cookie"))
        assertFalse(support.contains("Authorization="))
        assertFalse(support.contains("X-Goog-Api-Key="))
        assertFalse(support.contains("password="))
    }

    @Test
    fun r11ActivityUsesNativeShellRealWebAuthAndOneSelectedUri() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11Activity.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR11Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R11 - Login Model File"))
        assertTrue(activity.contains("AiStudioWebSessionExecutor"))
        assertTrue(activity.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(activity.contains("takePersistableUriPermission"))
        assertTrue(activity.contains("onShowFileChooser"))
        assertTrue(activity.contains("filePathCallback.onReceiveValue(arrayOf(selected.uri))"))
        assertTrue(activity.contains("AiStudioWebSessionR11Support.DOCUMENT_START"))
        assertTrue(activity.contains("refreshModels(openPickerIfEmpty = true)"))
        assertTrue(activity.contains("selectCurrentModel"))
        assertTrue(activity.contains("attachSelectedFile"))
        assertTrue(activity.contains("R11_E2E_RESULT"))
        assertTrue(activity.contains("Hãy xem toàn bộ video này và tóm tắt chi tiết"))
        assertFalse(activity.contains("Gemini API Key"))
        assertFalse(activity.contains("Mật khẩu"))
        assertFalse(activity.contains("password"))
        assertFalse(activity.contains("document.cookie"))
    }

    @Test
    fun r11LoggingHasEnoughMilestonesForDeviceDiagnosis() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11Activity.kt")
        val support = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11Support.kt")
        listOf(
            "R11_ACTIVITY_CREATE",
            "R11_AUTH_PROBE_NATIVE",
            "R11_MODEL_CATALOG_NATIVE",
            "R11_MODEL_SELECT_VERIFY",
            "R11_FILE_SELECTED",
            "R11_FILE_CHOOSER_REQUEST",
            "R11_FILE_CHOOSER_SERVED",
            "R11_ATTACH_NATIVE_START",
            "R11_ATTACHMENT_POLL",
            "R11_ATTACHMENT_READY",
            "R11_E2E_START",
            "R11_E2E_RESULT",
        ).forEach { assertTrue("Thiếu log $it", activity.contains(it)) }
        listOf(
            "R11_SUPPORT_INSTALLED",
            "R11_MODEL_DISCOVERED",
            "R11_MODEL_NETWORK_BATCH",
            "R11_GENERATE_MODEL_OBSERVED",
            "R11_UPLOAD_START",
            "R11_UPLOAD_COMPLETE",
            "R11_MODEL_SELECT_RESULT",
            "R11_ATTACH_TRIGGER",
        ).forEach { assertTrue("Thiếu JS log $it", support.contains(it)) }
    }
}
