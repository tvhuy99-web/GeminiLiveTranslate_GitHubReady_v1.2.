package com.oai.geminilivetranslate.ui

object AiStudioWebSessionLabScripts {
    const val VERSION = "2026-09-02-web-session-r2"

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
            expectedMarker: '',
            captureCount: 0,
            lastResult: null,
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

          function recordResult(source,status,ok,text,responseType,contentType) {
            const raw = String(text || '');
            const marker = state.expectedMarker;
            const markerFound = !!marker && raw.indexOf(marker) >= 0;
            state.lastResult = {
              source:source,
              status:Number(status),
              ok:!!ok,
              responseChars:raw.length,
              responseType:String(responseType||''),
              contentType:String(contentType||''),
              marker:marker,
              markerFound:markerFound,
              responseText:raw.slice(0,16000),
              at:Date.now()
            };
            emit('GENERATE_RESULT', state.lastResult);
          }

          function xhrContentType(xhr) {
            try { return String(xhr.getResponseHeader('content-type') || ''); }
            catch (_) { return ''; }
          }

          function xhrResponseText(xhr, done) {
            const type = String(xhr.responseType || '');
            try {
              if (type === '' || type === 'text') {
                let text = '';
                try { text = typeof xhr.responseText === 'string' ? xhr.responseText : ''; } catch (_) {}
                done(text, type);
                return;
              }
              if (type === 'json') {
                let text = '';
                try { text = JSON.stringify(xhr.response); } catch (_) {}
                done(text, type);
                return;
              }
              if (type === 'arraybuffer') {
                let text = '';
                try {
                  if (xhr.response) text = new TextDecoder('utf-8').decode(new Uint8Array(xhr.response));
                } catch (_) {}
                done(text, type);
                return;
              }
              if (type === 'blob' && xhr.response) {
                try {
                  if (typeof xhr.response.text === 'function') {
                    xhr.response.text().then((text) => done(String(text||''), type)).catch(() => done('', type));
                    return;
                  }
                } catch (_) {}
              }
              let fallback = '';
              try { fallback = typeof xhr.response === 'string' ? xhr.response : JSON.stringify(xhr.response); } catch (_) {}
              done(String(fallback || ''), type);
            } catch (_) {
              done('', type);
            }
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
                    clone.text().then((text) => recordResult('fetch',resp.status,resp.ok,text,'fetch',resp.headers.get('content-type')||''))
                      .catch(() => recordResult('fetch',resp.status,resp.ok,'','fetch',resp.headers.get('content-type')||''));
                  } catch (_) { recordResult('fetch',resp.status,resp.ok,'','fetch',''); }
                }).catch(() => recordResult('fetch',-1,false,'','fetch',''));
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
                listenersAttached:false
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
              const m = xhr.__aisWsMeta || {method:'POST',url:'',headerSummary:[],resultRecorded:false,listenersAttached:false};
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
                };

                const finish = function(eventName) {
                  lifecycle(eventName);
                  if (m.resultRecorded) return;
                  let readyState = -1;
                  try { readyState = Number(xhr.readyState); } catch (_) {}
                  if (eventName === 'readystatechange' && readyState !== 4) return;
                  m.resultRecorded = true;
                  xhrResponseText(xhr, function(text, responseType) {
                    let status = -1;
                    try { status = Number(xhr.status); } catch (_) {}
                    recordResult('xhr',status,status>=200&&status<300,text,responseType,xhrContentType(xhr));
                  });
                };

                if (!m.listenersAttached) {
                  m.listenersAttached = true;
                  attachNativeListener(xhr,'readystatechange',function(){ finish('readystatechange'); });
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

          function visible(el) {
            try { const r=el.getBoundingClientRect(); const s=getComputedStyle(el); return r.width>2&&r.height>2&&s.display!=='none'&&s.visibility!=='hidden'; }
            catch (_) { return false; }
          }

          function promptCandidates() {
            return Array.from(document.querySelectorAll('textarea,input,[contenteditable="true"],[role="textbox"]')).map((el) => {
              const hay=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.getAttribute('role')||'')).toLowerCase();
              let score=0;
              if(el.tagName==='TEXTAREA') score+=130;
              if(el.isContentEditable) score+=100;
              if(hay.includes('prompt')) score+=100;
              if(visible(el)) score+=100;
              return {el:el,score:score};
            }).sort((a,b)=>b.score-a.score);
          }

          function setPrompt(el,text) {
            el.focus();
            try {
              const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:(el.tagName==='INPUT'?HTMLInputElement.prototype:null);
              const desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');
              if(desc&&desc.set) desc.set.call(el,text); else if('value' in el) el.value=text; else el.textContent=text;
            } catch (_) { if('value' in el) el.value=text; else el.textContent=text; }
            try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text})); }
            catch (_) { el.dispatchEvent(new Event('input',{bubbles:true})); }
            el.dispatchEvent(new Event('change',{bubbles:true}));
          }

          function runCandidates() {
            return Array.from(document.querySelectorAll('button,[role="button"],input[type="submit"]')).map((el)=>{
              const r=el.getBoundingClientRect();
              const hay=((el.innerText||el.textContent||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.getAttribute('type')||'')+' '+String(el.className||'')).toLowerCase();
              let score=0;
              if(hay.includes('run')) score+=180;
              if(hay.includes('send')) score+=180;
              if((el.getAttribute('type')||'').toLowerCase()==='submit') score+=100;
              if(visible(el)) score+=80;
              if(el.disabled) score-=400;
              return {el:el,score:score,rect:r,label:String(el.innerText||el.getAttribute('aria-label')||'').slice(0,100)};
            }).sort((a,b)=>b.score-a.score);
          }

          state.prepareTrustedSend = function(prompt,marker) {
            state.expectedMarker=String(marker||'');
            state.lastResult=null;
            state.lastXhrLifecycle=null;
            const prompts=promptCandidates();
            if(!prompts.length) return {ok:false,error:'prompt-not-found'};
            setPrompt(prompts[0].el,String(prompt||''));
            const runs=runCandidates();
            if(!runs.length||runs[0].score<100) return {ok:false,error:'run-not-found'};
            const r=runs[0].rect;
            emit('TRUSTED_SEND_READY',{runLabel:runs[0].label,runScore:runs[0].score,marker:state.expectedMarker});
            return {ok:true,x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height,runLabel:runs[0].label,runScore:runs[0].score};
          };

          state.inspect = function() {
            return {
              version:state.version,
              href:location.href,
              readyState:document.readyState,
              captureCount:state.captureCount,
              lastResult:state.lastResult,
              lastCallStack:state.lastCallStack,
              lastXhrLifecycle:state.lastXhrLifecycle
            };
          };

          state.getLastSafeResponse = function() { return state.lastResult || {ok:false,error:'no-result',lastXhrLifecycle:state.lastXhrLifecycle}; };
          window.__AIS_WEB_SESSION__=state;
          emit('DOCUMENT_START_INSTALLED',{version:state.version,href:location.href});
        })();
    """.trimIndent()

    fun call(expression: String): String = """
        (function(){
          try {
            if (!window.__AIS_WEB_SESSION__) return JSON.stringify({ok:false,error:'probe-not-installed'});
            const value = ($expression);
            return JSON.stringify({ok:true,value:value});
          } catch(e) {
            return JSON.stringify({ok:false,error:String(e),stack:String(e&&e.stack||'')});
          }
        })();
    """.trimIndent()
}
