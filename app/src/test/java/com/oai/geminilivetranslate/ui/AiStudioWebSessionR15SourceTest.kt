package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR15SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun reusableWebLiveClientFramesRealPcmAndUsesBoundedQueuesWithoutOwningAuth() {
        val client = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebLiveClient.kt")
        assertTrue(client.contains("2026-09-03-r15-android-pcm-web-live-client"))
        assertTrue(client.contains("AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES"))
        assertTrue(client.contains("FRAME_BYTES"))
        assertTrue(client.contains("Base64.NO_WRAP"))
        assertTrue(client.contains("enqueuePcmBase64"))
        assertTrue(client.contains("MAX_LOCAL_QUEUE_FRAMES = 192"))
        assertTrue(client.contains("MAX_FRAMES_PER_JS_BATCH = 12"))
        assertTrue(client.contains("PUMP_DELAY_MS = 8L"))
        assertTrue(client.contains("BACKPRESSURED"))
        assertTrue(client.contains("framesDroppedLocally"))
        assertTrue(client.contains("framesDroppedByJs"))
        assertTrue(client.contains("flushPadded"))
        assertFalse(client.contains("document.cookie"))
        assertFalse(client.contains("Authorization="))
        assertFalse(client.contains("X-Goog-Api-Key="))
        assertFalse(client.contains("getAuthToken"))
        assertFalse(client.contains("CookieManager"))
    }

    @Test
    fun r15ActivityStreamsExistingFileAudioSourceContinuouslyThroughReusableClient() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR15Activity.kt")
        assertTrue(activity.contains("2026-09-03-web-session-r15.0-real-source-bridge"))
        assertTrue(activity.indexOf("installDocumentStartLayers()") < activity.indexOf("executor.start(AI_STUDIO_NEW_CHAT)"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR13DeepProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebLiveClient"))
        assertTrue(activity.contains("FileAudioSource"))
        assertTrue(activity.contains("onPcm16Mono16k"))
        assertTrue(activity.contains("liveClient.sendAudio(data)"))
        assertTrue(activity.contains("liveClient.sendAudioStreamEnd()"))
        assertTrue(activity.contains("Bắt đầu stream nguồn thật liên tục"))
        assertFalse(activity.contains("MAX_FILE_INJECT_MS"))
        assertFalse(activity.contains("MAX_FILE_FRAMES"))
        assertTrue(activity.contains("r15-final-summary"))
        assertTrue(activity.contains("R15 FINAL SUMMARY"))
        assertTrue(activity.contains("clientFramesAcceptedByJs"))
        assertTrue(activity.contains("injectedHttp2xx"))
        assertTrue(activity.contains("LEGACY_PROBE_SAMPLE_EVERY = 100"))
        assertFalse(activity.contains("req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))"))
        assertFalse(activity.contains("AccountManager"))
        assertFalse(activity.contains("getAuthToken"))
    }

    @Test
    fun r15IsExperimentLauncherWhileEarlierLiveStagesRemainInternal() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR15Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R15 - Real Source Bridge"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        listOf(
            "AiStudioWebSessionR14Activity",
            "AiStudioWebSessionR13Activity",
            "AiStudioWebSessionR11R2Activity",
        ).forEach { activityName ->
            val block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.$activityName\\\"[\\s\\S]*?/>")
                .find(manifest)?.value.orEmpty()
            assertTrue("Không tìm thấy block $activityName", block.isNotEmpty())
            assertFalse("$activityName không được là launcher", block.contains("LAUNCHER"))
        }
    }

    @Test
    fun r15FinalSummaryHasHighestPriorityInBoundedDiagnostics() {
        val log = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt")
        assertTrue(log.contains("\"r15-final-summary.txt\" -> 0"))
        assertTrue(log.indexOf("\"r15-final-summary.txt\" -> 0") < log.indexOf("\"events.log\" -> 4"))
    }
}
