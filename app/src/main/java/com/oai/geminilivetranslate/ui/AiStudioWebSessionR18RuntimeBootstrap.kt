package com.oai.geminilivetranslate.ui

/**
 * R18.3B non-UI page-runtime Live bootstrap experiment.
 *
 * The bootstrap never searches for controls, labels, coordinates, DOM selectors or event handlers.
 * It scans already-instantiated page-runtime objects using data property descriptors only, ranks
 * callable methods by Live-runtime behavioral signals, and invokes a candidate only when one unique
 * high-confidence non-UI candidate is found. Ambiguous discovery fails closed.
 *
 * Diagnostics expose only bounded structural paths, scores, signal names and counters. Function
 * source, request bodies, cookies, auth data and media bytes are never exported.
 */
object AiStudioWebSessionR18RuntimeBootstrap {
    const val VERSION = "2026-09-03-r18.3b-runtime-bootstrap"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R183B_BOOTSTRAP__&&window.__AIS_R183B_BOOTSTRAP__.version)return;

  const VERSION='2026-09-03-r18.3b-runtime-bootstrap';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const MAX_OBJECTS=6000,MAX_DEPTH=5,MAX_PROPS=180,MAX_CANDIDATES=48;
  const state={
    configured:false,targetLanguage:'vi',stage:'idle',lastError:'',
    scans:0,objectsVisited:0,functionsVisited:0,candidateCount:0,strongCandidateCount:0,
    invokeAttempts:0,invokeReturns:0,invokePromises:0,invokeErrors:0,
    bidiBefore:0,bidiAfter:0,setupBefore:0,setupAfter:0,startAt:0,lastScanAt:0,
    selected:null,top:[],retryCount:0,maxRetries:10
  };
  let retryTimer=0;

  function bridge(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R183B_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safeCode(v){const s=String(v||'vi').trim().slice(0,32);return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';}
  function safeSeg(v){return String(v||'').replace(/[^A-Za-z0-9_$-]/g,'_').slice(0,44)||'_';}
  function hashText(v){let h=2166136261,s=String(v||'');for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return (h>>>0).toString(16);}
  function fnSource(fn){try{return Function.prototype.toString.call(fn);}catch(_){return '';}}
  function isNative(src){return !src||src.indexOf('[native code]')>=0;}
  function liveCounters(){
    let bidi=0,setup=0;
    try{const r=window.__AIS_R18_CAUSAL__&&window.__AIS_R18_CAUSAL__.describe();bidi=Number(r&&r.counters&&r.counters.bidiSend||0);}catch(_){}
    try{const o=window.__AIS_LIVE_OUTPUT_ENGINE__&&window.__AIS_LIVE_OUTPUT_ENGINE__.describe();setup=Number(o&&o.setupCompleteEvents||0);}catch(_){}
    return {bidi:bidi,setup:setup};
  }
  function sourceSignals(src){
    const s=String(src||'');const signals=[];let score=0;
    if(/getUserMedia/.test(s)){signals.push('getUserMedia');score+=14;}
    if(/bidiGenerateContent/i.test(s)){signals.push('bidiGenerateContent');score+=14;}
    if(s.indexOf(TARGET_MODEL)>=0){signals.push('targetModel');score+=12;}
    if(/\.xn\s*\(/.test(s)){signals.push('xn-call');score+=8;}
    if(/\.km\s*\(/.test(s)){signals.push('km-call');score+=6;}
    if(/AudioContext|createMediaStreamSource/.test(s)){signals.push('webAudio');score+=6;}
    if(/audio\\/pcm/i.test(s)){signals.push('audioPcm');score+=6;}
    const uiCoupled=/\.click\s*\(|dispatchEvent\s*\(|querySelector|closest\s*\(|getBoundingClientRect|aria-label|data-testid/.test(s);
    if(uiCoupled)score-=40;
    return {score:score,signals:signals,uiCoupled:uiCoupled};
  }
  function transportShape(obj){
    try{
      const p=Object.getPrototypeOf(obj);if(!p)return false;
      const d=Object.getOwnPropertyDescriptors(p);
      return !!(d.xn&&typeof d.xn.value==='function'&&d.km&&typeof d.km.value==='function');
    }catch(_){return false;}
  }
  function dataChildren(obj){
    const out=[];
    try{
      const d=Object.getOwnPropertyDescriptors(obj);const names=Object.keys(d).slice(0,MAX_PROPS);
      for(let i=0;i<names.length;i++){
        const name=names[i],desc=d[name];if(!desc||!Object.prototype.hasOwnProperty.call(desc,'value'))continue;
        const value=desc.value;if(value&&(typeof value==='object'||typeof value==='function'))out.push({name:name,value:value});
      }
    }catch(_){}
    return out;
  }
  function methodEntries(obj,path,depth){
    const out=[];
    try{
      const sources=[{owner:obj,prefix:path}];
      const p=Object.getPrototypeOf(obj);if(p&&p!==Object.prototype&&p!==Function.prototype)sources.push({owner:p,prefix:path+'.<proto>'});
      for(let si=0;si<sources.length;si++){
        const item=sources[si],d=Object.getOwnPropertyDescriptors(item.owner),names=Object.keys(d).slice(0,MAX_PROPS);
        for(let i=0;i<names.length;i++){
          const name=names[i],desc=d[name];if(!desc||typeof desc.value!=='function'||name==='constructor')continue;
          const fn=desc.value,src=fnSource(fn);state.functionsVisited++;
          if(isNative(src))continue;
          const sig=sourceSignals(src);if(sig.uiCoupled||sig.signals.length===0)continue;
          let score=sig.score;
          if(fn.length===0)score+=5;else if(fn.length===1)score+=2;else if(fn.length>3)score-=8;
          if(transportShape(obj)){score+=4;sig.signals.push('transport-shape');}
          if(score<8)continue;
          out.push({receiver:obj,fn:fn,path:(item.prefix+'.'+safeSeg(name)).slice(0,260),name:safeSeg(name),arity:Number(fn.length||0),score:score,signals:sig.signals.slice(0,8),sourceHash:hashText(src)});
        }
      }
    }catch(_){}
    return out;
  }
  function roots(){
    const out=[{name:'window',value:window},{name:'document',value:document}];
    try{if(document.documentElement)out.push({name:'documentElement',value:document.documentElement});}catch(_){}
    try{if(document.body)out.push({name:'body',value:document.body});}catch(_){}
    try{if(window._)out.push({name:'closureNamespace',value:window._});}catch(_){}
    return out;
  }
  function discover(){
    state.scans++;state.lastScanAt=Date.now();state.objectsVisited=0;state.functionsVisited=0;
    const seen=new WeakSet(),queue=roots().map(function(r){return {value:r.value,path:r.name,depth:0};}),candidates=[];
    while(queue.length&&state.objectsVisited<MAX_OBJECTS){
      const item=queue.shift(),obj=item.value;
      if(!obj||(typeof obj!=='object'&&typeof obj!=='function'))continue;
      try{if(seen.has(obj))continue;seen.add(obj);}catch(_){continue;}
      state.objectsVisited++;
      const methods=methodEntries(obj,item.path,item.depth);for(let i=0;i<methods.length&&candidates.length<MAX_CANDIDATES;i++)candidates.push(methods[i]);
      if(item.depth>=MAX_DEPTH)continue;
      const kids=dataChildren(obj);
      for(let i=0;i<kids.length&&queue.length<MAX_OBJECTS;i++){
        const child=kids[i];queue.push({value:child.value,path:(item.path+'.'+safeSeg(child.name)).slice(0,220),depth:item.depth+1});
      }
    }
    candidates.sort(function(a,b){return b.score-a.score||a.arity-b.arity;});
    state.candidateCount=candidates.length;state.strongCandidateCount=candidates.filter(function(c){return c.score>=18&&c.signals.length>=2&&c.arity<=2;}).length;
    state.top=candidates.slice(0,10).map(function(c){return {path:c.path,arity:c.arity,score:c.score,signals:c.signals,sourceHash:c.sourceHash};});
    bridge('DISCOVERY',{scans:state.scans,objectsVisited:state.objectsVisited,functionsVisited:state.functionsVisited,candidateCount:state.candidateCount,strongCandidateCount:state.strongCandidateCount,top:state.top});
    return candidates;
  }
  function invokeUnique(candidates){
    const strong=candidates.filter(function(c){return c.score>=18&&c.signals.length>=2&&c.arity<=2;});
    if(!strong.length){state.stage='no-strong-candidate';return false;}
    const best=strong[0],next=strong.length>1?strong[1]:null;
    if(next&&next.score===best.score&&next.sourceHash!==best.sourceHash){state.stage='ambiguous-candidates';return false;}
    state.selected={path:best.path,arity:best.arity,score:best.score,signals:best.signals,sourceHash:best.sourceHash};
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
    if(c.setup>state.setupBefore){state.stage='setup-complete';bridge('SUCCESS',{bidiBefore:state.bidiBefore,bidiAfter:c.bidi,setupBefore:state.setupBefore,setupAfter:c.setup,selected:state.selected});return true;}
    if(c.bidi>state.bidiBefore){state.stage='bidi-active-waiting-setup';return false;}
    return false;
  }
  function tryScan(){
    if(state.stage==='setup-complete'||state.stage==='bidi-active-waiting-setup')return;
    const candidates=discover();
    if(invokeUnique(candidates))return;
    state.retryCount++;
    if(state.retryCount<state.maxRetries){state.stage='waiting-runtime';retryTimer=setTimeout(tryScan,900);}
    else{state.stage=state.stage==='ambiguous-candidates'?'ambiguous-candidates':'no-runtime-candidate';bridge('FINAL_NO_START',{stage:state.stage,retryCount:state.retryCount,top:state.top});}
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
    return {ok:true,version:VERSION,targetModel:TARGET_MODEL,configured:state.configured,targetLanguage:state.targetLanguage,stage:state.stage,lastError:state.lastError,scans:state.scans,objectsVisited:state.objectsVisited,functionsVisited:state.functionsVisited,candidateCount:state.candidateCount,strongCandidateCount:state.strongCandidateCount,invokeAttempts:state.invokeAttempts,invokeReturns:state.invokeReturns,invokePromises:state.invokePromises,invokeErrors:state.invokeErrors,bidiBefore:state.bidiBefore,bidiAfter:state.bidiAfter,setupBefore:state.setupBefore,setupAfter:state.setupAfter,retryCount:state.retryCount,startAgeMs:state.startAt?Date.now()-state.startAt:-1,lastScanAgeMs:state.lastScanAt?Date.now()-state.lastScanAt:-1,selected:state.selected,top:state.top};
  }

  window.__AIS_R183B_BOOTSTRAP__={version:VERSION,configure:configure,start:start,reset:reset,discover:function(){discover();return describe();},describe:describe};
  bridge('ENGINE_INSTALLED',{version:VERSION,targetModel:TARGET_MODEL,mode:'non-ui-runtime-scan'});
})();
    """.trimIndent()
}
