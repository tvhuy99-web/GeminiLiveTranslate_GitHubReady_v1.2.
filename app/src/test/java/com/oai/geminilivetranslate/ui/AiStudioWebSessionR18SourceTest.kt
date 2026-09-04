package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR18SourceTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/oai/geminilivetranslate/$path"),
        File("app/src/main/java/com/oai/geminilivetranslate/$path"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source R18/R19: $path")

    private fun manifest(): String = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy AndroidManifest.xml")

    @Test
    fun r18CausalProbeRemainsObservational() {
        val probe = source("ui/AiStudioWebSessionR18CausalProbe.kt")
        assertTrue(probe.contains("R18.1/R18.2 observational probe"))
        assertTrue(probe.contains("TRUSTED_EVENT"))
        assertTrue(probe.contains("GET_USER_MEDIA_CALL"))
        assertTrue(probe.contains("BIDI_OPEN"))
        assertTrue(probe.contains("BIDI_SEND"))
        assertFalse(probe.contains("import android.view.MotionEvent"))
        assertFalse(probe.contains("MotionEvent.obtain("))
        assertFalse(probe.contains(".click()"))
    }

    @Test
    fun r183LanguageGuardForcesVietnameseWithoutLanguageUi() {
        val guard = source("ui/AiStudioWebSessionR18LanguageGuard.kt")
        assertTrue(guard.contains("r18.3a-network-language-guard"))
        assertTrue(guard.contains("gemini-3.5-live-translate-preview"))
        assertTrue(guard.contains("targetLanguage:'vi'"))
        assertTrue(guard.contains("/v1/bidiGenerateContent"))
        assertTrue(guard.contains("targetLanguageVerified"))
        assertFalse(guard.contains("querySelector("))
        assertFalse(guard.contains(".click()"))
        assertFalse(guard.contains("MotionEvent"))
        assertFalse(guard.contains("Authorization"))
        assertFalse(guard.contains("document.cookie"))
    }

    @Test
    fun r189ColdExperimentStaysZeroUiAndDoesNotHardcodeLearnedCallback() {
        val bootstrap = source("ui/AiStudioWebSessionR18RuntimeBootstrap.kt")
        assertTrue(bootstrap.contains("r18.9-cold-reload-bootstrap"))
        assertTrue(bootstrap.contains("coldMatchCount"))
        assertTrue(bootstrap.contains("coldReplayAttempts"))
        assertTrue(bootstrap.contains("coldR16SetupDelta"))
        assertTrue(bootstrap.contains("coldBootstrapProven"))
        assertTrue(bootstrap.contains("sessionStorage"))
        assertTrue(bootstrap.contains("ev.isTrusted===false"))
        assertFalse(bootstrap.contains("e062283a"))
        assertFalse(bootstrap.contains("573200da"))
        assertFalse(bootstrap.contains("document.querySelector("))
        assertFalse(bootstrap.contains("document.querySelectorAll("))
        assertFalse(bootstrap.contains(".click()"))
        assertFalse(bootstrap.contains("new MouseEvent"))
        assertFalse(bootstrap.contains("MotionEvent"))
        assertFalse(bootstrap.contains("getBoundingClientRect()"))
        assertFalse(bootstrap.contains("aria-label"))
        assertFalse(bootstrap.contains("data-testid"))
    }

    @Test
    fun r19SilentCarrierProvidesAudioOnlyStreamWithoutUiActivation() {
        val carrier = source("ui/AiStudioWebSessionPhysicalCarrier.kt")
        assertTrue(carrier.contains("r19-trusted-start-silent-carrier"))
        assertTrue(carrier.contains("navigator.mediaDevices"))
        assertTrue(carrier.contains("getUserMedia"))
        assertTrue(carrier.contains("createMediaStreamDestination"))
        assertTrue(carrier.contains("sampleRate:16000"))
        assertTrue(carrier.contains("gain.gain.value=0"))
        assertTrue(carrier.contains("__AIS_PHYSICAL_CARRIER__"))
        assertFalse(carrier.contains("querySelector("))
        assertFalse(carrier.contains("querySelectorAll("))
        assertFalse(carrier.contains(".click()"))
        assertFalse(carrier.contains("new MouseEvent"))
        assertFalse(carrier.contains("dispatchEvent("))
        assertFalse(carrier.contains("MotionEvent"))
        assertFalse(carrier.contains("getBoundingClientRect"))
        assertFalse(carrier.contains("aria-label"))
        assertFalse(carrier.contains("data-testid"))
    }

    @Test
    fun historicalOracleRemainsExplicitlyLabOnly() {
        val oracle = source("ui/AiStudioWebSessionR18StartOracle.kt")
        assertTrue(oracle.contains("LAB_ONLY_UI_ORACLE"))
        assertTrue(oracle.contains("r18.5-r174-start-oracle-async-causal-lab"))
        assertTrue(oracle.contains("markOracleTarget"))
        assertTrue(oracle.contains("createMediaStreamDestination"))
        assertTrue(oracle.contains("maxAttempts:3"))
    }

    @Test
    fun r19ActivityRequiresPhysicalStartThenHandsOffToR14AndR16() {
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")
        val manifest = manifest()

        assertTrue(activity.contains("r19-one-trusted-tap-physical-handoff"))
        assertTrue(activity.contains("CHẠM START TRỰC TIẾP"))
        assertTrue(activity.contains("AiStudioWebSessionPhysicalCarrier.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR18LanguageGuard.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebLiveClient"))
        assertTrue(activity.contains("AiStudioWebLiveOutputBridge"))
        assertTrue(activity.contains("MicAudioSource"))
        assertTrue(activity.contains("StreamingPcmPlayer"))
        assertTrue(activity.contains("inputClient.sendAudio(data)"))
        assertTrue(activity.contains("translatedPlayer?.enqueue(pcm24kMono)"))
        assertTrue(activity.contains("setupCompleteEvents"))
        assertTrue(activity.contains("targetLanguageVerified"))
        assertTrue(activity.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(activity.contains("lp.height = dp(2)"))
        assertTrue(activity.contains("executor.webView.alpha = 0f"))

        // The physical-handoff Activity must not contain or install any UI oracle/cold replay path.
        assertFalse(activity.contains("AiStudioWebSessionR18StartOracle.DOCUMENT_START"))
        assertFalse(activity.contains("AiStudioWebSessionR18StartOracleProbe.DOCUMENT_START"))
        assertFalse(activity.contains("AiStudioWebSessionR18RuntimeBootstrap.DOCUMENT_START"))
        assertFalse(activity.contains("window.__AIS_R184_START_ORACLE__"))
        assertFalse(activity.contains("document.querySelector("))
        assertFalse(activity.contains(".click()"))
        assertFalse(activity.contains("dispatchEvent("))
        assertFalse(activity.contains("import android.view.MotionEvent"))
        assertFalse(activity.contains("MotionEvent.obtain("))
        assertFalse(activity.contains("getBoundingClientRect("))

        assertTrue(manifest.contains(".ui.AiStudioWebSessionR18Activity"))
        assertTrue(manifest.contains("AI Studio - 1 chạm Start"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
    }
}
