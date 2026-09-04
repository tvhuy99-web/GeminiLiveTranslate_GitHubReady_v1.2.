#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"
EXEC = ROOT / "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt"
FILE_CLIENT = ROOT / "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt"
TEST = ROOT / "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


src = REQ.read_text()
src = replace_once(
    src,
    'const val VERSION = "2026-09-05-web-session-r11.7-strict-upload-observation"',
    'const val VERSION = "2026-09-05-web-session-r11.8-upload-rpc-trace"',
    "version",
)

src = replace_once(
    src,
    """            attachmentFileReadCount: 0,\n            attachmentLastReadKind: '',\n            attachmentNetworkStarted: 0,""",
    """            attachmentFileReadCount: 0,\n            attachmentLastReadKind: '',\n            attachmentFileReadStarted: 0,\n            attachmentFileReadCompleted: 0,\n            attachmentFileReadFailed: 0,\n            attachmentFileReadBytes: -1,\n            attachmentFileReadResultChars: -1,\n            attachmentPayloadStarted: 0,\n            attachmentPayloadCompleted: 0,\n            attachmentPayloadFailed: 0,\n            attachmentPayloadActive: 0,\n            attachmentLastPayload: null,\n            attachmentNetworkStarted: 0,""",
    "state fields",
)

src = replace_once(
    src,
    """          const clickEntries = [];\n          let nextClickId = 1;""",
    """          const clickEntries = [];\n          let nextClickId = 1;\n          let attachmentDataProbe = null;\n          let nextPayloadId = 1;""",
    "private trace state",
)

src = replace_once(
    src,
    """            fix.attachmentFileReadCount = 0;\n            fix.attachmentLastReadKind = '';\n            fix.attachmentNetworkStarted = 0;""",
    """            fix.attachmentFileReadCount = 0;\n            fix.attachmentLastReadKind = '';\n            fix.attachmentFileReadStarted = 0;\n            fix.attachmentFileReadCompleted = 0;\n            fix.attachmentFileReadFailed = 0;\n            fix.attachmentFileReadBytes = -1;\n            fix.attachmentFileReadResultChars = -1;\n            fix.attachmentPayloadStarted = 0;\n            fix.attachmentPayloadCompleted = 0;\n            fix.attachmentPayloadFailed = 0;\n            fix.attachmentPayloadActive = 0;\n            fix.attachmentLastPayload = null;\n            attachmentDataProbe = null;\n            fix.attachmentNetworkStarted = 0;""",
    "reset trace state",
)

body_tail = """            return out;\n          }\n\n          function noteAttachmentNetStart(source, url, method, body) {"""
body_insert = r"""            return out;
          }

          function buildDataProbe(result) {
            try {
              if (typeof result !== 'string' || result.length < 128) return null;
              const comma = result.indexOf(',');
              const payload = comma >= 0 ? result.slice(comma + 1) : result;
              if (payload.length < 96) return null;
              const width = 24;
              const starts = [
                Math.max(0, Math.floor(payload.length * 0.08) - 12),
                Math.max(0, Math.floor(payload.length * 0.50) - 12),
                Math.max(0, Math.floor(payload.length * 0.92) - 12)
              ];
              const segments = starts.map(function(at){return payload.slice(at, at + width);}).filter(function(x){return x.length === width;});
              return {resultChars:result.length,payloadChars:payload.length,segments:segments};
            } catch (_) { return null; }
          }

          function probeMatchCount(text) {
            try {
              if (!attachmentDataProbe || !Array.isArray(attachmentDataProbe.segments) || typeof text !== 'string') return 0;
              let matches = 0;
              for (let i=0;i<attachmentDataProbe.segments.length;i++) if (text.indexOf(attachmentDataProbe.segments[i]) >= 0) matches += 1;
              return matches;
            } catch (_) { return 0; }
          }

          function attachmentPayloadMeta(body) {
            const meta = bodyMeta(body);
            let probeMatches = 0, likely = false, reason = 'none';
            try {
              const expected = Math.max(0, Number(fix.attachmentExpectedSize || 0));
              const readDone = Number(fix.attachmentFileReadCompleted || 0) > 0;
              if (typeof body === 'string') {
                probeMatches = probeMatchCount(body);
                if (probeMatches >= 2) { likely = true; reason = 'dataurl-probe'; }
                else if (readDone && expected > 0 && body.length >= Math.floor(expected * 1.20)) { likely = true; reason = 'large-string-after-file-read'; }
              } else if (meta.fileCount > 0 && expected > 0 && meta.fileBytes >= Math.floor(expected * 0.90)) {
                likely = true; reason = 'file-formdata-size';
              } else if (readDone && expected > 0 && meta.bytes >= Math.floor(expected * 0.90)) {
                likely = true; reason = 'binary-size-match';
              }
            } catch (_) {}
            return Object.assign({},meta,{probeMatches:probeMatches,likelyFilePayload:likely,reason:reason});
          }

          function markPayloadCandidate(token, url, source, method, body) {
            if (!token || token.payloadCandidate || !attachmentWindowActive()) return token;
            const pm = attachmentPayloadMeta(body);
            if (!pm.likelyFilePayload) return token;
            const hp = hostPath(url);
            token.payloadCandidate = true;
            token.payloadId = nextPayloadId++;
            token.payloadMeta = pm;
            token.payloadProgressBucket = -1;
            fix.attachmentPayloadStarted += 1;
            fix.attachmentPayloadActive += 1;
            fix.attachmentLastPayload = {
              id:token.payloadId,source:String(source||''),host:hp.host,path:hp.path,method:String(method||''),
              bodyKind:pm.kind,bodyBytes:pm.bytes,bodyChars:pm.chars,probeMatches:pm.probeMatches,reason:pm.reason,
              isGenerate:isGenerateUrl(url),startedAt:Date.now(),status:-1
            };
            emit('R20_ATTACHMENT_PAYLOAD_START',{
              id:token.payloadId,source:String(source||''),host:hp.host,path:hp.path,method:String(method||''),
              bodyKind:pm.kind,bodyBytes:pm.bytes,bodyChars:pm.chars,probeMatches:pm.probeMatches,reason:pm.reason,
              expectedSize:fix.attachmentExpectedSize,isGenerate:isGenerateUrl(url),active:fix.attachmentPayloadActive,started:fix.attachmentPayloadStarted
            });
            return token;
          }

          function noteAttachmentNetStart(source, url, method, body) {"""
src = replace_once(src, body_tail, body_insert, "payload helpers")

old_net_start = r"""          function noteAttachmentNetStart(source, url, method, body) {
            if (!attachmentWindowActive() || isGenerateUrl(url)) return null;
            const hp = hostPath(url);
            const meta = bodyMeta(body);
            fix.attachmentNetworkStarted += 1;
            const token = {source:String(source||''),host:hp.host,path:hp.path,method:String(method||''),meta:meta,at:Date.now(),done:false};
            fix.attachmentLastNet = token;
            emit('R11_ATTACHMENT_NET_REQUEST',{
              source:token.source,host:token.host,path:token.path,method:token.method,
              bodyKind:meta.kind,bodyBytes:meta.bytes,bodyChars:meta.chars,fileCount:meta.fileCount,fileBytes:meta.fileBytes,
              started:fix.attachmentNetworkStarted
            });
            return token;
          }

          function noteAttachmentNetDone(token, status) {
            if (!token || token.done) return;
            token.done = true;
            const s = Number(status || -1);
            const ok = s >= 200 && s < 400;
            if (ok) fix.attachmentNetworkCompleted += 1; else fix.attachmentNetworkFailed += 1;
            fix.attachmentLastNet = {source:token.source,host:token.host,path:token.path,method:token.method,status:s,ok:ok,finishedAt:Date.now()};
            emit('R11_ATTACHMENT_NET_RESULT',{
              source:token.source,host:token.host,path:token.path,method:token.method,status:s,ok:ok,
              completed:fix.attachmentNetworkCompleted,failed:fix.attachmentNetworkFailed
            });
          }"""
new_net_start = r"""          function noteAttachmentNetStart(source, url, method, body) {
            if (!attachmentWindowActive()) return null;
            const hp = hostPath(url);
            const meta = bodyMeta(body);
            fix.attachmentNetworkStarted += 1;
            const token = {source:String(source||''),host:hp.host,path:hp.path,method:String(method||''),meta:meta,at:Date.now(),done:false,payloadCandidate:false};
            fix.attachmentLastNet = token;
            emit('R11_ATTACHMENT_NET_REQUEST',{
              source:token.source,host:token.host,path:token.path,method:token.method,
              bodyKind:meta.kind,bodyBytes:meta.bytes,bodyChars:meta.chars,fileCount:meta.fileCount,fileBytes:meta.fileBytes,
              isGenerate:isGenerateUrl(url),started:fix.attachmentNetworkStarted
            });
            markPayloadCandidate(token,url,source,method,body);
            return token;
          }

          function noteAttachmentNetDone(token, status) {
            if (!token || token.done) return;
            token.done = true;
            const s = Number(status || -1);
            const ok = s >= 200 && s < 400;
            if (ok) fix.attachmentNetworkCompleted += 1; else fix.attachmentNetworkFailed += 1;
            fix.attachmentLastNet = {source:token.source,host:token.host,path:token.path,method:token.method,status:s,ok:ok,finishedAt:Date.now()};
            emit('R11_ATTACHMENT_NET_RESULT',{
              source:token.source,host:token.host,path:token.path,method:token.method,status:s,ok:ok,
              completed:fix.attachmentNetworkCompleted,failed:fix.attachmentNetworkFailed
            });
            if (token.payloadCandidate) {
              fix.attachmentPayloadActive = Math.max(0,fix.attachmentPayloadActive-1);
              if (ok) fix.attachmentPayloadCompleted += 1; else fix.attachmentPayloadFailed += 1;
              fix.attachmentLastPayload = {
                id:token.payloadId,source:token.source,host:token.host,path:token.path,method:token.method,
                status:s,ok:ok,finishedAt:Date.now(),reason:token.payloadMeta&&token.payloadMeta.reason||'unknown'
              };
              emit('R20_ATTACHMENT_PAYLOAD_RESULT',{
                id:token.payloadId,source:token.source,host:token.host,path:token.path,method:token.method,status:s,ok:ok,
                active:fix.attachmentPayloadActive,completed:fix.attachmentPayloadCompleted,failed:fix.attachmentPayloadFailed
              });
            }
          }"""
src = replace_once(src, old_net_start, new_net_start, "net trace functions")

old_xhr_listener = r"""                  if (netToken) {
                    const xhr = this;
                    xhr.addEventListener('loadend',function(){
                      let status=-1;try{status=Number(xhr.status||-1);}catch(_){}
                      noteAttachmentNetDone(netToken,status);
                    },{once:true});
                  }"""
new_xhr_listener = r"""                  if (netToken) {
                    const xhr = this;
                    if (netToken.payloadCandidate && xhr.upload && xhr.upload.addEventListener) {
                      xhr.upload.addEventListener('progress',function(ev){
                        try {
                          if (!ev || !ev.lengthComputable || Number(ev.total||0) <= 0) return;
                          const ratio = Math.max(0,Math.min(1,Number(ev.loaded||0)/Number(ev.total||1)));
                          const bucket = Math.floor(ratio * 10);
                          if (bucket === netToken.payloadProgressBucket) return;
                          netToken.payloadProgressBucket = bucket;
                          emit('R20_ATTACHMENT_PAYLOAD_PROGRESS',{
                            id:netToken.payloadId,loaded:Number(ev.loaded||0),total:Number(ev.total||0),percent:Math.round(ratio*100),
                            host:netToken.host,path:netToken.path
                          });
                        } catch (_) {}
                      },false);
                    }
                    xhr.addEventListener('loadend',function(){
                      let status=-1;try{status=Number(xhr.status||-1);}catch(_){}
                      noteAttachmentNetDone(netToken,status);
                    },{once:true});
                  }"""
src = replace_once(src, old_xhr_listener, new_xhr_listener, "xhr progress")

old_fetch_body = r"""                  body = init && Object.prototype.hasOwnProperty.call(init,'body') ? init.body : null;
                  token = noteAttachmentNetStart('fetch',url,method,body);
                } catch (_) {}
                const p = current.apply(this,arguments);"""
new_fetch_body = r"""                  body = init && Object.prototype.hasOwnProperty.call(init,'body') ? init.body : null;
                  token = noteAttachmentNetStart('fetch',url,method,body);
                  if (body == null && input && typeof input.clone === 'function' && typeof input.text === 'function') {
                    try {
                      input.clone().text().then(function(text){
                        if (!token || token.done) return;
                        markPayloadCandidate(token,url,'fetch-request-clone',method,text);
                        const pm = attachmentPayloadMeta(text);
                        emit('R20_FETCH_REQUEST_CLONE_META',{
                          host:hostPath(url).host,path:hostPath(url).path,chars:String(text||'').length,
                          likelyFilePayload:pm.likelyFilePayload,probeMatches:pm.probeMatches,reason:pm.reason
                        });
                      }).catch(function(){});
                    } catch (_) {}
                  }
                } catch (_) {}
                const p = current.apply(this,arguments);"""
src = replace_once(src, old_fetch_body, new_fetch_body, "fetch Request clone")

old_read = r"""                  const wrapped = function(blob) {
                    try {
                      if (attachmentWindowActive() && blob) {
                        fix.attachmentFileReadCount += 1;
                        fix.attachmentLastReadKind = name;
                        emit('R11_ATTACHMENT_FILE_READ',{
                          method:name,
                          name:String(blob.name||'').slice(0,260),
                          mime:String(blob.type||'').slice(0,180),
                          size:Number(blob.size||-1),
                          readCount:fix.attachmentFileReadCount
                        });
                      }
                    } catch (_) {}
                    return current.apply(this,arguments);
                  };"""
new_read = r"""                  const wrapped = function(blob) {
                    const reader = this;
                    let matched = false, startedAt = 0, settled = false, lastProgressBucket = -1;
                    try {
                      if (attachmentWindowActive() && blob) {
                        const blobName = String(blob.name||'');
                        const blobMime = String(blob.type||'');
                        const blobSize = Number(blob.size||-1);
                        matched = (blobName && blobName === fix.attachmentExpectedName) || (blobSize > 0 && blobSize === Number(fix.attachmentExpectedSize||-1));
                        fix.attachmentFileReadCount += 1;
                        fix.attachmentLastReadKind = name;
                        if (matched) {
                          fix.attachmentFileReadStarted += 1;
                          startedAt = Date.now();
                        }
                        emit('R11_ATTACHMENT_FILE_READ',{
                          method:name,name:blobName.slice(0,260),mime:blobMime.slice(0,180),size:blobSize,
                          readCount:fix.attachmentFileReadCount,matched:matched,started:fix.attachmentFileReadStarted
                        });
                        if (matched && reader && reader.addEventListener) {
                          reader.addEventListener('progress',function(ev){
                            try {
                              if (!ev || !ev.lengthComputable || Number(ev.total||0) <= 0) return;
                              const ratio = Math.max(0,Math.min(1,Number(ev.loaded||0)/Number(ev.total||1)));
                              const bucket = Math.floor(ratio * 4);
                              if (bucket === lastProgressBucket) return;
                              lastProgressBucket = bucket;
                              emit('R20_ATTACHMENT_FILE_READ_PROGRESS',{
                                method:name,loaded:Number(ev.loaded||0),total:Number(ev.total||0),percent:Math.round(ratio*100)
                              });
                            } catch (_) {}
                          },false);
                          reader.addEventListener('load',function(){
                            if (settled) return; settled = true;
                            try {
                              const result = reader.result;
                              const resultChars = typeof result === 'string' ? result.length : -1;
                              const resultBytes = result && typeof result !== 'string' && typeof result.byteLength === 'number' ? Number(result.byteLength) : Number(blob.size||-1);
                              if (name === 'readAsDataURL' && typeof result === 'string') attachmentDataProbe = buildDataProbe(result);
                              fix.attachmentFileReadCompleted += 1;
                              fix.attachmentFileReadBytes = resultBytes;
                              fix.attachmentFileReadResultChars = resultChars;
                              emit('R20_ATTACHMENT_FILE_READ_DONE',{
                                method:name,size:Number(blob.size||-1),resultKind:typeof result === 'string'?'string':'binary',
                                resultChars:resultChars,resultBytes:resultBytes,payloadChars:attachmentDataProbe?attachmentDataProbe.payloadChars:-1,
                                probeSegments:attachmentDataProbe&&attachmentDataProbe.segments?attachmentDataProbe.segments.length:0,
                                elapsedMs:startedAt?Date.now()-startedAt:-1,completed:fix.attachmentFileReadCompleted
                              });
                            } catch (_) {}
                          },{once:true});
                          const fail = function(kind){
                            if (settled) return; settled = true; fix.attachmentFileReadFailed += 1;
                            emit('R20_ATTACHMENT_FILE_READ_ERROR',{method:name,kind:kind,elapsedMs:startedAt?Date.now()-startedAt:-1,failed:fix.attachmentFileReadFailed});
                          };
                          reader.addEventListener('error',function(){fail('error');},{once:true});
                          reader.addEventListener('abort',function(){fail('abort');},{once:true});
                        }
                      }
                    } catch (_) {}
                    try { return current.apply(this,arguments); }
                    catch (err) {
                      if (matched && !settled) { settled = true; fix.attachmentFileReadFailed += 1; emit('R20_ATTACHMENT_FILE_READ_ERROR',{method:name,kind:'throw',failed:fix.attachmentFileReadFailed}); }
                      throw err;
                    }
                  };"""
src = replace_once(src, old_read, new_read, "FileReader lifecycle")

old_evidence = r"""                const present=attachmentPresent();
                const activeUploads=Number(support.activeUploads||0),uploadStarted=Number(support.uploadStarted||0),uploadCompleted=Number(support.uploadCompleted||0),uploadFailed=Number(support.uploadFailed||0);
                const uploadObserved=uploadStarted>0;
                const uploadSettled=uploadObserved&&activeUploads===0&&uploadFailed===0&&uploadCompleted>=uploadStarted;
                const busy=!!support.busy,submitReady=!!submit.ready;
                const ready=present&&!busy&&uploadSettled&&submitReady;
                return {
                  ok:true,version:fix.version,windowActive:attachmentWindowActive(),present:present,ready:ready,nameVisible:attachmentNameVisible(),busy:busy,submitReady:submitReady,
                  submitScore:Number(submit.score||-1),submitDisabled:!!submit.disabled,submitLabel:String(submit.label||'').slice(0,180),
                  uploadObserved:uploadObserved,uploadSettled:uploadSettled,activeUploads:activeUploads,uploadStarted:uploadStarted,uploadCompleted:uploadCompleted,uploadFailed:uploadFailed,"""
new_evidence = r"""                const present=attachmentPresent();
                const activeUploads=Number(support.activeUploads||0),uploadStarted=Number(support.uploadStarted||0),uploadCompleted=Number(support.uploadCompleted||0),uploadFailed=Number(support.uploadFailed||0);
                const uploadObserved=uploadStarted>0;
                const uploadSettled=uploadObserved&&activeUploads===0&&uploadFailed===0&&uploadCompleted>=uploadStarted;
                const localReadReady=fix.attachmentFileReadCompleted>0&&fix.attachmentFileReadFailed===0;
                const serverPayloadObserved=fix.attachmentPayloadStarted>0;
                const serverPayloadSettled=serverPayloadObserved&&fix.attachmentPayloadActive===0&&fix.attachmentPayloadFailed===0&&fix.attachmentPayloadCompleted>=fix.attachmentPayloadStarted;
                const busy=!!support.busy,submitReady=!!submit.ready;
                const attachmentPrepared=present&&!busy&&localReadReady&&submitReady;
                const ready=attachmentPrepared;
                return {
                  ok:true,version:fix.version,windowActive:attachmentWindowActive(),present:present,ready:ready,nameVisible:attachmentNameVisible(),busy:busy,submitReady:submitReady,
                  submitScore:Number(submit.score||-1),submitDisabled:!!submit.disabled,submitLabel:String(submit.label||'').slice(0,180),
                  attachmentPrepared:attachmentPrepared,localReadReady:localReadReady,
                  serverPayloadObserved:serverPayloadObserved,serverPayloadSettled:serverPayloadSettled,
                  payloadActive:fix.attachmentPayloadActive,payloadStarted:fix.attachmentPayloadStarted,payloadCompleted:fix.attachmentPayloadCompleted,payloadFailed:fix.attachmentPayloadFailed,
                  lastPayload:fix.attachmentLastPayload,
                  uploadObserved:uploadObserved,uploadSettled:uploadSettled,activeUploads:activeUploads,uploadStarted:uploadStarted,uploadCompleted:uploadCompleted,uploadFailed:uploadFailed,"""
src = replace_once(src, old_evidence, new_evidence, "readiness semantics")

src = replace_once(
    src,
    """                  fileReadCount:fix.attachmentFileReadCount,lastReadKind:fix.attachmentLastReadKind,\n                  networkStarted:fix.attachmentNetworkStarted,networkCompleted:fix.attachmentNetworkCompleted,networkFailed:fix.attachmentNetworkFailed,""",
    """                  fileReadCount:fix.attachmentFileReadCount,lastReadKind:fix.attachmentLastReadKind,\n                  fileReadStarted:fix.attachmentFileReadStarted,fileReadCompleted:fix.attachmentFileReadCompleted,fileReadFailed:fix.attachmentFileReadFailed,\n                  fileReadBytes:fix.attachmentFileReadBytes,fileReadResultChars:fix.attachmentFileReadResultChars,\n                  networkStarted:fix.attachmentNetworkStarted,networkCompleted:fix.attachmentNetworkCompleted,networkFailed:fix.attachmentNetworkFailed,""",
    "evidence read counters",
)

REQ.write_text(src)

exe = EXEC.read_text()
old_log = '''                    events?.onLog("R18_ATTACHMENT_UPLOAD_READY", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt}")'''
new_log = '''                    events?.onLog("R20_ATTACHMENT_PREPARED", "token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt} localReadReady=${obj?.optBoolean("localReadReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)}")'''
exe = replace_once(exe, old_log, new_log, "prepared log")
old_wait = '''                    events?.onLog("R18_ATTACHMENT_WAIT_UPLOAD", "token=$token busy=${obj?.optBoolean("busy", false)} uploadObserved=${obj?.optBoolean("uploadObserved", false)} uploadSettled=${obj?.optBoolean("uploadSettled", false)} submitReady=${obj?.optBoolean("submitReady", false)} activeUploads=${obj?.optInt("activeUploads", 0)} started=${obj?.optInt("uploadStarted", 0)} completed=${obj?.optInt("uploadCompleted", 0)} failed=${obj?.optInt("uploadFailed", 0)}")'''
new_wait = '''                    events?.onLog("R20_ATTACHMENT_WAIT_PREPARED", "token=$token busy=${obj?.optBoolean("busy", false)} present=$present localReadReady=${obj?.optBoolean("localReadReady", false)} attachmentPrepared=${obj?.optBoolean("attachmentPrepared", false)} submitReady=${obj?.optBoolean("submitReady", false)} serverPayloadObserved=${obj?.optBoolean("serverPayloadObserved", false)} serverPayloadSettled=${obj?.optBoolean("serverPayloadSettled", false)} payloadActive=${obj?.optInt("payloadActive", 0)} payloadStarted=${obj?.optInt("payloadStarted", 0)} payloadCompleted=${obj?.optInt("payloadCompleted", 0)} payloadFailed=${obj?.optInt("payloadFailed", 0)}")'''
exe = replace_once(exe, old_wait, new_wait, "wait log")
EXEC.write_text(exe)

client = FILE_CLIENT.read_text()
client = client.replace('onProgress("Đang tải tệp lên AI Studio...", 8)', 'onProgress("Đang đưa tệp vào AI Studio và chờ trang đọc xong...", 8)')
client = client.replace('logger.log(2, TAG, "ATTACHMENT_READY model=$model name=$name")', 'logger.log(2, TAG, "ATTACHMENT_PREPARED model=$model name=$name")')
FILE_CLIENT.write_text(client)

test = TEST.read_text()
test = test.replace('assertTrue(requestFix.contains("uploadObserved=uploadStarted>0"))\n        assertTrue(requestFix.contains("uploadSettled=uploadObserved"))',
'''assertTrue(requestFix.contains("2026-09-05-web-session-r11.8-upload-rpc-trace"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_FILE_READ_DONE"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_START"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_PROGRESS"))
        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_RESULT"))
        assertTrue(requestFix.contains("probeMatches"))
        assertTrue(requestFix.contains("localReadReady=fix.attachmentFileReadCompleted>0"))
        assertTrue(requestFix.contains("attachmentPrepared=present&&!busy&&localReadReady&&submitReady"))
        assertTrue(requestFix.contains("serverPayloadObserved=fix.attachmentPayloadStarted>0"))''')
test = test.replace('assertTrue(src.contains("R18_ATTACHMENT_WAIT_UPLOAD"))\n        assertTrue(src.contains("R18_ATTACHMENT_UPLOAD_READY"))',
'''assertTrue(src.contains("R20_ATTACHMENT_WAIT_PREPARED"))
        assertTrue(src.contains("R20_ATTACHMENT_PREPARED"))''')
TEST.write_text(test)

print("R18.10 upload/RPC trace patch applied")
