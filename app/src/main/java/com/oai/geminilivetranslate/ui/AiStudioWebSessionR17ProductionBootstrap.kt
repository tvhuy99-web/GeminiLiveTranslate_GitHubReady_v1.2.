package com.oai.geminilivetranslate.ui

/**
 * R17.6 lean production bootstrap for authenticated AI Studio Live.
 *
 * The earlier R17.5 script grew from several laboratory generations and could disappear before
 * publishing its global controller on some Android WebView builds. R17.6 keeps only the production
 * responsibilities that the hidden Live backend needs: route/model awareness, request-level model
 * and target-language guard, synthetic microphone carrier, hidden Start activation, and compact
 * diagnostics. R14 owns Android PCM replacement and R16 owns translated output decoding.
 *
 * No auth material, request body, cookie, token, or media payload is exported to diagnostics.
 */
object AiStudioWebSessionR17ProductionBootstrap {
    const val VERSION = "2026-09-04-web-session-r17.6-lean-live-bootstrap"
    const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
    const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"
    const val TARGET_MODEL = "function-specific"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.version)return;

  const VERSION='2026-09-04-web-session-r17.6-lean-live-bootstrap';
  const TRANSLATE_MODEL='gemini-3.5-live-translate-preview';
  const TRANSCRIBE_MODEL='gemini-3.5-transcribe-live';
  const state={
    configured:false,transcribeOnly:false,targetLanguage:'vi',targetModel:TRANSLATE_MODEL,echoTargetLanguage:false,
    instructionApplied:false,streamSelected:false,modelSeen:false,modelVerified:false,modelRouteRequested:false,
    languageUiSelected:false,languageAttempts:0,translationGuardRequests:0,translationConfigSeen:false,targetLanguageVerified:false,
    targetLanguageRewriteRequests:0,targetLanguageRewriteCount:0,lastLanguageStrategy:'none',setupObserved:false,
    carrierActive:false,syntheticCarrier:false,syntheticErrors:0,pageOutputMuted:false,authRequired:false,
    stage:'boot',lastBlocker:'not-configured',lastAction:'',lastActionAt:0,lastTickAt:0,
    deepElements:0,interactiveControls:0,shadowRoots:0,frameDocuments:0,discoveryScans:0,
    streamScans:0,streamCandidates:0,streamAttempts:0,modelScans:0,modelCandidates:0,modelAttempts:0,modelSearchAttempts:0,
    startScans:0,startCandidates:0,startAttempts:0,modelGuardInstalled:false,modelGuardRequests:0,
    modelRewriteRequests:0,modelRewriteCount:0,routeKind:'other'
  };
  let synthetic=null,carrierGain=null,carrierContext=null,carrierOscillator=null,lastDiscoverySignature='';
  const clickTimes=new WeakMap();

  function diag(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R17_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||260);}
  function tag(el){try{return String(el&&el.tagName||'').toUpperCase();}catch(_){return '';}}
  function attr(el,name){try{return el&&el.getAttribute?safeText(el.getAttribute(name)||'',180):'';}catch(_){return '';}}
  function role(el){return attr(el,'role').toLowerCase();}
  function label(el){
    try{return safeText([attr(el,'aria-label'),attr(el,'placeholder'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safeText(el&&el.title||'',100),safeText(el&&el.value||'',160),safeText(el&&el.textContent||'',260)].filter(Boolean).join(' '),420).toLowerCase();}catch(_){return '';}
  }
  function isInteractive(el){
    const t=tag(el),r=role(el);if(t==='BUTTON'||t==='A'||t==='INPUT'||t==='TEXTAREA'||t==='SELECT'||t==='OPTION')return true;
    return r==='button'||r==='tab'||r==='option'||r==='menuitem'||r==='combobox'||r==='link'||r==='listbox'||!!attr(el,'aria-haspopup');
  }
  function collectDeep(){
    state.discoveryScans++;const roots=[document],seen=new Set(),all=[],interactive=[];let shadow=0,frames=0;
    while(roots.length&&all.length<6000){
      const root=roots.shift();if(!root||seen.has(root))continue;seen.add(root);let nodes=[];
      try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&all.length<6000;i++){
        const el=nodes[i];all.push(el);if(isInteractive(el))interactive.push(el);
        try{if(el.shadowRoot){roots.push(el.shadowRoot);shadow++;}}catch(_){}
        try{const t=tag(el);if((t==='IFRAME'||t==='FRAME')&&el.contentDocument){roots.push(el.contentDocument);frames++;}}catch(_){}
      }
    }
    state.deepElements=all.length;state.interactiveControls=interactive.length;state.shadowRoots=shadow;state.frameDocuments=frames;
    return {all:all,interactive:interactive};
  }
  function detectRoute(){
    try{const p=String(location.pathname||'').toLowerCase();if(p.indexOf('live')>=0)return 'live';if(p.indexOf('stream')>=0)return 'stream';if(p.indexOf('realtime')>=0)return 'realtime';}catch(_){}
    return 'other';
  }
  function routeModel(){try{return String(new URL(location.href).searchParams.get('model')||'').replace(/^models\//,'').toLowerCase();}catch(_){return '';}}
  function routeHasTargetModel(){return routeModel()===String(state.targetModel||'').toLowerCase().replace(/^models\//,'');}
  function clickElement(el,reason){
    try{
      if(!el)return false;const now=Date.now(),previous=Number(clickTimes.get(el)||0);if(previous&&now-previous<1600)return false;clickTimes.set(el,now);
      if(typeof el.click==='function')el.click();else{const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;el.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,view:w}));}
      state.lastAction=reason;state.lastActionAt=now;diag('AUTO_ACTION',{reason:reason,tag:tag(el)||'none',role:role(el)||'none'});return true;
    }catch(e){diag('AUTO_ACTION_ERROR',{reason:reason,name:String(e&&e.name||'Error')});return false;}
  }
  function setupSeen(){try{const e=window.__AIS_LIVE_OUTPUT_ENGINE__;return !!(e&&typeof e.describe==='function'&&Number(e.describe().setupCompleteEvents||0)>0);}catch(_){return false;}}
  function startScore(el){
    const l=label(el),r=role(el),t=tag(el);if(!l||attr(el,'disabled')||attr(el,'aria-disabled')==='true'||l.indexOf('stop')>=0)return 0;
    if(!(t==='BUTTON'||t==='A'||r==='button'||r==='menuitem'||r==='tab'||r==='link'))return 0;
    let s=0;if(/\b(start|begin|connect|talk|speak|join)\b/.test(l))s+=9;if(l.indexOf('go live')>=0||l.indexOf('start session')>=0||l.indexOf('start live')>=0)s+=10;
    if(l.indexOf('microphone')>=0||l.indexOf(' mic')>=0)s+=5;if(l.indexOf('live')>=0||l.indexOf('stream')>=0)s+=2;return s;
  }
  function tryStart(snapshot){
    state.startScans++;if(setupSeen()){state.setupObserved=true;state.stage='setup-complete';state.lastBlocker='none';return;}
    if(!state.streamSelected){state.lastBlocker='waiting-stream';return;}if(!state.modelSeen&&!state.modelGuardInstalled){state.lastBlocker='waiting-model';return;}
    const scored=[];for(let i=0;i<snapshot.interactive.length;i++){const score=startScore(snapshot.interactive[i]);if(score>=7)scored.push({el:snapshot.interactive[i],score:score});}
    scored.sort(function(a,b){return b.score-a.score;});state.startCandidates=scored.length;
    if(scored.length&&state.startAttempts<8){state.startAttempts++;if(clickElement(scored[0].el,'start-live')){state.stage='start-clicked';state.lastBlocker='waiting-live-setup';buildSyntheticCarrier();return;}}
    state.lastBlocker='start-control-not-found';
  }
  function patchTranslationTree(node,depth){
    const d=depth||0;if(d>12||node==null||typeof node!=='object')return {changed:0,seen:0};let changed=0,seen=0;
    if(Array.isArray(node)){for(let i=0;i<node.length;i++){const r=patchTranslationTree(node[i],d+1);changed+=r.changed;seen+=r.seen;}return {changed:changed,seen:seen};}
    const model=typeof node.model==='string'?String(node.model).toLowerCase():'';
    if(model.indexOf(TRANSLATE_MODEL)>=0){
      const gk=Object.prototype.hasOwnProperty.call(node,'generation_config')?'generation_config':'generationConfig';let gc=node[gk];
      if(!gc||typeof gc!=='object'||Array.isArray(gc)){gc={};node[gk]=gc;changed++;}
      const tk=Object.prototype.hasOwnProperty.call(gc,'translation_config')?'translation_config':'translationConfig';let tc=gc[tk];
      if(!tc||typeof tc!=='object'||Array.isArray(tc)){tc={};gc[tk]=tc;changed++;}
      const lk=Object.prototype.hasOwnProperty.call(tc,'target_language_code')?'target_language_code':'targetLanguageCode';
      const ek=Object.prototype.hasOwnProperty.call(tc,'echo_target_language')?'echo_target_language':'echoTargetLanguage';seen++;
      if(String(tc[lk]||'')!==state.targetLanguage){tc[lk]=state.targetLanguage;changed++;}
      if(Boolean(tc[ek])!==Boolean(state.echoTargetLanguage)){tc[ek]=Boolean(state.echoTargetLanguage);changed++;}
    }
    const direct=[['translationConfig','targetLanguageCode','echoTargetLanguage'],['translation_config','target_language_code','echo_target_language']];
    for(let i=0;i<direct.length;i++){
      const cfg=node[direct[i][0]];if(cfg&&typeof cfg==='object'&&!Array.isArray(cfg)){seen++;if(String(cfg[direct[i][1]]||'')!==state.targetLanguage){cfg[direct[i][1]]=state.targetLanguage;changed++;}if(Boolean(cfg[direct[i][2]])!==Boolean(state.echoTargetLanguage)){cfg[direct[i][2]]=Boolean(state.echoTargetLanguage);changed++;}}
    }
    const keys=Object.keys(node);for(let i=0;i<keys.length;i++){const r=patchTranslationTree(node[keys[i]],d+1);changed+=r.changed;seen+=r.seen;}
    return {changed:changed,seen:seen};
  }
  function rewriteSetupBody(body){
    try{
      let params=null,asString=false;if(typeof body==='string'){params=new URLSearchParams(body);asString=true;}else if(body instanceof URLSearchParams){params=new URLSearchParams(body.toString());}else return body;
      let anyChanged=false,modelRewrites=0,translationChanges=0,translationSeen=0,touched=false;const updates=[];
      params.forEach(function(value,key){
        const keyText=String(key);if(keyText.indexOf('req')!==0||keyText.indexOf('___data__')<0)return;if(String(value).indexOf('audio/pcm')>=0)return;
        let next=String(value),changed=false;
        if(next.toLowerCase().indexOf(state.targetModel.toLowerCase())>=0){state.modelSeen=true;state.modelVerified=true;}
        else{
          const replaced=next.replace(/(models\/)?gemini-[a-z0-9._-]*(?:live|translate|transcribe)[a-z0-9._-]*/ig,function(match,prefix){modelRewrites++;return (prefix?'models/':'')+state.targetModel;});
          if(replaced!==next){next=replaced;changed=true;state.modelSeen=true;state.modelVerified=true;}
        }
        if(!state.transcribeOnly&&next.toLowerCase().indexOf(TRANSLATE_MODEL)>=0){
          touched=true;state.translationGuardRequests++;
          try{const parsed=JSON.parse(next);const result=patchTranslationTree(parsed,0);translationChanges+=result.changed;translationSeen+=result.seen;if(result.seen>0){state.translationConfigSeen=true;state.targetLanguageVerified=true;state.lastLanguageStrategy=result.changed>0?'named-config-rewrite':'named-config-already-correct';}if(result.changed>0){next=JSON.stringify(parsed);changed=true;}}catch(_){}
        }
        if(changed){updates.push([key,next]);anyChanged=true;}
      });
      for(let i=0;i<updates.length;i++)params.set(updates[i][0],updates[i][1]);
      if(touched)state.modelGuardRequests++;if(modelRewrites>0){state.modelRewriteRequests++;state.modelRewriteCount+=modelRewrites;}if(translationChanges>0){state.targetLanguageRewriteRequests++;state.targetLanguageRewriteCount+=translationChanges;}
      if(touched)diag('TRANSLATION_CONFIG_GUARD',{targetLanguageCode:state.targetLanguage,verified:state.targetLanguageVerified,configSeen:state.translationConfigSeen,rewriteCount:state.targetLanguageRewriteCount,strategy:state.lastLanguageStrategy});
      if(touched||modelRewrites>0)diag('MODEL_REQUEST_GUARD',{targetModel:state.targetModel,verified:state.modelVerified,rewriteCount:state.modelRewriteCount});
      return anyChanged?(asString?params.toString():params):body;
    }catch(_){return body;}
  }
  function installModelGuard(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype)return;const p=X.prototype;if(p.send&&p.send.__aisR176SetupGuard){state.modelGuardInstalled=true;return;}
      const currentOpen=p.open,currentSend=p.send;p.open=function(method,url){try{this.__aisR176Url=String(url||'');}catch(_){}return currentOpen.apply(this,arguments);};
      const wrapped=function(body){let next=body;try{if(String(this.__aisR176Url||'').indexOf('/v1/bidiGenerateContent')>=0)next=rewriteSetupBody(body);}catch(_){}return currentSend.call(this,next);};
      wrapped.__aisR176SetupGuard=true;p.send=wrapped;state.modelGuardInstalled=true;diag('HOOK',{target:'bidi-model-language-guard'});
    }catch(e){diag('HOOK_ERROR',{target:'bidi-model-language-guard',name:String(e&&e.name||'Error')});}
  }
  function buildSyntheticCarrier(){
    if(synthetic)return synthetic;
    try{
      const C=window.AudioContext||window.webkitAudioContext;if(!C)throw new Error('AudioContext unavailable');carrierContext=new C({sampleRate:16000});carrierOscillator=carrierContext.createOscillator();carrierGain=carrierContext.createGain();
      const dest=carrierContext.createMediaStreamDestination();carrierOscillator.type='sine';carrierOscillator.frequency.value=173;carrierGain.gain.value=0;carrierOscillator.connect(carrierGain);carrierGain.connect(dest);carrierOscillator.start();
      try{const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}synthetic=dest.stream;state.syntheticCarrier=true;diag('SYNTHETIC_CARRIER_READY',{tracks:synthetic.getAudioTracks().length,sampleRate:carrierContext.sampleRate||0});return synthetic;
    }catch(e){state.syntheticErrors++;diag('SYNTHETIC_CARRIER_ERROR',{count:state.syntheticErrors,name:String(e&&e.name||'Error')});return null;}
  }
  function setCarrierActive(enabled){
    state.carrierActive=!!enabled;try{if(!carrierGain)buildSyntheticCarrier();if(carrierGain)carrierGain.gain.setTargetAtTime(enabled?0.018:0,carrierContext.currentTime,0.01);if(carrierContext&&carrierContext.state==='suspended'){const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}}catch(_){}return describe();
  }
  function installSyntheticGum(){
    try{const md=navigator.mediaDevices;if(!md||typeof md.getUserMedia!=='function'||md.getUserMedia.__aisR176Synthetic)return;const native=md.getUserMedia.bind(md);const wrapped=function(constraints){try{const c=constraints||{};if(!!c.audio&&!c.video){const stream=buildSyntheticCarrier();if(stream)return Promise.resolve(stream);}}catch(_){}return native(constraints);};wrapped.__aisR176Synthetic=true;md.getUserMedia=wrapped;diag('HOOK',{target:'getUserMedia-synthetic'});}catch(e){diag('HOOK_ERROR',{target:'getUserMedia-synthetic',name:String(e&&e.name||'Error')});}
  }
  function installOutputMute(){
    try{const A=window.AudioNode&&window.AudioNode.prototype;if(!A||typeof A.connect!=='function'||A.connect.__aisR176Muted)return;const native=A.connect,muteByContext=new WeakMap();const wrapped=function(destination){try{const name=destination&&destination.constructor&&destination.constructor.name||'';if(name==='AudioDestinationNode'&&this.context&&typeof this.context.createGain==='function'){let g=muteByContext.get(this.context);if(!g){g=this.context.createGain();g.gain.value=0;native.call(g,destination);muteByContext.set(this.context,g);}state.pageOutputMuted=true;return native.call(this,g);}}catch(_){}return native.apply(this,arguments);};wrapped.__aisR176Muted=true;A.connect=wrapped;diag('HOOK',{target:'webaudio-output-mute'});}catch(e){diag('HOOK_ERROR',{target:'webaudio-output-mute',name:String(e&&e.name||'Error')});}
  }
  function detectAuth(){try{const h=String(location.hostname||'').toLowerCase();state.authRequired=h.indexOf('accounts.google.')>=0||h==='accounts.google.com';}catch(_){}
  function reportDiscovery(){
    const signature=[state.stage,state.lastBlocker,state.streamSelected,state.modelSeen,state.modelVerified,state.targetLanguageVerified,state.setupObserved,state.startCandidates,state.startAttempts,state.modelGuardRequests,state.modelRewriteRequests,state.routeKind].join('|');
    if(signature===lastDiscoverySignature)return;lastDiscoverySignature=signature;
    diag('DISCOVERY',{stage:state.stage,blocker:state.lastBlocker,deepElements:state.deepElements,interactiveControls:state.interactiveControls,shadowRoots:state.shadowRoots,frameDocuments:state.frameDocuments,startCandidates:state.startCandidates,startAttempts:state.startAttempts,modelSeen:state.modelSeen,modelVerified:state.modelVerified,targetLanguageVerified:state.targetLanguageVerified,translationGuardRequests:state.translationGuardRequests,modelGuardInstalled:state.modelGuardInstalled,modelGuardRequests:state.modelGuardRequests,modelRewriteRequests:state.modelRewriteRequests,routeKind:state.routeKind});
  }
  function tick(){
    state.lastTickAt=Date.now();detectAuth();if(state.authRequired){state.stage='auth';state.lastBlocker='auth-required';return;}if(window.top!==window)return;
    state.routeKind=detectRoute();state.streamScans++;state.streamSelected=state.routeKind==='live'||state.routeKind==='stream'||state.routeKind==='realtime';
    state.modelScans++;if(routeHasTargetModel()){state.modelSeen=true;state.modelRouteRequested=true;}if(!state.configured){state.stage='boot';state.lastBlocker='not-configured';return;}
    if(!state.streamSelected){state.stage='route';state.lastBlocker='waiting-live-route';reportDiscovery();return;}
    const snapshot=collectDeep();tryStart(snapshot);reportDiscovery();
  }
  function configure(targetLanguage,transcribeOnly,echoTargetLanguage){
    state.targetLanguage=safeText(targetLanguage||'vi',60)||'vi';state.transcribeOnly=!!transcribeOnly;state.echoTargetLanguage=echoTargetLanguage===true;state.targetModel=state.transcribeOnly?TRANSCRIBE_MODEL:TRANSLATE_MODEL;
    state.configured=true;state.stage='discover';state.lastBlocker='waiting-start';state.modelSeen=routeHasTargetModel();state.modelRouteRequested=state.modelSeen;state.modelVerified=false;state.targetLanguageVerified=state.transcribeOnly;
    diag('FUNCTION_MODEL',{transcribeOnly:state.transcribeOnly,targetModel:state.targetModel,targetLanguageCode:state.targetLanguage,echoTargetLanguage:state.echoTargetLanguage});tick();return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,targetModel:state.targetModel,configured:state.configured,transcribeOnly:state.transcribeOnly,targetLanguage:state.targetLanguage,echoTargetLanguage:state.echoTargetLanguage,
      instructionApplied:state.instructionApplied,streamSelected:state.streamSelected,modelSeen:state.modelSeen,modelVerified:state.modelVerified,modelRouteRequested:state.modelRouteRequested,modelSearchAttempts:state.modelSearchAttempts,
      languageUiSelected:state.languageUiSelected,languageAttempts:state.languageAttempts,translationGuardRequests:state.translationGuardRequests,translationConfigSeen:state.translationConfigSeen,targetLanguageVerified:state.targetLanguageVerified,
      targetLanguageRewriteRequests:state.targetLanguageRewriteRequests,targetLanguageRewriteCount:state.targetLanguageRewriteCount,lastLanguageStrategy:state.lastLanguageStrategy,setupObserved:state.setupObserved||setupSeen(),
      carrierActive:state.carrierActive,syntheticCarrier:state.syntheticCarrier,syntheticErrors:state.syntheticErrors,pageOutputMuted:state.pageOutputMuted,authRequired:state.authRequired,stage:state.stage,lastBlocker:state.lastBlocker,
      deepElements:state.deepElements,interactiveControls:state.interactiveControls,shadowRoots:state.shadowRoots,frameDocuments:state.frameDocuments,discoveryScans:state.discoveryScans,
      streamScans:state.streamScans,streamCandidates:state.streamCandidates,streamAttempts:state.streamAttempts,modelScans:state.modelScans,modelCandidates:state.modelCandidates,modelAttempts:state.modelAttempts,
      startScans:state.startScans,startCandidates:state.startCandidates,startAttempts:state.startAttempts,modelGuardInstalled:state.modelGuardInstalled,modelGuardRequests:state.modelGuardRequests,modelRewriteRequests:state.modelRewriteRequests,modelRewriteCount:state.modelRewriteCount,
      routeKind:state.routeKind,lastAction:state.lastAction,lastActionAgeMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,lastTickAgeMs:state.lastTickAt?Date.now()-state.lastTickAt:-1};
  }
  function resetAutomation(){state.startAttempts=0;state.startCandidates=0;state.setupObserved=false;state.lastAction='';state.stage='discover';state.lastBlocker='waiting-start';tick();return describe();}

  installModelGuard();installSyntheticGum();installOutputMute();
  window.__AIS_R17_PRODUCTION__={version:VERSION,configure:configure,setCarrierActive:setCarrierActive,describe:describe,resetAutomation:resetAutomation};
  setInterval(tick,700);setTimeout(tick,0);setTimeout(tick,900);setTimeout(tick,2200);
  diag('ENGINE_INSTALLED',{version:VERSION,top:window.top===window,modelGuardInstalled:state.modelGuardInstalled});
})();
    """.trimIndent()
}
