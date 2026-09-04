#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"
EXEC = ROOT / "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt"
FILE_CLIENT = ROOT / "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt"
TEST = ROOT / "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"

req = REQ.read_text()
required_req = [
    '2026-09-05-web-session-r11.8-upload-rpc-trace',
    'R20_ATTACHMENT_FILE_READ_DONE',
    'R20_ATTACHMENT_PAYLOAD_START',
    'R20_ATTACHMENT_PAYLOAD_PROGRESS',
    'R20_ATTACHMENT_PAYLOAD_RESULT',
    'attachmentPrepared=present&&!busy&&localReadReady&&submitReady',
    'serverPayloadObserved=fix.attachmentPayloadStarted>0',
]
missing = [x for x in required_req if x not in req]
if missing:
    raise SystemExit('R18.10 request patch incomplete: ' + ', '.join(missing))

exe = EXEC.read_text()
old_ready = '''events?.onLog("R18_ATTACHMENT_UPLOAD_READY", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt}")'''
new_ready = '''events?.onLog("R20_ATTACHMENT_PREPARED", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt} localReadReady=${obj?.optBoolean("localReadReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)}")'''
if old_ready in exe:
    exe = exe.replace(old_ready, new_ready, 1)
elif 'R20_ATTACHMENT_PREPARED' not in exe:
    raise SystemExit('executor ready log anchor missing')

old_wait = '''events?.onLog("R18_ATTACHMENT_WAIT_UPLOAD", "token=$token busy=${obj?.optBoolean("busy", false)} uploadObserved=${obj?.optBoolean("uploadObserved", false)} uploadSettled=${obj?.optBoolean("uploadSettled", false)} submitReady=${obj?.optBoolean("submitReady", false)} activeUploads=${obj?.optInt("activeUploads", 0)} started=${obj?.optInt("uploadStarted", 0)} completed=${obj?.optInt("uploadCompleted", 0)} failed=${obj?.optInt("uploadFailed", 0)}")'''
new_wait = '''events?.onLog("R20_ATTACHMENT_WAIT_PREPARED", "token=$token busy=${obj?.optBoolean("busy", false)} present=$present localReadReady=${obj?.optBoolean("localReadReady", false)} attachmentPrepared=${obj?.optBoolean("attachmentPrepared", false)} submitReady=${obj?.optBoolean("submitReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)} payloadActive=${obj?.optInt("payloadActive", 0)} payloadStarted=${obj?.optInt("payloadStarted", 0)} payloadCompleted=${obj?.optInt("payloadCompleted", 0)} payloadFailed=${obj?.optInt("payloadFailed", 0)}")'''
if old_wait in exe:
    exe = exe.replace(old_wait, new_wait, 1)
elif 'R20_ATTACHMENT_WAIT_PREPARED' not in exe:
    if 'R18_ATTACHMENT_WAIT_UPLOAD' in exe:
        exe = exe.replace('R18_ATTACHMENT_WAIT_UPLOAD', 'R20_ATTACHMENT_WAIT_PREPARED', 1)
    else:
        raise SystemExit('executor wait log anchor missing')
EXEC.write_text(exe)

client = FILE_CLIENT.read_text()
client = client.replace('onProgress("Đang tải tệp lên AI Studio...", 8)', 'onProgress("Đang đưa tệp vào AI Studio và chờ trang đọc xong...", 8)')
client = client.replace('logger.log(2, TAG, "ATTACHMENT_READY model=$model name=$name")', 'logger.log(2, TAG, "ATTACHMENT_PREPARED model=$model name=$name")')
FILE_CLIENT.write_text(client)

test = TEST.read_text()
test = test.replace('assertTrue(src.contains("R18_ATTACHMENT_WAIT_UPLOAD"))', 'assertTrue(src.contains("R20_ATTACHMENT_WAIT_PREPARED"))')
test = test.replace('assertTrue(src.contains("R18_ATTACHMENT_UPLOAD_READY"))', 'assertTrue(src.contains("R20_ATTACHMENT_PREPARED"))')
old_asserts = '''        assertTrue(requestFix.contains("uploadObserved=uploadStarted>0"))\n        assertTrue(requestFix.contains("uploadSettled=uploadObserved"))\n        assertTrue(requestFix.contains("submitReady"))\n        assertTrue(requestFix.contains("ready:ready"))'''
new_asserts = '''        assertTrue(requestFix.contains("2026-09-05-web-session-r11.8-upload-rpc-trace"))\n        assertTrue(requestFix.contains("R20_ATTACHMENT_FILE_READ_DONE"))\n        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_START"))\n        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_PROGRESS"))\n        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_RESULT"))\n        assertTrue(requestFix.contains("probeMatches"))\n        assertTrue(requestFix.contains("localReadReady=fix.attachmentFileReadCompleted>0"))\n        assertTrue(requestFix.contains("attachmentPrepared=present&&!busy&&localReadReady&&submitReady"))\n        assertTrue(requestFix.contains("serverPayloadObserved=fix.attachmentPayloadStarted>0"))\n        assertTrue(requestFix.contains("submitReady"))\n        assertTrue(requestFix.contains("ready:ready"))'''
if old_asserts in test:
    test = test.replace(old_asserts, new_asserts, 1)
elif 'R20_ATTACHMENT_PAYLOAD_START' not in test:
    raise SystemExit('test assertion anchor missing')
TEST.write_text(test)

for path, needles in [
    (EXEC, ['R20_ATTACHMENT_PREPARED', 'R20_ATTACHMENT_WAIT_PREPARED']),
    (FILE_CLIENT, ['ATTACHMENT_PREPARED']),
    (TEST, ['R20_ATTACHMENT_FILE_READ_DONE', 'R20_ATTACHMENT_PAYLOAD_RESULT']),
]:
    text = path.read_text()
    miss = [n for n in needles if n not in text]
    if miss:
        raise SystemExit(f'{path.name} missing: {miss}')

print('R18.10 executor/client/test finishing patch applied')
