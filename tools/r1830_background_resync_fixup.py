from pathlib import Path

path = Path('app/src/test/java/com/oai/geminilivetranslate/network/AiStudioBackgroundTimingSourceTest.kt')
text = path.read_text(encoding='utf-8')
lines = text.splitlines()
counts = {'timeout': 0, 'tag': 0, 'role': 0}
for index, line in enumerate(lines):
    stripped = line.strip()
    if stripped.startswith('assertTrue(executor.contains(') and 'resyncPendingRequest(requestSeq,' in stripped:
        lines[index] = '        assertTrue(executor.contains("""resyncPendingRequest(requestSeq, "timeout-probe")"""))'
        counts['timeout'] += 1
    elif stripped.startswith('assertTrue(executor.contains(') and 'STT_RUN' in stripped:
        lines[index] = '        assertTrue(executor.contains("""put("tag","STT_RUN")"""))'
        counts['tag'] += 1
    elif stripped.startswith('assertTrue(executor.contains(') and 'stt-run' in stripped:
        lines[index] = '        assertTrue(executor.contains("""put("role","stt-run")"""))'
        counts['role'] += 1
if counts != {'timeout': 1, 'tag': 1, 'role': 1}:
    raise SystemExit(f'unexpected replacement counts: {counts}')
path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('R18.30 test quoting fixed')
