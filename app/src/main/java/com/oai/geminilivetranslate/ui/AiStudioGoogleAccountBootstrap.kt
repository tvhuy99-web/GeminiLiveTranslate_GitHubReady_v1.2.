package com.oai.geminilivetranslate.ui

import android.content.Context
import android.net.Uri

/**
 * Small, intentionally conservative bridge between Android's user-selected Google account and the
 * AI Studio web login flow.
 *
 * We never request an auth token from AccountManager and never copy Android credentials/cookies into
 * WebView. The selected account name is stored locally only as a one-shot login hint. The WebView
 * still authenticates with Google's own web surface.
 */
object AiStudioGoogleAccountBootstrap {
    const val VERSION = "2026-09-02-r12.1-google-account-hint"

    private const val PREFS = "ai_studio_google_account_bootstrap"
    private const val KEY_ACCOUNT_NAME = "selected_google_account"
    private const val KEY_PENDING = "pending_web_bootstrap"
    private const val AI_STUDIO_NEW_CHAT = "https://aistudio.google.com/prompts/new_chat"

    fun arm(context: Context, accountName: String) {
        val normalized = accountName.trim()
        if (normalized.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT_NAME, normalized)
            .putBoolean(KEY_PENDING, true)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun selectedAccount(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT_NAME, "")
            .orEmpty()
            .trim()

    /** Returns a one-shot Google AccountChooser URL, then clears only the pending flag. */
    fun consumeStartUrl(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val account = prefs.getString(KEY_ACCOUNT_NAME, "").orEmpty().trim()
        prefs.edit().putBoolean(KEY_PENDING, false).apply()
        if (account.isBlank()) return null

        return Uri.Builder()
            .scheme("https")
            .authority("accounts.google.com")
            .appendPath("AccountChooser")
            .appendQueryParameter("Email", account)
            .appendQueryParameter("continue", AI_STUDIO_NEW_CHAT)
            .build()
            .toString()
    }

    fun maskedAccount(context: Context): String = mask(selectedAccount(context))

    fun mask(account: String): String {
        val value = account.trim()
        val at = value.indexOf('@')
        if (at <= 1) return if (value.isBlank()) "" else "***"
        val local = value.substring(0, at)
        val domain = value.substring(at)
        return local.take(1) + "***" + domain
    }
}
