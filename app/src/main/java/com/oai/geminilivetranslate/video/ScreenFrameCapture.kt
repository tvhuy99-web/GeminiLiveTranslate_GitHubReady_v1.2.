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
    private var framesEncoded = 0L
    private var framesSkipped = 0L

    fun start() {
        check(!closed.get()) { "ScreenFrameCapture đã đóng" }
        if (virtualDisplay != null) return
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
            "Bắt đầu screen capture source=${sourceWidth}x$sourceHeight capture=${width}x$height " +
                "densityDpi=$densityDpi maxFps=1 audioInput=false",
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
        logger.log(2, TAG, "Dừng screen capture encoded=$framesEncoded skipped=$framesSkipped")
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (closed.get()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                framesSkipped++
                return
            }
            lastFrameAt = now
            val jpeg = encodeJpeg(image) ?: return
            framesEncoded++
            if (framesEncoded == 1L || framesEncoded % 30L == 0L) {
                logger.log(3, TAG, "Đã mã hóa frame=$framesEncoded jpegBytes=${jpeg.size}")
            }
            onFrame(jpeg)
        } catch (error: Throwable) {
            if (!closed.get()) logger.log(1, TAG, "Không mã hóa được frame màn hình", error)
        } finally {
            image.close()
        }
    }

    private fun encodeJpeg(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
        val paddedWidth = width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val cropped: Bitmap
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            cropped = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
        } catch (error: Throwable) {
            padded.recycle()
            throw error
        }

        return try {
            encodeBounded(cropped)
        } finally {
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
        }
    }

    private fun encodeBounded(bitmap: Bitmap): ByteArray {
        for (quality in JPEG_QUALITIES) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_FRAME_BYTES || quality == JPEG_QUALITIES.last()) return bytes
        }
        return ByteArray(0)
    }

    companion object {
        private const val TAG = "ScreenFrameCapture"
        private const val MAX_LONG_EDGE = 1280
        private const val FRAME_INTERVAL_MS = 1_000L
        private const val MAX_FRAME_BYTES = 900 * 1024
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
