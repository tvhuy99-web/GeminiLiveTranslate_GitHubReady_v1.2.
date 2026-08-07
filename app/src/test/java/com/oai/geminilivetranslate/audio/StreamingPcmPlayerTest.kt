package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingPcmPlayerTest {
    @Test
    fun pauseMovesQueuedAudioIntoPreservedBacklog() {
        val player = StreamingPcmPlayer(
            sampleRate = 24_000,
            bufferBytes = 64_000,
            queueCapacity = 4,
            logger = null,
        )
        try {
            player.enqueue(ByteArray(12_000) { 1 })
            player.enqueue(ByteArray(12_000) { 2 })

            player.pause()

            val stats = player.stats()
            assertEquals(0L, stats.droppedChunks)
            assertEquals(0L, stats.pausedBacklogDroppedChunks)
            assertEquals(2, stats.pausedBacklogChunks)
            assertEquals(24_000L, stats.pausedBacklogBytes)
        } finally {
            player.stop()
        }
    }

    @Test
    fun audioArrivingWhilePausedIsPreservedInsteadOfUsingDropOldestQueue() {
        val player = StreamingPcmPlayer(
            sampleRate = 24_000,
            bufferBytes = 64_000,
            queueCapacity = 2,
            logger = null,
        )
        try {
            player.pause()
            repeat(100) { index ->
                player.enqueue(ByteArray(12_000) { index.toByte() })
            }

            val stats = player.stats()
            assertEquals(0L, stats.droppedChunks)
            assertEquals(0L, stats.pausedBacklogDroppedChunks)
            assertEquals(100, stats.pausedBacklogChunks)
            assertEquals(1_200_000L, stats.pausedBacklogBytes)

            player.flush()
            val flushed = player.stats()
            assertEquals(0, flushed.pausedBacklogChunks)
            assertEquals(0L, flushed.pausedBacklogBytes)
        } finally {
            player.stop()
        }
    }
}
