package com.oai.geminilivetranslate.ui

/**
 * R6 page-local handler capture.
 *
 * Captures AI Studio input/change/keydown listeners at document-start. R6 updates the prompt value,
 * invokes the page's own input/change handlers directly, then invokes the proven keydown handler
 * directly. No DOM event dispatch, Run click, native touch, credential export, or request replay.
 */
object AiStudioWebSessionR6HandlerCapture {
    const val VERSION = "2026-09-02-web-session-r6-handler-capture"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_R6_HANDLER_CAPTURE__ && window.__AIS_R6_HANDLER_CAPTURE__.version === '$VERSION') return;
          if (!window.EventTarget || !window.EventTarget.prototype) return;

          const nativeAdd = window.EventTarget.prototype.addEventListener;
          const nativeRemove = window.EventTarget.prototype.removeEventListener;
          const entries = [];
          let nextId = 1;

          function emit(kind,payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function targetMeta(target) {
            try {
              if (target === window) return {kind:'window'};
              if (target === document) return {kind:'document'};
              if (target && target.nodeType === 11 && target.host) return {kind:'shadow-root',hostTag:String(target.host.tagName||'').slice(0,40)};
              return {
                kind:'element',tag:String(target&&target.tagName||'').slice(0,40),
                role:String(target&&target.getAttribute&&target.getAttribute('role')||'').slice(0,80),
                aria:String(target&&target.getAttribute&&target.getAttribute('aria-label')||'').slice(0,120),
                classChars:String(target&&target.className||'').length
              };
            } catch (_) { return {kind:'unknown'}; }
          }

          function trackedType(type) {
            const t=String(type||'');
            return t==='input' || t==='change' || t==='keydown';
          }

          function sameCaptureOptions(a,b) {
            try {
              const ca=typeof a==='boolean'?a:!!(a&&a.capture);
              const cb=typeof b==='boolean'?b:!!(b&&b.capture);
              return ca===cb;
            } catch (_) { return true; }
          }

          window.EventTarget.prototype.addEventListener=function(type,listener,options) {
            try {
              if (trackedType(type) && listener && entries.length<1600) {
                entries.push({id:nextId++,type:String(type),target:this,listener:listener,options:options,active:true,at:Date.now(),meta:targetMeta(this)});
              }
            } catch (_) {}
            return nativeAdd.apply(this,arguments);
          };

          window.EventTarget.prototype.removeEventListener=function(type,listener,options) {
            try {
              if (trackedType(type) && listener) {
                for (let i=entries.length-1;i>=0;i--) {
                  const e=entries[i];
                  if (e.active && e.type===String(type) && e.target===this && e.listener===listener && sameCaptureOptions(e.options,options)) {
                    e.active=false; break;
                  }
                }
              }
            } catch (_) {}
            return nativeRemove.apply(this,arguments);
          };

          function visible(el) {
            try { const r=el.getBoundingClientRect(),s=getComputedStyle(el); return r.width>2&&r.height>2&&s.display!=='none'&&s.visibility!=='hidden'; }
            catch (_) { return false; }
          }

          function promptCandidates() {
            return Array.from(document.querySelectorAll('textarea,input,[contenteditable="true"],[role="textbox"]')).map(function(el){
              const hay=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.getAttribute('role')||'')).toLowerCase();
              let score=0;
              if(el.tagName==='TEXTAREA') score+=130;
              if(el.isContentEditable) score+=100;
              if(hay.indexOf('prompt')>=0) score+=100;
              if(visible(el)) score+=100;
              return {el:el,score:score};
            }).sort(function(a,b){return b.score-a.score;});
          }

          function setPromptValueOnly(el,text) {
            el.focus();
            try {
              const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:(el.tagName==='INPUT'?HTMLInputElement.prototype:null);
              const desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');
              if(desc&&desc.set) desc.set.call(el,text);
              else if('value' in el) el.value=text;
              else el.textContent=text;
            } catch (_) { if('value' in el) el.value=text; else el.textContent=text; }
          }

          function containsTarget(target,el) {
            try {
              if(!target||!el) return false;
              if(target===el) return true;
              if(typeof target.contains==='function'&&target.contains(el)) return true;
              if(target.nodeType===11&&target.host&&typeof target.host.contains==='function'&&target.host.contains(el)) return true;
            } catch (_) {}
            return false;
          }

          function scoreEntry(entry,el) {
            if(!entry.active) return -100000;
            const target=entry.target;
            let score=0;
            try {
              if(target===el) score+=1200;
              if(containsTarget(target,el)) score+=700;
              if(target===el.parentElement) score+=350;
              if(target===document) score+=260;
              if(target===window) score+=220;
              if(target===document.body) score+=200;
              if(target&&target.nodeType===11) score+=160;
              const age=Math.max(0,Date.now()-Number(entry.at||0));
              if(age<120000) score+=40;
            } catch (_) {}
            return score;
          }

          function eventPath(el) {
            const out=[]; let n=el;
            try { while(n){out.push(n);if(n.parentNode)n=n.parentNode;else if(n.host)n=n.host;else break;} } catch (_) {}
            if(out.indexOf(document)<0) out.push(document);
            if(out.indexOf(window)<0) out.push(window);
            return out;
          }

          function directEvent(type,promptEl,currentTarget,prompt) {
            let nativeEvent;
            if(type==='keydown') {
              nativeEvent=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',ctrlKey:true,bubbles:true,cancelable:true,composed:true});
            } else if(type==='input') {
              try { nativeEvent=new InputEvent('input',{bubbles:true,composed:true,inputType:'insertText',data:String(prompt||'')}); }
              catch (_) { nativeEvent=new Event('input',{bubbles:true,composed:true}); }
            } else {
              nativeEvent=new Event('change',{bubbles:true,composed:true});
            }
            const path=eventPath(promptEl);
            try {
              return new Proxy(nativeEvent,{
                get:function(target,prop) {
                  if(prop==='target'||prop==='srcElement') return promptEl;
                  if(prop==='currentTarget') return currentTarget;
                  if(prop==='composedPath') return function(){return path.slice();};
                  const value=Reflect.get(target,prop,target);
                  return typeof value==='function'?value.bind(target):value;
                }
              });
            } catch (_) { return nativeEvent; }
          }

          function invokeListener(entry,event) {
            const listener=entry.listener;
            if(typeof listener==='function') return listener.call(entry.target,event);
            if(listener&&typeof listener.handleEvent==='function') return listener.handleEvent.call(listener,event);
            throw new Error('listener-not-callable');
          }

          function ranked(type,el,limit) {
            return entries.filter(function(e){return e.active&&e.type===type;}).map(function(e){return {entry:e,score:scoreEntry(e,el)};})
              .filter(function(x){return x.score>-10000;}).sort(function(a,b){return b.score-a.score;}).slice(0,limit||80);
          }

          const state={
            version:'$VERSION',lastRun:null,
            describe:function(){
              const active=entries.filter(function(e){return e.active;});
              const counts={input:0,change:0,keydown:0};
              active.forEach(function(e){if(Object.prototype.hasOwnProperty.call(counts,e.type))counts[e.type]++;});
              return {ok:true,version:this.version,totalCaptured:entries.length,activeCount:active.length,counts:counts,
                sample:active.slice(-30).map(function(e){return {id:e.id,type:e.type,meta:e.meta,at:e.at};})};
            },
            invokeDirect:function(prompt,marker){
              try {
                const network=window.__AIS_WEB_SESSION__;
                if(!network) return {ok:false,error:'network-probe-not-installed'};
                network.expectedMarker=String(marker||''); network.lastResult=null; network.lastProgress=null; network.lastXhrLifecycle=null;
                const prompts=promptCandidates();
                if(!prompts.length) return {ok:false,error:'prompt-not-found'};
                const el=prompts[0].el;
                setPromptValueOnly(el,String(prompt||''));
                const baseline=Number(network.captureCount||0);

                const inputCandidates=ranked('input',el,30).concat(ranked('change',el,20)).sort(function(a,b){return b.score-a.score;});
                const keyCandidates=ranked('keydown',el,80);
                const run={startedAt:Date.now(),baselineCaptureCount:baseline,promptTag:String(el.tagName||''),promptScore:prompts[0].score,
                  inputCandidateCount:inputCandidates.length,keyCandidateCount:keyCandidates.length,inputAttempts:0,keyAttempts:0,captureStarted:false,successfulKeyEntryId:null,finished:false};
                this.lastRun=run;

                emit('R6_INPUT_PLAN',{candidateCount:inputCandidates.length,baselineCaptureCount:baseline,
                  top:inputCandidates.slice(0,12).map(function(x){return {id:x.entry.id,type:x.entry.type,score:x.score,meta:x.entry.meta};})});

                inputCandidates.slice(0,16).forEach(function(item){
                  const entry=item.entry;
                  if(item.score<500) return;
                  run.inputAttempts+=1;
                  try {
                    const ev=directEvent(entry.type,el,entry.target,prompt);
                    const result=invokeListener(entry,ev);
                    emit('R6_INPUT_HANDLER_ATTEMPT',{entryId:entry.id,type:entry.type,score:item.score,attempt:run.inputAttempts,
                      resultKind:result&&typeof result.then==='function'?'promise':'return',eventIsTrusted:!!ev.isTrusted,meta:entry.meta});
                    if(result&&typeof result.then==='function') result.catch(function(err){emit('R6_INPUT_HANDLER_ASYNC_ERROR',{entryId:entry.id,error:String(err).slice(0,1200)});});
                  } catch(err) {
                    emit('R6_INPUT_HANDLER_ERROR',{entryId:entry.id,type:entry.type,score:item.score,attempt:run.inputAttempts,error:String(err).slice(0,1200),meta:entry.meta});
                  }
                });
                emit('R6_INPUT_SYNC_DONE',{attempts:run.inputAttempts,valueChars:String(('value' in el)?el.value:(el.textContent||'')).length});
                emit('R6_KEYDOWN_PLAN',{candidateCount:keyCandidates.length,top:keyCandidates.slice(0,12).map(function(x){return {id:x.entry.id,score:x.score,meta:x.entry.meta};})});

                let index=0;
                function started(){return Number(network.captureCount||0)>baseline;}
                function step(){
                  if(started()) {
                    run.captureStarted=true; run.finished=true;
                    emit('R6_HANDLER_SUCCESS',{entryId:run.successfulKeyEntryId,inputAttempts:run.inputAttempts,keyAttempts:run.keyAttempts,captureCount:Number(network.captureCount||0)});
                    return;
                  }
                  if(index>=keyCandidates.length) {
                    run.finished=true;
                    emit('R6_HANDLER_FINAL',{captureStarted:false,inputAttempts:run.inputAttempts,keyAttempts:run.keyAttempts,keyCandidateCount:keyCandidates.length,captureCount:Number(network.captureCount||0)});
                    return;
                  }
                  const item=keyCandidates[index++],entry=item.entry;
                  run.keyAttempts+=1; run.successfulKeyEntryId=entry.id;
                  const ev=directEvent('keydown',el,entry.target,prompt);
                  try {
                    const result=invokeListener(entry,ev);
                    emit('R6_KEYDOWN_HANDLER_ATTEMPT',{entryId:entry.id,score:item.score,attempt:run.keyAttempts,
                      resultKind:result&&typeof result.then==='function'?'promise':'return',eventIsTrusted:!!ev.isTrusted,meta:entry.meta});
                    if(result&&typeof result.then==='function') result.catch(function(err){emit('R6_KEYDOWN_HANDLER_ASYNC_ERROR',{entryId:entry.id,error:String(err).slice(0,1200)});});
                  } catch(err) {
                    emit('R6_KEYDOWN_HANDLER_ERROR',{entryId:entry.id,score:item.score,attempt:run.keyAttempts,error:String(err).slice(0,1200),meta:entry.meta});
                  }
                  setTimeout(step,240);
                }
                setTimeout(step,180);
                setTimeout(function(){emit('R6_HANDLER_STATE',{captureStarted:started(),inputAttempts:run.inputAttempts,keyAttempts:run.keyAttempts,
                  inputCandidateCount:inputCandidates.length,keyCandidateCount:keyCandidates.length,captureCount:Number(network.captureCount||0),
                  hasResult:!!network.lastResult,hasProgress:!!network.lastProgress});},5000);

                return {ok:true,version:this.version,inputCandidateCount:inputCandidates.length,keyCandidateCount:keyCandidates.length,
                  promptTag:run.promptTag,promptScore:run.promptScore,baselineCaptureCount:baseline,
                  domEventDispatchUsed:false,keyboardDispatchUsed:false,runElementUsed:false,motionEventUsed:false};
              } catch(e) { return {ok:false,error:String(e),stack:String(e&&e.stack||'').slice(0,4000)}; }
            }
          };

          window.__AIS_R6_HANDLER_CAPTURE__=state;
          emit('R6_HANDLER_CAPTURE_INSTALLED',{version:state.version});
        })();
    """.trimIndent()
}
