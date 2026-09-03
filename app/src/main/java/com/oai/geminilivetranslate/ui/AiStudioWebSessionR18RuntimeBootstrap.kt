package com.oai.geminilivetranslate.ui

/**
 * R18.3B non-UI page-runtime Live bootstrap experiment.
 *
 * This bootstrap deliberately avoids DOM controls, labels, coordinates, synthetic UI events and
 * minified symbol names. It searches already-instantiated page-runtime objects using data property
 * descriptors only, ranks zero-argument methods by Live-runtime behavior visible in their function
 * bodies, and fails closed unless one candidate is clearly stronger than the rest.
 *
 * In parallel it learns structured V8 call-frame handles whenever a genuine Live setup request is
 * observed. Function/receiver/argument references stay inside the WebView; diagnostics export only
 * bounded structural metadata, hashes and type names. This gives the next iteration a stable way to
 * reason about the runtime service without hard-coding bundle symbols such as PQ/xn/km.
 */
object AiStudioWebSessionR18RuntimeBootstrap {
    const val VERSION = "2026-09-03-r18.3b2-behavioral-runtime-bootstrap"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R183B_BOOTSTRAP__&&window.__AIS_R183B_BOOTSTRAP__.version)return;

  const VERSION='2026-09-03-r18.3b2-behavioral-runtime-bootstrap';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const MAX_OBJECTS=8500,MAX_DEPTH=6,MAX_PROPS=360,MAX_CANDIDATES=64,MAX_LEARNED=20;
  const state={
    configured:false,targetLanguage:'vi',stage:'idle',lastError:'',
    scans:0,objectsVisited:0,functionsVisited:0,candidateCount:0,strongCandidateCount:0,
    invokeAttempts:0,invokeReturns:0,invokePromises:0,invokeErrors:0,
    bidiBefore:0,bidiAfter:0,setupBefore:0,setupAfter:0,startAt:0,lastScanAt:0,
    selected:null,top:[],retryCount:0,maxRetries:12,
    learnedCount:0,learnedFrames:[],setupObservations:0,lastRequestProfile:null
  };
  let retryTimer=0;
  const learned=[];

  function bridge(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R183B_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safeCode(v){const s=String(v||'vi').trim().slice(0,32);return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';}
  function safeSeg(v){return String(v||'').replace(/[^A-Za-z0-9_$-]/g,'_').slice(0,48)||'_';}
  function hashText(v){let h=2166136261,s=String(v||'');for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return (h>>>0).toString(16);}
  function fnSource(fn){try{return Function.prototype.toString.call(fn);}catch(_){return '';}}
  function isNative(src){return !src||src.indexOf('[native code]')>=0;}
  function typeName(v){
    if(v===null)return 'null';if(v===undefined)return 'undefined';
    try{return v&&v.constructor&&v.constructor.name||typeof v;}catch(_){return typeof v;}
  }
  function isUiObject(value){
    try{if(value===window||value===document)return true;}catch(_){}
    try{if(typeof EventTarget==='function'&&value instanceof EventTarget)return true;}catch(_){}
    try{if(typeof Node==='function'&&value instanceof Node)return true;}catch(_){}
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

  // Behavioral signals only. No minified function/property names are used as evidence.
  function sourceSignals(src){
    const s=String(src||'');const signals=[];let score=0;
    function add(name,weight,re){if(re.test(s)){signals.push(name);score+=weight;}}
    add('targetModel',22,/gemini-3\.5-live-translate-preview/i);
    add('bidiGenerateContent',20,/bidiGenerateContent/i);
    add('getUserMedia',16,/getUserMedia/);
    add('mediaDevices',10,/mediaDevices/);
    add('webAudio',10,/AudioContext|createMediaStreamSource|createMediaStreamTrackSource/);
    add('audioPcm',10,/audio\\/pcm/i);
    add('translationConfig',9,/translation[_A-Za-z]*config|target[_A-Za-z]*language/i);
    add('setupProtocol',7,/setupComplete|setup_complete|generationConfig|generation_config/);
    add('xhrTransport',5,/XMLHttpRequest|URLSearchParams/);
    const uiCoupled=/\.click\s*\(|dispatchEvent\s*\(|querySelector|getElementsBy|closest\s*\(|getBoundingClientRect|aria-label|data-testid|innerText|textContent/.test(s);
    if(uiCoupled)score-=80;
    return {score:score,signals:signals,uiCoupled:uiCoupled};
  }
  function receiverSignals(obj){
    const names={},signals=[];let score=0,methods=0;
    try{
      const owners=[obj];const p=Object.getPrototypeOf(obj);if(p&&p!==Object.prototype&&p!==Function.prototype)owners.push(p);
      for(let oi=0;oi<owners.length;oi++){
        const d=Object.getOwnPropertyDescriptors(owners[oi]),ks=Object.keys(d).slice(0,MAX_PROPS);
        for(let i=0;i<ks.length;i++){
          const desc=d[ks[i]];if(!desc||typeof desc.value!=='function')continue;methods++;
          const sig=sourceSignals(fnSource(desc.value));
          if(sig.uiCoupled)continue;
          for(let j=0;j<sig.signals.length;j++)if(!names[sig.signals[j]]){names[sig.signals[j]]=true;signals.push(sig.signals[j]);}
        }
      }
      if(signals.indexOf('bidiGenerateContent')>=0)score+=8;
      if(signals.indexOf('getUserMedia')>=0||signals.indexOf('mediaDevices')>=0)score+=6;
      if(signals.indexOf('webAudio')>=0||signals.indexOf('audioPcm')>=0)score+=5;
      if(signals.indexOf('targetModel')>=0||signals.indexOf('translationConfig')>=0)score+=5;
      if(signals.length>=3)score+=5;
    }catch(_){}
    return {score:score,signals:signals.slice(0,10),methods:methods};
  }
  function dataChildren(obj){
    const out=[];
    try{
      const d=Object.getOwnPropertyDescriptors(obj),names=Object.keys(d).slice(0,MAX_PROPS);
      for(let i=0;i<names.length;i++){
        const name=names[i],desc=d[name];if(!desc||!Object.prototype.hasOwnProperty.call(desc,'value'))continue;
        const value=desc.value;
        if(value&&(typeof value==='object'||typeof value==='function')&&!isUiObject(value))out.push({name:name,value:value});
      }
    }catch(_){}
    return out;
  }
  function methodEntries(obj,path){
    const out=[];if(isUiObject(obj))return out;
    try{
      const profile=receiverSignals(obj);
      const sources=[{owner:obj,prefix:path}];
      const p=Object.getPrototypeOf(obj);if(p&&p!==Object.prototype&&p!==Function.prototype)sources.push({owner:p,prefix:path+'.<proto>'});
      for(let si=0;si<sources.length;si++){
        const item=sources[si],d=Object.getOwnPropertyDescriptors(item.owner),names=Object.keys(d).slice(0,MAX_PROPS);
        for(let i=0;i<names.length;i++){
          const name=names[i],desc=d[name];if(!desc||typeof desc.value!=='function'||name==='constructor')continue;
          const fn=desc.value,src=fnSource(fn);state.functionsVisited++;
          if(isNative(src)||fn.length!==0)continue;
          const sig=sourceSignals(src);if(sig.uiCoupled||sig.signals.length===0)continue;
          const score=sig.score+profile.score;
          // Direct function evidence is mandatory; receiver cohesion only refines ranking.
          if(score<20)continue;
          out.push({
            receiver:obj,fn:fn,path:(item.prefix+'.'+safeSeg(name)).slice(0,280),name:safeSeg(name),
            arity:0,score:score,directSignals:sig.signals.slice(0,8),receiverSignals:profile.signals,
            sourceHash:hashText(src)
          });
        }
      }
    }catch(_){}
    return out;
  }
  function roots(){
    const out=[];
    try{if(window._&&!isUiObject(window._))out.push({name:'closureNamespace',value:window._});}catch(_){}
    try{
      const d=Object.getOwnPropertyDescriptors(window),names=Object.keys(d).slice(0,1200);
      for(let i=0;i<names.length&&out.length<420;i++){
        const name=names[i],desc=d[name];if(!desc||!Object.prototype.hasOwnProperty.call(desc,'value'))continue;
        const value=desc.value;
        if(value&&(typeof value==='object'||typeof value==='function')&&!isUiObject(value))out.push({name:'window.'+safeSeg(name),value:value});
      }
    }catch(_){}
    return out;
  }
  function discover(){
    state.scans++;state.lastScanAt=Date.now();state.objectsVisited=0;state.functionsVisited=0;
    const seen=new WeakSet(),queue=roots().map(function(r){return {value:r.value,path:r.name,depth:0};}),raw=[];
    while(queue.length&&state.objectsVisited<MAX_OBJECTS){
      const item=queue.shift(),obj=item.value;
      if(!obj||(typeof obj!=='object'&&typeof obj!=='function')||isUiObject(obj))continue;
      try{if(seen.has(obj))continue;seen.add(obj);}catch(_){continue;}
      state.objectsVisited++;
      const methods=methodEntries(obj,item.path);for(let i=0;i<methods.length&&raw.length<MAX_CANDIDATES*2;i++)raw.push(methods[i]);
      if(item.depth>=MAX_DEPTH)continue;
      const kids=dataChildren(obj);
      for(let i=0;i<kids.length&&queue.length<MAX_OBJECTS;i++)queue.push({value:kids[i].value,path:(item.path+'.'+safeSeg(kids[i].name)).slice(0,240),depth:item.depth+1});
    }
    // Deduplicate aliases that point to the same function+receiver identity.
    const candidates=[];
    for(let i=0;i<raw.length;i++){
      const c=raw[i];let duplicate=false;
      for(let j=0;j<candidates.length;j++)if(candidates[j].fn===c.fn&&candidates[j].receiver===c.receiver){duplicate=true;break;}
      if(!duplicate)candidates.push(c);
      if(candidates.length>=MAX_CANDIDATES)break;
    }
    candidates.sort(function(a,b){return b.score-a.score;});
    state.candidateCount=candidates.length;
    state.strongCandidateCount=candidates.filter(function(c){return c.score>=28&&c.directSignals.length>=1&&c.arity===0;}).length;
    state.top=candidates.slice(0,12).map(function(c){return {path:c.path,arity:c.arity,score:c.score,directSignals:c.directSignals,receiverSignals:c.receiverSignals,sourceHash:c.sourceHash};});
    bridge('DISCOVERY',{scans:state.scans,objectsVisited:state.objectsVisited,functionsVisited:state.functionsVisited,candidateCount:state.candidateCount,strongCandidateCount:state.strongCandidateCount,top:state.top});
    return candidates;
  }

  function queryProfile(raw){
    try{
      const u=new URL(String(raw||''),location.href),out={names:[],count:0};
      u.searchParams.forEach(function(v,k){
        if(out.names.length>=40)return;const s=String(v||'');let kind='opaque';
        if(/^\\d+$/.test(s))kind='numeric';else if(/^[A-Za-z0-9_-]{1,24}$/.test(s))kind='short-token';else if(s.length>80)kind='long-opaque';
        out.names.push({name:String(k||'').slice(0,80),chars:s.length,kind:kind});out.count++;
      });
      return out;
    }catch(_){return {names:[],count:0};}
  }
  function argumentSnapshot(fn){
    const refs=[];let available=false;
    try{
      const a=fn&&fn.arguments;if(a){available=true;for(let i=0;i<Math.min(8,a.length);i++)refs.push(a[i]);}
    }catch(_){}
    return {available:available,refs:refs,types:refs.map(typeName),hasUiArg:refs.some(isUiArg)};
  }
  function structuredFrames(){
    const out=[];const old=Error.prepareStackTrace;
    try{
      Error.prepareStackTrace=function(_,stack){return stack;};
      const e=new Error();if(Error.captureStackTrace)Error.captureStackTrace(e,structuredFrames);
      const stack=e.stack;if(!Array.isArray(stack))return out;
      for(let i=0;i<stack.length&&out.length<MAX_LEARNED;i++){
        const cs=stack[i];let fn=null,receiver=null,file='',line=0,column=0,fnName='',methodName='';
        try{fn=cs.getFunction&&cs.getFunction();}catch(_){}
        try{receiver=cs.getThis&&cs.getThis();}catch(_){}
        try{file=String(cs.getFileName&&cs.getFileName()||'');}catch(_){}
        try{line=Number(cs.getLineNumber&&cs.getLineNumber()||0);}catch(_){}
        try{column=Number(cs.getColumnNumber&&cs.getColumnNumber()||0);}catch(_){}
        try{fnName=safeSeg(cs.getFunctionName&&cs.getFunctionName()||'');}catch(_){}
        try{methodName=safeSeg(cs.getMethodName&&cs.getMethodName()||'');}catch(_){}
        if(typeof fn!=='function'||isUiObject(receiver))continue;
        const src=fnSource(fn),sig=sourceSignals(src),args=argumentSnapshot(fn);
        const meta={
          index:i,fileHost:'',filePath:'',line:line,column:column,functionName:fnName,methodName:methodName,
          receiverType:typeName(receiver),arity:Number(fn.length||0),sourceHash:hashText(src),
          directSignals:sig.signals.slice(0,8),argsAvailable:args.available,argTypes:args.types,hasUiArg:args.hasUiArg
        };
        try{const u=new URL(file);meta.fileHost=String(u.host||'').slice(0,120);meta.filePath=String(u.pathname||'').slice(0,220);}catch(_){}
        out.push({fn:fn,receiver:receiver,args:args.refs,meta:meta});
      }
    }catch(e){bridge('STRUCTURED_STACK_ERROR',{name:String(e&&e.name||'Error')});}
    finally{try{Error.prepareStackTrace=old;}catch(_){}}
    return out;
  }
  function learnFromSetup(rawUrl){
    const frames=structuredFrames();if(!frames.length)return;
    state.setupObservations++;
    for(let i=0;i<frames.length&&learned.length<MAX_LEARNED;i++){
      const f=frames[i];let exists=false;
      for(let j=0;j<learned.length;j++)if(learned[j].fn===f.fn&&learned[j].receiver===f.receiver){exists=true;break;}
      if(!exists)learned.push(f);
    }
    state.learnedCount=learned.length;state.learnedFrames=learned.map(function(x){return x.meta;}).slice(0,MAX_LEARNED);
    state.lastRequestProfile=queryProfile(rawUrl);
    bridge('RUNTIME_HANDLES_LEARNED',{setupObservations:state.setupObservations,learnedCount:state.learnedCount,frames:state.learnedFrames,requestProfile:state.lastRequestProfile});
  }
  function installStructuredObserver(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype||X.prototype.__aisR183BObserved)return;
      const nativeOpen=X.prototype.open,currentSend=X.prototype.send;
      X.prototype.open=function(method,url){try{this.__aisR183BRawUrl=String(url||'');}catch(_){}return nativeOpen.apply(this,arguments);};
      X.prototype.send=function(body){
        try{
          const raw=String(this.__aisR183BRawUrl||''),text=typeof body==='string'?body:'';
          if(raw.indexOf('/v1/bidiGenerateContent')>=0&&text.toLowerCase().indexOf(TARGET_MODEL)>=0&&!/audio\\/pcm/i.test(text))learnFromSetup(raw);
        }catch(_){}
        return currentSend.apply(this,arguments);
      };
      X.prototype.__aisR183BObserved=true;bridge('HOOK',{target:'structured-live-runtime-observer'});
    }catch(e){bridge('HOOK_ERROR',{target:'structured-live-runtime-observer',name:String(e&&e.name||'Error')});}
  }

  function invokeUnique(candidates){
    const strong=candidates.filter(function(c){return c.score>=28&&c.directSignals.length>=1&&c.arity===0;});
    if(!strong.length){state.stage='no-strong-candidate';return false;}
    const best=strong[0],next=strong.length>1?strong[1]:null;
    // Require a real margin. Equal or near-equal candidates fail closed rather than guessing.
    if(next&&best.score-next.score<6&&next.sourceHash!==best.sourceHash){state.stage='ambiguous-candidates';return false;}
    state.selected={path:best.path,arity:best.arity,score:best.score,directSignals:best.directSignals,receiverSignals:best.receiverSignals,sourceHash:best.sourceHash};
    state.stage='invoking-runtime';state.invokeAttempts++;bridge('INVOKE_ATTEMPT',state.selected);
    try{
      const result=Reflect.apply(best.fn,best.receiver,[]);state.invokeReturns++;
      if(result&&typeof result.then==='function'){
        state.invokePromises++;
        result.then(function(){bridge('INVOKE_PROMISE_RESOLVED',{path:best.path});checkProgress();}).catch(function(e){state.invokeErrors++;state.lastError=String(e&&e.name||e||'Error').slice(0,180);bridge('INVOKE_PROMISE_REJECTED',{path:best.path,error:state.lastError});});
      }
      setTimeout(checkProgress,120);setTimeout(checkProgress,700);setTimeout(checkProgress,2200);setTimeout(checkProgress,6000);
      return true;
    }catch(e){state.invokeErrors++;state.lastError=String(e&&e.name||e||'Error').slice(0,180);state.stage='invoke-error';bridge('INVOKE_ERROR',{path:best.path,error:state.lastError});return false;}
  }
  function checkProgress(){
    const c=liveCounters();state.bidiAfter=c.bidi;state.setupAfter=c.setup;
    if(c.setup>state.setupBefore){state.stage='setup-complete';bridge('SUCCESS',{bidiBefore:state.bidiBefore,bidiAfter:c.bidi,setupBefore:state.setupBefore,setupAfter:c.setup,selected:state.selected,learnedCount:state.learnedCount});return true;}
    if(c.bidi>state.bidiBefore){state.stage='bidi-active-waiting-setup';return false;}
    return false;
  }
  function tryScan(){
    if(state.stage==='setup-complete'||state.stage==='bidi-active-waiting-setup')return;
    const candidates=discover();
    if(invokeUnique(candidates))return;
    state.retryCount++;
    if(state.retryCount<state.maxRetries){state.stage='waiting-runtime';retryTimer=setTimeout(tryScan,900);}
    else{state.stage=state.stage==='ambiguous-candidates'?'ambiguous-candidates':'no-runtime-candidate';bridge('FINAL_NO_START',{stage:state.stage,retryCount:state.retryCount,top:state.top,learnedFrames:state.learnedFrames});}
  }
  function configure(code){state.targetLanguage=safeCode(code);state.configured=true;return describe();}
  function start(code){
    if(code)state.targetLanguage=safeCode(code);state.configured=true;
    try{const g=window.__AIS_R183_LANGUAGE__;if(g&&typeof g.configure==='function')g.configure(state.targetLanguage);}catch(_){}
    try{if(retryTimer)clearTimeout(retryTimer);}catch(_){}
    const c=liveCounters();state.bidiBefore=c.bidi;state.bidiAfter=c.bidi;state.setupBefore=c.setup;state.setupAfter=c.setup;
    state.startAt=Date.now();state.retryCount=0;state.selected=null;state.lastError='';state.stage='discovering-runtime';
    bridge('START',{targetLanguageCode:state.targetLanguage,bidiBefore:state.bidiBefore,setupBefore:state.setupBefore});
    setTimeout(tryScan,0);return describe();
  }
  function reset(){
    try{if(retryTimer)clearTimeout(retryTimer);}catch(_){}retryTimer=0;
    state.stage='idle';state.lastError='';state.scans=0;state.objectsVisited=0;state.functionsVisited=0;state.candidateCount=0;state.strongCandidateCount=0;state.invokeAttempts=0;state.invokeReturns=0;state.invokePromises=0;state.invokeErrors=0;state.bidiBefore=0;state.bidiAfter=0;state.setupBefore=0;state.setupAfter=0;state.startAt=0;state.lastScanAt=0;state.selected=null;state.top=[];state.retryCount=0;return describe();
  }
  function describe(){
    checkProgress();
    return {ok:true,version:VERSION,targetModel:TARGET_MODEL,configured:state.configured,targetLanguage:state.targetLanguage,stage:state.stage,lastError:state.lastError,scans:state.scans,objectsVisited:state.objectsVisited,functionsVisited:state.functionsVisited,candidateCount:state.candidateCount,strongCandidateCount:state.strongCandidateCount,invokeAttempts:state.invokeAttempts,invokeReturns:state.invokeReturns,invokePromises:state.invokePromises,invokeErrors:state.invokeErrors,bidiBefore:state.bidiBefore,bidiAfter:state.bidiAfter,setupBefore:state.setupBefore,setupAfter:state.setupAfter,retryCount:state.retryCount,startAgeMs:state.startAt?Date.now()-state.startAt:-1,lastScanAgeMs:state.lastScanAt?Date.now()-state.lastScanAt:-1,selected:state.selected,top:state.top,learnedCount:state.learnedCount,learnedFrames:state.learnedFrames,setupObservations:state.setupObservations,lastRequestProfile:state.lastRequestProfile};
  }

  installStructuredObserver();
  window.__AIS_R183B_BOOTSTRAP__={version:VERSION,configure:configure,start:start,reset:reset,discover:function(){discover();return describe();},describe:describe};
  bridge('ENGINE_INSTALLED',{version:VERSION,targetModel:TARGET_MODEL,mode:'behavioral-non-ui-runtime-scan'});
})();
""".trimIndent()
}
