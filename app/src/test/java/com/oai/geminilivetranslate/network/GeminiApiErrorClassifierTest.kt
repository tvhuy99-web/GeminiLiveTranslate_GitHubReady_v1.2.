package com.oai.geminilivetranslate.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiErrorClassifierTest {
    @Test
    fun recognizesAuthAndRateLimitErrorsForFailover() {
        listOf(401, 403, 429).forEach { code ->
            val error = IllegalStateException("Gemini HTTP $code: test")
            assertEquals(code, GeminiApiErrorClassifier.httpCode(error))
            assertTrue(GeminiApiErrorClassifier.requiresKeyFailover(error))
        }
    }

    @Test
    fun findsHttpCodeInNestedCauseAndKeepsServerErrorsOnSameKey() {
        val error = IllegalStateException(
            "wrapper",
            IllegalStateException("Không đọc được tiến trình: HTTP 503 unavailable"),
        )
        assertEquals(503, GeminiApiErrorClassifier.httpCode(error))
        assertFalse(GeminiApiErrorClassifier.requiresKeyFailover(error))
    }

    @Test
    fun ignoresMessagesWithoutHttpStatus() {
        val error = IllegalStateException("Mất kết nối mạng")
        assertEquals(null, GeminiApiErrorClassifier.httpCode(error))
        assertFalse(GeminiApiErrorClassifier.requiresKeyFailover(error))
    }
}
