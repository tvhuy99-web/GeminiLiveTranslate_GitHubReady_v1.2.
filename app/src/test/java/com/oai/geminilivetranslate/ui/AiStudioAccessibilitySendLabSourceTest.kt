package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioAccessibilitySendLabSourceTest {
    private fun source(path: String): String = sequenceOf(
        File(path),
        File("app/$path"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Không tìm thấy source để kiểm tra: $path")

    @Test
    fun talkBackLabIsSeparateLauncherAndLogsAccessibilityState() {
        val manifest = source("src/main/AndroidManifest.xml")
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioAccessibilitySendLabActivity.kt")
        assertTrue(manifest.contains(".ui.AiStudioAccessibilitySendLabActivity"))
        assertTrue(manifest.contains("android:label=\"AI Studio TalkBack Lab\""))
        assertTrue(activity.contains("AccessibilityManager"))
        assertTrue(activity.contains("isTouchExplorationEnabled"))
        assertTrue(activity.contains("ACCESSIBILITY_STATE"))
        assertTrue(activity.contains("TALKBACK_MANUAL"))
    }

    @Test
    fun labTestsFourIndependentSendPaths() {
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioAccessibilitySendLabActivity.kt")
        val scripts = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioAccessibilitySendLabScripts.kt")
        assertTrue(activity.contains("JS_HTMLELEMENT_CLICK"))
        assertTrue(activity.contains("JS_MOUSE_EVENTS"))
        assertTrue(activity.contains("JS_POINTER_EVENTS"))
        assertTrue(activity.contains("ANDROID_NATIVE_MOTION_EVENT"))
        assertTrue(activity.contains("webView.dispatchTouchEvent(down)"))
        assertTrue(scripts.contains("lab.sendClick"))
        assertTrue(scripts.contains("lab.sendMouse"))
        assertTrue(scripts.contains("lab.sendPointer"))
        assertTrue(scripts.contains("lab.sendRect"))
    }

    @Test
    fun rpcResultsAreCorrelatedWithSendMethodAndErrorBodies() {
        val scripts = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioAccessibilitySendLabScripts.kt")
        val activity = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioAccessibilitySendLabActivity.kt")
        assertTrue(scripts.contains("sendMethod:meta.sendMethod"))
        assertTrue(scripts.contains("RPC_START"))
        assertTrue(scripts.contains("RPC_END"))
        assertTrue(scripts.contains("GenerateContent"))
        assertTrue(scripts.contains("this.responseText.substring"))
        assertTrue(activity.contains("GENERATE_OUTCOME"))
    }

    @Test
    fun domInstrumentationCapturesTrustedAccessibilityRelevantEvents() {
        val scripts = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioAccessibilitySendLabScripts.kt")
        assertTrue(scripts.contains("isTrusted:!!ev.isTrusted"))
        assertTrue(scripts.contains("visibilitychange"))
        assertTrue(scripts.contains("pointerdown"))
        assertTrue(scripts.contains("focusin"))
        assertTrue(scripts.contains("METHOD_ARMED"))
    }
}
