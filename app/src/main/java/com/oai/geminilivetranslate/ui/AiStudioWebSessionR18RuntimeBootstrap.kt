package com.oai.geminilivetranslate.ui

/**
 * R18.8 lineage callback warm replay.
 *
 * R18.7 proved the first genuine Live setup is causally linked to an async lineage descended from
 * the exact untrusted Start listener, but it still required receiver/property identity and thus
 * rejected Promise continuations whose `this` is naturally undefined.
 *
 * R18.8 treats the already-captured async callback reference itself as the runtime candidate.
 * Only callbacks that actually ran on the confirmed setup lineage are eligible. The deepest safe
 * callback is replayed once after the oracle-created session is already established. This is a
 * warm replay experiment only: success does NOT prove cold zero-UI bootstrap.
 *
 * No selector, coordinate, synthetic event or UI activation logic is present here. Function and
 * argument references remain page-local; diagnostics export hashes, types, depths and counters.
 */
object AiStudioWebSessionR18RuntimeBootstrap {
    const val VERSION = "2026-09-04-r18.8-lineage-callback-warm-replay"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R183B_BOOTSTRAP__&&window.__AIS_R183B_BOOTSTRAP__.version)return;

  const VERSION='2026-09-04-r18.8-lineage-callback-warm-replay';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const MAX_CONTEXTS=900,MAX_LINEAGE=48,MAX_CANDIDATES=32;
  const nativeSetTimeout=window.setTimeout.bind(window);
  const nativeClearTimeout=window.clearTimeout.bind(window);
  const state={
    configured:false,armed:true,targetLanguage:'vi',stage:'idle',lastError:'',
    listenerRoots:0,asyncScheduled:0,asyncRuns:0,contextDrops:0,
    bidiRequests:0,setupObservations:0,setupLinked:0,setupUnlinked:0,lineageDepth:0,
    callbackCandidateCount:0,safeCallbackCandidateCount:0,callbackTop:[],selected:null,
    replayScheduled:false,callbackReplayAttempts:0,callbackReplayReturns:0,
    callbackReplayPromises:0,callbackReplayErrors:0,callbackReplaySetupSendDelta:0,
    callbackReplayBidiDelta:0,callbackReplayOutcome:'not-run',
    warmOnly:true,coldBootstrapProven:false,lastRoot:null,firstSetup:null,lastSetup:null,
    lastRequestProfile:null
  };
  let currentContext=null,nextContextId=1,replayTimer=null,replayEvalTimer=null;
  const contexts=[],contextById=new Map(),callbackCandidates=[];

  function bridge(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R183B_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safeCode(v){const s=String(v||'vi').trim().slice(0,32);return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';}
  function hashText(v){let h=2166136261,s=String(v||'');for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return (h>>>0).toString(16);}
  function fnSource(fn){try{return Function.prototype.toString.call(fn);}catch(_){return '';}}
  function typeName(v){try{return v&&v.constructor&&v.constructor.name||typeof v;}catch(_){return typeof v;}}
  function callable(listener){if(typeof listener==='function')return listener;try{if(listener&&typeof listener.handleEvent==='function')return listener.handleEvent;}catch(_){}return null;}
  function isUiObject(value){
    try{if(value===window||value===document)return true;}catch(_){}
    try{if(typeof Node==='function'&&value instanceof Node)return true;}catch(_){}
    try{if(typeof EventTarget==='function'&&value instanceof EventTarget){const n=typeName(value);if(/Window|Document|Element|Node|ShadowRoot/.test(n))return true;}}catch(_){}
    return false;
  }
  function isUiArg(value){try{if(typeof Event==='function'&&value instanceof Event)return true;}catch(_){}return isUiObject(value);}
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
  function contextMeta(ctx){
    if(!ctx)return null;
    return {id:ctx.id,parentId:ctx.parentId,rootId:ctx.rootId,kind:ctx.kind,depth:ctx.depth,runCount:ctx.runCount||0,
      callbackHash:ctx.callbackHash||'',callbackArity:ctx.callbackArity,callbackArgTypes:(ctx.callbackArgTypes||[]).slice(0,8),callbackHasUiArg:!!ctx.callbackHasUiArg};
  }
  function makeContext(kind,parent,fn){
    if(contexts.length>=MAX_CONTEXTS){state.contextDrops++;return parent||null;}
    const id=nextContextId++,ctx={
      id:id,parentId:parent?parent.id:0,rootId:parent?parent.rootId:id,kind:String(kind||'async').slice(0,64),depth:parent?parent.depth+1:0,runCount:0,
      fn:typeof fn==='function'?fn:null,callbackHash:typeof fn==='function'?hashText(fnSource(fn)):'',callbackArity:typeof fn==='function'?Number(fn.length||0):-1,
      callbackArgs:[],callbackArgTypes:[],callbackHasUiArg:false,scheduleStack:stackLines(4,10)
    };
    contexts.push(ctx);contextById.set(id,ctx);if(parent)state.asyncScheduled++;return ctx;
  }
  function runContext(ctx,fn,self,args){
    if(typeof fn!=='function')return;
    const prev=currentContext;currentContext=ctx;
    if(ctx){ctx.runCount++;state.asyncRuns++;const a=(args||[]).slice(0,8);ctx.callbackArgs=a;ctx.callbackArgTypes=a.map(typeName);ctx.callbackHasUiArg=a.some(isUiArg);}
    try{return fn.apply(self,args||[]);}finally{currentContext=prev;}
  }
  function wrapScheduled(fn,kind){if(typeof fn!=='function'||!currentContext)return fn;const ctx=makeContext(kind,currentContext,fn);return function(){return runContext(ctx,fn,this,Array.prototype.slice.call(arguments));};}
  function chainFor(ctx){const out=[],seen=new Set();let c=ctx;while(c&&out.length<MAX_LINEAGE&&!seen.has(c.id)){seen.add(c.id);out.push(c);c=c.parentId?contextById.get(c.parentId):null;}return out.reverse();}
  function isSafeCallback(ctx){
    if(!ctx||typeof ctx.fn!=='function'||ctx.kind==='exact-click-root'||ctx.runCount<1||ctx.callbackHasUiArg)return false;
    if(ctx.callbackArity===0)return true;
    return ctx.callbackArgs.length>=ctx.callbackArity&&ctx.callbackArgs.length<=8;
  }
  function callbackScore(ctx,setupDepth){
    const distance=Math.max(0,setupDepth-ctx.depth);let score=220-Math.min(160,distance*24);
    if(ctx.kind.indexOf('promise')===0)score+=18;if(ctx.callbackArity===0)score+=30;
    if(ctx.callbackArgs.length>=ctx.callbackArity)score+=12;if(ctx.callbackHasUiArg)score-=220;if(ctx.runCount>0)score+=10;if(ctx.depth===setupDepth)score+=20;
    return score;
  }
  function analyzeLineage(setupCtx){
    callbackCandidates.length=0;const chain=chainFor(setupCtx),setupDepth=setupCtx?setupCtx.depth:0;state.lineageDepth=chain.length;
    for(let i=0;i<chain.length;i++){
      const ctx=chain[i];if(typeof ctx.fn!=='function'||ctx.kind==='exact-click-root')continue;
      const safe=isSafeCallback(ctx),distance=Math.max(0,setupDepth-ctx.depth),score=callbackScore(ctx,setupDepth);
      callbackCandidates.push({ctx:ctx,fn:ctx.fn,args:ctx.callbackArgs.slice(0,8),safe:safe,score:score,meta:{
        contextId:ctx.id,contextDepth:ctx.depth,distanceFromSetup:distance,contextKind:ctx.kind,callbackHash:ctx.callbackHash,
        callbackArity:ctx.callbackArity,callbackArgTypes:ctx.callbackArgTypes.slice(0,8),callbackHasUiArg:ctx.callbackHasUiArg,runCount:ctx.runCount,safe:safe,score:score
      }});
    }
    callbackCandidates.sort(function(a,b){
      if(a.safe!==b.safe)return a.safe?-1:1;
      if(a.meta.distanceFromSetup!==b.meta.distanceFromSetup)return a.meta.distanceFromSetup-b.meta.distanceFromSetup;
      if(a.meta.callbackArity!==b.meta.callbackArity)return a.meta.callbackArity-b.meta.callbackArity;
      return b.score-a.score;
    });
    if(callbackCandidates.length>MAX_CANDIDATES)callbackCandidates.splice(MAX_CANDIDATES);
    state.callbackCandidateCount=callbackCandidates.length;state.safeCallbackCandidateCount=callbackCandidates.filter(function(c){return c.safe;}).length;
    state.callbackTop=callbackCandidates.slice(0,12).map(function(c){return c.meta;});
    const safe=callbackCandidates.filter(function(c){return c.safe;});
    if(safe.length){const best=safe[0],second=safe[1],margin=second?best.score-second.score:999;state.selected=Object.assign({},best.meta,{selectionMargin:margin,warmOnly:true});}else state.selected=null;
    return chain;
  }
  function selectedCandidate(){
    if(!state.selected)return null;
    for(let i=0;i<callbackCandidates.length;i++)if(callbackCandidates[i].meta.contextId===state.selected.contextId)return callbackCandidates[i];
    return null;
  }
  function scheduleWarmReplay(){
    if(state.replayScheduled||state.callbackReplayAttempts>0)return;
    const c=selectedCandidate();
    if(!c||!c.safe){state.callbackReplayOutcome='no-safe-callback';state.stage='lineage-captured-no-safe-callback';return;}
    state.replayScheduled=true;state.stage='warm-replay-scheduled';replayTimer=nativeSetTimeout(function(){runWarmReplay();},1800);
  }
  function runWarmReplay(){
    state.replayScheduled=false;const c=selectedCandidate();
    if(!c||!c.safe){state.callbackReplayOutcome='candidate-lost';state.stage='warm-replay-skipped';return;}
    const beforeSetup=state.setupObservations,beforeBidi=state.bidiRequests;state.callbackReplayAttempts++;state.stage='warm-replay-running';
    const replayCtx=makeContext('warm-callback-replay-root',null,c.fn);
    try{
      const args=c.meta.callbackArity===0?[]:c.args.slice(0,c.meta.callbackArity);const ret=runContext(replayCtx,c.fn,undefined,args);state.callbackReplayReturns++;
      if(ret&&typeof ret.then==='function'){state.callbackReplayPromises++;try{ret.catch(function(){state.callbackReplayErrors++;});}catch(_){}}
    }catch(e){state.callbackReplayErrors++;state.lastError=String(e&&e.name||'Error');}
    replayEvalTimer=nativeSetTimeout(function(){
      state.callbackReplaySetupSendDelta=Math.max(0,state.setupObservations-beforeSetup);state.callbackReplayBidiDelta=Math.max(0,state.bidiRequests-beforeBidi);
      if(state.callbackReplaySetupSendDelta>0)state.callbackReplayOutcome='warm-setup-replayed';
      else if(state.callbackReplayBidiDelta>0)state.callbackReplayOutcome='warm-bidi-activity';
      else if(state.callbackReplayErrors>0)state.callbackReplayOutcome='warm-replay-error';
      else state.callbackReplayOutcome='warm-no-network-delta';
      state.stage='warm-replay-complete';
      bridge('R188_WARM_REPLAY_RESULT',{selected:state.selected,callbackReplaySetupSendDelta:state.callbackReplaySetupSendDelta,
        callbackReplayBidiDelta:state.callbackReplayBidiDelta,callbackReplayErrors:state.callbackReplayErrors,callbackReplayOutcome:state.callbackReplayOutcome,
        warmOnly:true,coldBootstrapProven:false});
    },1200);
  }

  function installListenerRootHook(){
    try{
      const nativeAdd=EventTarget.prototype.addEventListener,nativeRemove=EventTarget.prototype.removeEventListener,maps=new WeakMap();
      EventTarget.prototype.addEventListener=function(type,listener,options){
        const t=String(type||''),fn=callable(listener);if(t!=='click'||!fn)return nativeAdd.apply(this,arguments);const target=this;
        const wrapped=function(ev){
          let exact=false;try{exact=!!state.armed&&ev&&ev.type==='click'&&ev.isTrusted===false&&target===ev.target&&String(target&&target.tagName||'').toUpperCase()==='BUTTON';}catch(_){}
          if(!exact){if(typeof listener==='function')return listener.call(this,ev);return listener.handleEvent.call(listener,ev);}
          const root=makeContext('exact-click-root',null,fn);state.listenerRoots++;state.lastRoot=contextMeta(root);state.stage='oracle-root-observed';bridge('R188_EXACT_START_ROOT',{root:state.lastRoot});
          return runContext(root,function(){if(typeof listener==='function')return listener.call(target,ev);return listener.handleEvent.call(listener,ev);},null,[]);
        };
        let map=maps.get(target);if(!map){map=new WeakMap();maps.set(target,map);}try{map.set(listener,wrapped);}catch(_){}return nativeAdd.call(target,type,wrapped,options);
      };
      EventTarget.prototype.removeEventListener=function(type,listener,options){if(String(type||'')==='click'&&listener){try{const map=maps.get(this),wrapped=map&&map.get(listener);if(wrapped)return nativeRemove.call(this,type,wrapped,options);}catch(_){} }return nativeRemove.apply(this,arguments);};
    }catch(e){bridge('R188_HOOK_ERROR',{target:'EventTarget',name:String(e&&e.name||'Error')});}
  }
  function installAsyncHooks(){
    try{const nativeThen=Promise.prototype.then;Promise.prototype.then=function(a,b){return nativeThen.call(this,wrapScheduled(a,'promise-fulfill'),wrapScheduled(b,'promise-reject'));};}catch(e){bridge('R188_HOOK_ERROR',{target:'Promise.then',name:String(e&&e.name||'Error')});}
    try{const nativeCatch=Promise.prototype.catch;Promise.prototype.catch=function(a){return nativeCatch.call(this,wrapScheduled(a,'promise-catch'));};}catch(_){}
    try{const nativeFinally=Promise.prototype.finally;if(typeof nativeFinally==='function')Promise.prototype.finally=function(a){return nativeFinally.call(this,wrapScheduled(a,'promise-finally'));};}catch(_){}
    try{const nativeQ=window.queueMicrotask;if(typeof nativeQ==='function')window.queueMicrotask=function(fn){return nativeQ.call(this,wrapScheduled(fn,'queueMicrotask'));};}catch(_){}
    try{const nativeTimeout=window.setTimeout;window.setTimeout=function(fn,delay){if(typeof fn!=='function')return nativeTimeout.apply(this,arguments);const args=Array.prototype.slice.call(arguments,2),wrapped=wrapScheduled(fn,'setTimeout');return nativeTimeout(function(){return wrapped.apply(this,args);},delay);};}catch(_){}
    try{const nativeInterval=window.setInterval;window.setInterval=function(fn,delay){if(typeof fn!=='function')return nativeInterval.apply(this,arguments);const args=Array.prototype.slice.call(arguments,2),wrapped=wrapScheduled(fn,'setInterval');return nativeInterval(function(){return wrapped.apply(this,args);},delay);};}catch(_){}
    try{const nativeRaf=window.requestAnimationFrame;if(typeof nativeRaf==='function')window.requestAnimationFrame=function(fn){return nativeRaf.call(this,wrapScheduled(fn,'requestAnimationFrame'));};}catch(_){}
    try{if(window.scheduler&&typeof window.scheduler.postTask==='function'){const nativePost=window.scheduler.postTask.bind(window.scheduler);window.scheduler.postTask=function(fn,options){return nativePost(wrapScheduled(fn,'scheduler.postTask'),options);};}}catch(_){}
    try{
      const NativeMC=window.MessageChannel,MP=window.MessagePort&&window.MessagePort.prototype;
      if(typeof NativeMC==='function'&&MP){
        const peers=new WeakMap(),queues=new WeakMap(),listenerMaps=new WeakMap();
        function CausalMessageChannel(){const ch=new NativeMC();try{peers.set(ch.port1,ch.port2);peers.set(ch.port2,ch.port1);}catch(_){}return ch;}
        CausalMessageChannel.prototype=NativeMC.prototype;try{Object.setPrototypeOf(CausalMessageChannel,NativeMC);}catch(_){}window.MessageChannel=CausalMessageChannel;
        const nativePost=MP.postMessage,nativeAdd=MP.addEventListener,nativeRemove=MP.removeEventListener;
        MP.postMessage=function(){try{if(currentContext){const target=peers.get(this);if(target){const ctx=makeContext('MessagePort.postMessage',currentContext,null),q=queues.get(target)||[];q.push(ctx);if(q.length>32)q.shift();queues.set(target,q);}}}catch(_){}return nativePost.apply(this,arguments);};
        MP.addEventListener=function(type,listener,options){
          if(String(type||'')==='message'&&typeof listener==='function'){
            let map=listenerMaps.get(this);if(!map){map=new WeakMap();listenerMaps.set(this,map);}let wrapped=map.get(listener);
            if(!wrapped){wrapped=function(ev){let ctx=null;try{const q=queues.get(this)||[];ctx=q.length?q.shift():null;}catch(_){}if(ctx)return runContext(ctx,listener,this,[ev]);return listener.call(this,ev);};map.set(listener,wrapped);}return nativeAdd.call(this,type,wrapped,options);
          }
          return nativeAdd.apply(this,arguments);
        };
        MP.removeEventListener=function(type,listener,options){if(String(type||'')==='message'&&typeof listener==='function'){try{const map=listenerMaps.get(this),wrapped=map&&map.get(listener);if(wrapped)return nativeRemove.call(this,type,wrapped,options);}catch(_){} }return nativeRemove.apply(this,arguments);};
      }
    }catch(e){bridge('R188_HOOK_ERROR',{target:'MessageChannel',name:String(e&&e.name||'Error')});}
  }
  function installSetupHook(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype||X.prototype.__aisR188Setup)return;const open=X.prototype.open,send=X.prototype.send;
      X.prototype.open=function(method,url){try{this.__aisR188Url=String(url||'');}catch(_){}return open.apply(this,arguments);};
      X.prototype.send=function(body){
        try{
          const url=String(this.__aisR188Url||'');if(url.indexOf('/v1/bidiGenerateContent')>=0)state.bidiRequests++;
          const text=typeof body==='string'?body:'';
          if(url.indexOf('/v1/bidiGenerateContent')>=0&&text.toLowerCase().indexOf(TARGET_MODEL)>=0&&!/audio\/pcm/i.test(text)){
            state.setupObservations++;const ctx=currentContext;if(ctx)state.setupLinked++;else state.setupUnlinked++;
            state.lastRequestProfile=(function(){try{const u=new URL(url,location.href);return {host:String(u.host||'').slice(0,120),path:String(u.pathname||'').slice(0,180)};}catch(_){return {host:'',path:''};}})();
            if(state.setupObservations===1){
              const chain=ctx?analyzeLineage(ctx):[];state.stage=ctx?'lineage-captured':'setup-unlinked';
              state.firstSetup={linked:!!ctx,context:contextMeta(ctx),lineage:chain.map(contextMeta),callbackCandidateCount:state.callbackCandidateCount,
                safeCallbackCandidateCount:state.safeCallbackCandidateCount,selected:state.selected,callbackTop:state.callbackTop};
              bridge(ctx?'R188_LINEAGE_CAPTURED':'R188_SETUP_UNLINKED',state.firstSetup);if(ctx)scheduleWarmReplay();
            }
            state.lastSetup={linked:!!ctx,context:contextMeta(ctx),setupObservation:state.setupObservations};
          }
        }catch(e){state.lastError=String(e&&e.name||'Error');bridge('R188_SETUP_ERROR',{name:state.lastError});}
        return send.apply(this,arguments);
      };
      X.prototype.__aisR188Setup=true;
    }catch(e){bridge('R188_HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}
  }
  function configure(code){state.configured=true;state.targetLanguage=safeCode(code);return describe();}
  function reset(){
    if(replayTimer!==null){try{nativeClearTimeout(replayTimer);}catch(_){}replayTimer=null;}if(replayEvalTimer!==null){try{nativeClearTimeout(replayEvalTimer);}catch(_){}replayEvalTimer=null;}
    state.armed=true;state.stage='idle';state.lastError='';state.listenerRoots=0;state.asyncScheduled=0;state.asyncRuns=0;state.contextDrops=0;
    state.bidiRequests=0;state.setupObservations=0;state.setupLinked=0;state.setupUnlinked=0;state.lineageDepth=0;
    state.callbackCandidateCount=0;state.safeCallbackCandidateCount=0;state.callbackTop=[];state.selected=null;state.replayScheduled=false;
    state.callbackReplayAttempts=0;state.callbackReplayReturns=0;state.callbackReplayPromises=0;state.callbackReplayErrors=0;
    state.callbackReplaySetupSendDelta=0;state.callbackReplayBidiDelta=0;state.callbackReplayOutcome='not-run';state.warmOnly=true;state.coldBootstrapProven=false;
    state.lastRoot=null;state.firstSetup=null;state.lastSetup=null;state.lastRequestProfile=null;currentContext=null;nextContextId=1;contexts.length=0;contextById.clear();callbackCandidates.length=0;
    return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,targetModel:TARGET_MODEL,configured:state.configured,targetLanguage:state.targetLanguage,stage:state.stage,lastError:state.lastError,armed:state.armed,
      listenerRoots:state.listenerRoots,asyncScheduled:state.asyncScheduled,asyncRuns:state.asyncRuns,contextDrops:state.contextDrops,bidiRequests:state.bidiRequests,
      setupObservations:state.setupObservations,setupLinked:state.setupLinked,setupUnlinked:state.setupUnlinked,lineageDepth:state.lineageDepth,
      callbackCandidateCount:state.callbackCandidateCount,safeCallbackCandidateCount:state.safeCallbackCandidateCount,callbackTop:state.callbackTop,selected:state.selected,
      replayScheduled:state.replayScheduled,callbackReplayAttempts:state.callbackReplayAttempts,callbackReplayReturns:state.callbackReplayReturns,
      callbackReplayPromises:state.callbackReplayPromises,callbackReplayErrors:state.callbackReplayErrors,callbackReplaySetupSendDelta:state.callbackReplaySetupSendDelta,
      callbackReplayBidiDelta:state.callbackReplayBidiDelta,callbackReplayOutcome:state.callbackReplayOutcome,warmOnly:state.warmOnly,coldBootstrapProven:state.coldBootstrapProven,
      lastRoot:state.lastRoot,firstSetup:state.firstSetup,lastSetup:state.lastSetup,lastRequestProfile:state.lastRequestProfile,contextCount:contexts.length};
  }

  installListenerRootHook();installAsyncHooks();installSetupHook();
  window.__AIS_R183B_BOOTSTRAP__={version:VERSION,configure:configure,reset:reset,describe:describe};
  bridge('R188_ENGINE_INSTALLED',{version:VERSION,warmOnly:true,coldBootstrapProven:false,targetModel:TARGET_MODEL});
})();
""".trimIndent()
}
