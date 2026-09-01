package com.oai.geminilivetranslate.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.oai.geminilivetranslate.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Logger riêng cho phòng thử nghiệm AI Studio Browser Bridge.
 *
 * Khác AppLogRepository, logger này LUÔN ghi file để không phụ thuộc cài đặt nhật ký
 * của ứng dụng chính. Mục tiêu là giữ lại tối đa dấu vết kỹ thuật khi WebView/AI Studio
 * thất bại giữa chừng hoặc renderer bị Android thu hồi.
 */
class AiStudioBridgeLabLog(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val root = File(appContext.filesDir, "aistudio-bridge-lab").apply { mkdirs() }
    private val sessionId = "${STAMP.format(Date())}-${UUID.randomUUID().toString().take(8)}"
    private val sessionDir = File(root, sessionId).apply { mkdirs() }
    private val eventFile = File(sessionDir, "events.log")
    private val appLog = AppLogRepository.get(appContext)
    private val startedElapsed = SystemClock.elapsedRealtime()

    init {
        writeRaw(
            buildString {
                appendLine("# AI Studio Browser Bridge Lab")
                appendLine("sessionId=$sessionId")
                appendLine("createdAt=${Date()}")
                appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} ${Build.DEVICE}")
                appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("locale=${Locale.getDefault().toLanguageTag()}")
                appendLine("note=Lab log intentionally captures verbose technical state, but never records cookie values or passwords.")
                appendLine()
            },
        )
        event("I", "SESSION_START", "AI Studio bridge laboratory started")
    }

    fun event(level: String, name: String, detail: String = "") {
        val safeLevel = level.uppercase(Locale.US).take(1).ifBlank { "I" }
        val safeName = compact(name, 80)
        val safeDetail = compactMultiline(detail, 24_000)
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - startedElapsed
        val line = "${EVENT_STAMP.format(Date(now))} +${elapsed}ms [$safeLevel][$safeName][${Thread.currentThread().name.take(48)}] $safeDetail\n"
        writeRaw(line)
        when (safeLevel) {
            "E" -> Log.e(TAG, "$safeName | $safeDetail")
            "W" -> Log.w(TAG, "$safeName | $safeDetail")
            "D" -> Log.d(TAG, "$safeName | $safeDetail")
            else -> Log.i(TAG, "$safeName | $safeDetail")
        }
        val appLevel = when (safeLevel) {
            "E" -> 0
            "W" -> 1
            "D" -> 3
            else -> 2
        }
        appLog.log(appLevel, TAG, "$safeName | $safeDetail")
    }

    fun exception(name: String, throwable: Throwable, detail: String = "") {
        val stack = buildString {
            if (detail.isNotBlank()) appendLine(detail)
            appendLine("${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            throwable.stackTrace.take(80).forEach { appendLine("    at $it") }
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine("Caused by ${cause.javaClass.name}: ${cause.message.orEmpty()}")
                cause.stackTrace.take(30).forEach { appendLine("    at $it") }
                cause = cause.cause
                depth++
            }
        }
        event("E", name, stack)
    }

    fun snapshot(name: String, content: String): File {
        val fileName = "${System.currentTimeMillis()}-${sanitizeFileName(name)}.txt"
        val file = File(sessionDir, fileName)
        synchronized(lock) {
            runCatching { file.writeText(content.take(MAX_SNAPSHOT_CHARS), Charsets.UTF_8) }
                .onFailure { Log.e(TAG, "Cannot write snapshot $fileName", it) }
        }
        event("I", "SNAPSHOT_SAVED", "name=$fileName chars=${content.length}")
        return file
    }

    fun currentSessionDirectory(): File = sessionDir

    fun currentEventFile(): File = eventFile

    fun createBundle(extraSummary: String = ""): File {
        event("I", "BUNDLE_CREATE", "Creating diagnostic bundle")
        val output = File(root, "AIStudioBridgeLab-$sessionId.zip")
        synchronized(lock) {
            ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
                addText(
                    zip,
                    "summary.txt",
                    buildString {
                        appendLine("AI Studio Browser Bridge Lab diagnostic bundle")
                        appendLine("sessionId=$sessionId")
                        appendLine("createdAt=${Date()}")
                        appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
                        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                        if (extraSummary.isNotBlank()) {
                            appendLine()
                            appendLine(extraSummary.take(50_000))
                        }
                    },
                )
                sessionDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                    zip.putNextEntry(ZipEntry("session/${file.name}"))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        event("I", "BUNDLE_READY", "path=${output.absolutePath} bytes=${output.length()}")
        return output
    }

    fun readTail(maxChars: Int = 18_000): String = synchronized(lock) {
        if (!eventFile.isFile) return@synchronized "Chưa có log."
        runCatching {
            val text = eventFile.readText(Charsets.UTF_8)
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }.getOrElse { "Không đọc được log: ${it.message}" }
    }

    private fun writeRaw(text: String) {
        synchronized(lock) {
            runCatching {
                sessionDir.mkdirs()
                eventFile.appendText(text, Charsets.UTF_8)
            }.onFailure { Log.e(TAG, "Cannot persist lab log", it) }
        }
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun compact(value: String, limit: Int): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(limit)

    private fun compactMultiline(value: String, limit: Int): String =
        value.replace("\u0000", "").trim().take(limit)

    private fun sanitizeFileName(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "snapshot" }
            .take(80)

    companion object {
        const val TAG = "AIStudioLab"
        private const val MAX_SNAPSHOT_CHARS = 1_000_000
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        private val EVENT_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }
}
