package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StreamingPcmConverterTest {
    @Test
    fun arbitraryChunkBoundariesMatchSingleChunkConversion() {
        val input = stereoSineWave(sampleRate = 48_000, seconds = 1)

        val single = StreamingPcmConverter(sourceRate = 48_000, channels = 2).let { converter ->
            converter.process(input) + converter.flush()
        }

        val streamedConverter = StreamingPcmConverter(sourceRate = 48_000, channels = 2)
        val streamed = ArrayList<Byte>()
        val chunkSizes = intArrayOf(997, 3_201, 7_777, 1_024, 5_555)
        var offset = 0
        var chunkIndex = 0
        while (offset < input.size) {
            val size = minOf(chunkSizes[chunkIndex++ % chunkSizes.size], input.size - offset)
            streamedConverter.process(input.copyOfRange(offset, offset + size)).forEach(streamed::add)
            offset += size
        }
        streamedConverter.flush().forEach(streamed::add)
        val streamedBytes = ByteArray(streamed.size) { streamed[it] }

        assertEquals(16_000 * 2, single.size)
        assertArrayEquals(single, streamedBytes)
    }

    @Test
    fun resetRemovesSamplesFromPreviousTimeline() {
        val converter = StreamingPcmConverter(sourceRate = 48_000, channels = 1)
        val first = monoRamp(480)
        converter.process(first)
        converter.reset()

        val second = monoRamp(480, start = 10_000)
        val output = converter.process(second) + converter.flush()
        val samples = PcmTools.bytesToShorts(output)

        assertEquals(160, samples.size)
        assertEquals(10_000, samples.first().toInt())
    }

    private fun stereoSineWave(sampleRate: Int, seconds: Int): ByteArray {
        val frames = sampleRate * seconds
        val output = ByteArray(frames * 4)
        var index = 0
        repeat(frames) { frame ->
            val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * 20_000).toInt()
            repeat(2) {
                output[index++] = (sample and 0xff).toByte()
                output[index++] = ((sample shr 8) and 0xff).toByte()
            }
        }
        return output
    }

    private fun monoRamp(samples: Int, start: Int = 0): ByteArray {
        val output = ByteArray(samples * 2)
        var index = 0
        repeat(samples) { position ->
            val sample = (start + position).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[index++] = (sample and 0xff).toByte()
            output[index++] = ((sample shr 8) and 0xff).toByte()
        }
        return output
    }
}
