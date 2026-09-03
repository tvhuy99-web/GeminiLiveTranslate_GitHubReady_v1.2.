package com.oai.geminilivetranslate.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiStudioWebSessionR18SourceTest {
    private fun source(path: String): String = File("src/main/java/com/oai/geminilivetranslate/$path").readText()

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

        assertFalse(probe.contains("dispatchTouchEvent"))
        assertFalse(probe.contains("MotionEvent"))
        assertFalse(probe.contains(".click()"))
        assertFalse(activity.contains("dispatchTouchEvent"))
        assertFalse(activity.contains("MotionEvent"))
        assertFalse(activity.contains(".click()"))
    }

    @Test
    fun r18GuidedLabExportsDetailedCaptureArtifacts() {
        val activity = source("ui/AiStudioWebSessionR18Activity.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val log = source("core/AiStudioWebSessionLabLog.kt")

        assertTrue(activity.contains("BẮT ĐẦU GHI R18.1 + R18.2"))
        assertTrue(activity.contains("KẾT THÚC + CHỤP TOÀN BỘ"))
        assertTrue(activity.contains("r18-final-summary"))
        assertTrue(activity.contains("r18-causal-timeline"))
        assertTrue(activity.contains("r18-r132-deep-recent"))
        assertTrue(activity.contains("AiStudioWebSessionLiveProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR13DeepProbe.DOCUMENT_START"))
        assertTrue(activity.contains("AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START"))

        assertTrue(manifest.contains(".ui.AiStudioWebSessionR18Activity"))
        assertTrue(manifest.contains("AI Studio R18 - Bắt đường Live"))
        assertTrue(log.contains("\"r18-final-summary.txt\" -> 0"))
        assertTrue(log.contains("\"r18-causal-timeline.txt\" -> 1"))
    }
}
