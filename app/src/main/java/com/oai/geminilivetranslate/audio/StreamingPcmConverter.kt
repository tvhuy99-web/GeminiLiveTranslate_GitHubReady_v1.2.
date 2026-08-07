package com.oai.geminilivetranslate.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Chuyển đổi PCM theo luồng sang mono 16-bit ở sample rate đích.
 *
 * Khác với việc resample từng chunk độc lập, lớp này giữ lại mẫu biên,
 * phần byte chưa đủ frame và pha nội suy giữa các lần gọi để tránh mất/lặp
 * mẫu ở ranh giới chunk.
 */
class StreamingPcmConverter(
    sourceRate: Int,
    channels: Int,
    encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val targetRate: Int = 16_000,
) {
    private var sourceRate = requirePositive(sourceRate, "sourceRate")
    private var channels = requirePositive(channels, "channels")
    private var encoding = requireSupportedEncoding(encoding)
    private var rawRemainder = ByteArray(0)
    private var sampleBuffer = ShortArray(0)
    private var sourcePosition = 0.0

    @Synchronized
    fun reconfigure(sourceRate: Int, channels: Int, encoding: Int): ByteArray {
        val nextSourceRate = requirePositive(sourceRate, "sourceRate")
        val nextChannels = requirePositive(channels, "channels")
        val nextEncoding = requireSupportedEncoding(encoding)
        val tail = flush()
        this.sourceRate = nextSourceRate
        this.channels = nextChannels
        this.encoding = nextEncoding
        resetInternal()
        return tail
    }

    @Synchronized
    fun process(input: ByteArray, length: Int = input.size): ByteArray {
        if (length <= 0) return ByteArray(0)
        val mono = decodeCompleteFrames(input, length)
        if (mono.isEmpty()) return ByteArray(0)
        appendSamples(mono)
        return PcmTools.shortsToBytes(produceOutput(flushLastSample = false))
    }

    /** Kết thúc luồng hiện tại và phát nốt mẫu cuối còn giữ lại. */
    @Synchronized
    fun flush(): ByteArray {
        if (sampleBuffer.isEmpty()) {
            rawRemainder = ByteArray(0)
            sourcePosition = 0.0
            return ByteArray(0)
        }
        val output = produceOutput(flushLastSample = true)
        resetInternal()
        return PcmTools.shortsToBytes(output)
    }

    /** Bỏ toàn bộ trạng thái, dùng khi tua tệp hoặc bỏ audio cũ sau pause. */
    @Synchronized
    fun reset() {
        resetInternal()
    }

    private fun decodeCompleteFrames(input: ByteArray, requestedLength: Int): ShortArray {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> error("PCM encoding không được hỗ trợ: $encoding")
        }
        val frameBytes = bytesPerSample * channels
        val safeLength = requestedLength.coerceIn(0, input.size)
        val combined = if (rawRemainder.isEmpty()) {
            input.copyOfRange(0, safeLength)
        } else {
            ByteArray(rawRemainder.size + safeLength).also {
                rawRemainder.copyInto(it)
                input.copyInto(it, rawRemainder.size, 0, safeLength)
            }
        }
        val completeLength = combined.size - combined.size % frameBytes
        rawRemainder = if (completeLength < combined.size) {
            combined.copyOfRange(completeLength, combined.size)
        } else {
            ByteArray(0)
        }
        if (completeLength == 0) return ShortArray(0)

        val frames = completeLength / frameBytes
        val output = ShortArray(frames)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val buffer = ByteBuffer.wrap(combined, 0, completeLength).order(ByteOrder.LITTLE_ENDIAN)
                for (frame in 0 until frames) {
                    var sum = 0.0
                    repeat(channels) { sum += buffer.float.coerceIn(-1f, 1f).toDouble() }
                    output[frame] = ((sum / channels) * Short.MAX_VALUE)
                        .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                var index = 0
                for (frame in 0 until frames) {
                    var sum = 0
                    repeat(channels) {
                        sum += ((combined[index].toInt() and 0xff) - 128) shl 8
                        index++
                    }
                    output[frame] = (sum / channels)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                var index = 0
                for (frame in 0 until frames) {
                    var sum = 0L
                    repeat(channels) {
                        val sample = ((combined[index].toInt() and 0xff) or
                            (combined[index + 1].toInt() shl 8)).toShort().toInt()
                        sum += sample
                        index += 2
                    }
                    output[frame] = (sum / channels)
                        .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
                }
            }
            else -> error("PCM encoding không được hỗ trợ: $encoding")
        }
        return output
    }

    private fun appendSamples(samples: ShortArray) {
        if (sampleBuffer.isEmpty()) {
            sampleBuffer = samples
            return
        }
        sampleBuffer = ShortArray(sampleBuffer.size + samples.size).also {
            sampleBuffer.copyInto(it)
            samples.copyInto(it, sampleBuffer.size)
        }
    }

    private fun produceOutput(flushLastSample: Boolean): ShortArray {
        if (sampleBuffer.isEmpty()) return ShortArray(0)
        val working = if (flushLastSample) {
            ShortArray(sampleBuffer.size + 1).also {
                sampleBuffer.copyInto(it)
                it[it.lastIndex] = sampleBuffer.last()
            }
        } else {
            sampleBuffer
        }
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val result = ArrayList<Short>(
            ((working.size - sourcePosition).coerceAtLeast(0.0) / ratio).toInt().coerceAtLeast(0)
        )
        while (sourcePosition + 1.0 < working.size) {
            val left = floor(sourcePosition).toInt()
            val right = left + 1
            val fraction = sourcePosition - left
            val interpolated = working[left] + (working[right] - working[left]) * fraction
            result += interpolated.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            sourcePosition += ratio
        }

        if (!flushLastSample) {
            val consumed = floor(sourcePosition).toInt().coerceIn(0, sampleBuffer.size)
            if (consumed > 0) {
                sampleBuffer = sampleBuffer.copyOfRange(consumed, sampleBuffer.size)
                sourcePosition -= consumed
            }
        }
        return ShortArray(result.size) { result[it] }
    }

    private fun resetInternal() {
        rawRemainder = ByteArray(0)
        sampleBuffer = ShortArray(0)
        sourcePosition = 0.0
    }

    private fun requirePositive(value: Int, name: String): Int {
        require(value > 0) { "$name phải lớn hơn 0" }
        return value
    }

    private fun requireSupportedEncoding(value: Int): Int {
        require(
            value == AudioFormat.ENCODING_PCM_8BIT ||
                value == AudioFormat.ENCODING_PCM_16BIT ||
                value == AudioFormat.ENCODING_PCM_FLOAT
        ) { "PCM encoding không được hỗ trợ: $value" }
        return value
    }
}
