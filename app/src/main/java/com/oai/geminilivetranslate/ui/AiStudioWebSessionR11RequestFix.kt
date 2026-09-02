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
    const val VERSION = "2026-09-02-web-session-r11.3-attachment-submit"

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
            fix.attachmentWindowUntil = Date.now() + 90000;
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
              windowMs:90000
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

          function noteAttachmentNetStart(source, url, method, body) {
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
          }

          function rewriteBody(url, body, source) {
            if (!fix.selectedModel || typeof body !== 'string' || !isGenerateUrl(url)) return body;
            const original = firstModel(body);
            if (!original) {
              emit('R11_MODEL_REWRITE_SKIPPED',{reason:'MODEL_NOT_FOUND_IN_BODY',target:fix.selectedModel,source:source,bodyChars:body.length});
              return body;
            }
            let rewritten = body;
            if (original !== fix.selectedModel) rewritten = body.split(original).join(fix.selectedModel);
            fix.lastOriginalModel = original;
            fix.lastAppliedModel = fix.selectedModel;
            fix.rewriteCount += 1;
            emit('R11_GENERATE_MODEL_REWRITE',{
              source:source,
              originalModel:original,
              targetModel:fix.selectedModel,
              changed:rewritten!==body,
              rewriteCount:fix.rewriteCount,
              bodyChars:rewritten.length
            });
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
                    xhr.addEventListener('loadend',function(){
                      let status=-1;try{status=Number(xhr.status||-1);}catch(_){}
                      noteAttachmentNetDone(netToken,status);
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
                return {
                  ok:true,version:fix.version,windowActive:attachmentWindowActive(),present:attachmentPresent(),nameVisible:attachmentNameVisible(),
                  expectedName:fix.attachmentExpectedName,expectedMime:fix.attachmentExpectedMime,expectedSize:fix.attachmentExpectedSize,
                  fileChangeCount:fix.attachmentFileChangeCount,fileChangeMatched:fix.attachmentFileChangeMatched,
                  lastChangedName:fix.attachmentLastChangedName,lastChangedMime:fix.attachmentLastChangedMime,lastChangedSize:fix.attachmentLastChangedSize,
                  fileReadCount:fix.attachmentFileReadCount,lastReadKind:fix.attachmentLastReadKind,
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
            const apiOk = installApiPatch();
            const xhrOk = installXhrRewrite();
            const fetchOk = installFetchObserver();
            const adaptiveOk = installAdaptiveFallback();
            return clickOk && fileChangeOk && fileReadOk && apiOk && xhrOk && fetchOk && adaptiveOk;
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
