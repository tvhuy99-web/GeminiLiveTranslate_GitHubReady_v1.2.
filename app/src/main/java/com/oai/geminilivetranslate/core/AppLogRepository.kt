package com.oai.geminilivetranslate.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.oai.geminilivetranslate.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppLogRepository private constructor(context: Context) {
    data class ClipboardExport(
        val text: String,
        val includedEntries: Int,
        val totalEntries: Int,
        val truncated: Boolean,
    )

    data class Entry(
        val sequence: Long,
        val epochMs: Long,
        val level: Int,
        val tag: String,
        val thread: String,
        val message: String,
        val throwable: String?,
    ) {
        fun format(): String {
            val stamp = FORMATTER.get().format(Date(epochMs))
            val levelName = LEVEL_NAMES.getOrElse(level) { "?" }
            val tail = throwable?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
            return "$stamp [$levelName][$tag][$thread] $message$tail"
        }

        companion object {
            private val LEVEL_NAMES = arrayOf("E", "W", "I", "D")
            private val FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US,
                ).apply { timeZone = TimeZone.getDefault() }
            }
        }
    }

    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val memory = ConcurrentLinkedDeque<Entry>()
    private val memoryCount = AtomicLong(0L)
    private val sequence = AtomicLong(0L)
    private val io = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diagnostic-log-writer").apply { isDaemon = true }
    }
    private val logDir = File(appContext.filesDir, "diagnostics")
    private val currentFile = File(logDir, "diagnostic-current.log")
    private var writer: BufferedWriter? = null
    private var pendingLines = 0
    private var lastFlushElapsed = 0L
    @Volatile private var cachedSecrets: List<String> = emptyList()
    @Volatile private var secretsLoadedAt = 0L

    init {
        logDir.mkdirs()
        pruneOldLogFiles()
        io.scheduleAtFixedRate({ flushWriter() }, 2, 2, TimeUnit.SECONDS)
    }

    fun log(level: Int, tag: String, message: String, throwable: Throwable? = null) {
        val settings = preferences.load()
        val safeLevel = level.coerceIn(0, 3)
        if (safeLevel > settings.logLevel) return
        if (tag == "GeminiInput" && !settings.logIncludeTranscript) return

        val safeTag = tag.trim().ifBlank { "App" }.take(40)
        val safeMessage = redact(message).take(MAX_MESSAGE_CHARS)
        val safeThrowable = throwable?.let(::formatThrowable)?.let(::redact)
        val entry = Entry(
            sequence = sequence.incrementAndGet(),
            epochMs = System.currentTimeMillis(),
            level = safeLevel,
            tag = safeTag,
            thread = Thread.currentThread().name.take(48),
            message = safeMessage,
            throwable = safeThrowable,
        )
        while (memoryCount.get() >= MAX_MEMORY_ENTRIES) {
            if (memory.pollFirst() != null) {
                memoryCount.decrementAndGet()
            } else {
                memoryCount.set(0L)
                break
            }
        }
        memory.addLast(entry)
        memoryCount.incrementAndGet()

        when (safeLevel) {
            0 -> Log.e(safeTag, safeMessage, throwable)
            1 -> Log.w(safeTag, safeMessage, throwable)
            2 -> Log.i(safeTag, safeMessage, throwable)
            else -> Log.d(safeTag, safeMessage, throwable)
        }

        if (settings.logToFile) {
            io.execute { appendToFile(entry, flushNow = safeLevel <= 1) }
        } else {
            io.execute { closeWriter() }
        }
    }

    fun entries(
        maxLevel: Int = 3,
        tag: String? = null,
        query: String? = null,
    ): List<Entry> {
        val normalizedTag = tag?.takeUnless { it == "Tất cả" || it.isBlank() }
        val normalizedQuery = query?.trim()?.takeIf(String::isNotBlank)?.lowercase(Locale.getDefault())
        return memory.filter { entry ->
            entry.level <= maxLevel.coerceIn(0, 3) &&
                (normalizedTag == null || entry.tag == normalizedTag) &&
                (normalizedQuery == null || entry.format().lowercase(Locale.getDefault()).contains(normalizedQuery))
        }
    }

    fun tags(): List<String> = memory.map(Entry::tag).distinct().sorted()

    fun text(maxLevel: Int = 3, tag: String? = null, query: String? = null): String =
        entries(maxLevel, tag, query).joinToString("\n", transform = Entry::format)
            .ifBlank { "Chưa có nhật ký phù hợp bộ lọc." }

    fun clipboardExport(maxChars: Int = MAX_CLIPBOARD_CHARS): ClipboardExport {
        val safeCap = maxChars.coerceIn(32_000, MAX_TEXT_EXPORT_CHARS)
        val totalEntries = memoryCount.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (totalEntries == 0) {
            return ClipboardExport(
                text = "Chưa có nhật ký.",
                includedEntries = 0,
                totalEntries = 0,
                truncated = false,
            )
        }

        val newestFirst = ArrayDeque<String>()
        var usedChars = 0
        var includedEntries = 0
        val iterator = memory.descendingIterator()
        while (iterator.hasNext()) {
            val line = iterator.next().format()
            val extra = line.length + if (newestFirst.isEmpty()) 0 else 1
            if (usedChars + extra > safeCap) break
            newestFirst.addFirst(line)
            usedChars += extra
            includedEntries++
        }

        val truncated = includedEntries < totalEntries
        val header = buildString {
            appendLine("Gemini Live Translate - Nhật ký sao chép an toàn")
            appendLine("Đã lấy $includedEntries/$totalEntries mục mới nhất.")
            if (truncated) {
                appendLine(
                    "Nội dung clipboard đã được giới hạn để tránh treo/văng ứng dụng. " +
                        "Dùng Chia sẻ để lấy gói chẩn đoán đầy đủ.",
                )
            }
            appendLine()
        }
        val body = newestFirst.joinToString("\n")
        val available = (safeCap - header.length).coerceAtLeast(0)
        val safeBody = if (body.length <= available) body else body.takeLast(available)
        return ClipboardExport(
            text = header + safeBody,
            includedEntries = includedEntries,
            totalEntries = totalEntries,
            truncated = truncated || body.length > available,
        )
    }

    fun clear() {
        memory.clear()
        memoryCount.set(0L)
        runCatching {
            io.submit {
                closeWriter()
                logDir.listFiles()?.forEach { it.delete() }
                logDir.mkdirs()
            }.get(5, TimeUnit.SECONDS)
        }.onFailure { Log.e("AppLogRepository", "Không xóa hết được log", it) }
    }

    fun flush() = flushBlocking()

    fun logFiles(): List<File> {
        flushBlocking()
        return currentLogFiles()
    }

    fun fileStats(): Pair<Int, Long> {
        val files = currentLogFiles()
        return files.size to files.sumOf(File::length)
    }

    fun invalidateSecrets() {
        cachedSecrets = emptyList()
        secretsLoadedAt = 0L
    }

    private fun currentLogFiles(): List<File> =
        logDir.listFiles()?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending(File::lastModified).orEmpty()

    private fun pruneOldLogFiles() {
        runCatching {
            logDir.listFiles()
                ?.filter { it.isFile && it.extension == "log" }
                ?.sortedByDescending(File::lastModified)
                ?.drop(MAX_ROTATED_FILES + 1)
                ?.forEach(File::delete)
        }.onFailure { Log.w("AppLogRepository", "Không dọn được log cũ", it) }
    }

    fun createDiagnosticBundle(): File {
        flushBlocking()
        val shareDir = File(appContext.cacheDir, "diagnostic-share").apply { mkdirs() }
        shareDir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > SHARE_TTL_MS) it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(shareDir, "GeminiLiveTranslate_diagnostics_$stamp.zip")
        ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
            addText(zip, "diagnostic-summary.txt", diagnosticSummary())
            val memoryTail = clipboardExport(MAX_BUNDLE_MEMORY_TAIL_CHARS)
            addText(zip, "memory-tail.txt", memoryTail.text)
            logFiles().forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        shareDir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_SHARED_REPORTS)
            ?.forEach(File::delete)
        return output
    }

    private fun diagnosticSummary(): String {
        val settings = preferences.load()
        val aiApi = AiApiSettingsStore(appContext).load()
        val runtime = Runtime.getRuntime()
        val freeStorage = logDir.usableSpace / (1024L * 1024L)
        val logSizes = logFiles().joinToString { "${it.name}=${it.length() / 1024}KB" }.ifBlank { "không có" }
        return buildString {
            appendLine("Gemini Live Translate - Báo cáo chẩn đoán")
            appendLine("Tạo lúc: ${Date()}")
            appendLine("Phiên bản: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Thiết bị: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("Bộ nhớ JVM: used=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}MB, max=${runtime.maxMemory() / 1_048_576}MB")
            appendLine("Dung lượng trống vùng log: ${freeStorage}MB")
            appendLine("Tệp log: $logSizes")
            appendLine("Forensic retention: memory=$MAX_MEMORY_ENTRIES entries, files=${MAX_ROTATED_FILES + 1} x ${MAX_FILE_BYTES / (1024L * 1024L)}MB")
            appendLine()
            appendLine("--- Cài đặt đã khử dữ liệu nhạy cảm ---")
            appendLine("model=${settings.model}")
            appendLine("videoProvider=${aiApi.provider}, videoGeminiModel=${aiApi.geminiModel}, videoProxyModel=${aiApi.proxyModel}, streaming=${aiApi.streamingEnabled}, timeoutMs=${aiApi.requestTimeoutMs}, temperature=${aiApi.temperature}")
            appendLine("videoTimelinePromptChars=${aiApi.timelinePrompt.length}, videoSummaryPromptChars=${aiApi.summaryPrompt.length}")
            appendLine("targetLanguage=${settings.targetLanguage}")
            appendLine("echoTargetLanguage=${settings.echoTargetLanguage}")
            appendLine("profile=${settings.performanceProfile}, uiMode=${settings.uiMode}")
            appendLine("autoReconnect=${settings.autoReconnect}, maxRetries=${settings.reconnectMaxRetries}")
            appendLine("qualityMode=${settings.qualityMode}, inputBufferMs=${settings.inputBufferMs}, jitter=${settings.outputJitterTarget}")
            appendLine("pacing=${settings.pacingEnabled}, pacingLeadMs=${settings.pacingTargetLatencyMs}, sendQueue=${settings.pacingMaxBuffer}")
            appendLine("translatedBuffer=${settings.translatedBufferBytes}, translatedQueue=${settings.translatedQueueMax}")
            appendLine("saveAudio=${settings.saveAudioEnabled}, saveMode=${settings.saveAudioMode}, export=${settings.exportFormat}")
            appendLine("logLevel=${settings.logLevel}, logToFile=${settings.logToFile}, transcriptInLog=${settings.logIncludeTranscript}")
            appendLine()
            appendLine("--- Trạng thái chạy gần nhất ---")
            DiagnosticContext.snapshot().forEach { (key, value) -> appendLine("$key=${redact(value)}") }
            appendLine()
            appendLine("Lưu ý: API Key/token/credential phiên đã được che. Nội dung forensic khác có thể chứa dữ liệu cá nhân trong phiên kiểm tra.")
        }
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(redact(text).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun appendToFile(entry: Entry, flushNow: Boolean) {
        runCatching {
            logDir.mkdirs()
            rotateIfNeeded(entry.format().length + 2L)
            val active = writer ?: BufferedWriter(FileWriter(currentFile, true), 64 * 1024).also { writer = it }
            active.append(entry.format()).append('\n')
            pendingLines++
            val elapsed = SystemClock.elapsedRealtime()
            if (flushNow || pendingLines >= 32 || elapsed - lastFlushElapsed >= 2_000L) flushWriter()
        }.onFailure { Log.e("AppLogRepository", "Không ghi được log", it) }
    }

    private fun rotateIfNeeded(incomingChars: Long) {
        if (currentFile.length() + incomingChars < MAX_FILE_BYTES) return
        closeWriter()
        for (index in MAX_ROTATED_FILES downTo 1) {
            val source = if (index == 1) currentFile else File(logDir, "diagnostic-${index - 1}.log")
            val target = File(logDir, "diagnostic-$index.log")
            if (target.exists()) target.delete()
            if (source.exists()) source.renameTo(target)
        }
        pruneOldLogFiles()
    }

    private fun flushBlocking() {
        if (Thread.currentThread().name == "diagnostic-log-writer") {
            flushWriter()
            return
        }
        runCatching { io.submit { flushWriter() }.get(5, TimeUnit.SECONDS) }
            .onFailure { Log.e("AppLogRepository", "Không flush được log", it) }
    }

    private fun flushWriter() {
        runCatching { writer?.flush() }
        pendingLines = 0
        lastFlushElapsed = SystemClock.elapsedRealtime()
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        pendingLines = 0
    }

    private fun formatThrowable(throwable: Throwable): String {
        val chain = generateSequence(throwable) { it.cause }.take(5).toList()
        val header = chain.joinToString(" <- ") { error ->
            "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
        val frames = throwable.stackTrace.take(MAX_STACK_FRAMES).joinToString("\n") { "    at $it" }
        return "$header\n$frames".take(MAX_THROWABLE_CHARS)
    }

    private fun redact(input: String): String {
        refreshSecretsIfNeeded()
        var value = input
        cachedSecrets.filter { it.length >= 8 }.forEach { value = value.replace(it, "[REDACTED_API_KEY]") }
        REDACTION_PATTERNS.forEach { (regex, replacement) -> value = regex.replace(value, replacement) }
        return value
    }

    private fun refreshSecretsIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (secretsLoadedAt != 0L && now - secretsLoadedAt < 60_000L) return
        secretsLoadedAt = now
        cachedSecrets = runCatching {
            val state = ApiKeyStore(appContext).load()
            (state.keys + listOfNotNull(state.proxyKey)).distinct()
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_MEMORY_ENTRIES = 12_000
        private const val MAX_MESSAGE_CHARS = 16_000
        private const val MAX_THROWABLE_CHARS = 24_000
        private const val MAX_STACK_FRAMES = 80
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
        private const val MAX_ROTATED_FILES = 3
        private const val MAX_CLIPBOARD_CHARS = 220_000
        private const val MAX_BUNDLE_MEMORY_TAIL_CHARS = 350_000
        private const val MAX_TEXT_EXPORT_CHARS = 400_000
        private const val SHARE_TTL_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_SHARED_REPORTS = 5

        private val REDACTION_PATTERNS = listOf(
            Regex("AIza[0-9A-Za-z_-]{12,}") to "AIza[REDACTED]",
            Regex("(?i)([?&](?:key|api_key|token|access_token|id_token|at|sid|hsid|ssid|apisid|sapisid|csrf|xsrf)=)[^&\\s]+") to "${'$'}1[REDACTED]",
            Regex("(?i)((?:authorization|x-goog-api-key|cookie|set-cookie)\\s*[:=]\\s*)(?:Bearer\\s+)?[^\\r\\n]+") to "${'$'}1[REDACTED]",
            Regex("(?i)(\\\"(?:key|apiKey|token|accessToken|idToken|authorization|at|sid|hsid|ssid|apisid|sapisid|csrf|xsrf)\\\"\\s*:\\s*\\\")[^\\\"]+") to "${'$'}1[REDACTED]",
            Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*") to "${'$'}1[REDACTED]",
        )

        @Volatile private var instance: AppLogRepository? = null

        fun get(context: Context): AppLogRepository = instance ?: synchronized(this) {
            instance ?: AppLogRepository(context).also { instance = it }
        }
    }
}

object DiagnosticContext {
    private val values = ConcurrentHashMap<String, String>()

    fun update(key: String, value: Any?) {
        if (value == null) values.remove(key) else values[key] = value.toString().take(4_000)
    }

    fun updateAll(items: Map<String, Any?>) = items.forEach { (key, value) -> update(key, value) }

    fun clearSession() {
        values.keys.filter { it.startsWith("session.") }.forEach(values::remove)
    }

    fun clearAll() = values.clear()

    fun snapshot(): Map<String, String> = values.toSortedMap()
}
