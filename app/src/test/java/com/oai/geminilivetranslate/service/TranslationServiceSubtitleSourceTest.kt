package com.oai.geminilivetranslate.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslationServiceSubtitleSourceTest {
    @Test
    fun firstSubtitleDeltaIsAlwaysAppendedToTranscript() {
        val source = sequenceOf(
            File("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt"),
            File("app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Không tìm thấy TranslationService.kt để kiểm tra source guard")

        assertFalse(
            "Biểu thức cũ bỏ mất delta khi transcript đang rỗng",
            source.contains("current.transcript + if (current.transcript.isBlank()) \"\" else \" \" + delta"),
        )
        assertTrue(
            "Transcript phải nối separator và delta thành các toán hạng riêng biệt",
            source.contains("current.transcript + separator + delta"),
        )
    }
}
