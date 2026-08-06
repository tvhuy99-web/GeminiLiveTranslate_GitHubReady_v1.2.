package com.oai.geminilivetranslate.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveClientSetupTest {
    @Test
    fun translationSetupMatchesWorkingLuaPayload() {
        val root = JSONObject(
            GeminiLiveClient.createSetupMessage(
                model = "gemini-3.5-live-translate-preview",
                targetLanguage = "vi",
                echoTargetLanguage = false,
                resumeHandle = "resume-token",
            )
        )
        val setup = root.getJSONObject("setup")
        val generationConfig = setup.getJSONObject("generationConfig")
        val translationConfig = generationConfig.getJSONObject("translationConfig")

        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        assertEquals("AUDIO", generationConfig.getJSONArray("responseModalities").getString(0))
        assertEquals("vi", translationConfig.getString("targetLanguageCode"))
        assertFalse(translationConfig.getBoolean("echoTargetLanguage"))

        assertFalse(generationConfig.has("inputAudioTranscription"))
        assertFalse(generationConfig.has("outputAudioTranscription"))
        assertFalse(setup.has("inputAudioTranscription"))
        assertFalse(setup.has("outputAudioTranscription"))
        assertTrue(setup.has("sessionResumption"))
        assertEquals("resume-token", setup.getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun newSessionOmitsEmptySessionResumptionObject() {
        val setup = JSONObject(
            GeminiLiveClient.createSetupMessage(
                model = "gemini-3.5-live-translate-preview",
                targetLanguage = "vi",
                echoTargetLanguage = true,
                resumeHandle = null,
            )
        ).getJSONObject("setup")

        assertFalse(setup.has("sessionResumption"))
    }
}
