package com.oai.geminilivetranslate.ui

/**
 * R12 page-local Direct Engine.
 *
 * This deliberately operates below the visual Send/Run control. It captures AI Studio's own
 * input/change/click/submit listeners at document-start, synchronizes prompt state through those
 * listeners, and invokes submit/click handlers directly. It also keeps the latest GenerateContent
 * request template privately inside the authenticated page for diagnostics/research. Header values,
 * request bodies, cookies and auth material are never exposed through describe() or native logs.
 */
object AiStudioWebSessionDirectEngine {
    const val VERSION = "2026-09-02-web-session-r12.0-direct-engine"

    val DOCUMENT_START: String = """
        (function(){
          'use strict';
          if(window.__AIS_DIRECT_ENGINE__&&window.__AIS_DIRECT_ENGINE__.version==='$VERSION')return;
          if(!window.EventTarget||!window.EventTarget.prototype||!window.XMLHttpRequest)return;

          const nativeAdd=window.EventTarget.prototype.addEventListener;
          const nativeRemove=window.EventTarget.prototype.removeEventListener;
          const NativeXHR=window.XMLHttpRequest;
          const previousOpen=NativeXHR.prototype.open;
          const previousSet=NativeXHR.prototype.setRequestHeader;
          const previousSend=NativeXHR.prototype.send;
          const entries=[];
          let nextId=1;

          const state={
            version:'$VERSION',
            lastRun:null,
            template:null,
            templateCount:0,
            directAttempts:0,
            directSuccesses:0
          };

          function emit(kind,payload){
            try{
              if(window.AIStudioWebSessionLab&&window.AIStudioWebSessionLab.onJsEvent){
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            }catch(_){}
          }

          function tracked(type){
            const t=String(type||'');
            return t==='input'||t==='change'||t==='click'||t==='submit';
          }

          function sameCapture(a,b){
            try{
              const ca=typeof a==='boolean'?a:!!(a&&a.capture);
              const cb=typeof b==='boolean'?b:!!(b&&b.capture);
              return ca===cb;
            }catch(_){return true;}
          }

          function meta(target){
            try{
              if(target===window)return {kind:'window'};
              if(target===document)return {kind:'document'};
              if(target&&target.nodeType===11&&target.host)return {kind:'shadow-root',hostTag:String(target.host.tagName||'').slice(0,40)};
              return {
                kind:'element',
                tag:String(target&&target.tagName||'').slice(0,40),
                role:String(target&&target.getAttribute&&target.getAttribute('role')||'').slice(0,80),
                type:String(target&&target.getAttribute&&target.getAttribute('type')||'').slice(0,40),
                aria:String(target&&target.getAttribute&&target.getAttribute('aria-label')||'').slice(0,180),
                testId:String(target&&target.getAttribute&&target.getAttribute('data-testid')||'').slice(0,120)
              };
            }catch(_){return {kind:'unknown'};}
          }

          window.EventTarget.prototype.addEventListener=function(type,listener,options){
            try{
              if(tracked(type)&&listener&&entries.length<2600){
                entries.push({id:nextId++,type:String(type),target:this,listener:listener,options:options,active:true,at:Date.now(),meta:meta(this)});
              }
            }catch(_){}
            return nativeAdd.apply(this,arguments);
          };

          window.EventTarget.prototype.removeEventListener=function(type,listener,options){
            try{
              if(tracked(type)&&listener){
                for(let i=entries.length-1;i>=0;i--){
                  const e=entries[i];
                  if(e.active&&e.type===String(type)&&e.target===this&&e.listener===listener&&sameCapture(e.options,options)){
                    e.active=false;break;
                  }
                }
              }
            }catch(_){}
            return nativeRemove.apply(this,arguments);
          };

          function isGenerateUrl(raw){
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(String(raw||''));
          }

          function safeUrl(raw){
            try{const u=new URL(String(raw||''),location.href);return {host:u.host,path:u.pathname};}
            catch(_){return {host:'',path:''};}
          }

          NativeXHR.prototype.open=function(method,url){
            try{
              this.__aisR12Meta={method:String(method||'POST'),url:String(url||''),headers:[],openedAt:Date.now()};
            }catch(_){}
            return previousOpen.apply(this,arguments);
          };

          NativeXHR.prototype.setRequestHeader=function(name,value){
            try{
              if(this.__aisR12Meta&&this.__aisR12Meta.headers.length<80){
                this.__aisR12Meta.headers.push([String(name||''),String(value||'')]);
              }
            }catch(_){}
            return previousSet.apply(this,arguments);
          };

          NativeXHR.prototype.send=function(body){
            try{
              const m=this.__aisR12Meta;
              if(m&&isGenerateUrl(m.url)){
                const prompt=currentPrompt();
                const attachment=attachmentInfo();
                state.template={
                  method:m.method,
                  url:m.url,
                  headers:m.headers.slice(),
                  body:typeof body==='string'?body:null,
                  prompt:prompt?String(readValue(prompt)||''):'',
                  attachmentPresent:!!attachment,
                  capturedAt:Date.now()
                };
                state.templateCount+=1;
                const p=safeUrl(m.url);
                emit('R12_REQUEST_TEMPLATE_CAPTURED',{
                  templateCount:state.templateCount,
                  host:p.host,path:p.path,
                  bodyChars:typeof body==='string'?body.length:0,
                  headerCount:m.headers.length,
                  attachmentPresent:!!attachment,
                  promptChars:state.template.prompt.length
                });
              }
            }catch(_){}
            return previousSend.apply(this,arguments);
          };

          function visible(el){
            try{const r=el.getBoundingClientRect(),s=getComputedStyle(el);return r.width>2&&r.height>2&&s.display!=='none'&&s.visibility!=='hidden';}
            catch(_){return false;}
          }

          function readValue(el){
            try{return 'value' in el?String(el.value||''):String(el.textContent||'');}
            catch(_){return '';}
          }

          function promptCandidates(){
            return Array.from(document.querySelectorAll('textarea,input,[contenteditable="true"],[role="textbox"]')).map(function(el){
              const hay=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.getAttribute('role')||'')).toLowerCase();
              let score=0;
              if(el.tagName==='TEXTAREA')score+=500;
              if(el.isContentEditable)score+=420;
              if(hay.indexOf('prompt')>=0)score+=360;
              if(visible(el))score+=300;
              if(readValue(el).length>0)score+=80;
              return {el:el,score:score};
            }).sort(function(a,b){return b.score-a.score;});
          }

          function currentPrompt(){const p=promptCandidates();return p.length?p[0].el:null;}

          function attachmentInfo(){
            try{
              const all=Array.from(document.querySelectorAll('button,[role="button"],div,span'));
              for(let i=0;i<all.length&&i<1800;i++){
                const el=all[i];
                if(!visible(el))continue;
                const text=String(el.innerText||el.textContent||'').trim();
                const aria=String(el.getAttribute&&el.getAttribute('aria-label')||'');
                const hay=(text+' '+aria).toLowerCase();
                if(/remove media|remove file|attachment|\.mp4|\.mov|\.webm|\.mp3|\.wav|\.pdf/.test(hay)){
                  const r=el.getBoundingClientRect();
                  return {el:el,label:(text||aria).slice(0,240),x:r.x,y:r.y,w:r.width,h:r.height};
                }
              }
            }catch(_){}
            return null;
          }

          function ancestors(el){
            const out=[];let n=el;let guard=0;
            try{
              while(n&&guard++<18){out.push(n);if(n.parentNode)n=n.parentNode;else if(n.host)n=n.host;else break;}
            }catch(_){}
            return out;
          }

          function commonComposer(prompt,attachment){
            if(!prompt)return null;
            const pa=ancestors(prompt);
            if(attachment&&attachment.el){
              const aa=ancestors(attachment.el);
              for(let i=0;i<pa.length;i++)if(aa.indexOf(pa[i])>=0&&pa[i]&&pa[i].querySelectorAll)return pa[i];
            }
            for(let i=0;i<pa.length;i++){
              const n=pa[i];
              try{
                if(n&&n.querySelector&&n.querySelector('button[type="submit"],button,[role="button"]'))return n;
              }catch(_){}
            }
            return prompt.parentElement||null;
          }

          function runButton(composer){
            if(!composer||!composer.querySelectorAll)return null;
            const buttons=Array.from(composer.querySelectorAll('button,[role="button"],input[type="submit"]')).map(function(el){
              const label=((el.innerText||el.textContent||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.getAttribute('type')||'')+' '+(el.getAttribute('data-testid')||'')).trim();
              const hay=label.toLowerCase();let score=0;
              if((el.getAttribute('type')||'').toLowerCase()==='submit')score+=1800;
              if(/\brun\b|\bsend\b/.test(hay))score+=1200;
              if(hay.indexOf('speech to text')>=0||hay.indexOf('add-media')>=0||hay.indexOf('insert images')>=0)score-=2200;
              if(hay.indexOf('remove media')>=0||hay.indexOf('tools menu')>=0||hay.indexOf('api key')>=0)score-=1800;
              if(visible(el))score+=300;
              return {el:el,score:score,label:label.slice(0,220),disabled:!!el.disabled};
            }).sort(function(a,b){return b.score-a.score;});
            return buttons.length?buttons[0]:null;
          }

          function findForm(el,composer){
            try{
              if(el&&el.form)return el.form;
              if(el&&el.closest){const f=el.closest('form');if(f)return f;}
              if(composer&&composer.closest){const f=composer.closest('form');if(f)return f;}
              if(composer&&composer.querySelector){const f=composer.querySelector('form');if(f)return f;}
            }catch(_){}
            return null;
          }

          function containsTarget(target,el){
            try{
              if(!target||!el)return false;
              if(target===el)return true;
              if(typeof target.contains==='function'&&target.contains(el))return true;
              if(target.nodeType===11&&target.host&&typeof target.host.contains==='function'&&target.host.contains(el))return true;
            }catch(_){}
            return false;
          }

          function scoreEntry(entry,target,composer){
            if(!entry.active)return -100000;
            let score=0;const t=entry.target;
            try{
              if(t===target)score+=2200;
              if(target&&containsTarget(t,target))score+=1500;
              if(composer&&t===composer)score+=1300;
              if(composer&&containsTarget(t,composer))score+=800;
              if(t===document)score+=420;
              if(t===document.body)score+=360;
              if(t===window)score+=300;
              const age=Math.max(0,Date.now()-Number(entry.at||0));if(age<180000)score+=100;
            }catch(_){}
            return score;
          }

          function ranked(type,target,composer,limit){
            return entries.filter(function(e){return e.active&&e.type===type;}).map(function(e){return {entry:e,score:scoreEntry(e,target,composer)};})
              .filter(function(x){return x.score>250;}).sort(function(a,b){return b.score-a.score;}).slice(0,limit||30);
          }

          function eventPath(target){
            const out=[];let n=target;let guard=0;
            try{while(n&&guard++<24){out.push(n);if(n.parentNode)n=n.parentNode;else if(n.host)n=n.host;else break;}}catch(_){}
            if(out.indexOf(document)<0)out.push(document);if(out.indexOf(window)<0)out.push(window);return out;
          }

          function proxyEvent(type,target,currentTarget,submitter){
            let ev;
            try{
              if(type==='submit'&&typeof SubmitEvent==='function')ev=new SubmitEvent('submit',{bubbles:true,cancelable:true,submitter:submitter||null});
              else if(type==='click')ev=new MouseEvent('click',{bubbles:true,cancelable:true,composed:true,button:0});
              else if(type==='input')ev=new InputEvent('input',{bubbles:true,composed:true,inputType:'insertText'});
              else ev=new Event(type,{bubbles:true,cancelable:true,composed:true});
            }catch(_){ev=new Event(type,{bubbles:true,cancelable:true});}
            const path=eventPath(target);
            try{
              return new Proxy(ev,{get:function(obj,prop){
                if(prop==='target'||prop==='srcElement')return target;
                if(prop==='currentTarget')return currentTarget;
                if(prop==='submitter')return submitter||null;
                if(prop==='composedPath')return function(){return path.slice();};
                const v=Reflect.get(obj,prop,obj);return typeof v==='function'?v.bind(obj):v;
              }});
            }catch(_){return ev;}
          }

          function invoke(entry,event){
            const l=entry.listener;
            if(typeof l==='function')return l.call(entry.target,event);
            if(l&&typeof l.handleEvent==='function')return l.handleEvent.call(l,event);
            throw new Error('listener-not-callable');
          }

          function setPromptOnly(el,text){
            try{
              const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:(el.tagName==='INPUT'?HTMLInputElement.prototype:null);
              const desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');
              if(desc&&desc.set)desc.set.call(el,text);else if('value' in el)el.value=text;else el.textContent=text;
            }catch(_){if('value' in el)el.value=text;else el.textContent=text;}
          }

          function syncPrompt(el,text,composer){
            setPromptOnly(el,text);
            const list=ranked('input',el,composer,16).concat(ranked('change',el,composer,12)).sort(function(a,b){return b.score-a.score;}).slice(0,18);
            let invoked=0;
            list.forEach(function(item){
              try{invoke(item.entry,proxyEvent(item.entry.type,el,item.entry.target,null));invoked+=1;}catch(err){
                emit('R12_PROMPT_HANDLER_ERROR',{entryId:item.entry.id,type:item.entry.type,score:item.score,error:String(err).slice(0,900)});
              }
            });
            emit('R12_PROMPT_SYNC',{invoked:invoked,promptChars:String(text||'').length,valueChars:readValue(el).length});
            return invoked;
          }

          function networkCapture(){
            try{return Number(window.__AIS_WEB_SESSION__&&window.__AIS_WEB_SESSION__.captureCount||0);}catch(_){return 0;}
          }

          function invokeDirect(promptText,marker){
            try{
              const prompt=currentPrompt();const attachment=attachmentInfo();
              if(!prompt)return {ok:false,error:'prompt-not-found'};
              const composer=commonComposer(prompt,attachment);
              const run=runButton(composer);
              const form=findForm(run&&run.el,composer);
              const network=window.__AIS_WEB_SESSION__;
              if(network){network.expectedMarker=String(marker||'');network.lastResult=null;network.lastProgress=null;network.lastXhrLifecycle=null;}
              const baseline=networkCapture();
              syncPrompt(prompt,String(promptText||''),composer);

              const submitTarget=form||composer||run&&run.el||prompt;
              const submitters=ranked('submit',submitTarget,composer,24);
              const clickers=run&&run.el?ranked('click',run.el,composer,32):[];
              const plan=submitters.map(function(x){return {kind:'submit',item:x};}).concat(clickers.map(function(x){return {kind:'click',item:x};}));
              const runState={
                startedAt:Date.now(),baselineCaptureCount:baseline,attachmentPresent:!!attachment,
                runLabel:run?run.label:'',runScore:run?run.score:-1,runDisabled:run?run.disabled:false,
                formPresent:!!form,submitCandidates:submitters.length,clickCandidates:clickers.length,
                attempts:0,captureStarted:false,finished:false
              };
              state.lastRun=runState;state.directAttempts+=1;
              emit('R12_DIRECT_PLAN',{
                baselineCaptureCount:baseline,attachmentPresent:!!attachment,hasComposer:!!composer,hasForm:!!form,
                runLabel:runState.runLabel,runScore:runState.runScore,runDisabled:runState.runDisabled,
                submitCandidates:submitters.length,clickCandidates:clickers.length,
                submitTop:submitters.slice(0,8).map(function(x){return {entryId:x.entry.id,score:x.score,meta:x.entry.meta};}),
                clickTop:clickers.slice(0,8).map(function(x){return {entryId:x.entry.id,score:x.score,meta:x.entry.meta};})
              });

              let index=0;
              function started(){return networkCapture()>baseline;}
              function finishSuccess(source,entryId){
                runState.captureStarted=true;runState.finished=true;state.directSuccesses+=1;
                emit('R12_DIRECT_SUBMIT_SUCCESS',{source:source,entryId:entryId||0,attempts:runState.attempts,captureCount:networkCapture(),baselineCaptureCount:baseline});
              }
              function step(){
                if(started()){finishSuccess('listener',0);return;}
                if(index>=plan.length){
                  if(form&&typeof form.requestSubmit==='function'){
                    runState.attempts+=1;
                    try{
                      form.requestSubmit(run&&run.el&&run.el.form===form?run.el:undefined);
                      emit('R12_DIRECT_REQUEST_SUBMIT',{attempt:runState.attempts,runLabel:runState.runLabel});
                    }catch(err){emit('R12_DIRECT_REQUEST_SUBMIT_ERROR',{error:String(err).slice(0,900)});}
                    setTimeout(function(){
                      if(started()){finishSuccess('requestSubmit',0);return;}
                      runState.finished=true;
                      emit('R12_DIRECT_SUBMIT_FINAL',{captureStarted:false,attempts:runState.attempts,captureCount:networkCapture(),baselineCaptureCount:baseline});
                    },320);
                  }else{
                    runState.finished=true;
                    emit('R12_DIRECT_SUBMIT_FINAL',{captureStarted:false,attempts:runState.attempts,captureCount:networkCapture(),baselineCaptureCount:baseline});
                  }
                  return;
                }
                const p=plan[index++],entry=p.item.entry;runState.attempts+=1;
                try{
                  const target=p.kind==='submit'?(form||submitTarget):(run&&run.el||submitTarget);
                  const ev=proxyEvent(p.kind,target,entry.target,run&&run.el||null);
                  const result=invoke(entry,ev);
                  emit('R12_DIRECT_HANDLER_ATTEMPT',{
                    kind:p.kind,entryId:entry.id,score:p.item.score,attempt:runState.attempts,
                    resultKind:result&&typeof result.then==='function'?'promise':'return',meta:entry.meta
                  });
                  if(result&&typeof result.then==='function')result.catch(function(err){emit('R12_DIRECT_HANDLER_ASYNC_ERROR',{entryId:entry.id,error:String(err).slice(0,900)});});
                }catch(err){emit('R12_DIRECT_HANDLER_ERROR',{kind:p.kind,entryId:entry.id,score:p.item.score,error:String(err).slice(0,900)});}
                setTimeout(step,110);
              }
              setTimeout(step,80);
              return {ok:true,pending:true,version:state.version,baselineCaptureCount:baseline,attachmentPresent:!!attachment,
                runLabel:runState.runLabel,runScore:runState.runScore,runDisabled:runState.runDisabled,
                formPresent:!!form,submitCandidates:submitters.length,clickCandidates:clickers.length};
            }catch(err){return {ok:false,error:String(err),stack:String(err&&err.stack||'').slice(0,3000)};}
          }

          function replayLastTemplate(){
            const t=state.template;
            if(!t||!t.url||typeof t.body!=='string')return {ok:false,error:'no-string-template'};
            if(Date.now()-Number(t.capturedAt||0)>300000)return {ok:false,error:'template-expired'};
            try{
              const xhr=new XMLHttpRequest();
              xhr.open(t.method||'POST',t.url,true);
              (t.headers||[]).forEach(function(pair){try{xhr.setRequestHeader(pair[0],pair[1]);}catch(_){}});
              xhr.send(t.body);
              emit('R12_TEMPLATE_REPLAY',{ok:true,bodyChars:t.body.length,headerCount:(t.headers||[]).length,attachmentPresent:!!t.attachmentPresent});
              return {ok:true,bodyChars:t.body.length,headerCount:(t.headers||[]).length,attachmentPresent:!!t.attachmentPresent};
            }catch(err){return {ok:false,error:String(err).slice(0,1200)};}
          }

          function describe(){
            const active=entries.filter(function(e){return e.active;});
            const counts={input:0,change:0,click:0,submit:0};
            active.forEach(function(e){if(Object.prototype.hasOwnProperty.call(counts,e.type))counts[e.type]+=1;});
            const t=state.template;
            return {
              ok:true,version:state.version,activeCount:active.length,counts:counts,
              directAttempts:state.directAttempts,directSuccesses:state.directSuccesses,lastRun:state.lastRun,
              template:{present:!!t,templateCount:state.templateCount,bodyChars:t&&typeof t.body==='string'?t.body.length:0,
                headerCount:t&&t.headers?t.headers.length:0,attachmentPresent:!!(t&&t.attachmentPresent),
                ageMs:t?Math.max(0,Date.now()-Number(t.capturedAt||0)):-1}
            };
          }

          window.__AIS_DIRECT_ENGINE__={
            version:state.version,
            invokeDirect:invokeDirect,
            describe:describe,
            replayLastTemplate:replayLastTemplate
          };
          emit('R12_DIRECT_ENGINE_INSTALLED',{version:state.version});
        })();
    """.trimIndent()
}
