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
        assertTrue(scripts.contains("resp.clone"))
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

    @Test
    fun latestLogCanBeSharedWithoutOpeningWebViewOrBlockingUi() {
        val manifest = source("src/main/AndroidManifest.xml")
        val share = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLogShareActivity.kt")
        val log = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt")
        assertTrue(manifest.contains(".ui.AiStudioWebSessionLogShareActivity"))
        assertTrue(manifest.contains("Chia sẻ log AI Studio"))
        assertTrue(share.contains("createLatestBundle"))
        assertTrue(share.contains("Intent.ACTION_SEND"))
        assertTrue(share.contains("Chia sẻ log ZIP gần nhất"))
        assertTrue(share.contains("Thread({"))
        assertTrue(share.contains("runOnUiThread"))
        assertFalse(share.contains("import android.webkit.WebView"))
        assertFalse(share.contains("WebView(this"))
        assertTrue(log.contains("latestSessionDirectory"))
        assertTrue(log.contains("bytesAtSnapshot"))
        assertTrue(log.contains("remaining = entry.bytesAtSnapshot"))
        assertTrue(log.contains("while (remaining > 0L)"))
    }
}
