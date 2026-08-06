package com.oai.geminilivetranslate.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

object PcmTools {
    fun toMono16k(
        input: ByteArray,
        length: Int,
        sampleRate: Int,
        channels: Int,
        encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    ): ByteArray {
        if (length <= 0 || sampleRate <= 0 || channels <= 0) return ByteArray(0)
        val mono = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> floatToMono(input, length, channels)
            AudioFormat.ENCODING_PCM_8BIT -> pcm8ToMono(input, length, channels)
            else -> pcm16ToMono(input, length, channels)
        }
        val resampled = if (sampleRate == 16_000) mono else resample(mono, sampleRate, 16_000)
        return shortsToBytes(resampled)
    }

    fun resamplePcm16Mono(data: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) return data.copyOf()
        return shortsToBytes(resample(bytesToShorts(data), sourceRate, targetRate))
    }

    fun mixPcm16(a: ByteArray, b: ByteArray, gainA: Float = 1f, gainB: Float = 1f): ByteArray {
        val size = maxOf(a.size, b.size)
        val result = ByteArray(size - size % 2)
        var i = 0
        while (i + 1 < result.size) {
            val av = if (i + 1 < a.size) ((a[i].toInt() and 0xff) or (a[i + 1].toInt() shl 8)).toShort().toInt() else 0
            val bv = if (i + 1 < b.size) ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt() else 0
            val mixed = (av * gainA + bv * gainB).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[i] = (mixed and 0xff).toByte()
            result[i + 1] = ((mixed shr 8) and 0xff).toByte()
            i += 2
        }
        return result
    }

    private fun pcm16ToMono(input: ByteArray, length: Int, channels: Int): ShortArray {
        val safeLength = length.coerceAtMost(input.size) - length.coerceAtMost(input.size) % 2
        val frames = safeLength / 2 / channels
        val out = ShortArray(frames)
        var byteIndex = 0
        for (frame in 0 until frames) {
            var sum = 0L
            repeat(channels) {
                val value = ((input[byteIndex].toInt() and 0xff) or (input[byteIndex + 1].toInt() shl 8)).toShort().toInt()
                sum += value
                byteIndex += 2
            }
            out[frame] = (sum / channels).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
        }
        return out
    }

    private fun floatToMono(input: ByteArray, length: Int, channels: Int): ShortArray {
        val safeLength = length.coerceAtMost(input.size) - length.coerceAtMost(input.size) % 4
        val buffer = ByteBuffer.wrap(input, 0, safeLength).order(ByteOrder.LITTLE_ENDIAN)
        val frames = safeLength / 4 / channels
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(channels) { sum += buffer.float.coerceIn(-1f, 1f) }
            out[frame] = ((sum / channels) * Short.MAX_VALUE).roundToInt().toShort()
        }
        return out
    }

    private fun pcm8ToMono(input: ByteArray, length: Int, channels: Int): ShortArray {
        val safeLength = length.coerceAtMost(input.size)
        val frames = safeLength / channels
        val out = ShortArray(frames)
        var index = 0
        for (frame in 0 until frames) {
            var sum = 0
            repeat(channels) {
                sum += ((input[index].toInt() and 0xff) - 128) shl 8
                index++
            }
            out[frame] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun resample(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        if (input.isEmpty() || sourceRate == targetRate) return input
        val outputSize = floor(input.size.toDouble() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        val output = ShortArray(outputSize)
        val ratio = sourceRate.toDouble() / targetRate
        for (i in output.indices) {
            val sourcePosition = i * ratio
            val left = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - left
            output[i] = (input[left] + (input[right] - input[left]) * fraction)
                .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    fun bytesToShorts(data: ByteArray): ShortArray {
        val count = data.size / 2
        val output = ShortArray(count)
        var index = 0
        for (i in 0 until count) {
            output[i] = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)).toShort()
            index += 2
        }
        return output
    }

    fun shortsToBytes(data: ShortArray): ByteArray {
        val output = ByteArray(data.size * 2)
        var index = 0
        data.forEach { sample ->
            val value = sample.toInt()
            output[index++] = (value and 0xff).toByte()
            output[index++] = ((value shr 8) and 0xff).toByte()
        }
        return output
    }
}
