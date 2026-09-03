package com.oai.geminilivetranslate.ui

/**
 * R18.6 zero-UI direct-runtime replay experiment.
 *
 * The historical R17.4/R18.4 oracle remains LAB-only and is used only to create one known-good
 * Start transition. This probe does not click or inspect DOM labels. It observes the exact
 * untrusted Start-button listener invocation, propagates a page-local causal context across async
 * boundaries, captures V8 function/receiver handles at the first Promise scheduling boundary below
 * that listener, and conditionally replays only a uniquely identified non-DOM runtime method.
 *
 * Function/receiver/argument references never leave the WebView. Diagnostics expose structural
 * metadata, type names, hashes, property identity and counter deltas only.
 */
object AiStudioWebSessionR18RuntimeBootstrap {
    const val VERSION = "2026-09-03-r18.6-direct-runtime-replay"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R183B_BOOTSTRAP__&&window.__AIS_R183B_BOOTSTRAP__.version)return;

  const VERSION='2026-09-03-r18.6-direct-runtime-replay';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const MAX_CONTEXTS=160,MAX_FRAMES=18,MAX_CANDIDATES=24;
  const state={
    configured:false,armed:true,targetLanguage:'vi',stage:'idle',lastError:'',
    listenerRoots:0,asyncScheduled:0,asyncRuns:0,contextDrops:0,
    candidateCount:0,safeCandidateCount:0,setupObservations:0,
    directAttempts:0,directReturns:0,directPromises:0,directErrors:0,
    directSetupDelta:0,directBidiDelta:0,directOutcome:'not-attempted',
    directProbeScheduled:false,lastRoot:null,selected:null,top:[],
    learnedCount:0,learnedFrames:[],lastRequestProfile:null
  };
  let currentContext=null,nextContextId=1,directTimer=0;
  const contexts=[],candidates=[];

  function bridge(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R183B_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safeCode(v){const s=String(v||'vi').trim().slice(0,32);return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';}
  function safeSeg(v){return String(v||'').replace(/[^A-Za-z0-9_$.-]/g,'_').slice(0,72)||'_';}
  function hashText(v){let h=2166136261,s=String(v||'');for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return (h>>>0).toString(16);}
  function fnSource(fn){try{return Function.prototype.toString.call(fn);}catch(_){return '';}}
  function typeName(v){try{return v&&v.constructor&&v.constructor.name||typeof v;}catch(_){return typeof v;}}
  function callable(listener){if(typeof listener==='function')return listener;try{if(listener&&typeof listener.handleEvent==='function')return listener.handleEvent;}catch(_){}return null;}
  function isUiObject(value){
    try{if(value===window||value===document)return true;}catch(_){}
    try{if(typeof Node==='function'&&value instanceof Node)return true;}catch(_){}
    try{
      if(typeof EventTarget==='function'&&value instanceof EventTarget){
        const n=typeName(value);if(/Window|Document|Element|Node|ShadowRoot/.test(n))return true;
      }
    }catch(_){}
    return false;
  }
  function isUiArg(value){
    try{if(typeof Event==='function'&&value instanceof Event)return true;}catch(_){}
    return isUiObject(value);
  }
  function liveCounters(){
    let bidi=0,setup=0;
    try{const r=window.__AIS_R18_CAUSAL__&&window.__AIS_R18_CAUSAL__.describe();bidi=Number(r&&r.counters&&r.counters.bidiSend||0);}catch(_){}
    try{const o=window.__AIS_LIVE_OUTPUT_ENGINE__&&window.__AIS_LIVE_OUTPUT_ENGINE__.describe();setup=Number(o&&o.setupCompleteEvents||0);}catch(_){}
    return {bidi:bidi,setup:setup};
  }
  function stackLines(skip,max){
    try{
      const raw=String(new Error().stack||'').split('\n').slice(skip||2,(skip||2)+(max||10)),out=[];
      for(let i=0;i<raw.length;i++){
        const s=String(raw[i]||'').replace(/[?#].*$/,'').replace(/https:\/\/aistudio\.google\.com/g,'https://aistudio.google.com');
        if(s)out.push(s.slice(0,300));
      }
      return out;
    }catch(_){return [];}
  }
  function argumentSnapshot(fn){
    const refs=[];let available=false;
    try{
      const a=fn&&fn.arguments;
      if(a){available=true;for(let i=0;i<Math.min(8,a.length);i++)refs.push(a[i]);}
    }catch(_){}
    return {available:available,refs:refs,types:refs.map(typeName),hasUiArg:refs.some(isUiArg)};
  }
  function propertyIdentity(receiver,fn){
    if(!receiver||typeof fn!=='function'||isUiObject(receiver))return null;
    let owner=receiver;
    for(let depth=0;depth<5&&owner;depth++){
      try{
        const d=Object.getOwnPropertyDescriptors(owner),names=Object.keys(d).slice(0,420);
        for(let i=0;i<names.length;i++){
          const desc=d[names[i]];
          if(desc&&Object.prototype.hasOwnProperty.call(desc,'value')&&desc.value===fn){
            return {owner:owner,name:names[i],depth:depth,ownerType:typeName(owner)};
          }
        }
      }catch(_){}
      try{owner=Object.getPrototypeOf(owner);}catch(_){owner=null;}
      if(owner===Object.prototype||owner===Function.prototype)break;
    }
    return null;
  }
  function fileMeta(file){
    const out={host:'',path:''};
    try{const u=new URL(String(file||''));out.host=String(u.host||'').slice(0,100);out.path=String(u.pathname||'').slice(0,220);}catch(_){}
    return out;
  }
  function structuredFrames(){
    const out=[],old=Error.prepareStackTrace;
    try{
      Error.prepareStackTrace=function(_,stack){return stack;};
      const e=new Error();if(Error.captureStackTrace)Error.captureStackTrace(e,structuredFrames);
      const stack=e.stack;if(!Array.isArray(stack))return out;
      for(let i=0;i<stack.length&&out.length<MAX_FRAMES;i++){
        const cs=stack[i];let fn=null,receiver=null,file='',line=0,column=0,fnName='',methodName='';
        try{fn=cs.getFunction&&cs.getFunction();}catch(_){}
        try{receiver=cs.getThis&&cs.getThis();}catch(_){}
        try{file=String(cs.getFileName&&cs.getFileName()||'');}catch(_){}
        try{line=Number(cs.getLineNumber&&cs.getLineNumber()||0);}catch(_){}
        try{column=Number(cs.getColumnNumber&&cs.getColumnNumber()||0);}catch(_){}
        try{fnName=safeSeg(cs.getFunctionName&&cs.getFunctionName()||'');}catch(_){}
        try{methodName=safeSeg(cs.getMethodName&&cs.getMethodName()||'');}catch(_){}
        if(typeof fn!=='function'||isUiObject(receiver))continue;
        const src=fnSource(fn),fm=fileMeta(file),args=argumentSnapshot(fn),prop=propertyIdentity(receiver,fn);
        out.push({
          fn:fn,receiver:receiver,args:args.refs,property:prop,
          meta:{index:i,fileHost:fm.host,filePath:fm.path,line:line,column:column,
            functionName:fnName,methodName:methodName,receiverType:typeName(receiver),
            arity:Number(fn.length||0),sourceHash:hashText(src),uiCoupled:false,
            argsAvailable:args.available,argTypes:args.types,hasUiArg:args.hasUiArg,
            propertyIdentity:prop?{name:safeSeg(prop.name),depth:prop.depth,ownerType:prop.ownerType}:null}
        });
      }
    }catch(e){bridge('STRUCTURED_STACK_ERROR',{name:String(e&&e.name||'Error')});}
    finally{try{Error.prepareStackTrace=old;}catch(_){} }
    return out;
  }
  function contextMeta(ctx){
    if(!ctx)return null;
    return {id:ctx.id,parentId:ctx.parentId,rootId:ctx.rootId,kind:ctx.kind,depth:ctx.depth,
      runCount:ctx.runCount||0,meta:ctx.meta||{}};
  }
  function makeContext(kind,parent,meta,frames){
    if(contexts.length>=MAX_CONTEXTS){state.contextDrops++;return parent||null;}
    const id=nextContextId++,ctx={id:id,parentId:parent?parent.id:0,rootId:parent?parent.rootId:id,
      kind:String(kind||'async').slice(0,64),depth:parent?parent.depth+1:0,runCount:0,meta:meta||{},
      frames:frames||[],scheduleStack:stackLines(4,10)};
    contexts.push(ctx);if(parent)state.asyncScheduled++;return ctx;
  }
  function runContext(ctx,fn,self,args){
    if(typeof fn!=='function')return;
    const prev=currentContext;currentContext=ctx;if(ctx){ctx.runCount++;state.asyncRuns++;}
    try{return fn.apply(self,args||[]);}finally{currentContext=prev;}
  }
  function candidateScore(frame,ctx){
    const m=frame.meta||{};let score=0;
    if(ctx&&ctx.depth===1)score+=40;
    if(m.propertyIdentity)score+=30;
    if(m.fileHost==='www.gstatic.com'||m.fileHost==='aistudio.google.com')score+=12;
    if(m.receiverType&&m.receiverType!=='Object'&&m.receiverType!=='Function')score+=12;
    if(m.functionName||m.methodName)score+=5;
    score+=Math.min(12,Number(m.index||0));
    if(m.hasUiArg)score-=80;
    if(!m.argsAvailable&&Number(m.arity||0)>0)score-=35;
    return score;
  }
  function addCandidate(frame,ctx){
    if(!frame||!frame.fn||!frame.receiver||isUiObject(frame.receiver))return;
    const score=candidateScore(frame,ctx);
    if(score<45)return;
    let c=null;
    for(let i=0;i<candidates.length;i++)if(candidates[i].fn===frame.fn&&candidates[i].receiver===frame.receiver){c=candidates[i];break;}
    if(!c){
      c={fn:frame.fn,receiver:frame.receiver,args:frame.args||[],property:frame.property,score:score,
        seen:0,rootIds:[],meta:Object.assign({},frame.meta,{score:score})};
      candidates.push(c);
      bridge('RUNTIME_HANDLES_LEARNED',{meta:c.meta});
    }
    c.seen++;
    if(ctx&&c.rootIds.indexOf(ctx.rootId)<0)c.rootIds.push(ctx.rootId);
    if(score>c.score){c.score=score;c.meta.score=score;}
    if(candidates.length>MAX_CANDIDATES)candidates.sort(function(a,b){return b.score-a.score;}).splice(MAX_CANDIDATES);
    refreshCandidateState();
  }
  function refreshCandidateState(){
    candidates.sort(function(a,b){return b.score-a.score;});
    state.candidateCount=candidates.length;
    state.safeCandidateCount=candidates.filter(function(c){
      const m=c.meta||{};return !!c.property&&!m.hasUiArg&&(m.argsAvailable||Number(m.arity||0)===0);
    }).length;
    state.learnedCount=candidates.length;
    state.learnedFrames=candidates.slice(0,12).map(function(c){return c.meta;});
    state.top=candidates.slice(0,8).map(function(c){return Object.assign({},c.meta,{seen:c.seen,rootCount:c.rootIds.length});});
  }
  function captureSchedule(kind,parent){
    const frames=structuredFrames(),ctx=makeContext(kind,parent,{},frames);
    if(parent&&parent.kind==='exact-click-root'){
      for(let i=0;i<frames.length;i++)addCandidate(frames[i],ctx);
    }
    return ctx;
  }
  function wrapScheduled(fn,kind){
    if(typeof fn!=='function'||!currentContext)return fn;
    const ctx=captureSchedule(kind,currentContext);
    return function(){return runContext(ctx,fn,this,Array.prototype.slice.call(arguments));};
  }

  function installListenerRootHook(){
    try{
      const nativeAdd=EventTarget.prototype.addEventListener,nativeRemove=EventTarget.prototype.removeEventListener;
      const maps=new WeakMap();
      EventTarget.prototype.addEventListener=function(type,listener,options){
        const t=String(type||''),fn=callable(listener);
        if(t!=='click'||!fn)return nativeAdd.apply(this,arguments);
        const target=this;
        const wrapped=function(ev){
          let exact=false;
          try{exact=!!state.armed&&ev&&ev.type==='click'&&ev.isTrusted===false&&target===ev.target&&String(target&&target.tagName||'').toUpperCase()==='BUTTON';}catch(_){}
          if(!exact){
            if(typeof listener==='function')return listener.call(this,ev);
            return listener.handleEvent.call(listener,ev);
          }
          const src=fnSource(fn),root=makeContext('exact-click-root',null,{
            listenerSourceHash:hashText(src),listenerArity:Number(fn.length||0),targetType:typeName(target)
          },[]);
          state.listenerRoots++;state.lastRoot=contextMeta(root);state.stage='oracle-root-observed';
          bridge('R186_EXACT_START_ROOT',{root:state.lastRoot});
          return runContext(root,function(){
            if(typeof listener==='function')return listener.call(target,ev);
            return listener.handleEvent.call(listener,ev);
          },null,[]);
        };
        let map=maps.get(target);if(!map){map=new WeakMap();maps.set(target,map);}try{map.set(listener,wrapped);}catch(_){}
        return nativeAdd.call(target,type,wrapped,options);
      };
      EventTarget.prototype.removeEventListener=function(type,listener,options){
        if(String(type||'')==='click'&&listener){
          try{const map=maps.get(this),wrapped=map&&map.get(listener);if(wrapped)return nativeRemove.call(this,type,wrapped,options);}catch(_){}
        }
        return nativeRemove.apply(this,arguments);
      };
    }catch(e){bridge('R186_HOOK_ERROR',{target:'EventTarget',name:String(e&&e.name||'Error')});}
  }
  function installAsyncHooks(){
    try{
      const nativeThen=Promise.prototype.then;
      Promise.prototype.then=function(onFulfilled,onRejected){
        return nativeThen.call(this,wrapScheduled(onFulfilled,'promise-fulfill'),wrapScheduled(onRejected,'promise-reject'));
      };
    }catch(e){bridge('R186_HOOK_ERROR',{target:'Promise.then',name:String(e&&e.name||'Error')});}
    try{
      const nativeCatch=Promise.prototype.catch;
      Promise.prototype.catch=function(onRejected){return nativeCatch.call(this,wrapScheduled(onRejected,'promise-catch'));};
    }catch(_){}
    try{
      const nativeFinally=Promise.prototype.finally;
      if(typeof nativeFinally==='function')Promise.prototype.finally=function(onFinally){return nativeFinally.call(this,wrapScheduled(onFinally,'promise-finally'));};
    }catch(_){}
    try{
      const nativeQ=window.queueMicrotask;
      if(typeof nativeQ==='function')window.queueMicrotask=function(fn){return nativeQ.call(this,wrapScheduled(fn,'queueMicrotask'));};
    }catch(_){}
    try{
      const nativeTimeout=window.setTimeout;
      window.setTimeout=function(fn,delay){
        if(typeof fn!=='function')return nativeTimeout.apply(this,arguments);
        const args=Array.prototype.slice.call(arguments,2),wrapped=wrapScheduled(fn,'setTimeout');
        return nativeTimeout(function(){return wrapped.apply(this,args);},delay);
      };
    }catch(_){}
    try{
      const nativeRaf=window.requestAnimationFrame;
      if(typeof nativeRaf==='function')window.requestAnimationFrame=function(fn){return nativeRaf.call(this,wrapScheduled(fn,'requestAnimationFrame'));};
    }catch(_){}
  }
  function requestProfile(raw){
    try{const u=new URL(String(raw||''),location.href);return {host:String(u.host||'').slice(0,100),path:String(u.pathname||'').slice(0,160)};}catch(_){return {host:'',path:''};}
  }
  function chooseDirectCandidate(){
    refreshCandidateState();
    const safe=candidates.filter(function(c){
      const m=c.meta||{};
      return !!c.property&&!m.hasUiArg&&(m.argsAvailable||Number(m.arity||0)===0);
    }).sort(function(a,b){return b.score-a.score;});
    if(!safe.length)return {candidate:null,reason:'no-safe-candidate'};
    if(safe.length>1&&safe[0].score-safe[1].score<8)return {candidate:null,reason:'ambiguous-candidates'};
    return {candidate:safe[0],reason:'ok'};
  }
  function finishDirectProbe(before){
    const after=liveCounters();
    state.directSetupDelta=after.setup-before.setup;
    state.directBidiDelta=after.bidi-before.bidi;
    if(state.directSetupDelta>0)state.directOutcome='direct-setup-confirmed';
    else if(state.directBidiDelta>=3)state.directOutcome='direct-bidi-activity';
    else state.directOutcome='no-observed-bootstrap';
    state.stage=state.directOutcome;
    bridge('R186_DIRECT_RESULT',{outcome:state.directOutcome,setupDelta:state.directSetupDelta,bidiDelta:state.directBidiDelta,selected:state.selected});
  }
  function tryDirectProbe(){
    state.directProbeScheduled=false;
    const choice=chooseDirectCandidate(),c=choice.candidate;
    if(!c){
      state.directOutcome=choice.reason;state.stage=choice.reason;
      bridge('R186_DIRECT_BLOCKED',{reason:choice.reason,top:state.top});return describe();
    }
    const m=c.meta||{};
    state.selected=Object.assign({},m,{seen:c.seen,rootCount:c.rootIds.length});
    const before=liveCounters();
    state.directAttempts++;state.stage='direct-runtime-invoked';state.directOutcome='pending';
    bridge('R186_DIRECT_ATTEMPT',{selected:state.selected,before:before});
    try{
      const args=m.argsAvailable?c.args:[];
      const ret=c.fn.apply(c.receiver,args);
      state.directReturns++;
      if(ret&&typeof ret.then==='function'){
        state.directPromises++;
        try{ret.then(function(){bridge('R186_DIRECT_PROMISE',{status:'resolved'});},function(){bridge('R186_DIRECT_PROMISE',{status:'rejected'});});}catch(_){}
      }
    }catch(e){
      state.directErrors++;state.lastError=String(e&&e.name||'Error');state.directOutcome='invoke-error';state.stage='invoke-error';
      bridge('R186_DIRECT_ERROR',{name:state.lastError,selected:state.selected});return describe();
    }
    directTimer=window.setTimeout(function(){finishDirectProbe(before);},2500);
    return describe();
  }
  function scheduleDirectProbe(){
    if(state.directProbeScheduled||state.directAttempts>0)return;
    state.directProbeScheduled=true;
    directTimer=window.setTimeout(function(){tryDirectProbe();},1500);
  }
  function installSetupHook(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype||X.prototype.__aisR186Setup)return;
      const open=X.prototype.open,send=X.prototype.send;
      X.prototype.open=function(method,url){try{this.__aisR186Url=String(url||'');}catch(_){}return open.apply(this,arguments);};
      X.prototype.send=function(body){
        try{
          const url=String(this.__aisR186Url||''),text=typeof body==='string'?body:'';
          if(url.indexOf('/v1/bidiGenerateContent')>=0&&text.toLowerCase().indexOf(TARGET_MODEL)>=0&&!/audio\/pcm/i.test(text)){
            state.setupObservations++;state.lastRequestProfile=requestProfile(url);
            if(state.stage!=='direct-runtime-invoked')state.stage='setup-observed';
            bridge('R186_SETUP_OBSERVED',{ordinal:state.setupObservations,profile:state.lastRequestProfile,candidates:state.top});
            if(state.setupObservations===1)scheduleDirectProbe();
          }
        }catch(e){bridge('R186_SETUP_HOOK_ERROR',{name:String(e&&e.name||'Error')});}
        return send.apply(this,arguments);
      };
      X.prototype.__aisR186Setup=true;
    }catch(e){bridge('R186_HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}
  }
  function reset(){
    try{if(directTimer)clearTimeout(directTimer);}catch(_){}directTimer=0;
    state.configured=false;state.armed=true;state.stage='armed';state.lastError='';
    state.listenerRoots=0;state.asyncScheduled=0;state.asyncRuns=0;state.contextDrops=0;
    state.candidateCount=0;state.safeCandidateCount=0;state.setupObservations=0;
    state.directAttempts=0;state.directReturns=0;state.directPromises=0;state.directErrors=0;
    state.directSetupDelta=0;state.directBidiDelta=0;state.directOutcome='not-attempted';
    state.directProbeScheduled=false;state.lastRoot=null;state.selected=null;state.top=[];
    state.learnedCount=0;state.learnedFrames=[];state.lastRequestProfile=null;
    contexts.length=0;candidates.length=0;currentContext=null;nextContextId=1;
    bridge('R186_RESET',{armed:true});return describe();
  }
  function start(code){state.targetLanguage=safeCode(code);state.configured=true;reset();state.configured=true;return describe();}
  function describe(){
    refreshCandidateState();
    return {ok:true,version:VERSION,targetModel:TARGET_MODEL,configured:state.configured,targetLanguage:state.targetLanguage,
      stage:state.stage,lastError:state.lastError,armed:state.armed,listenerRoots:state.listenerRoots,
      asyncScheduled:state.asyncScheduled,asyncRuns:state.asyncRuns,contextDrops:state.contextDrops,
      candidateCount:state.candidateCount,safeCandidateCount:state.safeCandidateCount,
      setupObservations:state.setupObservations,directAttempts:state.directAttempts,directReturns:state.directReturns,
      directPromises:state.directPromises,directErrors:state.directErrors,directSetupDelta:state.directSetupDelta,
      directBidiDelta:state.directBidiDelta,directOutcome:state.directOutcome,lastRoot:state.lastRoot,
      selected:state.selected,top:state.top,learnedCount:state.learnedCount,learnedFrames:state.learnedFrames,
      lastRequestProfile:state.lastRequestProfile,contextCount:contexts.length};
  }

  installListenerRootHook();installAsyncHooks();installSetupHook();
  window.__AIS_R183B_BOOTSTRAP__={version:VERSION,start:start,reset:reset,describe:describe,tryDirectProbe:tryDirectProbe};
  bridge('ENGINE_INSTALLED',{version:VERSION,targetModel:TARGET_MODEL,zeroUi:true,directReplay:true});
})();
""".trimIndent()
}
