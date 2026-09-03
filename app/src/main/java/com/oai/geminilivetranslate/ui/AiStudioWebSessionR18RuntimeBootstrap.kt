package com.oai.geminilivetranslate.ui

/**
 * R18.7 lineage-guided runtime capture.
 *
 * R18.5 proved the LAB Start oracle is causally linked to the first genuine Live setup. R18.6
 * looked only at the first async boundary and therefore missed the useful runtime frames near the
 * setup. R18.7 keeps page-local function/receiver handles for every async context descended from
 * the exact untrusted Start listener, then waits for the first non-audio Live setup and ranks only
 * frames that belong to that confirmed lineage.
 *
 * This probe is learning-only. It never invokes a learned runtime function and contains no DOM
 * selector, coordinate, synthetic event or UI activation logic. Function/receiver/argument values
 * stay inside the WebView; diagnostics expose only structural metadata, hashes and type names.
 */
object AiStudioWebSessionR18RuntimeBootstrap {
    const val VERSION = "2026-09-04-r18.7-lineage-guided-runtime-capture"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R183B_BOOTSTRAP__&&window.__AIS_R183B_BOOTSTRAP__.version)return;

  const VERSION='2026-09-04-r18.7-lineage-guided-runtime-capture';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const MAX_CONTEXTS=700,MAX_FRAMES=28,MAX_LINEAGE=40,MAX_CANDIDATES=80;
  const state={
    configured:false,armed:true,targetLanguage:'vi',stage:'idle',lastError:'',
    listenerRoots:0,asyncScheduled:0,asyncRuns:0,contextDrops:0,
    setupObservations:0,setupLinked:0,setupUnlinked:0,
    candidateCount:0,safeCandidateCount:0,lineageDepth:0,
    directAttempts:0,directReturns:0,directPromises:0,directErrors:0,
    directSetupDelta:0,directBidiDelta:0,directOutcome:'disabled-r18.7-learning-only',
    lastRoot:null,lastSetup:null,selected:null,top:[],learnedCount:0,learnedFrames:[],
    lastRequestProfile:null
  };
  let currentContext=null,nextContextId=1;
  const contexts=[],contextById=new Map(),lineageCandidates=[];

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
  function fileMeta(file){const out={host:'',path:''};try{const u=new URL(String(file||''));out.host=String(u.host||'').slice(0,100);out.path=String(u.pathname||'').slice(0,220);}catch(_){}return out;}
  function argumentSnapshot(fn){
    const refs=[];let available=false;
    try{const a=fn&&fn.arguments;if(a){available=true;for(let i=0;i<Math.min(8,a.length);i++)refs.push(a[i]);}}catch(_){}
    return {available:available,refs:refs,types:refs.map(typeName),hasUiArg:refs.some(isUiArg)};
  }
  function propertyIdentity(receiver,fn){
    if(!receiver||typeof fn!=='function'||isUiObject(receiver))return null;
    let owner=receiver;
    for(let depth=0;depth<6&&owner;depth++){
      try{
        const d=Object.getOwnPropertyDescriptors(owner),names=Object.keys(d).slice(0,480);
        for(let i=0;i<names.length;i++){
          const desc=d[names[i]];
          if(desc&&Object.prototype.hasOwnProperty.call(desc,'value')&&desc.value===fn)return {owner:owner,name:names[i],depth:depth,ownerType:typeName(owner)};
        }
      }catch(_){}
      try{owner=Object.getPrototypeOf(owner);}catch(_){owner=null;}
      if(owner===Object.prototype||owner===Function.prototype)break;
    }
    return null;
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
        out.push({fn:fn,receiver:receiver,args:args.refs,property:prop,meta:{
          index:i,fileHost:fm.host,filePath:fm.path,line:line,column:column,functionName:fnName,methodName:methodName,
          receiverType:typeName(receiver),arity:Number(fn.length||0),sourceHash:hashText(src),argsAvailable:args.available,
          argTypes:args.types,hasUiArg:args.hasUiArg,propertyIdentity:prop?{name:safeSeg(prop.name),depth:prop.depth,ownerType:prop.ownerType}:null
        }});
      }
    }catch(e){bridge('R187_STRUCTURED_STACK_ERROR',{name:String(e&&e.name||'Error')});}
    finally{try{Error.prepareStackTrace=old;}catch(_){} }
    return out;
  }
  function contextMeta(ctx){
    if(!ctx)return null;
    return {id:ctx.id,parentId:ctx.parentId,rootId:ctx.rootId,kind:ctx.kind,depth:ctx.depth,runCount:ctx.runCount||0,
      callbackHash:ctx.callbackHash||'',callbackArity:ctx.callbackArity,callbackArgTypes:(ctx.callbackArgTypes||[]).slice(0,8)};
  }
  function makeContext(kind,parent,fn,frames){
    if(contexts.length>=MAX_CONTEXTS){state.contextDrops++;return parent||null;}
    const id=nextContextId++,ctx={
      id:id,parentId:parent?parent.id:0,rootId:parent?parent.rootId:id,kind:String(kind||'async').slice(0,64),
      depth:parent?parent.depth+1:0,runCount:0,fn:typeof fn==='function'?fn:null,frames:frames||[],
      callbackHash:typeof fn==='function'?hashText(fnSource(fn)):'',callbackArity:typeof fn==='function'?Number(fn.length||0):-1,
      callbackArgs:[],callbackArgTypes:[],callbackHasUiArg:false,scheduleStack:stackLines(4,10)
    };
    contexts.push(ctx);contextById.set(id,ctx);if(parent)state.asyncScheduled++;return ctx;
  }
  function runContext(ctx,fn,self,args){
    if(typeof fn!=='function')return;
    const prev=currentContext;currentContext=ctx;
    if(ctx){
      ctx.runCount++;state.asyncRuns++;
      const a=(args||[]).slice(0,8);ctx.callbackArgs=a;ctx.callbackArgTypes=a.map(typeName);ctx.callbackHasUiArg=a.some(isUiArg);
    }
    try{return fn.apply(self,args||[]);}finally{currentContext=prev;}
  }
  function captureSchedule(kind,parent,fn){const frames=structuredFrames();return makeContext(kind,parent,fn,frames);}
  function wrapScheduled(fn,kind){if(typeof fn!=='function'||!currentContext)return fn;const ctx=captureSchedule(kind,currentContext,fn);return function(){return runContext(ctx,fn,this,Array.prototype.slice.call(arguments));};}
  function chainFor(ctx){const out=[],seen=new Set();let c=ctx;while(c&&out.length<MAX_LINEAGE&&!seen.has(c.id)){seen.add(c.id);out.push(c);c=c.parentId?contextById.get(c.parentId):null;}return out.reverse();}
  function transportLike(meta){const n=String((meta&&meta.functionName)||'')+' '+String((meta&&meta.methodName)||'');return /(^|[._$])send($|[._$])/i.test(n);}
  function candidateScore(frame,ctx,setupDepth){
    const m=frame.meta||{},distance=Math.max(0,setupDepth-ctx.depth);let score=120-Math.min(90,distance*14);
    if(m.fileHost==='www.gstatic.com'||m.fileHost==='aistudio.google.com')score+=18;else score-=35;
    if(m.propertyIdentity)score+=28;
    if(m.receiverType&&m.receiverType!=='Object'&&m.receiverType!=='Function'&&m.receiverType!=='undefined')score+=10;
    if(m.argsAvailable)score+=8;if(Number(m.arity||0)===0)score+=8;if(m.hasUiArg)score-=100;if(transportLike(m))score-=32;
    score-=Math.min(18,Number(m.index||0)*2);return score;
  }
  function analyzeLineage(setupCtx){
    lineageCandidates.length=0;
    const chain=chainFor(setupCtx),setupDepth=setupCtx?setupCtx.depth:0,seen=new Set();state.lineageDepth=chain.length;
    for(let ci=0;ci<chain.length;ci++){
      const ctx=chain[ci],frames=ctx.frames||[];
      for(let fi=0;fi<frames.length;fi++){
        const frame=frames[fi],m=frame.meta||{};
        if(typeof frame.fn!=='function'||isUiObject(frame.receiver))continue;
        if(m.fileHost!=='www.gstatic.com'&&m.fileHost!=='aistudio.google.com')continue;
        const key=m.sourceHash+'|'+m.receiverType+'|'+(m.propertyIdentity?m.propertyIdentity.name:'')+'|'+ctx.id;if(seen.has(key))continue;seen.add(key);
        const score=candidateScore(frame,ctx,setupDepth),distance=Math.max(0,setupDepth-ctx.depth);
        lineageCandidates.push({fn:frame.fn,receiver:frame.receiver,args:frame.args||[],property:frame.property,score:score,ctx:ctx,meta:Object.assign({},m,{
          score:score,contextId:ctx.id,contextDepth:ctx.depth,distanceFromSetup:distance,contextKind:ctx.kind,
          transportLike:transportLike(m),callbackHash:ctx.callbackHash||'',callbackArgTypes:(ctx.callbackArgTypes||[]).slice(0,8)
        })});
      }
    }
    lineageCandidates.sort(function(a,b){return b.score-a.score;});if(lineageCandidates.length>MAX_CANDIDATES)lineageCandidates.splice(MAX_CANDIDATES);
    state.candidateCount=lineageCandidates.length;
    state.safeCandidateCount=lineageCandidates.filter(function(c){const m=c.meta||{};return !m.hasUiArg&&!!m.property&&(m.argsAvailable||Number(m.arity||0)===0)&&!m.transportLike;}).length;
    state.learnedCount=lineageCandidates.length;state.learnedFrames=lineageCandidates.slice(0,20).map(function(c){return c.meta;});state.top=lineageCandidates.slice(0,12).map(function(c){return c.meta;});
    const safe=lineageCandidates.filter(function(c){const m=c.meta||{};return !m.hasUiArg&&!!m.property&&(m.argsAvailable||Number(m.arity||0)===0)&&!m.transportLike;});
    if(safe.length){const best=safe[0],second=safe[1],margin=second?best.score-second.score:999;state.selected=Object.assign({},best.meta,{selectionMargin:margin,learningOnly:true});}else state.selected=null;
    return chain;
  }

  function installListenerRootHook(){
    try{
      const nativeAdd=EventTarget.prototype.addEventListener,nativeRemove=EventTarget.prototype.removeEventListener,maps=new WeakMap();
      EventTarget.prototype.addEventListener=function(type,listener,options){
        const t=String(type||''),fn=callable(listener);if(t!=='click'||!fn)return nativeAdd.apply(this,arguments);const target=this;
        const wrapped=function(ev){
          let exact=false;try{exact=!!state.armed&&ev&&ev.type==='click'&&ev.isTrusted===false&&target===ev.target&&String(target&&target.tagName||'').toUpperCase()==='BUTTON';}catch(_){}
          if(!exact){if(typeof listener==='function')return listener.call(this,ev);return listener.handleEvent.call(listener,ev);}
          const root=makeContext('exact-click-root',null,fn,[]);state.listenerRoots++;state.lastRoot=contextMeta(root);state.stage='oracle-root-observed';bridge('R187_EXACT_START_ROOT',{root:state.lastRoot});
          return runContext(root,function(){if(typeof listener==='function')return listener.call(target,ev);return listener.handleEvent.call(listener,ev);},null,[]);
        };
        let map=maps.get(target);if(!map){map=new WeakMap();maps.set(target,map);}try{map.set(listener,wrapped);}catch(_){}return nativeAdd.call(target,type,wrapped,options);
      };
      EventTarget.prototype.removeEventListener=function(type,listener,options){if(String(type||'')==='click'&&listener){try{const map=maps.get(this),wrapped=map&&map.get(listener);if(wrapped)return nativeRemove.call(this,type,wrapped,options);}catch(_){} }return nativeRemove.apply(this,arguments);};
    }catch(e){bridge('R187_HOOK_ERROR',{target:'EventTarget',name:String(e&&e.name||'Error')});}
  }
  function installAsyncHooks(){
    try{const nativeThen=Promise.prototype.then;Promise.prototype.then=function(a,b){return nativeThen.call(this,wrapScheduled(a,'promise-fulfill'),wrapScheduled(b,'promise-reject'));};}catch(e){bridge('R187_HOOK_ERROR',{target:'Promise.then',name:String(e&&e.name||'Error')});}
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
        MP.postMessage=function(){try{if(currentContext){const target=peers.get(this);if(target){const ctx=makeContext('MessagePort.postMessage',currentContext,null,structuredFrames());const q=queues.get(target)||[];q.push(ctx);if(q.length>32)q.shift();queues.set(target,q);}}}catch(_){}return nativePost.apply(this,arguments);};
        MP.addEventListener=function(type,listener,options){
          if(String(type||'')==='message'&&typeof listener==='function'){
            let map=listenerMaps.get(this);if(!map){map=new WeakMap();listenerMaps.set(this,map);}let wrapped=map.get(listener);
            if(!wrapped){wrapped=function(ev){let ctx=null;try{const q=queues.get(this)||[];ctx=q.length?q.shift():null;}catch(_){}if(ctx)return runContext(ctx,listener,this,[ev]);return listener.call(this,ev);};map.set(listener,wrapped);}return nativeAdd.call(this,type,wrapped,options);
          }
          return nativeAdd.apply(this,arguments);
        };
        MP.removeEventListener=function(type,listener,options){if(String(type||'')==='message'&&typeof listener==='function'){try{const map=listenerMaps.get(this),wrapped=map&&map.get(listener);if(wrapped)return nativeRemove.call(this,type,wrapped,options);}catch(_){} }return nativeRemove.apply(this,arguments);};
      }
    }catch(e){bridge('R187_HOOK_ERROR',{target:'MessageChannel',name:String(e&&e.name||'Error')});}
  }
  function installSetupHook(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype||X.prototype.__aisR187Setup)return;const open=X.prototype.open,send=X.prototype.send;
      X.prototype.open=function(method,url){try{this.__aisR187Url=String(url||'');}catch(_){}return open.apply(this,arguments);};
      X.prototype.send=function(body){
        try{
          const url=String(this.__aisR187Url||''),text=typeof body==='string'?body:'';
          if(url.indexOf('/v1/bidiGenerateContent')>=0&&text.toLowerCase().indexOf(TARGET_MODEL)>=0&&!/audio\/pcm/i.test(text)){
            state.setupObservations++;const ctx=currentContext;if(ctx)state.setupLinked++;else state.setupUnlinked++;const chain=ctx?analyzeLineage(ctx):[];state.stage=ctx?'lineage-captured':'setup-unlinked';
            state.lastRequestProfile=(function(){try{const u=new URL(url,location.href);return {host:String(u.host||'').slice(0,120),path:String(u.pathname||'').slice(0,180)};}catch(_){return {host:'',path:''};}})();
            state.lastSetup={linked:!!ctx,context:contextMeta(ctx),lineage:chain.map(contextMeta),candidateCount:state.candidateCount,safeCandidateCount:state.safeCandidateCount,selected:state.selected,top:state.top};
            bridge(ctx?'R187_LINEAGE_CAPTURED':'R187_SETUP_UNLINKED',state.lastSetup);
          }
        }catch(e){state.lastError=String(e&&e.name||'Error');bridge('R187_SETUP_ERROR',{name:state.lastError});}
        return send.apply(this,arguments);
      };
      X.prototype.__aisR187Setup=true;
    }catch(e){bridge('R187_HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}
  }
  function configure(code){state.configured=true;state.targetLanguage=safeCode(code);return describe();}
  function reset(){
    state.armed=true;state.stage='idle';state.lastError='';state.listenerRoots=0;state.asyncScheduled=0;state.asyncRuns=0;state.contextDrops=0;state.setupObservations=0;state.setupLinked=0;state.setupUnlinked=0;
    state.candidateCount=0;state.safeCandidateCount=0;state.lineageDepth=0;state.directAttempts=0;state.directReturns=0;state.directPromises=0;state.directErrors=0;state.directSetupDelta=0;state.directBidiDelta=0;
    state.directOutcome='disabled-r18.7-learning-only';state.lastRoot=null;state.lastSetup=null;state.selected=null;state.top=[];state.learnedCount=0;state.learnedFrames=[];state.lastRequestProfile=null;
    currentContext=null;nextContextId=1;contexts.length=0;contextById.clear();lineageCandidates.length=0;return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,targetModel:TARGET_MODEL,configured:state.configured,targetLanguage:state.targetLanguage,stage:state.stage,lastError:state.lastError,armed:state.armed,
      listenerRoots:state.listenerRoots,asyncScheduled:state.asyncScheduled,asyncRuns:state.asyncRuns,contextDrops:state.contextDrops,setupObservations:state.setupObservations,setupLinked:state.setupLinked,setupUnlinked:state.setupUnlinked,lineageDepth:state.lineageDepth,
      candidateCount:state.candidateCount,safeCandidateCount:state.safeCandidateCount,selected:state.selected,top:state.top,learnedCount:state.learnedCount,learnedFrames:state.learnedFrames,lastRoot:state.lastRoot,lastSetup:state.lastSetup,lastRequestProfile:state.lastRequestProfile,
      directAttempts:state.directAttempts,directReturns:state.directReturns,directPromises:state.directPromises,directErrors:state.directErrors,directSetupDelta:state.directSetupDelta,directBidiDelta:state.directBidiDelta,directOutcome:state.directOutcome,contextCount:contexts.length};
  }

  installListenerRootHook();installAsyncHooks();installSetupHook();
  window.__AIS_R183B_BOOTSTRAP__={version:VERSION,configure:configure,reset:reset,describe:describe};
  bridge('R187_ENGINE_INSTALLED',{version:VERSION,learningOnly:true,targetModel:TARGET_MODEL});
})();
""".trimIndent()
}
