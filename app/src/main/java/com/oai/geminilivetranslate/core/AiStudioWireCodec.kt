package com.oai.geminilivetranslate.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative codec for the reverse-engineered AI Studio GenerateContent wire array.
 *
 * The protocol is private and can change without notice. This codec therefore validates the
 * observed top-level shape before touching it and only rewrites fields that are explicitly known.
 * Unknown/opaque slots are preserved byte-for-byte at the JSON value level.
 */
object AiStudioWireCodec {
    const val MODEL_INDEX = 0
    const val CONTENTS_INDEX = 1
    const val SAFETY_INDEX = 2
    const val GENERATION_CONFIG_INDEX = 3
    const val SNAPSHOT_INDEX = 4
    const val SYSTEM_INSTRUCTION_INDEX = 5
    const val TOOLS_INDEX = 6

    data class Shape(
        val valid: Boolean,
        val error: String = "",
        val topLevelSize: Int = 0,
        val model: String = "",
        val hasContents: Boolean = false,
        val hasGenerationConfig: Boolean = false,
        val hasSnapshotSlot: Boolean = false,
        val fingerprint: String = "",
    )

    data class Decoded(
        val model: String,
        val prompt: String?,
        val snapshot: String?,
        val shape: Shape,
    )

    fun inspect(rawBody: String): Shape {
        val root = runCatching { JSONArray(rawBody) }.getOrElse {
            return Shape(valid = false, error = "INVALID_JSON:${it.javaClass.simpleName}")
        }
        if (root.length() <= SNAPSHOT_INDEX) {
            return Shape(
                valid = false,
                error = "WIRE_TOO_SHORT",
                topLevelSize = root.length(),
                fingerprint = fingerprint(root),
            )
        }

        val model = root.opt(MODEL_INDEX) as? String
            ?: return Shape(
                valid = false,
                error = "MODEL_SLOT_NOT_STRING",
                topLevelSize = root.length(),
                fingerprint = fingerprint(root),
            )
        if (!model.startsWith("models/")) {
            return Shape(
                valid = false,
                error = "MODEL_SLOT_UNEXPECTED",
                topLevelSize = root.length(),
                model = model.take(160),
                fingerprint = fingerprint(root),
            )
        }

        val contents = root.optJSONArray(CONTENTS_INDEX)
            ?: return Shape(
                valid = false,
                error = "CONTENTS_SLOT_NOT_ARRAY",
                topLevelSize = root.length(),
                model = model,
                fingerprint = fingerprint(root),
            )

        val generation = root.opt(GENERATION_CONFIG_INDEX)
        if (generation != null && generation !== JSONObject.NULL && generation !is JSONArray) {
            return Shape(
                valid = false,
                error = "GENERATION_CONFIG_SLOT_UNEXPECTED",
                topLevelSize = root.length(),
                model = model,
                hasContents = true,
                fingerprint = fingerprint(root),
            )
        }

        val snapshot = root.opt(SNAPSHOT_INDEX)
        if (snapshot != null && snapshot !== JSONObject.NULL && snapshot !is String) {
            return Shape(
                valid = false,
                error = "SNAPSHOT_SLOT_UNEXPECTED",
                topLevelSize = root.length(),
                model = model,
                hasContents = true,
                hasGenerationConfig = generation is JSONArray,
                fingerprint = fingerprint(root),
            )
        }

        return Shape(
            valid = true,
            topLevelSize = root.length(),
            model = model,
            hasContents = true,
            hasGenerationConfig = generation is JSONArray,
            hasSnapshotSlot = true,
            fingerprint = fingerprint(root),
        )
    }

    fun decode(rawBody: String): Decoded {
        val shape = inspect(rawBody)
        require(shape.valid) { "Unsupported AI Studio wire shape: ${shape.error} (${shape.fingerprint})" }
        val root = JSONArray(rawBody)
        return Decoded(
            model = root.getString(MODEL_INDEX),
            prompt = findLastUserText(root.getJSONArray(CONTENTS_INDEX)),
            snapshot = root.opt(SNAPSHOT_INDEX)?.takeUnless { it === JSONObject.NULL } as? String,
            shape = shape,
        )
    }

    /**
     * Rewrites only the requested semantic fields while preserving opaque protocol slots.
     * Prompt replacement preserves existing non-text parts, including attachment/file references.
     */
    fun rewrite(
        rawBody: String,
        model: String? = null,
        prompt: String? = null,
        snapshot: String? = null,
    ): String {
        val shape = inspect(rawBody)
        require(shape.valid) { "Unsupported AI Studio wire shape: ${shape.error} (${shape.fingerprint})" }

        val root = JSONArray(rawBody)
        if (model != null) {
            val normalized = model.trim().removePrefix("models/")
            require(normalized.isNotBlank()) { "model is blank" }
            root.put(MODEL_INDEX, "models/$normalized")
        }
        if (prompt != null) {
            val contents = root.getJSONArray(CONTENTS_INDEX)
            if (!replaceLastUserText(contents, prompt)) {
                contents.put(newUserContent(prompt))
            }
        }
        if (snapshot != null) {
            root.put(SNAPSHOT_INDEX, snapshot)
        }
        return root.toString()
    }

    private fun findLastUserText(contents: JSONArray): String? {
        for (i in contents.length() - 1 downTo 0) {
            val content = contents.optJSONArray(i) ?: continue
            if (content.optString(1) != "user") continue
            val parts = content.optJSONArray(0) ?: continue
            for (j in parts.length() - 1 downTo 0) {
                val part = parts.optJSONArray(j) ?: continue
                if (part.length() < 2) continue
                val first = part.opt(0)
                val text = part.opt(1)
                if ((first == null || first === JSONObject.NULL) && text is String) return text
            }
        }
        return null
    }

    private fun replaceLastUserText(contents: JSONArray, prompt: String): Boolean {
        for (i in contents.length() - 1 downTo 0) {
            val content = contents.optJSONArray(i) ?: continue
            if (content.optString(1) != "user") continue
            val parts = content.optJSONArray(0) ?: continue
            for (j in parts.length() - 1 downTo 0) {
                val part = parts.optJSONArray(j) ?: continue
                if (part.length() < 2) continue
                val first = part.opt(0)
                val text = part.opt(1)
                if ((first == null || first === JSONObject.NULL) && text is String) {
                    part.put(1, prompt)
                    return true
                }
            }
            parts.put(newTextPart(prompt))
            return true
        }
        return false
    }

    private fun newUserContent(prompt: String): JSONArray = JSONArray()
        .put(JSONArray().put(newTextPart(prompt)))
        .put("user")

    private fun newTextPart(prompt: String): JSONArray = JSONArray()
        .put(JSONObject.NULL)
        .put(prompt)

    /** Keep labels identical to AiStudioRequestGatewayScript.typeOf for device-log parity. */
    private fun fingerprint(root: JSONArray): String {
        val limit = minOf(root.length(), 14)
        return buildString {
            append("len=").append(root.length()).append(':')
            for (i in 0 until limit) {
                if (i > 0) append(',')
                append(i).append('=').append(typeOf(root.opt(i)))
            }
        }
    }

    private fun typeOf(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONArray -> "array"
        value is JSONObject -> "object"
        value is String -> "string"
        value is Number -> "number"
        value is Boolean -> "boolean"
        else -> value.javaClass.simpleName
    }
}
