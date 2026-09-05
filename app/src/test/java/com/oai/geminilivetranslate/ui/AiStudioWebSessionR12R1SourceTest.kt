package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR12R1SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun r121KeepsProgressWatchdogAndTerminal2xxCompletion() {
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(executor.contains("2026-09-05-web-session-r12.6-direct-stt-page"))
        assertTrue(executor.contains("FIRST_PROGRESS_TIMEOUT_MS = 300_000L"))
        assertTrue(executor.contains("PROGRESS_IDLE_TIMEOUT_MS = 60_000L"))
        assertTrue(executor.contains("PROGRESS_HARD_TIMEOUT_MS = 900_000L"))
        assertTrue(executor.contains("R12_PROGRESS_ACTIVITY"))
        assertTrue(executor.contains("R12_PROGRESS_WATCHDOG"))
        assertTrue(executor.contains("R12_TIMEOUT_FIRED"))
        assertTrue(executor.contains("R12_TERMINAL_RESULT"))
        assertTrue(executor.contains("terminal2xx"))
        assertTrue(executor.contains("NATIVE_SUBMIT_MAX_RETRIES = 3"))
        assertTrue(executor.contains("ATTACHMENT_TIMEOUT_MS = 300_000L"))
        assertTrue(executor.contains("ATTACHMENT_READY_STABLE_SCANS = 3"))
        assertTrue(executor.contains("MANUAL_READINESS_POLL_MS = 1_000L"))
        assertTrue(executor.contains("R19_MANUAL_VIDEO_ARMED"))
        assertTrue(executor.contains("AiStudioDebugWebViewHost.retain"))
    }

    @Test
    fun r13RemovesAccountChooserAndOnlyClearsLegacyHint() {
        val manifest = source("src/main/AndroidManifest.xml")
        val bootstrap = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioGoogleAccountBootstrap.kt")
        val launcherExists = sequenceOf(
            File("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12LauncherActivity.kt"),
            File("app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12LauncherActivity.kt"),
        ).any(File::isFile)

        assertFalse(launcherExists)
        assertFalse(manifest.contains("AiStudioWebSessionR12LauncherActivity"))
        assertFalse(manifest.contains("android.permission.GET_ACCOUNTS"))
        assertTrue(bootstrap.contains("2026-09-02-r13-account-bootstrap-removed"))
        assertTrue(bootstrap.contains("fun consumeStartUrl"))
        assertTrue(bootstrap.contains("return null"))
        assertFalse(bootstrap.contains("AccountManager"))
        assertFalse(bootstrap.contains("accounts.google.com"))
        assertFalse(bootstrap.contains("AccountChooser"))
        assertFalse(bootstrap.contains("KEY_ACCOUNT_NAME"))
        assertFalse(bootstrap.contains("getAuthToken"))
        assertFalse(bootstrap.contains("document.cookie"))
    }
}
