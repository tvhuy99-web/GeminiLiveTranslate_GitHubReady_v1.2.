package com.oai.geminilivetranslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StreamingPcmPlayer(
    private val sampleRate: Int,
    private val bufferBytes: Int,
    queueCapacity: Int,
    private val initialJitterChunks: Int = 1,
    private val usage: Int = AudioAttributes.USAGE_ACCESSIBILITY,
    private val logger: SessionLogger? = null,
    private val diagnosticName: String = "PcmPlayer",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedChunks = AtomicLong(0L)
    private val writtenBytes = AtomicLong(0L)
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
    @Volatile private var volume = 1f
    @Volatile private var track: AudioTrack? = null
    private var worker: Job? = null

    fun start() {
        if (worker?.isActive == true) return
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "AudioTrack không hỗ trợ sampleRate=$sampleRate, minBuffer=$minimum" }
        val chosenBuffer = maxOf(minimum, bufferBytes).coerceAtLeast(sampleRate / 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
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
                logger?.log(2, diagnosticName, "Khởi tạo AudioTrack sampleRate=$sampleRate bufferBytes=$chosenBuffer sessionId=${it.audioSessionId} jitter=$initialJitterChunks")
            }
        worker = scope.launch {
            runCatching {
                val prebuffer = ArrayList<ByteArray>()
                val first = queue.receiveCatching().getOrNull() ?: return@runCatching
                prebuffer.add(first)
                repeat((initialJitterChunks - 1).coerceAtLeast(0)) {
                    withTimeoutOrNull(750) { queue.receive() }?.let(prebuffer::add)
                }
                logger?.log(3, diagnosticName, "Bắt đầu phát sau prebuffer chunks=${prebuffer.size}")
                prebuffer.forEach(::writeBlocking)
                while (isActive) {
                    val data = queue.receiveCatching().getOrNull() ?: break
                    writeBlocking(data)
                }
            }.onFailure { error ->
                if (worker?.isCancelled != true) logger?.log(0, diagnosticName, "Worker phát PCM dừng do lỗi", error)
            }
        }
    }

    fun enqueue(data: ByteArray) {
        if (data.isEmpty()) return
        val result = queue.trySend(data.copyOf())
        if (result.isFailure) {
            logger?.log(1, diagnosticName, "Không enqueue được audio output bytes=${data.size}")
        }
    }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
        track?.setVolume(volume)
    }

    fun pause() {
        paused.set(true)
        runCatching { track?.pause() }
        logger?.log(2, diagnosticName, "Tạm dừng phát")
    }

    fun resume() {
        paused.set(false)
        runCatching { track?.play() }
        logger?.log(2, diagnosticName, "Tiếp tục phát")
    }

    fun flush() {
        var removed = 0
        while (queue.tryReceive().isSuccess) removed++
        runCatching { track?.flush() }
        logger?.log(2, diagnosticName, "Xả output queue removed=$removed")
    }

    fun stop() {
        val current = track
        val underruns = current?.underrunCount ?: 0
        logger?.log(2, diagnosticName, "Dừng AudioTrack writtenBytes=${writtenBytes.get()} dropped=${droppedChunks.get()} underruns=$underruns")
        worker?.cancel()
        worker = null
        queue.close()
        runCatching { current?.pause() }
        runCatching { current?.flush() }
        runCatching { current?.stop() }
        runCatching { current?.release() }
        track = null
        scope.cancel()
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
}
