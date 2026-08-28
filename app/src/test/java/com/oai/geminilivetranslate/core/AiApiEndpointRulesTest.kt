package com.oai.geminilivetranslate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AiApiEndpointRulesTest {
    @Test
    fun buildsChatAndModelsEndpointsFromBaseUrl() {
        val base = "https://example.test/v1"
        assertEquals(
            "https://example.test/v1/chat/completions",
            AiApiEndpointRules.proxyChatEndpoint(base),
        )
        assertEquals(
            "https://example.test/v1/models",
            AiApiEndpointRules.proxyModelsEndpoint(base),
        )
    }

    @Test
    fun normalizesKnownEndpointSuffixes() {
        val variants = listOf(
            "https://example.test/v1/chat/completions",
            "https://example.test/v1/responses",
            "https://example.test/v1/models",
            "https://example.test/v1/",
        )
        variants.forEach { value ->
            assertEquals(
                "https://example.test/v1/chat/completions",
                AiApiEndpointRules.proxyChatEndpoint(value),
            )
            assertEquals(
                "https://example.test/v1/models",
                AiApiEndpointRules.proxyModelsEndpoint(value),
            )
        }
    }
}
