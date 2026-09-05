package com.oai.geminilivetranslate.ui

/**
 * R11 request hardening layer.
 *
 * R11.1 moved model selection to the authenticated request layer and solved WebView file chooser
 * activation. R11.3 adds the device-found missing piece for attachments: AI Studio can keep the
 * prompt controller READY and expose an enabled Send/Run button while the old text-only Enter
 * handler no longer starts GenerateContent. This layer therefore observes the real file-change /
 * file-read / network lifecycle and adds an attachment-only programmatic Send/Run fallback.
 *
 * The fallback never runs for ordinary text generation. It is armed only after Android has served
 * a file URI and the attachment is still visible/observed in the current AI Studio composer.
 * No cookie, Authorization value, Google password, API-key value, or file bytes are exported.
 */
object AiStudioWebSessionR11RequestFix {
    const val VERSION = "2026-09-05-web-session-r11.10-manual-config-trace"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_R11_REQUEST_FIX__ && window.__AIS_R11_REQUEST_FIX__.version === '$VERSION') return;

          const fix = {
            version: '$VERSION',
            requestedModel: '',
            selectedModel: '',
            rewriteCount: 0,
            lastOriginalModel: '',
            lastAppliedModel: '',
            modelPatchInstalled: false,
            xhrRewriteInstalled: false,
            fetchObserverInstalled: false,
            clickTrackingInstalled: false,
            adaptiveFallbackInstalled: false,
            fileReadObserverInstalled: false,
            deepAttachmentObserverInstalled: false,
            performanceObserverInstalled: false,
            fileArmToken: 0,
            fileArmed: false,
            trustedActivationCount: 0,
            lastTrustedActivationAt: 0,
            attachmentWindowUntil: 0,
            attachmentExpectedName: '',
            attachmentExpectedMime: '',
            attachmentExpectedSize: -1,
            attachmentFileChangeCount: 0,
            attachmentFileChangeMatched: false,
            attachmentLastChangedName: '',
            attachmentLastChangedMime: '',
            attachmentLastChangedSize: -1,
            attachmentFileReadCount: 0,
            attachmentLastReadKind: '',
            attachmentFileReadStarted: 0,
            attachmentFileReadCompleted: 0,
            attachmentFileReadFailed: 0,
            attachmentFileReadBytes: -1,
            attachmentFileReadResultChars: -1,
            attachmentBlobReadStarted: 0,
            attachmentBlobReadCompleted: 0,
            attachmentBlobReadFailed: 0,
            attachmentBlobReadBytes: 0,
            attachmentFormDataSeen: 0,
            attachmentPerformanceCount: 0,
            attachmentLastPerformance: null,
            attachmentDomState: 'unknown',
            attachmentDomBusySeen: false,
            attachmentDomReadyAfterBusy: false,
            attachmentDomErrorSeen: false,
            attachmentDomProgress: -1,
            attachmentDomTransitionCount: 0,
            attachmentPayloadStarted: 0,
            attachmentPayloadCompleted: 0,
            attachmentPayloadFailed: 0,
            attachmentPayloadActive: 0,
            attachmentLastPayload: null,
            attachmentNetworkStarted: 0,
            attachmentNetworkCompleted: 0,
            attachmentNetworkFailed: 0,
            attachmentSubmitFallbacks: 0,
            attachmentSubmitButtonClicks: 0,
            attachmentSubmitListenerInvokes: 0,
            attachmentLastSubmitLabel: '',
            attachmentLastSubmitPath: '',
            attachmentLastNet: null
          };

          const clickEntries = [];
          let nextClickId = 1;
          let attachmentDataProbe = null;
          let nextPayloadId = 1;

          function emit(kind, payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function normalizeModel(raw) {
            return String(raw || '').trim().replace(/^models\//i, '').replace(/[\"'`,;:)\]}]+$/g, '').slice(0,120);
          }

          function firstModel(text) {
            const m = String(text || '').match(/(?:models\/)?gemini-[a-z0-9][a-z0-9._-]{2,110}/i);
            return m ? normalizeModel(m[0]) : '';
          }

          function isGenerateUrl(raw) {
            const s = String(raw || '');
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(s) || /\/GenerateContent(?:[/?]|$)/i.test(s);
          }

          function sanitizeTraceText(raw) {
            try {
              let s = String(raw || '');
              s = s.replace(/data:[^,]{0,160};base64,[A-Za-z0-9+\/_=-]{64,}/gi,'<DATA_URL_REDACTED>');
              s = s.replace(/AIza[0-9A-Za-z_-]{20,}/g,'<API_KEY_REDACTED>');
              s = s.replace(/ya29\.[0-9A-Za-z._-]+/g,'<OAUTH_REDACTED>');
              s = s.replace(/Bearer\s+[A-Za-z0-9._~+\/=-]+/gi,'Bearer <REDACTED>');
              s = s.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi,'<EMAIL_REDACTED>');
              s = s.replace(/[A-Za-z0-9+\/_=-]{256,}/g,function(m){return '<LONG_TOKEN_'+m.length+'>';});
              return s.slice(0,1800);
            } catch (_) { return ''; }
          }

          function emitGenerateRequestShape(source, url, body, stage) {
            try {
              if (!isGenerateUrl(url) || typeof body !== 'string') return;
              const hp = hostPath(url);
              const models = [];
              const seen = Object.create(null);
              const re = /(?:models\/)?gemini-[a-z0-9][a-z0-9._-]{2,110}/ig;
              let m;
              while ((m = re.exec(body)) && models.length < 8) {
                const id = normalizeModel(m[0]);
                if (id && !seen[id]) { seen[id] = true; models.push(id); }
              }
              emit('R22_GENERATE_REQUEST_SHAPE',{
                stage:String(stage||''),source:String(source||''),host:hp.host,path:hp.path,bodyChars:body.length,
                selectedModel:String(fix.selectedModel||''),models:models,
                hasTranscriptionConfig:/transcription[_-]?config|transcriptionConfig/i.test(body),
                hasDiarization:/diarization|speaker[_-]?separation|speakerDiarization/i.test(body),
                hasLanguageCodes:/language[_-]?codes|languageCodes/i.test(body),
                hasTimestamp:/timestamp[_-]?granular|timestampGranular/i.test(body),
                hasAudioMime:/audio\//i.test(body),hasVideoMime:/video\//i.test(body),
                hasDriveRef:/drive|resource[_-]?name|file[_-]?(?:uri|id)|attachment/i.test(body),
                preview:sanitizeTraceText(body)
              });
            } catch (err) { emit('R22_GENERATE_REQUEST_SHAPE_ERROR',{error:String(err).slice(0,500)}); }
          }

          function hostPath(raw) {
            try {
              const u = new URL(String(raw || ''), location.href);
              return {host:String(u.host||'').slice(0,160),path:String(u.pathname||'').slice(0,500)};
            } catch (_) { return {host:'',path:''}; }
          }

          function visible(el) {
            try {
              if (!el || !el.isConnected) return false;
              const r = el.getBoundingClientRect();
              const s = getComputedStyle(el);
              return r.width >= 1 && r.height >= 1 && s.display !== 'none' && s.visibility !== 'hidden';
            } catch (_) { return false; }
          }

          function labelOf(el) {
            try {
              return [
                el && el.textContent || '',
                el && el.getAttribute && el.getAttribute('aria-label') || '',
                el && el.getAttribute && el.getAttribute('title') || '',
                el && el.getAttribute && el.getAttribute('data-tooltip') || ''
              ].join(' ').replace(/\s+/g,' ').trim().slice(0,500);
            } catch (_) { return ''; }
          }

          function attachmentWindowActive() {
            return Date.now() <= Number(fix.attachmentWindowUntil || 0) && !!fix.attachmentExpectedName;
          }

          function attachmentNameVisible() {
            const name = String(fix.attachmentExpectedName || '');
            if (!name) return false;
            try {
              const text = String(document.body && document.body.innerText || '').slice(-220000);
              return text.indexOf(name) >= 0;
            } catch (_) { return false; }
          }

          function attachmentPresent() {
            return attachmentWindowActive() && (fix.attachmentFileChangeMatched || attachmentNameVisible());
          }

          function resetAttachmentObservation(name, mime, size) {
            fix.attachmentWindowUntil = Date.now() + 300000;
            fix.attachmentExpectedName = String(name || '').slice(0,260);
            fix.attachmentExpectedMime = String(mime || '').slice(0,180);
            fix.attachmentExpectedSize = Number(size || -1);
            fix.attachmentFileChangeCount = 0;
            fix.attachmentFileChangeMatched = false;
            fix.attachmentLastChangedName = '';
            fix.attachmentLastChangedMime = '';
            fix.attachmentLastChangedSize = -1;
            fix.attachmentFileReadCount = 0;
            fix.attachmentLastReadKind = '';
            fix.attachmentFileReadStarted = 0;
            fix.attachmentFileReadCompleted = 0;
            fix.attachmentFileReadFailed = 0;
            fix.attachmentFileReadBytes = -1;
            fix.attachmentFileReadResultChars = -1;
            fix.attachmentBlobReadStarted = 0;
            fix.attachmentBlobReadCompleted = 0;
            fix.attachmentBlobReadFailed = 0;
            fix.attachmentBlobReadBytes = 0;
            fix.attachmentFormDataSeen = 0;
            fix.attachmentPerformanceCount = 0;
            fix.attachmentLastPerformance = null;
            fix.attachmentDomState = 'unknown';
            fix.attachmentDomBusySeen = false;
            fix.attachmentDomReadyAfterBusy = false;
            fix.attachmentDomErrorSeen = false;
            fix.attachmentDomProgress = -1;
            fix.attachmentDomTransitionCount = 0;
            fix.attachmentPayloadStarted = 0;
            fix.attachmentPayloadCompleted = 0;
            fix.attachmentPayloadFailed = 0;
            fix.attachmentPayloadActive = 0;
            fix.attachmentLastPayload = null;
            attachmentDataProbe = null;
            fix.attachmentNetworkStarted = 0;
            fix.attachmentNetworkCompleted = 0;
            fix.attachmentNetworkFailed = 0;
            fix.attachmentSubmitFallbacks = 0;
            fix.attachmentSubmitButtonClicks = 0;
            fix.attachmentSubmitListenerInvokes = 0;
            fix.attachmentLastSubmitLabel = '';
            fix.attachmentLastSubmitPath = '';
            fix.attachmentLastNet = null;
            emit('R11_ATTACHMENT_OBSERVATION_START',{
              name:fix.attachmentExpectedName,
              mime:fix.attachmentExpectedMime,
              size:fix.attachmentExpectedSize,
              windowMs:300000
            });
          }

          function bodyMeta(body) {
            const out = {kind:'none',bytes:-1,chars:-1,fileCount:0,fileBytes:0};
            try {
              if (body == null) return out;
              if (typeof body === 'string') { out.kind='string'; out.chars=body.length; return out; }
              if (typeof File !== 'undefined' && body instanceof File) {
                out.kind='file';out.bytes=Number(body.size||-1);out.fileCount=1;out.fileBytes=Math.max(0,Number(body.size||0));return out;
              }
              if (typeof Blob !== 'undefined' && body instanceof Blob) {
                out.kind='blob';out.bytes=Number(body.size||-1);return out;
              }
              if (typeof FormData !== 'undefined' && body instanceof FormData) {
                out.kind='formdata';
                for (const pair of body.entries()) {
                  const value = pair[1];
                  if ((typeof File !== 'undefined' && value instanceof File) || (typeof Blob !== 'undefined' && value instanceof Blob)) {
                    out.fileCount += 1; out.fileBytes += Math.max(0,Number(value.size||0));
                  }
                }
                return out;
              }
              if (typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer) { out.kind='arraybuffer';out.bytes=body.byteLength;return out; }
              if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(body)) { out.kind='typedarray';out.bytes=body.byteLength;return out; }
              if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) { out.kind='urlsearchparams';out.chars=String(body).length;return out; }
              out.kind = Object.prototype.toString.call(body).slice(8,-1).toLowerCase().slice(0,80);
            } catch (_) {}
            return out;
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

          function noteAttachmentNetStart(source, url, method, body) {
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
          }

          function rewriteBody(url, body, source) {
            if (typeof body !== 'string' || !isGenerateUrl(url)) return body;
            const original = firstModel(body);
            let rewritten = body;
            if (fix.selectedModel && original) {
              if (original !== fix.selectedModel) rewritten = body.split(original).join(fix.selectedModel);
              fix.lastOriginalModel = original;
              fix.lastAppliedModel = fix.selectedModel;
              fix.rewriteCount += 1;
              emit('R11_GENERATE_MODEL_REWRITE',{
                source:source,originalModel:original,targetModel:fix.selectedModel,changed:rewritten!==body,
                rewriteCount:fix.rewriteCount,bodyChars:rewritten.length
              });
            } else if (!original) {
              emit('R11_MODEL_REWRITE_SKIPPED',{reason:'MODEL_NOT_FOUND_IN_BODY',target:fix.selectedModel,source:source,bodyChars:body.length});
            }
            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');
            return rewritten;
          }

          function installXhrRewrite() {
            try {
              if (!window.__AIS_R11_SUPPORT__ || !window.XMLHttpRequest || !XMLHttpRequest.prototype) return false;
              const proto = XMLHttpRequest.prototype;
              const current = proto.send;
              if (!current || current.__aisR11RequestFix === true) {
                fix.xhrRewriteInstalled = !!current;
                return !!current;
              }
              const wrapped = function(body) {
                let nextBody = body;
                let netToken = null;
                try {
                  const meta = this.__aisR11 || {};
                  nextBody = rewriteBody(meta.url || '', body, 'xhr');
                  netToken = noteAttachmentNetStart('xhr',meta.url||'',meta.method||'POST',nextBody);
                  if (netToken) {
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
                      try {
                        if (isGenerateUrl(meta.url||'') && status >= 400) {
                          let text=''; try { text=String(xhr.responseText||''); } catch (_) {}
                          emit('R22_GENERATE_RESPONSE_ERROR',{
                            source:'xhr',host:hostPath(meta.url||'').host,path:hostPath(meta.url||'').path,status:status,
                            responseChars:text.length,preview:sanitizeTraceText(text)
                          });
                        }
                      } catch (_) {}
                    },{once:true});
                  }
                } catch (err) {
                  emit('R11_MODEL_REWRITE_ERROR',{source:'xhr',error:String(err).slice(0,800)});
                }
                return current.call(this,nextBody);
              };
              wrapped.__aisR11RequestFix = true;
              proto.send = wrapped;
              fix.xhrRewriteInstalled = true;
              emit('R11_XHR_MODEL_REWRITE_INSTALLED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_XHR_MODEL_REWRITE_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function installFetchObserver() {
            try {
              const current = window.fetch;
              if (!current) return false;
              if (current.__aisR11AttachmentFetch === true) { fix.fetchObserverInstalled=true; return true; }
              const wrapped = function(input, init) {
                let url='',method='GET',body=null,token=null;
                try {
                  url = typeof input === 'string' ? input : (input && input.url) || '';
                  method = String(init && init.method || input && input.method || 'GET');
                  body = init && Object.prototype.hasOwnProperty.call(init,'body') ? init.body : null;
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
                const p = current.apply(this,arguments);
                if (token && p && typeof p.then === 'function') {
                  p.then(function(resp){noteAttachmentNetDone(token,Number(resp&&resp.status||-1));})
                   .catch(function(){noteAttachmentNetDone(token,-1);});
                }
                return p;
              };
              wrapped.__aisR11AttachmentFetch = true;
              window.fetch = wrapped;
              fix.fetchObserverInstalled = true;
              emit('R11_ATTACHMENT_FETCH_OBSERVER_INSTALLED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_ATTACHMENT_FETCH_OBSERVER_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function installFileReadObserver() {
            if (fix.fileReadObserverInstalled) return true;
            try {
              if (window.FileReader && FileReader.prototype) {
                ['readAsArrayBuffer','readAsDataURL','readAsText'].forEach(function(name){
                  const current = FileReader.prototype[name];
                  if (!current || current.__aisR11FileRead === true) return;
                  const wrapped = function(blob) {
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
                  };
                  wrapped.__aisR11FileRead = true;
                  FileReader.prototype[name] = wrapped;
                });
              }
              if (window.Blob && Blob.prototype && Blob.prototype.arrayBuffer && Blob.prototype.arrayBuffer.__aisR11FileRead !== true) {
                const currentArrayBuffer = Blob.prototype.arrayBuffer;
                const wrappedArrayBuffer = function() {
                  try {
                    if (attachmentWindowActive()) {
                      fix.attachmentFileReadCount += 1;
                      fix.attachmentLastReadKind = 'blob.arrayBuffer';
                      emit('R11_ATTACHMENT_FILE_READ',{
                        method:'blob.arrayBuffer',name:String(this&&this.name||'').slice(0,260),
                        mime:String(this&&this.type||'').slice(0,180),size:Number(this&&this.size||-1),readCount:fix.attachmentFileReadCount
                      });
                    }
                  } catch (_) {}
                  return currentArrayBuffer.apply(this,arguments);
                };
                wrappedArrayBuffer.__aisR11FileRead = true;
                Blob.prototype.arrayBuffer = wrappedArrayBuffer;
              }
              fix.fileReadObserverInstalled = true;
              emit('R11_ATTACHMENT_FILE_READ_OBSERVER_INSTALLED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_ATTACHMENT_FILE_READ_OBSERVER_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          const trackedAttachmentStreams = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
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

          function installFileChangeObserver() {
            try {
              if (document.__aisR11FileChangeObserver === true) return true;
              const handler = function(ev) {
                try {
                  const target = ev && ev.target;
                  if (!target || String(target.type||'').toLowerCase() !== 'file') return;
                  const files = target.files;
                  const count = files ? files.length : 0;
                  fix.attachmentFileChangeCount += 1;
                  let matched = false, firstName='',firstMime='',firstSize=-1;
                  for (let i=0;i<count;i++) {
                    const f=files[i];
                    const name=String(f&&f.name||'');
                    if (!firstName) { firstName=name;firstMime=String(f&&f.type||'');firstSize=Number(f&&f.size||-1); }
                    if (name && name === fix.attachmentExpectedName) matched = true;
                  }
                  if (matched) fix.attachmentFileChangeMatched = true;
                  fix.attachmentLastChangedName = firstName.slice(0,260);
                  fix.attachmentLastChangedMime = firstMime.slice(0,180);
                  fix.attachmentLastChangedSize = firstSize;
                  emit('R11_ATTACHMENT_FILE_CHANGE',{
                    isTrusted:!!(ev&&ev.isTrusted),fileCount:count,matched:matched,
                    firstName:fix.attachmentLastChangedName,firstMime:fix.attachmentLastChangedMime,firstSize:firstSize,
                    changeCount:fix.attachmentFileChangeCount
                  });
                } catch (err) { emit('R11_ATTACHMENT_FILE_CHANGE_ERROR',{error:String(err).slice(0,800)}); }
              };
              document.addEventListener('change',handler,true);
              document.__aisR11FileChangeObserver = true;
              emit('R11_ATTACHMENT_FILE_CHANGE_OBSERVER_INSTALLED',{version:fix.version});
              return true;
            } catch (_) { return false; }
          }

          function installClickTracking() {
            if (fix.clickTrackingInstalled) return true;
            try {
              if (!window.EventTarget || !EventTarget.prototype) return false;
              const proto = EventTarget.prototype;
              const currentAdd = proto.addEventListener;
              const currentRemove = proto.removeEventListener;
              if (currentAdd && currentAdd.__aisR11ClickTracking === true) { fix.clickTrackingInstalled=true; return true; }

              const addWrapped = function(type, listener, options) {
                try {
                  if (String(type||'') === 'click' && listener && clickEntries.length < 2400) {
                    clickEntries.push({id:nextClickId++,target:this,listener:listener,options:options,active:true,at:Date.now()});
                  }
                } catch (_) {}
                return currentAdd.apply(this,arguments);
              };
              addWrapped.__aisR11ClickTracking = true;
              proto.addEventListener = addWrapped;

              if (currentRemove) {
                const removeWrapped = function(type, listener, options) {
                  try {
                    if (String(type||'') === 'click' && listener) {
                      for (let i=clickEntries.length-1;i>=0;i--) {
                        const e=clickEntries[i];
                        if (e.active && e.target===this && e.listener===listener) { e.active=false; break; }
                      }
                    }
                  } catch (_) {}
                  return currentRemove.apply(this,arguments);
                };
                removeWrapped.__aisR11ClickTracking = true;
                proto.removeEventListener = removeWrapped;
              }
              fix.clickTrackingInstalled = true;
              emit('R11_ATTACHMENT_CLICK_TRACKING_INSTALLED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_ATTACHMENT_CLICK_TRACKING_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function clickRelationScore(entry, button) {
            if (!entry || !entry.active) return -100000;
            try {
              if (entry.target === button) return 1800;
              if (entry.target === button.parentElement) return 1100;
              if (entry.target && typeof entry.target.contains === 'function' && entry.target.contains(button)) return 900;
              if (entry.target === document.body) return 450;
              if (entry.target === document) return 400;
              if (entry.target === window) return 350;
            } catch (_) {}
            return -100000;
          }

          function sendButtonCandidates() {
            const out=[];
            try {
              const nodes=document.querySelectorAll('button,[role="button"]');
              for(let i=0;i<nodes.length&&i<1800;i++){
                const b=nodes[i];if(!visible(b))continue;
                const label=labelOf(b);if(!/(^|\b)(send|run|submit|gửi|chạy)(\b|$)/i.test(label))continue;
                const disabled=!!b.disabled||String(b.getAttribute&&b.getAttribute('aria-disabled')||'').toLowerCase()==='true';
                let score=disabled?-2000:1000;
                if(String(b.tagName||'')==='BUTTON')score+=180;
                if(String(b.getAttribute&&b.getAttribute('type')||'').toLowerCase()==='submit')score+=260;
                if(/(^|\b)(send|gửi)(\b|$)/i.test(label))score+=420;
                if(/(^|\b)(run|chạy)(\b|$)/i.test(label))score+=300;
                let listenerScore=-100000,listenerCount=0;
                for(let j=0;j<clickEntries.length;j++){
                  const s=clickRelationScore(clickEntries[j],b);
                  if(s>-100000){listenerCount+=1;if(s>listenerScore)listenerScore=s;}
                }
                if(listenerScore>-100000)score+=Math.min(900,listenerScore/2);
                out.push({button:b,label:label,disabled:disabled,score:score,listenerCount:listenerCount,listenerScore:listenerScore});
              }
            } catch (_) {}
            out.sort(function(a,b){return b.score-a.score;});
            return out;
          }

          function invokeClickListener(entry, button) {
            try {
              let ev = new MouseEvent('click',{bubbles:true,cancelable:true,composed:true,view:window});
              try {
                ev = new Proxy(ev,{get:function(obj,prop){
                  if(prop==='target'||prop==='srcElement')return button;
                  if(prop==='currentTarget')return entry.target;
                  if(prop==='composedPath')return function(){
                    const path=[];let n=button;while(n){path.push(n);n=n.parentNode||n.host||null;}path.push(document);path.push(window);return path;
                  };
                  const v=Reflect.get(obj,prop,obj);return typeof v==='function'?v.bind(obj):v;
                }});
              } catch (_) {}
              if(typeof entry.listener==='function')entry.listener.call(entry.target,ev);
              else if(entry.listener&&typeof entry.listener.handleEvent==='function')entry.listener.handleEvent.call(entry.listener,ev);
              else return false;
              fix.attachmentSubmitListenerInvokes += 1;
              return true;
            } catch (err) {
              emit('R11_ATTACHMENT_CLICK_LISTENER_ERROR',{entryId:Number(entry&&entry.id||-1),error:String(err).slice(0,800)});
              return false;
            }
          }

          function submitAttachmentViaButton(reason) {
            const net=window.__AIS_WEB_SESSION__;
            const baseline=Number(net&&net.captureCount||0);
            const candidates=sendButtonCandidates();
            emit('R11_ATTACHMENT_SEND_CANDIDATES',{
              reason:String(reason||''),baselineCaptureCount:baseline,count:candidates.length,
              top:candidates.slice(0,6).map(function(x){return {label:x.label.slice(0,180),disabled:x.disabled,score:x.score,listenerCount:x.listenerCount,listenerScore:x.listenerScore};})
            });
            if(!candidates.length)return {ok:false,error:'SEND_BUTTON_NOT_FOUND',baselineCaptureCount:baseline};
            const best=candidates[0];
            if(best.disabled)return {ok:false,error:'SEND_BUTTON_DISABLED',label:best.label.slice(0,180),baselineCaptureCount:baseline};
            fix.attachmentSubmitFallbacks += 1;
            fix.attachmentLastSubmitLabel = best.label.slice(0,260);

            let clicked=false,error='';
            try {
              if(window.HTMLElement&&HTMLElement.prototype&&HTMLElement.prototype.click)HTMLElement.prototype.click.call(best.button);
              else best.button.click();
              clicked=true;
              fix.attachmentSubmitButtonClicks += 1;
              fix.attachmentLastSubmitPath='programmatic-button-click';
            } catch(err){error=String(err).slice(0,800);}
            emit('R11_ATTACHMENT_SEND_CLICK',{
              ok:clicked,reason:String(reason||''),label:best.label.slice(0,180),score:best.score,
              listenerCount:best.listenerCount,baselineCaptureCount:baseline,error:error
            });

            setTimeout(function(){
              const now=Number(net&&net.captureCount||0);
              if(now>baseline){
                emit('R11_ATTACHMENT_SEND_RESULT',{ok:true,path:'programmatic-button-click',baselineCaptureCount:baseline,captureCount:now,label:best.label.slice(0,180)});
                return;
              }
              const supports=clickEntries.map(function(e){return {entry:e,score:clickRelationScore(e,best.button)};})
                .filter(function(x){return x.entry.active&&x.score>=350;}).sort(function(a,b){return b.score-a.score;}).slice(0,10);
              let invoked=0;
              for(let i=0;i<supports.length;i++)if(invokeClickListener(supports[i].entry,best.button))invoked+=1;
              if(invoked>0)fix.attachmentLastSubmitPath='direct-click-listener';
              emit('R11_ATTACHMENT_SEND_LISTENER_FALLBACK',{
                invoked:invoked,label:best.label.slice(0,180),baselineCaptureCount:baseline,captureCount:Number(net&&net.captureCount||0),
                support:supports.map(function(x){return {entryId:x.entry.id,score:x.score};})
              });
              setTimeout(function(){
                const finalCount=Number(net&&net.captureCount||0);
                emit('R11_ATTACHMENT_SEND_RESULT',{
                  ok:finalCount>baseline,path:fix.attachmentLastSubmitPath,baselineCaptureCount:baseline,captureCount:finalCount,
                  label:best.label.slice(0,180),listenerInvokes:invoked
                });
              },280);
            },320);
            return {ok:clicked,pending:true,label:best.label.slice(0,180),score:best.score,baselineCaptureCount:baseline};
          }

          function installAdaptiveFallback() {
            try {
              const runtime=window.__AIS_ADAPTIVE_RUNTIME__;
              if(!runtime||typeof runtime.generate!=='function')return false;
              if(runtime.generate.__aisR11AttachmentFallback===true){fix.adaptiveFallbackInstalled=true;return true;}
              const original=runtime.generate;
              const wrapped=function(prompt,marker){
                const net=window.__AIS_WEB_SESSION__;
                const baseline=Number(net&&net.captureCount||0);
                const result=original.apply(this,arguments);
                if(result&&result.ok&&attachmentPresent()){
                  emit('R11_ATTACHMENT_GENERATE_ARMED',{
                    baselineCaptureCount:baseline,expectedName:fix.attachmentExpectedName,
                    fileChangeMatched:fix.attachmentFileChangeMatched,nameVisible:attachmentNameVisible()
                  });
                  setTimeout(function(){
                    const now=Number(net&&net.captureCount||0);
                    if(now>baseline){
                      emit('R11_ATTACHMENT_GENERATE_FALLBACK_SKIP',{reason:'REQUEST_ALREADY_STARTED',baselineCaptureCount:baseline,captureCount:now});
                      return;
                    }
                    emit('R11_ATTACHMENT_GENERATE_FALLBACK_START',{
                      baselineCaptureCount:baseline,fileChangeMatched:fix.attachmentFileChangeMatched,
                      fileReadCount:fix.attachmentFileReadCount,networkStarted:fix.attachmentNetworkStarted
                    });
                    submitAttachmentViaButton('adaptive-generate-no-request');
                  },850);
                }
                return result;
              };
              wrapped.__aisR11AttachmentFallback=true;
              runtime.generate=wrapped;
              fix.adaptiveFallbackInstalled=true;
              emit('R11_ATTACHMENT_ADAPTIVE_FALLBACK_INSTALLED',{version:fix.version});
              return true;
            }catch(err){
              emit('R11_ATTACHMENT_ADAPTIVE_FALLBACK_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function installApiPatch() {
            try {
              const api = window.__AIS_R11_SUPPORT__;
              if (!api) return false;
              if (api.__r11RequestFixPatchedVersion === fix.version) return true;
              const originalDiscover = typeof api.discoverModels === 'function' ? api.discoverModels.bind(api) : null;
              const originalSelectionState = typeof api.selectionState === 'function' ? api.selectionState.bind(api) : null;
              const originalMarkFileChooserServed = typeof api.markFileChooserServed === 'function' ? api.markFileChooserServed.bind(api) : null;

              api.openModelPicker = function() {
                const result = {ok:true,path:'request-layer',pickerRequired:false};
                emit('R11_MODEL_PICKER_BYPASSED',result);
                return result;
              };

              api.selectModel = function(modelId) {
                const target = normalizeModel(modelId);
                if (!/^gemini-[a-z0-9][a-z0-9._-]{2,110}$/i.test(target)) {
                  return {ok:false,error:'INVALID_MODEL_ID',modelId:target};
                }
                let known = false;
                try {
                  const catalog = originalDiscover ? originalDiscover() : null;
                  const list = catalog && Array.isArray(catalog.models) ? catalog.models : [];
                  known = list.some(function(x){return x && normalizeModel(x.id)===target;});
                } catch (_) {}
                if (!known) {
                  emit('R11_MODEL_SELECT_RESULT',{ok:false,error:'MODEL_NOT_IN_CATALOG',modelId:target,path:'request-layer'});
                  return {ok:false,error:'MODEL_NOT_IN_CATALOG',modelId:target};
                }
                fix.requestedModel = target;
                fix.selectedModel = target;
                fix.lastAppliedModel = '';
                emit('R11_MODEL_SELECT_RESULT',{ok:true,modelId:target,path:'request-layer',pickerRequired:false});
                return {ok:true,pending:false,modelId:target,path:'request-layer'};
              };

              api.selectionState = function() {
                let base = {};
                try { base = originalSelectionState ? (originalSelectionState() || {}) : {}; } catch (_) {}
                return {
                  ok:true,
                  requestedModel:fix.requestedModel || String(base.requestedModel||''),
                  selectedModel:fix.selectedModel || String(base.selectedModel||''),
                  observedGenerateModel:String(base.observedGenerateModel||''),
                  rewriteCount:fix.rewriteCount,
                  lastOriginalModel:fix.lastOriginalModel,
                  lastAppliedModel:fix.lastAppliedModel,
                  path:'request-layer'
                };
              };

              api.markFileChooserServed = function(name,mime,size) {
                resetAttachmentObservation(name,mime,size);
                const base = originalMarkFileChooserServed ? originalMarkFileChooserServed(name,mime,size) : {ok:true};
                emit('R11_ATTACHMENT_FILE_DELIVERED',{
                  name:fix.attachmentExpectedName,mime:fix.attachmentExpectedMime,size:fix.attachmentExpectedSize
                });
                return base || {ok:true};
              };

              api.attachmentEvidence = function() {
                let support={},submit={};
                try{support=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachmentState?window.__AIS_R11_SUPPORT__.attachmentState(fix.attachmentExpectedName):{};}catch(_){}
                try{submit=window.__AIS_R11_SUBMIT_TARGET__&&window.__AIS_R11_SUBMIT_TARGET__.submissionReadinessIfAttachment?window.__AIS_R11_SUBMIT_TARGET__.submissionReadinessIfAttachment():{};}catch(_){}
                const present=attachmentPresent();
                const activeUploads=Number(support.activeUploads||0),uploadStarted=Number(support.uploadStarted||0),uploadCompleted=Number(support.uploadCompleted||0),uploadFailed=Number(support.uploadFailed||0);
                const uploadObserved=uploadStarted>0;
                const uploadSettled=uploadObserved&&activeUploads===0&&uploadFailed===0&&uploadCompleted>=uploadStarted;
                const localReadReady=fix.attachmentFileReadCompleted>0&&fix.attachmentFileReadFailed===0;
                const serverPayloadObserved=fix.attachmentPayloadStarted>0;
                const serverPayloadSettled=serverPayloadObserved&&fix.attachmentPayloadActive===0&&fix.attachmentPayloadFailed===0&&fix.attachmentPayloadCompleted>=fix.attachmentPayloadStarted;
                const dom=attachmentDomEvidence();
                const blobReadReady=fix.attachmentBlobReadCompleted>0&&fix.attachmentBlobReadFailed===0;
                const busy=!!support.busy||!!dom.busy,submitReady=!!submit.ready;
                const attachmentPrepared=present&&!busy&&submitReady&&(localReadReady||blobReadReady||!!dom.readyAfterBusy||serverPayloadSettled);
                const ready=attachmentPrepared&&!dom.error;
                return {
                  ok:true,version:fix.version,windowActive:attachmentWindowActive(),present:present,ready:ready,nameVisible:attachmentNameVisible(),busy:busy,submitReady:submitReady,
                  submitScore:Number(submit.score||-1),submitDisabled:!!submit.disabled,submitLabel:String(submit.label||'').slice(0,180),
                  attachmentPrepared:attachmentPrepared,localReadReady:localReadReady,blobReadReady:blobReadReady,
                  blobReadStarted:fix.attachmentBlobReadStarted,blobReadCompleted:fix.attachmentBlobReadCompleted,blobReadFailed:fix.attachmentBlobReadFailed,blobReadBytes:fix.attachmentBlobReadBytes,
                  formDataSeen:fix.attachmentFormDataSeen,performanceCount:fix.attachmentPerformanceCount,lastPerformance:fix.attachmentLastPerformance,
                  domState:String(dom.state||''),domBusy:!!dom.busy,domBusySeen:fix.attachmentDomBusySeen,domReadyAfterBusy:!!dom.readyAfterBusy,domErrorSeen:fix.attachmentDomErrorSeen,domProgress:Number(dom.progress||-1),domTransitions:fix.attachmentDomTransitionCount,
                  serverPayloadObserved:serverPayloadObserved,serverPayloadSettled:serverPayloadSettled,
                  payloadActive:fix.attachmentPayloadActive,payloadStarted:fix.attachmentPayloadStarted,payloadCompleted:fix.attachmentPayloadCompleted,payloadFailed:fix.attachmentPayloadFailed,
                  lastPayload:fix.attachmentLastPayload,
                  uploadObserved:uploadObserved,uploadSettled:uploadSettled,activeUploads:activeUploads,uploadStarted:uploadStarted,uploadCompleted:uploadCompleted,uploadFailed:uploadFailed,
                  expectedName:fix.attachmentExpectedName,expectedMime:fix.attachmentExpectedMime,expectedSize:fix.attachmentExpectedSize,
                  fileChangeCount:fix.attachmentFileChangeCount,fileChangeMatched:fix.attachmentFileChangeMatched,
                  lastChangedName:fix.attachmentLastChangedName,lastChangedMime:fix.attachmentLastChangedMime,lastChangedSize:fix.attachmentLastChangedSize,
                  fileReadCount:fix.attachmentFileReadCount,lastReadKind:fix.attachmentLastReadKind,
                  fileReadStarted:fix.attachmentFileReadStarted,fileReadCompleted:fix.attachmentFileReadCompleted,fileReadFailed:fix.attachmentFileReadFailed,
                  fileReadBytes:fix.attachmentFileReadBytes,fileReadResultChars:fix.attachmentFileReadResultChars,
                  networkStarted:fix.attachmentNetworkStarted,networkCompleted:fix.attachmentNetworkCompleted,networkFailed:fix.attachmentNetworkFailed,
                  submitFallbacks:fix.attachmentSubmitFallbacks,buttonClicks:fix.attachmentSubmitButtonClicks,
                  listenerInvokes:fix.attachmentSubmitListenerInvokes,lastSubmitLabel:fix.attachmentLastSubmitLabel,lastSubmitPath:fix.attachmentLastSubmitPath,
                  clickEntryCount:clickEntries.filter(function(e){return e.active;}).length,lastNet:fix.attachmentLastNet
                };
              };

              api.submitAttachmentViaButton = function(reason) { return submitAttachmentViaButton(reason||'api'); };

              api.armTrustedFileChooser = function() {
                let input = null;
                let count = 0;
                try {
                  const inputs = document.querySelectorAll('input[type="file"]');
                  count = inputs.length;
                  for (let i=inputs.length-1;i>=0;i--) {
                    if (inputs[i] && inputs[i].isConnected) { input = inputs[i]; break; }
                  }
                } catch (_) {}
                if (!input) {
                  emit('R11_FILE_ACTIVATION_ARM',{ok:false,error:'FILE_INPUT_NOT_FOUND',inputCount:count});
                  return {ok:false,error:'FILE_INPUT_NOT_FOUND',inputCount:count};
                }

                fix.fileArmToken += 1;
                const token = fix.fileArmToken;
                fix.fileArmed = true;
                let finished = false;

                function cleanup() {
                  if (finished) return;
                  finished = true;
                  fix.fileArmed = false;
                  try { document.removeEventListener('click',onTrustedClick,true); } catch (_) {}
                }

                function onTrustedClick(ev) {
                  if (token !== fix.fileArmToken || !fix.fileArmed) { cleanup(); return; }
                  if (!ev || ev.isTrusted !== true) {
                    emit('R11_FILE_ACTIVATION_IGNORED',{reason:'UNTRUSTED_CLICK',isTrusted:!!(ev&&ev.isTrusted)});
                    return;
                  }
                  cleanup();
                  try { ev.preventDefault(); } catch (_) {}
                  try { ev.stopImmediatePropagation(); } catch (_) {}
                  fix.trustedActivationCount += 1;
                  fix.lastTrustedActivationAt = Date.now();
                  let clicked = false;
                  let error = '';
                  try {
                    if (window.HTMLElement && HTMLElement.prototype && HTMLElement.prototype.click) {
                      HTMLElement.prototype.click.call(input);
                    } else {
                      input.click();
                    }
                    clicked = true;
                  } catch (err) { error = String(err).slice(0,800); }
                  emit('R11_FILE_TRUSTED_ACTIVATION',{
                    ok:clicked,
                    inputCount:count,
                    isTrusted:true,
                    activationCount:fix.trustedActivationCount,
                    error:error
                  });
                }

                document.addEventListener('click',onTrustedClick,true);
                setTimeout(function(){
                  if (token === fix.fileArmToken && fix.fileArmed) {
                    cleanup();
                    emit('R11_FILE_ACTIVATION_TIMEOUT',{token:token,inputCount:count});
                  }
                },3500);
                emit('R11_FILE_ACTIVATION_ARM',{ok:true,token:token,inputCount:count});
                return {ok:true,armed:true,token:token,inputCount:count};
              };

              api.requestFixState = function() {
                return {
                  ok:true,version:fix.version,
                  requestedModel:fix.requestedModel,selectedModel:fix.selectedModel,
                  rewriteCount:fix.rewriteCount,lastOriginalModel:fix.lastOriginalModel,lastAppliedModel:fix.lastAppliedModel,
                  xhrRewriteInstalled:fix.xhrRewriteInstalled,fetchObserverInstalled:fix.fetchObserverInstalled,
                  clickTrackingInstalled:fix.clickTrackingInstalled,adaptiveFallbackInstalled:fix.adaptiveFallbackInstalled,
                  fileArmed:fix.fileArmed,trustedActivationCount:fix.trustedActivationCount,lastTrustedActivationAt:fix.lastTrustedActivationAt,
                  attachment:this.attachmentEvidence()
                };
              };

              api.__r11RequestFixPatched = true;
              api.__r11RequestFixPatchedVersion = fix.version;
              fix.modelPatchInstalled = true;
              emit('R11_REQUEST_FIX_API_PATCHED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_REQUEST_FIX_API_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function ensureInstalled() {
            const clickOk = installClickTracking();
            const fileChangeOk = installFileChangeObserver();
            const fileReadOk = installFileReadObserver();
            const deepOk = installDeepAttachmentObserver();
            const apiOk = installApiPatch();
            const xhrOk = installXhrRewrite();
            const fetchOk = installFetchObserver();
            const adaptiveOk = installAdaptiveFallback();
            return clickOk && fileChangeOk && fileReadOk && deepOk && apiOk && xhrOk && fetchOk && adaptiveOk;
          }

          window.__AIS_R11_REQUEST_FIX__ = {
            version:fix.version,
            ensureInstalled:ensureInstalled,
            state:function(){return Object.assign({ok:true},fix,{activeClickEntries:clickEntries.filter(function(e){return e.active;}).length});},
            attachmentEvidence:function(){
              try { return window.__AIS_R11_SUPPORT__ && window.__AIS_R11_SUPPORT__.attachmentEvidence ? window.__AIS_R11_SUPPORT__.attachmentEvidence() : {ok:false,error:'support-not-patched'}; }
              catch(err){return {ok:false,error:String(err).slice(0,800)};}
            },
            submitAttachmentViaButton:function(reason){return submitAttachmentViaButton(reason||'direct-api');}
          };

          let tries = 0;
          const timer = setInterval(function(){
            tries += 1;
            if (ensureInstalled() && tries >= 5) clearInterval(timer);
            if (tries >= 120) clearInterval(timer);
          },50);
          ensureInstalled();
          emit('R11_REQUEST_FIX_INSTALLED',{version:fix.version});
        })();
    """.trimIndent()
}
