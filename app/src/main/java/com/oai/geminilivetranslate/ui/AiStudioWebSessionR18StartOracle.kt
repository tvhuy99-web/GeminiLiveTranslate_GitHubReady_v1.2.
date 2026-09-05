package com.oai.geminilivetranslate.ui

/**
 * R18.5 LAB-ONLY Start oracle + async causal propagation.
 *
 * LAB_ONLY_UI_ORACLE: this intentionally reproduces the one R17.4 Start path that succeeded on the
 * user's device. R18.5 additionally propagates a page-local causal context from that oracle action
 * across common async boundaries so the first genuine Live setup can be tied back to the Start
 * listener/component without hard-coding minified names or bundle line numbers.
 *
 * This file must never be wired into production bootstrap. Production remains zero-UI.
 */
object AiStudioWebSessionR18StartOracle {
    const val VERSION = "2026-09-03-r18.5-r174-start-oracle-async-causal-lab"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R184_START_ORACLE__&&window.__AIS_R184_START_ORACLE__.version)return;
  const VERSION='2026-09-03-r18.5-r174-start-oracle-async-causal-lab';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const LAB_ONLY_UI_ORACLE=true;
  const MAX_CONTEXTS=240,MAX_CHAIN=24,MAX_STACK=32;
  const state={active:false,stage:'idle',targetLanguage:'vi',scans:0,candidates:0,attempts:0,maxAttempts:3,
    syntheticCarrier:false,syntheticErrors:0,lastScore:0,lastActionAt:0,setupAt:0,lastError:'',
    asyncHooksInstalled:false,causalRoots:0,asyncScheduled:0,asyncRuns:0,contextDrops:0,
    setupRequests:0,setupLinked:0,setupUnlinked:0,lastRoot:null,lastSetupContext:null};
  let timer=0,synthetic=null,carrierContext=null,carrierOscillator=null,carrierGain=null;
  let nextContextId=1,currentContext=null;
  const contexts=[],contextById=new Map();

  function bridge(kind,payload){try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R184S_'+kind,payload:payload||{}}));}catch(_){} }
  function safeCode(v){const s=String(v||'vi').trim().slice(0,32);return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';}
  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||420);}
  function attr(el,name){try{return el&&el.getAttribute?safeText(el.getAttribute(name)||'',180):'';}catch(_){return '';}}
  function role(el){return attr(el,'role').toLowerCase();}
  function tag(el){try{return String(el&&el.tagName||'').toUpperCase();}catch(_){return '';}}
  function label(el){try{return safeText([attr(el,'aria-label'),attr(el,'placeholder'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safeText(el&&el.title||'',120),safeText(el&&el.value||'',180),safeText(el&&el.textContent||'',260)].filter(Boolean).join(' '),420).toLowerCase();}catch(_){return '';}}
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
  function isInteractive(el){
    const t=tag(el),r=role(el);if(t==='BUTTON'||t==='A'||t==='INPUT'||t==='TEXTAREA'||t==='SELECT'||t==='OPTION')return true;
    if(r==='button'||r==='tab'||r==='option'||r==='menuitem'||r==='combobox'||r==='link'||r==='listbox')return true;
    if(attr(el,'aria-haspopup'))return true;try{return !!el.isContentEditable;}catch(_){return false;}
  }
  function collectDeep(){
    state.scans++;const roots=[document],seen=new Set(),interactive=[];let visited=0;
    while(roots.length&&visited<7000){const root=roots.shift();if(!root||seen.has(root))continue;seen.add(root);let nodes=[];
      try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&visited<7000;i++){const el=nodes[i];visited++;if(isInteractive(el))interactive.push(el);
        try{if(el.shadowRoot)roots.push(el.shadowRoot);}catch(_){}
        try{const t=tag(el);if((t==='IFRAME'||t==='FRAME')&&el.contentDocument)roots.push(el.contentDocument);}catch(_){}
      }
    }
    return interactive;
  }
  function routeReady(){
    try{const u=new URL(location.href);return String(u.pathname||'').toLowerCase().indexOf('live')>=0&&String(u.searchParams.get('model')||'').replace(/^models\//,'').toLowerCase()===TARGET_MODEL;}catch(_){return false;}
  }
  function setupSeen(){try{const e=window.__AIS_LIVE_OUTPUT_ENGINE__;return !!(e&&typeof e.describe==='function'&&Number(e.describe().setupCompleteEvents||0)>0);}catch(_){return false;}}
  function startScore(el){
    const l=label(el),r=role(el),t=tag(el);if(attr(el,'disabled')||attr(el,'aria-disabled')==='true'||l.indexOf('stop')>=0)return 0;
    if(!(t==='BUTTON'||t==='A'||r==='button'||r==='menuitem'||r==='tab'||r==='link'))return 0;
    let s=0;if(/(^|\s)(start|begin|connect|talk|speak|join)(\s|$)/.test(l))s+=9;
    if(l.indexOf('go live')>=0||l.indexOf('start session')>=0||l.indexOf('start live')>=0)s+=10;
    if(l.indexOf('microphone')>=0||l.indexOf('mic')>=0)s+=6;if(l.indexOf('live')>=0||l.indexOf('stream')>=0)s+=2;return s;
  }

  function contextMeta(ctx){
    if(!ctx)return null;
    return {id:ctx.id,parentId:ctx.parentId,rootId:ctx.rootId,kind:ctx.kind,depth:ctx.depth,
      scheduledAt:ctx.scheduledAt,runAt:ctx.runAt||0,runCount:ctx.runCount||0,meta:ctx.meta||{}};
  }
  function makeContext(kind,parent,meta){
    if(contexts.length>=MAX_CONTEXTS){state.contextDrops++;return parent||null;}
    const id=nextContextId++,ctx={id:id,parentId:parent?parent.id:0,rootId:parent?parent.rootId:id,
      kind:String(kind||'async').slice(0,64),depth:parent?parent.depth+1:0,scheduledAt:Date.now(),runAt:0,runCount:0,
      meta:meta||{},scheduleStack:stackLines(4,10),runStack:[]};
    contexts.push(ctx);contextById.set(id,ctx);if(parent)state.asyncScheduled++;return ctx;
  }
  function runContext(ctx,fn,self,args){
    if(!ctx||typeof fn!=='function')return typeof fn==='function'?fn.apply(self,args||[]):undefined;
    const prev=currentContext;currentContext=ctx;ctx.runAt=Date.now();ctx.runCount++;ctx.runStack=stackLines(4,12);state.asyncRuns++;
    try{return fn.apply(self,args||[]);}finally{currentContext=prev;}
  }
  function wrapScheduled(fn,kind,meta){
    if(typeof fn!=='function'||!currentContext)return fn;
    const ctx=makeContext(kind,currentContext,meta||{});
    if(!ctx)return fn;
    return function(){return runContext(ctx,fn,this,Array.prototype.slice.call(arguments));};
  }
  function chainFor(ctx){
    const out=[],seen=new Set();let c=ctx;
    while(c&&out.length<MAX_CHAIN&&!seen.has(c.id)){seen.add(c.id);out.push({
      id:c.id,parentId:c.parentId,rootId:c.rootId,kind:c.kind,depth:c.depth,
      scheduledAgeMs:Date.now()-c.scheduledAt,runCount:c.runCount,meta:c.meta||{},
      scheduleStack:(c.scheduleStack||[]).slice(0,8),runStack:(c.runStack||[]).slice(0,8)
    });c=c.parentId?contextById.get(c.parentId):null;}
    return out.reverse();
  }
  function exactOracleListenerHashes(){
    try{
      const p=window.__AIS_R184_ORACLE_PROBE__,d=p&&typeof p.describe==='function'?p.describe():null;
      const rel=d&&d.lastOracle&&Array.isArray(d.lastOracle.related)?d.lastOracle.related:[];
      return Array.from(new Set(rel.filter(function(x){return x&&x.relation==='exact'&&x.sourceHash;}).map(function(x){return String(x.sourceHash).slice(0,32);}))).slice(0,12);
    }catch(_){return [];}
  }

  function installAsyncHooks(){
    if(state.asyncHooksInstalled)return;
    try{
      const nativeThen=Promise.prototype.then;
      Promise.prototype.then=function(onFulfilled,onRejected){
        return nativeThen.call(this,wrapScheduled(onFulfilled,'promise-fulfill',{}),wrapScheduled(onRejected,'promise-reject',{}));
      };
    }catch(e){bridge('R185_HOOK_ERROR',{target:'Promise.then',name:String(e&&e.name||'Error')});}
    try{
      const nativeCatch=Promise.prototype.catch;
      Promise.prototype.catch=function(onRejected){return nativeCatch.call(this,wrapScheduled(onRejected,'promise-catch',{}));};
    }catch(_){}
    try{
      const nativeFinally=Promise.prototype.finally;
      if(typeof nativeFinally==='function')Promise.prototype.finally=function(onFinally){return nativeFinally.call(this,wrapScheduled(onFinally,'promise-finally',{}));};
    }catch(_){}
    try{
      const nativeQ=window.queueMicrotask;
      if(typeof nativeQ==='function')window.queueMicrotask=function(fn){return nativeQ.call(this,wrapScheduled(fn,'queueMicrotask',{}));};
    }catch(_){}
    try{
      const nativeTimeout=window.setTimeout;
      window.setTimeout=function(fn,delay){
        const args=Array.prototype.slice.call(arguments,2);
        if(typeof fn!=='function')return nativeTimeout.apply(this,arguments);
        const wrapped=wrapScheduled(fn,'setTimeout',{delay:Number(delay||0)});
        return nativeTimeout(function(){return wrapped.apply(this,args);},delay);
      };
    }catch(_){}
    try{
      const nativeInterval=window.setInterval;
      window.setInterval=function(fn,delay){if(typeof fn!=='function')return nativeInterval.apply(this,arguments);const wrapped=wrapScheduled(fn,'setInterval',{delay:Number(delay||0)});const args=Array.prototype.slice.call(arguments,2);return nativeInterval(function(){return wrapped.apply(this,args);},delay);};
    }catch(_){}
    try{
      const nativeRaf=window.requestAnimationFrame;
      if(typeof nativeRaf==='function')window.requestAnimationFrame=function(fn){return nativeRaf.call(this,wrapScheduled(fn,'requestAnimationFrame',{}));};
    }catch(_){}
    try{
      if(window.scheduler&&typeof window.scheduler.postTask==='function'){
        const nativePostTask=window.scheduler.postTask.bind(window.scheduler);
        window.scheduler.postTask=function(fn,options){return nativePostTask(wrapScheduled(fn,'scheduler.postTask',{priority:String(options&&options.priority||'').slice(0,40)}),options);};
      }
    }catch(_){}
    try{
      const NativeMC=window.MessageChannel,MP=window.MessagePort&&window.MessagePort.prototype;
      if(typeof NativeMC==='function'&&MP){
        const peers=new WeakMap(),queues=new WeakMap(),listenerMaps=new WeakMap();
        function CausalMessageChannel(){
          const ch=new NativeMC();
          try{peers.set(ch.port1,ch.port2);peers.set(ch.port2,ch.port1);}catch(_){}
          return ch;
        }
        CausalMessageChannel.prototype=NativeMC.prototype;
        try{Object.setPrototypeOf(CausalMessageChannel,NativeMC);}catch(_){}
        window.MessageChannel=CausalMessageChannel;
        const nativePost=MP.postMessage,nativeAdd=MP.addEventListener,nativeRemove=MP.removeEventListener;
        MP.postMessage=function(){
          try{
            if(currentContext){
              const target=peers.get(this);
              if(target){
                const ctx=makeContext('MessagePort.postMessage',currentContext,{});
                if(ctx){const q=queues.get(target)||[];q.push(ctx);if(q.length>24)q.shift();queues.set(target,q);}
              }
            }
          }catch(_){}
          return nativePost.apply(this,arguments);
        };
        MP.addEventListener=function(type,listener,options){
          if(String(type||'')==='message'&&typeof listener==='function'){
            let map=listenerMaps.get(this);if(!map){map=new WeakMap();listenerMaps.set(this,map);}
            let wrapped=map.get(listener);
            if(!wrapped){
              wrapped=function(ev){
                let ctx=null;try{const q=queues.get(this)||[];ctx=q.length?q.shift():null;}catch(_){}
                if(ctx)return runContext(ctx,listener,this,[ev]);
                return listener.call(this,ev);
              };
              map.set(listener,wrapped);
            }
            return nativeAdd.call(this,type,wrapped,options);
          }
          return nativeAdd.apply(this,arguments);
        };
        MP.removeEventListener=function(type,listener,options){
          if(String(type||'')==='message'&&typeof listener==='function'){
            try{const map=listenerMaps.get(this),wrapped=map&&map.get(listener);if(wrapped)return nativeRemove.call(this,type,wrapped,options);}catch(_){}
          }
          return nativeRemove.apply(this,arguments);
        };
      }
    }catch(e){bridge('R185_HOOK_ERROR',{target:'MessageChannel',name:String(e&&e.name||'Error')});}
    state.asyncHooksInstalled=true;bridge('R185_ASYNC_HOOKS',{installed:true});
  }

  function installSetupCausalHook(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype||X.prototype.__aisR185SetupCausal)return;
      const open=X.prototype.open,send=X.prototype.send;
      X.prototype.open=function(method,url){try{this.__aisR185Url=String(url||'');}catch(_){}return open.apply(this,arguments);};
      X.prototype.send=function(body){
        try{
          const url=String(this.__aisR185Url||''),text=typeof body==='string'?body:'';
          if(url.indexOf('/v1/bidiGenerateContent')>=0&&text.toLowerCase().indexOf(TARGET_MODEL)>=0&&!/audio\/pcm/i.test(text)){
            state.setupRequests++;
            const ctx=currentContext,chain=chainFor(ctx);
            if(ctx)state.setupLinked++;else state.setupUnlinked++;
            const payload={linked:!!ctx,context:contextMeta(ctx),contextChain:chain,exactListenerHashes:chain.length&&chain[0].meta?chain[0].meta.exactListenerHashes||[]:[],
              afterOracleMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,stack:stackLines(4,MAX_STACK)};
            state.lastSetupContext=payload;
            bridge(ctx?'R185_SETUP_CAUSAL_LINK':'R185_SETUP_UNLINKED',payload);
          }
        }catch(e){bridge('R185_SETUP_HOOK_ERROR',{name:String(e&&e.name||'Error')});}
        return send.apply(this,arguments);
      };
      X.prototype.__aisR185SetupCausal=true;
    }catch(e){bridge('R185_HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}
  }

  function buildSyntheticCarrier(){
    if(synthetic)return synthetic;
    try{
      const C=window.AudioContext||window.webkitAudioContext;if(!C)throw new Error('AudioContext unavailable');
      carrierContext=new C({sampleRate:16000});carrierOscillator=carrierContext.createOscillator();carrierGain=carrierContext.createGain();
      const dest=carrierContext.createMediaStreamDestination();carrierOscillator.type='sine';carrierOscillator.frequency.value=173;carrierGain.gain.value=0.0;
      carrierOscillator.connect(carrierGain);carrierGain.connect(dest);carrierOscillator.start();
      try{const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}
      synthetic=dest.stream;state.syntheticCarrier=true;bridge('SYNTHETIC_CARRIER_READY',{tracks:synthetic.getAudioTracks().length,sampleRate:Number(carrierContext.sampleRate||0)});return synthetic;
    }catch(e){state.syntheticErrors++;state.lastError=String(e&&e.name||'Error');bridge('SYNTHETIC_CARRIER_ERROR',{count:state.syntheticErrors,name:state.lastError});return null;}
  }
  function installSyntheticGum(){
    try{
      const md=navigator.mediaDevices;if(!md||typeof md.getUserMedia!=='function'||md.getUserMedia.__aisR184Synthetic)return;
      const native=md.getUserMedia.bind(md);
      const wrapped=function(constraints){try{const c=constraints||{};if(!!c.audio&&!c.video){const stream=buildSyntheticCarrier();if(stream){bridge('SYNTHETIC_GUM',{audio:true,causalContext:contextMeta(currentContext)});return Promise.resolve(stream);}}}catch(_){}return native(constraints);};
      wrapped.__aisR184Synthetic=true;md.getUserMedia=wrapped;bridge('HOOK',{target:'getUserMedia-synthetic-r174'});
    }catch(e){bridge('HOOK_ERROR',{target:'getUserMedia-synthetic-r174',name:String(e&&e.name||'Error')});}
  }
  function labOnlyClick(el,score){
    try{
      const p=window.__AIS_R184_ORACLE_PROBE__;if(p&&typeof p.markOracleTarget==='function')p.markOracleTarget(el,{score:score});
      state.attempts++;state.lastScore=score;state.lastActionAt=Date.now();state.stage='oracle-clicked';
      const root=makeContext('oracle-start-action',null,{score:score,tag:tag(el),role:role(el),ariaChars:attr(el,'aria-label').length,exactListenerHashes:exactOracleListenerHashes()});
      state.causalRoots++;state.lastRoot=contextMeta(root);
      bridge('LAB_ONLY_START_ATTEMPT',{attempt:state.attempts,score:score,tag:tag(el),role:role(el),ariaChars:attr(el,'aria-label').length,
        causalRoot:state.lastRoot,exactListenerHashes:root&&root.meta?root.meta.exactListenerHashes||[]:[]});
      // LAB_ONLY_UI_ORACLE: exact R17.4 discovery trigger. Never use this in production.
      return runContext(root,function(){
        if(typeof el.click==='function')el.click();
        else{const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;el.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,view:w}));}
        buildSyntheticCarrier();return true;
      },null,[]);
    }catch(e){state.lastError=String(e&&e.name||'Error');bridge('LAB_ONLY_START_ERROR',{name:state.lastError});return false;}
  }
  function scan(){
    if(!state.active)return;
    if(setupSeen()){state.stage='setup-complete';state.setupAt=Date.now();state.active=false;bridge('SUCCESS',{attempts:state.attempts,setupMs:state.lastActionAt?state.setupAt-state.lastActionAt:-1,setupLinked:state.setupLinked});return;}
    if(!routeReady()){state.stage='waiting-live-route';timer=setTimeout(scan,900);return;}
    if(state.attempts>=state.maxAttempts){state.stage='attempts-exhausted';state.active=false;bridge('FINAL_NO_START',{attempts:state.attempts});return;}
    const interactive=collectDeep(),scored=[];
    for(let i=0;i<interactive.length;i++){const s=startScore(interactive[i]);if(s>=7)scored.push({el:interactive[i],score:s});}
    scored.sort(function(a,b){return b.score-a.score;});state.candidates=scored.length;
    if(!scored.length){state.stage='start-control-not-found';timer=setTimeout(scan,900);return;}
    labOnlyClick(scored[0].el,scored[0].score);
    timer=setTimeout(scan,6500);
  }
  function start(code){
    try{if(timer)clearTimeout(timer);}catch(_){}timer=0;
    state.targetLanguage=safeCode(code);state.active=true;state.stage='arming-r175-oracle';state.scans=0;state.candidates=0;state.attempts=0;state.lastScore=0;state.lastActionAt=0;state.setupAt=0;state.lastError='';
    state.causalRoots=0;state.asyncScheduled=0;state.asyncRuns=0;state.contextDrops=0;state.setupRequests=0;state.setupLinked=0;state.setupUnlinked=0;state.lastRoot=null;state.lastSetupContext=null;
    contexts.length=0;contextById.clear();currentContext=null;nextContextId=1;
    try{const g=window.__AIS_R183_LANGUAGE__;if(g&&typeof g.configure==='function')g.configure(state.targetLanguage);}catch(_){}
    buildSyntheticCarrier();bridge('START',{targetLanguageCode:state.targetLanguage,targetModel:TARGET_MODEL,labOnly:true,asyncCausal:true});timer=setTimeout(scan,120);return describe();
  }
  function reset(){
    try{if(timer)clearTimeout(timer);}catch(_){}timer=0;state.active=false;state.stage='idle';state.scans=0;state.candidates=0;state.attempts=0;state.lastScore=0;state.lastActionAt=0;state.setupAt=0;state.lastError='';
    state.causalRoots=0;state.asyncScheduled=0;state.asyncRuns=0;state.contextDrops=0;state.setupRequests=0;state.setupLinked=0;state.setupUnlinked=0;state.lastRoot=null;state.lastSetupContext=null;
    contexts.length=0;contextById.clear();currentContext=null;nextContextId=1;return describe();
  }
  function describe(){
    if(setupSeen()&&state.stage!=='setup-complete'){state.stage='setup-complete';state.active=false;}
    return {ok:true,version:VERSION,labOnly:true,targetModel:TARGET_MODEL,targetLanguage:state.targetLanguage,active:state.active,stage:state.stage,
      scans:state.scans,candidates:state.candidates,attempts:state.attempts,maxAttempts:state.maxAttempts,syntheticCarrier:state.syntheticCarrier,
      syntheticErrors:state.syntheticErrors,lastScore:state.lastScore,lastActionAgeMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,
      setupAgeMs:state.setupAt?Date.now()-state.setupAt:-1,lastError:state.lastError,
      causal:{asyncHooksInstalled:state.asyncHooksInstalled,causalRoots:state.causalRoots,asyncScheduled:state.asyncScheduled,asyncRuns:state.asyncRuns,
        contextDrops:state.contextDrops,setupRequests:state.setupRequests,setupLinked:state.setupLinked,setupUnlinked:state.setupUnlinked,
        currentContext:contextMeta(currentContext),lastRoot:state.lastRoot,lastSetupContext:state.lastSetupContext,
        contextCount:contexts.length,contexts:contexts.slice(0,80).map(contextMeta)}};
  }
  installAsyncHooks();installSetupCausalHook();installSyntheticGum();
  window.__AIS_R184_START_ORACLE__={version:VERSION,start:start,reset:reset,describe:describe};
  bridge('ENGINE_INSTALLED',{version:VERSION,labOnly:true,targetModel:TARGET_MODEL,asyncCausal:true});
})();
""".trimIndent()
}
