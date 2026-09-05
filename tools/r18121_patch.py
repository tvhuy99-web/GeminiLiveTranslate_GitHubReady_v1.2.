from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
video = ROOT / 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt'
trans = ROOT / 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt'

def once(path, old, new, label):
    s = path.read_text(encoding='utf-8')
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    path.write_text(s.replace(old, new, 1), encoding='utf-8')

once(
    video,
    'name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2',
    'name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2',
    'video-r22-log-level',
)
once(
    trans,
    'val level = if (name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3',
    'val level = if (name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3',
    'transcribe-r22-log-level',
)
print('R18.12.1 logging patch applied')
