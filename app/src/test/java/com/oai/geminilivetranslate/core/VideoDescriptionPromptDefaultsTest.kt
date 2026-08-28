package com.oai.geminilivetranslate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDescriptionPromptDefaultsTest {
    @Test
    fun renderReplacesDurationVariableInCustomPrompt() {
        val rendered = VideoDescriptionPromptDefaults.render(
            "Đầu {{VIDEO_DURATION_SECONDS}} giữa {{VIDEO_DURATION_SECONDS}} cuối",
            755.578,
        )

        assertTrue(rendered.contains("755.578"))
        assertFalse(rendered.contains(VideoDescriptionPromptDefaults.VIDEO_DURATION_VARIABLE))
    }

    @Test
    fun defaultTimelineAndSummaryKeepRequiredDurationVariable() {
        assertTrue(
            VideoDescriptionPromptDefaults.TIMELINE.contains(
                VideoDescriptionPromptDefaults.VIDEO_DURATION_VARIABLE
            )
        )
        assertTrue(
            VideoDescriptionPromptDefaults.SUMMARY.contains(
                VideoDescriptionPromptDefaults.VIDEO_DURATION_VARIABLE
            )
        )
    }
}
