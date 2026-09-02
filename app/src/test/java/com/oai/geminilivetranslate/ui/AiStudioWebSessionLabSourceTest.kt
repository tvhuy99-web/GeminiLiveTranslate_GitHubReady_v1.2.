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
        assertTrue(scripts.contains("2026-09-02-web-session-r2"))
        assertTrue(scripts.contains("GENERATE_START"))
        assertTrue(scripts.contains("GENERATE_RESULT"))
        assertTrue(scripts.contains("MakerSuiteService"))
        assertTrue(scripts.contains("resp.clone"))
        assertTrue(scripts.contains("callStack"))
        assertTrue(scripts.contains("headerSummary"))
        assertTrue(scripts.contains("responseText:raw.slice(0,16000)"))
        assertFalse(scripts.contains("GEMINI_API_KEY"))
        assertFalse(scripts.contains("asia-southeast1.run.app"))
    }

    @Test
    fun xhrCompletionUsesNativeEventListenerAndAllTerminalSignals() {
        val scripts = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLabScripts.kt")
        assertTrue(scripts.contains("window.EventTarget.prototype.addEventListener"))
        assertTrue(scripts.contains("nativeEventAdd.call(target"))
        assertTrue(scripts.contains("XHR_LIFECYCLE"))
        assertTrue(scripts.contains("readystatechange"))
        assertTrue(scripts.contains("loadend"))
        assertTrue(scripts.contains("timeout"))
        assertTrue(scripts.contains("arraybuffer"))
        assertTrue(scripts.contains("TextDecoder('utf-8')"))
        assertTrue(scripts.contains("xhr.response.text"))
        assertTrue(scripts.contains("lastXhrLifecycle"))
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
    fun latestLogIsShownOnScreenAndCopiedWithoutZipOrShareSheet() {
        val manifest = source("src/main/AndroidManifest.xml")
        val viewer = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLogShareActivity.kt")
        val log = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt")

        assertTrue(manifest.contains(".ui.AiStudioWebSessionLogShareActivity"))
        assertTrue(manifest.contains("Nhật ký AI Studio"))
        assertTrue(viewer.contains("createLatestTextReport"))
        assertTrue(viewer.contains("reportView.text = text"))
        assertTrue(viewer.contains("Sao chép toàn bộ"))
        assertTrue(viewer.contains("ClipboardManager"))
        assertTrue(viewer.contains("setPrimaryClip"))
        assertTrue(viewer.contains("Thread({"))
        assertTrue(viewer.contains("runOnUiThread"))
        assertFalse(viewer.contains("Intent.ACTION_SEND"))
        assertFalse(viewer.contains("FileProvider"))
        assertFalse(viewer.contains("createLatestBundle"))
        assertFalse(viewer.contains("import android.webkit.WebView"))

        assertTrue(log.contains("createLatestTextReport"))
        assertTrue(log.contains("MAX_REPORT_CHARS"))
        assertTrue(log.contains("readTailAtSnapshot"))
        assertTrue(log.contains("snapshotBytes"))
    }
}
