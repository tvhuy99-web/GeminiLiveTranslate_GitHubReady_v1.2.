package com.oai.geminilivetranslate.core

import android.content.Context
import java.io.File

/** Compatibility facade backed by one process-wide repository. */
class SessionLogger(context: Context, @Suppress("UNUSED_PARAMETER") preferences: AppPreferences) {
    private val repository = AppLogRepository.get(context)

    fun log(level: Int, tag: String, message: String, throwable: Throwable? = null) =
        repository.log(level, tag, message, throwable)

    fun text(maxLevel: Int = 3, tag: String? = null, query: String? = null): String =
        repository.text(maxLevel, tag, query)

    fun entries(maxLevel: Int = 3, tag: String? = null, query: String? = null): List<AppLogRepository.Entry> =
        repository.entries(maxLevel, tag, query)

    fun tags(): List<String> = repository.tags()
    fun clear() = repository.clear()
    fun flush() = repository.flush()
    fun logFiles(): List<File> = repository.logFiles()
    fun fileStats(): Pair<Int, Long> = repository.fileStats()
    fun createDiagnosticBundle(): File = repository.createDiagnosticBundle()
}
