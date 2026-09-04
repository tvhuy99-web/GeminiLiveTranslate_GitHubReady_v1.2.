from pathlib import Path

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text()

def write(path, text):
    (ROOT / path).write_text(text)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    return text.replace(old, new, 1)

# 1) AI Studio FILE Transcribe: no prompt, no self-invented JSON contract.
p = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt'
s = read(p)
s = s.replace('import org.json.JSONObject\n', '')
s = replace_once(s,
'''        attachAndWait(exec, uri, displayName, mimeType, size)\n        val prompt = buildPrompt(speakerDiarization)\n        onProgress("Tệp đã sẵn sàng; đang chép lời bằng model tệp...", 55)\n        val result = generateNative(exec, prompt)\n        val parsed = parse(result.modelText)\n''',
'''        attachAndWait(exec, uri, displayName, mimeType, size)\n        logger.log(2, TAG, "CONFIG model=$model prompt=false autoLanguage=true diarizationRequested=$speakerDiarization transport=aistudio-web-file-only")\n        onProgress("Tệp đã sẵn sàng; đang chép lời bằng model tệp, không dùng lời nhắc...", 55)\n        val result = generateFileOnlyNative(exec)\n        val parsed = parsePlainTranscript(result.modelText)\n''', 'transcribe call path')
start = s.index('    private fun generateNative(exec: AiStudioWebSessionExecutor, prompt: String): AiStudioWebSessionExecutor.Result {')
end = s.index('    private companion object { const val TAG = "AiStudioFileTranscribe" }', start)
replacement = '''    private fun generateFileOnlyNative(exec: AiStudioWebSessionExecutor): AiStudioWebSessionExecutor.Result {\n        val latch = CountDownLatch(1)\n        val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()\n        main.post {\n            val accepted = exec.generateAttachmentFileOnlyNative { r -> ref.set(r); latch.countDown() }\n            if (!accepted && ref.get() == null) {\n                ref.set(AiStudioWebSessionExecutor.Result(ok = false, error = "NATIVE_FILE_ONLY_TRANSCRIBE_NOT_ARMED"))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio chép lời tệp")\n        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp")\n        if (!r.ok) error("AI Studio file transcribe thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")\n        return r\n    }\n\n    private fun parsePlainTranscript(raw: String): GeminiFileTranscribeClient.Result {\n        val clean = raw.trim()\n            .removePrefix("```text")\n            .removePrefix("```")\n            .removeSuffix("```")\n            .trim()\n        if (clean.isBlank()) error("AI Studio trả bản chép lời rỗng")\n        return GeminiFileTranscribeClient.Result(clean, emptyList())\n    }\n\n'''
s = s[:start] + replacement + s[end:]
write(p, s)

# 2) Executor: add strict file-only native submission and keep video manual.
p = 'app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt'
s = read(p)
s = s.replace('2026-09-05-web-session-r12.4-manual-video-native-file', '2026-09-05-web-session-r12.5-file-only-transcribe-video-probe')
anchor = '''    fun awaitManualAttachmentGenerate(\n        prompt: String,\n        callback: (Result) -> Unit,\n    ): Boolean {\n'''
file_only = '''    fun generateAttachmentFileOnlyNative(\n        callback: (Result) -> Unit,\n    ): Boolean {\n        if (destroyed || !pageFinished || state != State.READY || pending != null) {\n            callback(Result(ok = false, error = "NOT_READY_OR_BUSY"))\n            return false\n        }\n        val request = beginPreparedAttachmentRequest("", callback, "attachment-file-only-native")\n        events?.onLog("R21_FILE_TRANSCRIBE_ARMED", "seq=${request.seq} prompt=false modelInput=file-only")\n        tryNativeAttachmentSubmit(\n            request.seq,\n            "file-transcribe-primary",\n            0,\n            allowProgrammaticFallback = false,\n            fileOnly = true,\n        )\n        return true\n    }\n\n'''
s = replace_once(s, anchor, file_only + anchor, 'executor file-only method insertion')
s = replace_once(s,
'''    private fun tryNativeAttachmentSubmit(requestSeq: Int, reason: String, attempt: Int, allowProgrammaticFallback: Boolean = true) {\n        if (pending?.seq != requestSeq) return\n        events?.onLog("R12_NATIVE_SUBMIT_START", "seq=$requestSeq reason=$reason attempt=${attempt + 1}")\n        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.nativeTargetIfAttachment() : ({ok:false,error:'native-submit-target-not-installed'}))"\n''',
'''    private fun tryNativeAttachmentSubmit(\n        requestSeq: Int,\n        reason: String,\n        attempt: Int,\n        allowProgrammaticFallback: Boolean = true,\n        fileOnly: Boolean = false,\n    ) {\n        if (pending?.seq != requestSeq) return\n        events?.onLog("R12_NATIVE_SUBMIT_START", "seq=$requestSeq reason=$reason attempt=${attempt + 1} fileOnly=$fileOnly")\n        val targetFunction = if (fileOnly) "nativeTargetIfAttachmentFileOnly" else "nativeTargetIfAttachment"\n        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__[${JSONObject.quote(targetFunction)}]() : ({ok:false,error:'native-submit-target-not-installed'}))"\n''', 'native submit signature')
s = s.replace('tryNativeAttachmentSubmit(requestSeq, "target-rescan", attempt + 1, allowProgrammaticFallback)', 'tryNativeAttachmentSubmit(requestSeq, "target-rescan", attempt + 1, allowProgrammaticFallback, fileOnly)')
s = s.replace('tryNativeAttachmentSubmit(requestSeq, "invalid-native-target", attempt + 1, allowProgrammaticFallback)', 'tryNativeAttachmentSubmit(requestSeq, "invalid-native-target", attempt + 1, allowProgrammaticFallback, fileOnly)')
s = s.replace('tryNativeAttachmentSubmit(requestSeq, "no-capture", attempt + 1, allowProgrammaticFallback)', 'tryNativeAttachmentSubmit(requestSeq, "no-capture", attempt + 1, allowProgrammaticFallback, fileOnly)')
s = replace_once(s,
'''            nativeTapController.requestNativeTap(\n                JSONObject()\n                    .put("xRatio", xRatio)\n                    .put("yRatio", yRatio)\n                    .put("tag", "VIDEO_SEND")\n                    .put("role", "composer-submit")\n                    .put("purpose", "video-generate")\n                    .toString(),\n            )\n''',
'''            nativeTapController.requestNativeTap(\n                JSONObject()\n                    .put("xRatio", xRatio)\n                    .put("yRatio", yRatio)\n                    .put("tag", if (fileOnly) "FILE_TRANSCRIBE_RUN" else "VIDEO_SEND")\n                    .put("role", "composer-submit")\n                    .put("purpose", if (fileOnly) "file-transcribe-run" else "video-generate")\n                    .toString(),\n            )\n''', 'native tap purpose')
# enrich manual video readiness log with new R21 evidence
old = "return {ok:true,baseline:b,captureCount:c,manualSubmitSeen:b>=0&&c>b,present:!!a.present,ready:!!a.ready,busy:!!a.busy,uploadObserved:!!a.uploadObserved,uploadSettled:!!a.uploadSettled,submitReady:!!a.submitReady,activeUploads:Number(a.activeUploads||0),uploadStarted:Number(a.uploadStarted||0),uploadCompleted:Number(a.uploadCompleted||0),uploadFailed:Number(a.uploadFailed||0),submitLabel:String(a.submitLabel||''),submitScore:Number(a.submitScore||-1)};"
new = "return {ok:true,baseline:b,captureCount:c,manualSubmitSeen:b>=0&&c>b,present:!!a.present,ready:!!a.ready,busy:!!a.busy,uploadObserved:!!a.uploadObserved,uploadSettled:!!a.uploadSettled,submitReady:!!a.submitReady,localReadReady:!!a.localReadReady,blobReadReady:!!a.blobReadReady,serverPayloadObserved:!!a.serverPayloadObserved,serverPayloadSettled:!!a.serverPayloadSettled,domState:String(a.domState||''),domBusySeen:!!a.domBusySeen,domReadyAfterBusy:!!a.domReadyAfterBusy,domErrorSeen:!!a.domErrorSeen,domProgress:Number(a.domProgress||-1),activeUploads:Number(a.activeUploads||0),uploadStarted:Number(a.uploadStarted||0),uploadCompleted:Number(a.uploadCompleted||0),uploadFailed:Number(a.uploadFailed||0),blobReadStarted:Number(a.blobReadStarted||0),blobReadCompleted:Number(a.blobReadCompleted||0),blobReadFailed:Number(a.blobReadFailed||0),performanceCount:Number(a.performanceCount||0),submitLabel:String(a.submitLabel||''),submitScore:Number(a.submitScore||-1)};"
s = replace_once(s, old, new, 'manual readiness evidence')
write(p, s)

# 3) Submit target: reject mic/settings as Run; add file-only native target.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt'
s = read(p)
s = s.replace('2026-09-05-web-session-r11.7-manual-ready-prompt', '2026-09-05-web-session-r11.8-semantic-submit-file-only')
readiness_old = '''          function submissionReadinessIfAttachment(){\n            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);\n            if(!attachmentPresent())return {ok:true,ready:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};\n            const d=discover(),list=d.candidates;\n            if(!list.length)return {ok:true,ready:false,error:'NO_BUTTON_CANDIDATE',baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot};\n            const best=list[0],ready=!best.disabled&&best.score>=2500;\n            return {ok:true,ready:ready,disabled:!!best.disabled,score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};\n          }\n\n'''
readiness_new = '''          function semanticSubmitCandidates(d){\n            const list=d&&Array.isArray(d.candidates)?d.candidates:[];\n            return list.filter(function(x){\n              try{\n                const label=String(x&&x.label||'');\n                const type=String(x&&x.button&&x.button.getAttribute&&x.button.getAttribute('type')||'').toLowerCase();\n                const positive=type==='submit'||/(^|\\b)(send|run|submit|gửi|chạy)(\\b|$)/i.test(label);\n                const negative=/(mic|microphone|speech\\s*to\\s*text|record|tune|setting|settings|attach|upload|add\\s*file|remove|delete|close|cancel|stop|đính\\s*kèm|tải\\s*tệp|xóa|đóng|hủy)/i.test(label);\n                return positive&&!negative;\n              }catch(_){return false;}\n            });\n          }\n\n          function submissionReadinessIfAttachment(){\n            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);\n            if(!attachmentPresent())return {ok:true,ready:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};\n            const d=discover(),list=semanticSubmitCandidates(d);\n            if(!list.length)return {ok:true,ready:false,error:'NO_SEMANTIC_SUBMIT',baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot};\n            const best=list[0],ready=!best.disabled&&best.score>=900;\n            return {ok:true,ready:ready,disabled:!!best.disabled,score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};\n          }\n\n'''
s = replace_once(s, readiness_old, readiness_new, 'semantic readiness')
anchor = '''          function installClickTracking(){\n'''
file_target = '''          function nativeTargetIfAttachmentFileOnly(){\n            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);\n            if(!attachmentPresent())return {ok:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};\n            const d=discover(),list=semanticSubmitCandidates(d);\n            emit('R21_FILE_ONLY_TARGET_DISCOVERY',{expectedName:expectedName(),hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,baselineCaptureCount:baseline,count:list.length,top:list.slice(0,8).map(function(x){return {score:x.score,label:x.label.slice(0,180),disabled:x.disabled,fingerprint:fingerprint(x.button,d.composerRoot,d.prompt,d.attachment)};})});\n            if(!list.length)return {ok:false,error:'NO_SEMANTIC_SUBMIT',baselineCaptureCount:baseline};\n            const best=list[0];\n            if(best.disabled||best.score<900)return {ok:false,error:'NO_HIGH_CONFIDENCE_FILE_SUBMIT',score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline};\n            try{\n              const r=best.button.getBoundingClientRect(),vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||1);\n              const cx=r.left+r.width/2,cy=r.top+r.height/2;\n              if(r.width<2||r.height<2||cx<0||cy<0||cx>vw||cy>vh)return {ok:false,error:'SUBMIT_OUT_OF_VIEW',baselineCaptureCount:baseline,score:best.score};\n              return {ok:true,native:true,fileOnly:true,xRatio:cx/vw,yRatio:cy/vh,baselineCaptureCount:baseline,score:best.score,label:best.label.slice(0,180),fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};\n            }catch(err){return {ok:false,error:'SUBMIT_GEOMETRY_ERROR',detail:String(err).slice(0,500),baselineCaptureCount:baseline};}\n          }\n\n'''
s = replace_once(s, anchor, file_target + anchor, 'file-only target')
s = replace_once(s,
'''            nativeTargetIfAttachment:nativeTargetIfAttachment,\n            submitIfAttachment:submitIfAttachment,\n''',
'''            nativeTargetIfAttachment:nativeTargetIfAttachment,\n            nativeTargetIfAttachmentFileOnly:nativeTargetIfAttachmentFileOnly,\n            submitIfAttachment:submitIfAttachment,\n''', 'expose file-only target')
write(p, s)

# 4) R11 request probe: add deep Blob/stream/FormData/performance/DOM observation.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt'
s = read(p)
s = s.replace('2026-09-05-web-session-r11.8-upload-rpc-trace', '2026-09-05-web-session-r11.9-blob-stream-dom-trace')
s = replace_once(s,
'''            fileReadObserverInstalled: false,\n            fileArmToken: 0,\n''',
'''            fileReadObserverInstalled: false,\n            deepAttachmentObserverInstalled: false,\n            performanceObserverInstalled: false,\n            fileArmToken: 0,\n''', 'deep install fields')
s = replace_once(s,
'''            attachmentFileReadResultChars: -1,\n            attachmentPayloadStarted: 0,\n''',
'''            attachmentFileReadResultChars: -1,\n            attachmentBlobReadStarted: 0,\n            attachmentBlobReadCompleted: 0,\n            attachmentBlobReadFailed: 0,\n            attachmentBlobReadBytes: 0,\n            attachmentFormDataSeen: 0,\n            attachmentPerformanceCount: 0,\n            attachmentLastPerformance: null,\n            attachmentDomState: 'unknown',\n            attachmentDomBusySeen: false,\n            attachmentDomReadyAfterBusy: false,\n            attachmentDomErrorSeen: false,\n            attachmentDomProgress: -1,\n            attachmentDomTransitionCount: 0,\n            attachmentPayloadStarted: 0,\n''', 'deep state fields')
s = replace_once(s,
'''            fix.attachmentFileReadResultChars = -1;\n            fix.attachmentPayloadStarted = 0;\n''',
'''            fix.attachmentFileReadResultChars = -1;\n            fix.attachmentBlobReadStarted = 0;\n            fix.attachmentBlobReadCompleted = 0;\n            fix.attachmentBlobReadFailed = 0;\n            fix.attachmentBlobReadBytes = 0;\n            fix.attachmentFormDataSeen = 0;\n            fix.attachmentPerformanceCount = 0;\n            fix.attachmentLastPerformance = null;\n            fix.attachmentDomState = 'unknown';\n            fix.attachmentDomBusySeen = false;\n            fix.attachmentDomReadyAfterBusy = false;\n            fix.attachmentDomErrorSeen = false;\n            fix.attachmentDomProgress = -1;\n            fix.attachmentDomTransitionCount = 0;\n            fix.attachmentPayloadStarted = 0;\n''', 'reset deep state')
insert_anchor = '''          function installFileChangeObserver() {\n'''
deep_code = r'''          const trackedAttachmentStreams = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
          let nextBlobReadId = 1;

          function expectedBlob(blob) {
            try {
              if (!attachmentWindowActive() || !blob) return false;
              const name = String(blob.name || '');
              const size = Number(blob.size || -1);
              return (name && name === fix.attachmentExpectedName) || (size > 0 && size === Number(fix.attachmentExpectedSize || -1));
            } catch (_) { return false; }
          }

          function beginBlobRead(method, blob) {
            if (!expectedBlob(blob)) return null;
            const token = {id:nextBlobReadId++,method:String(method||''),size:Number(blob&&blob.size||-1),startedAt:Date.now(),bytes:0,lastBucket:-1,done:false};
            fix.attachmentBlobReadStarted += 1;
            emit('R21_ATTACHMENT_BLOB_READ_START',{id:token.id,method:token.method,size:token.size,started:fix.attachmentBlobReadStarted});
            return token;
          }

          function finishBlobRead(token, ok, bytes, reason) {
            if (!token || token.done) return;
            token.done = true;
            const n = Math.max(0,Number(bytes||0));
            if (ok) { fix.attachmentBlobReadCompleted += 1; fix.attachmentBlobReadBytes = Math.max(fix.attachmentBlobReadBytes,n); }
            else fix.attachmentBlobReadFailed += 1;
            emit(ok?'R21_ATTACHMENT_BLOB_READ_DONE':'R21_ATTACHMENT_BLOB_READ_ERROR',{
              id:token.id,method:token.method,size:token.size,bytes:n,reason:String(reason||''),elapsedMs:Date.now()-token.startedAt,
              completed:fix.attachmentBlobReadCompleted,failed:fix.attachmentBlobReadFailed
            });
          }

          function installDeepAttachmentObserver() {
            if (fix.deepAttachmentObserverInstalled) return true;
            try {
              if (window.Blob && Blob.prototype) {
                ['arrayBuffer','text'].forEach(function(name){
                  const current=Blob.prototype[name];
                  if(!current||current.__aisR21DeepAttachment)return;
                  const wrapped=function(){
                    const token=beginBlobRead('blob.'+name,this);
                    let result;
                    try{result=current.apply(this,arguments);}catch(err){finishBlobRead(token,false,0,'throw');throw err;}
                    if(token&&result&&typeof result.then==='function'){
                      result.then(function(value){
                        let bytes=token.size;
                        try{if(value&&typeof value.byteLength==='number')bytes=Number(value.byteLength);else if(typeof value==='string')bytes=value.length;}catch(_){}
                        finishBlobRead(token,true,bytes,'promise');
                      }).catch(function(){finishBlobRead(token,false,0,'promise-reject');});
                    }
                    return result;
                  };
                  wrapped.__aisR21DeepAttachment=true;Blob.prototype[name]=wrapped;
                });
                const currentStream=Blob.prototype.stream;
                if(currentStream&&currentStream.__aisR21DeepAttachment!==true){
                  const wrappedStream=function(){
                    const token=beginBlobRead('blob.stream',this);
                    const stream=currentStream.apply(this,arguments);
                    try{if(token&&trackedAttachmentStreams&&stream)trackedAttachmentStreams.set(stream,token);}catch(_){}
                    return stream;
                  };
                  wrappedStream.__aisR21DeepAttachment=true;Blob.prototype.stream=wrappedStream;
                }
                const currentSlice=Blob.prototype.slice;
                if(currentSlice&&currentSlice.__aisR21DeepAttachment!==true){
                  const wrappedSlice=function(){
                    try{if(expectedBlob(this))emit('R21_ATTACHMENT_BLOB_SLICE',{size:Number(this&&this.size||-1),start:Number(arguments[0]||0),end:arguments.length>1?Number(arguments[1]||0):-1});}catch(_){}
                    return currentSlice.apply(this,arguments);
                  };
                  wrappedSlice.__aisR21DeepAttachment=true;Blob.prototype.slice=wrappedSlice;
                }
              }
              if (window.ReadableStream && ReadableStream.prototype && ReadableStream.prototype.getReader && trackedAttachmentStreams) {
                const currentGetReader=ReadableStream.prototype.getReader;
                if(currentGetReader.__aisR21DeepAttachment!==true){
                  const wrappedGetReader=function(){
                    const reader=currentGetReader.apply(this,arguments);
                    const token=trackedAttachmentStreams.get(this);
                    if(token&&reader&&typeof reader.read==='function'&&reader.read.__aisR21DeepAttachment!==true){
                      const originalRead=reader.read.bind(reader);
                      const wrappedRead=function(){
                        const p=originalRead();
                        if(!p||typeof p.then!=='function')return p;
                        return p.then(function(result){
                          if(result&&result.done){finishBlobRead(token,true,token.bytes,'stream-done');return result;}
                          try{
                            const value=result&&result.value;
                            const n=value&&typeof value.byteLength==='number'?Number(value.byteLength):0;
                            token.bytes+=Math.max(0,n);
                            if(token.size>0){
                              const bucket=Math.floor(Math.min(1,token.bytes/token.size)*4);
                              if(bucket>token.lastBucket){token.lastBucket=bucket;emit('R21_ATTACHMENT_BLOB_STREAM_PROGRESS',{id:token.id,bytes:token.bytes,total:token.size,percent:Math.min(100,bucket*25)});}
                            }
                          }catch(_){}
                          return result;
                        },function(err){finishBlobRead(token,false,token.bytes,'stream-reject');throw err;});
                      };
                      wrappedRead.__aisR21DeepAttachment=true;reader.read=wrappedRead;
                    }
                    return reader;
                  };
                  wrappedGetReader.__aisR21DeepAttachment=true;ReadableStream.prototype.getReader=wrappedGetReader;
                }
              }
              if(window.FormData&&FormData.prototype){
                ['append','set'].forEach(function(name){
                  const current=FormData.prototype[name];if(!current||current.__aisR21DeepAttachment)return;
                  const wrapped=function(key,value){
                    try{if(expectedBlob(value)){fix.attachmentFormDataSeen+=1;emit('R21_ATTACHMENT_FORMDATA_FILE',{method:name,key:String(key||'').slice(0,120),size:Number(value&&value.size||-1),seen:fix.attachmentFormDataSeen});}}catch(_){}
                    return current.apply(this,arguments);
                  };
                  wrapped.__aisR21DeepAttachment=true;FormData.prototype[name]=wrapped;
                });
              }
              if(!fix.performanceObserverInstalled&&window.PerformanceObserver){
                try{
                  const po=new PerformanceObserver(function(list){
                    if(!attachmentWindowActive())return;
                    const entries=list.getEntries();
                    for(let i=0;i<entries.length;i++){
                      const e=entries[i];if(!e)continue;
                      const type=String(e.initiatorType||'');
                      if(!/(fetch|xmlhttprequest|other|beacon)/i.test(type))continue;
                      const hp=hostPath(e.name||'');
                      fix.attachmentPerformanceCount+=1;
                      fix.attachmentLastPerformance={host:hp.host,path:hp.path,initiatorType:type,duration:Math.round(Number(e.duration||0)),transferSize:Number(e.transferSize||0),encodedBodySize:Number(e.encodedBodySize||0),decodedBodySize:Number(e.decodedBodySize||0),responseStatus:Number(e.responseStatus||0)};
                      emit('R21_ATTACHMENT_RESOURCE_TIMING',Object.assign({count:fix.attachmentPerformanceCount},fix.attachmentLastPerformance));
                    }
                  });
                  po.observe({entryTypes:['resource']});
                  fix.performanceObserverInstalled=true;
                }catch(err){emit('R21_ATTACHMENT_RESOURCE_TIMING_ERROR',{error:String(err).slice(0,500)});}
              }
              fix.deepAttachmentObserverInstalled=true;
              emit('R21_ATTACHMENT_DEEP_OBSERVER_INSTALLED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R21_ATTACHMENT_DEEP_OBSERVER_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function attachmentDomEvidence() {
            let state='unknown',busy=false,error=false,progress=-1,readyAfterBusy=false,surfaceFound=false;
            try {
              const name=String(fix.attachmentExpectedName||'');
              if(!name)return {state:state,busy:busy,error:error,progress:progress,readyAfterBusy:false,surfaceFound:false};
              const nodes=document.querySelectorAll('span,div,p,[aria-label],[title]');
              let surface=null,bestChars=100000000;
              for(let i=0;i<nodes.length&&i<6000;i++){
                const n=nodes[i];if(!visible(n))continue;
                const text=[n.textContent||'',n.getAttribute&&n.getAttribute('aria-label')||'',n.getAttribute&&n.getAttribute('title')||''].join(' ');
                if(text.indexOf(name)<0)continue;
                if(text.length<bestChars){surface=n;bestChars=text.length;}
              }
              if(surface){
                surfaceFound=true;
                let root=surface;
                for(let i=0;i<7&&root&&root.parentElement;i++)root=root.parentElement;
                const text=[root&&root.textContent||'',root&&root.getAttribute&&root.getAttribute('aria-label')||'',root&&root.className||''].join(' ').replace(/\s+/g,' ').slice(0,5000);
                const busyNode=root&&root.querySelector&&root.querySelector('[aria-busy="true"],progress,[role="progressbar"],[class*="spinner"],[class*="loading"],[class*="progress"]');
                busy=!!busyNode||/(uploading|processing|loading|preparing|tải\s*(lên|tệp)|đang\s*(tải|xử lý|chuẩn bị))/i.test(text);
                error=/(upload\s*failed|failed\s*to\s*upload|error\s*upload|tải\s*(lên|tệp).*thất\s*bại|lỗi.*tải)/i.test(text);
                const bar=root&&root.querySelector&&root.querySelector('[role="progressbar"],[aria-valuenow],progress');
                if(bar){
                  const v=Number(bar.getAttribute&&bar.getAttribute('aria-valuenow')||bar.value||-1);
                  if(Number.isFinite(v))progress=v;
                }
              }
              if(busy)fix.attachmentDomBusySeen=true;
              if(error)fix.attachmentDomErrorSeen=true;
              if(fix.attachmentDomBusySeen&&!busy&&surfaceFound&&!error){fix.attachmentDomReadyAfterBusy=true;readyAfterBusy=true;}
              else readyAfterBusy=fix.attachmentDomReadyAfterBusy;
              state=error?'error':busy?'busy':readyAfterBusy?'ready-after-busy':surfaceFound?'attached':'missing';
              fix.attachmentDomProgress=progress;
              if(state!==fix.attachmentDomState){
                const previous=fix.attachmentDomState;fix.attachmentDomState=state;fix.attachmentDomTransitionCount+=1;
                emit('R21_ATTACHMENT_DOM_STATE',{previous:previous,state:state,busy:busy,error:error,progress:progress,busySeen:fix.attachmentDomBusySeen,readyAfterBusy:readyAfterBusy,transitions:fix.attachmentDomTransitionCount});
              }
            }catch(err){emit('R21_ATTACHMENT_DOM_PROBE_ERROR',{error:String(err).slice(0,500)});}
            return {state:state,busy:busy,error:error,progress:progress,readyAfterBusy:readyAfterBusy,surfaceFound:surfaceFound};
          }

'''
s = replace_once(s, insert_anchor, deep_code + insert_anchor, 'insert deep observer')
# Patch attachmentEvidence computation/output.
s = replace_once(s,
'''                const busy=!!support.busy,submitReady=!!submit.ready;\n                const attachmentPrepared=present&&!busy&&localReadReady&&submitReady;\n                const ready=attachmentPrepared;\n''',
'''                const dom=attachmentDomEvidence();\n                const blobReadReady=fix.attachmentBlobReadCompleted>0&&fix.attachmentBlobReadFailed===0;\n                const busy=!!support.busy||!!dom.busy,submitReady=!!submit.ready;\n                const attachmentPrepared=present&&!busy&&submitReady&&(localReadReady||blobReadReady||!!dom.readyAfterBusy||serverPayloadSettled);\n                const ready=attachmentPrepared&&!dom.error;\n''', 'attachment evidence readiness')
s = replace_once(s,
'''                  attachmentPrepared:attachmentPrepared,localReadReady:localReadReady,\n                  serverPayloadObserved:serverPayloadObserved,serverPayloadSettled:serverPayloadSettled,\n''',
'''                  attachmentPrepared:attachmentPrepared,localReadReady:localReadReady,blobReadReady:blobReadReady,\n                  blobReadStarted:fix.attachmentBlobReadStarted,blobReadCompleted:fix.attachmentBlobReadCompleted,blobReadFailed:fix.attachmentBlobReadFailed,blobReadBytes:fix.attachmentBlobReadBytes,\n                  formDataSeen:fix.attachmentFormDataSeen,performanceCount:fix.attachmentPerformanceCount,lastPerformance:fix.attachmentLastPerformance,\n                  domState:String(dom.state||''),domBusy:!!dom.busy,domBusySeen:fix.attachmentDomBusySeen,domReadyAfterBusy:!!dom.readyAfterBusy,domErrorSeen:fix.attachmentDomErrorSeen,domProgress:Number(dom.progress||-1),domTransitions:fix.attachmentDomTransitionCount,\n                  serverPayloadObserved:serverPayloadObserved,serverPayloadSettled:serverPayloadSettled,\n''', 'attachment evidence deep output')
s = replace_once(s,
'''            const fileReadOk = installFileReadObserver();\n            const apiOk = installApiPatch();\n''',
'''            const fileReadOk = installFileReadObserver();\n            const deepOk = installDeepAttachmentObserver();\n            const apiOk = installApiPatch();\n''', 'ensure deep observer 1')
s = replace_once(s,
'''            return clickOk && fileChangeOk && fileReadOk && apiOk && xhrOk && fetchOk && adaptiveOk;\n''',
'''            return clickOk && fileChangeOk && fileReadOk && deepOk && apiOk && xhrOk && fetchOk && adaptiveOk;\n''', 'ensure deep observer 2')
write(p, s)

# 5) Raise R20/R21 logs for video and file clients.
for p in [
    'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt',
    'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt',
]:
    s = read(p)
    s = s.replace('name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT")', 'name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT")')
    s = s.replace('name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")', 'name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")')
    write(p, s)

# 6) Update source tests for R18.11 invariants.
p = 'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt'
s = read(p)
s = s.replace('2026-09-05-web-session-r12.4-manual-video-native-file', '2026-09-05-web-session-r12.5-file-only-transcribe-video-probe')
s = s.replace('2026-09-05-web-session-r11.8-upload-rpc-trace', '2026-09-05-web-session-r11.9-blob-stream-dom-trace')
s = replace_once(s,
'''        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_RESULT"))\n        assertTrue(requestFix.contains("probeMatches"))\n''',
'''        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_RESULT"))\n        assertTrue(requestFix.contains("R21_ATTACHMENT_BLOB_READ_START"))\n        assertTrue(requestFix.contains("R21_ATTACHMENT_BLOB_STREAM_PROGRESS"))\n        assertTrue(requestFix.contains("R21_ATTACHMENT_FORMDATA_FILE"))\n        assertTrue(requestFix.contains("R21_ATTACHMENT_RESOURCE_TIMING"))\n        assertTrue(requestFix.contains("R21_ATTACHMENT_DOM_STATE"))\n        assertTrue(requestFix.contains("probeMatches"))\n''', 'test deep markers')
s = replace_once(s,
'''        assertTrue(submitFix.contains("preparePromptIfAttachment"))\n        assertTrue(src.contains("awaitManualAttachmentGenerate"))\n''',
'''        assertTrue(submitFix.contains("preparePromptIfAttachment"))\n        assertTrue(submitFix.contains("semanticSubmitCandidates"))\n        assertTrue(submitFix.contains("nativeTargetIfAttachmentFileOnly"))\n        assertTrue(src.contains("awaitManualAttachmentGenerate"))\n''', 'test semantic submit')
s = replace_once(s,
'''        assertTrue(src.contains("generateAttachmentNativeOnly"))\n        assertTrue(src.contains("allowProgrammaticFallback = false"))\n''',
'''        assertTrue(src.contains("generateAttachmentNativeOnly"))\n        assertTrue(src.contains("generateAttachmentFileOnlyNative"))\n        assertTrue(src.contains("file-transcribe-run"))\n        assertTrue(src.contains("allowProgrammaticFallback = false"))\n''', 'test file-only executor')
write(p, s)

# Add a dedicated source invariant test for no-prompt file transcribe.
p = 'app/src/test/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeNoPromptSourceTest.kt'
write(p, '''package com.oai.geminilivetranslate.network\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport java.io.File\n\nclass AiStudioFileTranscribeNoPromptSourceTest {\n    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))\n        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")\n\n    @Test\n    fun authenticatedFileTranscribeUsesFileOnlyModelInvocation() {\n        val src = source("src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt")\n        assertTrue(src.contains("generateAttachmentFileOnlyNative"))\n        assertTrue(src.contains("prompt=false"))\n        assertTrue(src.contains("parsePlainTranscript"))\n        assertFalse(src.contains("buildPrompt("))\n        assertFalse(src.contains("Hãy chép lời CHÍNH XÁC"))\n        assertFalse(src.contains("Chỉ trả về một JSON object"))\n    }\n}\n''')

print('R18.11 source patch applied')
