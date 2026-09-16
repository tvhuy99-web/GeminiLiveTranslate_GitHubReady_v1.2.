package com.oai.geminilivetranslate.core

import org.json.JSONArray
import org.json.JSONTokener

/**
 * Incrementally extracts JSON-array frames from an AI Studio streaming response.
 *
 * This is deliberately not wired into the production response path yet. The observed
 * GenerateContent stream normally has a wrapper array and individual candidate frames at
 * [chunkDepth]. Until device fixtures confirm that shape for every mode, callers must opt in.
 *
 * The parser keeps JSON string/escape state across network fragments, accepts a fragmented
 * XSSI prefix, and fails closed on malformed nesting, malformed frames, an oversized active
 * frame, or a stream that ends before its JSON structure is complete.
 */
class AiStudioIncrementalJsonArrayParser(
    private val chunkDepth: Int = DEFAULT_CHUNK_DEPTH,
    private val maxBufferedChars: Int = DEFAULT_MAX_BUFFERED_CHARS,
) {
    data class FeedResult(
        val frames: List<JSONArray>,
        val error: String = "",
        val reset: Boolean = false,
    ) {
        val ok: Boolean get() = error.isBlank()
    }

    private val buffer = StringBuilder()
    private var position = 0
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var frameStart = -1
    private var preambleResolved = false

    init {
        require(chunkDepth >= 1) { "chunkDepth must be >= 1" }
        require(maxBufferedChars >= MIN_BUFFERED_CHARS) {
            "maxBufferedChars must be >= $MIN_BUFFERED_CHARS"
        }
    }

    fun feed(fragment: String): FeedResult {
        if (fragment.isEmpty()) return FeedResult(emptyList())
        buffer.append(fragment)

        resolvePreamble()?.let { return it }

        val frames = ArrayList<JSONArray>()
        while (position < buffer.length) {
            val ch = buffer[position]

            if (escaped) {
                escaped = false
                position += 1
                continue
            }
            if (inString && ch == '\\') {
                escaped = true
                position += 1
                continue
            }
            if (ch == '"') {
                inString = !inString
                position += 1
                continue
            }
            if (inString) {
                position += 1
                if (buffer.length > maxBufferedChars && frameStart >= 0) {
                    return fail("BUFFER_LIMIT_EXCEEDED", frames)
                }
                continue
            }

            when (ch) {
                '[' -> {
                    depth += 1
                    if (depth == chunkDepth && frameStart < 0) frameStart = position
                }

                ']' -> {
                    if (depth <= 0) return fail("UNBALANCED_CLOSE_BRACKET", frames)
                    depth -= 1
                    if (depth == chunkDepth - 1 && frameStart >= 0) {
                        val rawFrame = buffer.substring(frameStart, position + 1)
                        val parsed = runCatching {
                            JSONTokener(rawFrame).nextValue() as? JSONArray
                        }.getOrNull() ?: return fail("INVALID_JSON_FRAME", frames)
                        frames += parsed

                        // The outer wrapper is intentionally not kept. Its logical nesting depth
                        // remains in [depth], while the consumed text can be discarded safely.
                        buffer.delete(0, position + 1)
                        position = 0
                        frameStart = -1
                        compactScannedPrefixIfSafe()
                        continue
                    }
                }
            }

            position += 1
            if (buffer.length > maxBufferedChars) {
                if (frameStart >= 0) return fail("BUFFER_LIMIT_EXCEEDED", frames)
                compactScannedPrefixIfSafe(force = true)
                if (buffer.length > maxBufferedChars) return fail("BUFFER_LIMIT_EXCEEDED", frames)
            }
        }

        compactScannedPrefixIfSafe()
        return FeedResult(frames)
    }

    /**
     * Marks the current transport response as complete.
     *
     * A successful [feed] only means every frame seen so far was structurally valid. The network
     * can still terminate with an open wrapper, an unfinished frame, an unfinished JSON string, or
     * even half of the XSSI preamble buffered. [finish] turns those silent truncations into explicit
     * fail-closed errors and resets the parser for the next response.
     */
    fun finish(): FeedResult {
        if (!preambleResolved) {
            val current = buffer.toString()
            if (current.isNotEmpty() && current.length < XSSI_PREFIX.length && XSSI_PREFIX.startsWith(current)) {
                return fail("INCOMPLETE_XSSI_PREFIX", emptyList())
            }
            resolvePreamble()?.let { return it }
        }

        val error = when {
            escaped -> "INCOMPLETE_ESCAPE"
            inString -> "INCOMPLETE_STRING"
            frameStart >= 0 -> "INCOMPLETE_JSON_FRAME"
            depth != 0 -> "INCOMPLETE_JSON_STREAM"
            else -> ""
        }
        if (error.isNotBlank()) return fail(error, emptyList())

        reset()
        return FeedResult(emptyList(), reset = true)
    }

    fun reset() {
        buffer.setLength(0)
        position = 0
        depth = 0
        inString = false
        escaped = false
        frameStart = -1
        preambleResolved = false
    }

    fun bufferedChars(): Int = buffer.length

    fun currentDepth(): Int = depth

    private fun resolvePreamble(): FeedResult? {
        if (preambleResolved) return null
        val current = buffer.toString()

        // A network fragment can end in the middle of the XSSI marker. Do not interpret those
        // characters as JSON until it is clear whether the marker is present.
        if (current.length < XSSI_PREFIX.length && XSSI_PREFIX.startsWith(current)) {
            return FeedResult(emptyList())
        }

        if (current.startsWith(XSSI_PREFIX)) {
            buffer.delete(0, XSSI_PREFIX.length)
            while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
        }
        preambleResolved = true
        position = 0
        return null
    }

    private fun compactScannedPrefixIfSafe(force: Boolean = false) {
        if (frameStart >= 0 || inString || position <= 0) return
        if (!force && buffer.length < COMPACT_THRESHOLD) return
        if (position > buffer.length) return
        buffer.delete(0, position)
        position = 0
    }

    private fun fail(error: String, frames: List<JSONArray>): FeedResult {
        reset()
        return FeedResult(frames = frames.toList(), error = error, reset = true)
    }

    companion object {
        private const val XSSI_PREFIX = ")]}'"
        private const val DEFAULT_CHUNK_DEPTH = 3
        private const val DEFAULT_MAX_BUFFERED_CHARS = 2 * 1024 * 1024
        private const val MIN_BUFFERED_CHARS = 1_024
        private const val COMPACT_THRESHOLD = 64 * 1024
    }
}
