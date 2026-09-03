package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR17ProductionSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun hiddenBootstrapAutomatesStreamWithoutOwningSecretsOrPhysicalMic() {
        val js = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt")
        assertTrue(js.contains("2026-09-03-web-session-r17.0-hidden-production-bootstrap"))
        assertTrue(js.contains("gemini-3.1-flash-live-preview"))
        assertTrue(js.contains("createMediaStreamDestination"))
        assertTrue(js.contains("getUserMedia-synthetic"))
        assertTrue(js.contains("setCarrierActive"))
        assertTrue(js.contains("AudioDestinationNode"))
        assertTrue(js.contains("webaudio-output-mute"))
        assertTrue(js.contains("select-stream"))
        assertTrue(js.contains("start-live"))
        assertTrue(js.contains("system instructions"))
        assertTrue(js.contains("simultaneous interpreter"))
        assertFalse(js.contains("document.cookie"))
        assertFalse(js.contains("localStorage"))
        assertFalse(js.contains("sessionStorage"))
        assertFalse(js.contains("Authorization="))
        assertFalse(js.contains("X-Goog-Api-Key="))
    }

    @Test
    fun hiddenRealtimeClientReusesProvenR14R16AndMapsProductionCallbacks() {
        val client = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")
        assertTrue(client.contains("2026-09-03-r17-hidden-bidirectional-web-live-client"))
        assertTrue(client.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(client.contains("AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START"))
        assertTrue(client.contains("AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START"))
        assertTrue(client.contains("AiStudioWebLiveOutputBridge"))
        assertTrue(client.contains("listener.onAudio(pcm24kMono)"))
        assertTrue(client.contains("listener.onInputTranscript(text)"))
        assertTrue(client.contains("listener.onText(text)"))
        assertTrue(client.contains("listener.onTurnComplete()"))
        assertTrue(client.contains("listener.onInterrupted()"))
        assertTrue(client.contains("listener.onGoAway"))
        assertTrue(client.contains("listener.onSessionResumptionUpdate"))
        assertTrue(client.contains("templateObserved"))
        assertTrue(client.contains("carrierRequests"))
        assertTrue(client.contains("INPUT_IDLE_TO_SILENCE_MS"))
        assertFalse(client.contains("req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))"))
        assertFalse(client.contains("document.cookie"))
        assertFalse(client.contains("Authorization="))
    }

    @Test
    fun facadePrefersAiStudioAndPreservesApiFallbackWithoutApiKeyRequirement() {
        val facade = source("src/main/java/com/oai/geminilivetranslate/network/GeminiLiveClient.kt")
        val api = source("src/main/java/com/oai/geminilivetranslate/network/GeminiApiLiveClient.kt")
        val policy = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioLiveBackendPolicy.kt")
        val keys = source("src/main/java/com/oai/geminilivetranslate/core/ApiKeyStore.kt")
        assertTrue(facade.contains("2026-09-03-r17-realtime-session-facade"))
        assertTrue(facade.contains("connectAiStudio()"))
        assertTrue(facade.contains("connectApi()"))
        assertTrue(facade.contains("recordAiStudioFailure"))
        assertTrue(facade.contains("AI Studio chưa setup được"))
        assertTrue(api.contains("2026-09-03-r17-gemini-api-live-fallback"))
        assertTrue(policy.contains("__AI_STUDIO_WEB_SESSION__"))
        assertTrue(policy.contains("DEFAULT_ENABLED = true"))
        assertTrue(policy.contains("CIRCUIT_BREAKER_MS"))
        assertTrue(keys.contains("AiStudioLiveBackendPolicy.liveCredential(real)"))
        assertTrue(keys.contains("fun orderedGeminiKeys()"))
        assertFalse(policy.contains("document.cookie"))
    }

    @Test
    fun commonContractExistsAndExistingServicePipelineRemainsTheIntegrationPoint() {
        val contract = source("src/main/java/com/oai/geminilivetranslate/network/RealtimeSessionClient.kt")
        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")
        val player = source("src/main/java/com/oai/geminilivetranslate/audio/StreamingPcmPlayer.kt")
        assertTrue(contract.contains("interface RealtimeSessionClient"))
        assertTrue(contract.contains("sendAudio(pcm16kMono"))
        assertTrue(contract.contains("sendAudioStreamEnd"))
        assertTrue(service.contains("private var aiPlayer: StreamingPcmPlayer?"))
        assertTrue(service.contains("if (settings.aiVoice) aiPlayer?.enqueue(pcm24kMono)"))
        assertTrue(service.contains("appendTranslation(text, event)"))
        assertTrue(service.contains("appendTranscriptionSegment(text)"))
        assertTrue(service.contains("scheduleReconnect"))
        assertTrue(service.contains("onGoAway"))
        assertTrue(service.contains("onSessionResumptionUpdate"))
        assertTrue(player.contains("setSampleRate(sampleRate)"))
    }

    @Test
    fun noNewR17UserInterfaceOrLauncherWasAdded() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("AiStudioWebSessionR17Activity"))
        assertFalse(manifest.contains("R17 - Production"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        assertTrue(manifest.contains("AiStudioWebSessionR16Activity"))
    }
}
