#!/usr/bin/env python3
"""Validate the repository layer required for reproducible GitHub builds."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    ".github/workflows/android-ci.yml",
    ".github/workflows/android-release.yml",
    ".github/workflows/dependency-submission.yml",
    ".github/dependabot.yml",
    ".github/ISSUE_TEMPLATE/bug_report.yml",
    ".github/PULL_REQUEST_TEMPLATE.md",
    "SECURITY.md",
    "PRIVACY.md",
    "LICENSE",
    "docs/COMPLETENESS_MATRIX.md",
    "docs/GITHUB_RELEASE.md",
    "tools/check_no_secrets.py",
    "tools/verify_apk.py",
]


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    for item in REQUIRED:
        if not (ROOT / item).is_file():
            fail(f"Missing GitHub-ready file: {item}")
    if (ROOT / "source-archive").exists():
        fail("Obsolete split source-archive directory is present")
    ci = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
    release = (ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")
    required_ci = [
        "tools/check_no_secrets.py",
        "tools/verify_project.py",
        "testDebugUnitTest",
        "lintDebug",
        "assembleDebug",
        "tools/verify_apk.py",
        "actions/upload-artifact@v7",
    ]
    for token in required_ci:
        if token not in ci:
            fail(f"Android CI is missing token: {token}")
    required_release = [
        "ANDROID_KEYSTORE_BASE64",
        "assembleRelease",
        "bundleRelease",
        "apksigner",
        "gh release create",
    ]
    for token in required_release:
        if token not in release:
            fail(f"Release workflow is missing token: {token}")
    build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    for token in ["RELEASE_STORE_FILE", "signingConfigs", "enableV3Signing"]:
        if token not in build:
            fail(f"Release signing configuration is missing token: {token}")
    print("[OK] Direct Kotlin/Gradle source is present")
    print("[OK] CI builds, tests, lints and verifies a debug APK")
    print("[OK] Signed APK/AAB release workflow is configured")
    print("[OK] Secret scanning, dependency automation and support templates are present")
    print("GITHUB_READY_OK")


if __name__ == "__main__":
    main()
