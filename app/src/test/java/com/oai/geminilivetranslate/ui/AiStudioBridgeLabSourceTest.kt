package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioBridgeLabSourceTest {
    private fun source(path: String): String = sequenceOf(
        File(path),
        File("app/$path"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Không tìm thấy source để kiểm tra: $path")

    @Test
    fun labIsIsolatedBehindItsOwnLauncher() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.AiStudioBridgeLabActivity"))
        assertTrue(manifest.contains("android:label=\"AI Studio Bridge Lab\""))
        assertTrue(manifest.contains("android.permission.CAMERA"))
    }

    @Test
    fun webViewHasDeepDiagnosticsAndFileUploadSupport() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioBridgeLabActivity.kt")
        assertTrue(activity.contains("WebView.setWebContentsDebuggingEnabled(true)"))
        assertTrue(activity.contains("addJavascriptInterface(JsBridge(isPopup), JS_BRIDGE_NAME)"))
        assertTrue(activity.contains("override fun shouldInterceptRequest"))
        assertTrue(activity.contains("override fun onRenderProcessGone"))
        assertTrue(activity.contains("override fun onConsoleMessage"))
        assertTrue(activity.contains("override fun onShowFileChooser"))
        assertTrue(activity.contains("COOKIE_STATE"))
        assertTrue(activity.contains("AUTO_A_START"))
        assertTrue(activity.contains("AUTO_B_START"))
    }

    @Test
    fun javascriptProbeProvidesIndependentFallbackStrategies() {
        val scripts = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioBridgeLabScripts.kt")
        assertTrue(scripts.contains("MutationObserver"))
        assertTrue(scripts.contains("bridge.fillSemantic"))
        assertTrue(scripts.contains("bridge.fillExecCommand"))
        assertTrue(scripts.contains("bridge.sendByButton"))
        assertTrue(scripts.contains("bridge.sendByForm"))
        assertTrue(scripts.contains("bridge.sendByEnter"))
        assertTrue(scripts.contains("bridge.installNetworkHooks"))
        assertTrue(scripts.contains("window.fetch = function"))
        assertTrue(scripts.contains("XMLHttpRequest.prototype.open"))
        assertTrue(scripts.contains("LabWebSocket"))
        assertTrue(scripts.contains("bridge.resourceScan"))
    }

    @Test
    fun labLoggerAlwaysPersistsToItsOwnFile() {
        val logger = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioBridgeLabLog.kt")
        assertTrue(logger.contains("aistudio-bridge-lab"))
        assertTrue(logger.contains("eventFile.appendText"))
        assertTrue(logger.contains("AIStudioBridgeLab-"))
        assertTrue(logger.contains("createBundle"))
    }
}
