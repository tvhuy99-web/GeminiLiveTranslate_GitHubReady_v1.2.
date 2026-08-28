package com.oai.geminilivetranslate.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.oai.geminilivetranslate.core.SessionLogger
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class TtsEngineInfo(
    val packageName: String,
    val label: String,
)

data class TtsLanguageInfo(
    val languageTag: String,
    val label: String,
)

data class TtsVoiceInfo(
    val name: String,
    val languageTag: String,
    val label: String,
    val networkRequired: Boolean,
)

data class TtsEngineCatalog(
    val requestedEnginePackage: String,
    val resolvedEnginePackage: String,
    val languages: List<TtsLanguageInfo>,
    val voices: List<TtsVoiceInfo>,
) {
    fun voicesForLanguage(languageTag: String): List<TtsVoiceInfo> {
        val normalized = TtsPreferences.normalizeLanguageTag(languageTag) ?: return emptyList()
        return voices.filter { it.languageTag.equals(normalized, ignoreCase = true) }
    }

    fun preferredLanguage(savedLanguageTag: String = TtsPreferences.DEFAULT_TTS_LANGUAGE): TtsLanguageInfo? {
        if (languages.isEmpty()) return null
        val saved = TtsPreferences.normalizeLanguageTag(savedLanguageTag)
        saved?.let { wanted ->
            languages.firstOrNull { it.languageTag.equals(wanted, ignoreCase = true) }?.let { return it }
        }
        languages.firstOrNull { it.languageTag.equals(TtsPreferences.DEFAULT_TTS_LANGUAGE, ignoreCase = true) }
            ?.let { return it }
        languages.firstOrNull { it.languageTag.substringBefore('-').equals("vi", ignoreCase = true) }
            ?.let { return it }
        return languages.first()
    }

    fun preferredVoice(languageTag: String, savedVoiceName: String): TtsVoiceInfo? =
        voicesForLanguage(languageTag).firstOrNull { it.name == savedVoiceName }
}

class TtsCatalogScanner(
    context: Context,
    private val logger: SessionLogger,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeGeneration = AtomicLong(0L)
    @Volatile private var activeProbe: TextToSpeech? = null
    private var activeTimeout: Runnable? = null

    fun discoverEngines(): List<TtsEngineInfo> {
        val packageManager = appContext.packageManager
        val labels = linkedMapOf<String, String>()

        fun addPackage(packageName: String, fallbackLabel: String?) {
            if (packageName.isBlank() || labels.containsKey(packageName)) return
            val appLabel = runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info)?.toString()
            }.getOrNull()
            labels[packageName] = appLabel?.takeIf { it.isNotBlank() }
                ?: fallbackLabel?.takeIf { it.isNotBlank() }
                ?: packageName
        }

        runCatching {
            packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        }.onSuccess { services ->
            logger.log(2, "TTSSettings", "TTS_SERVICE discovery count=${services.size}")
            services.forEach { info ->
                val pkg = info.serviceInfo?.packageName ?: return@forEach
                val label = runCatching { info.loadLabel(packageManager)?.toString() }.getOrNull()
                addPackage(pkg, label)
            }
        }.onFailure {
            logger.log(1, "TTSSettings", "Không quét được TTS_SERVICE", it)
        }

        runCatching {
            packageManager.queryIntentActivities(Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA), 0)
        }.onSuccess { activities ->
            logger.log(2, "TTSSettings", "CHECK_TTS_DATA discovery count=${activities.size}")
            activities.forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                val label = runCatching { info.loadLabel(packageManager)?.toString() }.getOrNull()
                addPackage(pkg, label)
            }
        }.onFailure {
            logger.log(1, "TTSSettings", "Không quét được CHECK_TTS_DATA", it)
        }

        return buildList {
            add(TtsEngineInfo("", "Mặc định hệ thống"))
            labels.entries
                .sortedBy { it.value.lowercase(Locale.ROOT) }
                .forEach { (pkg, label) -> add(TtsEngineInfo(pkg, "$label • $pkg")) }
        }
    }

    fun inspect(
        engine: TtsEngineInfo,
        onResult: (Result<TtsEngineCatalog>) -> Unit,
    ) {
        val generation = probeGeneration.incrementAndGet()
        cancelCurrentProbeOnly()
        logger.log(2, "TTSSettings", "Đọc catalog engine=${engine.packageName.ifBlank { "DEFAULT" }}")

        val holder = arrayOfNulls<TextToSpeech>(1)
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (generation != probeGeneration.get()) return@post
                cancelTimeout()
                val current = holder[0]
                if (status != TextToSpeech.SUCCESS || current == null) {
                    current?.let { runCatching { it.shutdown() } }
                    activeProbe = null
                    onResult(Result.failure(IllegalStateException("Engine TTS không khởi tạo được, status=$status")))
                    return@post
                }

                Thread {
                    val catalog = runCatching { buildCatalog(current, engine) }
                    runCatching { current.stop() }
                    runCatching { current.shutdown() }
                    mainHandler.post {
                        if (generation != probeGeneration.get()) return@post
                        activeProbe = null
                        catalog.onSuccess {
                            logger.log(
                                2,
                                "TTSSettings",
                                "Catalog engine=${it.resolvedEnginePackage.ifBlank { "DEFAULT" }} languages=${it.languages.size} voices=${it.voices.size}",
                            )
                        }.onFailure {
                            logger.log(1, "TTSSettings", "Không đọc được catalog TTS", it)
                        }
                        onResult(catalog)
                    }
                }.start()
            }
        }

        val created = runCatching {
            if (engine.packageName.isBlank()) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, engine.packageName)
            }
        }.getOrElse { error ->
            logger.log(1, "TTSSettings", "Không tạo được TextToSpeech engine=${engine.packageName.ifBlank { "DEFAULT" }}", error)
            onResult(Result.failure(error))
            return
        }
        holder[0] = created
        activeProbe = created

        activeTimeout = Runnable {
            if (generation != probeGeneration.get()) return@Runnable
            val current = activeProbe
            activeProbe = null
            runCatching { current?.shutdown() }
            logger.log(1, "TTSSettings", "Timeout khi đọc catalog engine=${engine.packageName.ifBlank { "DEFAULT" }}")
            onResult(Result.failure(IllegalStateException("Bộ đọc không phản hồi sau ${PROBE_TIMEOUT_MS / 1000} giây")))
        }.also { mainHandler.postDelayed(it, PROBE_TIMEOUT_MS) }
    }

    fun cancel() {
        probeGeneration.incrementAndGet()
        cancelCurrentProbeOnly()
    }

    private fun cancelCurrentProbeOnly() {
        cancelTimeout()
        val current = activeProbe
        activeProbe = null
        runCatching { current?.stop() }
        runCatching { current?.shutdown() }
    }

    private fun cancelTimeout() {
        activeTimeout?.let(mainHandler::removeCallbacks)
        activeTimeout = null
    }

    private fun buildCatalog(engine: TextToSpeech, requested: TtsEngineInfo): TtsEngineCatalog {
        val locales = linkedMapOf<String, Locale>()
        val voiceRows = mutableListOf<TtsVoiceInfo>()

        val voices = runCatching { engine.voices.orEmpty() }
            .onFailure { logger.log(1, "TTSSettings", "getVoices() lỗi", it) }
            .getOrDefault(emptySet())

        voices.forEach { voice ->
            val locale = voice.locale ?: return@forEach
            val tag = normalizedTag(locale) ?: return@forEach
            locales.putIfAbsent(tag, locale)
            voiceRows += TtsVoiceInfo(
                name = voice.name,
                languageTag = tag,
                label = buildString {
                    append(voice.name)
                    if (voice.isNetworkConnectionRequired) append(" • cần mạng")
                },
                networkRequired = voice.isNetworkConnectionRequired,
            )
        }

        runCatching { engine.availableLanguages.orEmpty() }
            .onFailure { logger.log(1, "TTSSettings", "getAvailableLanguages() lỗi", it) }
            .getOrDefault(emptySet())
            .forEach { locale ->
                normalizedTag(locale)?.let { locales.putIfAbsent(it, locale) }
            }
        if (locales.isEmpty()) {
            fallbackLocaleCandidates().forEach { locale ->
                val availability = runCatching { engine.isLanguageAvailable(locale) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    normalizedTag(locale)?.let { locales.putIfAbsent(it, locale) }
                }
            }
        } else if (locales.keys.none { it.substringBefore('-').equals("vi", ignoreCase = true) }) {
            listOf(Locale.forLanguageTag("vi-VN"), Locale("vi")).forEach { locale ->
                val availability = runCatching { engine.isLanguageAvailable(locale) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    normalizedTag(locale)?.let { locales.putIfAbsent(it, locale) }
                }
            }
        }

        val displayLocale = Locale.forLanguageTag("vi-VN")
        val languages = locales.entries.map { (tag, locale) ->
            val human = locale.getDisplayName(displayLocale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString() }
                .ifBlank { tag }
            TtsLanguageInfo(tag, "$human • $tag")
        }.sortedWith(
            compareBy<TtsLanguageInfo> {
                when {
                    it.languageTag.equals("vi-VN", true) -> 0
                    it.languageTag.substringBefore('-').equals("vi", true) -> 1
                    else -> 2
                }
            }.thenBy { it.label.lowercase(displayLocale) },
        )

        val validTags = languages.mapTo(hashSetOf()) { it.languageTag.lowercase(Locale.ROOT) }
        val filteredVoices = voiceRows
            .filter { it.languageTag.lowercase(Locale.ROOT) in validTags }
            .distinctBy { "${it.languageTag.lowercase(Locale.ROOT)}\u0000${it.name}" }
            .sortedWith(compareBy<TtsVoiceInfo> { it.languageTag }.thenBy { it.name.lowercase(Locale.ROOT) })

        return TtsEngineCatalog(
            requestedEnginePackage = requested.packageName,
            resolvedEnginePackage = runCatching { engine.defaultEngine }.getOrNull().orEmpty(),
            languages = languages,
            voices = filteredVoices,
        )
    }

    private fun normalizedTag(locale: Locale): String? =
        TtsPreferences.normalizeLanguageTag(locale.toLanguageTag())

    private fun fallbackLocaleCandidates(): List<Locale> = buildList {
        add(Locale.forLanguageTag("vi-VN"))
        add(Locale("vi"))
        Locale.getAvailableLocales()
            .asSequence()
            .filter { it.language.isNotBlank() }
            .distinctBy { it.toLanguageTag().lowercase(Locale.ROOT) }
            .forEach(::add)
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 5_000L
    }
}

class TtsPreviewSpeaker(
    context: Context,
    private val logger: SessionLogger,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    @Volatile private var tts: TextToSpeech? = null
    private var timeout: Runnable? = null

    fun speak(selection: TtsSelection, onResult: (Boolean, String) -> Unit) {
        val safe = TtsPreferences.sanitize(selection)
        val currentGeneration = generation.incrementAndGet()
        shutdownCurrent()

        val holder = arrayOfNulls<TextToSpeech>(1)
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (currentGeneration != generation.get()) return@post
                cancelTimeout()
                val engine = holder[0]
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    onResult(false, "Không khởi tạo được bộ đọc đã chọn")
                    shutdownCurrent()
                    return@post
                }
                val locale = Locale.forLanguageTag(safe.languageTag)
                val availability = runCatching { engine.isLanguageAvailable(locale) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (availability < TextToSpeech.LANG_AVAILABLE) {
                    onResult(false, "Bộ đọc không hỗ trợ ${safe.languageTag}")
                    shutdownCurrent()
                    return@post
                }
                runCatching { engine.setLanguage(locale) }
                if (safe.voiceName.isNotBlank()) {
                    val voice = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                        .firstOrNull {
                            it.name == safe.voiceName &&
                                TtsPreferences.normalizeLanguageTag(it.locale?.toLanguageTag().orEmpty())
                                    ?.equals(safe.languageTag, ignoreCase = true) == true
                        }
                    if (voice != null) runCatching { engine.voice = voice }
                }
                engine.setSpeechRate(1f)
                engine.setPitch(1f)
                val utteranceId = "tts-preview-${UUID.randomUUID()}"
                runCatching {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                if (currentGeneration == generation.get()) onResult(true, "Đã phát giọng thử")
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                if (currentGeneration == generation.get()) onResult(false, "Không phát được giọng thử")
                            }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                    })
                }
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
                }
                val result = engine.speak(previewText(safe.languageTag), TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    logger.log(1, "TTSSettings", "Nghe thử speak() lỗi result=$result")
                    onResult(false, "Bộ đọc từ chối phát giọng thử")
                }
            }
        }

        val created = runCatching {
            if (safe.enginePackage.isBlank()) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, safe.enginePackage)
        }.getOrElse {
            logger.log(1, "TTSSettings", "Không tạo được bộ đọc để nghe thử", it)
            onResult(false, "Không mở được bộ đọc đã chọn")
            return
        }
        holder[0] = created
        tts = created
        timeout = Runnable {
            if (currentGeneration != generation.get()) return@Runnable
            onResult(false, "Bộ đọc không phản hồi khi nghe thử")
            shutdownCurrent()
        }.also { mainHandler.postDelayed(it, PREVIEW_TIMEOUT_MS) }
    }

    fun shutdown() {
        generation.incrementAndGet()
        shutdownCurrent()
    }

    private fun shutdownCurrent() {
        cancelTimeout()
        val current = tts
        tts = null
        runCatching { current?.stop() }
        runCatching { current?.shutdown() }
    }

    private fun cancelTimeout() {
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
    }

    private fun previewText(languageTag: String): String = when (languageTag.substringBefore('-').lowercase(Locale.ROOT)) {
        "vi" -> "Xin chào, đây là giọng đọc thử tiếng Việt."
        "en" -> "Hello, this is a text to speech voice preview."
        "zh" -> "你好，这是语音试听。"
        "ja" -> "こんにちは、これは音声の試聴です。"
        "ko" -> "안녕하세요. 음성 미리 듣기입니다."
        "fr" -> "Bonjour, ceci est un aperçu de la voix."
        "de" -> "Hallo, dies ist eine Stimmvorschau."
        "es" -> "Hola, esta es una prueba de voz."
        else -> "Xin chào, đây là giọng đọc thử."
    }

    companion object {
        private const val PREVIEW_TIMEOUT_MS = 7_000L
    }
}
