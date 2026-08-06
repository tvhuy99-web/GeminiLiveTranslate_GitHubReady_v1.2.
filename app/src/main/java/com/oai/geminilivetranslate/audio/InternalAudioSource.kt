package com.oai.geminilivetranslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.oai.geminilivetranslate.core.SessionLogger
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioSource(
    private val mediaProjection: MediaProjection,
    private val logger: SessionLogger? = null,
) : AudioSource {
    private data class CaptureConfig(val sampleRate: Int, val channelMask: Int, val channels: Int)

    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val userRequestedStop = AtomicBoolean(false)
    private val projectionRevoked = AtomicBoolean(false)
    private val discardBufferedOnResume = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null

    override suspend fun run(listener: AudioSource.Listener) {
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!userRequestedStop.get()) {
                    projectionRevoked.set(true)
                    logger?.log(1, "MediaProjection", "MediaProjection.onStop do hệ thống/người dùng thu hồi")
                } else {
                    logger?.log(2, "MediaProjection", "MediaProjection.onStop sau khi ứng dụng chủ động dừng")
                }
                stopped.set(true)
                releaseRecorder()
            }
        }
        var terminalError: Throwable? = null
        try {
            mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val (audioRecord, format) = createRecorder(captureConfig)
            recorder = audioRecord
            logger?.log(2, "InternalAudio", "Khởi tạo playback capture sampleRate=${format.sampleRate} channels=${format.channels} bufferFrames=${audioRecord.bufferSizeInFrames}")
            val converter = StreamingPcmConverter(format.sampleRate, format.channels)
            val chunkBytes = (format.sampleRate * format.channels * 2 / 10).coerceAtLeast(3_200)
            val buffer = ByteArray(chunkBytes)
            audioRecord.startRecording()
            logger?.log(2, "InternalAudio", "Bắt đầu thu âm thanh nội bộ")
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
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        terminalError = projectionStoppedError()
                        break
                    }
                    read < 0 -> {
                        terminalError = IllegalStateException("Lỗi đọc âm thanh nội bộ: $read")
                        break
                    }
                }
            }
            if (projectionRevoked.get() && !userRequestedStop.get()) {
                terminalError = projectionStoppedError()
            }
        } catch (error: Throwable) {
            if (!userRequestedStop.get()) {
                terminalError = if (projectionRevoked.get()) projectionStoppedError() else error
            }
        } finally {
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
            releaseRecorder()
        }
        terminalError?.takeIf { !userRequestedStop.get() }?.let { error ->
            logger?.log(0, "InternalAudio", "Nguồn âm thanh nội bộ dừng do lỗi", error)
            listener.onError(error)
        }
    }

    override fun pause() {
        paused.set(true)
        logger?.log(2, "InternalAudio", "Tạm dừng thu nội bộ")
    }

    override fun resume() {
        logger?.log(2, "InternalAudio", "Tiếp tục thu nội bộ; sẽ xả buffer cũ")
        discardBufferedOnResume.set(true)
        paused.set(false)
    }

    override fun stop() {
        logger?.log(2, "InternalAudio", "Ứng dụng chủ động dừng MediaProjection")
        userRequestedStop.set(true)
        stopped.set(true)
        releaseRecorder()
        runCatching { mediaProjection.stop() }
    }

    private fun createRecorder(capture: AudioPlaybackCaptureConfiguration): Pair<AudioRecord, CaptureConfig> {
        val candidates = listOf(
            CaptureConfig(48_000, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureConfig(44_100, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureConfig(48_000, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureConfig(16_000, AudioFormat.CHANNEL_IN_MONO, 1),
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
                    .setAudioPlaybackCaptureConfig(capture)
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
            logger?.log(1, "InternalAudio", "AudioRecord chưa initialized sampleRate=${config.sampleRate} channels=${config.channels} state=${built.state}")
            runCatching { built.release() }
        }
        throw IllegalStateException("Thiết bị không hỗ trợ cấu hình thu âm thanh nội bộ phù hợp", lastError)
    }

    private fun drainBufferedAudio(audioRecord: AudioRecord, buffer: ByteArray) {
        var attempts = 0
        while (attempts++ < 16 && !stopped.get()) {
            val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            if (read <= 0) break
        }
    }

    private fun projectionStoppedError(): Throwable = MediaProjectionStoppedException(
        "Phiên thu âm thanh nội bộ đã bị hệ thống dừng. Hãy cấp quyền chia sẻ lại để tiếp tục."
    )

    private fun releaseRecorder() {
        val current = recorder
        recorder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    class MediaProjectionStoppedException(message: String) : IllegalStateException(message)
}
