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
        val durationMs: Long,
    )

    fun extract(
        context: Context,
        uri: Uri,
        output: File,
        onProgress: (Int) -> Unit,
    ): Result {
        output.parentFile?.mkdirs()
        output.delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
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
        if (audioTrack < 0 || audioFormat == null) {
            extractor.release()
            error("Video không có track âm thanh")
        }

        val mimeType = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
        } else {
            0L
        }

        extractor.selectTrack(audioTrack)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val muxerTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val info = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
                if (durationUs > 0L) {
                    onProgress(((info.presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99))
                }
                extractor.advance()
            }
            onProgress(100)
        } finally {
            extractor.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }

        if (!output.isFile || output.length() <= 0L) {
            error("Không tách được âm thanh từ video")
        }
        return Result(
            file = output,
            mimeType = "audio/mp4",
            durationMs = durationUs / 1_000L,
        )
    }
}
