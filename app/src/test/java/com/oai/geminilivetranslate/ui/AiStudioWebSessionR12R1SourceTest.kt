package com.oai.geminilivetranslate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiStudioWebSessionR12R1SourceTest {
    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")

    @Test
    fun accountChooserUsesSystemGoogleAccountSelectionWithoutCredentialExtraction() {
        val launcher = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12LauncherActivity.kt")
        val bootstrap = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioGoogleAccountBootstrap.kt")
        val manifest = source("src/main/AndroidManifest.xml")

        assertTrue(launcher.contains("2026-09-02-web-session-r12.1-account-chooser-launcher"))
        assertTrue(launcher.contains("AccountManager.newChooseAccountIntent"))
        assertTrue(launcher.contains("arrayOf(GOOGLE_ACCOUNT_TYPE)"))
        assertTrue(launcher.contains("AccountManager.KEY_ACCOUNT_NAME"))
        assertTrue(launcher.contains("AiStudioGoogleAccountBootstrap.arm"))
        assertTrue(launcher.contains("AiStudioWebSessionR11R2Activity::class.java"))

        assertTrue(bootstrap.contains("2026-09-02-r12.1-google-account-hint"))
        assertTrue(bootstrap.contains("accounts.google.com"))
        assertTrue(bootstrap.contains("AccountChooser"))
        assertTrue(bootstrap.contains("appendQueryParameter(\"Email\""))
        assertTrue(bootstrap.contains("appendQueryParameter(\"continue\""))
        assertTrue(bootstrap.contains("pending_web_bootstrap"))
        assertTrue(bootstrap.contains("consumeStartUrl"))

        assertFalse(manifest.contains("android.permission.GET_ACCOUNTS"))
        assertFalse(launcher.contains("getAuthToken"))
        assertFalse(launcher.contains("peekAuthToken"))
        assertFalse(launcher.contains("getPassword"))
        assertFalse(bootstrap.contains("getAuthToken"))
        assertFalse(bootstrap.contains("document.cookie"))
        assertFalse(bootstrap.contains("Authorization="))
        assertFalse(bootstrap.contains("X-Goog-Api-Key="))
    }

    @Test
    fun executorUsesOneShotHintAndDoesNotLogAccountAddress() {
        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
        assertTrue(executor.contains("AiStudioGoogleAccountBootstrap.consumeStartUrl(appContext)"))
        assertTrue(executor.contains("source=google-account-hint"))
        assertTrue(executor.contains("R12_START_URL"))
        assertFalse(executor.contains("selectedAccount(appContext)"))
        assertFalse(executor.contains("KEY_ACCOUNT_NAME"))
    }
}
