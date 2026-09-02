package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR6SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r6InvokesInputAndKeydownHandlersDirectlyWithoutDomDispatchOrTouch() {
        val manifest = source("src/main/AndroidManifest.xml")
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR6Activity.kt")
        val capture = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR6HandlerCapture.kt")

        assertTrue(manifest.contains(".ui.AiStudioWebSessionR6Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R6 - Prompt state trực tiếp"))
        assertTrue(activity.contains("2026-09-02-web-session-r6"))
        assertTrue(activity.contains("AiStudioWebSessionR6HandlerCapture.DOCUMENT_START"))
        assertTrue(activity.contains("invokeDirect"))
        assertTrue(activity.contains("R6_HANDLER_SUCCESS"))
        assertTrue(activity.contains("R6_REASSEMBLED_RESULT"))
        assertTrue(activity.contains("getLastSafeResponse"))

        assertTrue(capture.contains("2026-09-02-web-session-r6-handler-capture"))
        assertTrue(capture.contains("t==='input' || t==='change' || t==='keydown'"))
        assertTrue(capture.contains("setPromptValueOnly"))
        assertTrue(capture.contains("R6_INPUT_PLAN"))
        assertTrue(capture.contains("R6_INPUT_HANDLER_ATTEMPT"))
        assertTrue(capture.contains("R6_INPUT_SYNC_DONE"))
        assertTrue(capture.contains("R6_KEYDOWN_HANDLER_ATTEMPT"))
        assertTrue(capture.contains("listener.call(entry.target,event)"))
        assertTrue(capture.contains("domEventDispatchUsed:false"))
        assertTrue(capture.contains("keyboardDispatchUsed:false"))

        assertFalse(capture.contains(".dispatchEvent("))
        assertFalse(capture.contains("ctrl-enter-event"))
        assertFalse(activity.contains("import android.view.MotionEvent"))
        assertFalse(activity.contains("dispatchTouchEvent"))
        assertFalse(activity.contains("runCandidates"))
        assertFalse(activity.contains("GEMINI_API_KEY"))
        assertFalse(activity.contains("Authorization="))
        assertFalse(activity.contains("X-Goog-Api-Key="))
        assertFalse(capture.contains("GEMINI_API_KEY"))
        assertFalse(capture.contains("Authorization="))
        assertFalse(capture.contains("X-Goog-Api-Key="))
    }
}
