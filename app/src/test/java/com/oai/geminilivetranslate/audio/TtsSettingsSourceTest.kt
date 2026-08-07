package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsSettingsSourceTest {
    @Test
    fun ttsSettingsAreLinkedAndDefaultToVietnamese() {
        val preferences = source("audio/TtsPreferences.kt")
        val catalog = source("audio/TtsCatalogScanner.kt")
        val activity = source("ui/TtsSettingsActivity.kt")
        val settings = source("ui/SettingsActivity.kt")
        val manifest = resource("AndroidManifest.xml")

        assertTrue(preferences.contains("DEFAULT_TTS_LANGUAGE = \"vi-VN\""))
        assertTrue(catalog.contains("fun voicesForLanguage(languageTag: String)"))
        assertTrue(catalog.contains("preferredLanguage"))
        assertTrue(catalog.contains("Mặc định hệ thống"))
        assertTrue(activity.contains("Bộ đọc"))
        assertTrue(activity.contains("Ngôn ngữ"))
        assertTrue(activity.contains("Giọng đọc"))
        assertTrue(activity.contains("Nghe thử"))
        assertTrue(activity.contains("voicesForLanguage"))
        assertTrue(settings.contains("TtsSettingsActivity::class.java"))
        assertTrue(manifest.contains(".ui.TtsSettingsActivity"))
    }

    @Test
    fun robustTtsAppliesPersistedEngineLanguageAndVoice() {
        val robust = source("audio/RobustTtsEngine.kt")
        assertTrue(robust.contains("ttsPreferences.load()"))
        assertTrue(robust.contains("configuredEnginePreference"))
        assertTrue(robust.contains("applySelectedVoice"))
        assertTrue(robust.contains("selection.languageTag"))
        assertTrue(robust.contains("selection.voiceName"))
    }

    private fun source(relative: String): String = firstExisting(
        "src/main/java/com/oai/geminilivetranslate/$relative",
        "app/src/main/java/com/oai/geminilivetranslate/$relative",
    )

    private fun resource(relative: String): String = firstExisting(
        "src/main/$relative",
        relative,
        "app/src/main/$relative",
    )

    private fun firstExisting(vararg paths: String): String = paths.asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("Không tìm thấy source: ${paths.joinToString()}")
}
