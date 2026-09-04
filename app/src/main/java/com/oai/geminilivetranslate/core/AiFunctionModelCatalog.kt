package com.oai.geminilivetranslate.core

/**
 * Single diagnostic view of the existing function-specific model defaults.
 * This does not change model selection; it prevents future backend work from collapsing
 * unrelated features onto one model.
 */
object AiFunctionModelCatalog {
    fun summary(videoDescriptionModel: String = AppPreferences.VIDEO_DESCRIPTION_MODEL): String =
        "liveTranslate=${AppPreferences.DEFAULT_MODEL} " +
            "liveTranscribe=${AppPreferences.TRANSCRIBE_LIVE_MODEL} " +
            "fileTranscribe=${AppPreferences.TRANSCRIBE_FILE_MODEL} " +
            "subtitleTranslate=${AppPreferences.SUBTITLE_TRANSLATE_MODEL} " +
            "videoDescription=${videoDescriptionModel.trim().removePrefix(\"models/\").ifBlank { AppPreferences.VIDEO_DESCRIPTION_MODEL }}"
}
