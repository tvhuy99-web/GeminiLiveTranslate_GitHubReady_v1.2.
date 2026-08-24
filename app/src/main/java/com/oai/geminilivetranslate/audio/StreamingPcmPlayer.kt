package com.oai.geminilivetranslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class StreamingPcmPlayer(
    private val sampleRate: Int,
    private val bufferBytes: Int,
    queueCapacity: Int,
    private val initialJitterChunks: Int = 1,
    private val usage: Int = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
    private val logger: SessionLogger? = null,
    private val diagnosticName: String = "PcmPlayer",
) {
    data class PlaybackStats(
        val droppedChunks: Long,
        val pausedBacklogDroppedChunks: Long,
        val pausedBacklogChunks: Int,
        val pausedBacklogBytes: Long,
        val writtenBytes: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedChunks = AtomicLong(0L)
    private val pausedBacklogDroppedChunks = AtomicLong(0L)
    private val writtenBytes = AtomicLong(0L)
    private val usesOriginalDeadlineQueue = diagnosticName == "OriginalPlayer"
    private val originalDeadlineQueue = if (usesOriginalDeadlineQueue) OriginalPcmDeadlineQueue(sampleRate) else null
    private val queue = Channel<ByteArray>(
        capacity = queueCapacity.coerceAtLeast(2),
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
            val dropped = droppedChunks.incrementAndGet()
            if (dropped == 1L || dropped % 25L == 0L) {
                logger?.log(1, diagnosticName, "Audio output queue bỏ chunk cũ dropped=$dropped")
            }
        },
    )
    private val paused = AtomicBoolean(false)
    private val pauseLock = Any()
    private val pausedBacklog = ArrayDeque<ByteArray>()
    private var pausedBacklogBytes = 0L

    @Volatile private var volume = 1f
    @Volatile private var playbackSpeed = 1f
    @Volatile private var track: AudioTrack? = null
    private var worker: Job? = null

    fun start() {
        if (worker?.isActive == true) return
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "AudioTrack không hỗ trợ sampleRate=$sampleRate, minBuffer=$minimum" }
        val chosenBuffer = maxOf(minimum, bufferBytes).coerceAtLeast(sampleRate / 2)
        val resolvedUsage = resolveUsage()
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(resolvedUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(chosenBuffer)
            .build().also {
                check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack chưa initialized, state=${it.state}" }
                it.setVolume(volume)
                it.play()
                applyPlaybackSpeed(it, playbackSpeed)
                logger?.log(2, diagnosticName, "Khởi tạo AudioTrack sampleRate=$sampleRate bufferBytes=$chosenBuffer sessionId=${it.audioSessionId} jitter=$initialJitterChunks speed=${formatSpeed(playbackSpeed)}x usage=$resolvedUsage deadlineQueue=$usesOriginalDeadlineQueue")
            }
        worker = scope.launch {
            runCatching {
                if (usesOriginalDeadlineQueue) {
                    runOriginalDeadlineWorker()
                } else {
                    runStandardWorker()
                }
            }.onFailure { error ->
                if (error !is CancellationException && worker?.isCancelled != true) {
                    logger?.log(0, diagnosticName, "Worker phát PCM dừng do lỗi", error)
                }
            }
        }
    }

    fun enqueue(data: ByteArray) {
        if (data.isEmpty()) return
        if (usesOriginalDeadlineQueue) {
            val planner = originalDeadlineQueue ?: return
            val now = SystemClock.elapsedRealtime()
            val dueAt = planner.enqueue(data, playbackSpeed, now)
            val stats = planner.stats()
            if (stats.queuedChunks == 1 || stats.queuedChunks % 50 == 0) {
                logger?.log(
                    3,
                    diagnosticName,
                    "Xếp PCM gốc theo deadline dueInMs=${(dueAt - now).coerceAtLeast(0L)} queued=${stats.queuedChunks} bytes=${stats.queuedBytes} generation=${stats.generation}",
                )
            }
            return
        }

        val copy = data.copyOf()
        synchronized(pauseLock) {
            if (paused.get()) {
                bufferPausedLocked(copy)
                return
            }
            val result = queue.trySend(copy)
            if (result.isFailure) {
                logger?.log(1, diagnosticName, "Không enqueue được audio output bytes=${data.size}")
            }
        }
    }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
        track?.setVolume(volume)
    }

    fun setPlaybackSpeed(speed: Float) {
        val requested = speed.coerceIn(FileAudioSource.MIN_PLAYBACK_SPEED, FileAudioSource.MAX_PLAYBACK_SPEED)
        val safe = if (diagnosticName.startsWith("TranslatedPlayer-")) {
            if (kotlin.math.abs(requested - 1f) >= 0.001f) {
                logger?.log(
                    2,
                    diagnosticName,
                    "Giữ PCM Gemini realtime speed=1.0x requested=${formatSpeed(requested)}x để tránh thiếu buffer",
                )
            }
            1f
        } else {
            requested
        }
        playbackSpeed = safe
        if (usesOriginalDeadlineQueue) {
            originalDeadlineQueue?.retime(safe, SystemClock.elapsedRealtime())
        }
        track?.let { applyPlaybackSpeed(it, safe) }
    }

    fun currentPlaybackSpeed(): Float = playbackSpeed

    fun pause() {
        if (usesOriginalDeadlineQueue) {
            if (!paused.compareAndSet(false, true)) return
            val now = SystemClock.elapsedRealtime()
            originalDeadlineQueue?.pause(now)
            runCatching { track?.pause() }
            val scheduled = originalDeadlineQueue?.stats()
            logger?.log(
                2,
                diagnosticName,
                "Tạm dừng phát; deadlineQueue=${scheduled?.queuedChunks ?: 0} deadlineBytes=${scheduled?.queuedBytes ?: 0}",
            )
            return
        }

        var moved = 0
        synchronized(pauseLock) {
            if (!paused.compareAndSet(false, true)) return
            while (true) {
                val pending = queue.tryReceive().getOrNull() ?: break
                bufferPausedLocked(pending)
                moved++
            }
        }
        runCatching { track?.pause() }
        val stats = stats()
        logger?.log(
            2,
            diagnosticName,
            "Tạm dừng phát; preservedQueue=$moved backlogChunks=${stats.pausedBacklogChunks} backlogBytes=${stats.pausedBacklogBytes}",
        )
    }

    fun resume() {
        if (!paused.compareAndSet(true, false)) return
        val pausedFor = if (usesOriginalDeadlineQueue) {
            originalDeadlineQueue?.resume(SystemClock.elapsedRealtime()) ?: 0L
        } else {
            0L
        }
        runCatching { track?.play() }
        track?.let { applyPlaybackSpeed(it, playbackSpeed) }
        if (usesOriginalDeadlineQueue) {
            val scheduled = originalDeadlineQueue?.stats()
            logger?.log(
                2,
                diagnosticName,
                "Tiếp tục phát; dời deadline thêm ${pausedFor}ms queued=${scheduled?.queuedChunks ?: 0} bytes=${scheduled?.queuedBytes ?: 0} speed=${formatSpeed(playbackSpeed)}x",
            )
        } else {
            val stats = stats()
            logger?.log(
                2,
                diagnosticName,
                "Tiếp tục phát; backlogChunks=${stats.pausedBacklogChunks} backlogBytes=${stats.pausedBacklogBytes} speed=${formatSpeed(playbackSpeed)}x",
            )
        }
    }

    fun flush() {
        if (usesOriginalDeadlineQueue) {
            val planner = originalDeadlineQueue
            val before = planner?.stats()
            val removed = planner?.clear() ?: 0
            runCatching { track?.flush() }
            logger?.log(
                2,
                diagnosticName,
                "Xả deadline queue removed=$removed bytes=${before?.queuedBytes ?: 0} newGeneration=${planner?.stats()?.generation ?: 0}",
            )
            return
        }

        var removed = 0
        while (queue.tryReceive().isSuccess) removed++
        var pausedRemoved = 0
        synchronized(pauseLock) {
            pausedRemoved = pausedBacklog.size
            pausedBacklog.clear()
            pausedBacklogBytes = 0L
        }
        runCatching { track?.flush() }
        logger?.log(2, diagnosticName, "Xả output queue removed=$removed pausedRemoved=$pausedRemoved")
    }

    fun stats(): PlaybackStats = synchronized(pauseLock) {
        PlaybackStats(
            droppedChunks = droppedChunks.get(),
            pausedBacklogDroppedChunks = pausedBacklogDroppedChunks.get(),
            pausedBacklogChunks = pausedBacklog.size,
            pausedBacklogBytes = pausedBacklogBytes,
            writtenBytes = writtenBytes.get(),
        )
    }

    fun stop() {
        val current = track
        val underruns = current?.underrunCount ?: 0
        val stats = stats()
        val scheduled = originalDeadlineQueue?.stats()
        logger?.log(
            2,
            diagnosticName,
            "Dừng AudioTrack writtenBytes=${stats.writtenBytes} dropped=${stats.droppedChunks} pausedDropped=${stats.pausedBacklogDroppedChunks} pausedBuffered=${stats.pausedBacklogChunks} scheduled=${scheduled?.queuedChunks ?: 0} scheduledBytes=${scheduled?.queuedBytes ?: 0} underruns=$underruns speed=${formatSpeed(playbackSpeed)}x",
        )
        worker?.cancel()
        worker = null
        queue.close()
        originalDeadlineQueue?.clear()
        synchronized(pauseLock) {
            pausedBacklog.clear()
            pausedBacklogBytes = 0L
        }
        runCatching { current?.pause() }
        runCatching { current?.flush() }
        runCatching { current?.stop() }
        runCatching { current?.release() }
        track = null
        scope.cancel()
    }

    private suspend fun runStandardWorker() {
        val prebuffer = ArrayList<ByteArray>()
        val first = receiveNextChunk() ?: return
        prebuffer.add(first)
        repeat((initialJitterChunks - 1).coerceAtLeast(0)) {
            withTimeoutOrNull(750) { receiveNextChunk() }?.let(prebuffer::add)
        }
        logger?.log(3, diagnosticName, "Bắt đầu phát sau prebuffer chunks=${prebuffer.size}")
        prebuffer.forEach(::writeBlocking)
        while (scope.isActive) {
            val data = receiveNextChunk() ?: break
            writeBlocking(data)
        }
    }

    private suspend fun runOriginalDeadlineWorker() {
        val planner = originalDeadlineQueue ?: return
        var started = false
        while (scope.isActive) {
            if (paused.get()) {
                delay(DEADLINE_POLL_MS)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val ready = planner.pollReady(now)
            if (ready != null) {
                if (!planner.isGenerationCurrent(ready.generation)) continue
                if (!started) {
                    started = true
                    logger?.log(
                        3,
                        diagnosticName,
                        "Bắt đầu phát PCM gốc theo deadline generation=${ready.generation} dueLagMs=${(now - ready.dueAtMs).coerceAtLeast(0L)}",
                    )
                }
                writeBlocking(ready.data)
                continue
            }
            val waitMs = planner.millisUntilNext(now)
            delay((waitMs ?: DEADLINE_POLL_MS).coerceIn(1L, DEADLINE_POLL_MS))
        }
    }

    private suspend fun receiveNextChunk(): ByteArray? {
        while (scope.isActive) {
            if (paused.get()) {
                delay(25)
                continue
            }
            pollPausedBacklog()?.let { return it }
            val received = withTimeoutOrNull(100) { queue.receiveCatching() }
            if (received != null) {
                received.getOrNull()?.let { return it }
                if (received.isClosed) return null
            }
        }
        return null
    }

    private fun pollPausedBacklog(): ByteArray? = synchronized(pauseLock) {
        if (paused.get() || pausedBacklog.isEmpty()) return@synchronized null
        pausedBacklog.removeFirst().also { pausedBacklogBytes -= it.size.toLong() }
    }

    private fun bufferPausedLocked(data: ByteArray) {
        if (data.size.toLong() > MAX_PAUSED_BACKLOG_BYTES) {
            recordPausedDrop(data.size)
            return
        }
        while (pausedBacklogBytes + data.size > MAX_PAUSED_BACKLOG_BYTES && pausedBacklog.isNotEmpty()) {
            val removed = pausedBacklog.removeFirst()
            pausedBacklogBytes -= removed.size.toLong()
            recordPausedDrop(removed.size)
        }
        pausedBacklog.addLast(data)
        pausedBacklogBytes += data.size.toLong()
    }

    private fun recordPausedDrop(bytes: Int) {
        val dropped = pausedBacklogDroppedChunks.incrementAndGet()
        if (dropped == 1L || dropped % 25L == 0L) {
            logger?.log(
                1,
                diagnosticName,
                "Paused backlog đạt giới hạn; bỏ chunk cũ dropped=$dropped bytes=$bytes limitBytes=$MAX_PAUSED_BACKLOG_BYTES",
            )
        }
    }

    private fun writeBlocking(data: ByteArray) {
        while (paused.get() && worker?.isActive == true) Thread.sleep(25)
        val audioTrack = track ?: return
        var offset = 0
        while (offset < data.size) {
            val written = audioTrack.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                logger?.log(0, diagnosticName, "AudioTrack.write thất bại code=$written remaining=${data.size - offset}")
                return
            }
            offset += written
            writtenBytes.addAndGet(written.toLong())
        }
    }

    private fun applyPlaybackSpeed(audioTrack: AudioTrack, speed: Float) {
        val safe = speed.coerceIn(FileAudioSource.MIN_PLAYBACK_SPEED, FileAudioSource.MAX_PLAYBACK_SPEED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack.playbackParams = PlaybackParams()
                    .allowDefaults()
                    .setSpeed(safe)
                    .setPitch(1f)
            } else {
                audioTrack.playbackRate = (sampleRate * safe).roundToInt()
            }
        }.onSuccess {
            logger?.log(2, diagnosticName, "Áp dụng tốc độ phát speed=${formatSpeed(safe)}x")
        }.onFailure {
            logger?.log(1, diagnosticName, "Thiết bị từ chối tốc độ speed=${formatSpeed(safe)}x; giữ tốc độ hiện tại", it)
        }
    }

    private fun resolveUsage(): Int {
        if (!diagnosticName.startsWith("TranslatedPlayer-")) return usage
        return when (diagnosticName.removePrefix("TranslatedPlayer-")) {
            "media" -> AudioAttributes.USAGE_MEDIA
            "accessibility" -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            "alarm" -> AudioAttributes.USAGE_ALARM
            "notification" -> AudioAttributes.USAGE_NOTIFICATION
            "ring" -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            "system" -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            "voice_call", "voice_communication" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            "dtmf" -> AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            "assistant" -> AudioAttributes.USAGE_ASSISTANT
            else -> usage
        }
    }

    private fun formatSpeed(speed: Float): String = String.format(Locale.US, "%.1f", speed)

    companion object {
        private const val MAX_PAUSED_BACKLOG_BYTES = 16L * 1024L * 1024L
        private const val DEADLINE_POLL_MS = 20L
    }
}
