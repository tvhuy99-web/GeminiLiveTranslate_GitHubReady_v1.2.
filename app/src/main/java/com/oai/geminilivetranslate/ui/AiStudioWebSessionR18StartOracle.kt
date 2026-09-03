package com.oai.geminilivetranslate.ui

/**
 * R18.4 LAB-ONLY Start oracle.
 *
 * LAB_ONLY_UI_ORACLE: this intentionally reproduces the one R17.4 Start path that succeeded on the
 * user's device so the adjacent R18.4 probe can learn the component/runtime path underneath it.
 * This file must never be wired into production bootstrap. Production remains zero-UI.
 */
object AiStudioWebSessionR18StartOracle {
    const val VERSION = "2026-09-03-r18.4-r174-start-oracle-lab"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R184_START_ORACLE__&&window.__AIS_R184_START_ORACLE__.version)return;
  const VERSION='2026-09-03-r18.4-r174-start-oracle-lab';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const LAB_ONLY_UI_ORACLE=true;
  const state={active:false,stage:'idle',targetLanguage:'vi',scans:0,candidates:0,attempts:0,maxAttempts:3,
    syntheticCarrier:false,syntheticErrors:0,lastScore:0,lastActionAt:0,setupAt:0,lastError:''};
  let timer=0,synthetic=null,carrierContext=null,carrierOscillator=null,carrierGain=null;

  function bridge(kind,payload){try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R184S_'+kind,payload:payload||{}}));}catch(_){} }
  function safeCode(v){const s=String(v||'vi').trim().slice(0,32);return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';}
  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||420);}
  function attr(el,name){try{return el&&el.getAttribute?safeText(el.getAttribute(name)||'',180):'';}catch(_){return '';}}
  function role(el){return attr(el,'role').toLowerCase();}
  function tag(el){try{return String(el&&el.tagName||'').toUpperCase();}catch(_){return '';}}
  function label(el){try{return safeText([attr(el,'aria-label'),attr(el,'placeholder'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safeText(el&&el.title||'',120),safeText(el&&el.value||'',180),safeText(el&&el.textContent||'',260)].filter(Boolean).join(' '),420).toLowerCase();}catch(_){return '';}}
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
      const wrapped=function(constraints){try{const c=constraints||{};if(!!c.audio&&!c.video){const stream=buildSyntheticCarrier();if(stream){bridge('SYNTHETIC_GUM',{audio:true});return Promise.resolve(stream);}}}catch(_){}return native(constraints);};
      wrapped.__aisR184Synthetic=true;md.getUserMedia=wrapped;bridge('HOOK',{target:'getUserMedia-synthetic-r174'});
    }catch(e){bridge('HOOK_ERROR',{target:'getUserMedia-synthetic-r174',name:String(e&&e.name||'Error')});}
  }
  function labOnlyClick(el,score){
    try{
      const p=window.__AIS_R184_ORACLE_PROBE__;if(p&&typeof p.markOracleTarget==='function')p.markOracleTarget(el,{score:score});
      state.attempts++;state.lastScore=score;state.lastActionAt=Date.now();state.stage='oracle-clicked';
      bridge('LAB_ONLY_START_ATTEMPT',{attempt:state.attempts,score:score,tag:tag(el),role:role(el),ariaChars:attr(el,'aria-label').length});
      // LAB_ONLY_UI_ORACLE: exact R17.4 discovery trigger. Never use this in production.
      if(typeof el.click==='function')el.click();
      else{const w=el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window;el.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,view:w}));}
      buildSyntheticCarrier();return true;
    }catch(e){state.lastError=String(e&&e.name||'Error');bridge('LAB_ONLY_START_ERROR',{name:state.lastError});return false;}
  }
  function scan(){
    if(!state.active)return;
    if(setupSeen()){state.stage='setup-complete';state.setupAt=Date.now();state.active=false;bridge('SUCCESS',{attempts:state.attempts,setupMs:state.lastActionAt?state.setupAt-state.lastActionAt:-1});return;}
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
    state.targetLanguage=safeCode(code);state.active=true;state.stage='arming-r174-oracle';state.scans=0;state.candidates=0;state.attempts=0;state.lastScore=0;state.lastActionAt=0;state.setupAt=0;state.lastError='';
    try{const g=window.__AIS_R183_LANGUAGE__;if(g&&typeof g.configure==='function')g.configure(state.targetLanguage);}catch(_){}
    buildSyntheticCarrier();bridge('START',{targetLanguageCode:state.targetLanguage,targetModel:TARGET_MODEL,labOnly:true});timer=setTimeout(scan,120);return describe();
  }
  function reset(){try{if(timer)clearTimeout(timer);}catch(_){}timer=0;state.active=false;state.stage='idle';state.scans=0;state.candidates=0;state.attempts=0;state.lastScore=0;state.lastActionAt=0;state.setupAt=0;state.lastError='';return describe();}
  function describe(){if(setupSeen()&&state.stage!=='setup-complete'){state.stage='setup-complete';state.active=false;}return {ok:true,version:VERSION,labOnly:true,targetModel:TARGET_MODEL,targetLanguage:state.targetLanguage,active:state.active,stage:state.stage,scans:state.scans,candidates:state.candidates,attempts:state.attempts,maxAttempts:state.maxAttempts,syntheticCarrier:state.syntheticCarrier,syntheticErrors:state.syntheticErrors,lastScore:state.lastScore,lastActionAgeMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,setupAgeMs:state.setupAt?Date.now()-state.setupAt:-1,lastError:state.lastError};}
  installSyntheticGum();
  window.__AIS_R184_START_ORACLE__={version:VERSION,start:start,reset:reset,describe:describe};
  bridge('ENGINE_INSTALLED',{version:VERSION,labOnly:true,targetModel:TARGET_MODEL});
})();
""".trimIndent()
}
