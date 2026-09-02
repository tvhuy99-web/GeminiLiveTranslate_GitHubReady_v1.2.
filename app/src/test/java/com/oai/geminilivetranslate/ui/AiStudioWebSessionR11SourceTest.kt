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
    fun r11RequestFixHandlesModelTrustedFileAndAttachmentSubmit() {
        val fix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        assertTrue(fix.contains("2026-09-02-web-session-r11.3-attachment-submit"))
        assertTrue(fix.contains("R11_GENERATE_MODEL_REWRITE"))
        assertTrue(fix.contains("body.split(original).join(fix.selectedModel)"))
        assertTrue(fix.contains("path:'request-layer'"))
        assertTrue(fix.contains("armTrustedFileChooser"))
        assertTrue(fix.contains("ev.isTrusted !== true"))
        assertTrue(fix.contains("R11_FILE_TRUSTED_ACTIVATION"))
        assertTrue(fix.contains("R11_ATTACHMENT_FILE_CHANGE"))
        assertTrue(fix.contains("R11_ATTACHMENT_FILE_READ"))
        assertTrue(fix.contains("R11_ATTACHMENT_NET_REQUEST"))
        assertTrue(fix.contains("R11_ATTACHMENT_SEND_CANDIDATES"))
        assertTrue(fix.contains("R11_ATTACHMENT_SEND_CLICK"))
        assertTrue(fix.contains("R11_ATTACHMENT_SEND_LISTENER_FALLBACK"))
        assertTrue(fix.contains("R11_ATTACHMENT_SEND_RESULT"))
        assertTrue(fix.contains("R11_ATTACHMENT_GENERATE_FALLBACK_START"))
        assertTrue(fix.contains("attachmentPresent()"))
        assertTrue(fix.contains("runtime.generate=wrapped"))
        assertTrue(fix.contains("HTMLElement.prototype.click.call(best.button)"))
        assertTrue(fix.contains("clickEntries"))
        assertFalse(fix.contains("document.cookie"))
        assertFalse(fix.contains("Authorization="))
        assertFalse(fix.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r12UsesTheProvenR11R2ShellForAuthModelFileAndStableAttachment() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11R2Activity.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(activity.contains("2026-09-02-web-session-r11.2-preflight-stable-attachment"))
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR11R2Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R12 - Direct Engine"))
        assertTrue(activity.contains("verifySelectedModelWithText"))
        assertTrue(activity.contains("MODEL_PREFLIGHT_MARKER"))
        assertTrue(activity.contains("R11_MODEL_PREFLIGHT_START"))
        assertTrue(activity.contains("R11_MODEL_PREFLIGHT_RESULT"))
        assertTrue(activity.contains("rewriteCount > baselineRewrite"))
        assertTrue(activity.contains("pollAttachmentStrict"))
        assertTrue(activity.contains("attachmentBusyVisual"))
        assertTrue(activity.contains("REQUIRED_STABLE_POLLS"))
        assertTrue(activity.contains("MIN_ATTACHMENT_STABLE_MS"))
        assertTrue(activity.contains("R11_ATTACHMENT_STRICT_POLL"))
        assertTrue(activity.contains("R11_ATTACHMENT_STABLE_READY"))
        assertTrue(activity.contains("waitForControllerReady"))
        assertTrue(activity.contains("R11_CONTROLLER_REDISCOVERY_POLL"))
        assertTrue(activity.contains("R11_ATTACHMENT_CONTROLLER_RESULT"))
        assertTrue(activity.contains("R11_VIDEO_GENERATE_RECOVERY"))
        assertTrue(activity.contains("NO_HANDLER_TRIGGERED_REQUEST"))
        assertTrue(activity.contains("filePathCallback.onReceiveValue(arrayOf(selected.uri))"))
        assertTrue(activity.contains("InputDevice.SOURCE_TOUCHSCREEN"))
        assertTrue(activity.contains("Hãy xem toàn bộ video này và tóm tắt chi tiết"))
        assertFalse(activity.contains("document.cookie"))
        assertFalse(activity.contains("Gemini API Key"))
        assertFalse(activity.contains("Mật khẩu:"))
    }

    @Test
    fun onlyCurrentR12ExperimentRemainsOnLauncher() {
        val manifest = source("src/main/AndroidManifest.xml")
        val launcherCount = Regex("android.intent.category.LAUNCHER").findAll(manifest).count()
        assertTrue("Launcher count phải là 2 nhưng là $launcherCount", launcherCount == 2)
        assertTrue(manifest.contains("android:name=\".ui.AiStudioWebSessionR11R2Activity\""))
        assertTrue(manifest.contains("android:label=\"AI Studio Web Session R12 - Direct Engine\""))
        listOf(
            "AiStudioWebSessionR11UnifiedActivity",
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
    fun r11R2AndR11R3LoggingHasEnoughMilestonesForDeviceDiagnosis() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11R2Activity.kt")
        val support = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11Support.kt")
        val fix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        listOf(
            "R11_ACTIVITY_CREATE",
            "R11_AUTH_PROBE_NATIVE",
            "R11_MODEL_CATALOG_NATIVE",
            "R11_MODEL_SELECT_VERIFY",
            "R11_MODEL_PREFLIGHT_START",
            "R11_MODEL_PREFLIGHT_RESULT",
            "R11_FILE_SELECTED",
            "R11_FILE_CHOOSER_REQUEST",
            "R11_FILE_CHOOSER_SERVED",
            "R11_FILE_ACTIVATION_ARM_NATIVE",
            "R11_FILE_ACTIVATION_PULSE",
            "R11_ATTACH_NATIVE_START",
            "R11_ATTACHMENT_STRICT_POLL",
            "R11_ATTACHMENT_STABLE_READY",
            "R11_CONTROLLER_REDISCOVERY_POLL",
            "R11_ATTACHMENT_CONTROLLER_RESULT",
            "R11_VIDEO_GENERATE_ATTEMPT",
            "R11_VIDEO_GENERATE_RECOVERY",
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
            "R11_ATTACHMENT_OBSERVATION_START",
            "R11_ATTACHMENT_FILE_CHANGE",
            "R11_ATTACHMENT_FILE_READ",
            "R11_ATTACHMENT_NET_REQUEST",
            "R11_ATTACHMENT_NET_RESULT",
            "R11_ATTACHMENT_GENERATE_ARMED",
            "R11_ATTACHMENT_GENERATE_FALLBACK_START",
            "R11_ATTACHMENT_SEND_CANDIDATES",
            "R11_ATTACHMENT_SEND_CLICK",
            "R11_ATTACHMENT_SEND_RESULT",
        ).forEach { assertTrue("Thiếu R11.3 log $it", fix.contains(it)) }
    }
}
