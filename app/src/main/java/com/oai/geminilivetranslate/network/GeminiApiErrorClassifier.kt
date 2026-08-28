package com.oai.geminilivetranslate.network

object GeminiApiErrorClassifier {
    private val httpCodePattern = Regex("""\bHTTP(?:=|\s+)(\d{3})\b""", RegexOption.IGNORE_CASE)

    fun httpCode(error: Throwable?): Int? {
        var current = error
        val visited = HashSet<Throwable>()
        while (current != null && visited.add(current)) {
            val match = httpCodePattern.find(current.message.orEmpty())
            if (match != null) return match.groupValues[1].toIntOrNull()
            current = current.cause
        }
        return null
    }

    fun requiresKeyFailover(error: Throwable?): Boolean =
        httpCode(error) in setOf(401, 403, 429)
}
