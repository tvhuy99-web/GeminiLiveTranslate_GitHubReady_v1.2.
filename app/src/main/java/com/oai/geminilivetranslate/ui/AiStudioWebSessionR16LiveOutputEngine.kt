package com.oai.geminilivetranslate.ui

/**
 * R16 page-local receive engine for AI Studio Live.
 *
 * R15 proved Android PCM can ride AI Studio's authenticated BrowserChannel/WebChannel carrier.
 * R16 decodes the reverse direction incrementally. Google BrowserChannel responses are framed as
 * decimal-length + newline + JSON payload chunks, so looking for JSON field names in raw
 * responseText is insufficient. This engine unwraps those chunks, recursively parses nested JSON
 * strings, forwards only recognized Live output (audio or explicitly named transcription/text
 * fields) through a dedicated non-logging Android bridge, and emits metadata-only diagnostics.
 *
 * Security: raw BrowserChannel payloads, Base64 audio, cookies, auth headers, access tokens and
 * session-resumption handles are never emitted through the diagnostic bridge. Resumption handles
 * remain page-local for this milestone.
 */
object AiStudioWebSessionR16LiveOutputEngine {
    const val VERSION = "2026-09-03-web-session-r16.0-browserchannel-output"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_LIVE_OUTPUT_ENGINE__&&window.__AIS_LIVE_OUTPUT_ENGINE__.version){return;}

  const VERSION='2026-09-03-web-session-r16.0-browserchannel-output';
  const MAX_BUFFER_CHARS=2200000;
  const MAX_NESTED_JSON_CHARS=1500000;
  const state={
    bidiResponses:0,browserChunks:0,parsedChunks:0,parseErrors:0,prefixSkips:0,
    audioChunks:0,audioPayloadChars:0,textEvents:0,inputTranscriptEvents:0,
    interimTranscriptEvents:0,outputTranscriptEvents:0,modelTextEvents:0,
    setupCompleteEvents:0,turnCompleteEvents:0,interruptedEvents:0,
    sessionResumptionEvents:0,goAwayEvents:0,bridgeErrors:0,lastMime:'',lastChunkAt:0,
    lastAudioAt:0,lastTextAt:0,lastShape:''
  };

  function diag(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R16_'+kind,payload:payload||{}}));
    }catch(_){}
  }
  function output(){try{return window.AIStudioWebLiveOutput||null;}catch(_){return null;}}
  function safeUrl(raw){
    try{const u=new URL(String(raw||''),location.href);return {host:String(u.host||''),path:String(u.pathname||'').slice(0,500)};}
    catch(_){return {host:'',path:''};}
  }
  function isBidi(raw){const u=safeUrl(raw);return /webchannel/i.test(u.host)&&/\/v1\/bidiGenerateContent/i.test(u.path);}
  function validBase64(v){
    const s=String(v||'');
    return s.length>=4&&s.length<=300000&&s.length%4===0&&/^[A-Za-z0-9+/]+={0,2}$/.test(s);
  }
  function isAudioMime(v){return typeof v==='string'&&/^audio\/pcm(?:;|$)/i.test(v);}
  function bridgeAudio(mime,data,path){
    if(!validBase64(data))return false;
    try{
      const b=output();if(!b||typeof b.onAudioChunk!=='function')return false;
      b.onAudioChunk(String(mime||'audio/pcm').slice(0,120),String(data));
      state.audioChunks++;state.audioPayloadChars+=String(data).length;state.lastMime=String(mime||'audio/pcm').slice(0,120);state.lastAudioAt=Date.now();
      if(state.audioChunks===1||state.audioChunks%25===0)diag('AUDIO_OUT',{ordinal:state.audioChunks,mime:state.lastMime,payloadChars:String(data).length,pathDepth:(path||[]).length,totalPayloadChars:state.audioPayloadChars});
      return true;
    }catch(e){state.bridgeErrors++;diag('OUTPUT_BRIDGE_ERROR',{kind:'audio',name:String(e&&e.name||'Error'),count:state.bridgeErrors});return false;}
  }
  function bridgeText(kind,text){
    const s=String(text||'');if(!s)return;
    try{
      const b=output();if(!b||typeof b.onText!=='function')return;
      b.onText(String(kind||'text').slice(0,80),s);
      state.textEvents++;state.lastTextAt=Date.now();
      if(kind==='inputTranscription')state.inputTranscriptEvents++;
      else if(kind==='interimInputTranscription')state.interimTranscriptEvents++;
      else if(kind==='outputTranscription')state.outputTranscriptEvents++;
      else if(kind==='modelText')state.modelTextEvents++;
      diag('TEXT_OUT',{kind:String(kind||'text').slice(0,80),chars:s.length,total:state.textEvents});
    }catch(e){state.bridgeErrors++;diag('OUTPUT_BRIDGE_ERROR',{kind:'text',name:String(e&&e.name||'Error'),count:state.bridgeErrors});}
  }
  function bridgeSignal(kind,value){
    try{
      const b=output();if(b&&typeof b.onSignal==='function')b.onSignal(String(kind||'signal').slice(0,80),String(value||'').slice(0,240));
    }catch(e){state.bridgeErrors++;diag('OUTPUT_BRIDGE_ERROR',{kind:'signal',name:String(e&&e.name||'Error'),count:state.bridgeErrors});}
  }
  function shape(node,depth){
    const d=depth||0;if(d>4)return '...';
    try{
      if(node===null)return 'null';
      if(Array.isArray(node))return '['+node.slice(0,10).map(function(x){return shape(x,d+1);}).join(',')+(node.length>10?',...':'')+']#'+node.length;
      if(typeof node==='string')return isAudioMime(node)?'mime:audio/pcm':'str#'+node.length;
      if(typeof node==='number')return 'num';
      if(typeof node==='boolean')return 'bool';
      if(typeof node==='object'){
        const ks=Object.keys(node).sort().slice(0,20);return '{'+ks.map(function(k){return k+':'+shape(node[k],d+1);}).join(',')+'}';
      }
      return typeof node;
    }catch(_){return 'err';}
  }
  function textFrom(v){
    try{
      if(v&&typeof v==='object'&&!Array.isArray(v)&&typeof v.text==='string')return v.text;
      if(typeof v==='string')return v;
    }catch(_){}
    return '';
  }
  function handleKnownObject(obj){
    if(!obj||typeof obj!=='object'||Array.isArray(obj))return;
    try{
      if(Object.prototype.hasOwnProperty.call(obj,'setupComplete')||Object.prototype.hasOwnProperty.call(obj,'setup_complete')){
        state.setupCompleteEvents++;bridgeSignal('setupComplete','true');
      }
      const sc=obj.serverContent||obj.server_content;
      if(sc&&typeof sc==='object'&&!Array.isArray(sc)){
        if(sc.interrupted===true){state.interruptedEvents++;bridgeSignal('interrupted','true');}
        const interim=textFrom(sc.interimInputTranscription||sc.interim_input_transcription);if(interim)bridgeText('interimInputTranscription',interim);
        const input=textFrom(sc.inputTranscription||sc.input_transcription);if(input)bridgeText('inputTranscription',input);
        const outputText=textFrom(sc.outputTranscription||sc.output_transcription);if(outputText)bridgeText('outputTranscription',outputText);
        const mt=sc.modelTurn||sc.model_turn;
        const parts=mt&&Array.isArray(mt.parts)?mt.parts:[];
        for(let i=0;i<parts.length;i++){
          const part=parts[i];if(!part||typeof part!=='object'||Array.isArray(part))continue;
          if(!outputText&&typeof part.text==='string'&&part.text)bridgeText('modelText',part.text);
        }
        if(sc.turnComplete===true||sc.turn_complete===true||sc.generationComplete===true||sc.generation_complete===true){state.turnCompleteEvents++;bridgeSignal('turnComplete','true');}
      }
      const resume=obj.sessionResumptionUpdate||obj.session_resumption_update;
      if(resume&&typeof resume==='object'){
        state.sessionResumptionEvents++;
        // Never export newHandle. Only the non-secret resumable state crosses the bridge.
        bridgeSignal('sessionResumption',String(resume.resumable===true));
      }
      const ga=obj.goAway||obj.go_away;
      if(ga&&typeof ga==='object'){
        state.goAwayEvents++;
        const tl=ga.timeLeft||ga.time_left||'';bridgeSignal('goAway',typeof tl==='string'?tl:'present');
      }
    }catch(_){}
  }
  function scan(node,path,depth){
    const d=depth||0;if(d>14||node==null)return;
    try{
      if(Array.isArray(node)){
        for(let i=0;i+1<node.length;i++){
          if(isAudioMime(node[i])&&typeof node[i+1]==='string')bridgeAudio(node[i],node[i+1],(path||[]).concat([i+1]));
        }
        for(let i=0;i<node.length;i++)scan(node[i],(path||[]).concat([i]),d+1);
        return;
      }
      if(typeof node==='object'){
        handleKnownObject(node);
        const mime=node.mimeType||node.mime_type;
        if(isAudioMime(mime)){
          const data=typeof node.data==='string'?node.data:(typeof node.bytes==='string'?node.bytes:'');
          if(data)bridgeAudio(mime,data,(path||[]).concat(['data']));
        }
        const ks=Object.keys(node);for(let i=0;i<ks.length;i++)scan(node[ks[i]],(path||[]).concat([ks[i]]),d+1);
        return;
      }
      if(typeof node==='string'&&node.length>1&&node.length<=MAX_NESTED_JSON_CHARS){
        const c=node.charAt(0);if(c==='['||c==='{'){
          try{scan(JSON.parse(node),(path||[]).concat(['json']),d+1);}catch(_){}
        }
      }
    }catch(_){}
  }
  function processPayload(payload){
    try{
      const parsed=JSON.parse(payload);state.parsedChunks++;state.lastChunkAt=Date.now();
      const sig=shape(parsed,0).slice(0,3500);
      if(sig!==state.lastShape){state.lastShape=sig;diag('MESSAGE_SHAPE',{ordinal:state.parsedChunks,chars:payload.length,shape:sig});}
      scan(parsed,[],0);
    }catch(e){state.parseErrors++;if(state.parseErrors<=3||state.parseErrors%50===0)diag('CHUNK_PARSE_ERROR',{chars:String(payload||'').length,count:state.parseErrors,name:String(e&&e.name||'Error')});}
  }
  function consume(meta,text,finalState){
    let full=String(text||'');
    if(full.length<meta.readChars){meta.readChars=0;meta.buffer='';}
    if(full.length===meta.readChars)return;
    meta.buffer+=full.slice(meta.readChars);meta.readChars=full.length;
    if(meta.buffer.length>MAX_BUFFER_CHARS){
      state.parseErrors++;diag('BUFFER_TRIM',{chars:meta.buffer.length});meta.buffer=meta.buffer.slice(-MAX_BUFFER_CHARS);
    }
    let guard=0;
    while(meta.buffer&&guard++<200){
      meta.buffer=meta.buffer.replace(/^\r?\n/,'');
      const m=/^(\d+)\n/.exec(meta.buffer);
      if(m){
        const len=Number(m[1]);if(!Number.isFinite(len)||len<0||len>MAX_BUFFER_CHARS){state.parseErrors++;meta.buffer=meta.buffer.slice(m[0].length);continue;}
        const start=m[0].length;if(meta.buffer.length-start<len)break;
        const payload=meta.buffer.slice(start,start+len);meta.buffer=meta.buffer.slice(start+len);
        state.browserChunks++;processPayload(payload);
        if(state.browserChunks===1||state.browserChunks%50===0)diag('BC_CHUNK',{ordinal:state.browserChunks,chars:len,remainingChars:meta.buffer.length});
        continue;
      }
      const first=meta.buffer.charAt(0);
      if(first==='['||first==='{'){
        try{JSON.parse(meta.buffer);const payload=meta.buffer;meta.buffer='';state.browserChunks++;processPayload(payload);continue;}catch(_){}
      }
      const idx=meta.buffer.search(/\d+\n/);
      if(idx>0&&idx<160){state.prefixSkips++;if(state.prefixSkips<=3)diag('BC_PREFIX_SKIPPED',{chars:idx});meta.buffer=meta.buffer.slice(idx);continue;}
      if(finalState&&meta.buffer.length){
        try{JSON.parse(meta.buffer);const payload=meta.buffer;meta.buffer='';state.browserChunks++;processPayload(payload);}catch(_){state.parseErrors++;diag('BC_FINAL_UNPARSED',{chars:meta.buffer.length,count:state.parseErrors});meta.buffer='';}
      }
      break;
    }
  }
  function describe(){
    return {ok:true,version:VERSION,bidiResponses:state.bidiResponses,browserChunks:state.browserChunks,parsedChunks:state.parsedChunks,parseErrors:state.parseErrors,prefixSkips:state.prefixSkips,audioChunks:state.audioChunks,audioPayloadChars:state.audioPayloadChars,textEvents:state.textEvents,inputTranscriptEvents:state.inputTranscriptEvents,interimTranscriptEvents:state.interimTranscriptEvents,outputTranscriptEvents:state.outputTranscriptEvents,modelTextEvents:state.modelTextEvents,setupCompleteEvents:state.setupCompleteEvents,turnCompleteEvents:state.turnCompleteEvents,interruptedEvents:state.interruptedEvents,sessionResumptionEvents:state.sessionResumptionEvents,goAwayEvents:state.goAwayEvents,bridgeErrors:state.bridgeErrors,lastMime:state.lastMime,lastChunkAgeMs:state.lastChunkAt?Date.now()-state.lastChunkAt:-1,lastAudioAgeMs:state.lastAudioAt?Date.now()-state.lastAudioAt:-1,lastTextAgeMs:state.lastTextAt?Date.now()-state.lastTextAt:-1,lastShape:state.lastShape};
  }
  function reset(){Object.keys(state).forEach(function(k){if(typeof state[k]==='number')state[k]=0;else state[k]='';});diag('RESET',{version:VERSION});return describe();}

  try{
    const X=window.XMLHttpRequest;
    if(X&&X.prototype&&!X.prototype.__aisR16Wrapped){
      const nativeOpen=X.prototype.open;const nativeSend=X.prototype.send;
      X.prototype.open=function(method,url){this.__aisR16={raw:String(url||''),readChars:0,buffer:''};return nativeOpen.apply(this,arguments);};
      X.prototype.send=function(body){
        const xhr=this;const meta=xhr.__aisR16||{raw:'',readChars:0,buffer:''};
        if(isBidi(meta.raw)){
          state.bidiResponses++;
          try{xhr.addEventListener('readystatechange',function(){
            if(xhr.readyState!==3&&xhr.readyState!==4)return;
            let text='';try{text=typeof xhr.responseText==='string'?xhr.responseText:'';}catch(_){}
            consume(meta,text,xhr.readyState===4);
          });}catch(_){}
        }
        return nativeSend.apply(this,arguments);
      };
      X.prototype.__aisR16Wrapped=true;diag('HOOK',{target:'XMLHttpRequest-response'});
    }
  }catch(e){diag('HOOK_ERROR',{target:'XMLHttpRequest-response',name:String(e&&e.name||'Error')});}

  window.__AIS_LIVE_OUTPUT_ENGINE__={version:VERSION,describe:describe,reset:reset};
  diag('ENGINE_INSTALLED',{version:VERSION,host:safeUrl(location.href).host});
})();
    """.trimIndent()
}
