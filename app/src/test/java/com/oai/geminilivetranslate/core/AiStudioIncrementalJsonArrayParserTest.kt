package com.oai.geminilivetranslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStudioIncrementalJsonArrayParserTest {
    @Test
    fun fragmentedFrameAcrossStringBoundaryIsReassembled() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val first = parser.feed("[[[null,\"he")
        val second = parser.feed("llo\"]]]")

        assertTrue(first.ok)
        assertTrue(first.frames.isEmpty())
        assertTrue(second.ok)
        assertEquals(1, second.frames.size)
        assertEquals("hello", second.frames.single().getString(1))
    }

    @Test
    fun fragmentedXssiPrefixIsIgnoredBeforeJson() {
        val parser = AiStudioIncrementalJsonArrayParser()

        assertTrue(parser.feed(")]" ).frames.isEmpty())
        assertTrue(parser.feed("}'\n").frames.isEmpty())
        val result = parser.feed("[[[null,\"hello\"]]]")

        assertTrue(result.ok)
        assertEquals(1, result.frames.size)
        assertEquals("hello", result.frames.single().getString(1))
    }

    @Test
    fun bracketsInsideQuotedTextDoNotChangeNestingDepth() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val result = parser.feed("[[[null,\"a [bracket] and ] text\"]]]")

        assertTrue(result.ok)
        assertEquals(1, result.frames.size)
        assertEquals("a [bracket] and ] text", result.frames.single().getString(1))
    }

    @Test
    fun escapedQuoteAndBackslashCanCrossFragments() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val first = parser.feed("[[[null,\"say \\")
        val second = parser.feed("\"hi\\\" on C:\\\\temp\"]]]")

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(1, second.frames.size)
        assertEquals("say \"hi\" on C:\\temp", second.frames.single().getString(1))
    }

    @Test
    fun multipleFramesAreEmittedWithoutLosingOuterDepth() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val result = parser.feed("[[[null,\"one\"],[null,\"two\"]]]")

        assertTrue(result.ok)
        assertEquals(2, result.frames.size)
        assertEquals("one", result.frames[0].getString(1))
        assertEquals("two", result.frames[1].getString(1))
    }

    @Test
    fun malformedClosingBracketFailsClosedAndResetsParser() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val bad = parser.feed("]")
        assertFalse(bad.ok)
        assertTrue(bad.reset)
        assertEquals("UNBALANCED_CLOSE_BRACKET", bad.error)
        assertEquals(0, parser.currentDepth())
        assertEquals(0, parser.bufferedChars())

        val recovered = parser.feed("[[[null,\"recovered\"]]]")
        assertTrue(recovered.ok)
        assertEquals("recovered", recovered.frames.single().getString(1))
    }

    @Test
    fun activeFrameOverBufferLimitFailsInsteadOfTruncatingJson() {
        val parser = AiStudioIncrementalJsonArrayParser(maxBufferedChars = 1_024)
        val largePartial = "[[[null,\"" + "x".repeat(1_100)

        val result = parser.feed(largePartial)

        assertFalse(result.ok)
        assertTrue(result.reset)
        assertEquals("BUFFER_LIMIT_EXCEEDED", result.error)
        assertEquals(0, parser.bufferedChars())
    }

    @Test
    fun finishAcceptsCompleteStreamAndResetsParser() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val result = parser.feed("[[[null,\"done\"]]]")
        val finished = parser.finish()

        assertTrue(result.ok)
        assertEquals(1, result.frames.size)
        assertTrue(finished.ok)
        assertTrue(finished.reset)
        assertEquals(0, parser.currentDepth())
        assertEquals(0, parser.bufferedChars())
    }

    @Test
    fun finishRejectsTruncatedStringAndResetsParser() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val partial = parser.feed("[[[null,\"unfinished")
        val finished = parser.finish()

        assertTrue(partial.ok)
        assertFalse(finished.ok)
        assertTrue(finished.reset)
        assertEquals("INCOMPLETE_STRING", finished.error)
        assertEquals(0, parser.currentDepth())
        assertEquals(0, parser.bufferedChars())
    }

    @Test
    fun finishRejectsUnclosedOuterWrapperAfterValidFrame() {
        val parser = AiStudioIncrementalJsonArrayParser()

        val partial = parser.feed("[[[null,\"frame\"]]")
        val finished = parser.finish()

        assertTrue(partial.ok)
        assertEquals(1, partial.frames.size)
        assertFalse(finished.ok)
        assertEquals("INCOMPLETE_JSON_STREAM", finished.error)
        assertTrue(finished.reset)
    }

    @Test
    fun finishRejectsFragmentedXssiPrefix() {
        val parser = AiStudioIncrementalJsonArrayParser()

        assertTrue(parser.feed(")]" ).ok)
        val finished = parser.finish()

        assertFalse(finished.ok)
        assertEquals("INCOMPLETE_XSSI_PREFIX", finished.error)
        assertTrue(finished.reset)
    }
}
