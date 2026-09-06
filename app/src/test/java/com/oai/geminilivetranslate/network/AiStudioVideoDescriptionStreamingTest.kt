package com.oai.geminilivetranslate.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioVideoDescriptionStreamingTest {
    @Test
    fun summaryExtractsIncompleteJsonString() {
        val raw = """{"text":"Xin chào\nthế giới"""
        assertEquals(
            "Xin chào\nthế giới",
            AiStudioVideoDescriptionClient.streamingTextForUi(raw, GeminiVideoDescriptionClient.Mode.SUMMARY),
        )
    }

    @Test
    fun summaryDecodesEscapedQuote() {
        val raw = """{"text":"Anh ấy nói \"xin chào\" rồi đi tiếp"}"""
        assertEquals(
            "Anh ấy nói \"xin chào\" rồi đi tiếp",
            AiStudioVideoDescriptionClient.streamingTextForUi(raw, GeminiVideoDescriptionClient.Mode.SUMMARY),
        )
    }

    @Test
    fun timelineCollectsMultipleTextFieldsIncludingLastPartial() {
        val raw = """{"items":[{"text":"Cảnh một"},{"text":"Cảnh hai đang hình thành"""
        assertEquals(
            "Cảnh một\nCảnh hai đang hình thành",
            AiStudioVideoDescriptionClient.streamingTextForUi(raw, GeminiVideoDescriptionClient.Mode.TIMELINE),
        )
    }

    @Test
    fun timelineCompletionRejectsUnterminatedArray() {
        val raw = """{"items":[{"index":1,"start_seconds":0.0,"end_seconds":14.0,"type":"description","text":"Cảnh một"},{"index":2,"start_seconds":14.0,"end_seconds":27.0,"type":"description","text":"Cảnh hai"}"""
        assertFalse(AiStudioVideoDescriptionClient.isCompleteJsonObject(raw))
    }

    @Test
    fun timelineCompletionAcceptsClosedJsonObject() {
        val raw = """{"items":[{"index":1,"start_seconds":0.0,"end_seconds":14.0,"type":"description","text":"Cảnh một"},{"index":2,"start_seconds":14.0,"end_seconds":27.0,"type":"description","text":"Cảnh hai"}]}"""
        assertTrue(AiStudioVideoDescriptionClient.isCompleteJsonObject(raw))
    }

    @Test
    fun timelineCompletionRejectsOpenStringAtEnd() {
        val raw = """{"items":[{"text":"Đang viết tiếp"""
        assertFalse(AiStudioVideoDescriptionClient.isCompleteJsonObject(raw))
    }
}
