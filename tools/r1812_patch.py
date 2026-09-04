from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match in {path}, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all_tests(old: str, new: str):
    for p in (ROOT / "app/src/test").rglob("*.kt"):
        text = p.read_text(encoding="utf-8")
        if old in text:
            p.write_text(text.replace(old, new), encoding="utf-8")


video = ROOT / "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt"
transcribe = ROOT / "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt"
executor = ROOT / "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt"
request_fix = ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"

# 1) Video Description: auto-submit again, but only after the proven upload/processing readiness gate.
replace_once(
    video,
    '''        logger.log(2, TAG, "Manual generate armed promptChars=${prompt.length} model=$model mode=$mode autoSubmit=false")\n        onProgress("Video đã gắn. Hãy chờ trang AI Studio xử lý xong rồi tự nhấn Send/Run trên trang web.", 25)\n        val webResult = generateAndAwaitManual(exec, prompt)''',
    '''        logger.log(2, TAG, "R22_VIDEO_AUTO_SUBMIT_AFTER_READY promptChars=${prompt.length} model=$model mode=$mode autoSubmit=true readinessGate=attachment-prepared")\n        onProgress("Video đã tải và xử lý xong; ứng dụng đang tự nhấn Run...", 25)\n        val webResult = generateAndAwaitAuto(exec, prompt)''',
    "video-auto-call",
)
replace_once(
    video,
    '''        exec.attachFile(uri, displayName, mimeType, size, requireUploadReady = false) { ok, detail ->\n            okRef.set(ok); detailRef.set(detail); latch.countDown()\n        }\n        if (!latch.await(95, TimeUnit.SECONDS)) error("Hết thời gian gắn video vào AI Studio")\n        throwIfCancelled()\n        if (!okRef.get()) error("Không gắn được video vào AI Studio: ${detailRef.get().take(500)}")\n        logger.log(2, TAG, "Attachment visible; manual readiness monitoring will continue name=$displayName size=$size")''',
    '''        exec.attachFile(uri, displayName, mimeType, size, requireUploadReady = true) { ok, detail ->\n            okRef.set(ok); detailRef.set(detail); latch.countDown()\n        }\n        if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải/xử lý video")\n        throwIfCancelled()\n        if (!okRef.get()) error("AI Studio chưa xác nhận video sẵn sàng: ${detailRef.get().take(500)}")\n        logger.log(2, TAG, "R22_VIDEO_ATTACHMENT_READY name=$displayName size=$size readiness=server-payload-settled+ready-after-busy")''',
    "video-ready-gate",
)
replace_once(
    video,
    '''    private fun generateAndAwaitManual(\n        exec: AiStudioWebSessionExecutor,\n        prompt: String,\n    ): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val resultRef = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.awaitManualAttachmentGenerate(prompt = prompt) { result ->\n                resultRef.set(result)\n                latch.countDown()\n            }\n            if (!accepted && resultRef.get() == null) {\n                resultRef.set(AiStudioWebSessionExecutor.Result(ok = false, error = "MANUAL_GENERATE_NOT_ARMED"))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ bạn nhấn Send/Run thủ công trong AI Studio")\n        throwIfCancelled()\n        val result = resultRef.get() ?: error("AI Studio không trả trạng thái sau thao tác thủ công")\n        if (!result.ok) error("AI Studio GenerateContent thất bại sau thao tác thủ công: ${result.error.ifBlank { "HTTP ${result.status}" }}")\n        return result\n    }''',
    '''    private fun generateAndAwaitAuto(\n        exec: AiStudioWebSessionExecutor,\n        prompt: String,\n    ): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val resultRef = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.generateAttachmentNativeOnly(prompt = prompt) { result ->\n                resultRef.set(result)\n                latch.countDown()\n            }\n            if (!accepted && resultRef.get() == null) {\n                resultRef.set(AiStudioWebSessionExecutor.Result(ok = false, error = "AUTO_GENERATE_NOT_ARMED"))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio mô tả video")\n        throwIfCancelled()\n        val result = resultRef.get() ?: error("AI Studio không trả trạng thái mô tả video")\n        if (!result.ok) error("AI Studio GenerateContent tự động thất bại: ${result.error.ifBlank { "HTTP ${result.status}" }}")\n        return result\n    }''',
    "video-auto-function",
)

# 2) File Transcribe: upload/prepare only, then the user manually presses Run while we observe.
replace_once(
    transcribe,
    '''        logger.log(2, TAG, "CONFIG model=$model prompt=false autoLanguage=true diarizationRequested=$speakerDiarization transport=aistudio-web-file-only")\n        onProgress("Tệp đã sẵn sàng; đang chép lời bằng model tệp, không dùng lời nhắc...", 55)\n        val result = generateFileOnlyNative(exec)''',
    '''        logger.log(2, TAG, "CONFIG model=$model prompt=false autoLanguage=true diarizationRequested=$speakerDiarization transport=aistudio-web-file-only manualRun=true")\n        onProgress("Tệp đã tải/xử lý xong. Hãy tự nhấn Run trên trang AI Studio; ứng dụng chỉ theo dõi request/config.", 55)\n        val result = awaitManualFileOnly(exec)''',
    "transcribe-manual-call",
)
replace_once(
    transcribe,
    '''    private fun generateFileOnlyNative(exec: AiStudioWebSessionExecutor): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.generateAttachmentFileOnlyNative { r -> ref.set(r); latch.countDown() }\n            if (!accepted && ref.get() == null) {\n                ref.set(AiStudioWebSessionExecutor.Result(ok = false, error = "NATIVE_FILE_ONLY_TRANSCRIBE_NOT_ARMED"))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio chép lời tệp")\n        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp")\n        if (!r.ok) error("AI Studio file transcribe thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")\n        return r\n    }''',
    '''    private fun awaitManualFileOnly(exec: AiStudioWebSessionExecutor): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.awaitManualAttachmentFileOnlyGenerate { r -> ref.set(r); latch.countDown() }\n            if (!accepted && ref.get() == null) {\n                ref.set(AiStudioWebSessionExecutor.Result(ok = false, error = "MANUAL_FILE_TRANSCRIBE_NOT_ARMED"))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ bạn nhấn Run thủ công cho chép lời tệp")\n        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp sau thao tác thủ công")\n        if (!r.ok) error("AI Studio file transcribe sau thao tác thủ công thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")\n        return r\n    }''',
    "transcribe-manual-function",
)

# 3) Executor: a manual file-only observer that never inserts a prompt and never auto-taps Run.
replace_once(
    executor,
    ' * R12.4 production-shaped executor for an authenticated AI Studio web session.',
    ' * R12.5 production-shaped executor for an authenticated AI Studio web session.',
    "executor-version-comment",
)
needle = '''    fun generateAttachmentFileOnlyNative(\n        callback: (Result) -> Unit,\n    ): Boolean {\n        if (destroyed || !pageFinished || state != State.READY || pending != null) {\n            callback(Result(ok = false, error = "NOT_READY_OR_BUSY"))\n            return false\n        }\n        val request = beginPreparedAttachmentRequest("", callback, "attachment-file-only-native")\n        events?.onLog("R21_FILE_TRANSCRIBE_ARMED", "seq=${request.seq} prompt=false modelInput=file-only")\n        tryNativeAttachmentSubmit(\n            request.seq,\n            "file-transcribe-primary",\n            0,\n            allowProgrammaticFallback = false,\n            fileOnly = true,\n        )\n        return true\n    }\n'''
insert = needle + '''\n    fun awaitManualAttachmentFileOnlyGenerate(\n        callback: (Result) -> Unit,\n    ): Boolean {\n        if (destroyed || !pageFinished || state != State.READY || pending != null) {\n            callback(Result(ok = false, error = "NOT_READY_OR_BUSY"))\n            return false\n        }\n        val baselineScript = "JSON.stringify((function(){var n=window.__AIS_WEB_SESSION__;var f=window.__AIS_R11_REQUEST_FIX__;return {ok:true,captureCount:Number(n&&n.captureCount||0),selectedModel:String(f&&f.selectedModel||''),requestedModel:String(f&&f.requestedModel||'')};})())"\n        webView.evaluateJavascript(baselineScript) { raw ->\n            if (destroyed || pending != null) {\n                callback(Result(ok = false, error = "NOT_READY_OR_BUSY"))\n                return@evaluateJavascript\n            }\n            val decoded = decodeEvalValue(raw)\n            val obj = runCatching { JSONObject(decoded) }.getOrNull()\n            val baseline = obj?.optInt("captureCount", -1) ?: -1\n            if (baseline < 0) {\n                callback(Result(ok = false, error = "CAPTURE_BASELINE_UNAVAILABLE", phase = decoded.take(500)))\n                return@evaluateJavascript\n            }\n            val request = beginPreparedAttachmentRequest("", callback, "manual-file-transcribe")\n            events?.onLog(\n                "R22_FILE_TRANSCRIBE_MANUAL_ARMED",\n                "seq=${request.seq} baseline=$baseline prompt=false modelInput=file-only selectedModel=${obj?.optString("selectedModel").orEmpty()} autoSubmit=false",\n            )\n            monitorManualFileOnlyGenerate(request.seq, baseline)\n        }\n        return true\n    }\n\n    private fun monitorManualFileOnlyGenerate(requestSeq: Int, baseline: Int) {\n        if (pending?.seq != requestSeq || destroyed) return\n        val script = "JSON.stringify((function(b){var a=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachmentEvidence?window.__AIS_R11_SUPPORT__.attachmentEvidence():{};var n=window.__AIS_WEB_SESSION__;var f=window.__AIS_R11_REQUEST_FIX__;var c=Number(n&&n.captureCount||0);return {ok:true,baseline:b,captureCount:c,manualSubmitSeen:b>=0&&c>b,present:!!a.present,ready:!!a.ready,busy:!!a.busy,submitReady:!!a.submitReady,localReadReady:!!a.localReadReady,serverPayloadObserved:!!a.serverPayloadObserved,serverPayloadSettled:!!a.serverPayloadSettled,domState:String(a.domState||''),domReadyAfterBusy:!!a.domReadyAfterBusy,selectedModel:String(f&&f.selectedModel||''),requestedModel:String(f&&f.requestedModel||''),payloadStarted:Number(a.payloadStarted||0),payloadCompleted:Number(a.payloadCompleted||0),payloadFailed:Number(a.payloadFailed||0),performanceCount:Number(a.performanceCount||0),submitLabel:String(a.submitLabel||''),submitScore:Number(a.submitScore||-1)};})($baseline))"\n        webView.evaluateJavascript(script) { raw ->\n            if (pending?.seq != requestSeq) return@evaluateJavascript\n            val decoded = decodeEvalValue(raw)\n            val obj = runCatching { JSONObject(decoded) }.getOrNull()\n            events?.onLog("R22_FILE_TRANSCRIBE_MANUAL_STATE", decoded.take(8000))\n            if (obj?.optBoolean("manualSubmitSeen") == true) {\n                events?.onLog(\n                    "R22_FILE_TRANSCRIBE_MANUAL_SUBMIT_DETECTED",\n                    "seq=$requestSeq captureCount=${obj.optInt("captureCount", -1)} baseline=$baseline selectedModel=${obj.optString("selectedModel")}",\n                )\n                readNormalized(requestSeq, "manual-file-transcribe-submit")\n                return@evaluateJavascript\n            }\n            main.postDelayed({ monitorManualFileOnlyGenerate(requestSeq, baseline) }, MANUAL_READINESS_POLL_MS)\n        }\n    }\n'''
replace_once(executor, needle, insert, "executor-manual-file-observer")

# 4) Request trace: log the sanitized shape of the *actual* Generate request created by AI Studio.
replace_once(
    request_fix,
    '    const val VERSION = "2026-09-05-web-session-r11.9-blob-stream-dom-trace"',
    '    const val VERSION = "2026-09-05-web-session-r11.10-manual-config-trace"',
    "request-fix-version",
)
insert_after = '''          function isGenerateUrl(raw) {\n            const s = String(raw || '');\n            return /MakerSuiteService\\/(?:GenerateContent|BidiGenerateContent)/i.test(s) || /\\/GenerateContent(?:[/?]|$)/i.test(s);\n          }\n'''
extra = insert_after + '''\n          function sanitizeTraceText(raw) {\n            try {\n              let s = String(raw || '');\n              s = s.replace(/data:[^,]{0,160};base64,[A-Za-z0-9+\\/_=-]{64,}/gi,'<DATA_URL_REDACTED>');\n              s = s.replace(/AIza[0-9A-Za-z_-]{20,}/g,'<API_KEY_REDACTED>');\n              s = s.replace(/ya29\\.[0-9A-Za-z._-]+/g,'<OAUTH_REDACTED>');\n              s = s.replace(/Bearer\\s+[A-Za-z0-9._~+\\/=-]+/gi,'Bearer <REDACTED>');\n              s = s.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}/gi,'<EMAIL_REDACTED>');\n              s = s.replace(/[A-Za-z0-9+\\/_=-]{256,}/g,function(m){return '<LONG_TOKEN_'+m.length+'>';});\n              return s.slice(0,1800);\n            } catch (_) { return ''; }\n          }\n\n          function emitGenerateRequestShape(source, url, body, stage) {\n            try {\n              if (!isGenerateUrl(url) || typeof body !== 'string') return;\n              const hp = hostPath(url);\n              const models = [];\n              const seen = Object.create(null);\n              const re = /(?:models\\/)?gemini-[a-z0-9][a-z0-9._-]{2,110}/ig;\n              let m;\n              while ((m = re.exec(body)) && models.length < 8) {\n                const id = normalizeModel(m[0]);\n                if (id && !seen[id]) { seen[id] = true; models.push(id); }\n              }\n              emit('R22_GENERATE_REQUEST_SHAPE',{\n                stage:String(stage||''),source:String(source||''),host:hp.host,path:hp.path,bodyChars:body.length,\n                selectedModel:String(fix.selectedModel||''),models:models,\n                hasTranscriptionConfig:/transcription[_-]?config|transcriptionConfig/i.test(body),\n                hasDiarization:/diarization|speaker[_-]?separation|speakerDiarization/i.test(body),\n                hasLanguageCodes:/language[_-]?codes|languageCodes/i.test(body),\n                hasTimestamp:/timestamp[_-]?granular|timestampGranular/i.test(body),\n                hasAudioMime:/audio\\//i.test(body),hasVideoMime:/video\\//i.test(body),\n                hasDriveRef:/drive|resource[_-]?name|file[_-]?(?:uri|id)|attachment/i.test(body),\n                preview:sanitizeTraceText(body)\n              });\n            } catch (err) { emit('R22_GENERATE_REQUEST_SHAPE_ERROR',{error:String(err).slice(0,500)}); }\n          }\n'''
replace_once(request_fix, insert_after, extra, "request-trace-functions")

old_rewrite = '''          function rewriteBody(url, body, source) {\n            if (!fix.selectedModel || typeof body !== 'string' || !isGenerateUrl(url)) return body;\n            const original = firstModel(body);\n            if (!original) {\n              emit('R11_MODEL_REWRITE_SKIPPED',{reason:'MODEL_NOT_FOUND_IN_BODY',target:fix.selectedModel,source:source,bodyChars:body.length});\n              return body;\n            }\n            let rewritten = body;\n            if (original !== fix.selectedModel) rewritten = body.split(original).join(fix.selectedModel);\n            fix.lastOriginalModel = original;\n            fix.lastAppliedModel = fix.selectedModel;\n            fix.rewriteCount += 1;\n            emit('R11_GENERATE_MODEL_REWRITE',{\n              source:source,\n              originalModel:original,\n              targetModel:fix.selectedModel,\n              changed:rewritten!==body,\n              rewriteCount:fix.rewriteCount,\n              bodyChars:rewritten.length\n            });\n            return rewritten;\n          }\n'''
new_rewrite = '''          function rewriteBody(url, body, source) {\n            if (typeof body !== 'string' || !isGenerateUrl(url)) return body;\n            const original = firstModel(body);\n            let rewritten = body;\n            if (fix.selectedModel && original) {\n              if (original !== fix.selectedModel) rewritten = body.split(original).join(fix.selectedModel);\n              fix.lastOriginalModel = original;\n              fix.lastAppliedModel = fix.selectedModel;\n              fix.rewriteCount += 1;\n              emit('R11_GENERATE_MODEL_REWRITE',{\n                source:source,originalModel:original,targetModel:fix.selectedModel,changed:rewritten!==body,\n                rewriteCount:fix.rewriteCount,bodyChars:rewritten.length\n              });\n            } else if (!original) {\n              emit('R11_MODEL_REWRITE_SKIPPED',{reason:'MODEL_NOT_FOUND_IN_BODY',target:fix.selectedModel,source:source,bodyChars:body.length});\n            }\n            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');\n            return rewritten;\n          }\n'''
replace_once(request_fix, old_rewrite, new_rewrite, "request-rewrite-trace")

old_loadend = '''                    xhr.addEventListener('loadend',function(){\n                      let status=-1;try{status=Number(xhr.status||-1);}catch(_){}\n                      noteAttachmentNetDone(netToken,status);\n                    },{once:true});'''
new_loadend = '''                    xhr.addEventListener('loadend',function(){\n                      let status=-1;try{status=Number(xhr.status||-1);}catch(_){}\n                      noteAttachmentNetDone(netToken,status);\n                      try {\n                        if (isGenerateUrl(meta.url||'') && status >= 400) {\n                          let text=''; try { text=String(xhr.responseText||''); } catch (_) {}\n                          emit('R22_GENERATE_RESPONSE_ERROR',{\n                            source:'xhr',host:hostPath(meta.url||'').host,path:hostPath(meta.url||'').path,status:status,\n                            responseChars:text.length,preview:sanitizeTraceText(text)\n                          });\n                        }\n                      } catch (_) {}\n                    },{once:true});'''
replace_once(request_fix, old_loadend, new_loadend, "xhr-error-response-trace")

# Keep source-inspection tests aligned with the new versions / intended routing.
replace_all_tests("2026-09-05-web-session-r11.9-blob-stream-dom-trace", "2026-09-05-web-session-r11.10-manual-config-trace")
replace_all_tests("R12.4 production-shaped executor", "R12.5 production-shaped executor")
for p in (ROOT / "app/src/test").rglob("*.kt"):
    text = p.read_text(encoding="utf-8")
    original = text
    if "AiStudioVideoDescriptionClient" in text:
        text = text.replace("awaitManualAttachmentGenerate", "generateAttachmentNativeOnly")
        text = text.replace("autoSubmit=false", "autoSubmit=true")
    if "AiStudioFileTranscribeClient" in text:
        text = text.replace("generateAttachmentFileOnlyNative", "awaitManualAttachmentFileOnlyGenerate")
        text = text.replace("R21_FILE_TRANSCRIBE_ARMED", "R22_FILE_TRANSCRIBE_MANUAL_ARMED")
    if text != original:
        p.write_text(text, encoding="utf-8")

print("R18.12 patch applied")
