package com.oai.geminilivetranslate.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import com.oai.geminilivetranslate.core.SessionLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Captures the Android screen as JPEG frames at no more than one frame per second. */
class ScreenFrameCapture(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val logger: SessionLogger,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val workerThread = HandlerThread("LiveScreenDescriptionCapture").apply { start() }
    private val worker = Handler(workerThread.looper)

    private val metrics: DisplayMetrics = appContext.resources.displayMetrics
    private val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
    private val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
    private val captureSize = fitWithin(sourceWidth, sourceHeight, MAX_LONG_EDGE)
    private val width = captureSize.first
    private val height = captureSize.second
    private val densityDpi = metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT)

    private val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private var virtualDisplay: VirtualDisplay? = null
    private var lastFrameAt = 0L
    private var imageCallbacks = 0L
    private var acquiredImages = 0L
    private var acquireNulls = 0L
    private var candidateFrames = 0L
    private var framesEncoded = 0L
    private var framesDelivered = 0L
    private var framesSkipped = 0L
    private var encodeNulls = 0L
    private var callbackErrors = 0L

    fun start() {
        check(!closed.get()) { "ScreenFrameCapture đã đóng" }
        if (virtualDisplay != null) {
            logger.log(1, TAG, "FRAME_PIPELINE_START_SKIP reason=already-started")
            return
        }
        logger.log(
            2,
            TAG,
            "FRAME_PIPELINE_START_BEGIN source=${sourceWidth}x$sourceHeight capture=${width}x$height " +
                "densityDpi=$densityDpi surfaceValid=${runCatching { imageReader.surface.isValid }.getOrDefault(false)} " +
                "thread=${Thread.currentThread().name}",
        )
        imageReader.setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, worker)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "GeminiLiveScreenDescription",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            worker,
        )
        logger.log(
            2,
            TAG,
            "FRAME_PIPELINE_STARTED source=${sourceWidth}x$sourceHeight capture=${width}x$height " +
                "densityDpi=$densityDpi maxFps=1 audioInput=false virtualDisplay=${virtualDisplay != null} " +
                "surfaceValid=${runCatching { imageReader.surface.isValid }.getOrDefault(false)} workerAlive=${workerThread.isAlive}",
        )
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        imageReader.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader.close() }
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        logger.log(
            2,
            TAG,
            "FRAME_PIPELINE_STOP callbacks=$imageCallbacks acquired=$acquiredImages acquireNulls=$acquireNulls " +
                "candidates=$candidateFrames encoded=$framesEncoded delivered=$framesDelivered " +
                "skipped=$framesSkipped encodeNulls=$encodeNulls callbackErrors=$callbackErrors",
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        imageCallbacks++
        val image = runCatching { reader.acquireLatestImage() }
            .onFailure { error ->
                logger.log(
                    0,
                    TAG,
                    "FRAME_TRACE stage=acquire-exception callback=$imageCallbacks thread=${Thread.currentThread().name}",
                    error,
                )
            }
            .getOrNull()
        if (image == null) {
            acquireNulls++
            if (acquireNulls == 1L || acquireNulls % 30L == 0L) {
                logger.log(
                    1,
                    TAG,
                    "FRAME_TRACE stage=acquire-null callback=$imageCallbacks acquireNulls=$acquireNulls " +
                        "closed=${closed.get()} surfaceValid=${runCatching { imageReader.surface.isValid }.getOrDefault(false)}",
                )
            }
            return
        }

        acquiredImages++
        var traceId = 0L
        try {
            if (closed.get()) {
                logger.log(2, TAG, "FRAME_TRACE stage=drop reason=capture-closed callback=$imageCallbacks")
                return
            }

            val now = SystemClock.elapsedRealtime()
            val ageMs = if (lastFrameAt == 0L) Long.MAX_VALUE else now - lastFrameAt
            if (ageMs < FRAME_INTERVAL_MS) {
                framesSkipped++
                if (framesSkipped == 1L || framesSkipped % 120L == 0L) {
                    logger.log(
                        3,
                        TAG,
                        "FRAME_TRACE stage=drop reason=fps-throttle skipped=$framesSkipped callbacks=$imageCallbacks " +
                            "acquired=$acquiredImages ageMs=$ageMs intervalMs=$FRAME_INTERVAL_MS",
                    )
                }
                return
            }

            lastFrameAt = now
            val candidateId = ++candidateFrames
            if (shouldTrace(candidateId)) {
                logger.log(
                    3,
                    TAG,
                    "FRAME_TRACE candidate=$candidateId stage=image-acquired callback=$imageCallbacks acquired=$acquiredImages " +
                        "image=${image.width}x${image.height} format=${image.format} planes=${image.planes.size} " +
                        "timestampNs=${image.timestamp} surfaceValid=${runCatching { reader.surface.isValid }.getOrDefault(false)}",
                )
            }

            val encodeStarted = SystemClock.elapsedRealtimeNanos()
            val jpeg = encodeJpeg(image, candidateId)
            val encodeMs = (SystemClock.elapsedRealtimeNanos() - encodeStarted) / 1_000_000.0
            if (jpeg == null || jpeg.isEmpty()) {
                encodeNulls++
                logger.log(
                    1,
                    TAG,
                    "FRAME_TRACE candidate=$candidateId stage=encode-empty encodeNulls=$encodeNulls encodeMs=${"%.3f".format(encodeMs)}",
                )
                return
            }

            framesEncoded++
            traceId = framesEncoded
            if (shouldTrace(traceId)) {
                logger.log(
                    3,
                    TAG,
                    "FRAME_TRACE id=$traceId candidate=$candidateId stage=jpeg-encoded encoded=$framesEncoded jpegBytes=${jpeg.size} " +
                        "encodeMs=${"%.3f".format(encodeMs)}",
                )
            }

            val callbackStarted = SystemClock.elapsedRealtimeNanos()
            if (shouldTrace(traceId)) {
                logger.log(
                    3,
                    TAG,
                    "FRAME_TRACE id=$traceId stage=callback-begin jpegBytes=${jpeg.size} thread=${Thread.currentThread().name}",
                )
            }
            onFrame(jpeg)
            framesDelivered++
            if (shouldTrace(traceId)) {
                val callbackMs = (SystemClock.elapsedRealtimeNanos() - callbackStarted) / 1_000_000.0
                logger.log(
                    3,
                    TAG,
                    "FRAME_TRACE id=$traceId stage=callback-end delivered=$framesDelivered callbackMs=${"%.3f".format(callbackMs)}",
                )
            }
        } catch (error: Throwable) {
            callbackErrors++
            if (!closed.get()) {
                logger.log(
                    0,
                    TAG,
                    "FRAME_TRACE id=$traceId stage=exception callbackErrors=$callbackErrors " +
                        "callbacks=$imageCallbacks acquired=$acquiredImages encoded=$framesEncoded delivered=$framesDelivered",
                    error,
                )
            }
        } finally {
            image.close()
        }
    }

    private fun encodeJpeg(image: Image, candidateId: Long): ByteArray? {
        val plane = image.planes.firstOrNull()
        if (plane == null) {
            logger.log(1, TAG, "FRAME_TRACE candidate=$candidateId stage=encode-reject reason=no-plane planes=${image.planes.size}")
            return null
        }

        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) {
            logger.log(
                1,
                TAG,
                "FRAME_TRACE candidate=$candidateId stage=encode-reject reason=invalid-stride pixelStride=$pixelStride rowStride=$rowStride",
            )
            return null
        }

        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
        val paddedWidth = width + rowPadding / pixelStride
        if (shouldTrace(candidateId)) {
            logger.log(
                3,
                TAG,
                "FRAME_TRACE candidate=$candidateId stage=buffer-layout remaining=${buffer.remaining()} capacity=${buffer.capacity()} " +
                    "pixelStride=$pixelStride rowStride=$rowStride rowPadding=$rowPadding paddedWidth=$paddedWidth target=${width}x$height",
            )
        }

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val cropped: Bitmap
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            cropped = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
        } catch (error: Throwable) {
            padded.recycle()
            logger.log(0, TAG, "FRAME_TRACE candidate=$candidateId stage=bitmap-copy-error paddedWidth=$paddedWidth height=$height", error)
            throw error
        }

        return try {
            encodeBounded(cropped, candidateId)
        } finally {
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
        }
    }

    private fun encodeBounded(bitmap: Bitmap, candidateId: Long): ByteArray {
        for (quality in JPEG_QUALITIES) {
            val output = ByteArrayOutputStream()
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (shouldTrace(candidateId)) {
                logger.log(
                    3,
                    TAG,
                    "FRAME_TRACE candidate=$candidateId stage=jpeg-attempt quality=$quality compressed=$compressed bytes=${bytes.size} " +
                        "limit=$MAX_FRAME_BYTES",
                )
            }
            if (!compressed) {
                logger.log(1, TAG, "FRAME_TRACE candidate=$candidateId stage=jpeg-compress-false quality=$quality")
            }
            if (bytes.size <= MAX_FRAME_BYTES || quality == JPEG_QUALITIES.last()) return bytes
        }
        return ByteArray(0)
    }

    private fun shouldTrace(traceId: Long): Boolean =
        traceId in 1L..FIRST_FRAMES_FULL_TRACE || traceId % PERIODIC_TRACE_INTERVAL == 0L

    companion object {
        private const val TAG = "ScreenFrameCapture"
        private const val MAX_LONG_EDGE = 1280
        private const val FRAME_INTERVAL_MS = 1_000L
        private const val MAX_FRAME_BYTES = 900 * 1024
        private const val FIRST_FRAMES_FULL_TRACE = 10L
        private const val PERIODIC_TRACE_INTERVAL = 30L
        private val JPEG_QUALITIES = intArrayOf(78, 68, 56)

        internal fun fitWithin(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val longEdge = maxOf(safeWidth, safeHeight)
            if (longEdge <= maxLongEdge) return safeWidth to safeHeight
            val scale = maxLongEdge.toDouble() / longEdge.toDouble()
            val outWidth = (safeWidth * scale).roundToInt().coerceAtLeast(1)
            val outHeight = (safeHeight * scale).roundToInt().coerceAtLeast(1)
            return outWidth to outHeight
        }
    }
}
