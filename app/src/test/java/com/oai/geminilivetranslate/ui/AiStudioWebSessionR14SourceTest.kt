package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR14SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun directLiveEnginePiggybacksOnlyAudioPayloadAndCountsWebChannel2xxOnProgress() {
        val engine = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR14DirectLiveEngine.kt")
        assertTrue(engine.contains("2026-09-03-web-session-r14.2-progress-2xx"))
        assertTrue(engine.contains("/v1/bidiGenerateContent"))
        assertTrue(engine.contains("URLSearchParams"))
        assertTrue(engine.contains("req\\d+___data__"))
        assertTrue(engine.contains("audio\\/pcm"))
        assertTrue(engine.contains("enqueuePcmBase64"))
        assertTrue(engine.contains("AUDIO_TEMPLATE_CAPTURED"))
        assertTrue(engine.contains("AUDIO_REPLACED"))
        assertTrue(engine.contains("readystatechange"))
        assertTrue(engine.contains("xhr.readyState>=2"))
        assertTrue(engine.contains("INJECT_HTTP_2XX"))
        assertTrue(engine.contains("INJECT_HTTP_ERROR"))
        assertTrue(engine.contains("INJECT_ZERO_STATUS_END"))
        assertTrue(engine.contains("injectedZeroStatusEnd"))
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
    fun r14ActivityRemainsARegressionHarnessWithBoundedDiagnostics() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR14Activity.kt")
        assertTrue(activity.contains("2026-09-03-web-session-r14.1-diagnostics-proof"))
        assertTrue(activity.indexOf("installDocumentStartLayers()") < activity.indexOf("executor.start(AI_STUDIO_NEW_CHAT)"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR13DeepProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(activity.contains("FileAudioSource"))
        assertTrue(activity.contains("Tiêm tone PCM 440 Hz"))
        assertTrue(activity.contains("Tiêm tối đa 8 giây từ tệp vào Live"))
        assertTrue(activity.contains("LEGACY_PROBE_SAMPLE_EVERY = 100"))
        assertTrue(activity.contains("r14-final-summary"))
        assertTrue(activity.contains("R14_FINAL_SUMMARY"))
        assertFalse(activity.contains("req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))"))
        assertFalse(activity.contains("AccountManager"))
        assertFalse(activity.contains("getAuthToken"))
    }

    @Test
    fun r16R15R14SummariesArePrioritizedBeforeEventsLog() {
        val log = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt")
        val r16 = log.indexOf("\"r16-final-summary.txt\" -> 0")
        val r15 = log.indexOf("\"r15-final-summary.txt\" -> 1")
        val r14 = log.indexOf("\"r14-final-summary.txt\" -> 2")
        val events = log.indexOf("\"events.log\" -> 5")
        assertTrue(r16 >= 0)
        assertTrue(r15 > r16)
        assertTrue(r14 > r15)
        assertTrue(events > r14)
        assertTrue(log.contains("MAX_REPORT_CHARS = 600_000"))
    }

    @Test
    fun manifestKeepsR14InternalAfterR16TakesExperimentLauncher() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR16Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R16 - Bidirectional Live"))
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR14Activity"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        val r14Block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.AiStudioWebSessionR14Activity\\\"[\\s\\S]*?/>")
            .find(manifest)?.value.orEmpty()
        assertTrue(r14Block.isNotEmpty())
        assertFalse(r14Block.contains("LAUNCHER"))
        assertFalse(manifest.contains("android.permission.GET_ACCOUNTS"))
    }
}
