package com.oai.geminilivetranslate.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object AudioFileSplitter {
    data class Part(
        val file: File,
        val mimeType: String,
        val startOffsetMs: Long,
        val durationMs: Long,
    )

    data class Result(
        val parts: List<Part>,
        val sourceMimeType: String,
        val durationMs: Long,
        val sampleCount: Long,
        val outputBytes: Long,
        val strategy: String,
    )

    fun splitInHalf(
        context: Context,
        uri: Uri,
        outputDir: File,
        maxDurationMs: Long,
        onProgress: (Int) -> Unit,
    ): Result {
        outputDir.mkdirs()
        val asset = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Không mở được tệp âm thanh")
        val extractor = MediaExtractor()
        try {
            if (asset.length >= 0L) {
                extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            } else {
                extractor.setDataSource(asset.fileDescriptor)
            }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) error("Tệp không có track âm thanh")

            val audioFormat = requireNotNull(format)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            val durationMs = durationUs / 1_000L
            if (durationMs <= maxDurationMs) error("Tệp chưa cần chia")
            if (durationMs > maxDurationMs * 2L) {
                error("Thử nghiệm tự chia hiện hỗ trợ tệp tối đa \${maxDurationMs * 2L / 60_000L} phút")
            }

            extractor.selectTrack(trackIndex)
            val splitUs = durationUs / 2L
            return when (mime) {
                "audio/mp4a-latm" -> splitAac(
                    extractor = extractor,
                    format = audioFormat,
                    durationUs = durationUs,
                    splitUs = splitUs,
                    outputDir = outputDir,
                    mime = mime,
                    onProgress = onProgress,
                )
                "audio/mpeg" -> splitMp3(
                    extractor = extractor,
                    format = audioFormat,
                    durationUs = durationUs,
                    splitUs = splitUs,
                    outputDir = outputDir,
                    mime = mime,
                    onProgress = onProgress,
                )
                else -> error("Thử nghiệm chia nhanh chưa hỗ trợ codec $mime")
            }
        } finally {
            extractor.release()
            asset.close()
        }
    }

    private data class AacConfig(
        val audioObjectType: Int,
        val frequencyIndex: Int,
        val channelCount: Int,
    )

    private fun splitAac(
        extractor: MediaExtractor,
        format: MediaFormat,
        durationUs: Long,
        splitUs: Long,
        outputDir: File,
        mime: String,
        onProgress: (Int) -> Unit,
    ): Result {
        val config = readAacConfig(format) ?: error("Không đọc được cấu hình AAC")
        val first = File(outputDir, "part-1.aac")
        val second = File(outputDir, "part-2.aac")
        first.delete()
        second.delete()

        val sampleBuffer = ByteBuffer.allocateDirect(AAC_MAX_SAMPLE_BYTES)
        val batch1 = ByteBuffer.allocateDirect(BATCH_BYTES + AAC_MAX_SAMPLE_BYTES + ADTS_HEADER_BYTES)
        val batch2 = ByteBuffer.allocateDirect(BATCH_BYTES + AAC_MAX_SAMPLE_BYTES + ADTS_HEADER_BYTES)
        var sampleCount = 0L
        var part2FirstUs = -1L
        var lastProgress = -1

        FileOutputStream(first).channel.use { ch1 ->
            FileOutputStream(second).channel.use { ch2 ->
                fun flush(target: ByteBuffer, firstPart: Boolean) {
                    if (target.position() <= 0) return
                    target.flip()
                    val channel = if (firstPart) ch1 else ch2
                    while (target.hasRemaining()) channel.write(target)
                    target.clear()
                }

                while (true) {
                    sampleBuffer.clear()
                    val size = extractor.readSampleData(sampleBuffer, 0)
                    if (size < 0) break
                    if (size + ADTS_HEADER_BYTES > AAC_MAX_ADTS_FRAME_BYTES) {
                        error("AAC frame quá lớn để chia nhanh")
                    }
                    val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    val firstPart = sampleTimeUs < splitUs
                    if (!firstPart && part2FirstUs < 0L) part2FirstUs = sampleTimeUs
                    val target = if (firstPart) batch1 else batch2
                    if (target.remaining() < size + ADTS_HEADER_BYTES) flush(target, firstPart)

                    putAdtsHeader(
                        target = target,
                        payloadSize = size,
                        audioObjectType = config.audioObjectType,
                        frequencyIndex = config.frequencyIndex,
                        channelCount = config.channelCount,
                    )
                    sampleBuffer.position(0)
                    sampleBuffer.limit(size)
                    target.put(sampleBuffer)
                    sampleCount++
                    reportProgress(sampleCount, sampleTimeUs, durationUs, lastProgress, onProgress)?.let {
                        lastProgress = it
                    }
                    extractor.advance()
                }
                flush(batch1, true)
                flush(batch2, false)
            }
        }

        validateParts(first, second)
        val secondStartUs = part2FirstUs.takeIf { it >= 0L } ?: splitUs
        onProgress(100)
        return Result(
            parts = listOf(
                Part(first, "audio/aac", 0L, secondStartUs / 1_000L),
                Part(second, "audio/aac", secondStartUs / 1_000L, (durationUs - secondStartUs) / 1_000L),
            ),
            sourceMimeType = mime,
            durationMs = durationUs / 1_000L,
            sampleCount = sampleCount,
            outputBytes = first.length() + second.length(),
            strategy = "aac-adts-half",
        )
    }

    private fun splitMp3(
        extractor: MediaExtractor,
        format: MediaFormat,
        durationUs: Long,
        splitUs: Long,
        outputDir: File,
        mime: String,
        onProgress: (Int) -> Unit,
    ): Result {
        val first = File(outputDir, "part-1.mp3")
        val second = File(outputDir, "part-2.mp3")
        first.delete()
        second.delete()

        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
        } else {
            256 * 1024
        }
        val sampleBuffer = ByteBuffer.allocateDirect(maxInput)
        val batch1 = ByteBuffer.allocateDirect(BATCH_BYTES + maxInput)
        val batch2 = ByteBuffer.allocateDirect(BATCH_BYTES + maxInput)
        var sampleCount = 0L
        var part2FirstUs = -1L
        var lastProgress = -1

        FileOutputStream(first).channel.use { ch1 ->
            FileOutputStream(second).channel.use { ch2 ->
                fun flush(target: ByteBuffer, firstPart: Boolean) {
                    if (target.position() <= 0) return
                    target.flip()
                    val channel = if (firstPart) ch1 else ch2
                    while (target.hasRemaining()) channel.write(target)
                    target.clear()
                }

                while (true) {
                    sampleBuffer.clear()
                    val size = extractor.readSampleData(sampleBuffer, 0)
                    if (size < 0) break
                    val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    val firstPart = sampleTimeUs < splitUs
                    if (!firstPart && part2FirstUs < 0L) part2FirstUs = sampleTimeUs
                    val target = if (firstPart) batch1 else batch2
                    if (target.remaining() < size) flush(target, firstPart)

                    sampleBuffer.position(0)
                    sampleBuffer.limit(size)
                    target.put(sampleBuffer)
                    sampleCount++
                    reportProgress(sampleCount, sampleTimeUs, durationUs, lastProgress, onProgress)?.let {
                        lastProgress = it
                    }
                    extractor.advance()
                }
                flush(batch1, true)
                flush(batch2, false)
            }
        }

        validateParts(first, second)
        val secondStartUs = part2FirstUs.takeIf { it >= 0L } ?: splitUs
        onProgress(100)
        return Result(
            parts = listOf(
                Part(first, "audio/mpeg", 0L, secondStartUs / 1_000L),
                Part(second, "audio/mpeg", secondStartUs / 1_000L, (durationUs - secondStartUs) / 1_000L),
            ),
            sourceMimeType = mime,
            durationMs = durationUs / 1_000L,
            sampleCount = sampleCount,
            outputBytes = first.length() + second.length(),
            strategy = "mp3-frame-half",
        )
    }

    private fun reportProgress(
        sampleCount: Long,
        sampleTimeUs: Long,
        durationUs: Long,
        lastProgress: Int,
        onProgress: (Int) -> Unit,
    ): Int? {
        if (sampleCount != 1L && sampleCount % PROGRESS_SAMPLE_INTERVAL != 0L) return null
        if (durationUs <= 0L) return null
        val progress = ((sampleTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
        if (progress == lastProgress) return null
        onProgress(progress)
        return progress
    }

    private fun validateParts(first: File, second: File) {
        if (!first.isFile || first.length() <= 0L || !second.isFile || second.length() <= 0L) {
            error("Không chia được tệp âm thanh thành hai phần")
        }
    }

    private fun readAacConfig(format: MediaFormat): AacConfig? {
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            return null
        }
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            return null
        }
        val frequencyIndex = AAC_SAMPLE_RATES.indexOf(sampleRate)
        if (frequencyIndex < 0 || channelCount !in 1..7) return null

        var audioObjectType = if (format.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
            format.getInteger(MediaFormat.KEY_AAC_PROFILE)
        } else {
            0
        }
        if (audioObjectType !in 1..4) {
            val csd = format.getByteBuffer("csd-0")?.duplicate()
            if (csd != null && csd.remaining() >= 2) {
                val first = csd.get().toInt() and 0xFF
                audioObjectType = (first ushr 3) and 0x1F
            }
        }
        if (audioObjectType !in 1..4) return null
        return AacConfig(audioObjectType, frequencyIndex, channelCount)
    }

    private fun putAdtsHeader(
        target: ByteBuffer,
        payloadSize: Int,
        audioObjectType: Int,
        frequencyIndex: Int,
        channelCount: Int,
    ) {
        val frameLength = payloadSize + ADTS_HEADER_BYTES
        val profile = (audioObjectType - 1).coerceIn(0, 3)
        target.put(0xFF.toByte())
        target.put(0xF1.toByte())
        target.put(((profile shl 6) or (frequencyIndex shl 2) or (channelCount ushr 2)).toByte())
        target.put((((channelCount and 3) shl 6) or (frameLength ushr 11)).toByte())
        target.put(((frameLength ushr 3) and 0xFF).toByte())
        target.put((((frameLength and 7) shl 5) or 0x1F).toByte())
        target.put(0xFC.toByte())
    }

    private const val ADTS_HEADER_BYTES = 7
    private const val AAC_MAX_ADTS_FRAME_BYTES = 8_191
    private const val AAC_MAX_SAMPLE_BYTES = AAC_MAX_ADTS_FRAME_BYTES - ADTS_HEADER_BYTES
    private const val BATCH_BYTES = 2 * 1_024 * 1_024
    private const val PROGRESS_SAMPLE_INTERVAL = 256L
    private val AAC_SAMPLE_RATES = intArrayOf(
        96_000,
        88_200,
        64_000,
        48_000,
        44_100,
        32_000,
        24_000,
        22_050,
        16_000,
        12_000,
        11_025,
        8_000,
        7_350,
    )
}
