package com.oai.geminilivetranslate.network

import org.junit.Assert.assertEquals
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
}
