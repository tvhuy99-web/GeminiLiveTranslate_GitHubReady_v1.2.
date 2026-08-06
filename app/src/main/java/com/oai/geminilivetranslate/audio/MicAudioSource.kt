package com.oai.geminilivetranslate.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.oai.geminilivetranslate.core.SessionLogger
import java.util.concurrent.atomic.AtomicBoolean

class MicAudioSource(private val logger: SessionLogger? = null) : AudioSource {
    private data class CaptureConfig(val sampleRate: Int, val channelMask: Int, val channels: Int)

    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val discardBufferedOnResume = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    override suspend fun run(listener: AudioSource.Listener) {
        try {
            val (audioRecord, config) = createRecorder()
            recorder = audioRecord
            logger?.log(2, "Microphone", "Khởi tạo AudioRecord sampleRate=${config.sampleRate} channels=${config.channels} bufferBytes=${audioRecord.bufferSizeInFrames * config.channels * 2}")
            enableEffects(audioRecord.audioSessionId)
            val converter = StreamingPcmConverter(config.sampleRate, config.channels)
            val chunkBytes = (config.sampleRate * config.channels * 2 / 10).coerceAtLeast(3_200)
            val buffer = ByteArray(chunkBytes)
            audioRecord.startRecording()
            logger?.log(2, "Microphone", "Bắt đầu thu sessionId=${audioRecord.audioSessionId} aec=${echoCanceler?.enabled == true} ns=${noiseSuppressor?.enabled == true}")
            while (!stopped.get()) {
                if (paused.get()) {
                    Thread.sleep(50)
                    continue
                }
                if (discardBufferedOnResume.getAndSet(false)) {
                    converter.reset()
                    drainBufferedAudio(audioRecord, buffer)
                }
                val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> {
                        val pcm = converter.process(buffer, read)
                        if (pcm.isNotEmpty()) listener.onPcm16Mono16k(pcm)
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> error("Microphone đã mất kết nối")
                    read < 0 -> error("Lỗi đọc microphone: $read")
                }
            }
        } catch (error: Throwable) {
            if (!stopped.get()) {
                logger?.log(0, "Microphone", "Nguồn microphone dừng do lỗi", error)
                listener.onError(error)
            }
        } finally {
            release()
        }
    }

    override fun pause() {
        paused.set(true)
        logger?.log(2, "Microphone", "Tạm dừng thu")
    }

    override fun resume() {
        logger?.log(2, "Microphone", "Tiếp tục thu; sẽ xả buffer cũ")
        discardBufferedOnResume.set(true)
        paused.set(false)
    }

    override fun stop() {
        logger?.log(2, "Microphone", "Dừng nguồn microphone")
        stopped.set(true)
        release()
    }

    private fun createRecorder(): Pair<AudioRecord, CaptureConfig> {
        val candidates = listOf(
            CaptureConfig(16_000, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureConfig(48_000, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureConfig(44_100, AudioFormat.CHANNEL_IN_MONO, 1),
        )
        var lastError: Throwable? = null
        for (config in candidates) {
            val minimum = AudioRecord.getMinBufferSize(
                config.sampleRate,
                config.channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) continue
            val built = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(config.channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minimum * 4, config.sampleRate * config.channels * 2 / 2))
                    .build()
            }.onFailure { lastError = it }.getOrNull() ?: continue
            if (built.state == AudioRecord.STATE_INITIALIZED) return built to config
            logger?.log(1, "Microphone", "AudioRecord chưa initialized sampleRate=${config.sampleRate} state=${built.state}")
            runCatching { built.release() }
        }
        throw IllegalStateException("Thiết bị không hỗ trợ cấu hình microphone phù hợp", lastError)
    }

    private fun drainBufferedAudio(audioRecord: AudioRecord, buffer: ByteArray) {
        var attempts = 0
        while (attempts++ < 16 && !stopped.get()) {
            val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            if (read <= 0) break
        }
    }

    private fun enableEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
    }

    private fun release() {
        val current = recorder
        recorder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
    }
}
