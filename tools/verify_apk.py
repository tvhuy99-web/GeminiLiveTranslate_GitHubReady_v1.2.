#!/usr/bin/env python3
"""Small APK integrity check usable in CI without adb or a device."""
from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_apk.py path/to/app.apk")
    apk = Path(sys.argv[1])
    if not apk.is_file():
        raise SystemExit(f"[FAIL] APK not found: {apk}")
    if apk.stat().st_size < 100_000:
        raise SystemExit(f"[FAIL] APK is unexpectedly small: {apk.stat().st_size} bytes")
    try:
        with zipfile.ZipFile(apk) as archive:
            bad = archive.testzip()
            if bad:
                raise SystemExit(f"[FAIL] Corrupt APK member: {bad}")
            names = set(archive.namelist())
    except zipfile.BadZipFile as error:
        raise SystemExit(f"[FAIL] Invalid APK/ZIP: {error}") from error
    required = {"AndroidManifest.xml", "resources.arsc"}
    missing = sorted(required - names)
    if missing:
        raise SystemExit("[FAIL] APK missing: " + ", ".join(missing))
    if not any(name.startswith("classes") and name.endswith(".dex") for name in names):
        raise SystemExit("[FAIL] APK has no classes*.dex")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    print(f"[OK] APK integrity: {apk.name}")
    print(f"[OK] APK size: {apk.stat().st_size} bytes")
    print(f"[OK] APK SHA-256: {digest}")


if __name__ == "__main__":
    main()
