from pathlib import Path

def patch(path, old, new, label):
    p = Path(path)
    s = p.read_text()
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    p.write_text(s.replace(old, new, 1))

patch(
    'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt',
    'assertTrue(requestFix.contains("attachmentPrepared=present&&!busy&&localReadReady&&submitReady"))',
    'assertTrue(requestFix.contains("attachmentPrepared=present&&!busy&&submitReady&&(localReadReady||blobReadReady||!!dom.readyAfterBusy||serverPayloadSettled)"))',
    'deep readiness assertion',
)
patch(
    'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12R1SourceTest.kt',
    '2026-09-05-web-session-r12.4-manual-video-native-file',
    '2026-09-05-web-session-r12.5-file-only-transcribe-video-probe',
    'executor version assertion',
)
print('R18.11 test follow-up applied')
