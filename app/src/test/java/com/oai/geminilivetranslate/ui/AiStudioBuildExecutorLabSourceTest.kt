package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioBuildExecutorLabSourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull { it.isFile }?.readText()
        ?: error("Không tìm thấy source để kiểm tra: $path")

    @Test
    fun buildExecutorLabUsesDirectProtocolWithoutWebView() {
        val source = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioBuildExecutorLabActivity.kt")
        assertTrue(source.contains("/api/bridge/health"))
        assertTrue(source.contains("/api/bridge/generate"))
        assertTrue(source.contains("/api/bridge/stream"))
        assertTrue(source.contains("text/event-stream"))
        assertTrue(source.contains("x-bridge-token"))
        assertTrue(source.contains("HttpURLConnection"))
        assertTrue(!source.contains("WebView("))
        assertTrue(!source.contains("evaluateJavascript"))
    }

    @Test
    fun manifestKeepsExperimentIsolated() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioBuildExecutorLabActivity"))
        assertTrue(manifest.contains("android:label=\"AI Studio Build Executor Lab\""))
    }
}
