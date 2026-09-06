from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


service = Path("app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")
main = Path("app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt")
executor = Path("app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")
executor_test = Path("app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionExecutorSourceTest.kt")
background_test = Path("app/src/test/java/com/oai/geminilivetranslate/service/TranslationServiceBackgroundSourceTest.kt")

replace_once(
    service,
    "import androidx.lifecycle.LifecycleService\n",
    "import androidx.core.content.ContextCompat\nimport androidx.lifecycle.LifecycleService\n",
)

replace_once(
    service,
    '''    fun setSelectedFile(uri: Uri, name: String?) {\n        if (_state.value.running && currentMode == SourceMode.FILE) {\n            stopTranslation("Đã dừng do đổi tệp")\n        } else {\n            saveCurrentHistoryNow("before-file-change")\n        }\n        selectedUri = uri\n        selectedFileName = name ?: uri.lastPathSegment\n        _state.update { it.copy(selectedUri = uri, selectedFileName = selectedFileName) }\n        beginHistorySession(SourceMode.FILE, "file-selected")\n    }\n''',
    '''    fun setSelectedFile(uri: Uri, name: String?) {\n        val resolvedName = name ?: uri.lastPathSegment\n        if (_state.value.running && currentMode == SourceMode.FILE && selectedUri == uri) {\n            selectedFileName = resolvedName ?: selectedFileName\n            _state.update { it.copy(selectedUri = uri, selectedFileName = selectedFileName) }\n            logger.log(\n                2,\n                "Service",\n                "R37_SELECTED_FILE_REAPPLY_IGNORED running=true name=${selectedFileName ?: "unknown"}",\n            )\n            return\n        }\n        if (_state.value.running && currentMode == SourceMode.FILE) {\n            stopTranslation("Đã dừng do đổi tệp")\n        } else {\n            saveCurrentHistoryNow("before-file-change")\n        }\n        selectedUri = uri\n        selectedFileName = resolvedName\n        _state.update { it.copy(selectedUri = uri, selectedFileName = selectedFileName) }\n        beginHistorySession(SourceMode.FILE, "file-selected")\n    }\n''',
)

replace_once(
    service,
    '''        _state.value = initialState\n        notificationController.start(this, initialState)\n''',
    '''        _state.value = initialState\n        ensureStartedForActiveSession()\n        notificationController.start(this, initialState)\n''',
)

replace_once(
    service,
    '''        when (intent?.action) {\n            ACTION_PAUSE -> pause()\n''',
    '''        when (intent?.action) {\n            ACTION_SESSION_KEEP_ALIVE -> logger.log(\n                3,\n                "Service",\n                "R37_BACKGROUND_SERVICE_STARTED running=${_state.value.running} source=$currentMode processing=$processingMode",\n            )\n            ACTION_PAUSE -> pause()\n''',
)

replace_once(
    service,
    '''    private fun acquireWakeLock() {\n''',
    '''    private fun ensureStartedForActiveSession() {\n        ContextCompat.startForegroundService(\n            this,\n            Intent(this, TranslationService::class.java).setAction(ACTION_SESSION_KEEP_ALIVE),\n        )\n        logger.log(\n            2,\n            "Service",\n            "R37_BACKGROUND_SESSION_OWNED source=$currentMode processing=$processingMode",\n        )\n    }\n\n    private fun acquireWakeLock() {\n''',
)

replace_once(
    service,
    '''        const val ACTION_PAUSE = "com.oai.geminilivetranslate.PAUSE"\n''',
    '''        const val ACTION_SESSION_KEEP_ALIVE = "com.oai.geminilivetranslate.SESSION_KEEP_ALIVE"\n        const val ACTION_PAUSE = "com.oai.geminilivetranslate.PAUSE"\n''',
)

replace_once(
    main,
    '''            restorePersistedSelectedFile("service-connected", applyToService = true)\n''',
    '''            val activeSession = translationService?.state?.value?.running == true\n            restorePersistedSelectedFile("service-connected", applyToService = !activeSession)\n            if (activeSession) {\n                logger.log(\n                    2,\n                    "Service",\n                    "R37_SERVICE_REBIND_ACTIVE preserved=true transcriptChars=${translationService?.state?.value?.transcript?.length ?: 0}",\n                )\n            }\n''',
)

replace_once(
    executor,
    '''import android.os.Handler\n''',
    '''import android.os.Build\nimport android.os.Handler\n''',
)

replace_once(
    executor,
    '''        webView.settings.apply {\n''',
    '''        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)\n        }\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {\n            webView.settings.offscreenPreRaster = true\n        }\n        events?.onLog(\n            "R37_WEBVIEW_BACKGROUND_POLICY",\n            "rendererPriority=important waiveWhenNotVisible=false offscreenPreRaster=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.M}",\n        )\n        webView.settings.apply {\n''',
)

replace_once(
    executor,
    '''        const val VERSION = "2026-09-06-web-session-r12.9-json-completion-guard"\n''',
    '''        const val VERSION = "2026-09-06-web-session-r12.10-background-service"\n''',
)

replace_once(
    executor_test,
    '''        assertTrue(executor.contains("2026-09-06-web-session-r12.9-json-completion-guard"))\n''',
    '''        assertTrue(executor.contains("2026-09-06-web-session-r12.10-background-service"))\n        assertTrue(executor.contains("RENDERER_PRIORITY_IMPORTANT"))\n        assertTrue(executor.contains("R37_WEBVIEW_BACKGROUND_POLICY"))\n''',
)

background_test.parent.mkdir(parents=True, exist_ok=True)
background_test.write_text('''package com.oai.geminilivetranslate.service\n\nimport java.io.File\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass TranslationServiceBackgroundSourceTest {\n    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))\n        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")\n\n    @Test\n    fun everyStartedSessionOwnsForegroundServiceLifecycle() {\n        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")\n        assertTrue(service.contains("ensureStartedForActiveSession()"))\n        assertTrue(service.contains("ACTION_SESSION_KEEP_ALIVE"))\n        assertTrue(service.contains("ContextCompat.startForegroundService"))\n        assertTrue(service.contains("R37_BACKGROUND_SESSION_OWNED"))\n    }\n\n    @Test\n    fun rebindingSameFileCannotStopRunningFileSession() {\n        val service = source("src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt")\n        val main = source("src/main/java/com/oai/geminilivetranslate/MainActivity.kt")\n        assertTrue(service.contains("selectedUri == uri"))\n        assertTrue(service.contains("R37_SELECTED_FILE_REAPPLY_IGNORED"))\n        assertTrue(main.contains("applyToService = !activeSession"))\n        assertTrue(main.contains("R37_SERVICE_REBIND_ACTIVE"))\n    }\n}\n''', encoding="utf-8")

print("R18.29 background service patch applied")
