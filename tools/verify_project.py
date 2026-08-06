#!/usr/bin/env python3
"""Fast structural checks that do not require Android SDK or network access."""

from __future__ import annotations

import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app/src/main/java"

REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/oai/geminilivetranslate/GeminiTranslateApp.kt",
    "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
    "app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt",
    "app/src/main/java/com/oai/geminilivetranslate/network/GeminiLiveClient.kt",
    "app/src/main/java/com/oai/geminilivetranslate/core/AppLogRepository.kt",
    "app/src/main/java/com/oai/geminilivetranslate/core/SessionLogger.kt",
    "app/src/main/java/com/oai/geminilivetranslate/core/SettingsPolicy.kt",
    "app/src/main/java/com/oai/geminilivetranslate/ui/LogViewerActivity.kt",
    "app/src/main/java/com/oai/geminilivetranslate/ui/SettingsActivity.kt",
    "app/src/main/java/com/oai/geminilivetranslate/audio/StreamingPcmConverter.kt",
    "app/src/main/java/com/oai/geminilivetranslate/audio/MicAudioSource.kt",
    "app/src/main/java/com/oai/geminilivetranslate/audio/InternalAudioSource.kt",
    "app/src/main/java/com/oai/geminilivetranslate/audio/FileAudioSource.kt",
    "app/src/test/java/com/oai/geminilivetranslate/audio/StreamingPcmConverterTest.kt",
    "app/src/test/java/com/oai/geminilivetranslate/core/SettingsPolicyTest.kt",
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout/activity_mini_browser.xml",
    "app/src/main/res/xml/file_paths.xml",
    "docs/DIAGNOSTICS.md",
]


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def camel(identifier: str) -> str:
    head, *tail = identifier.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def check_binding(kotlin_path: str, layout_path: str) -> None:
    source = (ROOT / kotlin_path).read_text(encoding="utf-8")
    xml = (ROOT / layout_path).read_text(encoding="utf-8")
    ids = set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', xml))
    properties = {camel(value) for value in ids} | {"root", "isInitialized"}
    used = set(re.findall(r"\bbinding\.([A-Za-z_][A-Za-z0-9_]*)", source))
    missing = sorted(used - properties)
    if missing:
        fail(f"Binding properties missing in {layout_path}: {', '.join(missing)}")


def require_tokens(source: str, tokens: list[str], label: str) -> None:
    for token in tokens:
        if token not in source:
            fail(f"Expected {label} token not found: {token}")


def main() -> None:
    for relative in REQUIRED:
        if not (ROOT / relative).is_file():
            fail(f"Missing required file: {relative}")

    for xml in sorted((ROOT / "app/src/main").rglob("*.xml")):
        try:
            ET.parse(xml)
        except ET.ParseError as error:
            fail(f"Invalid XML {xml.relative_to(ROOT)}: {error}")

    check_binding(
        "app/src/main/java/com/oai/geminilivetranslate/MainActivity.kt",
        "app/src/main/res/layout/activity_main.xml",
    )
    check_binding(
        "app/src/main/java/com/oai/geminilivetranslate/ui/MiniBrowserActivity.kt",
        "app/src/main/res/layout/activity_mini_browser.xml",
    )

    kotlin_files = sorted(JAVA_ROOT.rglob("*.kt"))
    kotlin = "\n".join(path.read_text(encoding="utf-8") for path in kotlin_files)
    require_tokens(
        kotlin,
        [
            "gemini-3.5-live-translate-preview",
            'audio/pcm;rate=16000',
            "inputAudioTranscription",
            "outputAudioTranscription",
            "translationConfig",
            "AudioPlaybackCaptureConfiguration",
            "AndroidKeyStore",
            "FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION",
        ],
        "Gemini/audio/security",
    )
    require_tokens(
        kotlin,
        [
            'put("sessionResumption", sessionResumption)',
            'optJSONObject("sessionResumptionUpdate")',
            'optJSONObject("goAway")',
            "queueSize()",
            "SendResult.BACKPRESSURED",
            "LinkedBlockingDeque<InputFrame>",
            "settings.pacingMaxBuffer",
            "StreamingPcmConverter",
            "MediaProjectionStoppedException",
            "mediaProjection.registerCallback",
        ],
        "resilience",
    )
    require_tokens(
        kotlin,
        [
            "class AppLogRepository private constructor",
            "AppLogRepository.get(context)",
            "diagnostic-current.log",
            "createDiagnosticBundle",
            "REDACTION_PATTERNS",
            "logIncludeTranscript",
            "BufferedWriter",
            "MAX_ROTATED_FILES",
            "class LogViewerActivity",
            "DiagnosticContext.updateAll",
            "Thread.setDefaultUncaughtExceptionHandler",
        ],
        "shared diagnostics",
    )
    require_tokens(
        kotlin,
        [
            "object SettingsPolicy",
            "fun sanitize(input: AppSettings)",
            "fun activeSessionSettings",
            "SettingsPolicy.diff",
            "ACTION_APPLY_SETTINGS",
            "ACTION_REFRESH_API_KEY",
            "restoreDefaultsPreservingKeys",
            "logIncludeTranscript: Boolean = false",
        ],
        "settings policy",
    )

    service = (JAVA_ROOT / "com/oai/geminilivetranslate/service/TranslationService.kt").read_text(encoding="utf-8")
    if service.count("notificationController.start(this, initialState)") != 1:
        fail("Foreground notification must be started exactly once per session")

    input_sources = "\n".join(
        (JAVA_ROOT / f"com/oai/geminilivetranslate/audio/{name}").read_text(encoding="utf-8")
        for name in ("MicAudioSource.kt", "InternalAudioSource.kt", "FileAudioSource.kt")
    )
    if "PcmTools.toMono16k" in input_sources:
        fail("An input source still uses the old stateless PCM conversion path")

    settings_activity = (JAVA_ROOT / "com/oai/geminilivetranslate/ui/SettingsActivity.kt").read_text(encoding="utf-8")
    if "Áp dụng model" in settings_activity or "Áp dụng mã" in settings_activity:
        fail("Obsolete two-step model/language apply buttons are still present")
    if "filesDir.listFiles()?.forEach(File::deleteRecursively)" in kotlin:
        fail("Unsafe whole-filesDir recursive reset path is present")

    logger_calls = len(re.findall(r"\blogger\?*\.log\(", kotlin))
    if logger_calls < 50:
        fail(f"Diagnostic coverage unexpectedly low: only {logger_calls} logger calls")

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    require_tokens(manifest, ['android:name=".GeminiTranslateApp"', 'android:name=".ui.LogViewerActivity"'], "manifest")
    file_paths = (ROOT / "app/src/main/res/xml/file_paths.xml").read_text(encoding="utf-8")
    require_tokens(file_paths, ['<cache-path name="diagnostic_cache" path="diagnostic-share/"'], "diagnostic FileProvider")

    build_file = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    require_tokens(build_file, ['versionName = "1.2.0"', 'versionCode = 10200', 'testImplementation("junit:junit:4.13.2")'], "build")

    lua_files = list(ROOT.rglob("*.lua"))
    if lua_files:
        fail("Lua files found in native project: " + ", ".join(str(p.relative_to(ROOT)) for p in lua_files))

    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    digest = hashlib.sha256(wrapper.read_bytes()).hexdigest()
    if wrapper.stat().st_size < 1_000:
        fail("Gradle bootstrap jar is unexpectedly small")

    print("[OK] Required project and diagnostics files")
    print("[OK] XML well-formed")
    print("[OK] ViewBinding IDs")
    print("[OK] Gemini/audio/security implementation markers")
    print("[OK] WebSocket backpressure, resumption and GoAway")
    print("[OK] Streaming PCM conversion and MediaProjection lifecycle")
    print(f"[OK] Shared diagnostics coverage: {logger_calls} log points")
    print("[OK] Rotating buffered logs, redaction and diagnostic ZIP")
    print("[OK] Centralized settings validation and live/deferred application")
    print("[OK] Scoped reset paths and no obsolete apply buttons")
    print("[OK] No Lua dependency or source")
    print(f"[OK] Gradle bootstrap SHA-256: {digest}")
    print("PROJECT_STRUCTURE_OK")


if __name__ == "__main__":
    main()
