package com.oai.geminilivetranslate.core.aistudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioIncrementalStreamParserTest {
    @Test
    fun parses_chunk_split_across_network_fragments() {
        val parser = AiStudioIncrementalStreamParser()
        val out = mutableListOf<String>()
        out += parser.feed("[[[null,\"he").map { it.toString() }
        out += parser.feed("llo\"]]]").map { it.toString() }
        assertEquals(listOf("[null,\"hello\"]"), out)
    }

    @Test
    fun handles_fragmented_xssi_prefix() {
        val parser = AiStudioIncrementalStreamParser()
        val out = mutableListOf<String>()
        out += parser.feed(")]").map { it.toString() }
        out += parser.feed("}'\n").map { it.toString() }
        out += parser.feed("[[[null,\"hello\"]]]").map { it.toString() }
        assertEquals(listOf("[null,\"hello\"]"), out)
    }

    @Test
    fun reset_clears_partial_state() {
        val parser = AiStudioIncrementalStreamParser()
        parser.feed("[[[null,\"partial")
        assertTrue(parser.bufferedChars() > 0)
        parser.reset()
        assertEquals(0, parser.bufferedChars())
    }
}
