package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionLabSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun labHasItsOwnLauncherAndDocumentStartProbe() {
        val manifest = source("src/main/AndroidManifest.xml")
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLabActivity.kt")
        val gradle = source("build.gradle.kts")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionLabActivity"))
        assertTrue(manifest.contains("AI Studio Web Session Lab"))
        assertTrue(activity.contains("WebViewCompat.addDocumentStartJavaScript"))
        assertTrue(activity.contains("WebViewFeature.DOCUMENT_START_SCRIPT"))
        assertTrue(gradle.contains("androidx.webkit:webkit:1.16.0"))
    }

    @Test
    fun probeCapturesGenerateNetworkResultWithoutExportingCredentialValues() {
        val scripts = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLabScripts.kt")
        assertTrue(scripts.contains("GENERATE_START"))
        assertTrue(scripts.contains("GENERATE_RESULT"))
        assertTrue(scripts.contains("MakerSuiteService"))
        assertTrue(scripts.contains("response.clone"))
        assertTrue(scripts.contains("callStack"))
        assertTrue(scripts.contains("headerSummary"))
        assertFalse(scripts.contains("GEMINI_API_KEY"))
        assertFalse(scripts.contains("asia-southeast1.run.app"))
    }

    @Test
    fun firstExperimentUsesTrustedTouchButReadsResponseFromNetwork() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLabActivity.kt")
        assertTrue(activity.contains("dispatchTouchEvent"))
        assertTrue(activity.contains("SOURCE_TOUCHSCREEN"))
        assertTrue(activity.contains("getLastSafeResponse"))
        assertTrue(activity.contains("Network GenerateContent"))
        assertFalse(activity.contains("GEMINI_API_KEY"))
        assertFalse(activity.contains("Cloud Run"))
        assertFalse(activity.contains("/api/bridge/generate"))
    }
}
