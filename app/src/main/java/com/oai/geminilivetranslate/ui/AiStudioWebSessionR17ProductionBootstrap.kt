package com.oai.geminilivetranslate.ui

/**
 * R17.5 production bootstrap for AI Studio Live.
 *
 * R17.5 keeps the proven R17.4 route/model/start automation and adds a dedicated Live Translate
 * language guard. The app target language is applied to the visible target-language control when
 * available and is verified/repaired again in the actual bidiGenerateContent setup request.
 * Diagnostics expose only language codes/counters/state, never request bodies, media or auth data.
 */
object AiStudioWebSessionR17ProductionBootstrap {
    const val VERSION = "2026-09-03-web-session-r17.5-translation-config-guard"
    const val TRANSLATE_MODEL = "gemini-3.5-live-translate-preview"
    const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"
    const val TARGET_MODEL = "function-specific"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.version){return;}
  const VERSION='2026-09-03-web-session-r17.5-translation-config-guard';
  const TRANSLATE_MODEL='gemini-3.5-live-translate-preview';
  const TRANSCRIBE_MODEL='gemini-3.5-transcribe-live';
  const state={
    configured:false,transcribeOnly:false,targetLanguage:'vi',targetModel:TRANSLATE_MODEL,
    echoTargetLanguage:false,
    instructionApplied:false,streamSelected:false,modelSeen:false,modelVerified:false,
    modelRouteRequested:false,modelMenuOpenedAt:0,modelSearchAttempts:0,
    languageUiSelected:false,languageMenuOpenedAt:0,languageAttempts:0,
    translationGuardRequests:0,translationConfigSeen:false,targetLanguageVerified:false,
    targetLanguageRewriteRequests:0,targetLanguageRewriteCount:0,lastLanguageStrategy:'none',
    setupObserved:false,carrierActive:false,syntheticCarrier:false,syntheticErrors:0,
    pageOutputMuted:false,authRequired:false,stage:'boot',lastBlocker:'not-configured',
    lastAction:'',lastActionAt:0,lastTickAt:0,
    deepElements:0,interactiveControls:0,shadowRoots:0,frameDocuments:0,
    discoveryScans:0,streamScans:0,streamCandidates:0,streamAttempts:0,
    modelScans:0,modelCandidates:0,modelAttempts:0,
    startScans:0,startCandidates:0,startAttempts:0,
    modelGuardInstalled:false,modelGuardRequests:0,modelRewriteRequests:0,modelRewriteCount:0,
    routeKind:'other'
  };
  let synthetic=null,carrierGain=null,carrierContext=null,carrierOscillator=null;
  let lastDiscoverySignature='';
  const clickTimes=new WeakMap();

  function diag(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R17_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||260);}
  function attr(el,name){try{return el&&el.getAttribute?safeText(el.getAttribute(name)||'',180):'';}catch(_){return '';}}
  function label(el){
    try{return safeText([attr(el,'aria-label'),attr(el,'placeholder'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safeText(el.title||'',120),safeText(el.value||'',180),safeText(el.textContent||'',260)].filter(Boolean).join(' '),420).toLowerCase();}catch(_){return '';}
  }
  function role(el){return attr(el,'role').toLowerCase();}
  function tag(el){try{return String(el.tagName||'').toUpperCase();}catch(_){return '';}}
  function isInteractive(el){
    const t=tag(el),r=role(el);
    if(t==='BUTTON'||t==='A'||t==='INPUT'||t==='TEXTAREA'||t==='SELECT'||t==='OPTION')return true;
    if(r==='button'||r==='tab'||r==='option'||r==='menuitem'||r==='combobox'||r==='link'||r==='listbox')return true;
    if(attr(el,'aria-haspopup'))return true;
    try{return !!el.isContentEditable;}catch(_){return false;}
  }
  function collectDeep(){
    state.discoveryScans++;
    const roots=[document],seen=new Set(),all=[],interactive=[];
    let shadow=0,frames=0;
    while(roots.length&&all.length<7000){
      const root=roots.shift();if(!root||seen.has(root))continue;seen.add(root);
      let nodes=[];try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&all.length<7000;i++){
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
  function routeModel(){
    try{return String(new URL(location.href).searchParams.get('model')||'').replace(/^models\//,'').toLowerCase();}catch(_){return '';}
  }
  function routeHasTargetModel(){return routeModel()===String(state.targetModel||'').toLowerCase().replace(/^models\//,'');}
  function nativeSetValue(el,value){
    try{
      const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;
      const t=tag(el),proto=t==='TEXTAREA'?w.HTMLTextAreaElement&&w.HTMLTextAreaElement.prototype:w.HTMLInputElement&&w.HTMLInputElement.prototype;
      const desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');if(desc&&desc.set)desc.set.call(el,value);else el.value=value;
      el.dispatchEvent(new w.Event('input',{bubbles:true}));el.dispatchEvent(new w.Event('change',{bubbles:true}));return true;
    }catch(_){return false;}
  }
  function clickElement(el,reason){
    try{
      if(!el)return false;const now=Date.now(),previous=Number(clickTimes.get(el)||0);if(previous&&now-previous<1800)return false;clickTimes.set(el,now);
      if(typeof el.click==='function')el.click();else{const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;el.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,view:w}));}
      state.lastAction=reason;state.lastActionAt=now;diag('AUTO_ACTION',{reason:reason,role:role(el)||'none',tag:tag(el)||'none'});return true;
    }catch(e){diag('AUTO_ACTION_ERROR',{reason:reason,name:String(e&&e.name||'Error')});return false;}
  }
  function clickableAncestor(el){
    let current=el;
    for(let i=0;i<7&&current;i++){
      if(isInteractive(current))return current;
      try{current=current.assignedSlot||current.parentElement||null;}catch(_){current=null;}
    }
    return null;
  }
  function instruction(){
    if(state.transcribeOnly)return 'Transcribe the incoming speech faithfully. Preserve the spoken language and wording. Do not answer or add commentary.';
    return 'Act as a simultaneous interpreter. Translate all incoming speech into '+state.targetLanguage+'. Preserve meaning, names, numbers and tone. Output only the translation and speak the translated result. Do not answer the speaker as an assistant.';
  }
  function tryInstruction(snapshot){
    if(state.instructionApplied||!state.configured)return;
    for(let i=0;i<snapshot.interactive.length;i++){
      const el=snapshot.interactive[i],t=tag(el);if(t!=='TEXTAREA'&&t!=='INPUT')continue;
      if(!/(system instructions?|instructions?|system prompt)/i.test(label(el)))continue;
      if(nativeSetValue(el,instruction())){state.instructionApplied=true;state.lastAction='instruction';state.lastActionAt=Date.now();diag('INSTRUCTION_APPLIED',{targetLanguage:state.targetLanguage,transcribeOnly:state.transcribeOnly,targetModel:state.targetModel,chars:instruction().length});return;}
    }
  }
  function tryStreamMode(snapshot){
    state.streamScans++;state.routeKind=detectRoute();
    if(state.streamSelected)return;
    if(state.routeKind==='live'||state.routeKind==='stream'||state.routeKind==='realtime'){
      state.streamSelected=true;state.stage='model';state.lastBlocker='none';return;
    }
    const candidates=[];
    for(let i=0;i<snapshot.interactive.length;i++){
      const el=snapshot.interactive[i],l=label(el);if(l.indexOf('stream')>=0||l.indexOf('realtime')>=0||l.indexOf('live')>=0)candidates.push(el);
    }
    state.streamCandidates=candidates.length;
    if(candidates.length){state.streamAttempts++;if(clickElement(candidates[0],'select-stream')){state.stage='stream-clicked';state.lastBlocker='waiting-stream-navigation';return;}}
    state.lastBlocker='stream-control-not-found';
  }
  function modelAliases(){
    const raw=String(state.targetModel||'').toLowerCase().replace(/^models\//,''),human=raw.replace(/-/g,' '),compact=human.replace(/\bpreview\b/g,'').replace(/\s+/g,' ').trim();
    return [raw,human,compact].filter(function(v,i,a){return !!v&&a.indexOf(v)===i;});
  }
  function hasTargetModel(text){const value=String(text||'').toLowerCase(),aliases=modelAliases();for(let i=0;i<aliases.length;i++)if(value.indexOf(aliases[i])>=0)return true;return false;}
  function exactModelControls(snapshot){
    const found=[],seen=new Set();
    for(let i=0;i<snapshot.all.length;i++){
      const el=snapshot.all[i];if(!hasTargetModel(label(el)))continue;const click=clickableAncestor(el);if(click&&!seen.has(click)){seen.add(click);found.push(click);}
    }
    return found;
  }
  function modelSelectorOpener(snapshot){
    for(let i=0;i<snapshot.interactive.length;i++){
      const el=snapshot.interactive[i],l=label(el),r=role(el),popup=attr(el,'aria-haspopup').toLowerCase();
      if(r==='combobox'||popup==='listbox'||popup==='menu'||l.indexOf('model')>=0||l.indexOf('gemini')>=0)return el;
    }
    return null;
  }
  function modelSearchField(snapshot){
    for(let i=0;i<snapshot.interactive.length;i++){
      const el=snapshot.interactive[i],t=tag(el),l=label(el);if((t==='INPUT'||role(el)==='combobox')&&(l.indexOf('model')>=0||l.indexOf('search')>=0||l.indexOf('gemini')>=0))return el;
    }
    return null;
  }
  function tryModel(snapshot){
    state.modelScans++;if(!state.streamSelected)return;
    if(routeHasTargetModel()){
      if(!state.modelRouteRequested){state.modelRouteRequested=true;state.modelSeen=true;state.stage='start';state.lastBlocker='waiting-start';state.lastAction='model-from-live-route';state.lastActionAt=Date.now();diag('MODEL_ROUTE_REQUESTED',{targetModel:state.targetModel});}
      return;
    }
    const exact=exactModelControls(snapshot);state.modelCandidates=exact.length;
    if(exact.length&&!state.modelSeen){
      state.modelAttempts++;if(clickElement(exact[0],'select-target-model')){state.modelSeen=true;state.stage='model-selected';state.lastBlocker='waiting-start';diag('MODEL_SELECTED',{targetModel:state.targetModel,attempt:state.modelAttempts});return;}
    }
    const now=Date.now();
    if(state.modelMenuOpenedAt>0&&now-state.modelMenuOpenedAt<900){state.stage='model-menu-opened';state.lastBlocker='waiting-model-menu-render';return;}
    if(state.modelMenuOpenedAt>0&&state.modelSearchAttempts<2){
      const field=modelSearchField(snapshot);if(field&&nativeSetValue(field,state.targetModel)){state.modelSearchAttempts++;state.lastAction='filter-model-selector';state.lastActionAt=now;state.lastBlocker='waiting-model-filter-results';return;}
    }
    if(state.modelAttempts<10){
      const opener=modelSelectorOpener(snapshot);
      if(opener){state.modelAttempts++;if(clickElement(opener,'open-model-selector')){state.modelMenuOpenedAt=now;state.stage='model-menu-opened';state.lastBlocker='waiting-model-menu-render';return;}}
    }
    state.lastBlocker='model-control-not-found';
  }
  function languageNames(){
    const code=String(state.targetLanguage||'').trim();const out=[code.toLowerCase()];
    try{const d=new Intl.DisplayNames(['en'],{type:'language'}).of(code);if(d)out.push(String(d).toLowerCase());}catch(_){}
    try{const d=new Intl.DisplayNames([code],{type:'language'}).of(code);if(d)out.push(String(d).toLowerCase());}catch(_){}
    if(code.toLowerCase()==='vi'){out.push('vietnamese');out.push('tiếng việt');out.push('tieng viet');}
    return out.filter(function(v,i,a){return !!v&&a.indexOf(v)===i;});
  }
  function languageOption(snapshot){
    const names=languageNames();let best=null,bestScore=0;
    for(let i=0;i<snapshot.all.length;i++){
      const el=snapshot.all[i],l=label(el);if(!l)continue;let s=0;
      for(let n=0;n<names.length;n++){
        const name=names[n];if(name.length>2&&l===name)s+=12;else if(name.length>2&&l.indexOf(name)>=0)s+=7;
      }
      const r=role(el),t=tag(el);if(r==='option'||r==='menuitem'||t==='OPTION')s+=3;
      if(s>bestScore){const click=clickableAncestor(el)||el;best=click;bestScore=s;}
    }
    return bestScore>=8?best:null;
  }
  function languageOpener(snapshot){
    let best=null,bestScore=0;
    for(let i=0;i<snapshot.interactive.length;i++){
      const el=snapshot.interactive[i],l=label(el),r=role(el),popup=attr(el,'aria-haspopup').toLowerCase();let s=0;
      if(l.indexOf('target language')>=0||l.indexOf('translate to')>=0||l.indexOf('translation language')>=0)s+=12;
      if(l.indexOf('language')>=0)s+=5;
      if(l.indexOf('english')>=0)s+=4;
      if(r==='combobox'||popup==='listbox'||popup==='menu')s+=2;
      if(s>bestScore){best=el;bestScore=s;}
    }
    return bestScore>=8?best:null;
  }
  function tryTargetLanguage(snapshot){
    if(state.transcribeOnly||state.targetLanguageVerified||state.languageUiSelected)return false;
    const now=Date.now();
    if(state.languageMenuOpenedAt>0&&now-state.languageMenuOpenedAt<850){state.stage='language-menu-opened';state.lastBlocker='waiting-language-menu-render';return true;}
    const option=languageOption(snapshot);
    if(option&&clickElement(option,'select-target-language')){
      state.languageUiSelected=true;state.lastLanguageStrategy='ui-option';state.stage='start';state.lastBlocker='waiting-start';
      diag('TARGET_LANGUAGE_UI_SELECTED',{targetLanguageCode:state.targetLanguage,attempt:state.languageAttempts});return false;
    }
    if(state.languageAttempts<5){
      const opener=languageOpener(snapshot);
      if(opener&&clickElement(opener,'open-target-language-selector')){
        state.languageAttempts++;state.languageMenuOpenedAt=now;state.stage='language-menu-opened';state.lastBlocker='waiting-language-menu-render';return true;
      }
    }
    return false;
  }
  function setupSeen(){try{const e=window.__AIS_LIVE_OUTPUT_ENGINE__;return !!(e&&typeof e.describe==='function'&&Number(e.describe().setupCompleteEvents||0)>0);}catch(_){return false;}}
  function startScore(el){
    const l=label(el),r=role(el),t=tag(el);if(attr(el,'disabled')||attr(el,'aria-disabled')==='true'||l.indexOf('stop')>=0)return 0;
    if(!(t==='BUTTON'||t==='A'||r==='button'||r==='menuitem'||r==='tab'||r==='link'))return 0;
    let s=0;if(/(^|\s)(start|begin|connect|talk|speak|join)(\s|$)/.test(l))s+=9;if(l.indexOf('go live')>=0||l.indexOf('start session')>=0||l.indexOf('start live')>=0)s+=10;if(l.indexOf('microphone')>=0||l.indexOf('mic')>=0)s+=6;if(l.indexOf('live')>=0||l.indexOf('stream')>=0)s+=2;return s;
  }
  function tryStart(snapshot){
    state.startScans++;
    if(setupSeen()){state.setupObserved=true;state.stage='setup-complete';state.lastBlocker='none';return;}
    if(!state.streamSelected){state.lastBlocker='waiting-stream';return;}
    if(!state.modelSeen&&!state.modelGuardInstalled){state.lastBlocker='waiting-model';return;}
    const scored=[];
    for(let i=0;i<snapshot.interactive.length;i++){const s=startScore(snapshot.interactive[i]);if(s>=7)scored.push({el:snapshot.interactive[i],score:s});}
    scored.sort(function(a,b){return b.score-a.score;});state.startCandidates=scored.length;
    if(scored.length){state.startAttempts++;if(clickElement(scored[0].el,'start-live')){state.stage='start-clicked';state.lastBlocker='waiting-live-setup';buildSyntheticCarrier();return;}}
    state.lastBlocker='start-control-not-found';
  }
  function containsTranslateModel(node,depth){
    const d=depth||0;if(d>12||node==null)return false;
    if(typeof node==='string')return node.toLowerCase().indexOf(TRANSLATE_MODEL)>=0;
    if(Array.isArray(node)){for(let i=0;i<node.length;i++)if(containsTranslateModel(node[i],d+1))return true;return false;}
    if(typeof node==='object'){const ks=Object.keys(node);for(let i=0;i<ks.length;i++)if(containsTranslateModel(node[ks[i]],d+1))return true;}
    return false;
  }
  function applyNamedTranslationConfig(node,depth){
    const d=depth||0;if(d>12||node==null||typeof node!=='object')return {changed:0,seen:0};
    let changed=0,seen=0;
    if(!Array.isArray(node)){
      const model=typeof node.model==='string'?String(node.model).toLowerCase():'';
      const isSetup=model.indexOf(TRANSLATE_MODEL)>=0;
      if(isSetup){
        const gk=Object.prototype.hasOwnProperty.call(node,'generation_config')?'generation_config':'generationConfig';
        let gc=node[gk];if(!gc||typeof gc!=='object'||Array.isArray(gc)){gc={};node[gk]=gc;changed++;}
        const tk=Object.prototype.hasOwnProperty.call(gc,'translation_config')?'translation_config':'translationConfig';
        let tc=gc[tk];if(!tc||typeof tc!=='object'||Array.isArray(tc)){tc={};gc[tk]=tc;changed++;}
        seen++;
        const lk=Object.prototype.hasOwnProperty.call(tc,'target_language_code')?'target_language_code':'targetLanguageCode';
        const ek=Object.prototype.hasOwnProperty.call(tc,'echo_target_language')?'echo_target_language':'echoTargetLanguage';
        if(String(tc[lk]||'')!==state.targetLanguage){tc[lk]=state.targetLanguage;changed++;}
        if(Boolean(tc[ek])!==Boolean(state.echoTargetLanguage)){tc[ek]=Boolean(state.echoTargetLanguage);changed++;}
      }
      if(node.translationConfig&&typeof node.translationConfig==='object'&&!Array.isArray(node.translationConfig)){
        const tc=node.translationConfig;seen++;
        if(String(tc.targetLanguageCode||'')!==state.targetLanguage){tc.targetLanguageCode=state.targetLanguage;changed++;}
        if(Boolean(tc.echoTargetLanguage)!==Boolean(state.echoTargetLanguage)){tc.echoTargetLanguage=Boolean(state.echoTargetLanguage);changed++;}
      }
      if(node.translation_config&&typeof node.translation_config==='object'&&!Array.isArray(node.translation_config)){
        const tc=node.translation_config;seen++;
        if(String(tc.target_language_code||'')!==state.targetLanguage){tc.target_language_code=state.targetLanguage;changed++;}
        if(Boolean(tc.echo_target_language)!==Boolean(state.echoTargetLanguage)){tc.echo_target_language=Boolean(state.echoTargetLanguage);changed++;}
      }
      const ks=Object.keys(node);for(let i=0;i<ks.length;i++){const r=applyNamedTranslationConfig(node[ks[i]],d+1);changed+=r.changed;seen+=r.seen;}
    }else{
      for(let i=0;i<node.length;i++){const r=applyNamedTranslationConfig(node[i],d+1);changed+=r.changed;seen+=r.seen;}
    }
    return {changed:changed,seen:seen};
  }
  function replaceDefaultEnglishTokens(node,depth){
    const d=depth||0;if(d>12||node==null)return 0;let changed=0;
    if(Array.isArray(node)){
      for(let i=0;i<node.length;i++){
        if(typeof node[i]==='string'&&String(node[i]).toLowerCase()==='en'){node[i]=state.targetLanguage;changed++;}
        else changed+=replaceDefaultEnglishTokens(node[i],d+1);
      }
    }else if(typeof node==='object'){
      const ks=Object.keys(node);for(let i=0;i<ks.length;i++)changed+=replaceDefaultEnglishTokens(node[ks[i]],d+1);
    }
    return changed;
  }
  function rewriteSetupBody(body){
    try{
      let params=null,asString=false;if(typeof body==='string'){params=new URLSearchParams(body);asString=true;}else if(body instanceof URLSearchParams){params=new URLSearchParams(body.toString());}else return body;
      let changed=false,replacements=0,touched=false,correct=false;const updates=[];
      params.forEach(function(value,key){
        if(!/^req\d+___data__$/.test(String(key)))return;if(String(value).indexOf('audio/pcm')>=0)return;
        let nextValue=String(value),requestChanged=false;
        if(hasTargetModel(nextValue)){correct=true;}
        else{
          const next=nextValue.replace(/(models\/)?gemini-[a-z0-9._-]*(?:live|translate|transcribe)[a-z0-9._-]*/ig,function(match,prefix){replacements++;return (prefix?'models/':'')+state.targetModel;});
          if(next!==nextValue){nextValue=next;requestChanged=true;}
        }
        const translateSetup=!state.transcribeOnly&&nextValue.toLowerCase().indexOf(TRANSLATE_MODEL)>=0;
        if(translateSetup){
          touched=true;state.translationGuardRequests++;
          try{
            const parsed=JSON.parse(nextValue);
            if(containsTranslateModel(parsed,0)){
              const named=applyNamedTranslationConfig(parsed,0);let languageChanges=named.changed;let strategy=named.seen>0?'named-config':'none';
              if(named.seen>0){state.translationConfigSeen=true;state.targetLanguageVerified=true;}
              if(named.seen===0){
                const tokenChanges=replaceDefaultEnglishTokens(parsed,0);languageChanges+=tokenChanges;
                if(tokenChanges>0){strategy='default-en-token';state.translationConfigSeen=true;state.targetLanguageVerified=true;}
              }
              if(languageChanges>0){nextValue=JSON.stringify(parsed);requestChanged=true;state.targetLanguageRewriteCount+=languageChanges;state.lastLanguageStrategy=strategy;}
              else if(named.seen>0){state.lastLanguageStrategy='named-config-already-correct';}
            }
          }catch(_){}
          diag('TRANSLATION_CONFIG_GUARD',{targetLanguageCode:state.targetLanguage,echoTargetLanguage:state.echoTargetLanguage,verified:state.targetLanguageVerified,configSeen:state.translationConfigSeen,guardRequests:state.translationGuardRequests,rewriteRequests:state.targetLanguageRewriteRequests+(requestChanged?1:0),rewriteCount:state.targetLanguageRewriteCount,strategy:state.lastLanguageStrategy});
        }
        if(requestChanged){updates.push([key,nextValue]);changed=true;if(translateSetup)state.targetLanguageRewriteRequests++;}
      });
      if(touched){state.modelGuardRequests++;state.modelSeen=true;if(correct||changed)state.modelVerified=true;}
      for(let i=0;i<updates.length;i++)params.set(updates[i][0],updates[i][1]);
      if(touched)diag('MODEL_REQUEST_GUARD',{targetModel:state.targetModel,verified:state.modelVerified,guardRequests:state.modelGuardRequests,rewriteRequests:state.modelRewriteRequests+(replacements>0?1:0),rewriteCount:state.modelRewriteCount+replacements});
      if(replacements>0){state.modelRewriteRequests++;state.modelRewriteCount+=replacements;}
      return changed?(asString?params.toString():params):body;
    }catch(_){return body;}
  }
  function installModelGuard(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype)return;const p=X.prototype;if(p.send&&p.send.__aisR175SetupGuard){state.modelGuardInstalled=true;return;}
      const currentOpen=p.open,currentSend=p.send;p.open=function(method,url){try{this.__aisR175Url=String(url||'');}catch(_){}return currentOpen.apply(this,arguments);};
      const wrappedSend=function(body){let next=body;try{if(String(this.__aisR175Url||'').indexOf('/v1/bidiGenerateContent')>=0)next=rewriteSetupBody(body);}catch(_){}return currentSend.call(this,next);};
      wrappedSend.__aisR175SetupGuard=true;p.send=wrappedSend;state.modelGuardInstalled=true;diag('HOOK',{target:'bidi-model-translation-guard'});
    }catch(e){diag('HOOK_ERROR',{target:'bidi-model-translation-guard',name:String(e&&e.name||'Error')});}
  }
  function buildSyntheticCarrier(){
    if(synthetic)return synthetic;
    try{
      const C=window.AudioContext||window.webkitAudioContext;if(!C)throw new Error('AudioContext unavailable');carrierContext=new C({sampleRate:16000});carrierOscillator=carrierContext.createOscillator();carrierGain=carrierContext.createGain();
      const dest=carrierContext.createMediaStreamDestination();carrierOscillator.type='sine';carrierOscillator.frequency.value=173;carrierGain.gain.value=0.0;carrierOscillator.connect(carrierGain);carrierGain.connect(dest);carrierOscillator.start();
      try{const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}synthetic=dest.stream;state.syntheticCarrier=true;diag('SYNTHETIC_CARRIER_READY',{tracks:synthetic.getAudioTracks().length,sampleRate:carrierContext.sampleRate||0});return synthetic;
    }catch(e){state.syntheticErrors++;diag('SYNTHETIC_CARRIER_ERROR',{count:state.syntheticErrors,name:String(e&&e.name||'Error')});return null;}
  }
  function setCarrierActive(enabled){
    state.carrierActive=!!enabled;try{if(!carrierGain)buildSyntheticCarrier();if(carrierGain)carrierGain.gain.setTargetAtTime(enabled?0.018:0.0,carrierContext.currentTime,0.01);if(carrierContext&&carrierContext.state==='suspended'){const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}}catch(_){}return describe();
  }
  function installSyntheticGum(){
    try{const md=navigator.mediaDevices;if(!md||typeof md.getUserMedia!=='function'||md.getUserMedia.__aisR17Synthetic)return;const native=md.getUserMedia.bind(md);const wrapped=function(constraints){try{const c=constraints||{};if(!!c.audio&&!c.video){const stream=buildSyntheticCarrier();if(stream)return Promise.resolve(stream);}}catch(_){}return native(constraints);};wrapped.__aisR17Synthetic=true;md.getUserMedia=wrapped;diag('HOOK',{target:'getUserMedia-synthetic'});}catch(e){diag('HOOK_ERROR',{target:'getUserMedia-synthetic',name:String(e&&e.name||'Error')});}
  }
  function installOutputMute(){
    try{const A=window.AudioNode&&window.AudioNode.prototype;if(!A||typeof A.connect!=='function'||A.connect.__aisR17Muted)return;const native=A.connect,muteByContext=new WeakMap();const wrapped=function(destination){try{const name=destination&&destination.constructor&&destination.constructor.name||'';if(name==='AudioDestinationNode'&&this.context&&typeof this.context.createGain==='function'){let g=muteByContext.get(this.context);if(!g){g=this.context.createGain();g.gain.value=0;native.call(g,destination);muteByContext.set(this.context,g);}state.pageOutputMuted=true;return native.call(this,g);}}catch(_){}return native.apply(this,arguments);};wrapped.__aisR17Muted=true;A.connect=wrapped;diag('HOOK',{target:'webaudio-output-mute'});}catch(e){diag('HOOK_ERROR',{target:'webaudio-output-mute',name:String(e&&e.name||'Error')});}
  }
  function detectAuth(){try{const h=String(location.hostname||'').toLowerCase();state.authRequired=h.indexOf('accounts.google.')>=0||h==='accounts.google.com';}catch(_){}
  function reportDiscovery(){
    const signature=[state.stage,state.lastBlocker,state.streamSelected,state.modelSeen,state.modelVerified,state.modelRouteRequested,state.languageUiSelected,state.targetLanguageVerified,state.translationGuardRequests,state.targetLanguageRewriteRequests,state.streamCandidates,state.modelCandidates,state.startCandidates,state.streamAttempts,state.modelAttempts,state.modelSearchAttempts,state.startAttempts,state.modelGuardRequests,state.modelRewriteRequests,state.routeKind].join('|');
    if(signature===lastDiscoverySignature)return;lastDiscoverySignature=signature;diag('DISCOVERY',{stage:state.stage,blocker:state.lastBlocker,deepElements:state.deepElements,interactiveControls:state.interactiveControls,shadowRoots:state.shadowRoots,frameDocuments:state.frameDocuments,streamCandidates:state.streamCandidates,modelCandidates:state.modelCandidates,startCandidates:state.startCandidates,streamAttempts:state.streamAttempts,modelAttempts:state.modelAttempts,modelSearchAttempts:state.modelSearchAttempts,startAttempts:state.startAttempts,modelSeen:state.modelSeen,modelVerified:state.modelVerified,modelRouteRequested:state.modelRouteRequested,languageUiSelected:state.languageUiSelected,targetLanguageVerified:state.targetLanguageVerified,translationGuardRequests:state.translationGuardRequests,targetLanguageRewriteRequests:state.targetLanguageRewriteRequests,lastLanguageStrategy:state.lastLanguageStrategy,modelGuardInstalled:state.modelGuardInstalled,modelGuardRequests:state.modelGuardRequests,modelRewriteRequests:state.modelRewriteRequests,routeKind:state.routeKind});
  }
  function tick(){
    state.lastTickAt=Date.now();detectAuth();if(state.authRequired){state.stage='auth';state.lastBlocker='auth-required';return;}if(window.top!==window)return;if(!state.configured){state.stage='boot';state.lastBlocker='not-configured';return;}
    const snapshot=collectDeep();tryStreamMode(snapshot);tryModel(snapshot);const languageBusy=tryTargetLanguage(snapshot);tryInstruction(snapshot);if(!languageBusy)tryStart(snapshot);reportDiscovery();
  }
  function configure(targetLanguage,transcribeOnly,echoTargetLanguage){
    state.targetLanguage=safeText(targetLanguage||'vi',60)||'vi';state.transcribeOnly=!!transcribeOnly;state.echoTargetLanguage=echoTargetLanguage===true;state.targetModel=state.transcribeOnly?TRANSCRIBE_MODEL:TRANSLATE_MODEL;
    state.configured=true;state.instructionApplied=false;state.streamSelected=false;state.modelSeen=false;state.modelVerified=false;state.modelRouteRequested=false;state.modelMenuOpenedAt=0;state.modelSearchAttempts=0;state.languageUiSelected=false;state.languageMenuOpenedAt=0;state.languageAttempts=0;state.translationGuardRequests=0;state.translationConfigSeen=false;state.targetLanguageVerified=false;state.targetLanguageRewriteRequests=0;state.targetLanguageRewriteCount=0;state.lastLanguageStrategy='none';state.setupObserved=false;state.stage='discover';state.lastBlocker='waiting-route';
    diag('FUNCTION_MODEL',{transcribeOnly:state.transcribeOnly,targetModel:state.targetModel,targetLanguageCode:state.targetLanguage,echoTargetLanguage:state.echoTargetLanguage});tick();return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,targetModel:state.targetModel,configured:state.configured,transcribeOnly:state.transcribeOnly,targetLanguage:state.targetLanguage,echoTargetLanguage:state.echoTargetLanguage,instructionApplied:state.instructionApplied,streamSelected:state.streamSelected,modelSeen:state.modelSeen,modelVerified:state.modelVerified,modelRouteRequested:state.modelRouteRequested,modelSearchAttempts:state.modelSearchAttempts,languageUiSelected:state.languageUiSelected,languageAttempts:state.languageAttempts,translationGuardRequests:state.translationGuardRequests,translationConfigSeen:state.translationConfigSeen,targetLanguageVerified:state.targetLanguageVerified,targetLanguageRewriteRequests:state.targetLanguageRewriteRequests,targetLanguageRewriteCount:state.targetLanguageRewriteCount,lastLanguageStrategy:state.lastLanguageStrategy,setupObserved:state.setupObserved||setupSeen(),carrierActive:state.carrierActive,syntheticCarrier:state.syntheticCarrier,syntheticErrors:state.syntheticErrors,pageOutputMuted:state.pageOutputMuted,authRequired:state.authRequired,stage:state.stage,lastBlocker:state.lastBlocker,deepElements:state.deepElements,interactiveControls:state.interactiveControls,shadowRoots:state.shadowRoots,frameDocuments:state.frameDocuments,discoveryScans:state.discoveryScans,streamScans:state.streamScans,streamCandidates:state.streamCandidates,streamAttempts:state.streamAttempts,modelScans:state.modelScans,modelCandidates:state.modelCandidates,modelAttempts:state.modelAttempts,startScans:state.startScans,startCandidates:state.startCandidates,startAttempts:state.startAttempts,modelGuardInstalled:state.modelGuardInstalled,modelGuardRequests:state.modelGuardRequests,modelRewriteRequests:state.modelRewriteRequests,modelRewriteCount:state.modelRewriteCount,routeKind:state.routeKind,lastAction:state.lastAction,lastActionAgeMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,lastTickAgeMs:state.lastTickAt?Date.now()-state.lastTickAt:-1};
  }
  function resetAutomation(){
    state.streamSelected=false;state.modelSeen=false;state.modelVerified=false;state.modelRouteRequested=false;state.setupObserved=false;state.instructionApplied=false;state.modelMenuOpenedAt=0;state.modelSearchAttempts=0;state.languageUiSelected=false;state.languageMenuOpenedAt=0;state.languageAttempts=0;state.translationGuardRequests=0;state.translationConfigSeen=false;state.targetLanguageVerified=false;state.targetLanguageRewriteRequests=0;state.targetLanguageRewriteCount=0;state.lastLanguageStrategy='none';state.startAttempts=0;state.streamAttempts=0;state.modelAttempts=0;state.streamCandidates=0;state.modelCandidates=0;state.startCandidates=0;state.lastAction='';state.stage='discover';state.lastBlocker='waiting-route';tick();return describe();
  }

  installModelGuard();installSyntheticGum();installOutputMute();
  window.__AIS_R17_PRODUCTION__={version:VERSION,configure:configure,setCarrierActive:setCarrierActive,describe:describe,resetAutomation:resetAutomation};
  setInterval(tick,700);setTimeout(tick,0);setTimeout(tick,900);setTimeout(tick,2200);
  diag('ENGINE_INSTALLED',{version:VERSION,top:window.top===window,modelGuardInstalled:state.modelGuardInstalled});
})();
    """.trimIndent()
}
