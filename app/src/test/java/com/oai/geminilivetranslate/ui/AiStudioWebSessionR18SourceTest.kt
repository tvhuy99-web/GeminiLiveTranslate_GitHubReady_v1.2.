package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR18SourceTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/oai/geminilivetranslate/$path"),
        File("app/src/main/java/com/oai/geminilivetranslate/$path"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source production: $path")

    private fun manifest(): String = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy AndroidManifest.xml")

    @Test
    fun apiSettingsExposeExactlyTheOfficialConnectionChoiceWithoutRemovingExistingSettings() {
        val activity = source("ui/ApiSettingsActivity.kt")
        val modeStore = source("core/AiConnectionModeStore.kt")
        assertTrue(modeStore.contains("MODE_API_KEY = \"api_key\""))
        assertTrue(modeStore.contains("MODE_AI_STUDIO = \"ai_studio\""))
        assertTrue(modeStore.contains("LABEL_API_KEY = \"API Key\""))
        assertTrue(modeStore.contains("LABEL_AI_STUDIO = \"Tài khoản Google / AI Studio\""))
        assertTrue(activity.contains("Chế độ kết nối Gemini Live"))
        assertTrue(activity.contains("ĐĂNG NHẬP / ĐĂNG XUẤT / CHUYỂN TÀI KHOẢN"))
        assertTrue(activity.contains("Gemini API Key"))
        assertTrue(activity.contains("Nhà cung cấp cho Mô tả video"))
        assertTrue(activity.contains("Kết nối và tự khôi phục"))
        assertTrue(activity.contains("Lời nhắc: Mô tả theo thời gian"))
        assertTrue(activity.contains("Lời nhắc: Mô tả tổng hợp"))
        assertTrue(activity.contains("AiFunctionModelCatalog.summary"))
    }

    @Test
    fun accountManagerKeepsAuthInsideWebViewAndLogsOnlyRouteMetadata() {
        val activity = source("ui/AiStudioAccountActivity.kt")
        assertTrue(activity.contains("CookieManager.getInstance()"))
        assertTrue(activity.contains("removeAllCookies"))
        assertTrue(activity.contains("WebStorage.getInstance().deleteAllData()"))
        assertTrue(activity.contains("aistudio.google.com/live"))
        assertTrue(activity.contains("AiStudioAccount"))
        assertFalse(activity.contains("getCookie("))
        assertFalse(activity.contains("Authorization"))
        assertFalse(activity.contains("access_token"))
        assertFalse(activity.contains("id_token"))
    }

    @Test
    fun liveFacadeUsesStrictSelectedModeAndNeverSilentlyCrossesBackends() {
        val facade = source("network/GeminiLiveClient.kt")
        val policy = source("core/AiStudioLiveBackendPolicy.kt")
        assertTrue(facade.contains("AiConnectionModeStore.MODE_AI_STUDIO"))
        assertTrue(facade.contains("CONNECT_AI_STUDIO"))
        assertTrue(facade.contains("CONNECT_API"))
        assertTrue(facade.contains("GEMINI_API_KEY_REQUIRED"))
        assertTrue(facade.contains("strictMode=true"))
        assertFalse(facade.contains("chuyển sang Gemini API fallback"))
        assertTrue(policy.contains("allowApiFallback(context: Context): Boolean = false"))
        assertTrue(policy.contains("AI_STUDIO_SENTINEL"))
    }

    @Test
    fun aiStudioSentinelIsScopedToLiveOperationsAndNeverBecomesANonLiveApiKey() {
        val keyStore = source("core/ApiKeyStore.kt")
        val service = source("service/TranslationService.kt")
        assertTrue(keyStore.contains("currentLiveCredential()"))
        assertTrue(keyStore.contains("selectedOperationUsesGeminiLive()"))
        assertTrue(keyStore.contains("PROCESSING_MODE_VIDEO_DESCRIPTION -> false"))
        assertTrue(keyStore.contains("PROCESSING_MODE_TRANSCRIBE"))
        assertTrue(keyStore.contains("selectedSource != SourceMode.FILE.name"))
        assertTrue(keyStore.contains("realGeminiKey(load())"))
        assertTrue(service.contains("keyStore.orderedGeminiKeys()"))
        assertTrue(service.contains("if (candidates.isEmpty()) error(\"Chưa có Gemini API Key\")"))
    }

    @Test
    fun functionSpecificDefaultModelsRemainSeparate() {
        val preferences = source("core/AppPreferences.kt")
        val catalog = source("core/AiFunctionModelCatalog.kt")
        val bootstrap = source("ui/AiStudioWebSessionR17ProductionBootstrap.kt")
        assertTrue(preferences.contains("gemini-3.5-live-translate-preview"))
        assertTrue(preferences.contains("gemini-3.5-transcribe"))
        assertTrue(preferences.contains("gemini-3.5-transcribe-live"))
        assertTrue(preferences.contains("gemini-3.5-flash-lite"))
        assertTrue(preferences.contains("gemini-3.7-flash"))
        assertTrue(catalog.contains("liveTranslate="))
        assertTrue(catalog.contains("liveTranscribe="))
        assertTrue(catalog.contains("fileTranscribe="))
        assertTrue(catalog.contains("subtitleTranslate="))
        assertTrue(catalog.contains("videoDescription="))
        val service = source("service/TranslationService.kt")
        val fileClient = source("network/AiStudioFileTranscribeClient.kt")
        assertTrue(service.contains("val useLiveFileTranscribe = false"))
        assertTrue(service.contains("FILE_TRANSCRIBE backend=aistudio-file"))
        assertTrue(fileClient.contains("AppPreferences.TRANSCRIBE_FILE_MODEL"))
        assertTrue(fileClient.contains("live=false"))
        assertTrue(bootstrap.contains("r17.11-start-before-camera-progress"))
        assertTrue(bootstrap.contains("TRANSLATE_MODEL='gemini-3.5-live-translate-preview'"))
        assertTrue(bootstrap.contains("TRANSCRIBE_MODEL='gemini-3.5-transcribe-live'"))
    }

    @Test
    fun productionAiStudioBootstrapMayAutomateTheHiddenUiAndHasDetailedState() {
        val bootstrap = source("ui/AiStudioWebSessionR17ProductionBootstrap.kt")
        assertTrue(bootstrap.contains("querySelectorAll('*')"))
        assertTrue(bootstrap.contains("typeof el.click==='function'"))
        assertTrue(bootstrap.contains("new w.MouseEvent('click'"))
        assertTrue(bootstrap.contains("startScans"))
        assertTrue(bootstrap.contains("startCandidates"))
        assertTrue(bootstrap.contains("startAttempts"))
        assertTrue(bootstrap.contains("START_ACK_TIMEOUT"))
        assertTrue(bootstrap.contains("waiting-start-ack"))
        assertTrue(bootstrap.contains("startProgressEvidence"))
        assertTrue(bootstrap.contains("progress.progress?12000:10000"))
        assertTrue(bootstrap.contains("modelVerified"))
        assertTrue(bootstrap.contains("targetLanguageVerified"))
        assertTrue(bootstrap.contains("setupObserved"))
        assertTrue(bootstrap.contains("/v1/bidiGenerateContent"))
        assertFalse(bootstrap.contains("SCREEN_SETUP_POSITIONAL"))
        assertFalse(bootstrap.contains("systemInstructionField:3"))
        assertFalse(bootstrap.contains("node[2]=instruction"))
        assertFalse(bootstrap.contains("Authorization"))
        assertFalse(bootstrap.contains("document.cookie"))
    }

    @Test
    fun hiddenLiveBackendUsesIsolatedWebViewAndBoundedVerifiedBootstrapRecovery() {
        val realtime = source("network/AiStudioWebRealtimeClient.kt")
        assertTrue(realtime.contains("isolatedLiveHost=true"))
        assertTrue(realtime.contains("val created = WebView(appContext)"))
        assertTrue(realtime.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(realtime.contains("AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START"))
        assertTrue(realtime.contains("AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START"))
        assertTrue(realtime.contains("bootstrapProbeScript"))
        assertTrue(realtime.contains("RECOVERY_BEGIN"))
        assertTrue(realtime.contains("RECOVERY_OK"))
        assertTrue(realtime.contains("RECOVERY_FAILED"))
        assertTrue(realtime.contains("R17_BOOTSTRAP_INSTALL_FAILED"))
        assertTrue(realtime.contains("MAX_BOOTSTRAP_RECOVERY_ATTEMPTS = 5"))
        assertTrue(realtime.contains("START_SESSION_RECOVERY"))
        assertTrue(realtime.contains("MAX_START_SESSION_RECOVERY_ATTEMPTS = 2"))
        assertTrue(realtime.contains("generation != pageGeneration"))
        assertFalse(realtime.contains("AiStudioWebSessionExecutor"))
        assertFalse(realtime.contains("AiStudioWebSessionLabScripts"))
        assertFalse(realtime.contains("AiStudioWebSessionAdaptiveRuntime"))
        assertFalse(realtime.contains("AiStudioWebSessionR11SubmitTargetFix"))
    }

    @Test
    fun screenDescriptionDesktopSharePathUsesDisplayMediaAndDisablesManualHeartbeat() {
        val nativeTap = source("network/AiStudioNativeTapDebugSupport.kt")
        val bootstrap = source("ui/AiStudioWebSessionR17ProductionBootstrap.kt")
        val screenBridge = source("ui/AiStudioWebSessionR19ScreenVideoBridge.kt")
        val desktopShare = source("ui/AiStudioWebSessionR21DesktopShareScreenExperiment.kt")
        val realtime = source("network/AiStudioWebRealtimeClient.kt")

        assertTrue(nativeTap.contains("r18.12-per-purpose-touch-debounce"))
        assertTrue(nativeTap.contains("lastTapAtByPurpose[purpose]"))
        assertTrue(nativeTap.contains("NATIVE_TAP_DEBOUNCE_MS = 1_200L"))
        assertFalse(nativeTap.contains("@Volatile private var lastTapAt = 0L"))
        assertTrue(nativeTap.contains("R26_DESKTOP_PROFILE_PRESERVED"))
        assertTrue(nativeTap.contains("desktopShareExperiment"))

        assertTrue(bootstrap.contains("r17.14-talk-then-native-share-screen"))
        assertTrue(bootstrap.contains("SCREEN_SETUP_PASSTHROUGH"))
        assertTrue(bootstrap.contains("page-owned-unmodified"))
        assertTrue(bootstrap.contains("return body;"))
        assertTrue(bootstrap.contains("talk-then-desktop-share-screen"))
        assertTrue(bootstrap.contains("SCREEN_LIVE_START"))
        assertTrue(bootstrap.contains("LIVE_SETUP_READY"))
        assertTrue(bootstrap.contains("SCREEN_SHARE_NATIVE_TAP_REQUEST"))
        assertTrue(bootstrap.contains("native-trusted-tap"))
        assertTrue(bootstrap.contains("shareDeferredUntilSetup:true"))

        assertTrue(screenBridge.contains("r19.15-desktop-share-screen-bridge"))
        assertTrue(screenBridge.contains("DESKTOP_SHARE_EXPERIMENT=true"))
        assertTrue(screenBridge.contains("DISPLAY_HOOK_INSTALLED"))
        assertTrue(screenBridge.contains("DISPLAY_REQUEST_ENTER"))
        assertTrue(screenBridge.contains("DISPLAY_STREAM_RETURNED"))
        assertTrue(screenBridge.contains("DISPLAY_PIPELINE_STATE"))
        assertTrue(screenBridge.contains("if(!md.getDisplayMedia||!md.getDisplayMedia.__aisR19ScreenVideo)"))
        assertTrue(screenBridge.contains("apiSynthesized"))
        assertTrue(screenBridge.contains("cameraAutomation:false"))

        assertTrue(desktopShare.contains("r21.2-desktop-identity-and-viewport"))
        assertTrue(desktopShare.contains("mobile:false"))
        assertTrue(desktopShare.contains("platform:'Linux'"))
        assertTrue(desktopShare.contains("DESKTOP_CSS_WIDTH=1280"))
        assertTrue(desktopShare.contains("meta[name=\"viewport\"]"))
        assertTrue(desktopShare.contains("maxTouchPoints"))
        assertTrue(desktopShare.contains("displaySurface:true"))
        assertTrue(desktopShare.contains("__AIS_DESKTOP_SHARE_EXPERIMENT__"))

        assertTrue(realtime.contains("DESKTOP_SHARE_USER_AGENT"))
        assertTrue(realtime.contains("WebSettingsCompat.setUserAgentMetadata"))
        assertTrue(realtime.contains("FORM_FACTOR_DESKTOP"))
        assertTrue(realtime.contains("AiStudioWebSessionR21DesktopShareScreenExperiment.DOCUMENT_START"))
        assertTrue(realtime.contains("AiStudioWebSessionR20ForensicDiagnostics.DOCUMENT_START"))
        assertTrue(realtime.contains(".configure(true,false)"))
        assertTrue(realtime.contains("WAITING_DESKTOP_SHARE_STREAM"))
        assertTrue(realtime.contains("displayStreamsReturned <= 0L"))
        assertTrue(realtime.contains("configureScreenHeartbeat(false"))
        assertFalse(realtime.contains("configureScreenHeartbeat(true"))
        assertFalse(realtime.contains("WAITING_SCREEN_CONTROL"))
        assertFalse(realtime.contains("instructionApplied="))

        val direct = source("ui/AiStudioWebSessionR14DirectLiveEngine.kt")
        val output = source("ui/AiStudioWebSessionR16LiveOutputEngine.kt")
        assertTrue(direct.contains("r14.5-postsetup-heartbeat"))
        assertTrue(direct.contains("realtime[4]=state.screenHeartbeatText"))
        assertTrue(direct.contains("SCREEN_HEARTBEAT_INJECTED"))
        assertTrue(direct.contains("screenTurnInFlight"))
        assertTrue(output.contains("r16.3-postsetup-heartbeat"))
        assertTrue(output.contains("notifyDirectSetupComplete()"))
    }

    @Test
    fun manifestContainsOnlyTheOfficialLauncherAndNoExperimentalActivity() {
        val manifest = manifest()
        assertTrue(manifest.contains(".ui.AiStudioAccountActivity"))
        assertTrue(manifest.contains(".MainActivity"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 1)
        assertFalse(manifest.contains(".ui.AiStudioWebSessionR"))
        assertFalse(manifest.contains(".ui.AiStudioWebSessionLabActivity"))
        assertFalse(manifest.contains("AI Studio - 1 chạm Start"))
    }
}
