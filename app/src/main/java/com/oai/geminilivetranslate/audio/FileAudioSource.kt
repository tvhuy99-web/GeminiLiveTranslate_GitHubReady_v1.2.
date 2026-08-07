package com.oai.geminilivetranslate.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FileAudioSource(
    private val context: Context,
    private val uri: Uri,
    private val pacingEnabled: Boolean,
    private val leadMs: Int,
    private val logger: SessionLogger? = null,
) : AudioSource {
    override val supportsSeek: Boolean = true
    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(NO_SEEK)
    private val currentPositionMs = AtomicLong(0)
    @Volatile private var durationMs: Long = 0

    override suspend fun run(listener: AudioSource.Listener) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Tệp không có track âm thanh")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Không xác định codec âm thanh")
            durationMs = inputFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L) / 1_000L
            logger?.log(2, "FileAudio", "Mở tệp mime=$mime durationMs=$durationMs inputRate=${inputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)} channels=${inputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0)} pacing=$pacingEnabled leadMs=$leadMs")
            runCatching { inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) }
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputRate = inputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var outputChannels = inputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val initialOutputEncoding = AudioFormat.ENCODING_PCM_16BIT
            var converter = StreamingPcmConverter(outputRate, outputChannels, initialOutputEncoding)
            var basePtsUs = -1L
            var baseWallMs = 0L

            while (!stopped.get() && !outputEnded) {
                if (paused.get()) {
                    delay(50)
                    continue
                }
                val seek = pendingSeekMs.getAndSet(NO_SEEK)
                if (seek != NO_SEEK) {
                    val target = seek.coerceIn(0, durationMs.coerceAtLeast(0))
                    extractor.seekTo(target * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    converter.reset()
                    inputEnded = false
                    outputEnded = false
                    basePtsUs = -1L
                    currentPositionMs.set(target)
                    logger?.log(2, "FileAudio", "Seek targetMs=$target durationMs=$durationMs")
                    listener.onProgress(percent(target), target, durationMs)
                }

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer rỗng")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        val newRate = format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputRate)
                        val newChannels = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                        val newEncoding = format.getIntOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        val tail = converter.reconfigure(newRate, newChannels, newEncoding)
                        if (tail.isNotEmpty()) listener.onPcm16Mono16k(tail)
                        outputRate = newRate
                        outputChannels = newChannels
                        logger?.log(2, "FileAudio", "Decoder output format rate=$newRate channels=$newChannels encoding=$newEncoding")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val raw = ByteArray(info.size)
                            outputBuffer.get(raw)
                            if (basePtsUs < 0) {
                                basePtsUs = info.presentationTimeUs
                                baseWallMs = SystemClock.elapsedRealtime()
                            }
                            if (pacingEnabled) {
                                val expected = baseWallMs + ((info.presentationTimeUs - basePtsUs) / 1_000L) - leadMs
                                val wait = expected - SystemClock.elapsedRealtime()
                                if (wait > 0) delay(wait.coerceAtMost(250))
                            }
                            val pcm = converter.process(raw, raw.size)
                            if (pcm.isNotEmpty()) listener.onPcm16Mono16k(pcm)
                            val position = (info.presentationTimeUs / 1_000L).coerceAtLeast(0)
                            currentPositionMs.set(position)
                            listener.onProgress(percent(position), position, durationMs)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (!stopped.get()) {
                converter.flush().takeIf { it.isNotEmpty() }?.let(listener::onPcm16Mono16k)
                logger?.log(2, "FileAudio", "Giải mã hoàn tất positionMs=${currentPositionMs.get()} durationMs=$durationMs")
                listener.onCompleted()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                logger?.log(3, "FileAudio", "Dừng coroutine giải mã theo yêu cầu")
            } else if (!stopped.get()) {
                logger?.log(0, "FileAudio", "Giải mã tệp thất bại", error)
                listener.onError(error)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    override fun pause() {
        paused.set(true)
        logger?.log(2, "FileAudio", "Tạm dừng giải mã")
    }
    override fun resume() {
        paused.set(false)
        logger?.log(2, "FileAudio", "Tiếp tục giải mã")
    }
    override fun seekBy(deltaMs: Long) {
        val pending = pendingSeekMs.get()
        val base = if (pending == NO_SEEK) currentPositionMs.get() else pending
        pendingSeekMs.set(base + deltaMs)
    }
    override fun seekToPercent(percent: Int) {
        if (durationMs > 0) pendingSeekMs.set(durationMs * percent.coerceIn(0, 100) / 100L)
    }
    override fun stop() {
        logger?.log(2, "FileAudio", "Dừng giải mã tệp")
        stopped.set(true)
    }

    private fun percent(positionMs: Long): Int = if (durationMs <= 0) 0 else
        ((positionMs * 100L / durationMs).toInt()).coerceIn(0, 100)

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        runCatching { getInteger(key) }.getOrDefault(default)

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        runCatching { getLong(key) }.getOrDefault(default)

    companion object { private const val NO_SEEK = Long.MIN_VALUE }
}
