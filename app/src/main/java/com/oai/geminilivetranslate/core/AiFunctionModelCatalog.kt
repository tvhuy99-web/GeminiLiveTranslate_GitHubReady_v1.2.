package com.oai.geminilivetranslate.core


object AiFunctionModelCatalog {
    fun summary(videoDescriptionModel: String = AppPreferences.VIDEO_DESCRIPTION_MODEL): String {
        val videoModel = videoDescriptionModel.trim().removePrefix("models/")
            .ifBlank { AppPreferences.VIDEO_DESCRIPTION_MODEL }
        return "liveTranslate=${AppPreferences.DEFAULT_MODEL} " +
            "liveTranscribe=${AppPreferences.TRANSCRIBE_LIVE_MODEL} " +
            "fileTranscribe=${AppPreferences.TRANSCRIBE_FILE_MODEL} " +
            "subtitleTranslate=${AppPreferences.SUBTITLE_TRANSLATE_MODEL} " +
            "videoDescription=$videoModel"
    }
}
