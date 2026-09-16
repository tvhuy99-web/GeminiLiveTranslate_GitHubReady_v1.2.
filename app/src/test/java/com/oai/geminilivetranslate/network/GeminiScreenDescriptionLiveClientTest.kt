package com.oai.geminilivetranslate.network

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiScreenDescriptionLiveClientTest {
    @Test
    fun setupKeepsAudioOutputWithoutAudioInputConfiguration() {
        val setup = JSONObject(
            GeminiScreenDescriptionLiveClient.createSetupMessage("Tiếng Việt (vi)"),
        ).getJSONObject("setup")

        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertFalse(setup.has("responseModalities"))
        val generationConfig = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", generationConfig.getJSONArray("responseModalities").getString(0))
        assertTrue(setup.has("systemInstruction"))
        assertTrue(setup.has("outputAudioTranscription"))
        assertFalse(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("contextWindowCompression"))
        assertTrue(setup.has("sessionResumption"))
        assertFalse(setup.getJSONObject("sessionResumption").has("handle"))
    }

    @Test
    fun customPromptReplacesDefaultSystemInstruction() {
        val custom = "Chỉ mô tả hành động và chữ quan trọng trên màn hình."
        val setup = JSONObject(
            GeminiScreenDescriptionLiveClient.createSetupMessage(
                outputLanguage = "Tiếng Việt (vi)",
                customPrompt = custom,
            ),
        ).getJSONObject("setup")

        val instruction = setup.getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertEquals(custom, instruction)
    }

    @Test
    fun blankCustomPromptFallsBackToDefaultSystemInstruction() {
        val setup = JSONObject(
            GeminiScreenDescriptionLiveClient.createSetupMessage(
                outputLanguage = "Tiếng Việt (vi)",
                customPrompt = "   ",
            ),
        ).getJSONObject("setup")

        val instruction = setup.getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(instruction.contains("thuyết minh hình ảnh theo thời gian thực"))
        assertNotEquals("", instruction)
    }

    @Test
    fun resumedSetupCarriesOnlyTheProvidedSessionHandle() {
        val handle = "resume-handle-123"
        val setup = JSONObject(
            GeminiScreenDescriptionLiveClient.createSetupMessage(
                outputLanguage = "Tiếng Việt (vi)",
                resumptionHandle = handle,
            ),
        ).getJSONObject("setup")

        val resumption = setup.getJSONObject("sessionResumption")
        assertEquals(handle, resumption.getString("handle"))
        assertEquals(1, resumption.length())
        assertFalse(setup.has("inputAudioTranscription"))
        assertFalse(setup.has("responseModalities"))
        assertEquals(
            "AUDIO",
            setup.getJSONObject("generationConfig")
                .getJSONArray("responseModalities")
                .getString(0),
        )
    }

    @Test
    fun videoMessageContainsOnlyVideoRealtimeMedia() {
        val frame = byteArrayOf(1, 2, 3, 4, 5)
        val realtimeInput = JSONObject(
            GeminiScreenDescriptionLiveClient.createVideoMessage(frame),
        ).getJSONObject("realtimeInput")

        assertTrue(realtimeInput.has("video"))
        assertFalse(realtimeInput.has("audio"))
        assertFalse(realtimeInput.has("audioStreamEnd"))

        val video = realtimeInput.getJSONObject("video")
        assertEquals("image/jpeg", video.getString("mimeType"))
        assertArrayEquals(frame, Base64.getDecoder().decode(video.getString("data")))
    }

    @Test
    fun heartbeatTriggersVisualReasoningWithoutAudioInput() {
        val realtimeInput = JSONObject(
            GeminiScreenDescriptionLiveClient.createHeartbeatMessage(),
        ).getJSONObject("realtimeInput")

        assertTrue(realtimeInput.getString("text").isNotBlank())
        assertFalse(realtimeInput.has("audio"))
        assertFalse(realtimeInput.has("video"))
        assertFalse(realtimeInput.has("audioStreamEnd"))
    }
}
