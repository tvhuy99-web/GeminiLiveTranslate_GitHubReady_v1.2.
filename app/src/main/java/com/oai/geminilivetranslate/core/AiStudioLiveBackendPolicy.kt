package com.oai.geminilivetranslate.core

import android.content.Context

/** Official routing policy for the two user-selectable connection modes. */
object AiStudioLiveBackendPolicy {
    const val VERSION = "2026-09-04-production-explicit-connection-mode"
    const val AI_STUDIO_SENTINEL = "__AI_STUDIO_WEB_SESSION__"

    fun connectionMode(context: Context): String = AiConnectionModeStore(context).load()

    fun preferAiStudio(context: Context): Boolean =
        connectionMode(context) == AiConnectionModeStore.MODE_AI_STUDIO

    fun configuredToPreferAiStudio(context: Context): Boolean = preferAiStudio(context)

    /** Explicit mode selection is strict. Never silently cross from AI Studio to API-key mode. */
    fun allowApiFallback(context: Context): Boolean = false

    /**
     * Lets TranslationService pass its existing nonblank-credential gate in AI Studio mode.
     * The sentinel is a local routing marker only and is never emitted to the network.
     */
    fun liveCredential(realApiKey: String?): String? =
        realApiKey?.takeIf(String::isNotBlank) ?: AI_STUDIO_SENTINEL

    fun isSentinel(value: String?): Boolean = value == AI_STUDIO_SENTINEL

    fun recordAiStudioFailure(hasApiFallback: Boolean) = Unit
    fun clearCircuitBreaker() = Unit
    fun circuitBreakerRemainingMs(): Long = 0L
}
