from pathlib import Path
import base64


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


executor_path = Path("app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
s = executor_path.read_text()

s = replace_once(
    s,
    '''    private var activeAttachment: PendingAttachment? = null
    private var sttModeModel: String? = null
    private val nativeTapController = AiStudioNativeTapController(webView, null)
''',
    '''    private var activeAttachment: PendingAttachment? = null
    private var sttModeModel: String? = null
    private var attachmentPartialCallback: ((String) -> Unit)? = null
    private var attachmentPartialLastText: String = ""
    private val nativeTapController = AiStudioNativeTapController(webView, null)
''',
    "executor partial fields",
)

s = replace_once(
    s,
    '''    fun generateAttachmentNativeOnly(
        prompt: String,
        callback: (Result) -> Unit,
    ): Boolean {
        if (destroyed || !pageFinished || state != State.READY || pending != null || prompt.isBlank()) {
            callback(Result(ok = false, error = if (prompt.isBlank()) "EMPTY_PROMPT" else "NOT_READY_OR_BUSY"))
            return false
        }
        prepareAttachmentPrompt(prompt) { ok, detail, _ ->
            if (!ok) {
                callback(Result(ok = false, error = "PROMPT_PREPARE_FAILED", phase = detail.take(500)))
                return@prepareAttachmentPrompt
            }
            val request = beginPreparedAttachmentRequest(prompt, callback, "attachment-native-only")
''',
    '''    fun generateAttachmentNativeOnly(
        prompt: String,
        onPartial: ((String) -> Unit)? = null,
        callback: (Result) -> Unit,
    ): Boolean {
        if (destroyed || !pageFinished || state != State.READY || pending != null || prompt.isBlank()) {
            callback(Result(ok = false, error = if (prompt.isBlank()) "EMPTY_PROMPT" else "NOT_READY_OR_BUSY"))
            return false
        }
        attachmentPartialCallback = onPartial
        attachmentPartialLastText = ""
        prepareAttachmentPrompt(prompt) { ok, detail, _ ->
            if (!ok) {
                clearAttachmentPartial()
                callback(Result(ok = false, error = "PROMPT_PREPARE_FAILED", phase = detail.take(500)))
                return@prepareAttachmentPrompt
            }
            val request = beginPreparedAttachmentRequest(prompt, callback, "attachment-native-only")
''',
    "executor video generate callback",
)

s = replace_once(
    s,
    '''    private fun schedulePolls(requestSeq: Int) {
        listOf(450L, 900L, 1_500L, 2_500L, 4_000L, 6_500L, 9_000L, 13_000L, 20_000L, 30_000L).forEach { delay ->
            main.postDelayed({ if (pending?.seq == requestSeq) readNormalized(requestSeq, "poll-$delay") }, delay)
        }
    }
''',
    '''    private fun schedulePolls(requestSeq: Int) {
        listOf(450L, 900L, 1_500L, 2_500L, 4_000L, 6_500L, 9_000L, 13_000L, 20_000L, 30_000L).forEach { delay ->
            main.postDelayed({ if (pending?.seq == requestSeq) readNormalized(requestSeq, "poll-$delay") }, delay)
        }
        main.postDelayed(object : Runnable {
            override fun run() {
                if (pending?.seq != requestSeq || attachmentPartialCallback == null) return
                readNormalized(requestSeq, "video-partial-live")
                main.postDelayed(this, ATTACHMENT_PARTIAL_POLL_MS)
            }
        }, ATTACHMENT_PARTIAL_POLL_MS)
    }
''',
    "executor continuous partial polling",
)

insert_marker = '''    private fun timeoutRequest(requestSeq: Int, reason: String) {
'''
helper = '''    private fun publishAttachmentPartial(requestSeq: Int, text: String, source: String) {
        val p = pending ?: return
        val callback = attachmentPartialCallback ?: return
        if (p.seq != requestSeq) return
        val normalized = text.trim()
        if (normalized.isBlank() || normalized.length <= attachmentPartialLastText.length) return
        val previous = attachmentPartialLastText.length
        attachmentPartialLastText = normalized
        events?.onLog(
            "R35_VIDEO_PARTIAL_RAW",
            "seq=$requestSeq source=$source chars=${normalized.length} delta=${normalized.length - previous}",
        )
        callback(normalized)
    }

    private fun clearAttachmentPartial() {
        attachmentPartialCallback = null
        attachmentPartialLastText = ""
    }

'''
if s.count(insert_marker) != 1:
    raise SystemExit("executor helper insertion marker missing")
s = s.replace(insert_marker, helper + insert_marker, 1)

s = replace_once(
    s,
    '''            parseNormalized(decoded)?.let {
                recordProgress(requestSeq, it.modelText.length, "normalized-$source")
                maybeFinish(requestSeq, it)
            }
''',
    '''            parseNormalized(decoded)?.let {
                recordProgress(requestSeq, it.modelText.length, "normalized-$source")
                publishAttachmentPartial(requestSeq, it.modelText, "normalized-$source")
                maybeFinish(requestSeq, it)
            }
''',
    "executor normalized partial publish",
)

s = replace_once(
    s,
    '''            if (payload != null && (kind == "GENERATE_PROGRESS" || kind == "NORMALIZED_GENERATE_RESULT" || kind == "GENERATE_RESULT")) {
                val chars = payload.optInt("responseChars", payload.optString("modelText").length)
                main.post {
                    val p = pending ?: return@post
                    recordProgress(p.seq, chars, kind)
                }
            }
''',
    '''            if (payload != null && (kind == "GENERATE_PROGRESS" || kind == "NORMALIZED_GENERATE_RESULT" || kind == "GENERATE_RESULT")) {
                val chars = payload.optInt("responseChars", payload.optString("modelText").length)
                val partialText = payload.optString("modelText")
                main.post {
                    val p = pending ?: return@post
                    recordProgress(p.seq, chars, kind)
                    publishAttachmentPartial(p.seq, partialText, "js-$kind")
                }
            }
''',
    "executor JS partial publish",
)

s = replace_once(
    s,
    '''        pending = null
        directRecoverySeq = -1
        runCatching { webView.evaluateJavascript("window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.cancel()", null) }
        p.callback(Result(ok = false, error = "CANCELLED"))
''',
    '''        pending = null
        directRecoverySeq = -1
        clearAttachmentPartial()
        runCatching { webView.evaluateJavascript("window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.cancel()", null) }
        p.callback(Result(ok = false, error = "CANCELLED"))
''',
    "executor cancel clear partial",
)

s = replace_once(
    s,
    '''        cancelCurrent()
        destroyed = true
''',
    '''        cancelCurrent()
        clearAttachmentPartial()
        destroyed = true
''',
    "executor destroy clear partial",
)

s = replace_once(
    s,
    '''        pending = null
        directRecoverySeq = -1
        p.callback(result)
''',
    '''        pending = null
        directRecoverySeq = -1
        clearAttachmentPartial()
        p.callback(result)
''',
    "executor finish clear partial",
)

s = replace_once(
    s,
    '''        const val VERSION = "2026-09-05-web-session-r12.6-direct-stt-page"
''',
    '''        const val VERSION = "2026-09-05-web-session-r12.7-video-partial-stream"
''',
    "executor version",
)

s = replace_once(
    s,
    '''        private const val WATCHDOG_TICK_MS = 2_000L
''',
    '''        private const val WATCHDOG_TICK_MS = 2_000L
        private const val ATTACHMENT_PARTIAL_POLL_MS = 850L
''',
    "executor partial poll constant",
)

executor_path.write_text(s)

client_path = Path("app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt")
c = client_path.read_text()

c = replace_once(
    c,
    '''        val webResult = generateAndAwaitAuto(exec, prompt)
        val output = webResult.modelText.trim()
        if (output.isBlank()) error("AI Studio không trả nội dung mô tả")
        onPartial(output)
        if (includeOutputInLogs) logger.log(3, TAG, "Output preview=${output.replace('\\n', ' ').take(2000)}")

        val timelineItems: List<GeminiVideoDescriptionClient.TimelineItem>
''',
    '''        var lastPartialText = ""
        val webResult = generateAndAwaitAuto(exec, prompt) { rawPartial ->
            val displayPartial = streamingTextForUi(rawPartial, mode)
            if (displayPartial.isNotBlank() && displayPartial.length > lastPartialText.length) {
                val previous = lastPartialText.length
                lastPartialText = displayPartial
                logger.log(
                    2,
                    TAG,
                    "R35_VIDEO_PARTIAL_UI mode=$mode chars=${displayPartial.length} delta=${displayPartial.length - previous}",
                )
                onPartial(displayPartial)
            }
        }
        val output = webResult.modelText.trim()
        if (output.isBlank()) error("AI Studio không trả nội dung mô tả")
        if (includeOutputInLogs) logger.log(3, TAG, "Output preview=${output.replace('\\n', ' ').take(2000)}")

        val timelineItems: List<GeminiVideoDescriptionClient.TimelineItem>
''',
    "client stream callback",
)

c = replace_once(
    c,
    '''        if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {
            timelineItems = parseTimeline(output, durationSeconds)
            summaryText = ""
        } else {
            timelineItems = emptyList()
            summaryText = parseSummary(output)
        }
        onProgress("Đang tạo kết quả...", 98)
''',
    '''        if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {
            timelineItems = parseTimeline(output, durationSeconds)
            summaryText = ""
        } else {
            timelineItems = emptyList()
            summaryText = parseSummary(output)
        }
        val finalDisplayText = if (mode == GeminiVideoDescriptionClient.Mode.TIMELINE) {
            timelineItems.joinToString("\\n") { it.text }.trim()
        } else {
            summaryText.trim()
        }
        if (finalDisplayText.isNotBlank() && finalDisplayText != lastPartialText) {
            onPartial(finalDisplayText)
        }
        onProgress("Đang tạo kết quả...", 98)
''',
    "client final sanitized partial",
)

c = replace_once(
    c,
    '''                            name.startsWith("R24_") || name.startsWith("JS_R24_") || name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2
''',
    '''                            name.startsWith("R35_") || name.startsWith("JS_R35_") || name.startsWith("R24_") || name.startsWith("JS_R24_") || name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2
''',
    "client R35 log visibility",
)

c = replace_once(
    c,
    '''    private fun generateAndAwaitAuto(
        exec: AiStudioWebSessionExecutor,
        prompt: String,
    ): AiStudioWebSessionExecutor.Result {
''',
    '''    private fun generateAndAwaitAuto(
        exec: AiStudioWebSessionExecutor,
        prompt: String,
        onPartial: (String) -> Unit,
    ): AiStudioWebSessionExecutor.Result {
''',
    "client generate signature",
)

c = replace_once(
    c,
    '''            val accepted = exec.generateAttachmentNativeOnly(prompt = prompt) { result ->
''',
    '''            val accepted = exec.generateAttachmentNativeOnly(prompt = prompt, onPartial = onPartial) { result ->
''',
    "client pass partial",
)

c = replace_once(
    c,
    '''    companion object {
        private const val TAG = "AiStudioVideo"
    }
''',
    '''    companion object {
        private const val TAG = "AiStudioVideo"

        internal fun streamingTextForUi(
            raw: String,
            mode: GeminiVideoDescriptionClient.Mode,
        ): String {
            val values = extractStreamingJsonStringValues(raw, "text")
            if (values.isEmpty()) return ""
            return if (mode == GeminiVideoDescriptionClient.Mode.SUMMARY) {
                values.first().trim()
            } else {
                values.joinToString("\\n") { it.trim() }.trim()
            }
        }

        private fun extractStreamingJsonStringValues(raw: String, field: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val needle = "\\\"$field\\\""
            val values = ArrayList<String>()
            var from = 0
            while (from < raw.length) {
                val key = raw.indexOf(needle, from)
                if (key < 0) break
                if (key > 0 && raw[key - 1] == '\\\\') {
                    from = key + needle.length
                    continue
                }
                var i = key + needle.length
                while (i < raw.length && raw[i].isWhitespace()) i++
                if (i >= raw.length || raw[i] != ':') {
                    from = key + needle.length
                    continue
                }
                i++
                while (i < raw.length && raw[i].isWhitespace()) i++
                if (i >= raw.length || raw[i] != '\"') {
                    from = key + needle.length
                    continue
                }
                i++
                val out = StringBuilder()
                var closed = false
                while (i < raw.length) {
                    val ch = raw[i++]
                    if (ch == '\"') {
                        closed = true
                        break
                    }
                    if (ch != '\\\\') {
                        out.append(ch)
                        continue
                    }
                    if (i >= raw.length) break
                    when (val escaped = raw[i++]) {
                        '\"' -> out.append('\"')
                        '\\\\' -> out.append('\\\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\\b')
                        'f' -> out.append('\\u000C')
                        'n' -> out.append('\\n')
                        'r' -> out.append('\\r')
                        't' -> out.append('\\t')
                        'u' -> {
                            if (i + 4 <= raw.length) {
                                val hex = raw.substring(i, i + 4)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    out.append(code.toChar())
                                    i += 4
                                }
                            }
                        }
                        else -> out.append(escaped)
                    }
                }
                val value = out.toString()
                if (value.isNotBlank()) values += value
                from = if (closed) i else raw.length
            }
            return values
        }
    }
''',
    "client streaming JSON parser",
)

client_path.write_text(c)

test_b64 = "cGFja2FnZSBjb20ub2FpLmdlbWluaWxpdmV0cmFuc2xhdGUubmV0d29yawoKaW1wb3J0IG9yZy5qdW5pdC5Bc3NlcnQuYXNzZXJ0RXF1YWxzCmltcG9ydCBvcmcuanVuaXQuVGVzdAoKY2xhc3MgQWlTdHVkaW9WaWRlb0Rlc2NyaXB0aW9uU3RyZWFtaW5nVGVzdCB7CiAgICBAVGVzdAogICAgZnVuIHN1bW1hcnlFeHRyYWN0c0luY29tcGxldGVKc29uU3RyaW5nKCkgewogICAgICAgIHZhbCByYXcgPSAiIiJ7InRleHQiOiJYaW4gY2jDoG9cbnRo4bq/IGdp4bubaSIiIgogICAgICAgIGFzc2VydEVxdWFscygKICAgICAgICAgICAgIlhpbiBjaMOgb1xudGjhur8gZ2nhu5tpIiwKICAgICAgICAgICAgQWlTdHVkaW9WaWRlb0Rlc2NyaXB0aW9uQ2xpZW50LnN0cmVhbWluZ1RleHRGb3JVaShyYXcsIEdlbWluaVZpZGVvRGVzY3JpcHRpb25DbGllbnQuTW9kZS5TVU1NQVJZKSwKICAgICAgICApCiAgICB9CgogICAgQFRlc3QKICAgIGZ1biBzdW1tYXJ5RGVjb2Rlc0VzY2FwZWRRdW90ZSgpIHsKICAgICAgICB2YWwgcmF3ID0gIiIieyJ0ZXh0IjoiQW5oIOG6pXkgbsOzaSBcInhpbiBjaMOgb1wiIHLhu5NpIMSRaSB0aeG6v3AifSIiIgogICAgICAgIGFzc2VydEVxdWFscygKICAgICAgICAgICAgIkFuaCDhuqV5IG7Ds2kgXCJ4aW4gY2jDoG9cIiBy4buTaSDEkWkgdGnhur9wIiwKICAgICAgICAgICAgQWlTdHVkaW9WaWRlb0Rlc2NyaXB0aW9uQ2xpZW50LnN0cmVhbWluZ1RleHRGb3JVaShyYXcsIEdlbWluaVZpZGVvRGVzY3JpcHRpb25DbGllbnQuTW9kZS5TVU1NQVJZKSwKICAgICAgICApCiAgICB9CgogICAgQFRlc3QKICAgIGZ1biB0aW1lbGluZUNvbGxlY3RzTXVsdGlwbGVUZXh0RmllbGRzSW5jbHVkaW5nTGFzdFBhcnRpYWwoKSB7CiAgICAgICAgdmFsIHJhdyA9ICIiInsiaXRlbXMiOlt7InRleHQiOiJD4bqjbmggbeG7mXQifSx7InRleHQiOiJD4bqjbmggaGFpIMSRYW5nIGjDrG5oIHRow6BuaCIiIgogICAgICAgIGFzc2VydEVxdWFscygKICAgICAgICAgICAgIkPhuqNuaCBt4buZdFxuQ+G6o25oIGhhaSDEkWFuZyBow6xuaCB0aMOgbmgiLAogICAgICAgICAgICBBaVN0dWRpb1ZpZGVvRGVzY3JpcHRpb25DbGllbnQuc3RyZWFtaW5nVGV4dEZvclVpKHJhdywgR2VtaW5pVmlkZW9EZXNjcmlwdGlvbkNsaWVudC5Nb2RlLlRJTUVMSU5FKSwKICAgICAgICApCiAgICB9Cn0K"
test_path = Path("app/src/test/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionStreamingTest.kt")
test_path.write_bytes(base64.b64decode(test_b64))

print("R18.26 patch applied")
