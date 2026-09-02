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
        assertFalse(support.contains("type=\"password\""))
    }

    @Test
    fun r11RequestFixMovesModelSelectionToRequestAndUsesTrustedFileActivation() {
        val fix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        assertTrue(fix.contains("2026-09-02-web-session-r11.1-request-model-trusted-file"))
        assertTrue(fix.contains("R11_GENERATE_MODEL_REWRITE"))
        assertTrue(fix.contains("body.split(original).join(fix.selectedModel)"))
        assertTrue(fix.contains("path:'request-layer'"))
        assertTrue(fix.contains("armTrustedFileChooser"))
        assertTrue(fix.contains("ev.isTrusted !== true"))
        assertTrue(fix.contains("R11_FILE_TRUSTED_ACTIVATION"))
        assertFalse(fix.contains("document.cookie"))
        assertFalse(fix.contains("Authorization="))
        assertFalse(fix.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r11UnifiedActivityUsesOneSelectedUriAndInternalTrustedPulse() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11UnifiedActivity.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR11UnifiedActivity"))
        assertTrue(manifest.contains("AI Studio Web Session R11"))
        assertTrue(activity.contains("AiStudioWebSessionExecutor"))
        assertTrue(activity.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(activity.contains("takePersistableUriPermission"))
        assertTrue(activity.contains("onShowFileChooser"))
        assertTrue(activity.contains("filePathCallback.onReceiveValue(arrayOf(selected.uri))"))
        assertTrue(activity.contains("AiStudioWebSessionR11Support.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR11RequestFix.DOCUMENT_START"))
        assertTrue(activity.contains("dispatchTrustedFileActivationPulse"))
        assertTrue(activity.contains("InputDevice.SOURCE_TOUCHSCREEN"))
        assertTrue(activity.contains("R11_FILE_ACTIVATION_PULSE"))
        assertTrue(activity.contains("selectCurrentModel"))
        assertTrue(activity.contains("attachSelectedFile"))
        assertTrue(activity.contains("observed == expectedModel && rewriteCount > 0"))
        assertTrue(activity.contains("R11_E2E_RESULT"))
        assertTrue(activity.contains("Hãy xem toàn bộ video này và tóm tắt chi tiết"))
        assertFalse(activity.contains("Gemini API Key"))
        assertFalse(activity.contains("Mật khẩu:"))
        assertFalse(activity.contains("type=\"password\""))
        assertFalse(activity.contains("document.cookie"))
    }

    @Test
    fun onlyR11ExperimentRemainsOnLauncher() {
        val manifest = source("src/main/AndroidManifest.xml")
        val launcherCount = Regex("android.intent.category.LAUNCHER").findAll(manifest).count()
        // Main application + exactly one AI Studio R11 experiment.
        assertTrue("Launcher count phải là 2 nhưng là $launcherCount", launcherCount == 2)
        listOf(
            "AiStudioWebSessionR10Activity",
            "AiStudioWebSessionR7Activity",
            "AiStudioWebSessionR6Activity",
            "AiStudioWebSessionR5Activity",
            "AiStudioWebSessionR4Activity",
            "AiStudioWebSessionLabActivity",
            "AiStudioWebSessionLogShareActivity",
        ).forEach { activityName ->
            val block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.$activityName\\\"[\\s\\S]*?/>").find(manifest)?.value.orEmpty()
            assertTrue("Không tìm thấy block $activityName", block.isNotEmpty())
            assertFalse("$activityName không được là launcher", block.contains("LAUNCHER"))
        }
    }

    @Test
    fun r11LoggingHasEnoughMilestonesForDeviceDiagnosis() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11UnifiedActivity.kt")
        val support = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11Support.kt")
        val fix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        listOf(
            "R11_ACTIVITY_CREATE",
            "R11_AUTH_PROBE_NATIVE",
            "R11_MODEL_CATALOG_NATIVE",
            "R11_MODEL_SELECT_VERIFY",
            "R11_FILE_SELECTED",
            "R11_FILE_CHOOSER_REQUEST",
            "R11_FILE_CHOOSER_SERVED",
            "R11_FILE_ACTIVATION_ARM_NATIVE",
            "R11_FILE_ACTIVATION_PULSE",
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
        ).forEach { assertTrue("Thiếu JS log $it", support.contains(it)) }
        listOf(
            "R11_REQUEST_FIX_INSTALLED",
            "R11_MODEL_SELECT_RESULT",
            "R11_GENERATE_MODEL_REWRITE",
            "R11_FILE_ACTIVATION_ARM",
            "R11_FILE_TRUSTED_ACTIVATION",
        ).forEach { assertTrue("Thiếu R11.1 log $it", fix.contains(it)) }
    }
}
