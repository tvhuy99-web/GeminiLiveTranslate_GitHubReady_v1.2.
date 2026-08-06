#!/usr/bin/env python3
"""Validate the repository layer required for reproducible GitHub builds."""
from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = "44afcdcadc571c1a83763fc68e95ffaea07429f9ea0c473978e6052d1b7ec174"
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
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/bootstrap-src/org/gradle/wrapper/GradleWrapperMain.java",
]


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def verify_wrapper() -> None:
    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != WRAPPER_SHA256:
        fail(f"Unexpected wrapper bootstrap SHA-256: {digest}")
    try:
        with zipfile.ZipFile(jar) as archive:
            bad = archive.testzip()
            if bad:
                fail(f"Corrupt wrapper JAR member: {bad}")
            if "org/gradle/wrapper/GradleWrapperMain.class" not in archive.namelist():
                fail("Wrapper JAR has no GradleWrapperMain class")
    except zipfile.BadZipFile as error:
        fail(f"Invalid wrapper JAR: {error}")
    properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    for token in [
        "gradle-8.10.2-bin.zip",
        "distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26",
    ]:
        if token not in properties:
            fail(f"Wrapper properties are missing token: {token}")


def main() -> None:
    for item in REQUIRED:
        if not (ROOT / item).is_file():
            fail(f"Missing GitHub-ready file: {item}")
    for obsolete in [
        ROOT / "source-archive",
        ROOT / ".github/workflows/one-time-official-wrapper.yml",
        ROOT / "docs/.wrapper-migration-trigger",
    ]:
        if obsolete.exists():
            fail(f"Obsolete migration/bootstrap path is present: {obsolete.relative_to(ROOT)}")

    verify_wrapper()
    ci = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
    release = (ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")
    dependency = (ROOT / ".github/workflows/dependency-submission.yml").read_text(encoding="utf-8")
    required_ci = [
        "tools/check_no_secrets.py",
        "tools/verify_project.py",
        "./gradlew --version",
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
        "./gradlew --version",
        "assembleRelease",
        "bundleRelease",
        "apksigner",
        "gh release create",
    ]
    for token in required_release:
        if token not in release:
            fail(f"Release workflow is missing token: {token}")
    if "./gradlew --no-daemon :app:dependencies" not in dependency:
        fail("Dependency submission does not use the verified project wrapper")

    build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    for token in ["RELEASE_STORE_FILE", "signingConfigs", "enableV3Signing"]:
        if token not in build:
            fail(f"Release signing configuration is missing token: {token}")

    print(f"[OK] Verified wrapper bootstrap SHA-256: {WRAPPER_SHA256}")
    print("[OK] Gradle 8.10.2 distribution is pinned with SHA-256")
    print("[OK] Direct Kotlin/Gradle source is present")
    print("[OK] CI builds, tests, lints and verifies a debug APK")
    print("[OK] Signed APK/AAB release workflow is configured")
    print("[OK] Secret scanning, dependency automation and support templates are present")
    print("GITHUB_READY_OK")


if __name__ == "__main__":
    main()
