package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR14SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun directLiveEnginePiggybacksOnlyAudioPayloadOnExistingWebChannel() {
        val engine = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR14DirectLiveEngine.kt")
        assertTrue(engine.contains("2026-09-02-web-session-r14.0-direct-live-audio-piggyback"))
        assertTrue(engine.contains("/v1/bidiGenerateContent"))
        assertTrue(engine.contains("URLSearchParams"))
        assertTrue(engine.contains("req\\d+___data__"))
        assertTrue(engine.contains("audio\\/pcm"))
        assertTrue(engine.contains("enqueuePcmBase64"))
        assertTrue(engine.contains("AUDIO_TEMPLATE_CAPTURED"))
        assertTrue(engine.contains("AUDIO_REPLACED"))
        assertTrue(engine.contains("INJECT_RESULT"))
        assertTrue(engine.contains("FRAME_BYTES = 1_280"))
        assertTrue(engine.contains("FRAME_MS = 40"))
        assertFalse(engine.contains("document.cookie"))
        assertFalse(engine.contains("localStorage"))
        assertFalse(engine.contains("sessionStorage"))
        assertFalse(engine.contains("getAuthToken"))
        assertFalse(engine.contains("Authorization="))
        assertFalse(engine.contains("X-Goog-Api-Key="))
        assertFalse(engine.contains("responseText"))
    }

    @Test
    fun r14ActivityKeepsDirectEngineAndBoundsLegacyProbeDiagnostics() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR14Activity.kt")
        assertTrue(activity.contains("2026-09-03-web-session-r14.1-diagnostics-proof"))
        assertTrue(activity.indexOf("installDocumentStartLayers()") < activity.indexOf("executor.start(AI_STUDIO_NEW_CHAT)"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR13DeepProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(activity.contains("Gemini 3.1 Flash Live Preview"))
        assertTrue(activity.contains("FileAudioSource"))
        assertTrue(activity.contains("Base64.NO_WRAP"))
        assertTrue(activity.contains("FRAME_BYTES"))
        assertTrue(activity.contains("Tiêm tone PCM 440 Hz"))
        assertTrue(activity.contains("Tiêm tối đa 8 giây từ tệp vào Live"))
        assertTrue(activity.contains("window.__AIS_LIVE_DIRECT_ENGINE__.enqueuePcmBase64"))
        assertTrue(activity.contains("window.__AIS_LIVE_DIRECT_ENGINE__.arm"))
        assertTrue(activity.contains("window.__AIS_LIVE_DIRECT_ENGINE__.describe"))
        assertTrue(activity.contains("LEGACY_PROBE_SAMPLE_EVERY = 100"))
        assertTrue(activity.contains("LEGACY_PROBE_INITIAL_KEEP = 3"))
        assertTrue(activity.contains("name.startsWith(\"JS_R132_BIDI_\")"))
        assertTrue(activity.contains("name.startsWith(\"JS_R13_XHR_\")"))
        assertTrue(activity.contains("r14-final-summary"))
        assertTrue(activity.contains("R14_FINAL_SUMMARY"))
        assertTrue(activity.contains("replacedFrames"))
        assertTrue(activity.contains("injectedHttp2xx"))
        assertTrue(activity.contains("shareLogsWithFinalSummary"))
        assertTrue(activity.contains("recent(12)"))
        assertFalse(activity.contains("recent(120)"))
        assertFalse(activity.contains("req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))"))
        assertFalse(activity.contains("AccountManager"))
        assertFalse(activity.contains("getAuthToken"))
    }

    @Test
    fun r14FinalSummaryIsPrioritizedBeforeEventsLogInTextDiagnostics() {
        val log = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt")
        val summary = log.indexOf("\"r14-final-summary.txt\" -> 0")
        val events = log.indexOf("\"events.log\" -> 3")
        assertTrue(summary >= 0)
        assertTrue(events > summary)
        assertTrue(log.contains("MAX_REPORT_CHARS = 600_000"))
    }

    @Test
    fun manifestExposesOnlyR14ExperimentAndMainAppAsLaunchers() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR14Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R14 - Direct Live Engine"))
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR13Activity"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        val r13Block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.AiStudioWebSessionR13Activity\\\"[\\s\\S]*?/>")
            .find(manifest)?.value.orEmpty()
        assertTrue(r13Block.isNotEmpty())
        assertFalse(r13Block.contains("LAUNCHER"))
        assertFalse(manifest.contains("android.permission.GET_ACCOUNTS"))
    }
}
