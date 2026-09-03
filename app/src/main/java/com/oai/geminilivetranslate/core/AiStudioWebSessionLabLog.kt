package com.oai.geminilivetranslate.core

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
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
        private const val MAX_REPORT_CHARS = 600_000
        private const val MAX_EVENTS_CHARS = 360_000
        private const val MAX_OTHER_FILE_CHARS = 100_000

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
         * Builds a bounded plain-text diagnostic report for direct on-screen display and clipboard
         * copy. It never waits for an actively-growing events.log to stop growing: every source
         * file is read only up to the byte length observed when that file is opened.
         */
        fun createLatestTextReport(context: Context): String {
            val latest = latestSessionDirectory(context)
                ?: error("Chưa có phiên nhật ký AI Studio Web Session nào")

            val files = latest.listFiles()
                ?.filter { it.isFile }
                .orEmpty()
                .sortedWith(compareBy<File>({ reportPriority(it.name) }, { it.name }))

            return buildString {
                appendLine("AI STUDIO WEB SESSION DIAGNOSTICS")
                appendLine("session=${latest.name}")
                appendLine("files=${files.size}")
                appendLine("reportLimitChars=$MAX_REPORT_CHARS")
                appendLine()

                files.forEach { file ->
                    if (length >= MAX_REPORT_CHARS) return@forEach
                    val remaining = MAX_REPORT_CHARS - length
                    val perFileLimit = when (file.name) {
                        "events.log" -> minOf(MAX_EVENTS_CHARS, remaining)
                        else -> minOf(MAX_OTHER_FILE_CHARS, remaining)
                    }
                    if (perFileLimit <= 0) return@forEach

                    appendLine("===== ${file.name} =====")
                    val snapshotBytes = file.length().coerceAtLeast(0L)
                    appendLine("snapshotBytes=$snapshotBytes")
                    append(readTailAtSnapshot(file, snapshotBytes, perFileLimit))
                    if (!endsWith('\n')) appendLine()
                    appendLine()
                }

                if (length >= MAX_REPORT_CHARS) {
                    appendLine("[REPORT TRUNCATED AT $MAX_REPORT_CHARS CHARACTERS]")
                }
            }.take(MAX_REPORT_CHARS)
        }

        private fun reportPriority(name: String): Int = when (name) {
            "r18-final-summary.txt" -> 0
            "r18-causal-timeline.txt" -> 1
            "r18-state-capture-finished.txt" -> 2
            "r18-r132-deep-recent.txt" -> 3
            "r16-final-summary.txt" -> 4
            "r15-final-summary.txt" -> 5
            "r14-final-summary.txt" -> 6
            "last-generate-call-stack.txt" -> 7
            "android-summary.txt" -> 8
            "events.log" -> 9
            else -> 20
        }

        private fun readTailAtSnapshot(file: File, snapshotBytes: Long, maxChars: Int): String {
            if (snapshotBytes <= 0L || maxChars <= 0) return ""
            val maxBytes = minOf(snapshotBytes, maxChars.toLong() * 3L)
            val start = (snapshotBytes - maxBytes).coerceAtLeast(0L)
            val bytes = ByteArray(maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

            val read = RandomAccessFile(file, "r").use { input ->
                input.seek(start)
                var offset = 0
                var remaining = bytes.size
                while (remaining > 0) {
                    val count = input.read(bytes, offset, remaining)
                    if (count < 0) break
                    offset += count
                    remaining -= count
                }
                offset
            }

            val prefix = if (start > 0L) "[... ${start} earlier bytes omitted ...]\n" else ""
            return (prefix + bytes.copyOf(read).toString(Charsets.UTF_8)).takeLast(maxChars)
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
