from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing {label} in {path}")
    if s.count(old) != 1:
        raise SystemExit(f"count {label}={s.count(old)} in {path}")
    p.write_text(s.replace(old, new, 1))
    print(f"patched {label}: {path}")

r11t = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"
replace_once(r11t,
    "    fun executorUsesDirectEngineBeforeLegacyUiFallbackAndContainsNoTouchSimulation() {",
    "    fun executorUsesDirectEngineThenNativeComposerTapBeforeLegacyFallback() {",
    "r11-test-name")
replace_once(r11t,
    '        assertTrue(src.contains("2026-09-02-web-session-r12.1-progress-watchdog"))',
    '        assertTrue(src.contains("2026-09-04-web-session-r12.2-native-submit-persistent-debug"))',
    "r11-r12-version")
replace_once(r11t,
    '        assertTrue(src.contains("tryLegacyProgrammaticFallback"))\n        assertFalse(src.contains("dispatchTouchEvent"))\n        assertFalse(src.contains("MotionEvent"))\n        assertFalse(src.contains("InputDevice.SOURCE_TOUCHSCREEN"))\n',
    '        assertTrue(src.contains("tryLegacyProgrammaticFallback"))\n        assertTrue(src.contains("tryNativeAttachmentSubmit"))\n        assertTrue(src.contains("nativeTapController.requestNativeTap"))\n        assertTrue(src.contains("R12_NATIVE_SUBMIT_ACK"))\n        assertFalse(src.contains("dispatchTouchEvent"))\n        assertFalse(src.contains("MotionEvent"))\n        assertFalse(src.contains("InputDevice.SOURCE_TOUCHSCREEN"))\n',
    "r11-native-assertions")

r12t = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12R1SourceTest.kt"
replace_once(r12t,
    '        assertTrue(executor.contains("2026-09-02-web-session-r12.1-progress-watchdog"))',
    '        assertTrue(executor.contains("2026-09-04-web-session-r12.2-native-submit-persistent-debug"))',
    "r12-version-assertion")
replace_once(r12t,
    '        assertTrue(executor.contains("terminal2xx"))\n',
    '        assertTrue(executor.contains("terminal2xx"))\n        assertTrue(executor.contains("NATIVE_SUBMIT_MAX_RETRIES = 3"))\n        assertTrue(executor.contains("AiStudioDebugWebViewHost.retain"))\n',
    "r12-new-behavior-assertions")

r18t = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18SourceTest.kt"
replace_once(r18t,
    '        assertTrue(bootstrap.contains("r17.6-lean-live-bootstrap"))',
    '        assertTrue(bootstrap.contains("r17.7-start-ack-retry"))',
    "r18-r17-version")
replace_once(r18t,
    '        assertTrue(bootstrap.contains("startAttempts"))\n',
    '        assertTrue(bootstrap.contains("startAttempts"))\n        assertTrue(bootstrap.contains("START_ACK_TIMEOUT"))\n        assertTrue(bootstrap.contains("waiting-start-ack"))\n',
    "r18-start-ack-assertions")

print("R18.7 test assertions updated")
