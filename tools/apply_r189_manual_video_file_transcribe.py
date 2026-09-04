#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f'MISSING {label}')
    return text.replace(old, new, 1)

# 1) R11 submit target: prepare prompt without submitting.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt'
s = read(p)
s = s.replace('2026-09-04-web-session-r11.6-upload-readiness-native-submit', '2026-09-05-web-session-r11.7-manual-ready-prompt')
anchor = "          function submitIfAttachment(){\n"
insert = r'''          function setPromptValue(el,text){
            try{
              const value=String(text||'');
              if(!el)return {ok:false,error:'NO_PROMPT'};
              try{el.focus();}catch(_){}
              const tag=String(el.tagName||'').toUpperCase();
              if(tag==='TEXTAREA'&&window.HTMLTextAreaElement){
                const d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');
                if(d&&d.set)d.set.call(el,value);else el.value=value;
              }else if(tag==='INPUT'&&window.HTMLInputElement){
                const d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                if(d&&d.set)d.set.call(el,value);else el.value=value;
              }else if(el.isContentEditable){
                el.textContent=value;
              }else if('value' in el){
                el.value=value;
              }else{
                el.textContent=value;
              }
              let ev=null;
              try{ev=new InputEvent('input',{bubbles:true,composed:true,inputType:'insertText',data:value});}catch(_){ev=new Event('input',{bubbles:true,composed:true});}
              el.dispatchEvent(ev);
              el.dispatchEvent(new Event('change',{bubbles:true,composed:true}));
              const observed=String(('value' in el?el.value:el.textContent)||'');
              return {ok:observed.length>0,observedChars:observed.length,tag:tag,role:String(el.getAttribute&&el.getAttribute('role')||'')};
            }catch(err){return {ok:false,error:'PROMPT_SET_ERROR',detail:String(err).slice(0,600)};}
          }

          function preparePromptIfAttachment(promptText){
            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);
            if(!attachmentPresent())return {ok:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};
            const d=discover();
            if(!d.prompt)return {ok:false,error:'NO_PROMPT',baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasComposerRoot:!!d.composerRoot};
            const set=setPromptValue(d.prompt,promptText);
            if(net){net.expectedMarker='';net.lastResult=null;net.lastProgress=null;net.lastXhrLifecycle=null;}
            const readiness=submissionReadinessIfAttachment();
            const out={ok:!!set.ok,baselineCaptureCount:baseline,promptChars:String(promptText||'').length,observedChars:Number(set.observedChars||0),tag:String(set.tag||''),role:String(set.role||''),submitReady:!!readiness.ready,submitDisabled:!!readiness.disabled,submitScore:Number(readiness.score||-1),submitLabel:String(readiness.label||'').slice(0,180),fingerprint:readiness.fingerprint||null};
            emit('R19_MANUAL_PROMPT_PREPARED',out);
            return out;
          }

'''
if 'function preparePromptIfAttachment(' not in s:
    s = must_replace(s, anchor, insert + anchor, 'R11 submit insert')
export_anchor = "            submissionReadinessIfAttachment:submissionReadinessIfAttachment,\n"
if 'preparePromptIfAttachment:preparePromptIfAttachment' not in s:
    s = must_replace(s, export_anchor, export_anchor + "            preparePromptIfAttachment:preparePromptIfAttachment,\n", 'R11 submit export')
write(p, s)

# 2) R11 request fix: uploadStarted=0 is UNKNOWN, never READY.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt'
s = read(p)
s = s.replace('2026-09-04-web-session-r11.6-upload-ready-gate', '2026-09-05-web-session-r11.7-strict-upload-observation')
old = "                const uploadSettled=activeUploads===0&&(uploadStarted===0||(uploadCompleted+uploadFailed)>=uploadStarted);\n                const busy=!!support.busy,submitReady=!!submit.ready;\n                const ready=present&&!busy&&uploadSettled&&submitReady;\n"
new = "                const uploadObserved=uploadStarted>0;\n                const uploadSettled=uploadObserved&&activeUploads===0&&uploadFailed===0&&uploadCompleted>=uploadStarted;\n                const busy=!!support.busy,submitReady=!!submit.ready;\n                const ready=present&&!busy&&uploadSettled&&submitReady;\n"
s = must_replace(s, old, new, 'strict upload readiness')
s = s.replace('uploadSettled:uploadSettled,activeUploads:activeUploads,uploadStarted:uploadStarted,uploadCompleted:uploadCompleted,uploadFailed:uploadFailed,', 'uploadObserved:uploadObserved,uploadSettled:uploadSettled,activeUploads:activeUploads,uploadStarted:uploadStarted,uploadCompleted:uploadCompleted,uploadFailed:uploadFailed,')
write(p, s)

# 3) Executor: allow attachment-present callback for manual video, strict readiness monitor, native-only and manual generate modes.
p = 'app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt'
s = read(p)
s = s.replace('R12.1 production-shaped executor', 'R12.4 production-shaped executor')
s = s.replace('2026-09-04-web-session-r12.3-upload-ready-native-submit', '2026-09-05-web-session-r12.4-manual-video-native-file')
s = must_replace(s, '        val callback: (Boolean, String) -> Unit,\n        var readyScans: Int = 0,', '        val callback: (Boolean, String) -> Unit,\n        val requireUploadReady: Boolean,\n        var readyScans: Int = 0,', 'PendingAttachment field')
s = must_replace(s, '        size: Long,\n        callback: (Boolean, String) -> Unit,', '        size: Long,\n        requireUploadReady: Boolean = true,\n        callback: (Boolean, String) -> Unit,', 'attachFile signature')
s = must_replace(s, '                callback = callback,\n            )', '                callback = callback,\n                requireUploadReady = requireUploadReady,\n            )', 'attachFile item')
s = must_replace(s, '            if (ready) {\n', '            if (!item.requireUploadReady && present) {\n                events?.onLog("R19_ATTACHMENT_PRESENT_MANUAL", "token=$token waitedMs=${now - item.startedAt} detail=${decoded.take(6000)}")\n                finishAttachment(token, true, decoded)\n                return@evaluateJavascript\n            }\n            if (ready) {\n', 'manual attach present')

# propagate strict readiness diagnostics at useful verbosity
s = s.replace('events?.onLog("R18_ATTACHMENT_WAIT_UPLOAD", "token=$token busy=${obj?.optBoolean("busy", false)} uploadSettled=${obj?.optBoolean("uploadSettled", false)} submitReady=${obj?.optBoolean("submitReady", false)} activeUploads=${obj?.optInt("activeUploads", 0)}")', 'events?.onLog("R18_ATTACHMENT_WAIT_UPLOAD", "token=$token busy=${obj?.optBoolean("busy", false)} uploadObserved=${obj?.optBoolean("uploadObserved", false)} uploadSettled=${obj?.optBoolean("uploadSettled", false)} submitReady=${obj?.optBoolean("submitReady", false)} activeUploads=${obj?.optInt("activeUploads", 0)} started=${obj?.optInt("uploadStarted", 0)} completed=${obj?.optInt("uploadCompleted", 0)} failed=${obj?.optInt("uploadFailed", 0)}")')

# insert prepare/manual/native-only API before generate()
anchor = '    fun generate(\n'
methods = r'''    private fun prepareAttachmentPrompt(prompt: String, callback: (Boolean, String, Int) -> Unit) {
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__&&window.__AIS_R11_SUBMIT_TARGET__.preparePromptIfAttachment?window.__AIS_R11_SUBMIT_TARGET__.preparePromptIfAttachment(${JSONObject.quote(prompt)}):({ok:false,error:'manual-prompt-preparer-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val ok = obj?.optBoolean("ok") == true
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            events?.onLog("R19_PROMPT_PREPARE", decoded.take(10000))
            callback(ok, decoded, baseline)
        }
    }

    private fun beginPreparedAttachmentRequest(
        prompt: String,
        callback: (Result) -> Unit,
        mode: String,
    ): Pending {
        seq += 1
        val request = Pending(
            seq = seq,
            prompt = prompt,
            marker = "",
            callback = callback,
            startedAt = SystemClock.uptimeMillis(),
            progressAware = true,
        )
        pending = request
        directRecoverySeq = -1
        setState(State.GENERATING, "request=${request.seq} mode=$mode")
        schedulePolls(request.seq)
        scheduleProgressWatchdog(request.seq)
        return request
    }

    fun generateAttachmentNativeOnly(
        prompt: String,
        callback: (Result) -> Unit,
    ): Boolean {
        if (destroyed || !pageFinished || state != State.READY || pending != null || prompt.isBlank()) {
            callback(Result(ok = false, error = if (prompt.isBlank()) "EMPTY_PROMPT" else "NOT_READY_OR_BUSY"))
            return false
        }
        prepareAttachmentPrompt(prompt) { ok, detail, _ ->
            if (!ok) {
                callback(Result(ok = false, error = "PROMPT_PREPARE_FAILED", phase = detail.take(500)))
                return@prepareAttachmentPrompt
            }
            val request = beginPreparedAttachmentRequest(prompt, callback, "attachment-native-only")
            events?.onLog("R19_NATIVE_FILE_SUBMIT_ARMED", "seq=${request.seq} promptChars=${prompt.length}")
            tryNativeAttachmentSubmit(request.seq, "native-file-primary", 0, allowProgrammaticFallback = false)
        }
        return true
    }

    fun awaitManualAttachmentGenerate(
        prompt: String,
        callback: (Result) -> Unit,
    ): Boolean {
        if (destroyed || !pageFinished || state != State.READY || pending != null || prompt.isBlank()) {
            callback(Result(ok = false, error = if (prompt.isBlank()) "EMPTY_PROMPT" else "NOT_READY_OR_BUSY"))
            return false
        }
        prepareAttachmentPrompt(prompt) { ok, detail, baseline ->
            if (!ok) {
                callback(Result(ok = false, error = "PROMPT_PREPARE_FAILED", phase = detail.take(500)))
                return@prepareAttachmentPrompt
            }
            val request = beginPreparedAttachmentRequest(prompt, callback, "manual-video-submit")
            events?.onLog("R19_MANUAL_VIDEO_ARMED", "seq=${request.seq} baseline=$baseline promptChars=${prompt.length} autoSubmit=false")
            monitorManualAttachmentReadiness(request.seq, baseline, prompt)
        }
        return true
    }

    private fun monitorManualAttachmentReadiness(requestSeq: Int, baseline: Int, prompt: String) {
        if (pending?.seq != requestSeq || destroyed) return
        val script = "JSON.stringify((function(b){var a=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachmentEvidence?window.__AIS_R11_SUPPORT__.attachmentEvidence():{};var n=window.__AIS_WEB_SESSION__;var c=Number(n&&n.captureCount||0);return {ok:true,baseline:b,captureCount:c,manualSubmitSeen:b>=0&&c>b,present:!!a.present,ready:!!a.ready,busy:!!a.busy,uploadObserved:!!a.uploadObserved,uploadSettled:!!a.uploadSettled,submitReady:!!a.submitReady,activeUploads:Number(a.activeUploads||0),uploadStarted:Number(a.uploadStarted||0),uploadCompleted:Number(a.uploadCompleted||0),uploadFailed:Number(a.uploadFailed||0),submitLabel:String(a.submitLabel||''),submitScore:Number(a.submitScore||-1)};})($baseline))"
        webView.evaluateJavascript(script) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            events?.onLog("R19_MANUAL_VIDEO_READINESS", decoded.take(8000))
            if (obj?.optBoolean("manualSubmitSeen") == true) {
                events?.onLog("R19_MANUAL_SUBMIT_DETECTED", "seq=$requestSeq captureCount=${obj.optInt("captureCount", -1)} baseline=$baseline")
                readNormalized(requestSeq, "manual-submit-detected")
                return@evaluateJavascript
            }
            val promptCheck = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__&&window.__AIS_R11_SUBMIT_TARGET__.preparePromptIfAttachment?window.__AIS_R11_SUBMIT_TARGET__.preparePromptIfAttachment(${JSONObject.quote(prompt)}):({ok:false,error:'manual-prompt-preparer-not-installed'}))"
            webView.evaluateJavascript(promptCheck) { prepared ->
                if (pending?.seq == requestSeq) events?.onLog("R19_MANUAL_PROMPT_REFRESH", decodeEvalValue(prepared).take(3000))
            }
            main.postDelayed({ monitorManualAttachmentReadiness(requestSeq, baseline, prompt) }, MANUAL_READINESS_POLL_MS)
        }
    }

'''
if 'fun awaitManualAttachmentGenerate(' not in s:
    s = must_replace(s, anchor, methods + anchor, 'executor manual methods')

# make native fallback optionally strict
s = must_replace(s, '    private fun tryNativeAttachmentSubmit(requestSeq: Int, reason: String, attempt: Int) {', '    private fun tryNativeAttachmentSubmit(requestSeq: Int, reason: String, attempt: Int, allowProgrammaticFallback: Boolean = true) {', 'native signature')
s = s.replace('tryNativeAttachmentSubmit(requestSeq, "target-rescan", attempt + 1)', 'tryNativeAttachmentSubmit(requestSeq, "target-rescan", attempt + 1, allowProgrammaticFallback)')
s = s.replace('else {\n                    tryProgrammaticAttachmentFallback(requestSeq, "native-target-unavailable")\n                }', 'else {\n                    if (allowProgrammaticFallback) tryProgrammaticAttachmentFallback(requestSeq, "native-target-unavailable")\n                    else finish(requestSeq, Result(ok = false, error = "NATIVE_SUBMIT_TARGET_UNAVAILABLE"))\n                }')
s = s.replace('tryNativeAttachmentSubmit(requestSeq, "invalid-native-target", attempt + 1)', 'tryNativeAttachmentSubmit(requestSeq, "invalid-native-target", attempt + 1, allowProgrammaticFallback)')
s = s.replace('else tryProgrammaticAttachmentFallback(requestSeq, "invalid-native-target")', 'else if (allowProgrammaticFallback) tryProgrammaticAttachmentFallback(requestSeq, "invalid-native-target") else finish(requestSeq, Result(ok = false, error = "NATIVE_SUBMIT_INVALID_TARGET"))')
s = s.replace('tryNativeAttachmentSubmit(requestSeq, "no-capture", attempt + 1)', 'tryNativeAttachmentSubmit(requestSeq, "no-capture", attempt + 1, allowProgrammaticFallback)')
s = s.replace('                    } else {\n                        tryProgrammaticAttachmentFallback(requestSeq, "native-no-capture")\n                    }', '                    } else {\n                        if (allowProgrammaticFallback) tryProgrammaticAttachmentFallback(requestSeq, "native-no-capture")\n                        else finish(requestSeq, Result(ok = false, error = "NATIVE_SUBMIT_NO_CAPTURE"))\n                    }')
# add manual polling constant
s = s.replace('        private const val NATIVE_SUBMIT_MAX_RETRIES = 3\n', '        private const val NATIVE_SUBMIT_MAX_RETRIES = 3\n        private const val MANUAL_READINESS_POLL_MS = 1_000L\n')
write(p, s)

# 4) Video client: attach only until visible, prepare prompt, then wait for USER manual click.
p = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt'
s = read(p)
s = s.replace('        logger.log(2, TAG, "Generate promptChars=${prompt.length} model=$model mode=$mode")\n        val webResult = generateAndAwait(exec, prompt)', '        logger.log(2, TAG, "Manual generate armed promptChars=${prompt.length} model=$model mode=$mode autoSubmit=false")\n        onProgress("Video đã gắn. Hãy chờ trang AI Studio xử lý xong rồi tự nhấn Send/Run trên trang web.", 25)\n        val webResult = generateAndAwaitManual(exec, prompt)')
s = s.replace('            val level = if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3', '            val level = when {\n                            name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2\n                            name.contains("ERROR") || name.contains("TIMEOUT") -> 1\n                            else -> 3\n                        }')
s = s.replace('        exec.attachFile(uri, displayName, mimeType, size) { ok, detail ->', '        exec.attachFile(uri, displayName, mimeType, size, requireUploadReady = false) { ok, detail ->')
s = s.replace('        logger.log(2, TAG, "Attachment upload-ready name=$displayName size=$size")', '        logger.log(2, TAG, "Attachment visible; manual readiness monitoring will continue name=$displayName size=$size")')
pattern = re.compile(r'    private fun generateAndAwait\(\n        exec: AiStudioWebSessionExecutor,\n        prompt: String,\n    \): AiStudioWebSessionExecutor.Result \{.*?\n    \}\n\n    private fun parseTimeline', re.S)
replacement = '''    private fun generateAndAwaitManual(\n        exec: AiStudioWebSessionExecutor,\n        prompt: String,\n    ): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val resultRef = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.awaitManualAttachmentGenerate(prompt = prompt) { result ->\n                resultRef.set(result)\n                latch.countDown()\n            }\n            if (!accepted && resultRef.get() == null) {\n                resultRef.set(AiStudioWebSessionExecutor.Result(ok = false, error = "MANUAL_GENERATE_NOT_ARMED"))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ bạn nhấn Send/Run thủ công trong AI Studio")\n        throwIfCancelled()\n        val result = resultRef.get() ?: error("AI Studio không trả trạng thái sau thao tác thủ công")\n        if (!result.ok) error("AI Studio GenerateContent thất bại sau thao tác thủ công: ${result.error.ifBlank { "HTTP ${result.status}" }}")\n        return result\n    }\n\n    private fun parseTimeline'''
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit('MISSING video generateAndAwait')
write(p, s)

# 5) New AI Studio FILE transcribe client, non-Live, model gemini-3.5-transcribe.
new_client = r'''package com.oai.geminilivetranslate.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Authenticated AI Studio FILE transcription. This is intentionally not Gemini Live. */
class AiStudioFileTranscribeClient(
    context: Context,
    private val logger: SessionLogger,
    private val model: String = AppPreferences.TRANSCRIBE_FILE_MODEL,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var executor: AiStudioWebSessionExecutor? = null

    fun transcribe(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String,
        speakerDiarization: Boolean,
        onProgress: (String, Int) -> Unit,
    ): GeminiFileTranscribeClient.Result {
        val size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { d -> d.length.takeIf { it >= 0L } ?: d.parcelFileDescriptor.statSize.takeIf { it >= 0L } }
        }.getOrNull() ?: -1L
        val startedAt = SystemClock.elapsedRealtime()
        logger.log(2, TAG, "START backend=aistudio-file model=$model name=$displayName mime=$mimeType bytes=$size live=false")
        onProgress("Đang mở AI Studio cho chép lời tệp...", 2)
        val exec = createAndAwaitReady()
        selectModel(exec)
        onProgress("Đang tải tệp lên AI Studio...", 8)
        attachAndWait(exec, uri, displayName, mimeType, size)
        val prompt = buildPrompt(speakerDiarization)
        onProgress("Tệp đã sẵn sàng; đang chép lời bằng model tệp...", 55)
        val result = generateNative(exec, prompt)
        val parsed = parse(result.modelText)
        logger.log(2, TAG, "DONE backend=aistudio-file model=$model chars=${parsed.text.length} words=${parsed.words.size} elapsedMs=${SystemClock.elapsedRealtime()-startedAt}")
        onProgress("Đang tạo kết quả...", 98)
        return parsed
    }

    fun cancel() {
        cancelled = true
        val current = executor
        executor = null
        main.post { current?.destroy() }
    }
    fun close() = cancel()

    private fun createAndAwaitReady(): AiStudioWebSessionExecutor {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val holder = AtomicReference<AiStudioWebSessionExecutor?>()
        main.post {
            val context = GeminiTranslateApp.currentActivity() ?: appContext
            val created = AiStudioWebSessionExecutor(context, object : AiStudioWebSessionExecutor.Events {
                override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
                    logger.log(3, TAG, "EXECUTOR state=$state detail=${detail.take(500)}")
                    if (state == AiStudioWebSessionExecutor.State.READY) latch.countDown()
                    if (state == AiStudioWebSessionExecutor.State.ERROR || state == AiStudioWebSessionExecutor.State.DESTROYED) {
                        failure.compareAndSet(null, "$state: $detail"); latch.countDown()
                    }
                }
                override fun onLog(name: String, detail: String) {
                    val level = if (name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3
                    logger.log(level, TAG, "$name ${detail.take(5000)}")
                }
            })
            holder.set(created); executor = created; created.start()
        }
        if (!latch.await(45, TimeUnit.SECONDS)) error("AI Studio file transcribe chưa sẵn sàng")
        failure.get()?.let { error(it) }
        return holder.get() ?: error("Không tạo được AI Studio file session")
    }

    private fun selectModel(exec: AiStudioWebSessionExecutor) {
        var last = ""
        repeat(12) { attempt ->
            val latch = CountDownLatch(1); val ok = AtomicReference(false); val detail = AtomicReference("")
            exec.selectModel(model) { yes, d -> ok.set(yes); detail.set(d); latch.countDown() }
            latch.await(4, TimeUnit.SECONDS)
            last = detail.get()
            if (ok.get()) { logger.log(2, TAG, "MODEL_READY model=$model attempt=${attempt+1}"); return }
            Thread.sleep(500)
        }
        error("Không chọn được model tệp $model: ${last.take(500)}")
    }

    private fun attachAndWait(exec: AiStudioWebSessionExecutor, uri: Uri, name: String, mime: String, size: Long) {
        val latch = CountDownLatch(1); val ok = AtomicReference(false); val detail = AtomicReference("")
        exec.attachFile(uri, name, mime, size, requireUploadReady = true) { yes, d -> ok.set(yes); detail.set(d); latch.countDown() }
        if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải tệp chép lời")
        if (!ok.get()) error("AI Studio chưa xác nhận tệp sẵn sàng: ${detail.get().take(700)}")
        logger.log(2, TAG, "ATTACHMENT_READY model=$model name=$name")
    }

    private fun generateNative(exec: AiStudioWebSessionExecutor, prompt: String): AiStudioWebSessionExecutor.Result {
        val latch = CountDownLatch(1); val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()
        main.post {
            val accepted = exec.generateAttachmentNativeOnly(prompt) { r -> ref.set(r); latch.countDown() }
            if (!accepted && ref.get() == null) { ref.set(AiStudioWebSessionExecutor.Result(ok=false,error="NATIVE_FILE_GENERATE_NOT_ARMED")); latch.countDown() }
        }
        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio chép lời tệp")
        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp")
        if (!r.ok) error("AI Studio file transcribe thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")
        return r
    }

    private fun buildPrompt(diarization: Boolean): String = """
Hãy chép lời CHÍNH XÁC toàn bộ lời nói trong tệp đính kèm bằng model chuyên chép lời tệp.
Không dịch, không tóm tắt, không thêm nội dung không nghe thấy.
${if (diarization) "Phân biệt người nói khi có thể." else "Không cần phân biệt người nói."}
Chỉ trả về một JSON object hợp lệ, không markdown:
{"text":"toàn bộ bản chép lời","words":[{"text":"từ hoặc cụm từ","start_seconds":0.0,"end_seconds":0.5,"speaker":""}]}
Nếu không thể cung cấp timestamp từng từ, vẫn phải trả text đầy đủ và có thể để words là mảng rỗng.
""".trimIndent()

    private fun parse(raw: String): GeminiFileTranscribeClient.Result {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = clean.indexOf('{'); val b = clean.lastIndexOf('}')
        val root = if (a >= 0 && b > a) JSONObject(clean.substring(a, b + 1)) else JSONObject().put("text", clean)
        val text = root.optString("text").trim()
        val words = ArrayList<GeminiFileTranscribeClient.WordInfo>()
        val arr = root.optJSONArray("words")
        if (arr != null) for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val body = w.optString("text").trim(); if (body.isBlank()) continue
            val start = (w.optDouble("start_seconds", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
            val end = (w.optDouble("end_seconds", start / 1000.0) * 1000.0).toLong().coerceAtLeast(start)
            words += GeminiFileTranscribeClient.WordInfo(body, w.optString("speaker").takeIf(String::isNotBlank), start, end)
        }
        if (text.isBlank() && words.isEmpty()) error("AI Studio trả bản chép lời rỗng")
        return GeminiFileTranscribeClient.Result(text.ifBlank { words.joinToString(" ") { it.text } }, words)
    }

    private companion object { const val TAG = "AiStudioFileTranscribe" }
}
'''
write('app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt', new_client)

# 6) TranslationService: FILE transcribe never routes to Live; use authenticated file client in AI Studio mode.
p = 'app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt'
s = read(p)
s = s.replace('import com.oai.geminilivetranslate.network.AiStudioVideoDescriptionClient\n', 'import com.oai.geminilivetranslate.network.AiStudioFileTranscribeClient\nimport com.oai.geminilivetranslate.network.AiStudioVideoDescriptionClient\n')
s = s.replace('    @Volatile private var aiStudioVideoDescriptionClient: AiStudioVideoDescriptionClient? = null\n', '    @Volatile private var aiStudioVideoDescriptionClient: AiStudioVideoDescriptionClient? = null\n    @Volatile private var aiStudioFileTranscribeClient: AiStudioFileTranscribeClient? = null\n')
s = s.replace('        val useLiveFileTranscribe = aiStudioMode && isTranscribeMode() && mode == SourceMode.FILE\n', '        val useLiveFileTranscribe = false // FILE uses gemini-3.5-transcribe, never the Live model.\n')
s = s.replace('            "START connectionMode=$connectionMode processing=$processingMode source=$mode apiKeyRequired=${!aiStudioMode && !useProxyVideoDescription} liveFileTranscribe=$useLiveFileTranscribe videoProvider=${aiApi.provider}",', '            "START connectionMode=$connectionMode processing=$processingMode source=$mode apiKeyRequired=${!aiStudioMode && !useProxyVideoDescription} liveFileTranscribe=$useLiveFileTranscribe fileTranscribeBackend=${if (aiStudioMode && isTranscribeMode() && mode == SourceMode.FILE) "aistudio-file" else "default"} videoProvider=${aiApi.provider}",')
s = s.replace('        aiStudioVideoDescriptionClient?.cancel()\n        aiStudioVideoDescriptionClient = null\n', '        aiStudioVideoDescriptionClient?.cancel()\n        aiStudioVideoDescriptionClient = null\n        aiStudioFileTranscribeClient?.cancel()\n        aiStudioFileTranscribeClient = null\n')

start = s.index('    private fun startFileTranscription() {')
end = s.index('    private fun mediaDurationMs(uri: Uri): Long {', start)
new_fun = r'''    private fun startFileTranscription() {
        sourceJob?.cancel()
        sourceJob = serviceScope.launch(Dispatchers.IO) {
            val uri = selectedUri ?: run {
                stopTranslation("Chưa chọn tệp")
                return@launch
            }
            val workDir = File(cacheDir, "transcribe-$sessionId").apply { deleteRecursively(); mkdirs() }
            val totalStartedAt = SystemClock.elapsedRealtime()
            try {
                val name = selectedFileName ?: uri.lastPathSegment ?: "audio"
                val mimeType = selectedMimeType(uri, name)
                val video = mimeType.startsWith("video/") || isVideoFileName(name)
                val sourceBytes = sourceSizeBytes(uri)
                logger.log(2, "TranscribeFile", "Bắt đầu xử lý name=$name mime=$mimeType video=$video sourceBytes=$sourceBytes")

                var durationMs = mediaDurationMs(uri)
                if (durationMs <= 0L) error("Không đọc được thời lượng tệp")
                if (durationMs > MAX_TRANSCRIBE_FILE_DURATION_MS) error("Tệp dài quá 30 phút. Hãy cắt tệp ngắn hơn rồi thử lại")

                val aiStudioMode = AiStudioLiveBackendPolicy.preferAiStudio(this@TranslationService)
                val result: GeminiFileTranscribeClient.Result = if (aiStudioMode) {
                    logger.log(2, "BackendRoute", "FILE_TRANSCRIBE backend=aistudio-file model=${AppPreferences.TRANSCRIBE_FILE_MODEL} live=false")
                    val c = AiStudioFileTranscribeClient(
                        context = this@TranslationService,
                        logger = logger,
                        model = AppPreferences.TRANSCRIBE_FILE_MODEL,
                    )
                    aiStudioFileTranscribeClient = c
                    try {
                        c.transcribe(
                            resolver = contentResolver,
                            uri = uri,
                            displayName = name,
                            mimeType = mimeType,
                            speakerDiarization = speakerDiarization,
                        ) { status, percent ->
                            updateState { it.copy(status = status, progressPercent = percent.coerceIn(0, 98)) }
                        }
                    } finally {
                        c.close()
                        if (aiStudioFileTranscribeClient === c) aiStudioFileTranscribeClient = null
                    }
                } else if (video) {
                    val extractStartedAt = SystemClock.elapsedRealtime()
                    logger.log(2, "TranscribeFile", "Bắt đầu tách audio track từ video")
                    updateState { it.copy(status = "Đang tách âm thanh...", progressPercent = 0) }
                    val extracted = VideoAudioExtractor.extract(
                        context = this@TranslationService,
                        uri = uri,
                        output = File(workDir, "audio.m4a"),
                        maxDurationMs = MAX_TRANSCRIBE_FILE_DURATION_MS,
                    ) { percent -> updateState { it.copy(status = "Đang tách âm thanh...", progressPercent = (percent / 10).coerceIn(0, 10)) } }
                    durationMs = extracted.durationMs
                    logger.log(2, "TranscribeFile", "Tách audio xong elapsedMs=${SystemClock.elapsedRealtime()-extractStartedAt} durationMs=$durationMs samples=${extracted.sampleCount} outputBytes=${extracted.outputBytes} trackMime=${extracted.trackMimeType} outputMime=${extracted.mimeType} strategy=${extracted.strategy}")
                    runWithGeminiKeyFailover("chép lời tệp") { candidateKey, keyIndex, keyCount ->
                        val c = GeminiFileTranscribeClient(candidateKey, logger)
                        try {
                            if (keyIndex > 0) updateState { it.copy(status = "Đang thử API Key ${keyIndex + 1}/$keyCount để chép lời...") }
                            c.transcribe(extracted.file, extracted.mimeType, speakerDiarization) { status, percent ->
                                updateState { it.copy(status = status, progressPercent = (10 + percent * 90 / 100).coerceIn(10, 98)) }
                            }
                        } finally { c.close() }
                    }
                } else {
                    updateState { it.copy(status = "Đang tải tệp lên...", progressPercent = 0) }
                    runWithGeminiKeyFailover("chép lời tệp") { candidateKey, keyIndex, keyCount ->
                        val c = GeminiFileTranscribeClient(candidateKey, logger)
                        try {
                            if (keyIndex > 0) updateState { it.copy(status = "Đang thử API Key ${keyIndex + 1}/$keyCount để chép lời...") }
                            c.transcribe(contentResolver, uri, name, mimeType, speakerDiarization) { status, percent ->
                                updateState { it.copy(status = status, progressPercent = percent.coerceIn(0, 98)) }
                            }
                        } finally { c.close() }
                    }
                }

                val cues = buildTranscriptionCues(result.words, speakerDiarization)
                if (cues.isNotEmpty()) subtitles.replaceTimed(cues) else {
                    subtitles.reset()
                    result.text.trim().takeIf(String::isNotBlank)?.let { subtitles.appendTimed(it, 0L, durationMs.coerceAtLeast(1_000L)) }
                }
                transcribePlainText = if (speakerDiarization && cues.isNotEmpty()) subtitles.plainText() else result.text.trim().ifBlank { subtitles.plainText() }
                fileInputEnded = true
                updateState { it.copy(transcript = transcribePlainText.takeLast(MAX_TRANSCRIPT_CHARS), status = "Đã hoàn tất chép lời", progressPercent = 100) }
                logger.log(2, "TranscribeFile", "Hoàn tất toàn bộ totalElapsedMs=${SystemClock.elapsedRealtime()-totalStartedAt} chars=${transcribePlainText.length} words=${result.words.size} cues=${cues.size} backend=${if (aiStudioMode) "aistudio-file" else "gemini-api"}")
                stopTranslation("Đã hoàn tất chép lời")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) return@launch
                logger.log(0, "TranscribeFile", "Chép lời tệp thất bại elapsedMs=${SystemClock.elapsedRealtime()-totalStartedAt}", error)
                if (_state.value.running) stopTranslation("Lỗi chép lời: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

'''
s = s[:start] + new_fun + s[end:]
write(p, s)

# 7) Live reliability: after real progress, 8s without server setup => recovery instead of 30s.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt'
s = read(p)
s = s.replace('2026-09-04-web-session-r17.8-progress-aware-start-ack', '2026-09-05-web-session-r17.9-fast-progress-recovery')
s = must_replace(s, 'const age=now-state.lastActionAt,progress=startProgressEvidence(),limit=progress.progress?30000:10000;', 'const age=now-state.lastActionAt,progress=startProgressEvidence(),limit=progress.progress?8000:10000;', 'R17 8s progress timeout')
write(p, s)

# Realtime client log version only; behavior uses R17 state and existing bounded reload recovery.
p = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt'
s = read(p)
s = s.replace('2026-09-04-production-ai-studio-live-r6-progress-aware-start-debug', '2026-09-05-production-ai-studio-live-r7-fast-start-recovery-debug')
write(p, s)

# Hard assertions so CI cannot package old routing/auto video behavior.
checks = {
    'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt': ['awaitManualAttachmentGenerate', 'autoSubmit=false', 'requireUploadReady = false'],
    'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt': ['gemini-3.5-transcribe', 'generateAttachmentNativeOnly', 'backend=aistudio-file'],
    'app/src/main/java/com/oai/geminilivetranslate/service/TranslationService.kt': ['val useLiveFileTranscribe = false', 'FILE_TRANSCRIBE backend=aistudio-file'],
    'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt': ['uploadObserved=uploadStarted>0', 'uploadSettled=uploadObserved'],
    'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt': ['limit=progress.progress?8000:10000'],
}
for path, markers in checks.items():
    text = read(path)
    for marker in markers:
        if marker not in text:
            raise SystemExit(f'ASSERTION FAILED {path}: {marker}')
print('R18.9 source patch applied and assertions passed')
