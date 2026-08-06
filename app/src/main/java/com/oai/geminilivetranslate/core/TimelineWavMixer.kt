package com.oai.geminilivetranslate.core

import com.oai.geminilivetranslate.audio.PcmTools
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

class TimelineWavMixer(
    file: File,
    private val targetRate: Int = 24_000,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var maxDataBytes = 0L
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun mix(data: ByteArray, sourceRate: Int, elapsedMs: Long, gain: Float = 0.78f) {
        if (closed || data.isEmpty()) return
        val normalized = if (sourceRate == targetRate) data else PcmTools.resamplePcm16Mono(data, sourceRate, targetRate)
        val evenLength = normalized.size - normalized.size % 2
        if (evenLength <= 0) return
        val offset = 44L + elapsedMs.coerceAtLeast(0) * targetRate * 2L / 1_000L
        val existing = ByteArray(evenLength)
        if (offset < raf.length()) {
            raf.seek(offset)
            val available = (raf.length() - offset).coerceAtMost(evenLength.toLong()).toInt()
            if (available > 0) raf.readFully(existing, 0, available)
        }
        val mixed = ByteArray(evenLength)
        var index = 0
        while (index + 1 < evenLength) {
            val oldSample = ((existing[index].toInt() and 0xff) or (existing[index + 1].toInt() shl 8)).toShort().toInt()
            val newSample = ((normalized[index].toInt() and 0xff) or (normalized[index + 1].toInt() shl 8)).toShort().toInt()
            val value = (oldSample + newSample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            mixed[index] = (value and 0xff).toByte()
            mixed[index + 1] = ((value shr 8) and 0xff).toByte()
            index += 2
        }
        raf.seek(offset)
        raf.write(mixed)
        maxDataBytes = maxOf(maxDataBytes, offset - 44L + mixed.size)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(WavWriter.header(maxDataBytes, targetRate, 1, 16))
        raf.close()
    }
}
