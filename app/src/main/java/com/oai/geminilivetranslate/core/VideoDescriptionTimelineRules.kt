package com.oai.geminilivetranslate.core

object VideoDescriptionTimelineRules {
    data class Item(
        val index: Int,
        val startSeconds: Double,
        val endSeconds: Double,
        val text: String,
    )

    data class Validation(
        val errors: List<String>,
        val lastEndSeconds: Double,
        val endCoverage: Double,
    ) {
        val valid: Boolean
            get() = errors.isEmpty()
    }

    fun validate(
        items: List<Item>,
        durationSeconds: Double,
        maxItemSeconds: Double = 15.0,
        toleranceSeconds: Double = 0.75,
    ): Validation {
        val errors = ArrayList<String>()
        var previousStart = -1.0

        items.forEachIndexed { position, item ->
            val expectedIndex = position + 1
            if (item.index != expectedIndex) {
                errors += "index=${item.index} expected=$expectedIndex"
            }
            if (!item.startSeconds.isFinite() || item.startSeconds < 0.0) {
                errors += "index=${item.index} start=${item.startSeconds}"
            }
            if (!item.endSeconds.isFinite() || item.endSeconds <= item.startSeconds) {
                errors += "index=${item.index} end=${item.endSeconds} start=${item.startSeconds}"
            }
            if (item.endSeconds > durationSeconds + toleranceSeconds) {
                errors += "index=${item.index} end=${item.endSeconds} duration=$durationSeconds"
            }
            if (
                item.startSeconds.isFinite() &&
                item.endSeconds.isFinite() &&
                item.endSeconds - item.startSeconds > maxItemSeconds + toleranceSeconds
            ) {
                errors += "index=${item.index} length=${item.endSeconds - item.startSeconds}"
            }
            if (
                item.startSeconds.isFinite() &&
                previousStart.isFinite() &&
                item.startSeconds + toleranceSeconds < previousStart
            ) {
                errors += "index=${item.index} time-order start=${item.startSeconds} previous=$previousStart"
            }
            if (item.text.isBlank()) {
                errors += "index=${item.index} text-blank"
            }
            previousStart = item.startSeconds
        }

        val lastEnd = items.lastOrNull()?.endSeconds?.takeIf(Double::isFinite) ?: 0.0
        val coverage = if (durationSeconds > 0.0) {
            (lastEnd / durationSeconds).coerceAtLeast(0.0)
        } else {
            0.0
        }
        return Validation(errors, lastEnd, coverage)
    }
}
