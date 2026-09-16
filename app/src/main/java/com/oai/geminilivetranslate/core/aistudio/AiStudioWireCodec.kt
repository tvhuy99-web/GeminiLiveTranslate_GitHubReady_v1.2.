package com.oai.geminilivetranslate.core.aistudio

import org.json.JSONArray
import org.json.JSONTokener

/**
 * Conservative codec for the reverse-engineered AI Studio GenerateContent wire array.
 *
 * This codec deliberately refuses to rewrite bodies whose structural shape does not match
 * the expected layout. AI Studio is an internal, changeable protocol, so a failed shape check
 * must be treated as a protocol change instead of guessing and corrupting a request.
 */
object AiStudioWireCodec {
    data class Layout(
        val modelIndex: Int = 0,
        val contentsIndex: Int = 1,
        val safetyIndex: Int = 2,
        val generationConfigIndex: Int = 3,
        val snapshotIndex: Int = 4,
        val systemInstructionIndex: Int = 5,
        val toolsIndex: Int = 6,
        val requestFlagIndex: Int = 10,
        val cachedContentIndex: Int = 11,
        val timezoneIndex: Int = 13,
    )

    data class Shape(
        val valid: Boolean,
        val errors: List<String>,
        val rootLength: Int,
        val fingerprint: String,
        val model: String,
    )

    data class Summary(
        val model: String,
        val rootLength: Int,
        val contentsCount: Int,
        val generationConfigLength: Int,
        val hasSnapshot: Boolean,
        val fingerprint: String,
    )

    data class Mutation(
        val model: String? = null,
        val contents: JSONArray? = null,
        val safety: JSONArray? = null,
        val generationConfigOverrides: Map<Int, Any?> = emptyMap(),
        val snapshot: String? = null,
        val replaceSnapshot: Boolean = false,
        val systemInstruction: Any? = UNCHANGED,
        val tools: Any? = UNCHANGED,
        val requestFlag: Any? = UNCHANGED,
        val cachedContent: Any? = UNCHANGED,
        val timezone: Any? = UNCHANGED,
    )

    class ProtocolShapeException(message: String) : IllegalArgumentException(message)

    val DEFAULT_LAYOUT = Layout()

    private object UNCHANGED

    fun inspect(rawBody: String, layout: Layout = DEFAULT_LAYOUT): Shape {
        val root = runCatching { parseRoot(rawBody) }.getOrElse {
            return Shape(
                valid = false,
                errors = listOf("ROOT_NOT_JSON_ARRAY:${it.message.orEmpty()}"),
                rootLength = -1,
                fingerprint = "invalid",
                model = "",
            )
        }
        val errors = mutableListOf<String>()
        val model = root.optString(layout.modelIndex, "").trim()
        if (model.isBlank()) errors += "MODEL_MISSING"
        if (model.isNotBlank() && !looksLikeModel(model)) errors += "MODEL_UNEXPECTED:$model"
        requireArraySlot(root, layout.contentsIndex, "CONTENTS", errors, required = true)
        requireArraySlot(root, layout.generationConfigIndex, "GENERATION_CONFIG", errors, required = false)
        if (layout.snapshotIndex < root.length() && !root.isNull(layout.snapshotIndex) && root.opt(layout.snapshotIndex) !is String) {
            errors += "SNAPSHOT_NOT_STRING"
        }
        return Shape(
            valid = errors.isEmpty(),
            errors = errors,
            rootLength = root.length(),
            fingerprint = fingerprint(root),
            model = model,
        )
    }

    fun summarize(rawBody: String, layout: Layout = DEFAULT_LAYOUT): Summary {
        val root = parseRoot(rawBody)
        val shape = inspect(rawBody, layout)
        if (!shape.valid) throw ProtocolShapeException(shape.errors.joinToString(";"))
        val contents = root.optJSONArray(layout.contentsIndex)
        val generation = root.optJSONArray(layout.generationConfigIndex)
        return Summary(
            model = shape.model,
            rootLength = root.length(),
            contentsCount = contents?.length() ?: 0,
            generationConfigLength = generation?.length() ?: 0,
            hasSnapshot = layout.snapshotIndex < root.length() && !root.isNull(layout.snapshotIndex) && root.optString(layout.snapshotIndex).isNotBlank(),
            fingerprint = shape.fingerprint,
        )
    }

    fun rewrite(
        rawBody: String,
        mutation: Mutation,
        layout: Layout = DEFAULT_LAYOUT,
    ): String {
        val root = parseRoot(rawBody)
        val shape = inspect(rawBody, layout)
        if (!shape.valid) {
            throw ProtocolShapeException("Refusing AI Studio wire rewrite: ${shape.errors.joinToString(";")}; shape=${shape.fingerprint}")
        }

        mutation.model?.trim()?.takeIf(String::isNotEmpty)?.let { model ->
            root.put(layout.modelIndex, if (model.startsWith("models/")) model else "models/$model")
        }
        mutation.contents?.let { root.put(layout.contentsIndex, deepCopy(it)) }
        mutation.safety?.let { root.put(layout.safetyIndex, deepCopy(it)) }

        if (mutation.generationConfigOverrides.isNotEmpty()) {
            val generation = root.optJSONArray(layout.generationConfigIndex)
                ?: JSONArray().also { root.put(layout.generationConfigIndex, it) }
            mutation.generationConfigOverrides.forEach { (index, value) ->
                require(index >= 0) { "generation config index must be >= 0" }
                generation.put(index, jsonValue(value))
            }
        }

        if (mutation.replaceSnapshot) {
            root.put(layout.snapshotIndex, jsonValue(mutation.snapshot))
        }
        putIfChanged(root, layout.systemInstructionIndex, mutation.systemInstruction)
        putIfChanged(root, layout.toolsIndex, mutation.tools)
        putIfChanged(root, layout.requestFlagIndex, mutation.requestFlag)
        putIfChanged(root, layout.cachedContentIndex, mutation.cachedContent)
        putIfChanged(root, layout.timezoneIndex, mutation.timezone)

        return root.toString()
    }

    private fun parseRoot(rawBody: String): JSONArray {
        val parsed = JSONTokener(rawBody).nextValue()
        return parsed as? JSONArray ?: throw ProtocolShapeException("AI Studio wire root is not an array")
    }

    private fun looksLikeModel(value: String): Boolean {
        val normalized = value.removePrefix("models/")
        return normalized.startsWith("gemini-", ignoreCase = true) ||
            normalized.startsWith("gemma-", ignoreCase = true) ||
            normalized.startsWith("veo-", ignoreCase = true)
    }

    private fun requireArraySlot(
        root: JSONArray,
        index: Int,
        name: String,
        errors: MutableList<String>,
        required: Boolean,
    ) {
        if (index >= root.length()) {
            if (required) errors += "${name}_MISSING"
            return
        }
        if (root.isNull(index)) {
            if (required) errors += "${name}_NULL"
            return
        }
        if (root.opt(index) !is JSONArray) errors += "${name}_NOT_ARRAY"
    }

    private fun fingerprint(root: JSONArray): String = buildString {
        val count = minOf(root.length(), 20)
        for (i in 0 until count) {
            if (i > 0) append(',')
            append(
                when (val value = root.opt(i)) {
                    null, org.json.JSONObject.NULL -> 'n'
                    is JSONArray -> 'a'
                    is org.json.JSONObject -> 'o'
                    is String -> 's'
                    is Number -> 'd'
                    is Boolean -> 'b'
                    else -> '?'
                },
            )
        }
        if (root.length() > count) append(",+")
    }

    private fun putIfChanged(root: JSONArray, index: Int, value: Any?) {
        if (value === UNCHANGED) return
        root.put(index, jsonValue(value))
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> org.json.JSONObject.NULL
        is JSONArray -> deepCopy(value)
        is org.json.JSONObject -> org.json.JSONObject(value.toString())
        else -> value
    }

    private fun deepCopy(array: JSONArray): JSONArray = JSONArray(array.toString())
}
