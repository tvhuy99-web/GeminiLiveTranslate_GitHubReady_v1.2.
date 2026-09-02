package com.oai.geminilivetranslate.ui

/**
 * R8/R9 adaptive page runtime.
 *
 * R8 discovers the prompt controller from the listener graph itself, without DOM selector lookup.
 * R9 rebuilds discovery from active listeners on every execution and never relies on fixed IDs.
 *
 * R9.1 fixes a device-found regression: ancestor/document/body support handlers now receive
 * cumulative relationship scores, matching the proven R7 behavior. READY candidates are also
 * restricted to connected prompt-like targets that own both input and keydown handlers.
 * R9.2 fixes the embedded JavaScript sort callback syntax and is protected by node --check in CI.
 */
object AiStudioWebSessionAdaptiveRuntime {
    const val VERSION = "2026-09-02-web-session-r9.2-adaptive-runtime"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_ADAPTIVE_RUNTIME__ && window.__AIS_ADAPTIVE_RUNTIME__.version === '$VERSION') return;
          if (!window.EventTarget || !window.EventTarget.prototype) return;

          const nativeAdd=window.EventTarget.prototype.addEventListener;
          const nativeRemove=window.EventTarget.prototype.removeEventListener;
          const entries=[];
          const groups=[];
          let nextEntryId=1;
          let nextGroupId=1;
          let generation=1;

          function emit(kind,payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function tracked(type) {
            const t=String(type||'');
            return t==='input'||t==='change'||t==='keydown';
          }

          function targetMeta(target) {
            try {
              if (target===window) return {kind:'window',tag:'',role:'',valueCapable:false,contentEditable:false,connected:true};
              if (target===document) return {kind:'document',tag:'',role:'',valueCapable:false,contentEditable:false,connected:true};
              if (target&&target.nodeType===11&&target.host) return {kind:'shadow-root',tag:String(target.host.tagName||''),role:'',valueCapable:false,contentEditable:false,connected:!!target.host.isConnected};
              return {
                kind:'element',
                tag:String(target&&target.tagName||'').slice(0,40),
                role:String(target&&target.getAttribute&&target.getAttribute('role')||'').slice(0,80),
                valueCapable:!!(target&&('value' in target)),
                contentEditable:!!(target&&target.isContentEditable),
                connected:target&&typeof target.isConnected==='boolean'?!!target.isConnected:true
              };
            } catch (_) { return {kind:'unknown',tag:'',role:'',valueCapable:false,contentEditable:false,connected:false}; }
          }

          function groupFor(target) {
            for (let i=0;i<groups.length;i++) if (groups[i].target===target) return groups[i];
            const g={id:nextGroupId++,target:target,createdAt:Date.now(),successes:0,failures:0,lastSuccessAt:0,lastFailureAt:0};
            groups.push(g);
            return g;
          }

          function capture(type,target,listener,options) {
            if (!tracked(type)||!listener||entries.length>=2400) return;
            const g=groupFor(target);
            entries.push({id:nextEntryId++,groupId:g.id,type:String(type),target:target,listener:listener,options:options,active:true,at:Date.now()});
            generation+=1;
          }

          function captureFlag(options) {
            try { return typeof options==='boolean'?options:!!(options&&options.capture); }
            catch (_) { return false; }
          }

          window.EventTarget.prototype.addEventListener=function(type,listener,options) {
            try { capture(type,this,listener,options); } catch (_) {}
            return nativeAdd.apply(this,arguments);
          };

          window.EventTarget.prototype.removeEventListener=function(type,listener,options) {
            try {
              if (tracked(type)&&listener) {
                const cap=captureFlag(options);
                for (let i=entries.length-1;i>=0;i--) {
                  const e=entries[i];
                  if(e.active&&e.type===String(type)&&e.target===this&&e.listener===listener&&captureFlag(e.options)===cap){e.active=false;generation+=1;break;}
                }
              }
            } catch (_) {}
            return nativeRemove.apply(this,arguments);
          };

          function activeFor(groupId,type) {
            return entries.filter(function(e){return e.active&&e.groupId===groupId&&(!type||e.type===type);});
          }

          // Cumulative scoring is intentional. A BODY/document ancestor may satisfy more than one
          // relationship at once, exactly as in the device-proven R7 implementation.
          function relationScore(entry,target) {
            if(!entry.active) return -100000;
            let score=0;
            try {
              if(entry.target===target) score+=1200;
              if(entry.target&&typeof entry.target.contains==='function'&&entry.target.contains(target)) score+=700;
              if(entry.target===target.parentElement) score+=350;
              if(entry.target===document) score+=260;
              if(entry.target===window) score+=220;
              if(entry.target===document.body) score+=200;
              if(entry.target&&entry.target.nodeType===11) score+=160;
              if(entry.target&&entry.target.nodeType===11&&entry.target.host&&typeof entry.target.host.contains==='function'&&entry.target.host.contains(target)) score+=620;
              if(Date.now()-Number(entry.at||0)<120000) score+=40;
            } catch (_) {}
            return score;
          }

          function candidateScore(group) {
            const ins=activeFor(group.id,'input');
            const keys=activeFor(group.id,'keydown');
            if(!ins.length||!keys.length) return -100000;
            const meta=targetMeta(group.target);
            let score=2200;
            if(meta.connected) score+=180;
            if(meta.valueCapable) score+=420;
            if(meta.contentEditable) score+=220;
            if(meta.role==='textbox') score+=180;
            if(meta.tag==='TEXTAREA') score+=160;
            if(meta.tag==='INPUT') score+=80;
            const inputAt=ins[ins.length-1].at;
            const keyAt=keys[keys.length-1].at;
            const gap=Math.abs(Number(inputAt)-Number(keyAt));
            if(gap<=25) score+=360; else if(gap<=100) score+=240; else if(gap<=500) score+=100;
            score+=Math.min(1800,group.successes*900);
            score-=Math.min(1800,group.failures*360);
            if(group.lastSuccessAt&&Date.now()-group.lastSuccessAt<600000) score+=700;
            return score;
          }

          function isReadyCandidate(item) {
            const m=item&&item.meta||{};
            if(!m.connected) return false;
            if(!(m.valueCapable||m.contentEditable||m.role==='textbox')) return false;
            if(item.score<3000) return false;
            return activeFor(item.group.id,'input').length>0 && activeFor(item.group.id,'keydown').length>0;
          }

          function candidates() {
            return groups.map(function(g){return {group:g,score:candidateScore(g),meta:targetMeta(g.target)};})
              .filter(function(x){return x.score>-50000;})
              .sort(function(a,b){return b.score-a.score;});
          }

          function readyCandidates() {
            return candidates().filter(isReadyCandidate);
          }

          function eventPath(target) {
            const out=[];let n=target;
            try{while(n){out.push(n);if(n.parentNode)n=n.parentNode;else if(n.host)n=n.host;else break;}}catch(_){}
            if(out.indexOf(document)<0)out.push(document);if(out.indexOf(window)<0)out.push(window);return out;
          }

          function promptTargetProxy(target,prompt) {
            const wanted=String(prompt||'');
            try {
              return new Proxy(target,{
                get:function(obj,prop){
                  if(prop==='value'||prop==='textContent')return wanted;
                  if(prop==='selectionStart'||prop==='selectionEnd')return wanted.length;
                  const v=Reflect.get(obj,prop,obj);return typeof v==='function'?v.bind(obj):v;
                },
                set:function(obj,prop,value){
                  if(prop==='value'||prop==='textContent'||prop==='selectionStart'||prop==='selectionEnd')return true;
                  try{return Reflect.set(obj,prop,value,obj);}catch(_){return true;}
                }
              });
            } catch (_) { return target; }
          }

          function directEvent(type,target,currentTarget,prompt,promptProxy) {
            let nativeEvent;
            if(type==='keydown') nativeEvent=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',ctrlKey:true,bubbles:true,cancelable:true,composed:true});
            else if(type==='input') { try{nativeEvent=new InputEvent('input',{bubbles:true,composed:true,inputType:'insertText',data:String(prompt||'')});}catch(_){nativeEvent=new Event('input',{bubbles:true,composed:true});} }
            else nativeEvent=new Event('change',{bubbles:true,composed:true});
            const path=eventPath(target);
            try {
              return new Proxy(nativeEvent,{get:function(obj,prop){
                if(prop==='target'||prop==='srcElement')return promptProxy;
                if(prop==='currentTarget')return currentTarget===target?promptProxy:currentTarget;
                if(prop==='composedPath')return function(){return path.slice();};
                const v=Reflect.get(obj,prop,obj);return typeof v==='function'?v.bind(obj):v;
              }});
            } catch (_) { return nativeEvent; }
          }

          function invoke(entry,event,target,promptProxy) {
            const thisArg=entry.target===target?promptProxy:entry.target;
            if(typeof entry.listener==='function')return entry.listener.call(thisArg,event);
            if(entry.listener&&typeof entry.listener.handleEvent==='function')return entry.listener.handleEvent.call(entry.listener,event);
            throw new Error('listener-not-callable');
          }

          function supportEntries(type,target,limit) {
            return entries.filter(function(e){return e.active&&e.type===type;})
              .map(function(e){return {entry:e,score:relationScore(e,target),meta:targetMeta(e.target)};})
              .filter(function(x){return x.score>=500;})
              .sort(function(a,b){return b.score-a.score;}).slice(0,limit||16);
          }

          function supportClass(entry,target) {
            try {
              if(entry.target===target) return 'direct';
              if(entry.target===document) return 'document';
              if(entry.target===window) return 'window';
              if(entry.target===document.body) return 'body';
              if(entry.target&&typeof entry.target.contains==='function'&&entry.target.contains(target)) return 'ancestor';
            } catch (_) {}
            return 'other';
          }

          const state={
            version:'$VERSION',
            lastRun:null,
            cancelledGeneration:0,
            discover:function(){
              const all=candidates();
              const ready=all.filter(isReadyCandidate);
              return {ok:true,version:this.version,generation:generation,entryCount:entries.filter(function(e){return e.active;}).length,
                candidateCount:all.length,readyCandidateCount:ready.length,controllerReady:ready.length>0,
                selectorQueryUsed:false,fixedListenerIdsUsed:false,
                top:all.slice(0,10).map(function(x){return {groupId:x.group.id,score:x.score,ready:isReadyCandidate(x),meta:x.meta,successes:x.group.successes,failures:x.group.failures};})};
            },
            cancel:function(){this.cancelledGeneration+=1;return {ok:true,cancelledGeneration:this.cancelledGeneration};},
            generate:function(prompt,marker){
              try {
                const net=window.__AIS_WEB_SESSION__;
                if(!net)return {ok:false,error:'network-probe-not-installed'};
                net.expectedMarker=String(marker||'');net.lastResult=null;net.lastProgress=null;net.lastXhrLifecycle=null;
                const baseline=Number(net.captureCount||0);
                const list=readyCandidates();
                if(!list.length)return {ok:false,error:'no-ready-controller',generation:generation};
                const cancelToken=this.cancelledGeneration;
                const run={startedAt:Date.now(),baselineCaptureCount:baseline,candidateCount:list.length,candidateAttempts:0,inputAttempts:0,keyAttempts:0,
                  successfulGroupId:null,captureStarted:false,finished:false,generation:generation};
                this.lastRun=run;
                emit('R9_DISCOVERY_PLAN',{generation:generation,candidateCount:list.length,baselineCaptureCount:baseline,
                  top:list.slice(0,8).map(function(x){return {groupId:x.group.id,score:x.score,meta:x.meta,successes:x.group.successes,failures:x.group.failures};})});

                let ci=0;
                function started(){return Number(net.captureCount||0)>baseline;}
                function tryCandidate(){
                  if(cancelToken!==state.cancelledGeneration){run.finished=true;emit('R9_CANCELLED',{candidateAttempts:run.candidateAttempts});return;}
                  if(started()){run.captureStarted=true;run.finished=true;emit('R9_HANDLER_SUCCESS',{groupId:run.successfulGroupId,candidateAttempts:run.candidateAttempts,inputAttempts:run.inputAttempts,keyAttempts:run.keyAttempts,captureCount:Number(net.captureCount||0)});return;}
                  if(ci>=list.length){run.finished=true;emit('R9_HANDLER_FINAL',{captureStarted:false,candidateAttempts:run.candidateAttempts,candidateCount:list.length,captureCount:Number(net.captureCount||0)});return;}
                  const item=list[ci++],group=item.group,target=group.target,promptProxy=promptTargetProxy(target,prompt);
                  run.candidateAttempts+=1;run.successfulGroupId=group.id;
                  const inputs=supportEntries('input',target,16).concat(supportEntries('change',target,12)).sort(function(a,b){return b.score-a.score;});
                  const keys=supportEntries('keydown',target,24);
                  const supportCounts={direct:0,document:0,body:0,ancestor:0,window:0,other:0};
                  inputs.forEach(function(x){const k=supportClass(x.entry,target);supportCounts[k]=(supportCounts[k]||0)+1;});
                  emit('R9_CANDIDATE_ATTEMPT',{groupId:group.id,score:item.score,meta:item.meta,inputCandidates:inputs.length,keyCandidates:keys.length,supportCounts:supportCounts,attempt:run.candidateAttempts});
                  inputs.forEach(function(x){
                    run.inputAttempts+=1;
                    try{const ev=directEvent(x.entry.type,target,x.entry.target,prompt,promptProxy);const r=invoke(x.entry,ev,target,promptProxy);
                      emit('R9_INPUT_HANDLER_ATTEMPT',{groupId:group.id,entryId:x.entry.id,type:x.entry.type,score:x.score,supportClass:supportClass(x.entry,target),resultKind:r&&typeof r.then==='function'?'promise':'return'});
                      if(r&&typeof r.then==='function')r.catch(function(err){emit('R9_INPUT_ASYNC_ERROR',{groupId:group.id,entryId:x.entry.id,error:String(err).slice(0,800)});});
                    }catch(err){emit('R9_INPUT_HANDLER_ERROR',{groupId:group.id,entryId:x.entry.id,error:String(err).slice(0,800)});}
                  });
                  let ki=0;
                  function tryKey(){
                    if(started()){
                      group.successes+=1;group.lastSuccessAt=Date.now();run.captureStarted=true;run.finished=true;
                      emit('R9_HANDLER_SUCCESS',{groupId:group.id,candidateAttempts:run.candidateAttempts,inputAttempts:run.inputAttempts,keyAttempts:run.keyAttempts,captureCount:Number(net.captureCount||0),selectorQueryUsed:false,realPromptValueMutated:false});return;
                    }
                    if(ki>=keys.length){group.failures+=1;group.lastFailureAt=Date.now();setTimeout(tryCandidate,80);return;}
                    const x=keys[ki++];run.keyAttempts+=1;
                    try{const ev=directEvent('keydown',target,x.entry.target,prompt,promptProxy);const r=invoke(x.entry,ev,target,promptProxy);
                      emit('R9_KEYDOWN_HANDLER_ATTEMPT',{groupId:group.id,entryId:x.entry.id,score:x.score,supportClass:supportClass(x.entry,target),resultKind:r&&typeof r.then==='function'?'promise':'return'});
                      if(r&&typeof r.then==='function')r.catch(function(err){emit('R9_KEYDOWN_ASYNC_ERROR',{groupId:group.id,entryId:x.entry.id,error:String(err).slice(0,800)});});
                    }catch(err){emit('R9_KEYDOWN_HANDLER_ERROR',{groupId:group.id,entryId:x.entry.id,error:String(err).slice(0,800)});}
                    setTimeout(tryKey,220);
                  }
                  setTimeout(tryKey,120);
                }
                setTimeout(tryCandidate,80);
                return {ok:true,version:this.version,generation:generation,candidateCount:list.length,baselineCaptureCount:baseline,
                  controllerReady:true,selectorQueryUsed:false,fixedListenerIdsUsed:false,domEventDispatchUsed:false,keyboardDispatchUsed:false,
                  realPromptValueMutated:false,runElementUsed:false,motionEventUsed:false};
              } catch(e) { return {ok:false,error:String(e),stack:String(e&&e.stack||'').slice(0,3000)}; }
            }
          };

          window.__AIS_ADAPTIVE_RUNTIME__=state;
          emit('R9_RUNTIME_INSTALLED',{version:state.version,generation:generation});
        })();
    """.trimIndent()
}
