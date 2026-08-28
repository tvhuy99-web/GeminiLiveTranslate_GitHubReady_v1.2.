package com.oai.geminilivetranslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {
    @Test
    fun parsesStandardSrtWithSpacesAroundArrow() {
        val result = SrtParser.parse(
            """
1
00:00:01,250 --> 00:00:03,500
Hello world.

2
00:00:04.000 --> 00:00:05.250
Second line
continues here.
            """.trimIndent()
        )

        assertEquals(2, result.cues.size)
        assertEquals(1_250L, result.cues[0].startMs)
        assertEquals(3_500L, result.cues[0].endMs)
        assertEquals("Hello world.", result.cues[0].text)
        assertEquals(4_000L, result.cues[1].startMs)
        assertEquals(5_250L, result.cues[1].endMs)
        assertEquals("Second line\ncontinues here.", result.cues[1].text)
        assertEquals(0, result.skippedBlocks)
    }

    @Test
    fun skipsInvalidBlocksAndRenumbersCuesSequentially() {
        val result = SrtParser.parse(
            """
9
00:00:02,000 --> 00:00:01,000
Invalid range

25
00:00:03,000 --> 00:00:04,000
Valid
            """.trimIndent()
        )

        assertEquals(1, result.cues.size)
        assertEquals(1, result.cues[0].index)
        assertEquals("Valid", result.cues[0].text)
        assertEquals(1, result.skippedBlocks)
        assertTrue(result.normalizedChars > 0)
    }
}
