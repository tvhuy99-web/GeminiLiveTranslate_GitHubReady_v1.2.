package com.oai.geminilivetranslate.core

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

class SubtitleStore {
    data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

    private val cues = CopyOnWriteArrayList<Cue>()
    @Volatile private var startedAt = SystemClock.elapsedRealtime()
    @Volatile private var lastEnd = 0L

    fun reset() {
        cues.clear()
        startedAt = SystemClock.elapsedRealtime()
        lastEnd = 0L
    }

    fun append(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        val now = max(0L, SystemClock.elapsedRealtime() - startedAt)
        val start = max(lastEnd, now - 1_200L)
        val duration = (cleaned.length * 55L).coerceIn(1_000L, 8_000L)
        val end = max(now + 350L, start + duration)
        val previous = cues.lastOrNull()
        if (previous != null && previous.text == cleaned && now - previous.endMs < 1_500) return
        cues += Cue(cues.size + 1, start, end, cleaned)
        lastEnd = end
    }

    fun appendTimed(text: String, startMs: Long, endMs: Long) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        val start = startMs.coerceAtLeast(0L)
        val end = max(start + 1L, endMs)
        cues += Cue(cues.size + 1, start, end, cleaned)
        lastEnd = end
    }

    fun replaceTimed(items: List<Cue>) {
        cues.clear()
        items.sortedBy { it.startMs }.forEach {
            appendTimed(it.text, it.startMs, it.endMs)
        }
    }

    fun snapshot(): List<Cue> = cues.toList()

    fun plainText(): String = cues.joinToString("\n") { it.text }

    fun srtText(): String = cues.joinToString("\n\n") {
        "${it.index}\n${format(it.startMs)} --> ${format(it.endMs)}\n${it.text}"
    }

    private fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = (total / 60_000) % 60
        val seconds = (total / 1_000) % 60
        val millis = total % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
