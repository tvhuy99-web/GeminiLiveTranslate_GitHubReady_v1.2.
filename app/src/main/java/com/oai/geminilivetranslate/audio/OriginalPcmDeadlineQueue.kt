package com.oai.geminilivetranslate.audio

import java.util.ArrayDeque
import kotlin.math.ceil

class OriginalPcmDeadlineQueue(
    private val sampleRate: Int,
) {
    data class ReadyChunk(
        val data: ByteArray,
        val dueAtMs: Long,
        val generation: Long,
    )

    data class Stats(
        val queuedChunks: Int,
        val queuedBytes: Long,
        val generation: Long,
        val paused: Boolean,
    )

    private data class PendingChunk(
        val data: ByteArray,
        var dueAtMs: Double,
        val generation: Long,
    )

    private val queue = ArrayDeque<PendingChunk>()
    private var queuedBytes = 0L
    private var generation = 0L
    private var nextDueAtMs = Double.NaN
    private var pausedAtMs: Long? = null

    init {
        require(sampleRate > 0) { "sampleRate phải > 0" }
    }

    @Synchronized
    fun enqueue(data: ByteArray, playbackSpeed: Float, nowMs: Long): Long {
        if (data.isEmpty()) return nowMs
        val speed = playbackSpeed.coerceAtLeast(0.1f)
        val dueAt = if (nextDueAtMs.isNaN()) {
            nowMs.toDouble()
        } else {
            maxOf(nowMs.toDouble(), nextDueAtMs)
        }
        queue.addLast(
            PendingChunk(
                data = data.copyOf(),
                dueAtMs = dueAt,
                generation = generation,
            )
        )
        queuedBytes += data.size.toLong()
        nextDueAtMs = dueAt + pcmDurationMs(data.size, speed)
        return dueAt.toLong()
    }

    @Synchronized
    fun pollReady(nowMs: Long): ReadyChunk? {
        if (pausedAtMs != null) return null
        discardStaleHead()
        val first = queue.peekFirst() ?: return null
        if (first.dueAtMs > nowMs.toDouble()) return null
        queue.removeFirst()
        queuedBytes -= first.data.size.toLong()
        if (queue.isEmpty() && nextDueAtMs < nowMs.toDouble()) nextDueAtMs = Double.NaN
        return ReadyChunk(
            data = first.data,
            dueAtMs = first.dueAtMs.toLong(),
            generation = first.generation,
        )
    }

    @Synchronized
    fun millisUntilNext(nowMs: Long): Long? {
        if (pausedAtMs != null) return null
        discardStaleHead()
        val first = queue.peekFirst() ?: return null
        return ceil(first.dueAtMs - nowMs.toDouble()).toLong().coerceAtLeast(0L)
    }

    @Synchronized
    fun pause(nowMs: Long) {
        if (pausedAtMs == null) pausedAtMs = nowMs
    }

    
    @Synchronized
    fun resume(nowMs: Long): Long {
        val pausedAt = pausedAtMs ?: return 0L
        val pausedFor = (nowMs - pausedAt).coerceAtLeast(0L)
        if (pausedFor > 0L) {
            queue.forEach { it.dueAtMs += pausedFor.toDouble() }
            if (!nextDueAtMs.isNaN()) nextDueAtMs += pausedFor.toDouble()
        }
        pausedAtMs = null
        return pausedFor
    }

    
    @Synchronized
    fun retime(playbackSpeed: Float, nowMs: Long) {
        if (queue.isEmpty()) return
        val speed = playbackSpeed.coerceAtLeast(0.1f)
        val referenceNow = pausedAtMs ?: nowMs
        var cursor = maxOf(referenceNow.toDouble(), queue.peekFirst().dueAtMs)
        queue.forEach { pending ->
            pending.dueAtMs = cursor
            cursor += pcmDurationMs(pending.data.size, speed)
        }
        nextDueAtMs = cursor
    }

    
    @Synchronized
    fun clear(): Int {
        val removed = queue.size
        queue.clear()
        queuedBytes = 0L
        generation++
        nextDueAtMs = Double.NaN
        pausedAtMs = null
        return removed
    }

    @Synchronized
    fun isGenerationCurrent(value: Long): Boolean = value == generation

    @Synchronized
    fun stats(): Stats = Stats(
        queuedChunks = queue.size,
        queuedBytes = queuedBytes,
        generation = generation,
        paused = pausedAtMs != null,
    )

    private fun pcmDurationMs(byteCount: Int, playbackSpeed: Float): Double =
        byteCount.toDouble() * 1_000.0 / (sampleRate.toDouble() * BYTES_PER_SAMPLE * playbackSpeed)

    private fun discardStaleHead() {
        while (true) {
            val first = queue.peekFirst() ?: return
            if (first.generation == generation) return
            queue.removeFirst()
            queuedBytes -= first.data.size.toLong()
        }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2.0
    }
}
