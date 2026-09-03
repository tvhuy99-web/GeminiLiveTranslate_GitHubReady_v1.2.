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
    fun r18CapturesCausalLiveBootstrapWithoutUiAutomation() {
        val probe = source("ui/AiStudioWebSessionR18CausalProbe.kt")
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")

        assertTrue(probe.contains("R18.1/R18.2 observational probe"))
        assertTrue(probe.contains("TRUSTED_EVENT"))
        assertTrue(probe.contains("GET_USER_MEDIA_CALL"))
        assertTrue(probe.contains("AUDIO_CONTEXT_RESUME"))
        assertTrue(probe.contains("BIDI_OPEN"))
        assertTrue(probe.contains("BIDI_SEND"))
        assertTrue(probe.contains("stackCandidates"))
        assertTrue(probe.contains("recurringFrames"))
        assertTrue(probe.contains("causalWindow"))
        assertTrue(probe.contains("startCapture"))
        assertTrue(probe.contains("stopCapture"))

        assertFalse(probe.contains("import android.view.MotionEvent"))
        assertFalse(probe.contains("dispatchTouchEvent("))
        assertFalse(probe.contains("MotionEvent.obtain("))
        assertFalse(probe.contains(".click()"))
        assertFalse(probe.contains("dispatchEvent(new MouseEvent"))
        assertFalse(activity.contains("import android.view.MotionEvent"))
        assertFalse(activity.contains("dispatchTouchEvent("))
        assertFalse(activity.contains("MotionEvent.obtain("))
        assertFalse(activity.contains(".click()"))
    }

    @Test
    fun r183ForcesVietnameseOnlyInLiveSetupWithoutLanguageUiAutomation() {
        val guard = source("ui/AiStudioWebSessionR18LanguageGuard.kt")
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")

        assertTrue(guard.contains("r18.3a-network-language-guard"))
        assertTrue(guard.contains("gemini-3.5-live-translate-preview"))
        assertTrue(guard.contains("targetLanguage:'vi'"))
        assertTrue(guard.contains("target_language_code"))
        assertTrue(guard.contains("targetLanguageCode"))
        assertTrue(guard.contains("/v1/bidiGenerateContent"))
        assertTrue(guard.contains("/^req\\d+___data__$/"))
        assertTrue(guard.contains("audio\\/pcm"))
        assertTrue(guard.contains("bounded-en-token"))
        assertTrue(guard.contains("ambiguous-en-token-not-rewritten"))
        assertTrue(guard.contains("targetLanguageVerified"))
        assertTrue(guard.contains("lastBeforeHash"))
        assertTrue(guard.contains("lastAfterHash"))
        assertTrue(guard.contains("lastFallbackPaths"))
        assertTrue(guard.contains("lastModelPaths"))

        assertFalse(guard.contains("querySelector("))
        assertFalse(guard.contains("getElementsBy"))
        assertFalse(guard.contains(".click()"))
        assertFalse(guard.contains("dispatchEvent("))
        assertFalse(guard.contains("MotionEvent"))
        assertFalse(guard.contains("requestBody"))
        assertFalse(guard.contains("Authorization"))
        assertFalse(guard.contains("document.cookie"))

        assertTrue(activity.contains("AiStudioWebSessionR18LanguageGuard.DOCUMENT_START"))
        assertTrue(activity.contains("window.__AIS_R183_LANGUAGE__.configure('vi')"))
        assertTrue(activity.contains("targetLanguageVerified"))
        assertTrue(activity.contains("r18-language-state"))
        assertFalse(activity.contains("AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START"))
    }

    @Test
    fun r183bDiscoversAndInvokesOnlyNonUiRuntimeCandidates() {
        val bootstrap = source("ui/AiStudioWebSessionR18RuntimeBootstrap.kt")
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")

        assertTrue(bootstrap.contains("r18.3b2-behavioral-runtime-bootstrap"))
        assertTrue(bootstrap.contains("Function.prototype.toString.call"))
        assertTrue(bootstrap.contains("Object.getOwnPropertyDescriptors"))
        assertTrue(bootstrap.contains("Reflect.apply(best.fn,best.receiver,[])"))
        assertTrue(bootstrap.contains("bidiGenerateContent"))
        assertTrue(bootstrap.contains("getUserMedia"))
        assertTrue(bootstrap.contains("gemini-3.5-live-translate-preview"))
        assertTrue(bootstrap.contains("receiverSignals"))
        assertTrue(bootstrap.contains("structuredFrames"))
        assertTrue(bootstrap.contains("RUNTIME_HANDLES_LEARNED"))
        assertTrue(bootstrap.contains("Error.prepareStackTrace"))
        assertTrue(bootstrap.contains("queryProfile"))
        assertTrue(bootstrap.contains("ambiguous-candidates"))
        assertTrue(bootstrap.contains("no-runtime-candidate"))
        assertTrue(bootstrap.contains("sourceHash"))
        assertTrue(bootstrap.contains("setup-complete"))
        assertTrue(bootstrap.contains("value instanceof EventTarget"))
        assertTrue(bootstrap.contains("value instanceof Node"))
        assertTrue(bootstrap.contains("fn.length!==0"))
        assertTrue(bootstrap.contains("c.arity===0"))

        assertFalse(bootstrap.contains("transport-shape"))
        assertFalse(bootstrap.contains("xn-call"))
        assertFalse(bootstrap.contains("km-call"))
        assertFalse(bootstrap.contains("document.querySelector("))
        assertFalse(bootstrap.contains("document.querySelectorAll("))
        assertFalse(bootstrap.contains("getElementsBy"))
        assertFalse(bootstrap.contains("dispatchEvent(new"))
        assertFalse(bootstrap.contains("new MouseEvent"))
        assertFalse(bootstrap.contains("MotionEvent"))
        assertFalse(bootstrap.contains("getBoundingClientRect()"))
        assertFalse(bootstrap.contains("innerText"))
        assertFalse(bootstrap.contains("textContent"))

        assertTrue(activity.contains("AiStudioWebSessionR18RuntimeBootstrap.DOCUMENT_START"))
        assertTrue(activity.contains("window.__AIS_R183B_BOOTSTRAP__.start('vi')"))
        assertTrue(activity.contains("TỰ KHỞI ĐỘNG LIVE R18.3B"))
        assertTrue(activity.contains("không cần thao tác trên trang AI Studio"))
        assertTrue(activity.contains("r18-bootstrap-state"))
        assertFalse(activity.contains("tự bấm Start Live"))
        assertFalse(activity.contains("clickElement("))
        assertFalse(activity.contains("dispatchEvent("))
    }

    @Test
    fun r183bGuidedLabExportsDetailedCaptureArtifacts() {
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")
        val manifest = manifest()
        val log = source("core/AiStudioWebSessionLabLog.kt")

        assertTrue(activity.contains("r18.3b-guided-runtime-bootstrap"))
        assertTrue(activity.contains("private fun finishCapture()"))
        assertTrue(activity.contains("r18-bootstrap-state"))
        assertTrue(activity.contains("r18-final-summary"))
        assertTrue(activity.contains("r18-causal-timeline"))
        assertTrue(activity.contains("r18-r132-deep-recent"))
        assertTrue(activity.contains("r18-language-state"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR13DeepProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START"))

        assertTrue(manifest.contains(".ui.AiStudioWebSessionR18Activity"))
        assertTrue(manifest.contains("AI Studio R18 - Bắt đường Live"))
        assertTrue(Regex("android.intent.category.LAUNCHER").findAll(manifest).count() == 2)
        val r16Block = Regex("<activity[^>]*android:name=\\\"\\.ui\\.AiStudioWebSessionR16Activity\\\"[\\s\\S]*?/>")
            .find(manifest)?.value.orEmpty()
        assertTrue(r16Block.isNotEmpty())
        assertFalse(r16Block.contains("LAUNCHER"))
        assertTrue(log.contains("\"r18-bootstrap-state.txt\" -> -6"))
        assertTrue(log.contains("\"r18-language-state.txt\" -> -5"))
        assertTrue(log.contains("\"r18-final-summary.txt\" -> -4"))
        assertTrue(log.contains("\"r18-causal-timeline.txt\" -> -3"))
        assertTrue(log.indexOf("\"r18-bootstrap-state.txt\" -> -6") < log.indexOf("\"r18-language-state.txt\" -> -5"))
        assertTrue(log.indexOf("\"r18-language-state.txt\" -> -5") < log.indexOf("\"r18-final-summary.txt\" -> -4"))
        assertTrue(log.indexOf("\"r18-final-summary.txt\" -> -4") < log.indexOf("\"r16-final-summary.txt\" -> 0"))
    }
}
