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
    fun r92UsesCumulativeSupportScoringHighConfidenceGateAndValidSortCallback() {
        val runtime = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionAdaptiveRuntime.kt")
        assertTrue(runtime.contains("2026-09-02-web-session-r9.2-adaptive-runtime"))
        assertTrue(runtime.contains("groupFor(target)"))
        assertTrue(runtime.contains("candidateScore(group)"))
        assertTrue(runtime.contains("readyCandidates()"))
        assertTrue(runtime.contains("isReadyCandidate(item)"))
        assertTrue(runtime.contains("readyCandidateCount"))
        assertTrue(runtime.contains("controllerReady"))
        assertTrue(runtime.contains("if(entry.target===document) score+=260"))
        assertTrue(runtime.contains("if(entry.target===document.body) score+=200"))
        assertTrue(runtime.contains("entry.target.contains(target)) score+=700"))
        assertFalse(runtime.contains("else if(entry.target===document)"))
        assertTrue(runtime.contains("supportCounts"))
        assertTrue(runtime.contains("supportClass"))
        assertTrue(runtime.contains(".sort(function(a,b){return b.score-a.score;})"))
        assertFalse(runtime.contains("function(a,b)=>"))
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
    fun httpStatusGuardPreservesNon2xxBeforeAiStudioResetsXhr() {
        val guard = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionHttpStatusGuard.kt")
        assertTrue(guard.contains("2026-09-02-web-session-http-status-guard-r1"))
        assertTrue(guard.contains("status<400||status>599"))
        assertTrue(guard.contains("readyState"))
        assertTrue(guard.contains("GENERATE_HTTP_ERROR"))
        assertTrue(guard.contains("error:'HTTP_'+String(meta.bestStatus)"))
        assertTrue(guard.contains("net.lastResult"))
        assertFalse(guard.contains("GEMINI_API_KEY"))
        assertFalse(guard.contains("Authorization="))
        assertFalse(guard.contains("X-Goog-Api-Key="))
        assertFalse(guard.contains("document.cookie"))
    }

    @Test
    fun r103ExecutorRequiresReadyControllerPropagatesHttpErrorsAndUsesTrustedAttachmentRecovery() {
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(executor.contains("2026-09-02-web-session-r10.3-trusted-attachment-submit"))
        assertTrue(executor.contains("fun generate("))
        assertTrue(executor.contains("fun cancelCurrent()"))
        assertTrue(executor.contains("fun destroy()"))
        assertTrue(executor.contains("error = \"BUSY\""))
        assertTrue(executor.contains("error = \"TIMEOUT\""))
        assertTrue(executor.contains("readyCandidateCount"))
        assertTrue(executor.contains("controllerReady"))
        assertTrue(executor.contains("AiStudioWebSessionHttpStatusGuard.DOCUMENT_START"))
        assertTrue(executor.contains("AiStudioWebSessionResponseCore.DOCUMENT_START"))
        assertTrue(executor.contains("AiStudioWebSessionAdaptiveRuntime.DOCUMENT_START"))
        assertTrue(executor.contains("AiStudioWebSessionR11SubmitTargetFix.DOCUMENT_START"))
        assertTrue(executor.contains("GENERATE_HTTP_ERROR"))
        assertTrue(executor.contains("HTTP_403"))
        assertTrue(executor.contains("HTTP_429"))
        assertTrue(executor.contains("HTTP_5XX"))
        assertTrue(executor.contains("NORMALIZED_GENERATE_RESULT"))
        assertTrue(executor.contains("R9_HANDLER_FINAL"))
        assertTrue(executor.contains("tryAttachmentSubmitRecovery"))
        assertTrue(executor.contains("dispatchTrustedWebViewTap"))
        assertTrue(executor.contains("dispatchTouchEvent"))
        assertTrue(executor.contains("InputDevice.SOURCE_TOUCHSCREEN"))
        assertFalse(executor.contains("querySelector("))
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
