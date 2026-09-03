package com.oai.geminilivetranslate.ui

/**
 * R17 hidden production bootstrap for AI Studio Stream.
 *
 * Responsibilities:
 *  - choose/enter Stream mode and start the Live session without Android touch synthesis,
 *  - keep the target Live model selected when the current page exposes a model selector,
 *  - write the translation/transcription instruction only into a clearly labelled system-
 *    instruction control when one exists,
 *  - provide a page-local synthetic audio MediaStream so AI Studio has a carrier clock without
 *    competing for the physical Android microphone used by TranslationService,
 *  - mute page WebAudio at AudioDestinationNode so Android StreamingPcmPlayer is the sole output.
 *
 * It never reads or exports cookies, local/session storage, auth headers, tokens, request bodies,
 * media payloads or session handles. All diagnostics are metadata-only.
 */
object AiStudioWebSessionR17ProductionBootstrap {
    const val VERSION = "2026-09-03-web-session-r17.0-hidden-production-bootstrap"
    const val TARGET_MODEL = "gemini-3.1-flash-live-preview"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.version){return;}
  const VERSION='2026-09-03-web-session-r17.0-hidden-production-bootstrap';
  const TARGET_MODEL='gemini-3.1-flash-live-preview';
  const state={
    configured:false,transcribeOnly:false,targetLanguage:'vi',instructionApplied:false,
    streamSelected:false,modelSeen:false,startAttempts:0,streamAttempts:0,modelAttempts:0,
    setupObserved:false,carrierActive:false,syntheticCarrier:false,syntheticErrors:0,
    pageOutputMuted:false,lastAction:'',lastActionAt:0,lastTickAt:0,authRequired:false
  };
  let synthetic=null;
  let carrierGain=null;
  let carrierContext=null;
  let carrierOscillator=null;
  const clicked=new WeakSet();

  function diag(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R17_'+kind,payload:payload||{}}));
    }catch(_){}
  }
  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||180);}
  function label(el){
    try{return safeText((el.getAttribute&&el.getAttribute('aria-label'))||el.title||el.innerText||el.textContent||'',220).toLowerCase();}
    catch(_){return '';}
  }
  function candidates(){
    try{return Array.from(document.querySelectorAll('button,[role="button"],[role="tab"],[role="option"],[role="menuitem"],textarea,input'))}
    catch(_){return [];}
  }
  function nativeSetValue(el,value){
    try{
      const proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
      const desc=Object.getOwnPropertyDescriptor(proto,'value');
      if(desc&&desc.set)desc.set.call(el,value);else el.value=value;
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
      return true;
    }catch(_){return false;}
  }
  function clickElement(el,reason){
    try{
      if(!el||clicked.has(el))return false;
      clicked.add(el);
      if(typeof el.click==='function')el.click();else el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));
      state.lastAction=reason;state.lastActionAt=Date.now();diag('AUTO_ACTION',{reason:reason,labelChars:label(el).length});return true;
    }catch(e){diag('AUTO_ACTION_ERROR',{reason:reason,name:String(e&&e.name||'Error')});return false;}
  }
  function instruction(){
    if(state.transcribeOnly)return 'Transcribe the incoming speech faithfully. Preserve the spoken language and wording. Do not answer or add commentary.';
    return 'Act as a simultaneous interpreter. Translate all incoming speech into '+state.targetLanguage+'. Preserve meaning, names, numbers and tone. Output only the translation and speak the translated result. Do not answer the speaker as an assistant.';
  }
  function tryInstruction(){
    if(state.instructionApplied||!state.configured)return;
    const list=candidates();
    for(let i=0;i<list.length;i++){
      const el=list[i];
      if(!(el instanceof HTMLTextAreaElement||el instanceof HTMLInputElement))continue;
      const l=label(el);
      if(!/(system instructions?|instructions?|system prompt)/i.test(l))continue;
      if(nativeSetValue(el,instruction())){state.instructionApplied=true;state.lastAction='instruction';state.lastActionAt=Date.now();diag('INSTRUCTION_APPLIED',{targetLanguage:state.targetLanguage,transcribeOnly:state.transcribeOnly,chars:instruction().length});return;}
    }
  }
  function tryStreamMode(){
    if(state.streamSelected)return;
    const list=candidates();
    for(let i=0;i<list.length;i++){
      const el=list[i];const l=label(el);
      if(l==='stream'||l==='stream realtime'||l==='realtime stream'||l==='live stream'||l==='streaming'){
        state.streamAttempts++;if(clickElement(el,'select-stream')){state.streamSelected=true;return;}
      }
    }
    const body=safeText(document.body&&document.body.innerText,5000).toLowerCase();
    if(body.indexOf('stream')>=0&&body.indexOf('gemini')>=0)state.streamSelected=true;
  }
  function tryModel(){
    const body=safeText(document.body&&document.body.innerText,12000).toLowerCase();
    if(body.indexOf(TARGET_MODEL)>=0||body.indexOf('gemini 3.1 flash live')>=0){state.modelSeen=true;return;}
    const list=candidates();
    for(let i=0;i<list.length;i++){
      const l=label(list[i]);
      if(l.indexOf(TARGET_MODEL)>=0||l.indexOf('gemini 3.1 flash live')>=0){state.modelAttempts++;if(clickElement(list[i],'select-target-model')){state.modelSeen=true;return;}}
    }
    if(state.streamSelected&&state.modelAttempts<3){
      for(let i=0;i<list.length;i++){
        const l=label(list[i]);
        if((l.indexOf('model')>=0||l.indexOf('gemini')>=0)&&l.length<180){state.modelAttempts++;clickElement(list[i],'open-model-selector');return;}
      }
    }
  }
  function setupSeen(){
    try{
      const e=window.__AIS_LIVE_OUTPUT_ENGINE__;
      if(e&&typeof e.describe==='function')return Number(e.describe().setupCompleteEvents||0)>0;
    }catch(_){}
    return false;
  }
  function tryStart(){
    if(setupSeen()){state.setupObserved=true;return;}
    if(!state.streamSelected)return;
    const list=candidates();
    for(let i=0;i<list.length;i++){
      const el=list[i];const l=label(el);
      if(/^(start|start session|start streaming|start stream|start live|connect|go live|talk)$/.test(l)||/^start .*session$/.test(l)||/^start .*stream/.test(l)){
        state.startAttempts++;if(clickElement(el,'start-live'))return;
      }
    }
  }
  function buildSyntheticCarrier(){
    if(synthetic)return synthetic;
    try{
      const C=window.AudioContext||window.webkitAudioContext;
      if(!C)throw new Error('AudioContext unavailable');
      carrierContext=new C({sampleRate:16000});
      carrierOscillator=carrierContext.createOscillator();
      carrierGain=carrierContext.createGain();
      const dest=carrierContext.createMediaStreamDestination();
      carrierOscillator.type='sine';carrierOscillator.frequency.value=173;
      carrierGain.gain.value=0.0;
      carrierOscillator.connect(carrierGain);carrierGain.connect(dest);carrierOscillator.start();
      try{const p=carrierContext.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}
      synthetic=dest.stream;state.syntheticCarrier=true;diag('SYNTHETIC_CARRIER_READY',{tracks:synthetic.getAudioTracks().length,sampleRate:carrierContext.sampleRate||0});
      return synthetic;
    }catch(e){state.syntheticErrors++;diag('SYNTHETIC_CARRIER_ERROR',{count:state.syntheticErrors,name:String(e&&e.name||'Error')});return null;}
  }
  function setCarrierActive(enabled){
    state.carrierActive=!!enabled;
    try{
      if(!carrierGain)buildSyntheticCarrier();
      if(carrierGain){const value=enabled?0.018:0.0;carrierGain.gain.setTargetAtTime(value,carrierContext.currentTime,0.01);}
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
        try{
          const c=constraints||{};const audio=!!c.audio;const video=!!c.video;
          if(audio&&!video){const stream=buildSyntheticCarrier();if(stream)return Promise.resolve(stream);}
        }catch(_){}
        return native(constraints);
      };
      wrapped.__aisR17Synthetic=true;md.getUserMedia=wrapped;diag('HOOK',{target:'getUserMedia-synthetic'});
    }catch(e){diag('HOOK_ERROR',{target:'getUserMedia-synthetic',name:String(e&&e.name||'Error')});}
  }
  function installOutputMute(){
    try{
      const A=window.AudioNode&&window.AudioNode.prototype;if(!A||typeof A.connect!=='function'||A.connect.__aisR17Muted)return;
      const native=A.connect;const muteByContext=new WeakMap();
      const wrapped=function(destination){
        try{
          const name=destination&&destination.constructor&&destination.constructor.name||'';
          if(name==='AudioDestinationNode'&&this.context&&typeof this.context.createGain==='function'){
            let g=muteByContext.get(this.context);if(!g){g=this.context.createGain();g.gain.value=0;native.call(g,destination);muteByContext.set(this.context,g);}
            state.pageOutputMuted=true;
            return native.call(this,g);
          }
        }catch(_){}
        return native.apply(this,arguments);
      };
      wrapped.__aisR17Muted=true;A.connect=wrapped;diag('HOOK',{target:'webaudio-output-mute'});
    }catch(e){diag('HOOK_ERROR',{target:'webaudio-output-mute',name:String(e&&e.name||'Error')});}
  }
  function detectAuth(){
    try{const h=String(location.hostname||'').toLowerCase();state.authRequired=h.indexOf('accounts.google.')>=0||h==='accounts.google.com';}catch(_){}
  }
  function tick(){
    state.lastTickAt=Date.now();detectAuth();if(state.authRequired)return;
    if(window.top===window){tryStreamMode();tryModel();tryInstruction();tryStart();}
  }
  function configure(targetLanguage,transcribeOnly){
    state.targetLanguage=safeText(targetLanguage||'vi',60)||'vi';state.transcribeOnly=!!transcribeOnly;state.configured=true;state.instructionApplied=false;tick();return describe();
  }
  function describe(){
    return {ok:true,version:VERSION,targetModel:TARGET_MODEL,configured:state.configured,transcribeOnly:state.transcribeOnly,targetLanguage:state.targetLanguage,instructionApplied:state.instructionApplied,streamSelected:state.streamSelected,modelSeen:state.modelSeen,startAttempts:state.startAttempts,streamAttempts:state.streamAttempts,modelAttempts:state.modelAttempts,setupObserved:state.setupObserved||setupSeen(),carrierActive:state.carrierActive,syntheticCarrier:state.syntheticCarrier,syntheticErrors:state.syntheticErrors,pageOutputMuted:state.pageOutputMuted,authRequired:state.authRequired,lastAction:state.lastAction,lastActionAgeMs:state.lastActionAt?Date.now()-state.lastActionAt:-1,lastTickAgeMs:state.lastTickAt?Date.now()-state.lastTickAt:-1};
  }
  function resetAutomation(){clicked.clear&&clicked.clear();state.streamSelected=false;state.modelSeen=false;state.startAttempts=0;state.streamAttempts=0;state.modelAttempts=0;state.setupObserved=false;state.instructionApplied=false;state.lastAction='';tick();return describe();}

  installSyntheticGum();installOutputMute();
  window.__AIS_R17_PRODUCTION__={version:VERSION,configure:configure,setCarrierActive:setCarrierActive,describe:describe,resetAutomation:resetAutomation};
  setInterval(tick,650);setTimeout(tick,0);setTimeout(tick,900);setTimeout(tick,2200);
  diag('ENGINE_INSTALLED',{version:VERSION,top:window.top===window});
})();
    """.trimIndent()
}
