package com.oai.geminilivetranslate.core

import android.content.Context


object AiStudioLiveBackendPolicy {
    const val VERSION = "2026-09-04-production-explicit-connection-mode"
    const val AI_STUDIO_SENTINEL = "__AI_STUDIO_WEB_SESSION__"

    fun connectionMode(context: Context): String = AiConnectionModeStore(context).load()

    fun preferAiStudio(context: Context): Boolean =
        connectionMode(context) == AiConnectionModeStore.MODE_AI_STUDIO

    fun configuredToPreferAiStudio(context: Context): Boolean = preferAiStudio(context)


    fun allowApiFallback(context: Context): Boolean = false


    fun liveCredential(realApiKey: String?): String? =
        realApiKey?.takeIf(String::isNotBlank) ?: AI_STUDIO_SENTINEL

    fun isSentinel(value: String?): Boolean = value == AI_STUDIO_SENTINEL

    fun recordAiStudioFailure(hasApiFallback: Boolean) = Unit
    fun clearCircuitBreaker() = Unit
    fun circuitBreakerRemainingMs(): Long = 0L
}
