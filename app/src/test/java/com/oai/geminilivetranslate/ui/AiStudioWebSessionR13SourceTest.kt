package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR13SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun liveProbeCoversModelMultimodalAndMultipleTransportLayersWithoutLoggingSecretsOrPayloads() {
        val probe = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLiveProbe.kt")
        assertTrue(probe.contains("2026-09-02-web-session-r13.1-live-transport-multimodal-probe"))
        assertTrue(probe.contains("gemini-3.1-flash-live-preview"))
        listOf(
            "WS_CREATE",
            "WS_OPEN",
            "WS_SEND",
            "WS_MESSAGE",
            "WEBSOCKET_STREAM_CREATE",
            "WEBTRANSPORT_CREATE",
            "FETCH_START",
            "XHR_START",
            "WORKER_CREATE",
            "SharedWorker",
            "SERVICE_WORKER_MESSAGE",
            "RTC_CREATE",
            "RTC_DATA_CHANNEL",
            "RTC_ADD_TRACK",
            "BEACON",
            "EVENTSOURCE_CREATE",
            "PerformanceObserver",
            "GET_USER_MEDIA",
            "GET_DISPLAY_MEDIA",
            "sessionResumptionUpdate",
            "serverContent",
            "realtimeInput",
            "generationConfigKeys",
            "responseModalities",
            "audioFramesOut",
            "videoFramesOut",
            "imageFramesOut",
            "audioFramesIn",
            "videoFramesIn",
            "targetObserved",
            "modelCandidates",
        ).forEach { assertTrue("Thiếu probe $it", probe.contains(it)) }
        assertTrue(probe.contains("return {scheme:String(u.protocol"))
        assertFalse(probe.contains("u.search"))
        assertFalse(probe.contains("u.hash"))
        assertFalse(probe.contains("document.cookie"))
        assertFalse(probe.contains("Authorization="))
        assertFalse(probe.contains("X-Goog-Api-Key="))
        assertFalse(probe.contains("localStorage"))
        assertFalse(probe.contains("sessionStorage"))
    }

    @Test
    fun r13ActivityTargetsGemini31FlashLiveAndKeepsFirstExperimentAudioOnly() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR13Activity.kt")
        assertTrue(activity.contains("2026-09-02-web-session-r13.1-live-transport-probe-activity"))
        assertTrue(activity.indexOf("installLiveProbe()") < activity.indexOf("executor.start(AI_STUDIO_NEW_CHAT)"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.TARGET_MODEL"))
        assertTrue(activity.contains("Gemini 3.1 Flash Live Preview"))
        assertTrue(activity.contains("PermissionRequest.RESOURCE_AUDIO_CAPTURE"))
        assertTrue(activity.contains("PermissionRequest.RESOURCE_VIDEO_CAPTURE"))
        assertTrue(activity.contains("asksVideo"))
        assertTrue(activity.contains("ActivityResultContracts.RequestPermission"))
        assertTrue(activity.contains("window.__AIS_LIVE_PROBE__.reset"))
        assertTrue(activity.contains("window.__AIS_LIVE_PROBE__.mark"))
        assertTrue(activity.contains("window.__AIS_LIVE_PROBE__.describe"))
        assertTrue(activity.contains("window.__AIS_LIVE_PROBE__.recent(220)"))
        assertTrue(activity.contains("targetObserved"))
        assertTrue(activity.contains("R13_PROBE_RECENT_NATIVE"))
        assertTrue(activity.contains("AiStudioWebSessionLogShareActivity"))
        // R13 observes video requests but deliberately never grants video capture yet.
        assertFalse(activity.contains("req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))"))
        assertFalse(activity.contains("AccountManager"))
        assertFalse(activity.contains("getAuthToken"))
    }

    @Test
    fun manifestExposesOnlyR13ProbeAsExperimentLauncher() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR13Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R13 - Live Probe"))
        assertFalse(manifest.contains("AiStudioWebSessionR12LauncherActivity"))
        assertFalse(manifest.contains("android.permission.GET_ACCOUNTS"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
    }
}
