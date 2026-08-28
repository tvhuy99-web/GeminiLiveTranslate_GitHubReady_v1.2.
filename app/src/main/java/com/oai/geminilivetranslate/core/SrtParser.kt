package com.oai.geminilivetranslate.core

object SrtParser {
    data class Result(
        val cues: List<SubtitleStore.Cue>,
        val skippedBlocks: Int,
        val normalizedChars: Int,
    )

    fun parse(raw: String): Result {
        val normalized = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return Result(emptyList(), 0, 0)

        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = ArrayList<SubtitleStore.Cue>()
        var skipped = 0

        blocks.forEach { block ->
            val lines = block.lines().map { it.trimEnd() }
            val timingIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingIndex < 0) {
                skipped++
                return@forEach
            }

            val timing = lines[timingIndex].split("-->", limit = 2)
            if (timing.size != 2) {
                skipped++
                return@forEach
            }
            val startMs = parseTime(timing[0])
            val endMs = parseTime(timing[1].trim().substringBefore(' '))
            val text = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (startMs == null || endMs == null || endMs <= startMs || text.isBlank()) {
                skipped++
                return@forEach
            }

            val explicitIndex = lines.take(timingIndex)
                .firstOrNull { it.trim().matches(Regex("\\d+")) }
                ?.trim()
                ?.toIntOrNull()
            cues += SubtitleStore.Cue(
                index = explicitIndex ?: (cues.size + 1),
                startMs = startMs,
                endMs = endMs,
                text = text,
            )
        }

        return Result(
            cues = cues.sortedBy { it.startMs }.mapIndexed { index, cue ->
                cue.copy(index = index + 1)
            },
            skippedBlocks = skipped,
            normalizedChars = normalized.length,
        )
    }

    private fun parseTime(raw: String): Long? {
        val cleaned = raw.trim().replace('.', ',')
        val match = TIME_REGEX.matchEntire(cleaned) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millisRaw = match.groupValues[4]
        val millis = when (millisRaw.length) {
            1 -> millisRaw.toLongOrNull()?.times(100L)
            2 -> millisRaw.toLongOrNull()?.times(10L)
            else -> millisRaw.take(3).toLongOrNull()
        } ?: return null
        if (minutes !in 0..59 || seconds !in 0..59) return null
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private val TIME_REGEX = Regex("""(\d{1,3}):(\d{2}):(\d{2}),(\d{1,3})""")
}
