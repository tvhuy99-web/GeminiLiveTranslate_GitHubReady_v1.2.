package com.oai.geminilivetranslate.core

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Internal R17 backend policy. There is deliberately no settings UI yet.
 *
 * The experiment branch prefers the authenticated AI Studio Web Session for Live translation and
 * falls back to the regular Gemini API when a real API key exists and the Web Session cannot be
 * bootstrapped. The circuit breaker is used only when an API fallback actually exists. A device
 * whose only usable Live backend is AI Studio must remain eligible for TranslationService's normal
 * reconnect/backoff path instead of being locked out for five minutes.
 */
object AiStudioLiveBackendPolicy {
    const val VERSION = "2026-09-03-r17.3-web-only-reconnect-policy"
    const val AI_STUDIO_SENTINEL = "__AI_STUDIO_WEB_SESSION__"

    private const val PREFS = "r17_live_backend_internal"
    private const val KEY_ENABLED = "prefer_ai_studio_web_session"
    private const val KEY_API_FALLBACK = "allow_api_fallback"
    private const val DEFAULT_ENABLED = true
    private const val DEFAULT_API_FALLBACK = true
    private const val CIRCUIT_BREAKER_MS = 5 * 60_000L

    private val disabledUntilElapsed = AtomicLong(0L)

    fun preferAiStudio(context: Context): Boolean {
        val enabled = configuredToPreferAiStudio(context)
        return enabled && SystemClock.elapsedRealtime() >= disabledUntilElapsed.get()
    }

    fun configuredToPreferAiStudio(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun allowApiFallback(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_API_FALLBACK, DEFAULT_API_FALLBACK)

    /**
     * Lets TranslationService start a Live session without requiring an app-owned Gemini API key.
     * This sentinel is never sent to Google. GeminiLiveClient consumes it only as a local routing
     * marker and uses the page-owned authenticated AI Studio session instead.
     */
    fun liveCredential(realApiKey: String?): String? =
        realApiKey?.takeIf(String::isNotBlank) ?: AI_STUDIO_SENTINEL

    fun isSentinel(value: String?): Boolean = value == AI_STUDIO_SENTINEL

    /**
     * Circuit-break only when there is another backend to use. If AI Studio is the sole backend,
     * TranslationService already supplies reconnect delays, so disabling AI Studio would convert a
     * recoverable bootstrap failure into AI_STUDIO_BACKEND_COOLDOWN_AND_NO_API_KEY.
     */
    fun recordAiStudioFailure(hasApiFallback: Boolean) {
        if (hasApiFallback) {
            disabledUntilElapsed.set(SystemClock.elapsedRealtime() + CIRCUIT_BREAKER_MS)
        } else {
            disabledUntilElapsed.set(0L)
        }
    }

    fun clearCircuitBreaker() {
        disabledUntilElapsed.set(0L)
    }

    fun circuitBreakerRemainingMs(): Long =
        (disabledUntilElapsed.get() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
}
