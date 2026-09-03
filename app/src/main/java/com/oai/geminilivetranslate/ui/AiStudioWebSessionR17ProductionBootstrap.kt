package com.oai.geminilivetranslate.ui

/**
 * R17.2 hidden production bootstrap for AI Studio Stream.
 *
 * R17.2 repairs hidden auto-bootstrap discovery without changing the proven R14/R16 transport.
 * It traverses ordinary DOM, open shadow roots and same-origin frames, preserves function-specific
 * Live models, and enforces that model page-locally on Live setup requests when the UI selector is
 * unavailable. Diagnostics contain counters/state only, never page text, request bodies or media.
 */
object AiStudioWebSessionR17ProductionBootstrap {
    const val VERSION = "2026-09-03-web-session-r17.2-deep-bootstrap"
    const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
    const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"
    /** Compatibility-only diagnostic label. Actual selection is always function-specific. */
    const val TARGET_MODEL = "function-specific"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.version){return;}
  const VERSION='2026-09-03-web-session-r17.2-deep-bootstrap';
  const TRANSLATE_MODEL='gemini-3.5-live-translate-preview';
  const TRANSCRIBE_MODEL='gemini-3.5-transcribe-live';
  const state={
    configured:false,transcribeOnly:false,targetLanguage:'vi',targetModel:TRANSLATE_MODEL,
    instructionApplied:false,streamSelected:false,modelSeen:false,modelVerified:false,
    setupObserved:false,carrierActive:false,syntheticCarrier:false,syntheticErrors:0,
    pageOutputMuted:false,authRequired:false,stage:'boot',lastBlocker:'not-configured',
    lastAction:'',lastActionAt:0,lastTickAt:0,
    deepElements:0,interactiveControls:0,shadowRoots:0,frameDocuments:0,
    discoveryScans:0,streamScans:0,streamCandidates:0,streamAttempts:0,
    modelScans:0,modelCandidates:0,modelAttempts:0,
    startScans:0,startCandidates:0,startAttempts:0,
    modelGuardInstalled:false,modelRewriteRequests:0,modelRewriteCount:0,
    routeKind:'other'
  };
  let synthetic=null;
  let carrierGain=null;
  let carrierContext=null;
  let carrierOscillator=null;
  let lastDiscoverySignature='';
  const clickTimes=new WeakMap();

  function diag(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R17_'+kind,payload:payload||{}}));
    }catch(_){}
  }
  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||260);}
  function attr(el,name){try{return el&&el.getAttribute?safeText(el.getAttribute(name)||'',180):'';}catch(_){return '';}}
  function label(el){
    try{
      const parts=[attr(el,'aria-label'),attr(el,'placeholder'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safeText(el.title||'',120),safeText(el.value||'',180),safeText(el.textContent||'',260)];
      return safeText(parts.filter(Boolean).join(' '),420).toLowerCase();
    }catch(_){return '';}
  }
  function role(el){return attr(el,'role').toLowerCase();}
  function tag(el){try{return String(el.tagName||'').toUpperCase();}catch(_){return '';}}
  function isInteractive(el){
    const t=tag(el),r=role(el);
    if(t==='BUTTON'||t==='A'||t==='INPUT'||t==='TEXTAREA'||t==='SELECT'||t==='OPTION')return true;
    if(r==='button'||r==='tab'||r==='option'||r==='menuitem'||r==='combobox'||r==='link'||r==='listbox')return true;
    if(attr(el,'aria-haspopup'))return true;
    try{if(el.isContentEditable)return true;}catch(_){}
    return false;
  }
  function collectDeep(){
    state.discoveryScans++;
    const roots=[document],seen=new Set(),all=[],interactive=[];
    let shadow=0,frames=0;
    while(roots.length&&all.length<7000){
      const root=roots.shift();
      if(!root||seen.has(root))continue;
      seen.add(root);
      let nodes=[];
      try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&all.length<7000;i++){
        const el=nodes[i];all.push(el);
        if(isInteractive(el))interactive.push(el);
        try{if(el.shadowRoot){roots.push(el.shadowRoot);shadow++;}}catch(_){}
        try{
          const t=tag(el);
          if((t==='IFRAME'||t==='FRAME')&&el.contentDocument){roots.push(el.contentDocument);frames++;}
        }catch(_){}
      }
    }
    state.deepElements=all.length;state.interactiveControls=interactive.length;state.shadowRoots=shadow;state.frameDocuments=frames;
    return {all:all,interactive:interactive};
  }
  function hrefPath(el){
    try{
      const raw=attr(el,'href')||(el&&el.href?String(el.href):'');
      if(!raw)return '';
      const u=new URL(raw,location.href);
      if(u.origin!==location.origin)return '';
      return String(u.pathname||'').toLowerCase();
    }catch(_){return '';}
  }
  function detectRoute(){
    try{
      const p=String(location.pathname||'').toLowerCase();
      if(p.indexOf('stream')>=0)return 'stream';
      if(p.indexOf('live')>=0)return 'live';
      if(p.indexOf('realtime')>=0)return 'realtime';
    }catch(_){}
    return 'other';
  }
  function nativeSetValue(el,value){
    try{
      const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;
      const t=tag(el);
      const proto=t==='TEXTAREA'?w.HTMLTextAreaElement&&w.HTMLTextAreaElement.prototype:w.HTMLInputElement&&w.HTMLInputElement.prototype;
      const desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');
      if(desc&&desc.set)desc.set.call(el,value);else el.value=value;
      el.dispatchEvent(new w.Event('input',{bubbles:true}));
      el.dispatchEvent(new w.Event('change',{bubbles:true}));
      return true;
    }catch(_){return false;}
  }
  function clickElement(el,reason){
    try{
      if(!el)return false;
      const now=Date.now(),previous=Number(clickTimes.get(el)||0);
      if(previous&&now-previous<1800)return false;
      clickTimes.set(el,now);
      if(typeof el.click==='function')el.click();
      else{
        const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;
        el.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,view:w}));
      }
      state.lastAction=reason;state.lastActionAt=now;
      diag('AUTO_ACTION',{reason:reason,role:role(el)||'none',tag:tag(el)||'none'});
      return true;
    }catch(e){diag('AUTO_ACTION_ERROR',{reason:reason,name:String(e&&e.name||'Error')});return false;}
  }
  function instruction(){
    if(state.transcribeOnly)return 'Transcribe the incoming speech faithfully. Preserve the spoken language and wording. Do not answer or add commentary.';
    return 'Act as a simultaneous interpreter. Translate all incoming speech into '+state.targetLanguage+'. Preserve meaning, names, numbers and tone. Output only the translation and speak the translated result. Do not answer the speaker as an assistant.';
  }
  function tryInstruction(snapshot){
    if(state.instructionApplied||!state.configured)return;
    const list=snapshot.interactive;
    for(let i=0;i<list.length;i++){
      const el=list[i],t=tag(el);
      if(t!=='TEXTAREA'&&t!=='INPUT')continue;
      const l=label(el);
      if(!/(system instructions?|instructions?|system prompt)/i.test(l))continue;
      if(nativeSetValue(el,instruction())){
        state.instructionApplied=true;state.lastAction='instruction';state.lastActionAt=Date.now();
        diag('INSTRUCTION_APPLIED',{targetLanguage:state.targetLanguage,transcribeOnly:state.transcribeOnly,targetModel:state.targetModel,chars:instruction().length});
        return;
      }
    }
  }
  function streamScore(el){
    const l=label(el),p=hrefPath(el),r=role(el),t=tag(el);
    let s=0;
    if(/(^|\s)(stream|streaming|live stream|realtime stream|real time stream)(\s|$)/.test(l))s+=8;
    if(l.indexOf('stream')>=0)s+=4;
    if(l.indexOf('realtime')>=0||l.indexOf('real time')>=0)s+=3;
    if((p.indexOf('stream')>=0||p.indexOf('live')>=0||p.indexOf('realtime')>=0)&&(t==='A'||r==='link'||r==='tab'||r==='button'))s+=8;
    if(r==='tab'||r==='menuitem'||r==='link'||r==='button')s+=1;
    return s;
  }
  function tryStreamMode(snapshot){
    state.streamScans++;state.routeKind=detectRoute();
    if(state.streamSelected)return;
    if(state.routeKind==='stream'||state.routeKind==='live'||state.routeKind==='realtime'){
      state.streamSelected=true;state.stage='model';state.lastBlocker='none';return;
    }
    const scored=[];
    for(let i=0;i<snapshot.interactive.length;i++){
      const s=streamScore(snapshot.interactive[i]);if(s>=7)scored.push({el:snapshot.interactive[i],score:s});
    }
    scored.sort(function(a,b){return b.score-a.score;});
    state.streamCandidates=scored.length;
    if(scored.length){
      state.streamAttempts++;
      if(clickElement(scored[0].el,'select-stream')){state.stage='stream-clicked';state.lastBlocker='waiting-stream-navigation';return;}
    }
    state.lastBlocker='stream-control-not-found';
  }
  function modelAliases(){
    const raw=String(state.targetModel||'').toLowerCase().replace(/^models\//,'');
    const human=raw.replace(/-/g,' ');
    const compact=human.replace(/\bpreview\b/g,'').replace(/\s+/g,' ').trim();
    return [raw,human,compact].filter(function(v,i,a){return !!v&&a.indexOf(v)===i;});
  }
  function hasTargetModel(text){
    const value=String(text||'').toLowerCase();
    const aliases=modelAliases();
    for(let i=0;i<aliases.length;i++)if(value.indexOf(aliases[i])>=0)return true;
    return false;
  }
  function tryModel(snapshot){
    state.modelScans++;
    if(!state.streamSelected)return;
    const exact=[];
    for(let i=0;i<snapshot.interactive.length;i++){
      const el=snapshot.interactive[i];if(hasTargetModel(label(el)))exact.push(el);
    }
    state.modelCandidates=exact.length;
    if(exact.length&&!state.modelSeen){
      state.modelAttempts++;
      if(clickElement(exact[0],'select-target-model')){
        state.modelSeen=true;state.stage='model-selected';state.lastBlocker='waiting-start';
        diag('MODEL_SELECTED',{targetModel:state.targetModel,attempt:state.modelAttempts});
        return;
      }
    }
    if(!state.modelSeen&&state.modelAttempts<10){
      let opener=null;
      for(let i=0;i<snapshot.interactive.length;i++){
        const el=snapshot.interactive[i],l=label(el),r=role(el),popup=attr(el,'aria-haspopup').toLowerCase();
        if((r==='combobox'||popup==='listbox'||popup==='menu'||l.indexOf('model')>=0||l.indexOf('gemini')>=0)&&l.length<420){opener=el;break;}
      }
      if(opener){state.modelAttempts++;clickElement(opener,'open-model-selector');state.lastBlocker='waiting-model-options';}
      else state.lastBlocker='model-control-not-found';
    }
  }
  function setupSeen(){
    try{
      const e=window.__AIS_LIVE_OUTPUT_ENGINE__;
      if(e&&typeof e.describe==='function')return Number(e.describe().setupCompleteEvents||0)>0;
    }catch(_){}
    return false;
  }
  function startScore(el){
    const l=label(el),r=role(el),t=tag(el);
    if(!(t==='BUTTON'||r==='button'||r==='menuitem'||r==='tab'))return 0;
    let s=0;
    if(/^(start|start session|start streaming|start stream|start live|start conversation|connect|go live|talk|begin)/.test(l))s+=9;
    if(l.indexOf('microphone')>=0||l.indexOf('start speaking')>=0||l.indexOf('speak now')>=0)s+=6;
    if(l.indexOf('stream')>=0||l.indexOf('live')>=0)s+=2;
    return s;
  }
  function tryStart(snapshot){
    state.startScans++;
    if(setupSeen()){state.setupObserved=true;state.stage='setup-complete';state.lastBlocker='none';return;}
    if(!state.streamSelected){state.lastBlocker='waiting-stream';return;}
    if(!state.modelSeen&&!state.modelGuardInstalled){state.lastBlocker='waiting-model';return;}
    const scored=[];
    for(let i=0;i<snapshot.interactive.length;i++){
      const s=startScore(snapshot.interactive[i]);if(s>=7)scored.push({el:snapshot.interactive[i],score:s});
    }
    scored.sort(function(a,b){return b.score-a.score;});
    state.startCandidates=scored.length;
    if(scored.length){
      state.startAttempts++;
      if(clickElement(scored[0].el,'start-live')){state.stage='start-clicked';state.lastBlocker='waiting-live-setup';buildSyntheticCarrier();return;}
    }
    state.lastBlocker='start-control-not-found';
  }
  function rewriteSetupBody(body){
    try{
      let params=null,asString=false;
      if(typeof body==='string'){params=new URLSearchParams(body);asString=true;}
      else if(body instanceof URLSearchParams){params=new URLSearchParams(body.toString());}
      else return body;
      let changed=false,requestTouched=false,replacements=0;
      const updates=[];
      params.forEach(function(value,key){
        if(!/^req\d+___data__$/.test(String(key)))return;
        if(String(value).indexOf('audio/pcm')>=0)return;
        requestTouched=true;
        if(hasTargetModel(value)){state.modelSeen=true;state.modelVerified=true;return;}
        const next=String(value).replace(/(models\/)?gemini-[a-z0-9._-]*(?:live|translate|transcribe)[a-z0-9._-]*/ig,function(match,prefix){
          replacements++;return (prefix?'models/':'')+state.targetModel;
        });
        if(next!==value){updates.push([key,next]);changed=true;}
      });
      for(let i=0;i<updates.length;i++)params.set(updates[i][0],updates[i][1]);
      if(requestTouched&&changed){
        state.modelRewriteRequests++;state.modelRewriteCount+=replacements;state.modelSeen=true;state.modelVerified=true;
        diag('MODEL_REQUEST_GUARD',{targetModel:state.targetModel,rewriteRequests:state.modelRewriteRequests,rewriteCount:state.modelRewriteCount});
      }
      return changed?(asString?params.toString():params):body;
    }catch(_){return body;}
  }
  function installModelGuard(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype)return;
      const p=X.prototype;
      if(p.send&&p.send.__aisR172ModelGuard){state.modelGuardInstalled=true;return;}
      const currentOpen=p.open,currentSend=p.send;
      p.open=function(method,url){try{this.__aisR172Url=String(url||'');}catch(_){}return currentOpen.apply(this,arguments);};
      const wrappedSend=function(body){
        let next=body;
        try{if(String(this.__aisR172Url||'').indexOf('/v1/bidiGenerateContent')>=0)next=rewriteSetupBody(body);}catch(_){}
        return currentSend.call(this,next);
      };
      wrappedSend.__aisR172ModelGuard=true;p.send=wrappedSend;state.modelGuardInstalled=true;
      diag('HOOK',{target:'bidi-model-guard'});
    }catch(e){diag('HOOK_ERROR',{target:'bidi-model-guard',name:String(e&&e.name||'Error')});}
  }
  function buildSyntheticCarrier(){
    if(synthetic)return synthetic;
    try{
      const C=window.AudioContext||window.webkitAudioContext;
      if(!C)throw new Error('AudioContext unavailable');
      carrierContext=new C({sampleRate:16000});carrierOscillator=carrierContext.createOscillator();carrierGain=carrierContext.createGain();
      const dest=carrierContext.createMediaStreamDestination();carrierOscillator.type='sine';carrierOscillator.frequency.value=173;carrierGain.gain.value=0.0;
      carrierOscillator.connect(carrierGain);carrierGain.connect(dest);carrierOscillator.start();
      try{const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}
      synthetic=dest.stream;state.syntheticCarrier=true;diag('SYNTHETIC_CARRIER_READY',{tracks:synthetic.getAudioTracks().length,sampleRate:carrierContext.sampleRate||0});return synthetic;
    }catch(e){state.syntheticErrors++;diag('SYNTHETIC_CARRIER_ERROR',{count:state.syntheticErrors,name:String(e&&e.name||'Error')});return null;}
  }
  function setCarrierActive(enabled){
    state.carrierActive=!!enabled;
    try{
      if(!carrierGain)buildSyntheticCarrier();
      if(carrierGain){carrierGain.gain.setTargetAtTime(enabled?0.018:0.0,carrierContext.currentTime,0.01);}
      if(carrierContext&&carrierContext.state==='suspended'){const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}
    }catch(_){}
    return describe();
  }
  function installSyntheticGum(){
    try{
      const md=navigator.mediaDevices;if(!md||typeof md.getUserMedia!=='function')return;
      if(md.getUserMedia.__aisR17Synthetic)return;
      const native=md.getUserMedia.bind(md);
      const wrapped=function(constraints){
        try{const c=constraints||{};if(!!c.audio&&!c.video){const stream=buildSyntheticCarrier();if(stream)return Promise.resolve(stream);}}catch(_){}
        return native(constraints);
      };
      wrapped.__aisR17Synthetic=true;md.getUserMedia=wrapped;diag('HOOK',{target:'getUserMedia-synthetic'});
    }catch(e){diag('HOOK_ERROR',{target:'getUserMedia-synthetic',name:String(e&&e.name||'Error')});}
  }
  function installOutputMute(){
    try{
      const A=window.AudioNode&&window.AudioNode.prototype;if(!A||typeof A.connect!=='function'||A.connect.__aisR17Muted)return;
      const native=A.connect,muteByContext=new WeakMap();
      const wrapped=function(destination){
        try{
          const name=destination&&destination.constructor&&destination.constructor.name||'';
          if(name==='AudioDestinationNode'&&this.context&&typeof this.context.createGain==='function'){
            let g=muteByContext.get(this.context);if(!g){g=this.context.createGain();g.gain.value=0;native.call(g,destination);muteByContext.set(this.context,g);}state.pageOutputMuted=true;return native.call(this,g);
          }
        }catch(_){}return native.apply(this,arguments);
      };
      wrapped.__aisR17Muted=true;A.connect=wrapped;diag('HOOK',{target:'webaudio-output-mute'});
    }catch(e){diag('HOOK_ERROR',{target:'webaudio-output-mute',name:String(e&&e.name||'Error')});}
  }
  function detectAuth(){
    try{const h=String(location.hostname||'').toLowerCase();state.authRequired=h.indexOf('accounts.google.')>=0||h==='accounts.google.com';}catch(_){}
  }
  function reportDiscovery(){
    const signature=[state.stage,state.lastBlocker,state.deepElements,state.interactiveControls,state.shadowRoots,state.frameDocuments,state.streamCandidates,state.modelCandidates,state.startCandidates,state.streamAttempts,state.modelAttempts,state.startAttempts,state.modelRewriteRequests].join('|');
    if(signature===lastDiscoverySignature)return;lastDiscoverySignature=signature;
    diag('DISCOVERY',{stage:state.stage,blocker:state.lastBlocker,deepElements:state.deepElements,interactiveControls:state.interactiveControls,shadowRoots:state.shadowRoots,frameDocuments:state.frameDocuments,streamCandidates:state.streamCandidates,modelCandidates:state.modelCandidates,startCandidates:state.startCandidates,streamAttempts:state.streamAttempts,modelAttempts:state.modelAttempts,startAttempts:state.startAttempts,modelGuardInstalled:state.modelGuardInstalled,modelRewriteRequests:state.modelRewriteRequests,routeKind:state.routeKind});
  }
  function tick(){
    state.lastTickAt=Date.now();detectAuth();
    if(state.authRequired){state.stage='auth';state.lastBlocker='auth-required';return;}
    if(window.top!==window)return;
    if(!state.configured){state.stage='boot';state.lastBlocker='not-configured';return;}
    const snapshot=collectDeep();
    tryStreamMode(snapshot);tryModel(snapshot);tryInstruction(snapshot);tryStart(snapshot);reportDiscovery();
  }
  function configure(targetLanguage,transcribeOnly){
    state.targetLanguage=safeText(targetLanguage||'vi',60)||'vi';state.transcribeOnly=!!transcribeOnly;
    state.targetModel=state.transcribeOnly?TRANSCRIBE_MODEL:TRANSLATE_MODEL;
    state.configured=true;state.instructionApplied=false;state.modelSeen=false;state.modelVerified=false;state.stage='discover';state.lastBlocker='stream-control-not-found';
    diag('FUNCTION_MODEL',{transcribeOnly:state.transcribeOnly,targetModel:state.targetModel});tick();return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,targetModel:state.targetModel,configured:state.configured,transcribeOnly:state.transcribeOnly,targetLanguage:state.targetLanguage,
      instructionApplied:state.instructionApplied,streamSelected:state.streamSelected,modelSeen:state.modelSeen,modelVerified:state.modelVerified,
      setupObserved:state.setupObserved||setupSeen(),carrierActive:state.carrierActive,syntheticCarrier:state.syntheticCarrier,syntheticErrors:state.syntheticErrors,pageOutputMuted:state.pageOutputMuted,authRequired:state.authRequired,
      stage:state.stage,lastBlocker:state.lastBlocker,deepElements:state.deepElements,interactiveControls:state.interactiveControls,shadowRoots:state.shadowRoots,frameDocuments:state.frameDocuments,discoveryScans:state.discoveryScans,
      streamScans:state.streamScans,streamCandidates:state.streamCandidates,streamAttempts:state.streamAttempts,modelScans:state.modelScans,modelCandidates:state.modelCandidates,modelAttempts:state.modelAttempts,
      startScans:state.startScans,startCandidates:state.startCandidates,startAttempts:state.startAttempts,modelGuardInstalled:state.modelGuardInstalled,modelRewriteRequests:state.modelRewriteRequests,modelRewriteCount:state.modelRewriteCount,routeKind:state.routeKind,
      lastAction:state.lastAction,lastActionAgeMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,lastTickAgeMs:state.lastTickAt?Date.now()-state.lastTickAt:-1};
  }
  function resetAutomation(){
    state.streamSelected=false;state.modelSeen=false;state.modelVerified=false;state.setupObserved=false;state.instructionApplied=false;
    state.startAttempts=0;state.streamAttempts=0;state.modelAttempts=0;state.streamCandidates=0;state.modelCandidates=0;state.startCandidates=0;state.lastAction='';state.stage='discover';state.lastBlocker='stream-control-not-found';tick();return describe();
  }

  installModelGuard();installSyntheticGum();installOutputMute();
  window.__AIS_R17_PRODUCTION__={version:VERSION,configure:configure,setCarrierActive:setCarrierActive,describe:describe,resetAutomation:resetAutomation};
  setInterval(tick,700);setTimeout(tick,0);setTimeout(tick,900);setTimeout(tick,2200);
  diag('ENGINE_INSTALLED',{version:VERSION,top:window.top===window,modelGuardInstalled:state.modelGuardInstalled});
})();
    """.trimIndent()
}
