package com.oai.geminilivetranslate.ui

import android.content.Context

/**
 * R13 compatibility cleanup for the removed Android Google-account chooser experiment.
 *
 * Older R12.1 installs may still have a pending account hint in SharedPreferences. The executor
 * calls consumeStartUrl() once at startup, so this tiny shim clears that legacy state and always
 * returns null. It cannot select accounts, store new account names, request tokens or redirect to
 * the Google sign-in site.
 */
object AiStudioGoogleAccountBootstrap {
    const val VERSION = "2026-09-02-r13-account-bootstrap-removed"

    private const val LEGACY_PREFS = "ai_studio_google_account_bootstrap"

    fun consumeStartUrl(context: Context): String? {
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        return null
    }
}
