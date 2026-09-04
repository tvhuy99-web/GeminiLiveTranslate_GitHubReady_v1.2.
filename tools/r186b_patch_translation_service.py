from pathlib import Path

p = Path('app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt')
s = p.read_text()


def once(old: str, new: str, label: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 occurrence, got {count}')
    s = s.replace(old, new, 1)


once(
    'import com.oai.geminilivetranslate.core.AiApiSettingsStore\nimport com.oai.geminilivetranslate.core.ApiKeyStore',
    'import com.oai.geminilivetranslate.core.AiApiSettingsStore\nimport com.oai.geminilivetranslate.core.AiStudioLiveBackendPolicy\nimport com.oai.geminilivetranslate.core.ApiKeyStore',
    'AiStudioLiveBackendPolicy import',
)

once(
    '''        val aiApi = AiApiSettingsStore(this).load()
        val useProxyVideoDescription =
            isVideoDescriptionMode() && aiApi.provider == AiApiSettingsStore.PROVIDER_OPENAI
        val apiKey = if (useProxyVideoDescription) null else keyStore.currentGeminiKey()
''',
    '''        val aiApi = AiApiSettingsStore(this).load()
        val useProxyVideoDescription =
            isVideoDescriptionMode() && aiApi.provider == AiApiSettingsStore.PROVIDER_OPENAI
        val aiStudioMode = AiStudioLiveBackendPolicy.preferAiStudio(this)
        val apiKey = if (useProxyVideoDescription || aiStudioMode) null else keyStore.currentGeminiKey()
''',
    'start route selection',
)

once(
    '''        } else if (apiKey.isNullOrBlank()) {
            updateError("Chưa có Gemini API Key")
            return
        }
        val geminiApiKey = apiKey.orEmpty()
''',
    '''        } else if (!aiStudioMode && apiKey.isNullOrBlank()) {
            updateError("Chưa có Gemini API Key")
            return
        }
        val geminiApiKey = if (aiStudioMode) {
            AiStudioLiveBackendPolicy.liveCredential(apiKey).orEmpty()
        } else {
            apiKey.orEmpty()
        }
''',
    'start key gate',
)

once(
    '''        val selectedKey = keyStore.currentGeminiKey()
        if (selectedKey.isNullOrBlank()) {
            stopTranslation("API Key đã bị xóa; phiên dịch đã dừng")
            return
        }
        logger.log(1, "ApiKey", "API Key đang dùng đã thay đổi; tạo kết nối Gemini mới")
        liveApiKey = selectedKey
        liveKeyFailoverAttempts = 0
        sessionResumptionHandle = null
        source?.pause()
        clearPendingInputForFreshSession()
        updateState { it.copy(setupComplete = false, status = "Đang áp dụng API Key mới...") }
        connectGemini(selectedKey)
''',
    '''        val aiStudioMode = AiStudioLiveBackendPolicy.preferAiStudio(this)
        val selectedKey = if (aiStudioMode) null else keyStore.currentGeminiKey()
        if (!aiStudioMode && selectedKey.isNullOrBlank()) {
            stopTranslation("API Key đã bị xóa; phiên dịch đã dừng")
            return
        }
        val credential = if (aiStudioMode) {
            AiStudioLiveBackendPolicy.liveCredential(selectedKey).orEmpty()
        } else {
            selectedKey.orEmpty()
        }
        logger.log(
            1,
            "ApiKey",
            if (aiStudioMode) "Đang dùng AI Studio; bỏ qua yêu cầu API Key và tạo lại kết nối AI Studio"
            else "API Key đang dùng đã thay đổi; tạo kết nối Gemini mới",
        )
        liveApiKey = credential
        liveKeyFailoverAttempts = 0
        sessionResumptionHandle = null
        source?.pause()
        clearPendingInputForFreshSession()
        updateState {
            it.copy(
                setupComplete = false,
                status = if (aiStudioMode) "Đang kết nối lại AI Studio..." else "Đang áp dụng API Key mới...",
            )
        }
        connectGemini(credential)
''',
    'refresh key routing',
)

p.write_text(s)

out = p.read_text()
required = [
    'val aiStudioMode = AiStudioLiveBackendPolicy.preferAiStudio(this)',
    '} else if (!aiStudioMode && apiKey.isNullOrBlank()) {',
    'AiStudioLiveBackendPolicy.liveCredential(apiKey).orEmpty()',
    'if (!aiStudioMode && selectedKey.isNullOrBlank()) {',
    'Đang dùng AI Studio; bỏ qua yêu cầu API Key',
]
for marker in required:
    if marker not in out:
        raise SystemExit(f'missing verified marker: {marker}')

print('R18.6b TranslationService patch applied and verified')
