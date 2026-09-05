package com.oai.geminilivetranslate.ui

/**
 * R5 page-local handler capture.
 *
 * Installed at document-start next to the proven R3 network probe. It records references to
 * keydown listeners that AI Studio registers inside its own page, then can invoke those listener
 * functions directly without dispatching a keyboard event, clicking Run, or creating a native
 * MotionEvent. No credential/header values are copied out of the page.
 */
object AiStudioWebSessionR5HandlerCapture {
    const val VERSION = "2026-09-02-web-session-r5-handler-capture"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_R5_HANDLER_CAPTURE__ && window.__AIS_R5_HANDLER_CAPTURE__.version === '$VERSION') return;
          if (!window.EventTarget || !window.EventTarget.prototype) return;

          const nativeAdd = window.EventTarget.prototype.addEventListener;
          const nativeRemove = window.EventTarget.prototype.removeEventListener;
          const entries = [];
          let nextId = 1;

          function emit(kind, payload) {
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
              if (target && target.nodeType === 11 && target.host) {
                return {kind:'shadow-root',hostTag:String(target.host.tagName||'').slice(0,40)};
              }
              return {
                kind:'element',
                tag:String(target && target.tagName || '').slice(0,40),
                role:String(target && target.getAttribute && target.getAttribute('role') || '').slice(0,80),
                aria:String(target && target.getAttribute && target.getAttribute('aria-label') || '').slice(0,120),
                classChars:String(target && target.className || '').length
              };
            } catch (_) { return {kind:'unknown'}; }
          }

          function sameCaptureOptions(a,b) {
            try {
              const ca = typeof a === 'boolean' ? a : !!(a && a.capture);
              const cb = typeof b === 'boolean' ? b : !!(b && b.capture);
              return ca === cb;
            } catch (_) { return true; }
          }

          window.EventTarget.prototype.addEventListener = function(type, listener, options) {
            try {
              if (String(type) === 'keydown' && listener && entries.length < 800) {
                entries.push({
                  id:nextId++,
                  target:this,
                  listener:listener,
                  options:options,
                  active:true,
                  at:Date.now(),
                  meta:targetMeta(this)
                });
              }
            } catch (_) {}
            return nativeAdd.apply(this, arguments);
          };

          window.EventTarget.prototype.removeEventListener = function(type, listener, options) {
            try {
              if (String(type) === 'keydown' && listener) {
                for (let i=entries.length-1;i>=0;i--) {
                  const e=entries[i];
                  if (e.active && e.target === this && e.listener === listener && sameCaptureOptions(e.options,options)) {
                    e.active=false;
                    break;
                  }
                }
              }
            } catch (_) {}
            return nativeRemove.apply(this, arguments);
          };

          function visible(el) {
            try {
              const r=el.getBoundingClientRect();
              const s=getComputedStyle(el);
              return r.width>2 && r.height>2 && s.display!=='none' && s.visibility!=='hidden';
            } catch (_) { return false; }
          }

          function promptCandidates() {
            return Array.from(document.querySelectorAll('textarea,input,[contenteditable="true"],[role="textbox"]')).map(function(el) {
              const hay=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.getAttribute('role')||'')).toLowerCase();
              let score=0;
              if(el.tagName==='TEXTAREA') score+=130;
              if(el.isContentEditable) score+=100;
              if(hay.indexOf('prompt')>=0) score+=100;
              if(visible(el)) score+=100;
              return {el:el,score:score};
            }).sort(function(a,b){return b.score-a.score;});
          }

          function setPrompt(el,text) {
            el.focus();
            try {
              const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:(el.tagName==='INPUT'?HTMLInputElement.prototype:null);
              const desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');
              if(desc&&desc.set) desc.set.call(el,text);
              else if('value' in el) el.value=text;
              else el.textContent=text;
            } catch (_) {
              if('value' in el) el.value=text; else el.textContent=text;
            }
            try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text})); }
            catch (_) { el.dispatchEvent(new Event('input',{bubbles:true})); }
            try { el.dispatchEvent(new Event('change',{bubbles:true})); } catch (_) {}
          }

          function containsTarget(target,el) {
            try {
              if (!target || !el) return false;
              if (target === el) return true;
              if (typeof target.contains === 'function' && target.contains(el)) return true;
              if (target.nodeType === 11 && target.host && typeof target.host.contains === 'function' && target.host.contains(el)) return true;
            } catch (_) {}
            return false;
          }

          function scoreEntry(entry,el) {
            if (!entry.active) return -100000;
            const target=entry.target;
            let score=0;
            try {
              if (target === el) score += 1200;
              if (containsTarget(target,el)) score += 700;
              if (target === el.parentElement) score += 350;
              if (target === document) score += 260;
              if (target === window) score += 220;
              if (target === document.body) score += 200;
              if (target && target.nodeType === 11) score += 160;
              const age=Math.max(0,Date.now()-Number(entry.at||0));
              if (age < 120000) score += 40;
            } catch (_) {}
            return score;
          }

          function eventPath(el) {
            const out=[];
            let n=el;
            try {
              while(n) {
                out.push(n);
                if (n.parentNode) n=n.parentNode;
                else if (n.host) n=n.host;
                else break;
              }
            } catch (_) {}
            if (out.indexOf(document)<0) out.push(document);
            if (out.indexOf(window)<0) out.push(window);
            return out;
          }

          function directKeyboardEvent(promptEl,currentTarget) {
            const nativeEvent=new KeyboardEvent('keydown',{
              key:'Enter',code:'Enter',ctrlKey:true,bubbles:true,cancelable:true,composed:true
            });
            const path=eventPath(promptEl);
            try {
              return new Proxy(nativeEvent,{
                get:function(target,prop) {
                  if (prop === 'target' || prop === 'srcElement') return promptEl;
                  if (prop === 'currentTarget') return currentTarget;
                  if (prop === 'composedPath') return function(){return path.slice();};
                  const value=Reflect.get(target,prop,target);
                  return typeof value === 'function' ? value.bind(target) : value;
                }
              });
            } catch (_) { return nativeEvent; }
          }

          function invokeListener(entry,event) {
            const listener=entry.listener;
            if (typeof listener === 'function') return listener.call(entry.target,event);
            if (listener && typeof listener.handleEvent === 'function') return listener.handleEvent.call(listener,event);
            throw new Error('listener-not-callable');
          }

          const state={
            version:'$VERSION',
            lastRun:null,
            describe:function() {
              const active=entries.filter(function(e){return e.active;});
              return {
                ok:true,
                version:this.version,
                totalCaptured:entries.length,
                activeCount:active.length,
                sample:active.slice(-20).map(function(e){return {id:e.id,meta:e.meta,at:e.at};})
              };
            },
            invokeDirect:function(prompt,marker) {
              try {
                const network=window.__AIS_WEB_SESSION__;
                if (!network) return {ok:false,error:'network-probe-not-installed'};
                network.expectedMarker=String(marker||'');
                network.lastResult=null;
                network.lastProgress=null;
                network.lastXhrLifecycle=null;

                const prompts=promptCandidates();
                if (!prompts.length) return {ok:false,error:'prompt-not-found'};
                const el=prompts[0].el;
                setPrompt(el,String(prompt||''));
                const baseline=Number(network.captureCount||0);
                const candidates=entries.filter(function(e){return e.active;}).map(function(e){
                  return {entry:e,score:scoreEntry(e,el)};
                }).filter(function(x){return x.score>-10000;}).sort(function(a,b){return b.score-a.score;}).slice(0,80);

                const run={
                  startedAt:Date.now(),
                  baselineCaptureCount:baseline,
                  promptTag:String(el.tagName||''),
                  promptScore:prompts[0].score,
                  candidateCount:candidates.length,
                  attempts:0,
                  captureStarted:false,
                  successfulEntryId:null,
                  finished:false
                };
                this.lastRun=run;
                emit('R5_HANDLER_PLAN',{
                  candidateCount:candidates.length,
                  baselineCaptureCount:baseline,
                  promptTag:run.promptTag,
                  promptScore:run.promptScore,
                  top:candidates.slice(0,12).map(function(x){return {id:x.entry.id,score:x.score,meta:x.entry.meta};})
                });

                let index=0;
                function started(){return Number(network.captureCount||0)>baseline;}
                function step(){
                  if (started()) {
                    run.captureStarted=true;
                    run.finished=true;
                    emit('R5_HANDLER_SUCCESS',{
                      entryId:run.successfulEntryId,
                      attempts:run.attempts,
                      captureCount:Number(network.captureCount||0)
                    });
                    return;
                  }
                  if (index>=candidates.length) {
                    run.finished=true;
                    emit('R5_HANDLER_FINAL',{
                      captureStarted:false,
                      attempts:run.attempts,
                      candidateCount:candidates.length,
                      captureCount:Number(network.captureCount||0)
                    });
                    return;
                  }

                  const item=candidates[index++];
                  const entry=item.entry;
                  run.attempts+=1;
                  run.successfulEntryId=entry.id;
                  const event=directKeyboardEvent(el,entry.target);
                  let resultKind='return';
                  try {
                    const result=invokeListener(entry,event);
                    if (result && typeof result.then === 'function') {
                      resultKind='promise';
                      result.catch(function(err){
                        emit('R5_HANDLER_ASYNC_ERROR',{entryId:entry.id,error:String(err).slice(0,1200)});
                      });
                    }
                    emit('R5_HANDLER_ATTEMPT',{
                      entryId:entry.id,
                      score:item.score,
                      attempt:run.attempts,
                      resultKind:resultKind,
                      eventIsTrusted:!!event.isTrusted,
                      meta:entry.meta
                    });
                  } catch (err) {
                    emit('R5_HANDLER_ATTEMPT_ERROR',{
                      entryId:entry.id,
                      score:item.score,
                      attempt:run.attempts,
                      error:String(err).slice(0,1200),
                      meta:entry.meta
                    });
                  }
                  setTimeout(step,240);
                }

                setTimeout(step,350);
                setTimeout(function(){
                  emit('R5_HANDLER_STATE',{
                    captureStarted:started(),
                    attempts:run.attempts,
                    candidateCount:candidates.length,
                    captureCount:Number(network.captureCount||0),
                    hasResult:!!network.lastResult,
                    hasProgress:!!network.lastProgress
                  });
                },5000);

                return {
                  ok:true,
                  version:this.version,
                  candidateCount:candidates.length,
                  promptTag:run.promptTag,
                  promptScore:run.promptScore,
                  baselineCaptureCount:baseline,
                  keyboardDispatchUsed:false,
                  runElementUsed:false,
                  motionEventUsed:false
                };
              } catch (e) {
                return {ok:false,error:String(e),stack:String(e&&e.stack||'').slice(0,4000)};
              }
            }
          };

          window.__AIS_R5_HANDLER_CAPTURE__=state;
          emit('R5_HANDLER_CAPTURE_INSTALLED',{version:state.version});
        })();
    """.trimIndent()
}
