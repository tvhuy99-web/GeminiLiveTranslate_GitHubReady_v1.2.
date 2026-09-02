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
        assertFalse(support.contains("document.cookie"))
        assertFalse(support.contains("Authorization="))
        assertFalse(support.contains("X-Goog-Api-Key="))
        assertFalse(support.contains("password="))
    }

    @Test
    fun r11RequestFixStillProtectsTheProvenClosedRequestPath() {
        val fix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        assertTrue(fix.contains("2026-09-02-web-session-r11.3-attachment-submit"))
        assertTrue(fix.contains("R11_GENERATE_MODEL_REWRITE"))
        assertTrue(fix.contains("armTrustedFileChooser"))
        assertTrue(fix.contains("R11_FILE_TRUSTED_ACTIVATION"))
        assertTrue(fix.contains("R11_ATTACHMENT_FILE_CHANGE"))
        assertTrue(fix.contains("R11_ATTACHMENT_NET_REQUEST"))
        assertTrue(fix.contains("attachmentPresent()"))
        assertFalse(fix.contains("document.cookie"))
        assertFalse(fix.contains("Authorization="))
        assertFalse(fix.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r12ClosedRequestShellRemainsInternalForRegressionTesting() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11R2Activity.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(activity.contains("2026-09-02-web-session-r11.2-preflight-stable-attachment"))
        assertTrue(activity.contains("verifySelectedModelWithText"))
        assertTrue(activity.contains("R11_MODEL_PREFLIGHT_RESULT"))
        assertTrue(activity.contains("R11_ATTACHMENT_STABLE_READY"))
        assertTrue(activity.contains("R11_VIDEO_GENERATE_RECOVERY"))
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR11R2Activity"))
        val block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.AiStudioWebSessionR11R2Activity\\\"[\\s\\S]*?/>")
            .find(manifest)?.value.orEmpty()
        assertTrue(block.isNotEmpty())
        assertFalse(block.contains("LAUNCHER"))
    }

    @Test
    fun onlyCurrentR13ExperimentAndMainAppAreLaunchers() {
        val manifest = source("src/main/AndroidManifest.xml")
        val launcherCount = Regex("android.intent.category.LAUNCHER").findAll(manifest).count()
        assertTrue("Launcher count phải là 2 nhưng là $launcherCount", launcherCount == 2)
        assertTrue(manifest.contains("android:name=\".ui.AiStudioWebSessionR13Activity\""))
        assertTrue(manifest.contains("android:label=\"AI Studio Web Session R13.2 - Deep Live Probe\""))
        assertFalse(manifest.contains("AiStudioWebSessionR12LauncherActivity"))
        listOf(
            "AiStudioWebSessionR11R2Activity",
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
    fun r11R2LoggingStillHasEnoughMilestonesForClosedRequestDiagnosis() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11R2Activity.kt")
        listOf(
            "R11_ACTIVITY_CREATE",
            "R11_AUTH_PROBE_NATIVE",
            "R11_MODEL_CATALOG_NATIVE",
            "R11_MODEL_PREFLIGHT_START",
            "R11_MODEL_PREFLIGHT_RESULT",
            "R11_FILE_SELECTED",
            "R11_FILE_CHOOSER_SERVED",
            "R11_ATTACHMENT_STABLE_READY",
            "R11_CONTROLLER_REDISCOVERY_POLL",
            "R11_VIDEO_GENERATE_ATTEMPT",
            "R11_VIDEO_GENERATE_RECOVERY",
            "R11_E2E_RESULT",
        ).forEach { assertTrue("Thiếu log $it", activity.contains(it)) }
    }
}
