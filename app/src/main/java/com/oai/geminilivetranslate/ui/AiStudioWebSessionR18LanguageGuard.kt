package com.oai.geminilivetranslate.ui

/**
 * R18.3A language experiment for AI Studio Live Translate.
 *
 * Goal: prove that the target language can be applied in the Live setup request itself, without
 * opening or selecting AI Studio's target-language UI. This script only inspects setup-shaped
 * bidiGenerateContent request parameters that contain the Live Translate model. Audio/PCM carrier
 * frames are skipped. Diagnostics expose only counters, strategy names, hashes and language codes.
 */
object AiStudioWebSessionR18LanguageGuard {
    const val VERSION = "2026-09-03-r18.3a-network-language-guard"
    const val TARGET_MODEL = "gemini-3.5-live-translate-preview"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R183_LANGUAGE__&&window.__AIS_R183_LANGUAGE__.version){return;}

  const VERSION='2026-09-03-r18.3a-network-language-guard';
  const TARGET_MODEL='gemini-3.5-live-translate-preview';
  const state={
    targetLanguage:'vi',enabled:true,guardInstalled:false,
    bidiRequests:0,setupRequests:0,translateSetupRequests:0,
    parseErrors:0,namedConfigSeen:0,namedConfigChanged:0,
    fallbackCandidateRequests:0,fallbackAppliedRequests:0,ambiguousFallbackRequests:0,
    rewriteRequests:0,rewriteCount:0,targetLanguageVerified:false,
    lastStrategy:'none',lastBeforeHash:'',lastAfterHash:'',lastBodyChars:0,lastFallbackCandidates:0
  };

  function bridge(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function'){
        b.onJsEvent(JSON.stringify({kind:'R183_'+kind,payload:payload||{}}));
      }
    }catch(_){}
  }
  function safeCode(value){
    const s=String(value||'vi').trim().slice(0,32);
    return /^[A-Za-z0-9-]+$/.test(s)?s:'vi';
  }
  function hashText(value){
    let h=2166136261;const s=String(value||'');
    for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}
    return (h>>>0).toString(16);
  }
  function containsTargetModel(node,depth){
    const d=depth||0;if(d>14||node==null)return false;
    if(typeof node==='string')return String(node).toLowerCase().indexOf(TARGET_MODEL)>=0;
    if(Array.isArray(node)){
      for(let i=0;i<node.length;i++)if(containsTargetModel(node[i],d+1))return true;
      return false;
    }
    if(typeof node==='object'){
      const keys=Object.keys(node);
      for(let i=0;i<keys.length;i++)if(containsTargetModel(node[keys[i]],d+1))return true;
    }
    return false;
  }
  function applyNamedConfig(node,depth){
    const d=depth||0;if(d>14||node==null||typeof node!=='object')return {seen:0,changed:0};
    let seen=0,changed=0;
    if(Array.isArray(node)){
      for(let i=0;i<node.length;i++){
        const r=applyNamedConfig(node[i],d+1);seen+=r.seen;changed+=r.changed;
      }
      return {seen:seen,changed:changed};
    }

    const model=typeof node.model==='string'?String(node.model).toLowerCase():'';
    if(model.indexOf(TARGET_MODEL)>=0){
      const generationKey=Object.prototype.hasOwnProperty.call(node,'generation_config')?'generation_config':'generationConfig';
      let generation=node[generationKey];
      if(!generation||typeof generation!=='object'||Array.isArray(generation)){
        generation={};node[generationKey]=generation;changed++;
      }
      const translationKey=Object.prototype.hasOwnProperty.call(generation,'translation_config')?'translation_config':'translationConfig';
      let translation=generation[translationKey];
      if(!translation||typeof translation!=='object'||Array.isArray(translation)){
        translation={};generation[translationKey]=translation;changed++;
      }
      const languageKey=Object.prototype.hasOwnProperty.call(translation,'target_language_code')?'target_language_code':'targetLanguageCode';
      const echoKey=Object.prototype.hasOwnProperty.call(translation,'echo_target_language')?'echo_target_language':'echoTargetLanguage';
      seen++;
      if(String(translation[languageKey]||'')!==state.targetLanguage){translation[languageKey]=state.targetLanguage;changed++;}
      if(Boolean(translation[echoKey])!==false){translation[echoKey]=false;changed++;}
    }

    if(node.translationConfig&&typeof node.translationConfig==='object'&&!Array.isArray(node.translationConfig)){
      const tc=node.translationConfig;seen++;
      if(String(tc.targetLanguageCode||'')!==state.targetLanguage){tc.targetLanguageCode=state.targetLanguage;changed++;}
      if(Boolean(tc.echoTargetLanguage)!==false){tc.echoTargetLanguage=false;changed++;}
    }
    if(node.translation_config&&typeof node.translation_config==='object'&&!Array.isArray(node.translation_config)){
      const tc=node.translation_config;seen++;
      if(String(tc.target_language_code||'')!==state.targetLanguage){tc.target_language_code=state.targetLanguage;changed++;}
      if(Boolean(tc.echo_target_language)!==false){tc.echo_target_language=false;changed++;}
    }

    const keys=Object.keys(node);
    for(let i=0;i<keys.length;i++){
      const r=applyNamedConfig(node[keys[i]],d+1);seen+=r.seen;changed+=r.changed;
    }
    return {seen:seen,changed:changed};
  }
  function countExactEnglish(node,depth){
    const d=depth||0;if(d>14||node==null)return 0;
    if(typeof node==='string')return String(node).toLowerCase()==='en'?1:0;
    let count=0;
    if(Array.isArray(node)){
      for(let i=0;i<node.length;i++)count+=countExactEnglish(node[i],d+1);
    }else if(typeof node==='object'){
      const keys=Object.keys(node);
      for(let i=0;i<keys.length;i++)count+=countExactEnglish(node[keys[i]],d+1);
    }
    return count;
  }
  function replaceExactEnglish(node,depth){
    const d=depth||0;if(d>14||node==null)return 0;let changed=0;
    if(Array.isArray(node)){
      for(let i=0;i<node.length;i++){
        if(typeof node[i]==='string'&&String(node[i]).toLowerCase()==='en'){
          node[i]=state.targetLanguage;changed++;
        }else changed+=replaceExactEnglish(node[i],d+1);
      }
    }else if(typeof node==='object'){
      const keys=Object.keys(node);
      for(let i=0;i<keys.length;i++)changed+=replaceExactEnglish(node[keys[i]],d+1);
    }
    return changed;
  }
  function rewriteSetupValue(value){
    const original=String(value||'');
    if(original.toLowerCase().indexOf(TARGET_MODEL)<0)return {value:original,changed:0,touched:false};
    state.translateSetupRequests++;
    state.lastBeforeHash=hashText(original);state.lastBodyChars=original.length;state.lastFallbackCandidates=0;
    try{
      const parsed=JSON.parse(original);
      if(!containsTargetModel(parsed,0))return {value:original,changed:0,touched:true};
      const named=applyNamedConfig(parsed,0);
      state.namedConfigSeen+=named.seen;state.namedConfigChanged+=named.changed;
      let changed=named.changed;
      let strategy=named.seen>0?(named.changed>0?'named-config':'named-config-already-correct'):'none';

      if(named.seen===0){
        const candidates=countExactEnglish(parsed,0);state.lastFallbackCandidates=candidates;
        if(candidates>0)state.fallbackCandidateRequests++;
        if(candidates>=1&&candidates<=4){
          const fallbackChanges=replaceExactEnglish(parsed,0);
          changed+=fallbackChanges;state.fallbackAppliedRequests++;
          strategy='bounded-en-token';
        }else if(candidates>4){
          state.ambiguousFallbackRequests++;strategy='ambiguous-en-token-not-rewritten';
        }else{
          strategy='no-language-field-found';
        }
      }

      const next=changed>0?JSON.stringify(parsed):original;
      state.lastAfterHash=hashText(next);state.lastStrategy=strategy;
      if(changed>0){
        state.rewriteRequests++;state.rewriteCount+=changed;state.targetLanguageVerified=true;
      }else if(named.seen>0){
        state.targetLanguageVerified=true;
      }
      bridge('LANGUAGE_SETUP',{
        targetLanguageCode:state.targetLanguage,
        strategy:strategy,
        namedSeen:named.seen,
        changed:changed,
        fallbackCandidates:state.lastFallbackCandidates,
        verified:state.targetLanguageVerified,
        beforeHash:state.lastBeforeHash,
        afterHash:state.lastAfterHash,
        bodyChars:state.lastBodyChars,
        translateSetupRequests:state.translateSetupRequests,
        rewriteRequests:state.rewriteRequests,
        rewriteCount:state.rewriteCount
      });
      return {value:next,changed:changed,touched:true};
    }catch(e){
      state.parseErrors++;state.lastStrategy='parse-error';
      bridge('LANGUAGE_SETUP_ERROR',{name:String(e&&e.name||'Error'),parseErrors:state.parseErrors,bodyChars:state.lastBodyChars});
      return {value:original,changed:0,touched:true};
    }
  }
  function rewriteBody(body){
    if(!state.enabled)return body;
    try{
      let params=null,asString=false;
      if(typeof body==='string'){params=new URLSearchParams(body);asString=true;}
      else if(body instanceof URLSearchParams){params=new URLSearchParams(body.toString());}
      else return body;

      let changed=false,touched=false;const updates=[];
      params.forEach(function(value,key){
        if(!/^req\d+___data__$/.test(String(key)))return;
        const text=String(value||'');if(/audio\/pcm/i.test(text))return;
        state.setupRequests++;
        const result=rewriteSetupValue(text);
        if(result.touched)touched=true;
        if(result.changed>0){updates.push([key,result.value]);changed=true;}
      });
      for(let i=0;i<updates.length;i++)params.set(updates[i][0],updates[i][1]);
      if(touched)bridge('REQUEST_SUMMARY',{
        targetLanguageCode:state.targetLanguage,
        setupRequests:state.setupRequests,
        translateSetupRequests:state.translateSetupRequests,
        rewriteRequests:state.rewriteRequests,
        rewriteCount:state.rewriteCount,
        verified:state.targetLanguageVerified,
        strategy:state.lastStrategy
      });
      return changed?(asString?params.toString():params):body;
    }catch(_){return body;}
  }
  function install(){
    try{
      const X=window.XMLHttpRequest;if(!X||!X.prototype)return false;const p=X.prototype;
      if(p.send&&p.send.__aisR183LanguageGuard){state.guardInstalled=true;return true;}
      const nativeOpen=p.open,currentSend=p.send;
      p.open=function(method,url){try{this.__aisR183Url=String(url||'');}catch(_){}return nativeOpen.apply(this,arguments);};
      const wrappedSend=function(body){
        let next=body;
        try{
          if(String(this.__aisR183Url||'').indexOf('/v1/bidiGenerateContent')>=0){
            state.bidiRequests++;next=rewriteBody(body);
          }
        }catch(_){}
        return currentSend.call(this,next);
      };
      wrappedSend.__aisR183LanguageGuard=true;p.send=wrappedSend;state.guardInstalled=true;
      bridge('HOOK',{target:'bidi-translate-language-setup'});return true;
    }catch(e){bridge('HOOK_ERROR',{name:String(e&&e.name||'Error')});return false;}
  }
  function resetCounters(){
    state.bidiRequests=0;state.setupRequests=0;state.translateSetupRequests=0;state.parseErrors=0;
    state.namedConfigSeen=0;state.namedConfigChanged=0;state.fallbackCandidateRequests=0;
    state.fallbackAppliedRequests=0;state.ambiguousFallbackRequests=0;state.rewriteRequests=0;
    state.rewriteCount=0;state.targetLanguageVerified=false;state.lastStrategy='none';
    state.lastBeforeHash='';state.lastAfterHash='';state.lastBodyChars=0;state.lastFallbackCandidates=0;
  }
  function configure(code){
    state.targetLanguage=safeCode(code);state.enabled=true;resetCounters();
    bridge('CONFIGURE',{targetLanguageCode:state.targetLanguage});return describe();
  }
  function reset(){resetCounters();return describe();}
  function describe(){
    return {
      ok:true,version:VERSION,targetModel:TARGET_MODEL,targetLanguage:state.targetLanguage,
      enabled:state.enabled,guardInstalled:state.guardInstalled,bidiRequests:state.bidiRequests,
      setupRequests:state.setupRequests,translateSetupRequests:state.translateSetupRequests,
      parseErrors:state.parseErrors,namedConfigSeen:state.namedConfigSeen,
      namedConfigChanged:state.namedConfigChanged,fallbackCandidateRequests:state.fallbackCandidateRequests,
      fallbackAppliedRequests:state.fallbackAppliedRequests,ambiguousFallbackRequests:state.ambiguousFallbackRequests,
      rewriteRequests:state.rewriteRequests,rewriteCount:state.rewriteCount,
      targetLanguageVerified:state.targetLanguageVerified,lastStrategy:state.lastStrategy,
      lastBeforeHash:state.lastBeforeHash,lastAfterHash:state.lastAfterHash,
      lastBodyChars:state.lastBodyChars,lastFallbackCandidates:state.lastFallbackCandidates
    };
  }

  window.__AIS_R183_LANGUAGE__={version:VERSION,configure:configure,reset:reset,describe:describe};
  install();
  bridge('ENGINE_INSTALLED',{version:VERSION,targetLanguageCode:state.targetLanguage,targetModel:TARGET_MODEL});
})();
""".trimIndent()
}
