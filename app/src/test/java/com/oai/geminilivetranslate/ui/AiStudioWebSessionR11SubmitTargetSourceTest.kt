package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR11SubmitTargetSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r12DirectEngineCapturesPageHandlersAndKeepsRequestTemplatePageLocal() {
        val src = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionDirectEngine.kt")
        assertTrue(src.contains("2026-09-02-web-session-r12.0-direct-engine"))
        assertTrue(src.contains("t==='click'||t==='submit'"))
        assertTrue(src.contains("invokeDirect"))
        assertTrue(src.contains("R12_DIRECT_PLAN"))
        assertTrue(src.contains("R12_DIRECT_HANDLER_ATTEMPT"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_SUCCESS"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_FINAL"))
        assertTrue(src.contains("R12_REQUEST_TEMPLATE_CAPTURED"))
        assertTrue(src.contains("replayLastTemplate"))
        assertTrue(src.contains("form.requestSubmit"))
        assertTrue(src.contains("type=\"submit\""))
        assertFalse(src.contains("document.cookie"))
        assertFalse(src.contains("Authorization="))
        assertFalse(src.contains("X-Goog-Api-Key="))
        assertFalse(src.contains("password="))
    }

    @Test
    fun executorSupportsManualVideoObservationAndStrictNativeFileSubmit() {
        val src = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(src.contains("2026-09-05-web-session-r12.5-file-only-transcribe-video-probe"))
        assertTrue(src.contains("AiStudioWebSessionDirectEngine.DOCUMENT_START"))
        assertTrue(src.contains("tryDirectEngineRecovery"))
        assertTrue(src.contains("R12_DIRECT_RECOVERY_START"))
        assertTrue(src.contains("R12_DIRECT_DISPATCH"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_SUCCESS"))
        assertTrue(src.contains("R12_DIRECT_SUBMIT_FINAL"))
        assertTrue(src.contains("tryLegacyProgrammaticFallback"))
        assertTrue(src.contains("tryNativeAttachmentSubmit"))
        assertTrue(src.contains("nativeTapController.requestNativeTap"))
        assertTrue(src.contains("R12_NATIVE_SUBMIT_ACK"))
        assertTrue(src.contains("R20_ATTACHMENT_WAIT_PREPARED"))
        assertTrue(src.contains("R20_ATTACHMENT_PREPARED"))
        val requestFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
        val submitFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt")
        assertTrue(requestFix.contains("2026-09-05-web-session-r11.11-http-rpc-diagnostic"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_FILE_READ_DONE"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_START"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_PROGRESS"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_RESULT"))
        assertTrue(requestFix.contains("R21_ATTACHMENT_BLOB_READ_START"))
        assertTrue(requestFix.contains("R21_ATTACHMENT_BLOB_STREAM_PROGRESS"))
        assertTrue(requestFix.contains("R21_ATTACHMENT_FORMDATA_FILE"))
        assertTrue(requestFix.contains("R21_ATTACHMENT_RESOURCE_TIMING"))
        assertTrue(requestFix.contains("R21_ATTACHMENT_DOM_STATE"))
        assertTrue(requestFix.contains("probeMatches"))
        assertTrue(requestFix.contains("localReadReady=fix.attachmentFileReadCompleted>0"))
        assertTrue(requestFix.contains("attachmentPrepared=present&&!busy&&submitReady&&(localReadReady||blobReadReady||!!dom.readyAfterBusy||serverPayloadSettled)"))
        assertTrue(requestFix.contains("serverPayloadObserved=fix.attachmentPayloadStarted>0"))
        assertTrue(requestFix.contains("submitReady"))
        assertTrue(requestFix.contains("ready:ready"))
        assertTrue(submitFix.contains("submissionReadinessIfAttachment"))
        assertTrue(submitFix.contains("preparePromptIfAttachment"))
        assertTrue(submitFix.contains("semanticSubmitCandidates"))
        assertTrue(submitFix.contains("nativeTargetIfAttachmentFileOnly"))
        assertTrue(submitFix.contains("2026-09-05-web-session-r11.9-cached-hit-test-submit"))
        assertTrue(submitFix.contains("R23_PREPARED_SUBMIT_TARGET"))
        assertTrue(submitFix.contains("R23_NATIVE_SUBMIT_HIT_TEST"))
        assertTrue(submitFix.contains("document.elementFromPoint"))
        assertTrue(requestFix.contains("R23_GENERATE_HTTP_DIAGNOSTIC"))
        assertTrue(requestFix.contains("summarizeGenerateBody"))
        assertTrue(requestFix.contains("requestHeaderNames"))
        assertTrue(src.contains("awaitManualAttachmentGenerate"))
        assertTrue(src.contains("R19_MANUAL_VIDEO_READINESS"))
        assertTrue(src.contains("generateAttachmentNativeOnly"))
        assertTrue(src.contains("generateAttachmentFileOnlyNative"))
        assertTrue(src.contains("file-transcribe-run"))
        assertTrue(src.contains("allowProgrammaticFallback = false"))
        assertFalse(src.contains("dispatchTouchEvent"))
        assertFalse(src.contains("MotionEvent"))
        assertFalse(src.contains("InputDevice.SOURCE_TOUCHSCREEN"))

        val handlerFinalIndex = src.indexOf("\"R9_HANDLER_FINAL\"")
        val directIndex = src.indexOf("tryDirectEngineRecovery(p.seq", handlerFinalIndex)
        val legacyMethodIndex = src.indexOf("private fun tryLegacyProgrammaticFallback")
        assertTrue(handlerFinalIndex >= 0)
        assertTrue(directIndex > handlerFinalIndex)
        assertTrue(legacyMethodIndex >= 0)
    }
}
