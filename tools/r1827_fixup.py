#!/usr/bin/env python3
from pathlib import Path

helper = Path("tools/r1827_cleanup.py")
s = helper.read_text(encoding="utf-8")

old = '''    s = s.replace("            out.rawMarkerFound=rawMarkerFound;\\n            out.markerFound=markerFound;\\n", "")
'''
new = old + '''    s = s.replace("            const fp=[normalized.at,normalized.responseChars,normalized.phase,normalized.markerFound,normalized.modelTextChars].join('|');\\n", "            const fp=[normalized.at,normalized.responseChars,normalized.phase,normalized.modelTextChars].join('|');\\n")
'''
if old not in s:
    raise SystemExit("response marker patch anchor missing")
s = s.replace(old, new, 1)

roots_old = '''    roots = [path("app/src/main/java"), path("app/src/test/java")]
'''
roots_new = '''    roots = [path("app/src/main/java")]
'''
if roots_old not in s:
    raise SystemExit("dead-reference roots anchor missing")
s = s.replace(roots_old, roots_new, 1)

comments_old = '''    for p in changed_kotlin_files():
        p.write_text(strip_kotlin_comments(p.read_text(encoding="utf-8")), encoding="utf-8")
'''
comments_new = '''    for p in changed_kotlin_files():
        cleaned = strip_kotlin_comments(p.read_text(encoding="utf-8"))
        cleaned = "\\n".join(line.rstrip() for line in cleaned.splitlines()) + ("\\n" if cleaned.endswith("\\n") else "")
        p.write_text(cleaned, encoding="utf-8")
'''
if comments_old not in s:
    raise SystemExit("comment cleanup whitespace anchor missing")
s = s.replace(comments_old, comments_new, 1)
helper.write_text(s, encoding="utf-8")

request = Path("app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")
r = request.read_text(encoding="utf-8")
for line in (
    "            fix.attachmentSubmitFallbacks = 0;\n",
    "            fix.attachmentSubmitButtonClicks = 0;\n",
    "            fix.attachmentSubmitListenerInvokes = 0;\n",
    "            fix.attachmentLastSubmitLabel = '';\n",
    "            fix.attachmentLastSubmitPath = '';\n",
):
    r = r.replace(line, "")
request.write_text(r, encoding="utf-8")

print("R18.27 fixups applied")
