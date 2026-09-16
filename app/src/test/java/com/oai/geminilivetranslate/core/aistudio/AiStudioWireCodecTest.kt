package com.oai.geminilivetranslate.core.aistudio

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioWireCodecTest {
    private val sample = """["models/gemini-3.5-flash",[[[[null,"old"]],"user"]],null,[null,null,null,128,0.5,0.8,16],"snapshot-1",null,null]"""

    @Test
    fun inspect_accepts_expected_shape() {
        val shape = AiStudioWireCodec.inspect(sample)
        assertTrue(shape.valid)
        assertEquals("models/gemini-3.5-flash", shape.model)
        assertEquals(7, shape.rootLength)
    }

    @Test
    fun rewrite_updates_only_requested_fields() {
        val rewritten = AiStudioWireCodec.rewrite(
            sample,
            AiStudioWireCodec.Mutation(
                model = "gemini-3.5-pro",
                contents = JSONArray("[[[[null,\"new prompt\"]],\"user\"]]"),
                generationConfigOverrides = mapOf(3 to 256, 4 to 0.2),
            ),
        )
        val root = JSONArray(rewritten)
        assertEquals("models/gemini-3.5-pro", root.getString(0))
        assertEquals("new prompt", root.getJSONArray(1).getJSONArray(0).getJSONArray(0).getJSONArray(0).getString(1))
        assertEquals(256, root.getJSONArray(3).getInt(3))
        assertEquals(0.2, root.getJSONArray(3).getDouble(4), 0.0001)
        assertEquals("snapshot-1", root.getString(4))
    }

    @Test
    fun rewrite_can_replace_snapshot_explicitly() {
        val rewritten = AiStudioWireCodec.rewrite(
            sample,
            AiStudioWireCodec.Mutation(snapshot = "snapshot-2", replaceSnapshot = true),
        )
        assertEquals("snapshot-2", JSONArray(rewritten).getString(4))
    }

    @Test(expected = AiStudioWireCodec.ProtocolShapeException::class)
    fun rewrite_refuses_unknown_shape() {
        AiStudioWireCodec.rewrite(
            """["models/gemini-3.5-flash","contents-is-not-array",null,[],null]""",
            AiStudioWireCodec.Mutation(model = "gemini-3.5-pro"),
        )
    }

    @Test
    fun inspect_rejects_non_array_contents() {
        val shape = AiStudioWireCodec.inspect("""["models/gemini-3.5-flash","bad",null,[],null]""")
        assertFalse(shape.valid)
        assertTrue(shape.errors.any { it.contains("CONTENTS_NOT_ARRAY") })
    }
}
