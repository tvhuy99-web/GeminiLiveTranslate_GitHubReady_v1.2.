package com.oai.geminilivetranslate.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class HistorySession(
    val id: String,
    val sourceMode: String,
    val processingMode: String,
    val videoDescriptionMode: String,
    val title: String,
    val mediaUri: String?,
    val mediaName: String?,
    val primaryTranscript: String,
    val primarySrt: String,
    val vietnameseTranscript: String,
    val vietnameseSrt: String,
    val showingVietnamese: Boolean,
    val speakerDiarization: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val hasValue: Boolean
        get() = primaryTranscript.isNotBlank() ||
            primarySrt.isNotBlank() ||
            vietnameseTranscript.isNotBlank() ||
            vietnameseSrt.isNotBlank()

    val hasVietnamese: Boolean
        get() = vietnameseTranscript.isNotBlank() || vietnameseSrt.isNotBlank()
}

class SessionHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY_NAME).apply {
        mkdirs()
    }
    private val meta = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun newSession(
        sourceMode: String,
        processingMode: String,
        mediaUri: String?,
        mediaName: String?,
        speakerDiarization: Boolean,
        videoDescriptionMode: String = AppPreferences.VIDEO_DESCRIPTION_TIMELINE,
        nowMs: Long = System.currentTimeMillis(),
    ): HistorySession = HistorySession(
        id = UUID.randomUUID().toString(),
        sourceMode = sourceMode,
        processingMode = processingMode,
        videoDescriptionMode = videoDescriptionMode,
        title = deriveTitle(sourceMode, mediaName, "", nowMs),
        mediaUri = mediaUri,
        mediaName = mediaName,
        primaryTranscript = "",
        primarySrt = "",
        vietnameseTranscript = "",
        vietnameseSrt = "",
        showingVietnamese = false,
        speakerDiarization = speakerDiarization,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    )

    @Synchronized
    fun save(session: HistorySession): HistorySession {
        if (!session.hasValue || isDeleted(session.id)) return session
        val normalized = session.copy(
            title = deriveTitle(
                sourceMode = session.sourceMode,
                mediaName = session.mediaName,
                transcript = session.primaryTranscript.ifBlank { session.vietnameseTranscript },
                createdAtMs = session.createdAtMs,
            ),
            updatedAtMs = System.currentTimeMillis(),
        )
        writeAtomic(fileFor(normalized.id), toJson(normalized).toString())
        trimToLimit()
        return normalized
    }

    @Synchronized
    fun load(id: String): HistorySession? {
        val safeId = sanitizeId(id) ?: return null
        val file = fileFor(safeId)
        if (!file.isFile) return null
        return runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
    }

    @Synchronized
    fun listRecent(limit: Int = MAX_SESSIONS): List<HistorySession> =
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
            }
            .filter(HistorySession::hasValue)
            .sortedByDescending { it.updatedAtMs }
            .take(limit.coerceIn(1, MAX_SESSIONS))
            .toList()

    @Synchronized
    fun delete(id: String): Boolean {
        val safeId = sanitizeId(id) ?: return false
        val deletedIds = meta.getStringSet(KEY_DELETED_IDS, emptySet()).orEmpty().toMutableSet()
        deletedIds += safeId
        meta.edit().putStringSet(KEY_DELETED_IDS, deletedIds.toList().takeLast(MAX_TOMBSTONES).toSet()).apply()
        val file = fileFor(safeId)
        return !file.exists() || file.delete()
    }

    @Synchronized
    fun count(): Int = listRecent(MAX_SESSIONS).size

    private fun trimToLimit() {
        val all = directory.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                if (!file.isFile || !file.extension.equals("json", ignoreCase = true)) return@mapNotNull null
                val session = runCatching { fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
                    ?: return@mapNotNull null
                session to file
            }
            .sortedByDescending { it.first.updatedAtMs }
        all.drop(MAX_SESSIONS).forEach { (_, file) -> file.delete() }
    }

    private fun fileFor(id: String): File = File(directory, "$id.json")

    private fun writeAtomic(target: File, content: String) {
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Không thể thay tệp lịch sử cũ")
        }
        if (!temp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun toJson(session: HistorySession): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("id", session.id)
        .put("sourceMode", session.sourceMode)
        .put("processingMode", session.processingMode)
        .put("videoDescriptionMode", session.videoDescriptionMode)
        .put("title", session.title)
        .put("mediaUri", session.mediaUri ?: JSONObject.NULL)
        .put("mediaName", session.mediaName ?: JSONObject.NULL)
        .put("primaryTranscript", session.primaryTranscript)
        .put("primarySrt", session.primarySrt)
        .put("vietnameseTranscript", session.vietnameseTranscript)
        .put("vietnameseSrt", session.vietnameseSrt)
        .put("showingVietnamese", session.showingVietnamese)
        .put("speakerDiarization", session.speakerDiarization)
        .put("createdAtMs", session.createdAtMs)
        .put("updatedAtMs", session.updatedAtMs)

    private fun fromJson(json: JSONObject): HistorySession {
        val created = json.optLong("createdAtMs", System.currentTimeMillis())
        val sourceMode = json.optString("sourceMode", SourceMode.FILE.name)
        val mediaName = json.optNullableString("mediaName")
        val primaryTranscript = json.optString("primaryTranscript")
        val vietnameseTranscript = json.optString("vietnameseTranscript")
        return HistorySession(
            id = json.getString("id"),
            sourceMode = sourceMode,
            processingMode = json.optString(
                "processingMode",
                AppPreferences.PROCESSING_MODE_TRANSCRIBE,
            ),
            videoDescriptionMode = json.optString(
                "videoDescriptionMode",
                AppPreferences.VIDEO_DESCRIPTION_TIMELINE,
            ).takeIf {
                it == AppPreferences.VIDEO_DESCRIPTION_TIMELINE ||
                    it == AppPreferences.VIDEO_DESCRIPTION_SUMMARY
            } ?: AppPreferences.VIDEO_DESCRIPTION_TIMELINE,
            title = json.optString("title").ifBlank {
                deriveTitle(
                    sourceMode,
                    mediaName,
                    primaryTranscript.ifBlank { vietnameseTranscript },
                    created,
                )
            },
            mediaUri = json.optNullableString("mediaUri"),
            mediaName = mediaName,
            primaryTranscript = primaryTranscript,
            primarySrt = json.optString("primarySrt"),
            vietnameseTranscript = vietnameseTranscript,
            vietnameseSrt = json.optString("vietnameseSrt"),
            showingVietnamese = json.optBoolean("showingVietnamese", false),
            speakerDiarization = json.optBoolean("speakerDiarization", false),
            createdAtMs = created,
            updatedAtMs = json.optLong("updatedAtMs", created),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun deriveTitle(
        sourceMode: String,
        mediaName: String?,
        transcript: String,
        createdAtMs: Long,
    ): String {
        if (sourceMode == SourceMode.FILE.name) {
            val name = mediaName.orEmpty().trim()
            if (name.isNotBlank()) {
                return name.substringBeforeLast('.', name).trim().ifBlank { name }
            }
        }

        val words = transcript
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .filter(String::isNotBlank)
            .take(10)
        val fromText = buildString {
            words.forEach { word ->
                val extra = if (isEmpty()) word.length else word.length + 1
                if (length + extra > MAX_TITLE_CHARS) return@forEach
                if (isNotEmpty()) append(' ')
                append(word)
            }
        }.trim()
        if (fromText.isNotBlank()) return fromText

        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(createdAtMs))
        return when (sourceMode) {
            SourceMode.MICROPHONE.name -> "Ghi âm $time"
            SourceMode.INTERNAL.name -> "Âm thanh nội bộ $time"
            else -> "Phiên $time"
        }
    }

    private fun isDeleted(id: String): Boolean =
        id in meta.getStringSet(KEY_DELETED_IDS, emptySet()).orEmpty()

    private fun sanitizeId(id: String): String? =
        id.trim().takeIf { it.matches(Regex("[A-Za-z0-9-]{8,80}")) }

    companion object {
        const val MAX_SESSIONS = 10
        private const val DIRECTORY_NAME = "session_history"
        private const val META_PREFS = "session_history_meta"
        private const val KEY_DELETED_IDS = "deletedIds"
        private const val FORMAT_VERSION = 1
        private const val MAX_TITLE_CHARS = 80
        private const val MAX_TOMBSTONES = 100
    }
}
