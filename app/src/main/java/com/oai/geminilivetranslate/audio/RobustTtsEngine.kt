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

class RobustTtsEngine(
    context: Context,
    private val logger: SessionLogger,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsPreferences = TtsPreferences(appContext)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var activeEngine: String? = null
    @Volatile private var configuredEnginePreference: String = ""

    private var initGeneration = 0L
    private var activeAttemptIndex = -1
    private var initTimeout: Runnable? = null

    fun initialize(onReady: (Boolean) -> Unit) {
        mainHandler.post {
            val generation = ++initGeneration
            ready = false
            activeEngine = null
            val selection = ttsPreferences.load()
            configuredEnginePreference = selection.enginePackage
            val candidates = discoverEngineCandidates(selection.enginePackage)
            logger.log(
                2,
                "TTS",
                "Khởi tạo robust TTS preferred=${selection.enginePackage.ifBlank { "DEFAULT" }} language=${selection.languageTag} voice=${selection.voiceName.ifBlank { "DEFAULT" }} candidates=${candidates.size} engines=${candidates.filterNotNull().joinToString()}",
            )
            tryCandidate(candidates, 0, generation, onReady)
        }
    }

    fun isReady(): Boolean = ready

    fun currentEngine(): String? = activeEngine

    fun speak(
        text: String,
        languageTag: String,
        rate: Float = 1f,
        pitch: Float = 1f,
        volume: Float = 1f,
        queue: Boolean = true,
    ): Boolean {
        val engine = tts ?: return false
        if (!ready || text.isBlank()) return false

        val selection = ttsPreferences.load()
        if (selection.enginePackage != configuredEnginePreference) {
            logger.log(
                2,
                "TTS",
                "Bộ đọc đã đổi từ ${configuredEnginePreference.ifBlank { "DEFAULT" }} sang ${selection.enginePackage.ifBlank { "DEFAULT" }}; yêu cầu khởi tạo lại trước khi đọc",
            )
            return false
        }

        return runCatching {
            val selectedLanguage = selection.languageTag.ifBlank { languageTag.ifBlank { TtsPreferences.DEFAULT_TTS_LANGUAGE } }
            val localeApplied = setLocale(engine, selectedLanguage)
            if (!localeApplied && !selectedLanguage.equals(languageTag, ignoreCase = true)) {
                logger.log(1, "TTS", "Locale TTS đã chọn $selectedLanguage không dùng được; thử locale bản dịch $languageTag")
                setLocale(engine, languageTag)
            }
            applySelectedVoice(engine, selection.voiceName, selectedLanguage)
            engine.setSpeechRate(rate.coerceIn(0.5f, 3f))
            engine.setPitch(pitch.coerceIn(0.5f, 2f))
            val queueMode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            val utteranceId = "tts-${UUID.randomUUID()}"
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            }

            var result = engine.speak(text, queueMode, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                logger.log(1, "TTS", "speak() trả lỗi=$result với Bundle; thử lại không params engine=${activeEngine ?: "default"}")
                result = engine.speak(text, queueMode, null, utteranceId)
            }
            if (result == TextToSpeech.SUCCESS) {
                logger.log(
                    3,
                    "TTS",
                    "Đã xếp câu đọc chars=${text.length} rate=${"%.2f".format(Locale.US, rate)} engine=${activeEngine ?: "default"} language=$selectedLanguage voice=${selection.voiceName.ifBlank { "DEFAULT" }}",
                )
                true
            } else {
                logger.log(1, "TTS", "Không xếp được câu đọc result=$result engine=${activeEngine ?: "default"}")
                false
            }
        }.onFailure {
            logger.log(1, "TTS", "speak() gặp ngoại lệ engine=${activeEngine ?: "default"}", it)
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { tts?.stop() }
            .onFailure { logger.log(1, "TTS", "Không stop được TTS", it) }
    }

    fun shutdown() {
        mainHandler.post {
            ++initGeneration
            cancelInitTimeout()
            ready = false
            activeEngine = null
            configuredEnginePreference = ""
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
        }
    }

    private fun discoverEngineCandidates(preferredEngine: String): List<String?> {
        val packages = linkedSetOf<String>()
        val packageManager = appContext.packageManager

        runCatching {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val services = packageManager.queryIntentServices(intent, 0)
            logger.log(2, "TTS", "TTS_SERVICE discovery count=${services.size}")
            services.forEach { info ->
                val packageName = info.serviceInfo?.packageName ?: return@forEach
                if (packageName.isNotBlank() && packages.add(packageName)) {
                    val label = runCatching { info.loadLabel(packageManager)?.toString() }.getOrNull()
                    logger.log(2, "TTS", "Phát hiện engine service package=$packageName label=${label ?: packageName}")
                }
            }
        }.onFailure {
            logger.log(1, "TTS", "Không quét được TTS_SERVICE qua PackageManager", it)
        }
        runCatching {
            val checkIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            val activities = packageManager.queryIntentActivities(checkIntent, 0)
            logger.log(2, "TTS", "CHECK_TTS_DATA discovery count=${activities.size}")
            activities.forEach { info ->
                val packageName = info.activityInfo?.packageName ?: return@forEach
                if (packageName.isNotBlank() && packages.add(packageName)) {
                    val label = runCatching { info.loadLabel(packageManager)?.toString() }.getOrNull()
                    logger.log(2, "TTS", "Phát hiện engine check-data package=$packageName label=${label ?: packageName}")
                }
            }
        }.onFailure {
            logger.log(1, "TTS", "Không quét được ACTION_CHECK_TTS_DATA", it)
        }

        if (packages.isEmpty()) {
            logger.log(1, "TTS", "PackageManager không thấy engine TTS nào; vẫn thử engine mặc định của hệ thống")
        }

        return buildList {
            if (preferredEngine.isNotBlank()) add(preferredEngine)
            add(null) // system default is the first fallback
            packages.filterNot { it == preferredEngine }.forEach(::add)
        }.distinct()
    }

    private fun tryCandidate(
        candidates: List<String?>,
        index: Int,
        generation: Long,
        onReady: (Boolean) -> Unit,
    ) {
        if (generation != initGeneration) return
        if (index !in candidates.indices) {
            ready = false
            activeEngine = null
            logger.log(1, "TTS", "Không engine TTS nào khởi tạo thành công")
            onReady(false)
            return
        }

        activeAttemptIndex = index
        cancelInitTimeout()
        shutdownCurrentInstance()
        val requestedEngine = candidates[index]
        logger.log(2, "TTS", "Thử khởi tạo engine=${requestedEngine ?: "DEFAULT"} attempt=${index + 1}/${candidates.size}")

        val listener = TextToSpeech.OnInitListener { status ->
            if (generation != initGeneration || activeAttemptIndex != index) return@OnInitListener
            cancelInitTimeout()
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                activeEngine = requestedEngine ?: runCatching { tts?.defaultEngine }.getOrNull()
                val current = tts
                if (current != null) {
                    applyCurrentSelection(current)
                    installUtteranceListener(current)
                }
                logger.log(2, "TTS", "TTS sẵn sàng engine=${activeEngine ?: "DEFAULT"} attempt=${index + 1}")
                onReady(true)
            } else {
                logger.log(1, "TTS", "Engine init thất bại status=$status engine=${requestedEngine ?: "DEFAULT"}; thử engine tiếp theo")
                tryCandidate(candidates, index + 1, generation, onReady)
            }
        }

        try {
            tts = if (requestedEngine == null) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, requestedEngine)
            }
        } catch (error: Throwable) {
            logger.log(1, "TTS", "Tạo TextToSpeech thất bại engine=${requestedEngine ?: "DEFAULT"}", error)
            tryCandidate(candidates, index + 1, generation, onReady)
            return
        }

        initTimeout = Runnable {
            if (generation != initGeneration || activeAttemptIndex != index || ready) return@Runnable
            logger.log(1, "TTS", "OnInit timeout ${INIT_TIMEOUT_MS}ms engine=${requestedEngine ?: "DEFAULT"}")
            if (index + 1 < candidates.size) {
                tryCandidate(candidates, index + 1, generation, onReady)
            } else {
                val current = tts
                if (current != null) {
                    logger.log(1, "TTS", "Không còn engine khác; force-probe instance sau timeout")
                    ready = true
                    activeEngine = requestedEngine ?: runCatching { current.defaultEngine }.getOrNull()
                    applyCurrentSelection(current)
                    installUtteranceListener(current)
                    onReady(true)
                } else {
                    onReady(false)
                }
            }
        }.also { mainHandler.postDelayed(it, INIT_TIMEOUT_MS) }
    }

    private fun applyCurrentSelection(engine: TextToSpeech) {
        val selection = ttsPreferences.load()
        val language = selection.languageTag.ifBlank { TtsPreferences.DEFAULT_TTS_LANGUAGE }
        setLocale(engine, language)
        applySelectedVoice(engine, selection.voiceName, language)
    }

    private fun applySelectedVoice(engine: TextToSpeech, voiceName: String, languageTag: String): Boolean {
        if (voiceName.isBlank()) return true
        val wantedTag = TtsPreferences.normalizeLanguageTag(languageTag) ?: return false
        val voice = runCatching { engine.voices.orEmpty() }
            .onFailure { logger.log(1, "TTS", "Không đọc được danh sách voice khi áp dụng voice=$voiceName", it) }
            .getOrDefault(emptySet())
            .firstOrNull {
                it.name == voiceName &&
                    TtsPreferences.normalizeLanguageTag(it.locale?.toLanguageTag().orEmpty())
                        ?.equals(wantedTag, ignoreCase = true) == true
            }
        if (voice == null) {
            logger.log(1, "TTS", "Voice đã chọn không còn khả dụng voice=$voiceName language=$wantedTag; dùng voice mặc định của locale")
            return false
        }
        return runCatching {
            engine.voice = voice
            logger.log(2, "TTS", "Áp dụng voice=${voice.name} language=$wantedTag network=${voice.isNetworkConnectionRequired}")
            true
        }.onFailure {
            logger.log(1, "TTS", "Không áp dụng được voice=$voiceName", it)
        }.getOrDefault(false)
    }

    private fun setLocale(engine: TextToSpeech, languageTag: String): Boolean {
        val requested = Locale.forLanguageTag(languageTag.ifBlank { TtsPreferences.DEFAULT_TTS_LANGUAGE })
        val primary = runCatching { engine.isLanguageAvailable(requested) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (primary >= TextToSpeech.LANG_AVAILABLE) {
            val result = runCatching { engine.setLanguage(requested) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            logger.log(3, "TTS", "Locale ${requested.toLanguageTag()} availability=$primary setResult=$result")
            return result >= TextToSpeech.LANG_AVAILABLE
        }

        val languageOnly = Locale(requested.language.ifBlank { "vi" })
        val fallback = runCatching { engine.isLanguageAvailable(languageOnly) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (fallback >= TextToSpeech.LANG_AVAILABLE) {
            val result = runCatching { engine.setLanguage(languageOnly) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            logger.log(3, "TTS", "Locale fallback ${languageOnly.language} availability=$fallback setResult=$result")
            return result >= TextToSpeech.LANG_AVAILABLE
        }

        logger.log(1, "TTS", "Engine ${activeEngine ?: "DEFAULT"} không báo hỗ trợ locale=$languageTag primary=$primary fallback=$fallback")
        return false
    }

    private fun installUtteranceListener(engine: TextToSpeech) {
        runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    logger.log(3, "TTS", "Bắt đầu utterance id=${utteranceId.orEmpty().take(20)}")
                }

                override fun onDone(utteranceId: String?) {
                    logger.log(3, "TTS", "Hoàn tất utterance id=${utteranceId.orEmpty().take(20)}")
                }

                override fun onError(utteranceId: String?) {
                    logger.log(1, "TTS", "Utterance lỗi id=${utteranceId.orEmpty().take(20)}")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    logger.log(1, "TTS", "Utterance lỗi code=$errorCode id=${utteranceId.orEmpty().take(20)}")
                }
            })
        }.onFailure {
            logger.log(1, "TTS", "Không gắn được UtteranceProgressListener", it)
        }
    }

    private fun shutdownCurrentInstance() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        activeEngine = null
    }

    private fun cancelInitTimeout() {
        initTimeout?.let(mainHandler::removeCallbacks)
        initTimeout = null
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L
    }
}
