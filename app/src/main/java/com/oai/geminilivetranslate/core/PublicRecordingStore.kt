package com.oai.geminilivetranslate.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/**
 * Records to a short-lived cache file, then publishes the completed WAV to the user's public
 * Music/GeminiLiveTranslate folder. API keys, preferences and diagnostic logs remain private.
 */
class PublicRecordingStore(
    private val context: Context,
    private val logger: SessionLogger,
) {
    data class Pending(
        val tempFile: File,
        val displayName: String,
    )

    fun create(displayName: String): Pending {
        val dir = File(context.cacheDir, "recording-pending").apply {
            mkdirs()
            listFiles()
                ?.filter { System.currentTimeMillis() - it.lastModified() > STALE_MS }
                ?.forEach(File::delete)
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val temp = File(dir, "${UUID.randomUUID()}-$safeName")
        return Pending(temp, safeName)
    }

    fun publish(pending: Pending): Uri {
        check(pending.tempFile.isFile) { "Không tìm thấy tệp ghi tạm" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(pending)
        } else {
            publishLegacy(pending)
        }
    }

    fun discard(pending: Pending?) {
        pending?.tempFile?.delete()
    }

    /**
     * Deletes recordings created by this app from the public Music/GeminiLiveTranslate folder.
     * Returns the number of removed entries, or -1 when the operation fails.
     */
    fun deleteAll(): Int = runCatching {
        val deleted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteWithMediaStore()
        } else {
            deleteLegacy()
        }
        File(context.cacheDir, "recording-pending").deleteRecursively()
        logger.log(1, "Recorder", "Đã xóa bản ghi công khai count=$deleted")
        deleted
    }.onFailure {
        logger.log(0, "Recorder", "Không xóa được toàn bộ bản ghi công khai", it)
    }.getOrDefault(-1)

    private fun publishWithMediaStore(pending: Pending): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, pending.displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Không tạo được mục MediaStore")
        try {
            resolver.openOutputStream(uri, "w")?.buffered(COPY_BUFFER)?.use { output ->
                pending.tempFile.inputStream().buffered(COPY_BUFFER).use { input ->
                    input.copyTo(output, COPY_BUFFER)
                }
            } ?: error("Không mở được tệp công khai")
            ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }.also {
                resolver.update(uri, it, null, null)
            }
            pending.tempFile.delete()
            logger.log(2, "Recorder", "Đã lưu WAV công khai uri=$uri")
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun deleteWithMediaStore(): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(PUBLIC_RELATIVE_PATH)
        return resolver.delete(collection, selection, selectionArgs)
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(pending: Pending): Uri {
        val dir = legacyDirectory()
        check(dir.exists() || dir.mkdirs()) {
            "Không tạo được thư mục ${dir.absolutePath}"
        }
        val target = uniqueFile(dir, pending.displayName)
        pending.tempFile.inputStream().buffered(COPY_BUFFER).use { input ->
            target.outputStream().buffered(COPY_BUFFER).use { output ->
                input.copyTo(output, COPY_BUFFER)
            }
        }
        pending.tempFile.delete()
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("audio/wav"),
            null,
        )
        logger.log(2, "Recorder", "Đã lưu WAV công khai path=${target.absolutePath}")
        return Uri.fromFile(target)
    }

    @Suppress("DEPRECATION")
    private fun deleteLegacy(): Int {
        val dir = legacyDirectory()
        if (!dir.exists()) {
            return 0
        }
        val count = dir.walkTopDown().count { it.isFile }
        check(dir.deleteRecursively()) {
            "Không xóa được thư mục ${dir.absolutePath}"
        }
        return count
    }

    @Suppress("DEPRECATION")
    private fun legacyDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "GeminiLiveTranslate",
    )

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) {
            return direct
        }
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let {
            if (it.isBlank()) "" else ".$it"
        }
        var index = 2
        while (true) {
            val candidate = File(dir, "${base}_$index$ext")
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    companion object {
        private const val COPY_BUFFER = 256 * 1024
        private const val STALE_MS = 24L * 60L * 60L * 1_000L
        private const val PUBLIC_RELATIVE_PATH = "Music/GeminiLiveTranslate/"
    }
}
