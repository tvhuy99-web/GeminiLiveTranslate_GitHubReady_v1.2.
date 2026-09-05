package com.oai.geminilivetranslate.core

import android.content.Context

/** Official user-selectable AI connection mode. */
class AiConnectionModeStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY_MODE, MODE_API_KEY)
        .orEmpty()
        .takeIf { it == MODE_API_KEY || it == MODE_AI_STUDIO }
        ?: MODE_API_KEY

    fun save(mode: String): String {
        val safe = mode.takeIf { it == MODE_API_KEY || it == MODE_AI_STUDIO } ?: MODE_API_KEY
        prefs.edit().putString(KEY_MODE, safe).apply()
        return safe
    }

    fun usesAiStudio(): Boolean = load() == MODE_AI_STUDIO

    companion object {
        const val MODE_API_KEY = "api_key"
        const val MODE_AI_STUDIO = "ai_studio"
        const val LABEL_API_KEY = "API Key"
        const val LABEL_AI_STUDIO = "Tài khoản Google / AI Studio"

        private const val PREFS_NAME = "gemini_translate_connection"
        private const val KEY_MODE = "connectionMode"
    }
}
