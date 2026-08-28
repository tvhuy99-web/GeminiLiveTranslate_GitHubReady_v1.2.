package com.oai.geminilivetranslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPolicyTest {
    @Test
    fun sanitizeClampsAndRepairsInvalidValues() {
        val safe = SettingsPolicy.sanitize(
            AppSettings(
                model = "models/",
                targetLanguage = "@@invalid@@",
                uiMode = "broken",
                performanceProfile = "broken",
                reconnectMaxRetries = 99,
                outputJitterTarget = 20,
                translatedQueueMax = 5,
                pacingMaxBuffer = 999,
                saveAudioMode = "broken",
                exportFormat = "broken",
                logLevel = 99,
                micLanguages = listOf("", "vi", "VI", "bad value"),
                micLanguageIndex = 99,
            )
        )

        assertEquals(AppPreferences.DEFAULT_MODEL, safe.model)
        assertEquals("vi", safe.targetLanguage)
        assertEquals("advanced", safe.uiMode)
        assertEquals("custom", safe.performanceProfile)
        assertEquals(10, safe.reconnectMaxRetries)
        assertEquals(5, safe.outputJitterTarget)
        assertEquals(50, safe.pacingMaxBuffer)
        assertEquals("translated", safe.saveAudioMode)
        assertEquals("srt", safe.exportFormat)
        assertEquals(3, safe.logLevel)
        assertEquals(listOf("vi"), safe.micLanguages)
        assertEquals(0, safe.micLanguageIndex)
    }

    @Test
    fun expandedAudioStreamTypesSurviveSanitization() {
        val values = listOf(
            "accessibility",
            "media",
            "voice_communication",
            "assistant",
            "alarm",
            "notification",
            "ring",
            "system",
            "voice_call",
            "dtmf",
        )
        values.forEach { value ->
            assertEquals(value, SettingsPolicy.sanitize(AppSettings(aiAudioStreamType = value)).aiAudioStreamType)
        }
    }

    @Test
    fun diffSeparatesLiveReconnectPlaybackAndNextSessionChanges() {
        val old = AppSettings()
        val changed = old.copy(
            targetLanguage = "en",
            translatedBufferBytes = 120_000,
            pacingMaxBuffer = 9,
            logLevel = 3,
        )
        val diff = SettingsPolicy.diff(old, changed)

        assertTrue("targetLanguage" in diff.reconnect)
        assertTrue("translatedBufferBytes" in diff.playbackRebuild)
        assertTrue("pacingMaxBuffer" in diff.nextSession)
        assertTrue("logLevel" in diff.immediate)
    }
    @Test
    fun activeSessionKeepsDeferredAudioPipelineValuesButAppliesSafeChanges() {
        val before = AppSettings(
            targetLanguage = "vi",
            qualityMode = false,
            inputBufferMs = 800,
            pacingMaxBuffer = 5,
            saveAudioEnabled = false,
            translatedBufferBytes = 96_000,
            logLevel = 2,
        )
        val persisted = before.copy(
            targetLanguage = "en-US",
            qualityMode = true,
            inputBufferMs = 2_000,
            pacingMaxBuffer = 20,
            saveAudioEnabled = true,
            translatedBufferBytes = 128_000,
            logLevel = 3,
        )

        val active = SettingsPolicy.activeSessionSettings(before, persisted)

        assertEquals("en-US", active.targetLanguage)
        assertEquals(128_000, active.translatedBufferBytes)
        assertEquals(3, active.logLevel)
        assertEquals(false, active.qualityMode)
        assertEquals(800, active.inputBufferMs)
        assertEquals(5, active.pacingMaxBuffer)
        assertEquals(false, active.saveAudioEnabled)
    }

    @Test
    fun defaultDiagnosticsCaptureNormalEventsWithoutTranscript() {
        val defaults = AppSettings()
        assertEquals(2, defaults.logLevel)
        assertTrue(defaults.logToFile)
        assertEquals(false, defaults.logIncludeTranscript)
    }

}
