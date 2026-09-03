package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR17ProductionSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun bootstrapKeepsFunctionModelsAndOwnsNoSecretsOrPhysicalMic() {
        val js = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt")
        assertTrue(js.contains("2026-09-03-web-session-r17.4-progress-model-bootstrap"))
        assertTrue(js.contains("gemini-3.5-live-translate-preview"))
        assertTrue(js.contains("gemini-3.5-transcribe-live"))
        assertFalse(js.contains("const val TARGET_MODEL = \"gemini-3.1-flash-live-preview\""))
        assertTrue(js.contains("state.targetModel=state.transcribeOnly?TRANSCRIBE_MODEL:TRANSLATE_MODEL"))
        assertTrue(js.contains("FUNCTION_MODEL"))
        assertTrue(js.contains("createMediaStreamDestination"))
        assertTrue(js.contains("getUserMedia-synthetic"))
        assertTrue(js.contains("setCarrierActive"))
        assertTrue(js.contains("AudioDestinationNode"))
        assertTrue(js.contains("webaudio-output-mute"))
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
    fun r174UnderstandsRouteModelWaitsForMenuAndVerifiesSetupModelPageLocally() {
        val js = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt")
        assertTrue(js.contains("el.shadowRoot"))
        assertTrue(js.contains("el.contentDocument"))
        assertTrue(js.contains("interactiveControls"))
        assertTrue(js.contains("shadowRoots"))
        assertTrue(js.contains("frameDocuments"))
        assertTrue(js.contains("routeHasTargetModel"))
        assertTrue(js.contains("MODEL_ROUTE_REQUESTED"))
        assertTrue(js.contains("waiting-model-menu-render"))
        assertTrue(js.contains("modelSearchAttempts"))
        assertTrue(js.contains("clickableAncestor"))
        assertTrue(js.contains("bidi-model-guard"))
        assertTrue(js.contains("/v1/bidiGenerateContent"))
        assertTrue(js.contains("MODEL_REQUEST_GUARD"))
        assertTrue(js.contains("modelGuardRequests"))
        assertTrue(js.contains("modelRewriteRequests"))
        assertTrue(js.contains("lastBlocker"))
        assertTrue(js.contains("DISCOVERY"))
        assertFalse(js.contains("responseText"))
        assertFalse(js.contains("requestBody"))
        assertFalse(js.contains("audioPayload"))
    }

    @Test
    fun r174StartsOnDedicatedLiveRouteAndUsesProgressAwareTimeout() {
        val client = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")
        assertTrue(client.contains("2026-09-03-r17.4-visible-progress-bootstrap"))
        assertTrue(client.contains("https://aistudio.google.com/live"))
        assertTrue(client.contains("created.start(liveUrl)"))
        assertTrue(client.contains("liveRouteUrl()"))
        assertTrue(client.contains("targetLiveModel()"))
        assertTrue(client.contains("Uri.encode(targetLiveModel())"))
        assertTrue(client.contains("repairLiveRouteIfNeeded"))
        assertTrue(client.contains("ROUTE_REPAIR_GRACE_MS"))
        assertTrue(client.contains("MAX_ROUTE_REPAIR_ATTEMPTS"))
        assertTrue(client.contains("lastBootstrapProgressAt"))
        assertTrue(client.contains("updateBootstrapProgress"))
        assertTrue(client.contains("SETUP_STALL_TIMEOUT_MS"))
        assertTrue(client.contains("SETUP_HARD_TIMEOUT_MS"))
        assertTrue(client.contains("AI_STUDIO_LIVE_SETUP_STALLED"))
        assertFalse(client.contains("https://aistudio.google.com/prompts/new_chat"))
    }

    @Test
    fun r174ShowsTheExactProductionWebViewWithoutAddingAnotherLauncher() {
        val client = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")
        val surface = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioLiveDebugSurface.kt")
        val app = source("src/main/java/com/oai/geminilivetranslate/GeminiTranslateApp.kt")
        val manifest = source("src/main/AndroidManifest.xml")

        assertTrue(client.contains("AiStudioLiveDebugSurface.show("))
        assertTrue(client.contains("created.webView"))
        assertTrue(client.contains("AiStudioLiveDebugSurface.detach(it)"))
        assertTrue(surface.contains("2026-09-03-r17.4-visible-production-webview"))
        assertTrue(surface.contains("const val ENABLED = true"))
        assertTrue(surface.contains("currentWebView = WeakReference(webView)"))
        assertTrue(surface.contains("Ẩn Web"))
        assertTrue(surface.contains("Hiện Web"))
        assertTrue(surface.contains("MAIN_ACTIVITY"))
        assertTrue(app.contains("AiStudioLiveDebugSurface.install(this)"))
        assertFalse(manifest.contains("AiStudioWebSessionR17Activity"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
    }

    @Test
    fun applicationDefaultsRemainFunctionSpecific() {
        val prefs = source("src/main/java/com/oai/geminilivetranslate/core/AppPreferences.kt")
        val fileTranscriber = source("src/main/java/com/oai/geminilivetranslate/network/GeminiFileTranscribeClient.kt")
        val subtitleTranslator = source("src/main/java/com/oai/geminilivetranslate/network/SubtitleTranslationClient.kt")
        val videoClient = source("src/main/java/com/oai/geminilivetranslate/network/GeminiVideoDescriptionClient.kt")
        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")

        assertTrue(prefs.contains("DEFAULT_MODEL = \"gemini-3.5-live-translate-preview\""))
        assertTrue(prefs.contains("TRANSCRIBE_LIVE_MODEL = \"gemini-3.5-transcribe-live\""))
        assertTrue(prefs.contains("TRANSCRIBE_FILE_MODEL = \"gemini-3.5-transcribe\""))
        assertTrue(prefs.contains("SUBTITLE_TRANSLATE_MODEL = \"gemini-3.5-flash-lite\""))
        assertTrue(prefs.contains("VIDEO_DESCRIPTION_MODEL = \"gemini-3.7-flash\""))
        assertTrue(fileTranscriber.contains("private const val MODEL = \"gemini-3.5-transcribe\""))
        assertTrue(subtitleTranslator.contains("AppPreferences.SUBTITLE_TRANSLATE_MODEL"))
        assertTrue(videoClient.contains("model: String = AppPreferences.VIDEO_DESCRIPTION_MODEL"))
        assertTrue(service.contains("isTranscribeMode() && currentMode == SourceMode.FILE -> AppPreferences.TRANSCRIBE_FILE_MODEL"))
        assertTrue(service.contains("isTranscribeMode() -> AppPreferences.TRANSCRIBE_LIVE_MODEL"))
        assertTrue(service.contains("else -> settings.model"))
    }

    @Test
    fun realtimeClientStillReusesProvenR14R16AndMapsProductionCallbacks() {
        val client = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt")
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
    fun facadePrefersAiStudioAndPreservesApiFallbackWithoutLockingWebOnlyDevices() {
        val facade = source("src/main/java/com/oai/geminilivetranslate/network/GeminiLiveClient.kt")
        val api = source("src/main/java/com/oai/geminilivetranslate/network/GeminiApiLiveClient.kt")
        val policy = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioLiveBackendPolicy.kt")
        val keys = source("src/main/java/com/oai/geminilivetranslate/core/ApiKeyStore.kt")
        assertTrue(facade.contains("2026-09-03-r17.3-realtime-session-facade"))
        assertTrue(facade.contains("connectAiStudio()"))
        assertTrue(facade.contains("connectApi()"))
        assertTrue(facade.contains("(!hasRealApiKey && configuredForWeb)"))
        assertTrue(facade.contains("recordAiStudioFailure(hasApiFallback = canFallback)"))
        assertTrue(facade.contains("backend Live duy nhất; giữ quyền thử lại qua reconnect/backoff"))
        assertTrue(api.contains("2026-09-03-r17-gemini-api-live-fallback"))
        assertTrue(policy.contains("2026-09-03-r17.3-web-only-reconnect-policy"))
        assertTrue(policy.contains("recordAiStudioFailure(hasApiFallback: Boolean)"))
        assertTrue(policy.contains("if (hasApiFallback)"))
        assertTrue(policy.contains("disabledUntilElapsed.set(0L)"))
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
}
