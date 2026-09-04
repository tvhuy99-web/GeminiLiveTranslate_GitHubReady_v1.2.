from pathlib import Path

workflow = Path('.github/workflows/r186-ai-studio-all-modes.yml').read_text()
start_marker = "          python3 - <<'PY'\n"
end_marker = "\n          PY\n"
start = workflow.find(start_marker)
if start < 0:
    raise SystemExit('R18.6 patch start marker not found')
start += len(start_marker)
end = workflow.find(end_marker, start)
if end < 0:
    raise SystemExit('R18.6 patch end marker not found')

raw = workflow[start:end]
out = []
in_triple = False
for line in raw.splitlines():
    cooked = line if in_triple else (line[10:] if line.startswith('          ') else line)
    out.append(cooked)
    if cooked.count("'''") % 2 == 1:
        in_triple = not in_triple
if in_triple:
    raise SystemExit('R18.6 extractor ended inside triple-quoted Python string')

script = '\n'.join(out) + '\n'
# The executor timeout was reduced after the original R18.6 patch was authored.
script = script.replace(
    'private const val DEFAULT_TIMEOUT_MS = 120_000L',
    'private const val DEFAULT_TIMEOUT_MS = 20_000L',
)
print(f'Executing R18.6 full source patch: {len(script)} chars')
exec(compile(script, '<r18.6-full-patch>', 'exec'), {'__name__': '__main__'})

# The repository test suite uses JUnit4, not kotlin-test. Normalize the generated
# source-routing regression test to the same imports used by the existing tests.
test_path = Path('app/src/test/java/com/oai/geminilivetranslate/service/AiStudioAllModesRoutingSourceTest.kt')
if test_path.is_file():
    test_source = test_path.read_text()
    test_source = test_source.replace('import kotlin.test.Test', 'import org.junit.Test')
    test_source = test_source.replace('import kotlin.test.assertFalse', 'import org.junit.Assert.assertFalse')
    test_source = test_source.replace('import kotlin.test.assertTrue', 'import org.junit.Assert.assertTrue')
    test_path.write_text(test_source)
    if 'import kotlin.test.' in test_source:
        raise SystemExit('R18.6 generated test still contains kotlin.test imports')
    print('R18.6 generated routing test normalized to JUnit4')
