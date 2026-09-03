#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app" / "src" / "main" / "java"

PATTERN = re.compile(
    r"val\s+([A-Za-z0-9_]*DOCUMENT_START[A-Za-z0-9_]*)\s*:\s*String\s*=\s*\"\"\"(.*?)\"\"\"\.trimIndent\(\)",
    re.DOTALL,
)


def main() -> int:
    node = shutil.which("node")
    if not node:
        print("[FAIL] node is required to syntax-check embedded JavaScript", file=sys.stderr)
        return 2

    checked = 0
    failures: list[str] = []

    for path in sorted(JAVA_ROOT.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for match in PATTERN.finditer(text):
            name = match.group(1)
            script = textwrap.dedent(match.group(2)).strip() + "\n"
            checked += 1
            with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8", delete=False) as tmp:
                tmp.write(script)
                tmp_path = pathlib.Path(tmp.name)
            try:
                proc = subprocess.run(
                    [node, "--check", str(tmp_path)],
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    check=False,
                )
                if proc.returncode != 0:
                    rel = path.relative_to(ROOT)
                    failures.append(f"{rel}:{name}\n{proc.stdout.strip()}")
            finally:
                tmp_path.unlink(missing_ok=True)

    if checked == 0:
        print("[FAIL] no DOCUMENT_START JavaScript blocks found", file=sys.stderr)
        return 3

    if failures:
        print(f"[FAIL] embedded JavaScript syntax errors: {len(failures)}", file=sys.stderr)
        for failure in failures:
            print("\n" + failure, file=sys.stderr)
        return 1

    print(f"[OK] Embedded JavaScript syntax: {checked} DOCUMENT_START blocks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
