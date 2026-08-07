#!/usr/bin/env python3
"""Validate the repository layer required for reproducible GitHub builds."""
from __future__ import annotations

import base64
import hashlib
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = "44afcdcadc571c1a83763fc68e95ffaea07429f9ea0c473978e6052d1b7ec174"
STABLE_DEBUG_CERT_SHA256 = "85e27156c4557de4f35b6ebe771dd426f108182894219ff3b6dbed607a230c95"
STABLE_DEBUG_KEYSTORE_SHA256 = "cb0ac0e1e81d5e8a79a766a10737319fd14087b26574ec64493c62659cbf14fc"
REQUIRED = [
    ".github/workflows/android-ci.yml",
    ".github/workflows/android-release.yml",
    ".github/workflows/dependency-submission.yml",
    ".github/dependabot.yml",
    ".github/ISSUE_TEMPLATE/bug_report.yml",
    ".github/PULL_REQUEST_TEMPLATE.md",
    ".github/signing/README.md",
    ".github/signing/stable-debug.keystore.b64",
    "SECURITY.md",
    "PRIVACY.md",
    "LICENSE",
    "docs/COMPLETENESS_MATRIX.md",
    "docs/GITHUB_RELEASE.md",
    "docs/UPDATE_SIGNING.md",
    "tools/check_no_secrets.py",
    "tools/verify_apk.py",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/bootstrap-src/org/gradle/wrapper/GradleWrapperMain.java",
    "app/src/test/java/com/oai/geminilivetranslate/network/GeminiLiveClientSetupTest.kt",
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


def verify_stable_debug_key() -> None:
    encoded_path = ROOT / ".github/signing/stable-debug.keystore.b64"
    try:
        decoded = base64.b64decode(encoded_path.read_text(encoding="utf-8"), validate=True)
    except (ValueError, OSError) as error:
        fail(f"Stable debug keystore is not valid base64: {error}")
    digest = hashlib.sha256(decoded).hexdigest()
    if digest != STABLE_DEBUG_KEYSTORE_SHA256:
        fail(f"Unexpected stable debug keystore SHA-256: {digest}")


def main() -> None:
    for item in REQUIRED:
        if not (ROOT / item).is_file():
            fail(f"Missing GitHub-ready file: {item}")
    for obsolete in [
        ROOT / "source-archive",
        ROOT / ".github/workflows/one-time-official-wrapper.yml",
        ROOT / "docs/.wrapper-migration-trigger",
        ROOT / ".ci-patch",
    ]:
        if obsolete.exists():
            fail(f"Obsolete migration/bootstrap path is present: {obsolete.relative_to(ROOT)}")

    verify_wrapper()
    verify_stable_debug_key()
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
        ".github/signing/stable-debug.keystore.b64",
        STABLE_DEBUG_CERT_SHA256,
        "com.oai.geminilivetranslate.debug",
        "versionCode='10202'",
        "gh release create",
        "gh release upload",
        "gh release delete-asset",
        "debug-latest",
        "GeminiLiveTranslate-debug-latest.apk",
    ]
    for token in required_ci:
        if token not in ci:
            fail(f"Android CI is missing token: {token}")
    if "actions/upload-artifact" in ci:
        fail("Android CI still depends on Actions artifact storage")

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
    for token in [
        "RELEASE_STORE_FILE",
        "UPDATE_STORE_FILE",
        "signingConfigs",
        "enableV3Signing",
        'applicationIdSuffix = ".debug"',
        "versionCode = 10202",
        'versionName = "1.2.2"',
        ".github/signing/stable-debug.keystore.b64",
        'create("stableDebug")',
    ]:
        if token not in build:
            fail(f"Build/update signing configuration is missing token: {token}")

    client = (ROOT / "app/src/main/java/com/oai/geminilivetranslate/network/GeminiLiveClient.kt").read_text(encoding="utf-8")
    for forbidden in ['.put("inputAudioTranscription"', '.put("outputAudioTranscription"']:
        if forbidden in client:
            fail(f"Gemini setup reintroduced unsupported field: {forbidden}")
    for token in ["createSetupMessage", "responseModalities", "translationConfig", "targetLanguageCode"]:
        if token not in client:
            fail(f"Gemini setup is missing token: {token}")

    print(f"[OK] Verified wrapper bootstrap SHA-256: {WRAPPER_SHA256}")
    print("[OK] Gradle 8.10.2 distribution is pinned with SHA-256")
    print("[OK] Gemini setup matches the working Lua translation payload")
    print("[OK] Debug package/version are fixed for update installation")
    print(f"[OK] Stable debug certificate is pinned: {STABLE_DEBUG_CERT_SHA256}")
    print(f"[OK] Stable debug keystore SHA-256: {STABLE_DEBUG_KEYSTORE_SHA256}")
    print("[OK] CI builds, tests, lints and verifies an updateable debug APK")
    print("[OK] Rolling debug APK is published through GitHub Releases")
    print("[OK] Signed APK/AAB release workflow is configured separately")
    print("[OK] Secret scanning, dependency automation and support templates are present")
    print("GITHUB_READY_OK")


if __name__ == "__main__":
    main()
