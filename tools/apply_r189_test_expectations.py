#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch(path, replacements):
    p = ROOT / path
    s = p.read_text(encoding='utf-8')
    for old, new in replacements:
        if old not in s:
            raise SystemExit(f'MISSING test expectation in {path}: {old}')
        s = s.replace(old, new)
    p.write_text(s, encoding='utf-8')

patch(
    'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt',
    [
        ('fun executorUsesDirectEngineThenNativeComposerTapBeforeLegacyFallback()', 'fun executorSupportsManualVideoObservationAndStrictNativeFileSubmit()'),
        ('2026-09-04-web-session-r12.3-upload-ready-native-submit', '2026-09-05-web-session-r12.4-manual-video-native-file'),
        ('assertTrue(requestFix.contains("uploadSettled"))', 'assertTrue(requestFix.contains("uploadObserved=uploadStarted>0"))\n        assertTrue(requestFix.contains("uploadSettled=uploadObserved"))'),
        ('assertTrue(submitFix.contains("submissionReadinessIfAttachment"))', 'assertTrue(submitFix.contains("submissionReadinessIfAttachment"))\n        assertTrue(submitFix.contains("preparePromptIfAttachment"))\n        assertTrue(src.contains("awaitManualAttachmentGenerate"))\n        assertTrue(src.contains("R19_MANUAL_VIDEO_READINESS"))\n        assertTrue(src.contains("generateAttachmentNativeOnly"))\n        assertTrue(src.contains("allowProgrammaticFallback = false"))'),
    ],
)

patch(
    'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12R1SourceTest.kt',
    [
        ('2026-09-04-web-session-r12.3-upload-ready-native-submit', '2026-09-05-web-session-r12.4-manual-video-native-file'),
        ('assertTrue(executor.contains("ATTACHMENT_READY_STABLE_SCANS = 3"))', 'assertTrue(executor.contains("ATTACHMENT_READY_STABLE_SCANS = 3"))\n        assertTrue(executor.contains("MANUAL_READINESS_POLL_MS = 1_000L"))\n        assertTrue(executor.contains("R19_MANUAL_VIDEO_ARMED"))'),
    ],
)

patch(
    'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18SourceTest.kt',
    [
        ('r17.8-progress-aware-start-ack', 'r17.9-fast-progress-recovery'),
        ('progress.progress?30000:10000', 'progress.progress?8000:10000'),
        ('assertTrue(catalog.contains("videoDescription="))', 'assertTrue(catalog.contains("videoDescription="))\n        val service = source("service/TranslationService.kt")\n        val fileClient = source("network/AiStudioFileTranscribeClient.kt")\n        assertTrue(service.contains("val useLiveFileTranscribe = false"))\n        assertTrue(service.contains("FILE_TRANSCRIBE backend=aistudio-file"))\n        assertTrue(fileClient.contains("AppPreferences.TRANSCRIBE_FILE_MODEL"))\n        assertTrue(fileClient.contains("live=false"))'),
    ],
)

print('R18.9 test expectations patched')
