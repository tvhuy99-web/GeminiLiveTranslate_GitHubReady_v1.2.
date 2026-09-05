from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


executor = Path("app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
client = Path("app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt")
test = Path("app/src/test/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionStreamingTest.kt")

replace_once(
    executor,
    """    private data class Pending(\n        val seq: Int,\n        val callback: (Result) -> Unit,\n        val startedAt: Long,\n        var firstProgressAt: Long = 0L,\n        var lastProgressAt: Long = 0L,\n        var lastResponseChars: Int = 0,\n    )\n""",
    """    private data class Pending(\n        val seq: Int,\n        val callback: (Result) -> Unit,\n        val startedAt: Long,\n        val completionValidator: ((String) -> Boolean)? = null,\n        var firstProgressAt: Long = 0L,\n        var lastProgressAt: Long = 0L,\n        var lastResponseChars: Int = 0,\n    )\n""",
)

replace_once(
    executor,
    """    private fun beginPreparedAttachmentRequest(\n        callback: (Result) -> Unit,\n        mode: String,\n    ): Pending {\n        seq += 1\n        val request = Pending(\n            seq = seq,\n            callback = callback,\n            startedAt = SystemClock.uptimeMillis(),\n        )\n""",
    """    private fun beginPreparedAttachmentRequest(\n        callback: (Result) -> Unit,\n        mode: String,\n        completionValidator: ((String) -> Boolean)? = null,\n    ): Pending {\n        seq += 1\n        val request = Pending(\n            seq = seq,\n            callback = callback,\n            startedAt = SystemClock.uptimeMillis(),\n            completionValidator = completionValidator,\n        )\n""",
)

replace_once(
    executor,
    """    fun generateAttachmentNativeOnly(\n        prompt: String,\n        onPartial: ((String) -> Unit)? = null,\n        callback: (Result) -> Unit,\n    ): Boolean {\n""",
    """    fun generateAttachmentNativeOnly(\n        prompt: String,\n        onPartial: ((String) -> Unit)? = null,\n        completionValidator: ((String) -> Boolean)? = null,\n        callback: (Result) -> Unit,\n    ): Boolean {\n""",
)

replace_once(
    executor,
    """            val request = beginPreparedAttachmentRequest(callback, \"attachment-native-only\")\n""",
    """            val request = beginPreparedAttachmentRequest(\n                callback = callback,\n                mode = \"attachment-native-only\",\n                completionValidator = completionValidator,\n            )\n""",
)

replace_once(
    executor,
    """        if (sttModeModel != null && result.ok && result.complete && result.modelText.isBlank()) {\n            events?.onLog(\"R28_STT_NETWORK_COMPLETE_EMPTY\", \"seq=$requestSeq status=${result.status} phase=${result.phase}; waiting for dedicated STT DOM result\")\n            return\n        }\n        if (result.ok && result.complete) finish(requestSeq, result)\n""",
    """        if (sttModeModel != null && result.ok && result.complete && result.modelText.isBlank()) {\n            events?.onLog(\"R28_STT_NETWORK_COMPLETE_EMPTY\", \"seq=$requestSeq status=${result.status} phase=${result.phase}; waiting for dedicated STT DOM result\")\n            return\n        }\n        if (result.ok && result.complete) {\n            val validator = p.completionValidator\n            if (validator != null && !validator(result.modelText)) {\n                events?.onLog(\n                    \"R36_VIDEO_COMPLETION_DEFERRED\",\n                    \"seq=$requestSeq chars=${result.modelText.length} status=${result.status} phase=${result.phase}\",\n                )\n                return\n            }\n            finish(requestSeq, result)\n        }\n""",
)

replace_once(
    executor,
    """        const val VERSION = \"2026-09-05-web-session-r12.8-cleanup\"\n""",
    """        const val VERSION = \"2026-09-06-web-session-r12.9-json-completion-guard\"\n""",
)

replace_once(
    client,
    """        val webResult = generateAndAwaitAuto(exec, prompt) { rawPartial ->\n""",
    """        val webResult = generateAndAwaitAuto(exec, prompt, mode) { rawPartial ->\n""",
)

replace_once(
    client,
    """    private fun generateAndAwaitAuto(\n        exec: AiStudioWebSessionExecutor,\n        prompt: String,\n        onPartial: (String) -> Unit,\n    ): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val resultRef = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.generateAttachmentNativeOnly(prompt = prompt, onPartial = onPartial) { result ->\n""",
    """    private fun generateAndAwaitAuto(\n        exec: AiStudioWebSessionExecutor,\n        prompt: String,\n        mode: GeminiVideoDescriptionClient.Mode,\n        onPartial: (String) -> Unit,\n    ): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val resultRef = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        val completionValidator: ((String) -> Boolean)? = if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {\n            { raw -> isCompleteJsonObject(raw) }\n        } else {\n            null\n        }\n        main.post {\n            val accepted = exec.generateAttachmentNativeOnly(\n                prompt = prompt,\n                onPartial = onPartial,\n                completionValidator = completionValidator,\n            ) { result ->\n""",
)

replace_once(
    client,
    """        internal fun streamingTextForUi(\n""",
    """        internal fun isCompleteJsonObject(raw: String): Boolean {\n            val text = raw.trim().removePrefix(\"```json\").removePrefix(\"```\").removeSuffix(\"```\").trim()\n            val start = text.indexOf('{')\n            if (start < 0) return false\n            var depth = 0\n            var inString = false\n            var escaped = false\n            var end = -1\n            for (i in start until text.length) {\n                val ch = text[i]\n                if (inString) {\n                    if (escaped) escaped = false\n                    else if (ch == '\\\\') escaped = true\n                    else if (ch == '\"') inString = false\n                    continue\n                }\n                when (ch) {\n                    '\"' -> inString = true\n                    '{', '[' -> depth += 1\n                    '}', ']' -> {\n                        depth -= 1\n                        if (depth < 0) return false\n                        if (depth == 0) {\n                            end = i\n                            break\n                        }\n                    }\n                }\n            }\n            if (inString || depth != 0 || end < 0) return false\n            val trailing = text.substring(end + 1).trim()\n            if (trailing.isNotEmpty()) return false\n            return runCatching { JSONObject(text.substring(start, end + 1)); true }.getOrDefault(false)\n        }\n\n        internal fun streamingTextForUi(\n""",
)

replace_once(
    test,
    """import org.junit.Assert.assertEquals\n""",
    """import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\n""",
)

replace_once(
    test,
    """    @Test\n    fun timelineCollectsMultipleTextFieldsIncludingLastPartial() {\n        val raw = \"\"\"{\"items\":[{\"text\":\"Cảnh một\"},{\"text\":\"Cảnh hai đang hình thành\"\"\"\n        assertEquals(\n            \"Cảnh một\\nCảnh hai đang hình thành\",\n            AiStudioVideoDescriptionClient.streamingTextForUi(raw, GeminiVideoDescriptionClient.Mode.TIMELINE),\n        )\n    }\n""",
    """    @Test\n    fun timelineCollectsMultipleTextFieldsIncludingLastPartial() {\n        val raw = \"\"\"{\"items\":[{\"text\":\"Cảnh một\"},{\"text\":\"Cảnh hai đang hình thành\"\"\"\n        assertEquals(\n            \"Cảnh một\\nCảnh hai đang hình thành\",\n            AiStudioVideoDescriptionClient.streamingTextForUi(raw, GeminiVideoDescriptionClient.Mode.TIMELINE),\n        )\n    }\n\n    @Test\n    fun timelineCompletionRejectsUnterminatedArray() {\n        val raw = \"\"\"{\"items\":[{\"index\":1,\"start_seconds\":0.0,\"end_seconds\":14.0,\"type\":\"description\",\"text\":\"Cảnh một\"},{\"index\":2,\"start_seconds\":14.0,\"end_seconds\":27.0,\"type\":\"description\",\"text\":\"Cảnh hai\"}\"\"\"\n        assertFalse(AiStudioVideoDescriptionClient.isCompleteJsonObject(raw))\n    }\n\n    @Test\n    fun timelineCompletionAcceptsClosedJsonObject() {\n        val raw = \"\"\"{\"items\":[{\"index\":1,\"start_seconds\":0.0,\"end_seconds\":14.0,\"type\":\"description\",\"text\":\"Cảnh một\"},{\"index\":2,\"start_seconds\":14.0,\"end_seconds\":27.0,\"type\":\"description\",\"text\":\"Cảnh hai\"}]}\"\"\"\n        assertTrue(AiStudioVideoDescriptionClient.isCompleteJsonObject(raw))\n    }\n\n    @Test\n    fun timelineCompletionRejectsOpenStringAtEnd() {\n        val raw = \"\"\"{\"items\":[{\"text\":\"Đang viết tiếp\"\"\"\n        assertFalse(AiStudioVideoDescriptionClient.isCompleteJsonObject(raw))\n    }\n""",
)

print("R18.28 JSON completion guard patch applied")
