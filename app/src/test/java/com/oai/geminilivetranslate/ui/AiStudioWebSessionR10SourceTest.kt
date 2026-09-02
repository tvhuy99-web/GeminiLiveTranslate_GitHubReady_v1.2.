package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR10SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r71NormalizesProtobufFragmentsInsideResponseCore() {
        val core = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionResponseCore.kt")
        assertTrue(core.contains("2026-09-02-web-session-r7.1-response-core"))
        assertTrue(core.contains("extractModelText"))
        assertTrue(core.contains("rawMarkerFound"))
        assertTrue(core.contains("modelTextChars"))
        assertTrue(core.contains("terminalSignal"))
        assertTrue(core.contains("NORMALIZED_GENERATE_RESULT"))
        assertTrue(core.contains("net.getLastSafeResponse=function"))
        assertFalse(core.contains("GEMINI_API_KEY"))
        assertFalse(core.contains("Authorization="))
        assertFalse(core.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r8R9DiscoverControllerFromListenerGraphWithoutTextareaSelectorOrFixedIds() {
        val runtime = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionAdaptiveRuntime.kt")
        assertTrue(runtime.contains("2026-09-02-web-session-r9-adaptive-runtime"))
        assertTrue(runtime.contains("groupFor(target)"))
        assertTrue(runtime.contains("candidateScore(group)"))
        assertTrue(runtime.contains("successes"))
        assertTrue(runtime.contains("failures"))
        assertTrue(runtime.contains("R9_DISCOVERY_PLAN"))
        assertTrue(runtime.contains("R9_CANDIDATE_ATTEMPT"))
        assertTrue(runtime.contains("R9_HANDLER_SUCCESS"))
        assertTrue(runtime.contains("selectorQueryUsed:false"))
        assertTrue(runtime.contains("fixedListenerIdsUsed:false"))
        assertTrue(runtime.contains("realPromptValueMutated:false"))
        assertFalse(runtime.contains("querySelector("))
        assertFalse(runtime.contains(".dispatchEvent("))
        assertFalse(runtime.contains("GEMINI_API_KEY"))
        assertFalse(runtime.contains("Authorization="))
        assertFalse(runtime.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r10ExecutorOwnsSessionGenerateSingleFlightTimeoutAndCancel() {
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(executor.contains("2026-09-02-web-session-r10-executor"))
        assertTrue(executor.contains("fun generate("))
        assertTrue(executor.contains("fun cancelCurrent()"))
        assertTrue(executor.contains("fun destroy()"))
        assertTrue(executor.contains("error = \"BUSY\""))
        assertTrue(executor.contains("error = \"TIMEOUT\""))
        assertTrue(executor.contains("AiStudioWebSessionResponseCore.DOCUMENT_START"))
        assertTrue(executor.contains("AiStudioWebSessionAdaptiveRuntime.DOCUMENT_START"))
        assertTrue(executor.contains("NORMALIZED_GENERATE_RESULT"))
        assertTrue(executor.contains("R9_HANDLER_FINAL"))
        assertFalse(executor.contains("querySelector("))
        assertFalse(executor.contains("dispatchTouchEvent"))
        assertFalse(executor.contains("GEMINI_API_KEY"))
        assertFalse(executor.contains("Authorization="))
        assertFalse(executor.contains("X-Goog-Api-Key="))
    }

    @Test
    fun r10ActivityIsThinShellAroundExecutor() {
        val manifest = source("src/main/AndroidManifest.xml")
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR10Activity.kt")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionR10Activity"))
        assertTrue(manifest.contains("AI Studio Web Session R10 - Executor"))
        assertTrue(activity.contains("AiStudioWebSessionExecutor"))
        assertTrue(activity.contains("executor.generate(prompt, marker)"))
        assertTrue(activity.contains("executor.refreshDiscovery()"))
        assertTrue(activity.contains("executor.cancelCurrent()"))
        assertFalse(activity.contains("evaluateJavascript"))
        assertFalse(activity.contains("querySelector("))
        assertFalse(activity.contains("MotionEvent"))
        assertFalse(activity.contains("GEMINI_API_KEY"))
    }
}
