from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# 1) MainActivity: use ACTION_OPEN_DOCUMENT and persist the returned read permission.
path = "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt"
text = read(path)
old = '''        val flags = result.data?.flags ?: 0
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                logger.log(1, "History", "Không giữ được quyền đọc lâu dài uriScheme=${uri.scheme}", it)
            }
        }
'''
new = '''        val takeFlags = (result.data?.flags ?: 0) and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (takeFlags != 0) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }.onFailure {
                logger.log(1, "History", "Không giữ được quyền đọc lâu dài uriScheme=${uri.scheme}", it)
            }
        }
'''
text = replace_once(text, old, new, "MainActivity persist permission")
old = '''    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        filePicker.launch(
            Intent.createChooser(intent, "Chọn tệp âm thanh hoặc video")
        )
    }
'''
new = '''    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        filePicker.launch(
            Intent.createChooser(intent, "Chọn tệp âm thanh hoặc video")
        )
    }
'''
text = replace_once(text, old, new, "MainActivity ACTION_OPEN_DOCUMENT")
write(path, text)


# 2) SessionHistoryStore: persist Gemini remote-file metadata and allow an uploaded video
#    to become history even before description text exists.
path = "app/src/main/java/com/oai/geminilivetranslate/core/SessionHistoryStore.kt"
text = read(path)
old = '''    val mediaUri: String?,
    val mediaName: String?,
    val primaryTranscript: String,
'''
new = '''    val mediaUri: String?,
    val mediaName: String?,
    val geminiFileName: String?,
    val geminiFileUri: String?,
    val geminiFileMimeType: String?,
    val geminiFileUploadedAtMs: Long,
    val primaryTranscript: String,
'''
text = replace_once(text, old, new, "HistorySession remote fields")
old = '''    val hasValue: Boolean
        get() = primaryTranscript.isNotBlank() ||
            primarySrt.isNotBlank() ||
'''
new = '''    val hasValue: Boolean
        get() = !geminiFileUri.isNullOrBlank() ||
            primaryTranscript.isNotBlank() ||
            primarySrt.isNotBlank() ||
'''
text = replace_once(text, old, new, "HistorySession hasValue")
old = '''        mediaUri = mediaUri,
        mediaName = mediaName,
        primaryTranscript = "",
'''
new = '''        mediaUri = mediaUri,
        mediaName = mediaName,
        geminiFileName = null,
        geminiFileUri = null,
        geminiFileMimeType = null,
        geminiFileUploadedAtMs = 0L,
        primaryTranscript = "",
'''
text = replace_once(text, old, new, "newSession remote defaults")
old = '''        .put("mediaUri", session.mediaUri ?: JSONObject.NULL)
        .put("mediaName", session.mediaName ?: JSONObject.NULL)
        .put("primaryTranscript", session.primaryTranscript)
'''
new = '''        .put("mediaUri", session.mediaUri ?: JSONObject.NULL)
        .put("mediaName", session.mediaName ?: JSONObject.NULL)
        .put("geminiFileName", session.geminiFileName ?: JSONObject.NULL)
        .put("geminiFileUri", session.geminiFileUri ?: JSONObject.NULL)
        .put("geminiFileMimeType", session.geminiFileMimeType ?: JSONObject.NULL)
        .put("geminiFileUploadedAtMs", session.geminiFileUploadedAtMs)
        .put("primaryTranscript", session.primaryTranscript)
'''
text = replace_once(text, old, new, "history JSON write remote fields")
old = '''            mediaUri = json.optNullableString("mediaUri"),
            mediaName = mediaName,
            primaryTranscript = primaryTranscript,
'''
new = '''            mediaUri = json.optNullableString("mediaUri"),
            mediaName = mediaName,
            geminiFileName = json.optNullableString("geminiFileName"),
            geminiFileUri = json.optNullableString("geminiFileUri"),
            geminiFileMimeType = json.optNullableString("geminiFileMimeType"),
            geminiFileUploadedAtMs = json.optLong("geminiFileUploadedAtMs", 0L),
            primaryTranscript = primaryTranscript,
'''
text = replace_once(text, old, new, "history JSON read remote fields")
text = replace_once(text, '        private const val FORMAT_VERSION = 1\n', '        private const val FORMAT_VERSION = 2\n', "history format version")
write(path, text)


# 3) History accessibility text: announce that the video is already uploaded/reusable.
path = "app/src/main/java/com/oai/geminilivetranslate/ui/HistoryActivity.kt"
text = read(path)
old = '''        val videoAvailable = buildString {
            if (session.videoTimelineSrt.isNotBlank()) append(", có mô tả theo thời gian")
            if (session.videoSummaryText.isNotBlank()) append(", có mô tả tổng hợp")
        }
'''
new = '''        val videoAvailable = buildString {
            if (!session.geminiFileUri.isNullOrBlank()) append(", video đã tải lên Gemini")
            if (session.videoTimelineSrt.isNotBlank()) append(", có mô tả theo thời gian")
            if (session.videoSummaryText.isNotBlank()) append(", có mô tả tổng hợp")
        }
'''
text = replace_once(text, old, new, "HistoryActivity remote state")
write(path, text)


# 4) GeminiVideoDescriptionClient: reuse an ACTIVE uploaded file instead of uploading again.
path = "app/src/main/java/com/oai/geminilivetranslate/network/GeminiVideoDescriptionClient.kt"
text = read(path)
old = '''    data class Result(
        val timelineItems: List<TimelineItem>,
'''
new = '''    data class RemoteFile(
        val name: String?,
        val uri: String,
        val mimeType: String,
        val uploadedAtMs: Long,
    )

    data class Result(
        val timelineItems: List<TimelineItem>,
'''
text = replace_once(text, old, new, "Gemini remote data class")
old = '''        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit = {},
    ): Result {
'''
new = '''        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit = {},
        remoteFile: RemoteFile? = null,
        onRemoteFileReady: (RemoteFile) -> Unit = {},
    ): Result {
'''
text = replace_once(text, old, new, "Gemini public describe signature")
old = '''            mode = mode,
            onProgress = onProgress,
            onPartial = onPartial,
        )
    }

    private fun describe(
        source: UploadSource,
        durationMs: Long,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
    ): Result {
'''
new = '''            mode = mode,
            onProgress = onProgress,
            onPartial = onPartial,
            remoteFile = remoteFile,
            onRemoteFileReady = onRemoteFileReady,
        )
    }

    private fun describe(
        source: UploadSource,
        durationMs: Long,
        mode: Mode,
        onProgress: (String, Int) -> Unit,
        onPartial: (String) -> Unit,
        remoteFile: RemoteFile?,
        onRemoteFileReady: (RemoteFile) -> Unit,
    ): Result {
'''
text = replace_once(text, old, new, "Gemini private describe signature")
old = '''        var uploadedName: String? = null
        try {
            onProgress("Đang tải nguyên video lên...", 2)
            val uploaded = upload(source, onProgress)
            uploadedName = uploaded.name
            waitUntilActive(uploaded, onProgress)

            val prompt = VideoDescriptionPromptDefaults.render(
'''
new = '''        val reusable = reusableUploadedFile(remoteFile, source.mimeType)
        val uploaded = if (reusable != null) {
            onProgress("Đang dùng lại video đã tải lên Gemini...", 55)
            logger.log(
                2,
                TAG,
                "Dùng lại video đã tải fileName=${reusable.name ?: "none"} fileUri=${reusable.uri.take(100)} mime=${reusable.mimeType}",
            )
            reusable
        } else {
            onProgress("Đang tải nguyên video lên...", 2)
            upload(source, onProgress).also { waitUntilActive(it, onProgress) }
        }
        val readyRemote = RemoteFile(
            name = uploaded.name,
            uri = uploaded.uri,
            mimeType = uploaded.mimeType,
            uploadedAtMs = if (reusable != null) {
                remoteFile?.uploadedAtMs ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            },
        )
        onRemoteFileReady(readyRemote)

            val prompt = VideoDescriptionPromptDefaults.render(
'''
text = replace_once(text, old, new, "Gemini upload/reuse selection")
old = '''            throw lastError ?: IllegalStateException("Gemini không tạo được mô tả video")
        } finally {
            uploadedName?.let(::deleteUploadedFile)
        }
    }
'''
new = '''            throw lastError ?: IllegalStateException("Gemini không tạo được mô tả video")
    }
'''
text = replace_once(text, old, new, "Gemini stop deleting remote file")
marker = '''    private fun createAndAwait(
'''
helper = '''    private fun reusableUploadedFile(
        remote: RemoteFile?,
        fallbackMimeType: String,
    ): UploadedFile? {
        if (remote == null || remote.uri.isBlank() || remote.uploadedAtMs <= 0L) return null
        val ageMs = (System.currentTimeMillis() - remote.uploadedAtMs).coerceAtLeast(0L)
        if (ageMs >= REMOTE_FILE_MAX_AGE_MS) {
            logger.log(2, TAG, "Video Gemini đã quá thời gian tái sử dụng ageMs=$ageMs; sẽ tải lại")
            return null
        }
        val name = remote.name?.takeIf(String::isNotBlank) ?: return null
        val uploaded = UploadedFile(
            name = name,
            uri = remote.uri,
            mimeType = remote.mimeType.takeIf(String::isNotBlank) ?: fallbackMimeType,
        )
        val clean = name.removePrefix("/")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$clean")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                logger.log(
                    if (response.code == 400 || response.code == 403 || response.code == 404) 2 else 1,
                    TAG,
                    "Không tái sử dụng được video Gemini HTTP=${response.code} fileName=$name; ${if (response.code == 400 || response.code == 403 || response.code == 404) "sẽ tải lại" else "không thể xác minh"}",
                )
                if (response.code == 400 || response.code == 403 || response.code == 404) {
                    return@use null
                }
                throw IllegalStateException(
                    "Không kiểm tra được video đã tải: HTTP ${response.code} ${sanitizeForLog(body, 500)}"
                )
            }
            val state = runCatching { JSONObject(body).optString("state").uppercase() }
                .getOrDefault("")
            if (state == "ACTIVE") {
                uploaded
            } else {
                logger.log(2, TAG, "Video Gemini cache state=${state.ifBlank { "UNKNOWN" }}; sẽ tải lại")
                null
            }
        }
    }

'''
if text.count(marker) != 1:
    raise RuntimeError(f"Gemini helper insertion: expected one createAndAwait marker, found {text.count(marker)}")
text = text.replace(marker, helper + marker, 1)
text = replace_once(
    text,
    '        private const val MAX_FILE_POLLS = 180\n',
    '        private const val REMOTE_FILE_MAX_AGE_MS = 48L * 60L * 60L * 1_000L\n        private const val MAX_FILE_POLLS = 180\n',
    "Gemini remote max age",
)
write(path, text)


# 5) TranslationService: pass cached Gemini file into the client and save it to history
#    immediately after Gemini confirms the upload is ACTIVE.
path = "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt"
text = read(path)
old = '''                                onPartial = partial,
                            )
                        } finally {
                            gemini.close()
'''
new = '''                                onPartial = partial,
                                remoteFile = currentHistorySession?.let { history ->
                                    history.geminiFileUri
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { remoteUri ->
                                            GeminiVideoDescriptionClient.RemoteFile(
                                                name = history.geminiFileName,
                                                uri = remoteUri,
                                                mimeType = history.geminiFileMimeType ?: resolvedMime,
                                                uploadedAtMs = history.geminiFileUploadedAtMs,
                                            )
                                        }
                                },
                                onRemoteFileReady = { remote ->
                                    val history = currentHistorySession
                                    if (
                                        history != null &&
                                        history.mediaUri == uri.toString() &&
                                        selectedUri == uri
                                    ) {
                                        currentHistorySession = history.copy(
                                            geminiFileName = remote.name,
                                            geminiFileUri = remote.uri,
                                            geminiFileMimeType = remote.mimeType,
                                            geminiFileUploadedAtMs = remote.uploadedAtMs,
                                        )
                                        saveCurrentHistoryNow("video-upload-ready")
                                    }
                                },
                            )
                        } finally {
                            gemini.close()
'''
# There are two onPartial blocks in startVideoDescription (proxy + Gemini), but this exact
# suffix with gemini.close is unique.
text = replace_once(text, old, new, "TranslationService remote-file callback")
write(path, text)


# Sanity checks: fail the workflow instead of committing a half-applied patch.
checks = {
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt": [
        "Intent.ACTION_OPEN_DOCUMENT",
        "takePersistableUriPermission(uri, takeFlags)",
    ],
    "app/src/main/java/com/oai/geminilivetranslate/core/SessionHistoryStore.kt": [
        "geminiFileUri",
        "geminiFileUploadedAtMs",
        "FORMAT_VERSION = 2",
    ],
    "app/src/main/java/com/oai/geminilivetranslate/network/GeminiVideoDescriptionClient.kt": [
        "data class RemoteFile",
        "reusableUploadedFile",
        "onRemoteFileReady(readyRemote)",
        "REMOTE_FILE_MAX_AGE_MS",
    ],
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt": [
        'saveCurrentHistoryNow("video-upload-ready")',
        "GeminiVideoDescriptionClient.RemoteFile",
    ],
}
for filename, needles in checks.items():
    body = read(filename)
    for needle in needles:
        if needle not in body:
            raise RuntimeError(f"sanity check failed: {needle!r} missing from {filename}")

print("Video history/reuse patch applied successfully")
