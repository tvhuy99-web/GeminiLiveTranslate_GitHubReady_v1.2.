package com.oai.geminilivetranslate.core.aistudio

import org.json.JSONArray
import org.json.JSONTokener

/** Incremental parser for the array-framed streaming response emitted by AI Studio. */
class AiStudioIncrementalStreamParser {
    private var buffer = ""
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var chunkStart = -1
    private var position = 0
    private var preambleResolved = false

    fun feed(data: String): List<JSONArray> {
        if (data.isEmpty()) return emptyList()
        buffer += data
        val out = mutableListOf<JSONArray>()

        if (!preambleResolved) {
            val prefix = XSSI_PREFIX
            if (buffer.length < prefix.length && prefix.startsWith(buffer)) return emptyList()
            if (buffer.startsWith(prefix)) {
                buffer = buffer.substring(prefix.length).trimStart()
            }
            preambleResolved = true
            position = 0
        }

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
                continue
            }

            when (ch) {
                '[' -> {
                    depth += 1
                    if (depth == CHUNK_DEPTH && chunkStart < 0) chunkStart = position
                }
                ']' -> {
                    depth -= 1
                    if (depth < 0) {
                        reset()
                        return out
                    }
                    if (depth == CHUNK_DEPTH - 1 && chunkStart >= 0) {
                        val candidate = buffer.substring(chunkStart, position + 1)
                        val parsed = runCatching { JSONTokener(candidate).nextValue() as? JSONArray }.getOrNull()
                        if (parsed != null) out += parsed

                        buffer = buffer.substring(position + 1)
                        position = 0
                        chunkStart = -1
                        continue
                    }
                }
            }
            position += 1
        }

        compactIfSafe()
        return out
    }

    fun reset() {
        buffer = ""
        depth = 0
        inString = false
        escaped = false
        chunkStart = -1
        position = 0
        preambleResolved = false
    }

    fun bufferedChars(): Int = buffer.length

    private fun compactIfSafe() {
        if (chunkStart >= 0 || inString || buffer.length < COMPACT_THRESHOLD) return
        // Keep only the unscanned tail. Logical nesting depth is intentionally preserved.
        if (position > 0 && position <= buffer.length) {
            buffer = buffer.substring(position)
            position = 0
        }
    }

    companion object {
        private const val XSSI_PREFIX = ")]}'"
        private const val CHUNK_DEPTH = 3
        private const val COMPACT_THRESHOLD = 64 * 1024
    }
}
