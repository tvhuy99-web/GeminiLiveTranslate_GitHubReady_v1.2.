package com.oai.geminilivetranslate.core

object AiApiEndpointRules {
    fun proxyChatEndpoint(
        raw: String,
        defaultUrl: String = AiApiSettingsStore.DEFAULT_PROXY_URL,
    ): String = baseUrl(raw, defaultUrl) + "/chat/completions"

    fun proxyModelsEndpoint(
        raw: String,
        defaultUrl: String = AiApiSettingsStore.DEFAULT_PROXY_URL,
    ): String = baseUrl(raw, defaultUrl) + "/models"

    private fun baseUrl(raw: String, defaultUrl: String): String {
        var value = raw.trim().ifBlank { defaultUrl.trim() }.removeSuffix("/")
        val suffixes = listOf(
            "/chat/completions",
            "/responses",
            "/models",
        )
        suffixes.forEach { suffix ->
            if (value.endsWith(suffix)) {
                value = value.removeSuffix(suffix).removeSuffix("/")
                return@forEach
            }
        }
        return value
    }
}
