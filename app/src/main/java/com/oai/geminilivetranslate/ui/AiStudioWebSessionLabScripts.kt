package com.oai.geminilivetranslate.ui

object AiStudioWebSessionLabScripts {
    const val VERSION = "2026-09-02-web-session-r3"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_WEB_SESSION__ && window.__AIS_WEB_SESSION__.version === '$VERSION') return;

          const nativeFetch = window.fetch ? window.fetch.bind(window) : null;
          const NativeXHR = window.XMLHttpRequest;
          const nativeEventAdd = window.EventTarget && window.EventTarget.prototype
            ? window.EventTarget.prototype.addEventListener
            : null;
          const state = {
            version: '$VERSION',
            captureCount: 0,
            lastResult: null,
            lastProgress: null,
            lastCallStack: '',
            lastXhrLifecycle: null
          };

          function emit(kind, payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function isGenerateUrl(url) {
            const s = String(url || '');
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(s) || /\/GenerateContent(?:$|[/?])/i.test(s);
          }

          function safeUrlParts(raw) {
            try {
              const u = new URL(String(raw || ''), location.href);
              return {host:u.host,path:u.pathname};
            } catch (_) { return {host:'',path:''}; }
          }

          function headerSummary(input) {
            const out = [];
            try {
              const h = new Headers(input || {});
              h.forEach((value,name) => out.push({name:String(name),valueLength:String(value||'').length}));
            } catch (_) {}
            return out;
          }

          function bodyChars(body) {
            return typeof body === 'string' ? body.length : 0;
          }

          function safeStack() {
            try {
              return String(new Error('AI_STUDIO_GENERATE_CALL').stack || '')
                .split('\n').slice(0,18).join('\n').slice(0,8000);
            } catch (_) { return ''; }
          }

          function recordStart(source,url,method,headers,body) {
            if (!isGenerateUrl(url)) return;
            state.captureCount += 1;
            state.lastCallStack = safeStack();
            const p = safeUrlParts(url);
            emit('GENERATE_START', {
              source:source,captureCount:state.captureCount,method:String(method||''),
              host:p.host,path:p.path,headerSummary:headerSummary(headers),
              bodyChars:bodyChars(body),callStack:state.lastCallStack
            });
          }

          function resultPayload(source,status,ok,text,responseType,contentType,phase,partial) {
            const raw = String(text || '');
            return {
              source:source,
              status:Number(status),
              ok:!!ok,
              responseChars:raw.length,
              responseType:String(responseType||''),
              contentType:String(contentType||''),
              phase:String(phase||''),
              partial:!!partial,
              responseText:raw.slice(0,16000),
              at:Date.now()
            };
          }

          function recordResult(source,status,ok,text,responseType,contentType,phase,partial) {
            state.lastResult = resultPayload(source,status,ok,text,responseType,contentType,phase,partial);
            emit('GENERATE_RESULT', state.lastResult);
          }

          function recordProgress(source,status,text,responseType,contentType,phase) {
            const payload = resultPayload(source,status,status>=200&&status<300,text,responseType,contentType,phase,true);
            state.lastProgress = payload;
            emit('GENERATE_PROGRESS', {
              source:payload.source,
              status:payload.status,
              responseChars:payload.responseChars,
              responseType:payload.responseType,
              contentType:payload.contentType,
              phase:payload.phase,
              at:payload.at
            });
            return payload;
          }

          function xhrContentType(xhr) {
            try { return String(xhr.getResponseHeader('content-type') || ''); }
            catch (_) { return ''; }
          }

          function xhrTextNow(xhr) {
            let type = '';
            try { type = String(xhr.responseType || ''); } catch (_) {}
            try {
              if (type === '' || type === 'text') {
                return {text:typeof xhr.responseText === 'string' ? xhr.responseText : '',type:type};
              }
              if (type === 'json') {
                let text = '';
                try { text = JSON.stringify(xhr.response); } catch (_) {}
                return {text:text,type:type};
              }
              if (type === 'arraybuffer') {
                let text = '';
                try {
                  if (xhr.response) text = new TextDecoder('utf-8').decode(new Uint8Array(xhr.response));
                } catch (_) {}
                return {text:text,type:type};
              }
              let fallback = '';
              try { fallback = typeof xhr.response === 'string' ? xhr.response : ''; } catch (_) {}
              return {text:String(fallback || ''),type:type};
            } catch (_) {
              return {text:'',type:type};
            }
          }

          function xhrResponseText(xhr, done) {
            const immediate = xhrTextNow(xhr);
            if (immediate.text || immediate.type !== 'blob') {
              done(immediate.text, immediate.type);
              return;
            }
            try {
              if (xhr.response && typeof xhr.response.text === 'function') {
                xhr.response.text().then((text) => done(String(text||''), 'blob')).catch(() => done('', 'blob'));
                return;
              }
            } catch (_) {}
            done('', immediate.type);
          }

          function attachNativeListener(target, name, listener) {
            try {
              if (nativeEventAdd) {
                nativeEventAdd.call(target, name, listener, false);
                return true;
              }
            } catch (_) {}
            try {
              target.addEventListener(name, listener, false);
              return true;
            } catch (_) { return false; }
          }

          if (nativeFetch) {
            window.fetch = function(input, init) {
              let url='', method='GET', headers={}, body=null;
              try {
                url = typeof input === 'string' ? input : (input && input.url) || '';
                method = (init && init.method) || (input && input.method) || 'GET';
                headers = (init && init.headers) || (input && input.headers) || {};
                body = init && Object.prototype.hasOwnProperty.call(init,'body') ? init.body : null;
                recordStart('fetch',url,method,headers,body);
              } catch (_) {}
              const promise = nativeFetch(input,init);
              if (isGenerateUrl(url)) {
                promise.then((resp) => {
                  try {
                    const clone = resp.clone();
                    clone.text().then((text) => recordResult('fetch',resp.status,resp.ok,text,'fetch',resp.headers.get('content-type')||'','fetch-complete',false))
                      .catch(() => recordResult('fetch',resp.status,resp.ok,'','fetch',resp.headers.get('content-type')||'','fetch-read-error',true));
                  } catch (_) { recordResult('fetch',resp.status,resp.ok,'','fetch','','fetch-clone-error',true); }
                }).catch(() => recordResult('fetch',-1,false,'','fetch','','fetch-rejected',true));
              }
              return promise;
            };
          }

          if (NativeXHR && NativeXHR.prototype) {
            const nativeOpen = NativeXHR.prototype.open;
            const nativeSend = NativeXHR.prototype.send;
            const nativeSet = NativeXHR.prototype.setRequestHeader;

            NativeXHR.prototype.open = function(method,url) {
              this.__aisWsMeta = {
                method:String(method||'GET'),
                url:String(url||''),
                headerSummary:[],
                resultRecorded:false,
                listenersAttached:false,
                bestText:'',
                bestStatus:-1,
                bestResponseType:'',
                bestContentType:'',
                progressCount:0
              };
              return nativeOpen.apply(this,arguments);
            };
            NativeXHR.prototype.setRequestHeader = function(name,value) {
              try {
                if (this.__aisWsMeta) this.__aisWsMeta.headerSummary.push({name:String(name),valueLength:String(value||'').length});
              } catch (_) {}
              return nativeSet.apply(this,arguments);
            };
            NativeXHR.prototype.send = function(body) {
              const xhr = this;
              const m = xhr.__aisWsMeta || {
                method:'POST',url:'',headerSummary:[],resultRecorded:false,listenersAttached:false,
                bestText:'',bestStatus:-1,bestResponseType:'',bestContentType:'',progressCount:0
              };
              if (isGenerateUrl(m.url)) {
                state.captureCount += 1;
                state.lastCallStack = safeStack();
                const p = safeUrlParts(m.url);
                emit('GENERATE_START', {
                  source:'xhr',captureCount:state.captureCount,method:m.method,host:p.host,path:p.path,
                  headerSummary:m.headerSummary,bodyChars:bodyChars(body),callStack:state.lastCallStack
                });

                const lifecycle = function(eventName) {
                  let status = -1;
                  let readyState = -1;
                  let responseType = '';
                  try { status = Number(xhr.status); } catch (_) {}
                  try { readyState = Number(xhr.readyState); } catch (_) {}
                  try { responseType = String(xhr.responseType || ''); } catch (_) {}
                  state.lastXhrLifecycle = {event:eventName,status:status,readyState:readyState,responseType:responseType,at:Date.now()};
                  emit('XHR_LIFECYCLE', state.lastXhrLifecycle);
                  return {status:status,readyState:readyState,responseType:responseType};
                };

                const captureStreaming = function(eventName, info) {
                  if (m.resultRecorded) return;
                  const rs = info ? info.readyState : Number(xhr.readyState || 0);
                  const status = info ? info.status : Number(xhr.status || -1);
                  if (rs !== 3 || status < 200 || status >= 300) return;
                  const snapshot = xhrTextNow(xhr);
                  const text = String(snapshot.text || '');
                  if (!text || text.length < String(m.bestText || '').length) return;
                  m.bestText = text;
                  m.bestStatus = status;
                  m.bestResponseType = snapshot.type;
                  m.bestContentType = xhrContentType(xhr);
                  m.progressCount += 1;
                  const progress = recordProgress('xhr',status,text,snapshot.type,m.bestContentType,eventName+'-rs3');
                };

                const finish = function(eventName) {
                  const info = lifecycle(eventName);
                  captureStreaming(eventName, info);
                  if (m.resultRecorded) return;

                  if (eventName === 'readystatechange') {
                    if (info.readyState === 3) return;
                    if (info.readyState === 0) {
                      if (m.bestText) {
                        m.resultRecorded = true;
                        recordResult(
                          'xhr',
                          m.bestStatus,
                          m.bestStatus>=200&&m.bestStatus<300,
                          m.bestText,
                          m.bestResponseType,
                          m.bestContentType,
                          'reset-after-stream',
                          true
                        );
                      }
                      return;
                    }
                    if (info.readyState !== 4) return;
                  }

                  m.resultRecorded = true;
                  xhrResponseText(xhr, function(text, responseType) {
                    let status = -1;
                    try { status = Number(xhr.status); } catch (_) {}
                    const finalText = String(text || '') || String(m.bestText || '');
                    const finalStatus = status > 0 ? status : m.bestStatus;
                    recordResult(
                      'xhr',
                      finalStatus,
                      finalStatus>=200&&finalStatus<300,
                      finalText,
                      responseType || m.bestResponseType,
                      xhrContentType(xhr) || m.bestContentType,
                      eventName,
                      eventName === 'error' || eventName === 'abort' || eventName === 'timeout'
                    );
                  });
                };

                if (!m.listenersAttached) {
                  m.listenersAttached = true;
                  attachNativeListener(xhr,'readystatechange',function(){ finish('readystatechange'); });
                  attachNativeListener(xhr,'progress',function(){
                    const info = lifecycle('progress');
                    captureStreaming('progress', info);
                  });
                  attachNativeListener(xhr,'load',function(){ finish('load'); });
                  attachNativeListener(xhr,'loadend',function(){ finish('loadend'); });
                  attachNativeListener(xhr,'error',function(){ finish('error'); });
                  attachNativeListener(xhr,'abort',function(){ finish('abort'); });
                  attachNativeListener(xhr,'timeout',function(){ finish('timeout'); });
                }
              }
              return nativeSend.apply(xhr,arguments);
            };
          }

          state.getLastSafeResponse = function() {
            return state.lastResult || state.lastProgress || {ok:false,error:'no-result',lastXhrLifecycle:state.lastXhrLifecycle};
          };
          window.__AIS_WEB_SESSION__=state;
          emit('DOCUMENT_START_INSTALLED',{version:state.version,href:location.href});
        })();
    """.trimIndent()

}
