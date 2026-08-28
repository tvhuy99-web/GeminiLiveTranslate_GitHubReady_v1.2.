package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalPcmDeadlineQueueTest {
    @Test
    fun burstIsSpreadAcrossPerChunkDeadlinesAtPlaybackSpeed() {
        val queue = OriginalPcmDeadlineQueue(sampleRate = 16_000)
        val chunkA = ByteArray(3_200) { 1 }
        val chunkB = ByteArray(3_200) { 2 }
        val chunkC = ByteArray(3_200) { 3 }
        assertEquals(1_000L, queue.enqueue(chunkA, playbackSpeed = 2f, nowMs = 1_000L))
        assertEquals(1_050L, queue.enqueue(chunkB, playbackSpeed = 2f, nowMs = 1_000L))
        assertEquals(1_100L, queue.enqueue(chunkC, playbackSpeed = 2f, nowMs = 1_000L))

        assertNull(queue.pollReady(999L))
        assertArrayEquals(chunkA, queue.pollReady(1_000L)?.data)
        assertNull(queue.pollReady(1_049L))
        assertArrayEquals(chunkB, queue.pollReady(1_050L)?.data)
        assertNull(queue.pollReady(1_099L))
        assertArrayEquals(chunkC, queue.pollReady(1_100L)?.data)
    }

    @Test
    fun pauseResumeShiftsEveryPendingDeadline() {
        val queue = OriginalPcmDeadlineQueue(sampleRate = 16_000)
        val first = ByteArray(3_200) { 1 }
        val second = ByteArray(3_200) { 2 }
        val third = ByteArray(3_200) { 3 }

        queue.enqueue(first, playbackSpeed = 1f, nowMs = 1_000L)
        queue.enqueue(second, playbackSpeed = 1f, nowMs = 1_000L)
        queue.enqueue(third, playbackSpeed = 1f, nowMs = 1_000L)
        assertArrayEquals(first, queue.pollReady(1_000L)?.data)

        queue.pause(1_050L)
        assertNull(queue.pollReady(10_000L))
        assertEquals(2_000L, queue.resume(3_050L))

        assertNull(queue.pollReady(3_099L))
        assertArrayEquals(second, queue.pollReady(3_100L)?.data)
        assertNull(queue.pollReady(3_199L))
        assertArrayEquals(third, queue.pollReady(3_200L)?.data)
    }

    @Test
    fun clearAdvancesGenerationAndInvalidatesOldTimeline() {
        val queue = OriginalPcmDeadlineQueue(sampleRate = 16_000)
        val old = ByteArray(3_200) { 7 }
        val fresh = ByteArray(3_200) { 9 }

        queue.enqueue(old, playbackSpeed = 1f, nowMs = 1_000L)
        val before = queue.stats()
        assertEquals(1, before.queuedChunks)

        assertEquals(1, queue.clear())
        val after = queue.stats()
        assertEquals(before.generation + 1L, after.generation)
        assertEquals(0, after.queuedChunks)
        assertFalse(after.paused)
        assertNull(queue.pollReady(10_000L))

        val due = queue.enqueue(fresh, playbackSpeed = 1f, nowMs = 5_000L)
        assertEquals(5_000L, due)
        val ready = queue.pollReady(5_000L)
        assertArrayEquals(fresh, ready?.data)
        assertTrue(queue.isGenerationCurrent(ready?.generation ?: -1L))
    }

    @Test
    fun retimeReSpacesPendingChunksWhenFileSpeedChanges() {
        val queue = OriginalPcmDeadlineQueue(sampleRate = 16_000)
        val chunks = List(3) { ByteArray(3_200) { it.toByte() } }

        chunks.forEach { queue.enqueue(it, playbackSpeed = 1f, nowMs = 1_000L) }
        queue.retime(playbackSpeed = 2f, nowMs = 1_000L)

        assertArrayEquals(chunks[0], queue.pollReady(1_000L)?.data)
        assertNull(queue.pollReady(1_049L))
        assertArrayEquals(chunks[1], queue.pollReady(1_050L)?.data)
        assertNull(queue.pollReady(1_099L))
        assertArrayEquals(chunks[2], queue.pollReady(1_100L)?.data)
    }
}
