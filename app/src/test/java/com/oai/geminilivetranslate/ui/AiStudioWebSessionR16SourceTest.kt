package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR16SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun outputEngineUnwrapsBrowserChannelAndExportsOnlyRecognizedLivePayloads() {
        val engine = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR16LiveOutputEngine.kt")
        assertTrue(engine.contains("2026-09-03-web-session-r16.0-browserchannel-output"))
        assertTrue(engine.contains("/v1/bidiGenerateContent"))
        assertTrue(engine.contains("/^(\\d+)\\n/"))
        assertTrue(engine.contains("AIStudioWebLiveOutput"))
        assertTrue(engine.contains("onAudioChunk"))
        assertTrue(engine.contains("onText"))
        assertTrue(engine.contains("onSignal"))
        assertTrue(engine.contains("serverContent"))
        assertTrue(engine.contains("inputTranscription"))
        assertTrue(engine.contains("interimInputTranscription"))
        assertTrue(engine.contains("outputTranscription"))
        assertTrue(engine.contains("turnComplete"))
        assertTrue(engine.contains("interrupted"))
        assertTrue(engine.contains("sessionResumptionUpdate"))
        assertTrue(engine.contains("goAway"))
        assertTrue(engine.contains("audio/pcm"))
        assertTrue(engine.contains("Never export newHandle"))
        assertFalse(engine.contains("document.cookie"))
        assertFalse(engine.contains("Authorization="))
        assertFalse(engine.contains("X-Goog-Api-Key="))
        assertFalse(engine.contains("localStorage"))
        assertFalse(engine.contains("sessionStorage"))
    }

    @Test
    fun privateOutputBridgeNeverLogsPayloadContents() {
        val bridge = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebLiveOutputBridge.kt")
        assertTrue(bridge.contains("2026-09-03-r16-private-live-output-bridge"))
        assertTrue(bridge.contains("@JavascriptInterface"))
        assertTrue(bridge.contains("AIStudioWebLiveOutput"))
        assertTrue(bridge.contains("Base64.decode"))
        assertTrue(bridge.contains("listener.onAudio(decoded, mime)"))
        assertTrue(bridge.contains("listener.onText(safeKind, value)"))
        assertTrue(bridge.contains("payloadChars=${encoded.length}"))
        assertFalse(bridge.contains("logger(\"R16_OUTPUT_AUDIO\", encoded"))
        assertFalse(bridge.contains("logger(\"R16_OUTPUT_TEXT\", value"))
        assertFalse(bridge.contains("document.cookie"))
        assertFalse(bridge.contains("Authorization="))
        assertFalse(bridge.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r16ActivityInstallsOutputDecoderBeforeLoadAndGatesStreamingOnLiveCarrier() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR16Activity.kt")
        assertTrue(activity.contains("2026-09-03-web-session-r16.0-bidirectional-live"))
        assertTrue(activity.indexOf("installDocumentStartLayers()") < activity.indexOf("executor.start(AI_STUDIO_NEW_CHAT)"))
        assertTrue(activity.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebLiveOutputBridge"))
        assertTrue(activity.contains("FileAudioSource"))
        assertTrue(activity.contains("templateObserved"))
        assertTrue(activity.contains("carrierRequests"))
        assertTrue(activity.contains("reason=live-carrier-not-ready"))
        assertTrue(activity.contains("r16-final-summary"))
        assertTrue(activity.contains("outputAudioChunks"))
        assertTrue(activity.contains("outputAudioBytes"))
        assertTrue(activity.contains("outputTextEvents"))
        assertTrue(activity.contains("outputEngineState"))
        assertFalse(activity.contains("req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))"))
        assertFalse(activity.contains("AccountManager"))
        assertFalse(activity.contains("getAuthToken"))
    }

    @Test
    fun r16IsExperimentLauncherAndSummaryHasHighestPriority() {
        val manifest = source("src/main/AndroidManifest.xml")
        val log = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR16Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R16 - Bidirectional Live"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        listOf("AiStudioWebSessionR15Activity", "AiStudioWebSessionR14Activity", "AiStudioWebSessionR13Activity").forEach { name ->
            val block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.$name\\\"[\\s\\S]*?/>").find(manifest)?.value.orEmpty()
            assertTrue("Không tìm thấy block $name", block.isNotEmpty())
            assertFalse("$name không được là launcher", block.contains("LAUNCHER"))
        }
        assertTrue(log.contains("\"r16-final-summary.txt\" -> 0"))
        assertTrue(log.indexOf("\"r16-final-summary.txt\" -> 0") < log.indexOf("\"events.log\" -> 5"))
    }
}
