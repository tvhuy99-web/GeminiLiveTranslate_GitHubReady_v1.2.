package com.oai.geminilivetranslate.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object VideoAudioExtractor {
    data class Result(
        val file: File,
        val mimeType: String,
        val trackMimeType: String,
        val durationMs: Long,
        val outputBytes: Long,
        val sampleCount: Long,
        val strategy: String,
    )

    fun extract(
        context: Context,
        uri: Uri,
        output: File,
        maxDurationMs: Long? = null,
        onProgress: (Int) -> Unit,
    ): Result {
        output.parentFile?.mkdirs()
        output.delete()

        val asset = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Không mở được video")
        val extractor = MediaExtractor()
        try {
            if (asset.length >= 0L) {
                extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            } else {
                extractor.setDataSource(asset.fileDescriptor)
            }

            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = format
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) error("Video không có track âm thanh")

            val format = requireNotNull(audioFormat)
            val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            val durationMs = durationUs / 1_000L
            if (durationMs <= 0L) error("Không đọc được thời lượng audio trong video")
            if (maxDurationMs != null && durationMs > maxDurationMs) {
                error("Tệp dài quá 30 phút. Hãy cắt tệp ngắn hơn rồi thử lại")
            }

            extractor.selectTrack(audioTrack)
            val aacConfig = if (trackMime == "audio/mp4a-latm") readAacConfig(format) else null
            return if (aacConfig != null) {
                val aacFile = File(output.parentFile, output.nameWithoutExtension + ".aac")
                extractAacAdts(
                    extractor = extractor,
                    config = aacConfig,
                    durationUs = durationUs,
                    output = aacFile,
                    trackMime = trackMime,
                    onProgress = onProgress,
                )
            } else {
                extractWithMediaMuxer(
                    extractor = extractor,
                    format = format,
                    durationUs = durationUs,
                    output = output,
                    trackMime = trackMime,
                    onProgress = onProgress,
                )
            }
        } finally {
            extractor.release()
            asset.close()
        }
    }

    private data class AacConfig(
        val audioObjectType: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val frequencyIndex: Int,
    )

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

        return AacConfig(
            audioObjectType = audioObjectType,
            sampleRate = sampleRate,
            channelCount = channelCount,
            frequencyIndex = frequencyIndex,
        )
    }

    private fun extractAacAdts(
        extractor: MediaExtractor,
        config: AacConfig,
        durationUs: Long,
        output: File,
        trackMime: String,
        onProgress: (Int) -> Unit,
    ): Result {
        output.delete()
        val sampleBuffer = ByteBuffer.allocateDirect(AAC_MAX_SAMPLE_BYTES)
        val batchBuffer = ByteBuffer.allocateDirect(AAC_BATCH_BYTES + AAC_MAX_SAMPLE_BYTES + ADTS_HEADER_BYTES)
        var sampleCount = 0L
        var bytesWritten = 0L
        var lastProgress = -1

        FileOutputStream(output).channel.use { channel ->
            fun flushBatch() {
                if (batchBuffer.position() <= 0) return
                batchBuffer.flip()
                while (batchBuffer.hasRemaining()) channel.write(batchBuffer)
                batchBuffer.clear()
            }

            while (true) {
                sampleBuffer.clear()
                val size = extractor.readSampleData(sampleBuffer, 0)
                if (size < 0) break
                if (size + ADTS_HEADER_BYTES > AAC_MAX_ADTS_FRAME_BYTES) {
                    error("AAC frame quá lớn để đóng gói nhanh")
                }
                if (batchBuffer.remaining() < size + ADTS_HEADER_BYTES) flushBatch()

                putAdtsHeader(
                    target = batchBuffer,
                    payloadSize = size,
                    audioObjectType = config.audioObjectType,
                    frequencyIndex = config.frequencyIndex,
                    channelCount = config.channelCount,
                )
                sampleBuffer.position(0)
                sampleBuffer.limit(size)
                batchBuffer.put(sampleBuffer)

                sampleCount++
                bytesWritten += size + ADTS_HEADER_BYTES
                if (sampleCount == 1L || sampleCount % PROGRESS_SAMPLE_INTERVAL == 0L) {
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs >= 0L && durationUs > 0L) {
                        val progress = ((sampleTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
                extractor.advance()
            }
            flushBatch()
        }

        onProgress(100)
        if (!output.isFile || output.length() <= 0L) error("Không tách được âm thanh AAC từ video")
        return Result(
            file = output,
            mimeType = "audio/aac",
            trackMimeType = trackMime,
            durationMs = durationUs / 1_000L,
            outputBytes = output.length().takeIf { it > 0L } ?: bytesWritten,
            sampleCount = sampleCount,
            strategy = "aac-adts-fast",
        )
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

    private fun extractWithMediaMuxer(
        extractor: MediaExtractor,
        format: MediaFormat,
        durationUs: Long,
        output: File,
        trackMime: String,
        onProgress: (Int) -> Unit,
    ): Result {
        output.delete()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val muxerTrack = activeMuxer.addTrack(format)
            activeMuxer.start()
            started = true

            val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val info = MediaCodec.BufferInfo()
            var sampleCount = 0L
            var bytesWritten = 0L
            var lastProgress = -1

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                activeMuxer.writeSampleData(muxerTrack, buffer, info)
                sampleCount++
                bytesWritten += size
                if (durationUs > 0L) {
                    val progress = ((info.presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
                extractor.advance()
            }
            onProgress(100)
            activeMuxer.stop()
            started = false
            activeMuxer.release()
            muxer = null

            if (!output.isFile || output.length() <= 0L) error("Không tách được âm thanh từ video")
            return Result(
                file = output,
                mimeType = "audio/mp4",
                trackMimeType = trackMime,
                durationMs = durationUs / 1_000L,
                outputBytes = output.length().takeIf { it > 0L } ?: bytesWritten,
                sampleCount = sampleCount,
                strategy = "media-muxer-fallback",
            )
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    companion object {
        private const val ADTS_HEADER_BYTES = 7
        private const val AAC_MAX_ADTS_FRAME_BYTES = 8_191
        private const val AAC_MAX_SAMPLE_BYTES = AAC_MAX_ADTS_FRAME_BYTES - ADTS_HEADER_BYTES
        private const val AAC_BATCH_BYTES = 2 * 1_024 * 1_024
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
}
