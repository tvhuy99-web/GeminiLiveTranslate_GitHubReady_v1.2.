package com.oai.geminilivetranslate.ui

import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.oai.geminilivetranslate.audio.AudioSource
import com.oai.geminilivetranslate.audio.FileAudioSource
import com.oai.geminilivetranslate.audio.RobustTtsEngine
import com.oai.geminilivetranslate.audio.StreamingPcmPlayer
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SrtParser
import com.oai.geminilivetranslate.core.SubtitleStore
import com.oai.geminilivetranslate.databinding.ActivitySubtitlePlaybackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SubtitlePlaybackActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySubtitlePlaybackBinding
    private lateinit var preferences: AppPreferences
    private lateinit var logger: SessionLogger
    private lateinit var ttsEngine: RobustTtsEngine

    private var mediaUri: Uri? = null
    private var mediaName: String? = null
    private var subtitleName: String? = null
    private var subtitleRaw: String = ""
    private var cues: List<SubtitleStore.Cue> = emptyList()

    private var source: FileAudioSource? = null
    private var player: StreamingPcmPlayer? = null
    private var playbackJob: Job? = null
    private var duckRestoreJob: Job? = null

    private var ttsReady = false
    private var ttsInitializing = false
    private var pendingPlayAfterTts = false
    private var playing = false
    private var paused = false
    private var completed = false
    private var ducking = false

    private var playbackSpeed = 1f
    private var currentPositionMs = 0L
    private var durationMs = 0L
    private var lastCueListIndex = -1
    private var lastProgressLogBucket = -1L
    private var cueEvents = 0L

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        persistReadPermission(uri)
        selectMedia(uri, displayName(uri) ?: uri.lastPathSegment ?: "Tệp media", false)
    }

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        persistReadPermission(uri)
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Không đọc được tệp phụ đề")
            val name = displayName(uri) ?: uri.lastPathSegment ?: "Phụ đề"
            selectSubtitle(text, name, false)
        }.onFailure {
            logger.log(0, TAG, "Đọc tệp phụ đề thất bại uriScheme=${uri.scheme}", it)
            status("Lỗi phụ đề: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubtitlePlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)
        logger = SessionLogger(this, preferences)
        ttsEngine = RobustTtsEngine(this, logger)

        restorePreferences()
        setupUi()
        restoreSeedFromIntent()
        ensureTtsReady(false)
        logger.log(
            2,
            TAG,
            "Mở màn hình seedMedia=${mediaUri != null} seedSubtitle=${subtitleRaw.isNotBlank()} cues=${cues.size}",
        )
    }

    override fun onDestroy() {
        logger.log(
            2,
            TAG,
            "Đóng màn hình playing=$playing paused=$paused completed=$completed positionMs=$currentPositionMs durationMs=$durationMs cueEvents=$cueEvents",
        )
        stopPlayback(resetPosition = false)
        ttsEngine.shutdown()
        super.onDestroy()
    }

    private fun setupUi() = with(binding) {
        selectMediaButton.setOnClickListener {
            mediaPicker.launch(arrayOf("video/*", "audio/*"))
        }
        selectSubtitleButton.setOnClickListener {
            subtitlePicker.launch(arrayOf("application/x-subrip", "text/srt", "text/plain", "text/*"))
        }
        playPauseButton.setOnClickListener {
            when {
                !playing -> requestStartPlayback()
                paused -> resumePlayback()
                else -> pausePlayback()
            }
        }
        rewindButton.setOnClickListener { seekBy(-10_000L) }
        forwardButton.setOnClickListener { seekBy(10_000L) }

        fileSpeedSeekBar.max = 20
        fileSpeedSeekBar.progress = ((playbackSpeed - 1f) * 10f).roundToInt().coerceIn(0, 20)
        syncSpeedUi()
        fileSpeedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                setPlaybackSpeed((1f + progress / 10f).coerceIn(1f, 3f))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekToPercent(seekBar?.progress ?: 0)
            }
        })

        originalVolumeSeekBar.setOnSeekBarChangeListener(volumeListener { value ->
            preferences.setVolumes(value, ttsVolumeSeekBar.progress)
            applyOriginalVolume()
            originalVolumeSeekBar.contentDescription = "Âm lượng âm thanh gốc: $value%"
            logger.log(3, TAG, "Đổi âm lượng gốc value=$value ducking=$ducking")
        })
        ttsVolumeSeekBar.setOnSeekBarChangeListener(volumeListener { value ->
            preferences.setVolumes(originalVolumeSeekBar.progress, value)
            ttsVolumeSeekBar.contentDescription = "Âm lượng giọng đọc phụ đề: $value%"
            logger.log(3, TAG, "Đổi âm lượng TTS value=$value")
        })
        autoDuckingSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.setAutoDucking(checked)
            logger.log(2, TAG, "Auto ducking enabled=$checked")
            if (!checked) restoreDucking("disabled")
        }
        updateButtons()
    }

    private fun restorePreferences() = with(binding) {
        val settings = preferences.load()
        originalVolumeSeekBar.progress = settings.originalVolume
        ttsVolumeSeekBar.progress = settings.translatedVolume
        autoDuckingSwitch.isChecked = settings.autoDucking
        playbackSpeed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1f)
            .coerceIn(1f, 3f)
    }

    private fun restoreSeedFromIntent() {
        intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let { uri ->
                selectMedia(
                    uri = uri,
                    name = intent.getStringExtra(EXTRA_MEDIA_NAME)
                        ?: uri.lastPathSegment
                        ?: "Media từ phiên hiện tại",
                    fromSeed = true,
                )
            }
        }
        intent.getStringExtra(EXTRA_SUBTITLE_SRT)?.takeIf(String::isNotBlank)?.let { srt ->
            selectSubtitle(
                raw = srt,
                name = intent.getStringExtra(EXTRA_SUBTITLE_NAME) ?: "Phụ đề từ phiên hiện tại",
                fromSeed = true,
            )
        }
        updateReadyStatus()
    }

    private fun selectMedia(uri: Uri, name: String, fromSeed: Boolean) {
        stopPlayback(resetPosition = true)
        mediaUri = uri
        mediaName = name
        binding.selectMediaButton.text = name
        binding.selectMediaButton.contentDescription = "Tệp media: $name"
        logger.log(
            2,
            TAG,
            "Chọn media source=${if (fromSeed) "session" else "picker"} name=$name uriScheme=${uri.scheme}",
        )
        updateReadyStatus()
    }

    private fun selectSubtitle(raw: String, name: String, fromSeed: Boolean) {
        stopPlayback(resetPosition = true)
        val parsed = SrtParser.parse(raw)
        subtitleRaw = raw
        subtitleName = name
        cues = parsed.cues
        binding.selectSubtitleButton.text = name
        binding.selectSubtitleButton.contentDescription = "Tệp phụ đề: $name"
        val first = cues.firstOrNull()
        val last = cues.lastOrNull()
        logger.log(
            if (cues.isEmpty()) 1 else 2,
            TAG,
            "Nạp phụ đề source=${if (fromSeed) "session" else "picker"} name=$name rawChars=${raw.length} normalizedChars=${parsed.normalizedChars} cues=${cues.size} skippedBlocks=${parsed.skippedBlocks} firstStartMs=${first?.startMs ?: -1} lastEndMs=${last?.endMs ?: -1}",
        )
        if (preferences.load().logIncludeTranscript && cues.isNotEmpty()) {
            logger.log(
                3,
                TAG,
                "Phụ đề preview=${cues.take(5).joinToString(" | ") { "${it.index}:${it.text.replace(Regex("\\s+"), " ").take(120)}" }}",
            )
        }
        updateReadyStatus()
    }

    private fun requestStartPlayback() {
        if (mediaUri == null) {
            status("Hãy chọn video hoặc âm thanh")
            return
        }
        if (cues.isEmpty()) {
            status("Hãy chọn phụ đề SRT hợp lệ")
            return
        }
        if (!ttsReady) {
            pendingPlayAfterTts = true
            status("Đang chuẩn bị TTS...")
            ensureTtsReady(true)
            return
        }
        startPlayback()
    }

    private fun ensureTtsReady(startWhenReady: Boolean) {
        if (ttsReady) {
            if (startWhenReady && pendingPlayAfterTts) {
                pendingPlayAfterTts = false
                startPlayback()
            }
            return
        }
        if (ttsInitializing) return
        ttsInitializing = true
        logger.log(2, TAG, "Khởi tạo RobustTtsEngine")
        ttsEngine.initialize { success ->
            ttsInitializing = false
            ttsReady = success
            logger.log(
                if (success) 2 else 1,
                TAG,
                "TTS init success=$success engine=${ttsEngine.currentEngine() ?: "DEFAULT"} pendingPlay=$pendingPlayAfterTts",
            )
            if (success && pendingPlayAfterTts) {
                pendingPlayAfterTts = false
                startPlayback()
            } else if (success && playing && !paused) {
                lastCueListIndex = -1
                processCue(currentPositionMs)
            } else if (!success && startWhenReady) {
                pendingPlayAfterTts = false
                status("Không khởi tạo được TTS")
            } else {
                updateReadyStatus()
            }
        }
    }

    private fun startPlayback() {
        val uri = mediaUri ?: return
        if (cues.isEmpty() || playing) return

        completed = false
        paused = false
        currentPositionMs = 0L
        durationMs = 0L
        lastCueListIndex = -1
        lastProgressLogBucket = -1L
        cueEvents = 0L
        restoreDucking("start")

        val settings = preferences.load()
        val mediaPlayer = StreamingPcmPlayer(
            sampleRate = 16_000,
            bufferBytes = 64_000,
            queueCapacity = 100,
            initialJitterChunks = 1,
            usage = AudioAttributes.USAGE_MEDIA,
            logger = logger,
            diagnosticName = "SubtitleMediaPlayer",
        ).also {
            it.start()
            it.setVolume(settings.originalVolume)
            it.setPlaybackSpeed(playbackSpeed)
        }
        player = mediaPlayer

        val fileSource = FileAudioSource(
            context = this,
            uri = uri,
            pacingEnabled = true,
            leadMs = 0,
            initialPlaybackSpeed = playbackSpeed,
            logger = logger,
        )
        source = fileSource
        playing = true
        updateButtons()
        status("Đang phát với phụ đề")
        logger.log(
            2,
            TAG,
            "Bắt đầu phát media=$mediaName subtitle=$subtitleName cues=${cues.size} speed=${formatSpeed()}x originalVolume=${settings.originalVolume} ttsVolume=${settings.translatedVolume} autoDucking=${settings.autoDucking} duckFactor=${settings.duckVolumeFactor} ttsEngine=${ttsEngine.currentEngine() ?: "DEFAULT"}",
        )

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            fileSource.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    if (!paused && playing) mediaPlayer.enqueue(data)
                }

                override fun onProgress(percent: Int, positionMs: Long, mediaDurationMs: Long) {
                    currentPositionMs = positionMs
                    durationMs = mediaDurationMs
                    runOnUiThread {
                        if (!binding.progressSeekBar.isPressed) {
                            binding.progressSeekBar.progress = percent.coerceIn(0, 100)
                        }
                        binding.progressSeekBar.contentDescription =
                            "Vị trí đang phát: ${percent.coerceIn(0, 100)}%"
                        processCue(positionMs)
                        logProgress(positionMs, mediaDurationMs, percent)
                    }
                }

                override fun onCompleted() {
                    runOnUiThread {
                        currentPositionMs = durationMs.coerceAtLeast(currentPositionMs)
                        completed = true
                        playing = false
                        paused = false
                        source = null
                        playbackJob = null
                        binding.progressSeekBar.progress = 100
                        binding.subtitleText.text = cues.lastOrNull()?.text ?: "Đã phát hết"
                        status("Đã phát hết")
                        updateButtons()
                        val stats = mediaPlayer.stats()
                        logger.log(
                            2,
                            TAG,
                            "Phát hoàn tất positionMs=$currentPositionMs durationMs=$durationMs cueEvents=$cueEvents playerWrittenBytes=${stats.writtenBytes} dropped=${stats.droppedChunks}",
                        )
                        lifecycleScope.launch {
                            delay(700)
                            mediaPlayer.stop()
                            if (player === mediaPlayer) player = null
                        }
                    }
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        logger.log(0, TAG, "Phát media thất bại positionMs=$currentPositionMs", error)
                        status("Lỗi phát: ${error.message ?: error.javaClass.simpleName}")
                        stopPlayback(resetPosition = false)
                    }
                }
            })
        }
    }

    private fun pausePlayback() {
        if (!playing || paused) return
        paused = true
        source?.pause()
        player?.pause()
        ttsEngine.stop()
        restoreDucking("pause")
        status("Đã tạm dừng")
        updateButtons()
        logger.log(2, TAG, "Tạm dừng positionMs=$currentPositionMs cueIndex=$lastCueListIndex")
    }

    private fun resumePlayback() {
        if (!playing || !paused) return
        paused = false
        source?.resume()
        player?.resume()
        ttsEngine.stop()
        restoreDucking("resume")
        lastCueListIndex = -1
        status("Đang phát với phụ đề")
        updateButtons()
        logger.log(2, TAG, "Tiếp tục positionMs=$currentPositionMs speed=${formatSpeed()}x")
    }

    private fun stopPlayback(resetPosition: Boolean) {
        pendingPlayAfterTts = false
        source?.stop()
        source = null
        playbackJob?.cancel()
        playbackJob = null
        player?.stop()
        player = null
        ttsEngine.stop()
        duckRestoreJob?.cancel()
        duckRestoreJob = null
        restoreDucking("stop")
        playing = false
        paused = false
        completed = false
        lastCueListIndex = -1
        if (resetPosition) {
            currentPositionMs = 0L
            durationMs = 0L
            if (::binding.isInitialized) binding.progressSeekBar.progress = 0
        }
        if (::binding.isInitialized) updateButtons()
    }

    private fun seekBy(deltaMs: Long) {
        if (!playing) return
        logger.log(2, TAG, "Seek deltaMs=$deltaMs fromMs=$currentPositionMs")
        prepareForSeek()
        source?.seekBy(deltaMs)
        if (!paused) source?.resume()
    }

    private fun seekToPercent(percent: Int) {
        if (!playing) return
        val safe = percent.coerceIn(0, 100)
        logger.log(
            2,
            TAG,
            "Seek percent=$safe fromMs=$currentPositionMs durationMs=$durationMs estimatedTargetMs=${if (durationMs > 0) durationMs * safe / 100L else -1}",
        )
        prepareForSeek()
        source?.seekToPercent(safe)
        if (!paused) source?.resume()
    }

    private fun prepareForSeek() {
        source?.pause()
        player?.flush()
        ttsEngine.stop()
        restoreDucking("seek")
        lastCueListIndex = -1
    }

    private fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(1f, 3f)
        if (kotlin.math.abs(playbackSpeed - safe) < 0.001f) return
        playbackSpeed = safe
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(KEY_SPEED, safe).apply()
        source?.setPlaybackSpeed(safe)
        player?.setPlaybackSpeed(safe)
        syncSpeedUi()
        logger.log(2, TAG, "Đổi tốc độ speed=${formatSpeed()}x positionMs=$currentPositionMs")
        if (playing && !paused) {
            ttsEngine.stop()
            restoreDucking("speed-change")
            lastCueListIndex = -1
            processCue(currentPositionMs)
        }
    }

    private fun processCue(positionMs: Long) {
        if (!playing || paused || cues.isEmpty()) return
        val index = findCueIndex(positionMs)
        if (index < 0 || index == lastCueListIndex) return
        lastCueListIndex = index
        val cue = cues[index]
        cueEvents++

        binding.subtitleText.text = cue.text
        binding.subtitleText.contentDescription = cue.text
        binding.subtitleScroll.post { binding.subtitleScroll.fullScroll(View.FOCUS_DOWN) }

        val lateMs = (positionMs - cue.startMs).coerceAtLeast(0L)
        val settings = preferences.load()
        val spoken = ttsEngine.speak(
            text = cue.text,
            languageTag = "vi-VN",
            rate = playbackSpeed,
            pitch = 1f,
            volume = settings.translatedVolume.coerceIn(0, 100) / 100f,
            queue = false,
        )
        val estimatedTtsMs = estimateTtsDurationMs(cue.text)
        if (spoken) applyDucking(estimatedTtsMs, cue.index)
        logger.log(
            if (spoken) 2 else 1,
            TAG_CUE,
            "cueEvent=$cueEvents cue=${cue.index}/${cues.size} startMs=${cue.startMs} endMs=${cue.endMs} positionMs=$positionMs lateMs=$lateMs chars=${cue.text.length} speed=${formatSpeed()}x ttsQueued=$spoken estimatedTtsMs=$estimatedTtsMs autoDucking=${settings.autoDucking}",
        )
        if (settings.logIncludeTranscript) {
            logger.log(3, TAG_CUE, "cue=${cue.index} text=${cue.text.replace(Regex("\\s+"), " ").take(500)}")
        }
        if (!spoken) {
            ttsReady = false
            ensureTtsReady(false)
        }
    }

    private fun findCueIndex(positionMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return -1
        return if (positionMs < cues[candidate].endMs) candidate else -1
    }

    private fun applyDucking(durationMs: Long, cueId: Int) {
        val settings = preferences.load()
        if (!settings.autoDucking) return
        ducking = true
        applyOriginalVolume()
        duckRestoreJob?.cancel()
        logger.log(
            3,
            TAG,
            "Duck apply cue=$cueId durationMs=$durationMs original=${settings.originalVolume} factor=${settings.duckVolumeFactor}",
        )
        duckRestoreJob = lifecycleScope.launch {
            delay(durationMs.coerceAtLeast(250L) + 300L)
            restoreDucking("timer-cue-$cueId")
        }
    }

    private fun restoreDucking(reason: String) {
        val wasDucking = ducking
        ducking = false
        applyOriginalVolume()
        if (wasDucking) logger.log(3, TAG, "Duck restore reason=$reason positionMs=$currentPositionMs")
    }

    private fun applyOriginalVolume() {
        val settings = preferences.load()
        val percent = if (ducking && settings.autoDucking) {
            (settings.originalVolume * settings.duckVolumeFactor).roundToInt()
        } else {
            settings.originalVolume
        }
        player?.setVolume(percent.coerceIn(0, 100))
    }

    private fun estimateTtsDurationMs(text: String): Long =
        ((text.length * 65L) / playbackSpeed.coerceAtLeast(0.5f)).toLong().coerceIn(500L, 8_000L)

    private fun logProgress(positionMs: Long, mediaDurationMs: Long, percent: Int) {
        val bucket = positionMs / 10_000L
        if (bucket == lastProgressLogBucket) return
        lastProgressLogBucket = bucket
        logger.log(
            3,
            TAG,
            "Progress percent=$percent positionMs=$positionMs durationMs=$mediaDurationMs cueIndex=$lastCueListIndex playing=$playing paused=$paused speed=${formatSpeed()}x",
        )
    }

    private fun updateReadyStatus() {
        if (!::binding.isInitialized || playing) return
        when {
            mediaUri == null && cues.isEmpty() -> status("Hãy chọn media và phụ đề")
            mediaUri == null -> status("Đã có phụ đề; hãy chọn video hoặc âm thanh")
            cues.isEmpty() -> status("Đã có media; hãy chọn phụ đề SRT")
            !ttsReady -> status("Đã có media và phụ đề; đang chuẩn bị TTS")
            else -> status("Sẵn sàng phát với phụ đề")
        }
        updateButtons()
    }

    private fun updateButtons() = with(binding) {
        playPauseButton.text = when {
            playing && !paused -> "Tạm dừng"
            playing && paused -> "Phát"
            completed -> "Phát lại"
            else -> "Phát"
        }
        val ready = mediaUri != null && cues.isNotEmpty()
        playPauseButton.isEnabled = ready
        rewindButton.isEnabled = playing
        forwardButton.isEnabled = playing
        progressSeekBar.isEnabled = playing
    }

    private fun syncSpeedUi() = with(binding) {
        val display = formatSpeed()
        fileSpeedLabel.text = "Tốc độ phát: ${display}×"
        fileSpeedSeekBar.contentDescription = "Tốc độ phát: $display lần"
    }

    private fun status(message: String) {
        binding.statusText.text = "Trạng thái: $message"
        binding.statusText.contentDescription = binding.statusText.text
    }

    private fun volumeListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress.coerceIn(0, 100))
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun formatSpeed(): String = String.format(Locale.US, "%.1f", playbackSpeed)

    companion object {
        const val EXTRA_MEDIA_URI = "subtitlePlayback.mediaUri"
        const val EXTRA_MEDIA_NAME = "subtitlePlayback.mediaName"
        const val EXTRA_SUBTITLE_SRT = "subtitlePlayback.subtitleSrt"
        const val EXTRA_SUBTITLE_NAME = "subtitlePlayback.subtitleName"

        private const val TAG = "SubtitlePlayback"
        private const val TAG_CUE = "SubtitlePlaybackCue"
        private const val PREFS_NAME = "subtitle_playback_prefs"
        private const val KEY_SPEED = "speed"
    }
}
