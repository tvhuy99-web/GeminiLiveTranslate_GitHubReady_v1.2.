package com.oai.geminilivetranslate.core

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiStudioWebSessionLabLog(private val context: Context) {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val sessionId = "$stamp-${UUID.randomUUID().toString().take(8)}"
    private val dir = File(context.filesDir, ROOT_DIR_NAME + "/" + sessionId).apply { mkdirs() }
    private val events = File(dir, "events.log")

    @Synchronized
    fun event(level: String, name: String, detail: String) {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        events.appendText("$now [$level][$name] ${detail.replace('\u0000', ' ').take(48_000)}\n")
    }

    fun snapshot(name: String, text: String) {
        File(dir, "${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt").writeText(text.take(900_000))
    }

    fun createBundle(summary: String): File {
        snapshot("android-summary", summary)
        return zipSessionDirectory(context, dir)
    }

    fun eventFile(): File = events
    fun sessionDirectory(): File = dir

    companion object {
        private const val ROOT_DIR_NAME = "aistudio-web-session-lab"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        private data class SnapshotEntry(
            val file: File,
            val relativePath: String,
            val bytesAtSnapshot: Long,
        )

        fun latestSessionDirectory(context: Context): File? {
            val root = File(context.filesDir, ROOT_DIR_NAME)
            return root.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
        }

        fun createLatestBundle(context: Context): File {
            val latest = latestSessionDirectory(context)
                ?: error("Chưa có phiên nhật ký AI Studio Web Session nào")
            return zipSessionDirectory(context, latest)
        }

        /**
         * Creates a point-in-time ZIP even when the active Lab is still appending to events.log.
         * Each source file is copied only up to the byte length observed before ZIP creation.
         * This prevents an actively-growing log from keeping the export operation alive forever.
         */
        private fun zipSessionDirectory(context: Context, sessionDir: File): File {
            val entries = sessionDir.walkTopDown()
                .filter(File::isFile)
                .map { file ->
                    SnapshotEntry(
                        file = file,
                        relativePath = file.relativeTo(sessionDir).invariantSeparatorsPath,
                        bytesAtSnapshot = file.length().coerceAtLeast(0L),
                    )
                }
                .toList()

            val out = File(context.cacheDir, "AIStudioWebSessionLab-${sessionDir.name}.zip")
            val temp = File(context.cacheDir, out.name + ".tmp")
            temp.delete()

            try {
                ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    entries.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.relativePath))
                        FileInputStream(entry.file).use { input ->
                            var remaining = entry.bytesAtSnapshot
                            while (remaining > 0L) {
                                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, requested)
                                if (read < 0) break
                                zip.write(buffer, 0, read)
                                remaining -= read.toLong()
                            }
                        }
                        zip.closeEntry()
                    }
                }
                if (out.exists() && !out.delete()) {
                    error("Không thể thay ZIP cũ: ${out.name}")
                }
                if (!temp.renameTo(out)) {
                    temp.copyTo(out, overwrite = true)
                    temp.delete()
                }
                return out
            } catch (t: Throwable) {
                temp.delete()
                throw t
            }
        }
    }
}
