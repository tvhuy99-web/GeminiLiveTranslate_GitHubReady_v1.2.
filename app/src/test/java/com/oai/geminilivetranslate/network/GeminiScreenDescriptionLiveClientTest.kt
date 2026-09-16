package com.oai.geminilivetranslate.network

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiScreenDescriptionLiveClientTest {
    @Test
    fun setupKeepsAudioOutputWithoutAudioInputConfiguration() {
        val setup = JSONObject(
            GeminiScreenDescriptionLiveClient.createSetupMessage("Tiếng Việt (vi)"),
        ).getJSONObject("setup")

        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals("AUDIO", setup.getJSONArray("responseModalities").getString(0))
        assertTrue(setup.has("systemInstruction"))
        assertTrue(setup.has("outputAudioTranscription"))
        assertFalse(setup.has("inputAudioTranscription"))
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
        assertArrayEquals(frame, Base64.decode(video.getString("data"), Base64.DEFAULT))
    }
}
