package com.oai.geminilivetranslate.core

import android.content.Context
import java.io.File
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

        private fun zipSessionDirectory(context: Context, sessionDir: File): File {
            val out = File(context.cacheDir, "AIStudioWebSessionLab-${sessionDir.name}.zip")
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                sessionDir.walkTopDown().filter(File::isFile).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(sessionDir).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return out
        }
    }
}
