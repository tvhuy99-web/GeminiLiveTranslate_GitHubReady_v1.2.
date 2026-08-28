package com.oai.geminilivetranslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDescriptionTimelineRulesTest {
    @Test
    fun acceptsSequentialItemsInsideDurationAndFifteenSecondLimit() {
        val result = VideoDescriptionTimelineRules.validate(
            items = listOf(
                VideoDescriptionTimelineRules.Item(1, 0.0, 10.0, "Mở đầu"),
                VideoDescriptionTimelineRules.Item(2, 10.0, 24.5, "Nhân vật bước vào phòng"),
                VideoDescriptionTimelineRules.Item(3, 24.5, 30.0, "Máy quay chuyển cảnh"),
            ),
            durationSeconds = 30.0,
        )

        assertTrue(result.valid)
        assertTrue(result.errors.isEmpty())
        assertEquals(30.0, result.lastEndSeconds, 0.0001)
        assertEquals(1.0, result.endCoverage, 0.0001)
    }

    @Test
    fun rejectsBrokenIndexLongCueOutOfRangeAndBlankText() {
        val result = VideoDescriptionTimelineRules.validate(
            items = listOf(
                VideoDescriptionTimelineRules.Item(1, 0.0, 16.0, "Quá dài"),
                VideoDescriptionTimelineRules.Item(3, 14.0, 31.0, ""),
            ),
            durationSeconds = 30.0,
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("length=") })
        assertTrue(result.errors.any { it.contains("expected=2") })
        assertTrue(result.errors.any { it.contains("duration=30.0") })
        assertTrue(result.errors.any { it.contains("text-blank") })
    }

    @Test
    fun rejectsReversedTimelineBeyondTolerance() {
        val result = VideoDescriptionTimelineRules.validate(
            items = listOf(
                VideoDescriptionTimelineRules.Item(1, 12.0, 15.0, "Một"),
                VideoDescriptionTimelineRules.Item(2, 10.0, 12.0, "Hai"),
            ),
            durationSeconds = 20.0,
        )

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("time-order") })
    }
}
