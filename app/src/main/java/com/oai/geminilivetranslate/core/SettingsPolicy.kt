package com.oai.geminilivetranslate.core

/** Central source of truth for validation, presets and live-application rules. */
object SettingsPolicy {
    val validUiModes = setOf("simple", "advanced")
    val validProfiles = setOf("realtime", "balanced", "stable", "custom")
    val validSaveModes = setOf("translated", "original", "mixed")
    val validExportFormats = setOf("srt", "txt")
    val validAiAudioStreamTypes = setOf(
        "accessibility",
        "media",
        "voice_communication",
        "assistant",
        "alarm",
        "notification",
        "ring",
        "system",
        "voice_call",
        "dtmf",
    )

    fun sanitize(input: AppSettings): AppSettings {
        val languages = input.micLanguages.mapNotNull(LanguageCatalog::normalize).distinct().ifEmpty { listOf("vi") }
        val target = LanguageCatalog.normalize(input.targetLanguage) ?: "vi"
        val queueMax = input.translatedQueueMax.coerceIn(5, 100)
        return input.copy(
            model = input.model.trim().removePrefix("models/").ifBlank { AppPreferences.DEFAULT_MODEL },
            targetLanguage = target,
            aiAudioStreamType = input.aiAudioStreamType.takeIf(validAiAudioStreamTypes::contains) ?: "accessibility",
            duckVolumeFactor = input.duckVolumeFactor.coerceIn(0f, 1f),
            uiMode = input.uiMode.takeIf(validUiModes::contains) ?: "advanced",
            performanceProfile = input.performanceProfile.takeIf(validProfiles::contains) ?: "custom",
            reconnectMaxRetries = input.reconnectMaxRetries.coerceIn(1, 10),
            inputBufferMs = input.inputBufferMs.coerceIn(200, 10_000),
            outputJitterTarget = input.outputJitterTarget.coerceIn(3, 20).coerceAtMost(queueMax),
            fileSyncDelayMs = input.fileSyncDelayMs.coerceIn(0, 20_000),
            pacingTargetLatencyMs = input.pacingTargetLatencyMs.coerceIn(100, 2_000),
            pacingMaxBuffer = input.pacingMaxBuffer.coerceIn(1, 50),
            translatedBufferBytes = input.translatedBufferBytes.coerceIn(48_000, 192_000),
            translatedQueueMax = queueMax,
            ttsSmoothTimeoutMs = input.ttsSmoothTimeoutMs.coerceIn(500, 5_000),
            ttsSmoothMinChars = input.ttsSmoothMinChars.coerceIn(20, 200),
            ttsSmoothMinWords = input.ttsSmoothMinWords.coerceIn(3, 20),
            saveAudioMode = input.saveAudioMode.takeIf(validSaveModes::contains) ?: "translated",
            exportFormat = input.exportFormat.takeIf(validExportFormats::contains) ?: "srt",
            logLevel = input.logLevel.coerceIn(0, 3),
            originalVolume = input.originalVolume.coerceIn(0, 100),
            translatedVolume = input.translatedVolume.coerceIn(0, 100),
            micLanguages = languages,
            micLanguageIndex = input.micLanguageIndex.coerceIn(0, languages.lastIndex),
        )
    }

    fun applyProfile(name: String, current: AppSettings): AppSettings = sanitize(when (name) {
        "realtime" -> current.copy(
            performanceProfile = name,
            translatedBufferBytes = 64_000,
            translatedQueueMax = 12,
            pacingMaxBuffer = 3,
            pacingTargetLatencyMs = 300,
            qualityMode = false,
            inputBufferMs = 400,
            outputJitterTarget = 3,
            fileSyncDelayMs = 1_200,
            reconnectMaxRetries = 3,
        )
        "stable" -> current.copy(
            performanceProfile = name,
            translatedBufferBytes = 160_000,
            translatedQueueMax = 50,
            pacingMaxBuffer = 8,
            pacingTargetLatencyMs = 900,
            qualityMode = true,
            inputBufferMs = 1_200,
            outputJitterTarget = 12,
            fileSyncDelayMs = 3_500,
            reconnectMaxRetries = 6,
        )
        "balanced" -> current.copy(
            performanceProfile = name,
            translatedBufferBytes = 96_000,
            translatedQueueMax = 20,
            pacingMaxBuffer = 5,
            pacingTargetLatencyMs = 500,
            qualityMode = false,
            inputBufferMs = 800,
            outputJitterTarget = 8,
            fileSyncDelayMs = 2_000,
            reconnectMaxRetries = 3,
        )
        else -> current.copy(performanceProfile = "custom")
    })


    /**
     * Persisted settings may contain values that are intentionally deferred until a fresh source session.
     * Keep the current session coherent instead of changing only half of its audio pipeline.
     */
    fun activeSessionSettings(before: AppSettings, persistedAfter: AppSettings): AppSettings {
        val old = sanitize(before)
        val next = sanitize(persistedAfter)
        return next.copy(
            qualityMode = old.qualityMode,
            inputBufferMs = old.inputBufferMs,
            fileSyncDelayMs = old.fileSyncDelayMs,
            pacingEnabled = old.pacingEnabled,
            pacingTargetLatencyMs = old.pacingTargetLatencyMs,
            pacingMaxBuffer = old.pacingMaxBuffer,
            saveAudioEnabled = old.saveAudioEnabled,
            saveAudioMode = old.saveAudioMode,
        )
    }

    fun diff(old: AppSettings, new: AppSettings): SettingsDiff {
        val before = sanitize(old)
        val after = sanitize(new)
        val changed = linkedSetOf<String>()
        fun mark(name: String, valueChanged: Boolean) { if (valueChanged) changed += name }

        mark("model", before.model != after.model)
        mark("targetLanguage", before.targetLanguage != after.targetLanguage)
        mark("echoTargetLanguage", before.echoTargetLanguage != after.echoTargetLanguage)
        mark("aiVoice", before.aiVoice != after.aiVoice)
        mark("aiAudioStreamType", before.aiAudioStreamType != after.aiAudioStreamType)
        mark("autoDucking", before.autoDucking != after.autoDucking)
        mark("duckVolumeFactor", before.duckVolumeFactor != after.duckVolumeFactor)
        mark("muteOriginalInInternal", before.muteOriginalInInternal != after.muteOriginalInInternal)
        mark("uiMode", before.uiMode != after.uiMode)
        mark("performanceProfile", before.performanceProfile != after.performanceProfile)
        mark("autoReconnect", before.autoReconnect != after.autoReconnect)
        mark("reconnectMaxRetries", before.reconnectMaxRetries != after.reconnectMaxRetries)
        mark("qualityMode", before.qualityMode != after.qualityMode)
        mark("inputBufferMs", before.inputBufferMs != after.inputBufferMs)
        mark("outputJitterTarget", before.outputJitterTarget != after.outputJitterTarget)
        mark("fileSyncDelayMs", before.fileSyncDelayMs != after.fileSyncDelayMs)
        mark("pacingEnabled", before.pacingEnabled != after.pacingEnabled)
        mark("pacingTargetLatencyMs", before.pacingTargetLatencyMs != after.pacingTargetLatencyMs)
        mark("pacingMaxBuffer", before.pacingMaxBuffer != after.pacingMaxBuffer)
        mark("translatedBufferBytes", before.translatedBufferBytes != after.translatedBufferBytes)
        mark("translatedQueueMax", before.translatedQueueMax != after.translatedQueueMax)
        mark("ttsSmoothEnabled", before.ttsSmoothEnabled != after.ttsSmoothEnabled)
        mark("ttsSmoothTimeoutMs", before.ttsSmoothTimeoutMs != after.ttsSmoothTimeoutMs)
        mark("ttsSmoothMinChars", before.ttsSmoothMinChars != after.ttsSmoothMinChars)
        mark("ttsSmoothMinWords", before.ttsSmoothMinWords != after.ttsSmoothMinWords)
        mark("saveAudioEnabled", before.saveAudioEnabled != after.saveAudioEnabled)
        mark("saveAudioMode", before.saveAudioMode != after.saveAudioMode)
        mark("exportFormat", before.exportFormat != after.exportFormat)
        mark("logLevel", before.logLevel != after.logLevel)
        mark("logToFile", before.logToFile != after.logToFile)
        mark("logIncludeTranscript", before.logIncludeTranscript != after.logIncludeTranscript)
        mark("originalVolume", before.originalVolume != after.originalVolume)
        mark("translatedVolume", before.translatedVolume != after.translatedVolume)
        mark("micLanguages", before.micLanguages != after.micLanguages)
        mark("micLanguageIndex", before.micLanguageIndex != after.micLanguageIndex)

        val reconnect = changed.intersect(setOf("model", "targetLanguage", "echoTargetLanguage"))
        val playbackRebuild = changed.intersect(setOf(
            "translatedBufferBytes", "translatedQueueMax", "outputJitterTarget", "aiAudioStreamType"
        ))
        val nextSession = changed.intersect(setOf(
            "qualityMode", "inputBufferMs", "fileSyncDelayMs", "pacingEnabled", "pacingTargetLatencyMs",
            "pacingMaxBuffer", "saveAudioEnabled", "saveAudioMode"
        ))
        val immediate = changed - reconnect - playbackRebuild - nextSession
        return SettingsDiff(changed, immediate, reconnect, playbackRebuild, nextSession)
    }
}

data class SettingsDiff(
    val changed: Set<String>,
    val immediate: Set<String>,
    val reconnect: Set<String>,
    val playbackRebuild: Set<String>,
    val nextSession: Set<String>,
) {
    val isEmpty: Boolean get() = changed.isEmpty()
    val requiresReconnect: Boolean get() = reconnect.isNotEmpty()
    val requiresPlaybackRebuild: Boolean get() = playbackRebuild.isNotEmpty()

    fun userSummary(): String = buildList {
        if (immediate.isNotEmpty()) add("Áp dụng ngay: ${immediate.joinToString()}")
        if (reconnect.isNotEmpty()) add("Kết nối lại Gemini: ${reconnect.joinToString()}")
        if (playbackRebuild.isNotEmpty()) add("Tạo lại bộ phát: ${playbackRebuild.joinToString()}")
        if (nextSession.isNotEmpty()) add("Áp dụng từ phiên tiếp theo: ${nextSession.joinToString()}")
    }.joinToString("\n")
}
