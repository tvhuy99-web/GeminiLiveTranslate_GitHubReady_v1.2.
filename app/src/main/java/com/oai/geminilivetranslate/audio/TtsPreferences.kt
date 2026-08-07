package com.oai.geminilivetranslate.audio

import android.content.Context
import java.util.Locale

data class TtsSelection(
    val enginePackage: String = "",
    val languageTag: String = DEFAULT_TTS_LANGUAGE,
    val voiceName: String = "",
)

class TtsPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TtsSelection = sanitize(
        TtsSelection(
            enginePackage = prefs.getString(KEY_ENGINE, "").orEmpty(),
            languageTag = prefs.getString(KEY_LANGUAGE, DEFAULT_TTS_LANGUAGE).orEmpty(),
            voiceName = prefs.getString(KEY_VOICE, "").orEmpty(),
        ),
    )

    fun save(selection: TtsSelection) {
        val safe = sanitize(selection)
        prefs.edit()
            .putString(KEY_ENGINE, safe.enginePackage)
            .putString(KEY_LANGUAGE, safe.languageTag)
            .putString(KEY_VOICE, safe.voiceName)
            .apply()
    }

    fun reset() = save(TtsSelection())

    fun clear(): Boolean = prefs.edit().clear().commit()

    companion object {
        const val DEFAULT_TTS_LANGUAGE = "vi-VN"
        private const val PREFS_NAME = "gemini_translate_tts_prefs"
        private const val KEY_ENGINE = "enginePackage"
        private const val KEY_LANGUAGE = "languageTag"
        private const val KEY_VOICE = "voiceName"

        fun sanitize(input: TtsSelection): TtsSelection {
            val tag = normalizeLanguageTag(input.languageTag) ?: DEFAULT_TTS_LANGUAGE
            return input.copy(
                enginePackage = input.enginePackage.trim(),
                languageTag = tag,
                voiceName = input.voiceName.trim(),
            )
        }

        fun normalizeLanguageTag(raw: String): String? {
            val cleaned = raw.trim().replace('_', '-')
            if (cleaned.isBlank()) return null
            val locale = Locale.forLanguageTag(cleaned)
            if (locale.language.isBlank() || locale.language == "und") return null
            return locale.toLanguageTag().ifBlank { null }
        }
    }
}
