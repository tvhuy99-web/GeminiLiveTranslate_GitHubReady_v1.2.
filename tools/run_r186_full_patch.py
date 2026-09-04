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
