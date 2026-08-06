package com.oai.geminilivetranslate.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(data: ByteArray, length: Int = data.size) {
        if (closed || length <= 0) return
        val safeLength = length.coerceAtMost(data.size)
        raf.write(data, 0, safeLength)
        dataBytes += safeLength
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate, channels, bitsPerSample))
        raf.close()
    }

    companion object {
        fun header(dataSize: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36L + dataSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }.array()
        }
    }
}
