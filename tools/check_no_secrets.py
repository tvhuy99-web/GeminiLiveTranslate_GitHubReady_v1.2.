#!/usr/bin/env python3
"""Fail CI when source files contain likely credentials or private key material."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", ".gradle", ".idea", "build", ".cxx", "captures"}
SKIP_SUFFIXES = {".jar", ".zip", ".apk", ".aab", ".png", ".jpg", ".jpeg", ".webp", ".ico"}
FORBIDDEN_FILES = {"keystore.properties", "key.properties", "google-services.json"}
PATTERNS = {
    "Gemini/Google API key": re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    "PEM private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{30,}\b"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
}


def is_skipped(path: Path) -> bool:
    return any(part in SKIP_DIRS for part in path.parts) or path.suffix.lower() in SKIP_SUFFIXES


def main() -> None:
    findings: list[str] = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or is_skipped(path):
            continue
        if path.name in FORBIDDEN_FILES or path.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx"}:
            findings.append(f"forbidden credential file: {path.relative_to(ROOT)}")
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for label, pattern in PATTERNS.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(f"{label}: {path.relative_to(ROOT)}:{line}")
    if findings:
        print("[FAIL] Potential secrets detected:", file=sys.stderr)
        for item in findings:
            print(f"  - {item}", file=sys.stderr)
        raise SystemExit(1)
    print("[OK] No likely credentials or private keys found")


if __name__ == "__main__":
    main()
