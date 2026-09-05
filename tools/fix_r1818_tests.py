from pathlib import Path

new_test = Path('app/src/test/java/com/oai/geminilivetranslate/network/AiStudioDirectSttPageSourceTest.kt')
s = new_test.read_text()
s = s.replace(
    'import kotlin.test.Test\nimport kotlin.test.assertFalse\nimport kotlin.test.assertTrue\n',
    'import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n',
)
s = s.replace(
    '''    private val root = File(System.getProperty("user.dir")).parentFile ?: File(".")\n    private fun source(path: String) = File(root, "src/main/java/com/oai/geminilivetranslate/$path").readText()\n''',
    '''    private fun source(path: String): String = sequenceOf(\n        File("src/main/java/com/oai/geminilivetranslate/$path"),\n        File("app/src/main/java/com/oai/geminilivetranslate/$path"),\n    ).firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")\n''',
)
new_test.write_text(s)

legacy = Path('app/src/test/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeNoPromptSourceTest.kt')
s = legacy.read_text()
s = s.replace('assertTrue(src.contains("generateAttachmentFileOnlyNative"))', 'assertTrue(src.contains("generateSttFileNative"))')
s = s.replace(
    'assertTrue(src.contains("R24_FILE_TRANSCRIBE_ATTACH_RETRY"))',
    'assertTrue(src.contains("startFileTranscribe(model)"))\n        assertTrue(src.contains("attachSttFile"))\n        assertTrue(src.contains("transport=aistudio-stt-direct-page"))',
)
legacy.write_text(s)

r12 = Path('app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12R1SourceTest.kt')
s = r12.read_text().replace(
    '2026-09-05-web-session-r12.5-file-only-transcribe-video-probe',
    '2026-09-05-web-session-r12.6-direct-stt-page',
)
r12.write_text(s)

submit = Path('app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt')
s = submit.read_text().replace(
    '2026-09-05-web-session-r12.5-file-only-transcribe-video-probe',
    '2026-09-05-web-session-r12.6-direct-stt-page',
)
submit.write_text(s)

assert 'kotlin.test' not in new_test.read_text()
assert 'sequenceOf(' in new_test.read_text()
assert 'generateSttFileNative' in legacy.read_text()
assert 'startFileTranscribe(model)' in legacy.read_text()
assert '2026-09-05-web-session-r12.6-direct-stt-page' in r12.read_text()
assert '2026-09-05-web-session-r12.6-direct-stt-page' in submit.read_text()
print('R18.18 test fixes v3 OK')
