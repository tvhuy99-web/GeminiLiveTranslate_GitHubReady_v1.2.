package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR18SourceTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/oai/geminilivetranslate/$path"),
        File("app/src/main/java/com/oai/geminilivetranslate/$path"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source R18: $path")

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
        assertTrue(probe.contains("stackCandidates"))
        assertTrue(probe.contains("causalWindow"))
        assertFalse(probe.contains("import android.view.MotionEvent"))
        assertFalse(probe.contains("dispatchTouchEvent("))
        assertFalse(probe.contains("MotionEvent.obtain("))
        assertFalse(probe.contains(".click()"))
        assertFalse(probe.contains("dispatchEvent(new MouseEvent"))
    }

    @Test
    fun r183LanguageGuardForcesVietnameseWithoutLanguageUi() {
        val guard = source("ui/AiStudioWebSessionR18LanguageGuard.kt")
        assertTrue(guard.contains("r18.3a-network-language-guard"))
        assertTrue(guard.contains("gemini-3.5-live-translate-preview"))
        assertTrue(guard.contains("targetLanguage:'vi'"))
        assertTrue(guard.contains("target_language_code"))
        assertTrue(guard.contains("targetLanguageCode"))
        assertTrue(guard.contains("/v1/bidiGenerateContent"))
        assertTrue(guard.contains("targetLanguageVerified"))
        assertTrue(guard.contains("lastFallbackPaths"))
        assertTrue(guard.contains("lastModelPaths"))
        assertFalse(guard.contains("querySelector("))
        assertFalse(guard.contains(".click()"))
        assertFalse(guard.contains("MotionEvent"))
        assertFalse(guard.contains("Authorization"))
        assertFalse(guard.contains("document.cookie"))
    }

    @Test
    fun r187LineageCaptureLearnsOnlyFromConfirmedSetupLineageAndContainsNoUiAutomation() {
        val bootstrap = source("ui/AiStudioWebSessionR18RuntimeBootstrap.kt")
        assertTrue(bootstrap.contains("r18.7-lineage-guided-runtime-capture"))
        assertTrue(bootstrap.contains("Function.prototype.toString.call"))
        assertTrue(bootstrap.contains("Object.getOwnPropertyDescriptors"))
        assertTrue(bootstrap.contains("structuredFrames"))
        assertTrue(bootstrap.contains("Error.prepareStackTrace"))
        assertTrue(bootstrap.contains("exact-click-root"))
        assertTrue(bootstrap.contains("argumentSnapshot"))
        assertTrue(bootstrap.contains("propertyIdentity"))
        assertTrue(bootstrap.contains("chainFor"))
        assertTrue(bootstrap.contains("analyzeLineage"))
        assertTrue(bootstrap.contains("distanceFromSetup"))
        assertTrue(bootstrap.contains("setupLinked"))
        assertTrue(bootstrap.contains("lineage-captured"))
        assertTrue(bootstrap.contains("disabled-r18.7-learning-only"))
        assertTrue(bootstrap.contains("Promise.prototype.then"))
        assertTrue(bootstrap.contains("queueMicrotask"))
        assertTrue(bootstrap.contains("scheduler.postTask"))
        assertTrue(bootstrap.contains("MessageChannel"))
        assertTrue(bootstrap.contains("ev.isTrusted===false"))
        assertTrue(bootstrap.contains("/v1/bidiGenerateContent"))
        assertTrue(bootstrap.contains("audio\\/pcm"))
        assertFalse(bootstrap.contains("document.querySelector("))
        assertFalse(bootstrap.contains("document.querySelectorAll("))
        assertFalse(bootstrap.contains(".click()"))
        assertFalse(bootstrap.contains("new MouseEvent"))
        assertFalse(bootstrap.contains("dispatchEvent("))
        assertFalse(bootstrap.contains("MotionEvent"))
        assertFalse(bootstrap.contains("getBoundingClientRect()"))
        assertFalse(bootstrap.contains("aria-label"))
        assertFalse(bootstrap.contains("data-testid"))
        assertFalse(bootstrap.contains("direct-setup-confirmed"))
        assertFalse(bootstrap.contains("direct-bidi-activity"))
    }

    @Test
    fun r184ProbeBindsExactOracleTargetToListenerGraphAndSetupFrames() {
        val probe = source("ui/AiStudioWebSessionR18StartOracleProbe.kt")
        assertTrue(probe.contains("r18.4-start-oracle-probe"))
        assertTrue(probe.contains("EventTarget.prototype.addEventListener"))
        assertTrue(probe.contains("EventTarget.prototype.removeEventListener"))
        assertTrue(probe.contains("markOracleTarget"))
        assertTrue(probe.contains("RELATED_LISTENER_INVOKED"))
        assertTrue(probe.contains("ORACLE_GRAPH"))
        assertTrue(probe.contains("Error.prepareStackTrace"))
        assertTrue(probe.contains("/v1/bidiGenerateContent"))
        assertTrue(probe.contains("gemini-3.5-live-translate-preview"))
        assertTrue(probe.contains("listenerFrameLinks"))
        assertTrue(probe.contains("graphFrameLinks"))
        assertTrue(probe.contains("sourceHash"))
        assertFalse(probe.contains(".click()"))
        assertFalse(probe.contains("new MouseEvent"))
        assertFalse(probe.contains("querySelector("))
        assertFalse(probe.contains("getBoundingClientRect"))
        assertFalse(probe.contains("Authorization"))
        assertFalse(probe.contains("document.cookie"))
        assertFalse(probe.contains("requestBody"))
    }

    @Test
    fun r185R174OracleIsExplicitlyLabOnlyAndCarriesAsyncCausalContext() {
        val oracle = source("ui/AiStudioWebSessionR18StartOracle.kt")
        assertTrue(oracle.contains("LAB_ONLY_UI_ORACLE"))
        assertTrue(oracle.contains("r18.5-r174-start-oracle-async-causal-lab"))
        assertTrue(oracle.contains("start|begin|connect|talk|speak|join"))
        assertTrue(oracle.contains("go live"))
        assertTrue(oracle.contains("start session"))
        assertTrue(oracle.contains("microphone"))
        assertTrue(oracle.contains("markOracleTarget"))
        assertTrue(oracle.contains("typeof el.click==='function'"))
        assertTrue(oracle.contains("new w.MouseEvent('click'"))
        assertTrue(oracle.contains("createMediaStreamDestination"))
        assertTrue(oracle.contains("getUserMedia"))
        assertTrue(oracle.contains("sampleRate:16000"))
        assertTrue(oracle.contains("Promise.prototype.then"))
        assertTrue(oracle.contains("queueMicrotask"))
        assertTrue(oracle.contains("scheduler.postTask"))
        assertTrue(oracle.contains("MessageChannel"))
        assertTrue(oracle.contains("setupLinked"))
        assertTrue(oracle.contains("contextChain"))
        assertTrue(oracle.contains("maxAttempts:3"))
        assertFalse(oracle.contains("getBoundingClientRect"))
        assertFalse(oracle.contains("MotionEvent"))
        assertFalse(oracle.contains("screenX"))
        assertFalse(oracle.contains("clientX"))
    }

    @Test
    fun r184ActivityArmsOracleLanguageAndR187WithoutContainingUiAutomationItself() {
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")
        val manifest = manifest()
        val log = source("core/AiStudioWebSessionLabLog.kt")
        assertTrue(activity.contains("r18.4-r174-oracle-causal-learning"))
        assertTrue(activity.contains("R18.4 - ORACLE R17.4 + HỌC ĐƯỜNG START"))
        assertTrue(activity.contains("AiStudioWebSessionR18StartOracleProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR18StartOracle.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR18RuntimeBootstrap.DOCUMENT_START"))
        assertTrue(activity.contains("window.__AIS_R184_START_ORACLE__"))
        assertTrue(activity.contains("o.start('vi')"))
        assertTrue(activity.contains("window.__AIS_R183_LANGUAGE__"))
        assertTrue(activity.contains("r184-r174-oracle-network-vi"))
        assertTrue(activity.contains("out.r183b=b&&b.reset?b.reset():null"))
        assertTrue(activity.contains("r18-bootstrap-state"))
        assertTrue(activity.contains("r18-language-state"))
        assertTrue(activity.contains("r18-causal-timeline"))
        assertFalse(activity.contains(".click()"))
        assertFalse(activity.contains("dispatchEvent("))
        assertFalse(activity.contains("import android.view.MotionEvent"))
        assertFalse(activity.contains("MotionEvent.obtain("))
        assertFalse(activity.contains("getBoundingClientRect("))
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR18Activity"))
        assertTrue(manifest.contains("AI Studio R18 - Bắt đường Live"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        assertTrue(log.contains("\"r18-bootstrap-state.txt\" -> -6"))
        assertTrue(log.contains("\"r18-language-state.txt\" -> -5"))
        assertTrue(log.contains("\"r18-final-summary.txt\" -> -4"))
        assertTrue(log.contains("\"r18-causal-timeline.txt\" -> -3"))
    }
}
