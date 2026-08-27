package com.oai.geminilivetranslate.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

object VideoAudioExtractor {
    data class Result(
        val file: File,
        val mimeType: String,
        val trackMimeType: String,
        val durationMs: Long,
        val outputBytes: Long,
        val sampleCount: Long,
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

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Không mở được video")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
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
            if (started) {
                activeMuxer.stop()
                started = false
            }
            activeMuxer.release()
            muxer = null

            if (!output.isFile || output.length() <= 0L) error("Không tách được âm thanh từ video")
            return Result(
                file = output,
                mimeType = "audio/mp4",
                trackMimeType = trackMime,
                durationMs = durationMs,
                outputBytes = output.length().takeIf { it > 0L } ?: bytesWritten,
                sampleCount = sampleCount,
            )
        } finally {
            extractor.release()
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            descriptor.close()
        }
    }
}