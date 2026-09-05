from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text()
    n = s.count(old)
    if n != count:
        raise SystemExit(f"{path}: expected {count} matches, got {n} for {old!r}")
    p.write_text(s.replace(old, new, count))

p = 'app/src/test/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeNoPromptSourceTest.kt'
replace(p,
'''        assertTrue(src.contains("awaitManualAttachmentFileOnlyGenerate"))
        assertTrue(src.contains("prompt=false"))
''',
'''        assertTrue(src.contains("generateAttachmentFileOnlyNative"))
        assertTrue(src.contains("manualRun=false autoSubmit=true"))
        assertTrue(src.contains("R24_FILE_TRANSCRIBE_ATTACH_RETRY"))
        assertTrue(src.contains("prompt=false"))
        assertFalse(src.contains("awaitManualAttachmentFileOnlyGenerate"))
        assertFalse(src.contains("manualRun=true"))
''')

p = 'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt'
replace(p,
'''        assertTrue(submitFix.contains("2026-09-05-web-session-r11.9-cached-hit-test-submit"))
        assertTrue(submitFix.contains("R23_PREPARED_SUBMIT_TARGET"))
''',
'''        assertTrue(submitFix.contains("2026-09-05-web-session-r11.10-file-only-hit-test"))
        assertTrue(submitFix.contains("R24_FILE_ONLY_NATIVE_HIT_TEST"))
        assertTrue(submitFix.contains("R23_PREPARED_SUBMIT_TARGET"))
''')
replace(p,
'''        assertTrue(src.contains("generateAttachmentFileOnlyNative"))
        assertTrue(src.contains("file-transcribe-run"))
        assertTrue(src.contains("allowProgrammaticFallback = false"))
''',
'''        assertTrue(src.contains("generateAttachmentFileOnlyNative"))
        assertTrue(src.contains("R24_FILE_TRANSCRIBE_AUTO_SUBMIT_POLICY"))
        assertTrue(src.contains("file-transcribe-run"))
        assertTrue(src.contains("allowProgrammaticFallback = false"))
''')

print('R18.14 tests updated')
