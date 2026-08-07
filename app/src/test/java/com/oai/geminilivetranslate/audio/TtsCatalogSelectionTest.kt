package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsCatalogSelectionTest {
    private val catalog = TtsEngineCatalog(
        requestedEnginePackage = "engine.test",
        resolvedEnginePackage = "engine.test",
        languages = listOf(
            TtsLanguageInfo("en-US", "English • en-US"),
            TtsLanguageInfo("vi-VN", "Tiếng Việt • vi-VN"),
            TtsLanguageInfo("zh-CN", "Tiếng Trung • zh-CN"),
        ),
        voices = listOf(
            TtsVoiceInfo("en-one", "en-US", "en-one", false),
            TtsVoiceInfo("vi-one", "vi-VN", "vi-one", false),
            TtsVoiceInfo("vi-two", "vi-VN", "vi-two", true),
            TtsVoiceInfo("zh-one", "zh-CN", "zh-one", false),
        ),
    )

    @Test
    fun defaultLanguageAlwaysPrefersVietnamese() {
        assertEquals("vi-VN", catalog.preferredLanguage("")?.languageTag)
        assertEquals("vi-VN", catalog.preferredLanguage("fr-FR")?.languageTag)
    }

    @Test
    fun savedSupportedLanguageIsKept() {
        assertEquals("en-US", catalog.preferredLanguage("en-US")?.languageTag)
    }

    @Test
    fun voicesAreStrictlyFilteredBySelectedLanguage() {
        val voices = catalog.voicesForLanguage("vi-VN")
        assertEquals(listOf("vi-one", "vi-two"), voices.map { it.name })
        assertTrue(voices.all { it.languageTag == "vi-VN" })
    }

    @Test
    fun savedVoiceMustBelongToSelectedLanguage() {
        assertEquals("vi-two", catalog.preferredVoice("vi-VN", "vi-two")?.name)
        assertEquals(null, catalog.preferredVoice("vi-VN", "en-one"))
    }
}
