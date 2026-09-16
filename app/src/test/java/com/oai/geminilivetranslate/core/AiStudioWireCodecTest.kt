package com.oai.geminilivetranslate.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioWireCodecTest {
    @Test
    fun inspect_acceptsObservedGenerateContentShape() {
        val raw = "[\"models/gemini-test\",[[[[null,\"hello\"]],\"user\"]],null,[null,null,null,128,0.5],\"snapshot\",null,null]"

        val shape = AiStudioWireCodec.inspect(raw)

        assertTrue(shape.valid)
        assertEquals("models/gemini-test", shape.model)
        assertTrue(shape.hasContents)
        assertTrue(shape.hasGenerationConfig)
        assertTrue(shape.hasSnapshotSlot)
        assertTrue(shape.fingerprint.startsWith("len=7:"))
    }

    @Test
    fun inspect_usesSameBooleanTypeLabelAsBrowserGateway() {
        val raw = "[\"models/gemini-test\",[[[[null,\"hello\"]],\"user\"]],true,[],\"snapshot\"]"

        val shape = AiStudioWireCodec.inspect(raw)

        assertTrue(shape.valid)
        assertEquals(
            "len=5:0=string,1=array,2=boolean,3=array,4=string",
            shape.fingerprint,
        )
    }

    @Test
    fun inspect_rejectsUnknownShapeInsteadOfGuessing() {
        val raw = "{\"model\":\"models/gemini-test\"}"

        val shape = AiStudioWireCodec.inspect(raw)

        assertFalse(shape.valid)
        assertTrue(shape.error.startsWith("INVALID_JSON") || shape.error == "MODEL_SLOT_NOT_STRING")
    }

    @Test
    fun inspectReplayEnvelope_acceptsCleanEnvelopeAndEmptyToolsArray() {
        val root = basicRequest("hello")
            .put(AiStudioWireCodec.SYSTEM_INSTRUCTION_INDEX, JSONObject.NULL)
            .put(AiStudioWireCodec.TOOLS_INDEX, JSONArray())

        val envelope = AiStudioWireCodec.inspectReplayEnvelope(root.toString())

        assertTrue(envelope.safe)
        assertFalse(envelope.systemInstructionPresent)
        assertFalse(envelope.toolsPresent)
        assertFalse(envelope.cachedContentPresent)
    }

    @Test
    fun inspectReplayEnvelope_matchesBrowserRulesForSystemToolsAndCachedContent() {
        val root = basicRequest("hello")
        while (root.length() <= AiStudioWireCodec.CACHED_CONTENT_INDEX) root.put(JSONObject.NULL)
        root.put(
            AiStudioWireCodec.SYSTEM_INSTRUCTION_INDEX,
            JSONArray().put(JSONArray().put(JSONObject.NULL).put("system")),
        )
        root.put(AiStudioWireCodec.TOOLS_INDEX, JSONArray().put(JSONArray()))
        root.put(AiStudioWireCodec.CACHED_CONTENT_INDEX, "cachedContents/example")

        val envelope = AiStudioWireCodec.inspectReplayEnvelope(root.toString())

        assertFalse(envelope.safe)
        assertTrue(envelope.systemInstructionPresent)
        assertTrue(envelope.toolsPresent)
        assertTrue(envelope.cachedContentPresent)
    }

    @Test
    fun rewrite_changesOnlyKnownFieldsAndPreservesOpaqueSlots() {
        val raw = "[\"models/old\",[[[[null,\"old prompt\"]],\"user\"]],[[1,2,3]],[null,null,null,128,0.5],\"old-snapshot\",[[[null,\"system\"]],\"user\"],[[[]]],null,null,null,77]"

        val rewritten = AiStudioWireCodec.rewrite(
            rawBody = raw,
            model = "new-model",
            prompt = "new prompt",
            snapshot = "new-snapshot",
        )
        val root = JSONArray(rewritten)

        assertEquals("models/new-model", root.getString(0))
        assertEquals("new prompt", root.getJSONArray(1).getJSONArray(0).getJSONArray(0).getJSONArray(0).getString(1))
        assertEquals("new-snapshot", root.getString(4))
        assertEquals("system", root.getJSONArray(5).getJSONArray(0).getJSONArray(0).getString(1))
        assertEquals(77, root.getInt(10))
        assertEquals("[[1,2,3]]", root.getJSONArray(2).toString())
        assertEquals("[[[]]]", root.getJSONArray(6).toString())
    }

    @Test
    fun rewrite_preservesAttachmentPartWhileReplacingUserText() {
        val attachment = JSONArray()
            .put(JSONObject.NULL)
            .put(JSONObject.NULL)
            .put(JSONObject.NULL)
            .put(JSONObject.NULL)
            .put(JSONObject.NULL)
            .put(JSONArray().put("files/abc123"))
        val text = JSONArray().put(JSONObject.NULL).put("old prompt")
        val content = JSONArray().put(JSONArray().put(attachment).put(text)).put("user")
        val root = JSONArray()
            .put("models/gemini-test")
            .put(JSONArray().put(content))
            .put(JSONObject.NULL)
            .put(JSONArray())
            .put("snapshot")

        val rewritten = JSONArray(AiStudioWireCodec.rewrite(root.toString(), prompt = "new prompt"))
        val parts = rewritten.getJSONArray(1).getJSONArray(0).getJSONArray(0)

        assertEquals("files/abc123", parts.getJSONArray(0).getJSONArray(5).getString(0))
        assertEquals("new prompt", parts.getJSONArray(1).getString(1))
    }

    @Test
    fun decode_readsPromptWithoutMutatingBody() {
        val raw = "[\"models/gemini-test\",[[[[null,\"hello\"]],\"user\"]],null,[],\"snapshot\"]"

        val decoded = AiStudioWireCodec.decode(raw)

        assertEquals("models/gemini-test", decoded.model)
        assertEquals("hello", decoded.prompt)
        assertEquals("snapshot", decoded.snapshot)
        assertTrue(decoded.shape.valid)
    }

    private fun basicRequest(prompt: String): JSONArray = JSONArray()
        .put("models/gemini-test")
        .put(
            JSONArray().put(
                JSONArray()
                    .put(JSONArray().put(JSONArray().put(JSONObject.NULL).put(prompt)))
                    .put("user"),
            ),
        )
        .put(JSONObject.NULL)
        .put(JSONArray())
        .put("snapshot")
}
