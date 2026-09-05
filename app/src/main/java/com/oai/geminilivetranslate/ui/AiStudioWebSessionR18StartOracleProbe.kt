package com.oai.geminilivetranslate.ui

/**
 * R18.4 document-start causal probe around the exact Start control selected by the R17.4 oracle.
 *
 * This file NEVER clicks UI. It keeps listener/function/object references page-local and exports
 * structural metadata only: type names, relation classes, source hashes, stack locations and counts.
 */
object AiStudioWebSessionR18StartOracleProbe {
    const val VERSION = "2026-09-03-r18.4-start-oracle-probe"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R184_ORACLE_PROBE__&&window.__AIS_R184_ORACLE_PROBE__.version)return;
  const VERSION='2026-09-03-r18.4-start-oracle-probe';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const MAX_EVENTS=220,MAX_GRAPH=180,MAX_FRAMES=24;
  const entries=[],events=[],graphRefs=[],setupFrames=[];
  let nextId=1,oracleTarget=null,oracleMarkedAt=0;
  const state={
    installed:false,registrations:0,removals:0,oracleMarks:0,relatedListeners:0,
    listenerInvocations:0,graphObjects:0,graphFunctions:0,setupObservations:0,
    setupFrameCount:0,listenerFrameLinks:0,graphFrameLinks:0,lastOracle:null,lastInvocation:null
  };

  function bridge(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R184P_'+kind,payload:payload||{}}));}catch(_){}
  }
  function hashText(v){let h=2166136261,s=String(v||'');for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return (h>>>0).toString(16);}
  function fnSource(fn){try{return Function.prototype.toString.call(fn);}catch(_){return '';}}
  function safeSeg(v){return String(v||'').replace(/[^A-Za-z0-9_$.-]/g,'_').slice(0,80)||'_';}
  function typeName(v){try{return v&&v.constructor&&v.constructor.name||typeof v;}catch(_){return typeof v;}}
  function targetMeta(t){
    try{
      return {type:typeName(t),tag:String(t&&t.tagName||'').slice(0,32),role:String(t&&t.getAttribute&&t.getAttribute('role')||'').slice(0,48),
        ariaChars:String(t&&t.getAttribute&&t.getAttribute('aria-label')||'').length,connected:typeof t.isConnected==='boolean'?!!t.isConnected:true};
    }catch(_){return {type:typeName(t),tag:'',role:'',ariaChars:0,connected:false};}
  }
  function push(kind,payload){
    const item={id:nextId++,at:Date.now(),kind:kind,payload:payload||{}};
    events.push(item);if(events.length>MAX_EVENTS)events.shift();bridge(kind,payload);return item;
  }
  function captureFlag(o){try{return typeof o==='boolean'?o:!!(o&&o.capture);}catch(_){return false;}}
  function stackLines(skip){
    try{
      const lines=String(new Error().stack||'').split('\n').slice(skip||2,12),out=[];
      for(let i=0;i<lines.length;i++){
        const s=String(lines[i]||'').replace(/[?#].*$/,'').replace(/https:\/\/aistudio\.google\.com/g,'https://aistudio.google.com');
        if(s)out.push(s.slice(0,260));
      }
      return out;
    }catch(_){return [];}
  }
  function relation(target){
    if(!oracleTarget)return 'none';
    try{
      if(target===oracleTarget)return 'exact';
      if(target===document)return 'document';
      if(target===window)return 'window';
      if(target===document.body)return 'body';
      if(target&&typeof target.contains==='function'&&target.contains(oracleTarget))return 'ancestor';
      if(oracleTarget&&typeof oracleTarget.contains==='function'&&oracleTarget.contains(target))return 'descendant';
    }catch(_){}
    return 'other';
  }
  function callable(listener){
    if(typeof listener==='function')return listener;
    try{if(listener&&typeof listener.handleEvent==='function')return listener.handleEvent;}catch(_){}
    return null;
  }
  function entryMeta(e){
    return {id:e.id,type:e.type,relation:relation(e.target),target:targetMeta(e.target),listenerKind:typeof e.original,
      sourceHash:e.sourceHash,arity:e.arity,registrationStack:e.registrationStack};
  }
  function captureInvocation(e){
    if(!oracleTarget)return;
    const rel=relation(e.target);if(rel==='other'||rel==='none'||rel==='descendant')return;
    state.listenerInvocations++;
    const meta={entryId:e.id,relation:rel,sourceHash:e.sourceHash,target:targetMeta(e.target),stack:stackLines(3),afterOracleMs:oracleMarkedAt?Date.now()-oracleMarkedAt:-1};
    state.lastInvocation=meta;push('RELATED_LISTENER_INVOKED',meta);
  }

  const nativeAdd=EventTarget.prototype.addEventListener;
  const nativeRemove=EventTarget.prototype.removeEventListener;
  EventTarget.prototype.addEventListener=function(type,listener,options){
    const t=String(type||'');
    if((t==='click'||t==='pointerdown'||t==='pointerup')&&listener){
      try{
        const fn=callable(listener),entry={id:nextId++,type:t,target:this,original:listener,wrapped:null,options:options,active:true,
          sourceHash:fn?hashText(fnSource(fn)):'',arity:fn?Number(fn.length||0):-1,registrationStack:stackLines(3)};
        const wrapped=function(ev){try{captureInvocation(entry);}catch(_){};if(typeof listener==='function')return listener.call(this,ev);return listener.handleEvent.call(listener,ev);};
        entry.wrapped=wrapped;entries.push(entry);state.registrations++;
        return nativeAdd.call(this,type,wrapped,options);
      }catch(_){}
    }
    return nativeAdd.apply(this,arguments);
  };
  EventTarget.prototype.removeEventListener=function(type,listener,options){
    const t=String(type||''),cap=captureFlag(options);
    if(t==='click'||t==='pointerdown'||t==='pointerup'){
      for(let i=entries.length-1;i>=0;i--){const e=entries[i];if(e.active&&e.type===t&&e.target===this&&e.original===listener&&captureFlag(e.options)===cap){
        e.active=false;state.removals++;return nativeRemove.call(this,type,e.wrapped,options);
      }}
    }
    return nativeRemove.apply(this,arguments);
  };

  function isDom(v){try{return v===window||v===document||(typeof Node==='function'&&v instanceof Node);}catch(_){return false;}}
  function graphAdd(value,path,depth,seen){
    if(!value||(typeof value!=='object'&&typeof value!=='function')||graphRefs.length>=MAX_GRAPH)return;
    if(isDom(value)&&depth>0)return;
    try{if(seen.has(value))return;seen.add(value);}catch(_){return;}
    const item={value:value,path:String(path||'').slice(0,220),type:typeName(value),kind:typeof value,sourceHash:'',arity:-1};
    if(typeof value==='function'){item.sourceHash=hashText(fnSource(value));item.arity=Number(value.length||0);state.graphFunctions++;}
    else state.graphObjects++;
    graphRefs.push(item);
    if(depth>=3)return;
    try{
      const d=Object.getOwnPropertyDescriptors(value),keys=Object.keys(d).slice(0,180);
      for(let i=0;i<keys.length&&graphRefs.length<MAX_GRAPH;i++){
        const desc=d[keys[i]];if(!desc||!Object.prototype.hasOwnProperty.call(desc,'value'))continue;
        const child=desc.value;if(!child||(typeof child!=='object'&&typeof child!=='function'))continue;
        if(isDom(child))continue;graphAdd(child,String(path||'root')+'.'+safeSeg(keys[i]),depth+1,seen);
      }
      const p=Object.getPrototypeOf(value);
      if(p&&p!==Object.prototype&&p!==Function.prototype&&!isDom(p))graphAdd(p,String(path||'root')+'.<proto>',depth+1,seen);
    }catch(_){}
  }
  function scanOracleGraph(){
    graphRefs.length=0;state.graphObjects=0;state.graphFunctions=0;
    const seen=new WeakSet();
    try{
      let n=oracleTarget;
      for(let depth=0;depth<5&&n;depth++){
        const d=Object.getOwnPropertyDescriptors(n),keys=Object.keys(d).slice(0,260);
        for(let i=0;i<keys.length&&graphRefs.length<MAX_GRAPH;i++){
          const desc=d[keys[i]];if(!desc||!Object.prototype.hasOwnProperty.call(desc,'value'))continue;
          const v=desc.value;if(v&&(typeof v==='object'||typeof v==='function')&&!isDom(v))graphAdd(v,'target'+depth+'.'+safeSeg(keys[i]),0,seen);
        }
        n=n.parentElement||n.parentNode||null;
      }
    }catch(_){}
    const related=entries.filter(function(e){if(!e.active)return false;const r=relation(e.target);return r==='exact'||r==='ancestor'||r==='document'||r==='body'||r==='window';});
    for(let i=0;i<related.length&&graphRefs.length<MAX_GRAPH;i++){
      const fn=callable(related[i].original);if(fn)graphAdd(fn,'listener.'+related[i].id,0,seen);
      if(related[i].original&&typeof related[i].original==='object')graphAdd(related[i].original,'listenerObject.'+related[i].id,0,seen);
    }
    push('ORACLE_GRAPH',{objects:state.graphObjects,functions:state.graphFunctions,refs:graphRefs.slice(0,40).map(function(x){return {path:x.path,type:x.type,kind:x.kind,arity:x.arity,sourceHash:x.sourceHash};})});
  }
  function markOracleTarget(el,meta){
    oracleTarget=el;oracleMarkedAt=Date.now();state.oracleMarks++;
    const related=entries.filter(function(e){if(!e.active)return false;const r=relation(e.target);return r==='exact'||r==='ancestor'||r==='document'||r==='body'||r==='window';});
    state.relatedListeners=related.length;
    state.lastOracle={score:Number(meta&&meta.score||0),target:targetMeta(el),related:related.slice(0,40).map(entryMeta)};
    push('ORACLE_TARGET_MARKED',state.lastOracle);scanOracleGraph();return describe();
  }

  function structuredFrames(){
    const out=[],old=Error.prepareStackTrace;
    try{
      Error.prepareStackTrace=function(_,stack){return stack;};
      const e=new Error();if(Error.captureStackTrace)Error.captureStackTrace(e,structuredFrames);
      const stack=e.stack;if(!Array.isArray(stack))return out;
      for(let i=0;i<stack.length&&out.length<MAX_FRAMES;i++){
        const cs=stack[i];let fn=null,receiver=null,file='',line=0,column=0,name='',method='';
        try{fn=cs.getFunction&&cs.getFunction();}catch(_){}
        try{receiver=cs.getThis&&cs.getThis();}catch(_){}
        try{file=String(cs.getFileName&&cs.getFileName()||'');}catch(_){}
        try{line=Number(cs.getLineNumber&&cs.getLineNumber()||0);}catch(_){}
        try{column=Number(cs.getColumnNumber&&cs.getColumnNumber()||0);}catch(_){}
        try{name=safeSeg(cs.getFunctionName&&cs.getFunctionName()||'');}catch(_){}
        try{method=safeSeg(cs.getMethodName&&cs.getMethodName()||'');}catch(_){}
        const src=typeof fn==='function'?fnSource(fn):'',hash=src?hashText(src):'';
        const listenerIdentity=entries.some(function(x){return callable(x.original)===fn||x.wrapped===fn;});
        const listenerHash=!!hash&&entries.some(function(x){return x.sourceHash===hash;});
        const graphIdentity=graphRefs.some(function(x){return x.value===fn||x.value===receiver;});
        const graphHash=!!hash&&graphRefs.some(function(x){return x.sourceHash===hash;});
        let host='',path='';try{const u=new URL(file);host=String(u.host||'').slice(0,100);path=String(u.pathname||'').slice(0,180);}catch(_){}
        out.push({index:i,host:host,path:path,line:line,column:column,functionName:name,methodName:method,receiverType:typeName(receiver),
          arity:typeof fn==='function'?Number(fn.length||0):-1,sourceHash:hash,listenerIdentity:listenerIdentity,listenerHash:listenerHash,graphIdentity:graphIdentity,graphHash:graphHash});
      }
    }catch(e){push('STRUCTURED_STACK_ERROR',{name:String(e&&e.name||'Error')});}
    finally{try{Error.prepareStackTrace=old;}catch(_){} }
    return out;
  }
  function learnSetup(rawUrl,body){
    const text=typeof body==='string'?body:'';
    if(text.toLowerCase().indexOf(TARGET_MODEL)<0||/audio\/pcm/i.test(text))return;
    state.setupObservations++;
    const frames=structuredFrames();setupFrames.length=0;for(let i=0;i<frames.length;i++)setupFrames.push(frames[i]);
    state.setupFrameCount=frames.length;
    state.listenerFrameLinks=frames.filter(function(f){return f.listenerIdentity||f.listenerHash;}).length;
    state.graphFrameLinks=frames.filter(function(f){return f.graphIdentity||f.graphHash;}).length;
    let profile={host:'',path:'',queryNames:[]};try{const u=new URL(String(rawUrl||''),location.href);profile.host=u.host;profile.path=u.pathname;u.searchParams.forEach(function(_,k){if(profile.queryNames.length<30)profile.queryNames.push(String(k).slice(0,80));});}catch(_){}
    push('SETUP_CAUSAL_FRAMES',{afterOracleMs:oracleMarkedAt?Date.now()-oracleMarkedAt:-1,profile:profile,frameCount:frames.length,
      listenerLinks:state.listenerFrameLinks,graphLinks:state.graphFrameLinks,frames:frames});
  }
  try{
    const X=window.XMLHttpRequest;if(X&&X.prototype&&!X.prototype.__aisR184Probe){
      const open=X.prototype.open,send=X.prototype.send;
      X.prototype.open=function(method,url){try{this.__aisR184Url=String(url||'');}catch(_){}return open.apply(this,arguments);};
      X.prototype.send=function(body){try{if(String(this.__aisR184Url||'').indexOf('/v1/bidiGenerateContent')>=0)learnSetup(this.__aisR184Url,body);}catch(_){}return send.apply(this,arguments);};
      X.prototype.__aisR184Probe=true;
    }
  }catch(e){push('XHR_HOOK_ERROR',{name:String(e&&e.name||'Error')});}

  function reset(){
    oracleTarget=null;oracleMarkedAt=0;events.length=0;graphRefs.length=0;setupFrames.length=0;
    state.oracleMarks=0;state.relatedListeners=0;state.listenerInvocations=0;state.graphObjects=0;state.graphFunctions=0;
    state.setupObservations=0;state.setupFrameCount=0;state.listenerFrameLinks=0;state.graphFrameLinks=0;state.lastOracle=null;state.lastInvocation=null;
    push('RESET',{registrations:state.registrations});return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,installed:state.installed,registrations:state.registrations,removals:state.removals,
      oracleMarks:state.oracleMarks,relatedListeners:state.relatedListeners,listenerInvocations:state.listenerInvocations,
      graphObjects:state.graphObjects,graphFunctions:state.graphFunctions,setupObservations:state.setupObservations,
      setupFrameCount:state.setupFrameCount,listenerFrameLinks:state.listenerFrameLinks,graphFrameLinks:state.graphFrameLinks,
      lastOracle:state.lastOracle,lastInvocation:state.lastInvocation,setupFrames:setupFrames.slice(0,MAX_FRAMES)};
  }
  function recent(limit){const n=Math.max(1,Math.min(MAX_EVENTS,Number(limit||120)));return {ok:true,version:VERSION,events:events.slice(-n)};}
  state.installed=true;
  window.__AIS_R184_ORACLE_PROBE__={version:VERSION,reset:reset,markOracleTarget:markOracleTarget,describe:describe,recent:recent};
  push('ENGINE_INSTALLED',{version:VERSION});
})();
""".trimIndent()
}
