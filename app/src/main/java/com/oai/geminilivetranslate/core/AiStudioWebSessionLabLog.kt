package com.oai.geminilivetranslate.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiStudioWebSessionLabLog(context: Context) {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val sessionId = "$stamp-${UUID.randomUUID().toString().take(8)}"
    private val dir = File(context.filesDir, "aistudio-web-session-lab/$sessionId").apply { mkdirs() }
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
        val out = File(context.cacheDir, "AIStudioWebSessionLab-$sessionId.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter(File::isFile).forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    fun eventFile(): File = events
    fun sessionDirectory(): File = dir
}
