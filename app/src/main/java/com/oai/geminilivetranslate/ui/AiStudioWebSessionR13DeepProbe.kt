package com.oai.geminilivetranslate.ui

/**
 * R13.2 deep probe for AI Studio Live.
 *
 * R13.1 proved that AI Studio Live uses Google WebChannel over XHR at /v1/bidiGenerateContent.
 * This document-start probe moves one layer upward without controlling the session yet. It records:
 *  - WebChannel envelope field names/value lengths and caller stack fingerprints,
 *  - streaming XHR response growth/structural flags,
 *  - AudioContext/AudioWorklet/MediaRecorder/MediaStreamTrackProcessor topology.
 *
 * Security rule: credential values, query strings, request/response bodies, audio/video bytes and
 * WebChannel payload values never cross the JS bridge. Only structural metadata is emitted.
 */
object AiStudioWebSessionR13DeepProbe {
    const val VERSION = "2026-09-02-web-session-r13.2-deep-webchannel-audio-probe"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_LIVE_DEEP_PROBE__&&window.__AIS_LIVE_DEEP_PROBE__.version){return;}

  const VERSION='2026-09-02-web-session-r13.2-deep-webchannel-audio-probe';
  const MAX_EVENTS=420;
  const events=[];
  const counters={
    bidiOpen:0,bidiSend:0,bidiReady3:0,bidiReady4:0,bidiAbort:0,
    audioContext:0,audioNode:0,audioWorkletNode:0,audioPortOut:0,audioPortIn:0,
    mediaRecorder:0,mediaRecorderChunk:0,trackProcessor:0
  };
  let seq=0;
  let markSeq=0;

  function bridge(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:kind,payload:payload||{}}));
    }catch(_){}
  }
  function push(kind,payload,emit){
    try{
      const e={id:++seq,at:Date.now(),kind:kind,payload:payload||{}};
      events.push(e);if(events.length>MAX_EVENTS)events.splice(0,events.length-MAX_EVENTS);
      if(emit!==false)bridge('R132_'+kind,e.payload);
      return e;
    }catch(_){return null;}
  }
  function safeUrl(raw){
    try{
      const u=new URL(String(raw||''),location.href);
      return {scheme:String(u.protocol||'').replace(':',''),host:String(u.host||''),path:String(u.pathname||'').slice(0,500)};
    }catch(_){return {scheme:'',host:'',path:''};}
  }
  function isBidi(raw){
    const u=safeUrl(raw);
    return /webchannel/i.test(u.host)&&/\/v1\/bidiGenerateContent/i.test(u.path);
  }
  function keysOf(obj,limit){try{return Object.keys(obj||{}).slice(0,limit||40).sort();}catch(_){return [];}}
  function typeOf(v){
    if(v===null)return 'null';
    if(Array.isArray(v))return 'array';
    try{return v&&v.constructor&&v.constructor.name||typeof v;}catch(_){return typeof v;}
  }
  function byteLength(v){
    try{
      if(v instanceof ArrayBuffer)return v.byteLength;
      if(ArrayBuffer.isView(v))return v.byteLength||0;
      if(typeof Blob!=='undefined'&&v instanceof Blob)return v.size||0;
    }catch(_){}
    return 0;
  }
  function modelsFromText(text){
    try{
      const out=[];const ms=String(text||'').match(/(?:models\/)?gemini-[a-z0-9._-]+/ig)||[];
      ms.forEach(function(m){const n=String(m).replace(/^models\//i,'');if(out.indexOf(n)<0&&out.length<8)out.push(n.slice(0,160));});
      return out;
    }catch(_){return [];}
  }
  function mimeCandidates(text){
    try{
      const out=[];const ms=String(text||'').match(/(?:audio|video|image)\/[a-z0-9.+_-]+/ig)||[];
      ms.forEach(function(m){const n=String(m).toLowerCase();if(out.indexOf(n)<0&&out.length<12)out.push(n.slice(0,100));});
      return out;
    }catch(_){return [];}
  }
  function stackMeta(){
    try{
      const raw=String(new Error().stack||'');
      const lines=raw.split('\n').slice(2,14).map(function(line){
        return String(line||'')
          .replace(/https?:\/\/([^\s?#)]+)(?:\?[^\s#)]*)?(?:#[^\s)]*)?/g,function(_,p){return 'https://'+p;})
          .replace(/\s+/g,' ').trim().slice(0,260);
      }).filter(Boolean);
      let h=2166136261;
      const s=lines.join('|');
      for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}
      return {fingerprint:(h>>>0).toString(16),lines:lines};
    }catch(_){return {fingerprint:'',lines:[]};}
  }
  function valueShape(v,depth){
    const d=depth||0;
    const out={type:typeOf(v)};
    try{
      if(v==null)return out;
      if(typeof v==='string'){
        out.chars=v.length;
        out.models=modelsFromText(v);
        out.mimes=mimeCandidates(v);
        if(d<2&&v.length<180000){
          try{out.json=valueShape(JSON.parse(v),d+1);}catch(_){}
        }
        return out;
      }
      const bytes=byteLength(v);if(bytes){out.bytes=bytes;return out;}
      if(Array.isArray(v)){
        out.length=v.length;
        out.items=v.slice(0,8).map(function(x){return valueShape(x,d+1);});
        return out;
      }
      if(typeof v==='object'){
        out.keys=keysOf(v,50);
        if(d<2){
          const known={};
          ['setup','realtimeInput','realtime_input','clientContent','client_content','serverContent','server_content','sessionResumption','session_resumption','sessionResumptionUpdate','session_resumption_update','goAway','go_away','audio','video','mediaChunks','media_chunks','inlineData','inline_data','mimeType','mime_type','model'].forEach(function(k){
            if(Object.prototype.hasOwnProperty.call(v,k)){
              if(k==='mimeType'||k==='mime_type'||k==='model')known[k]={type:typeOf(v[k]),chars:typeof v[k]==='string'?v[k].length:0,models:k==='model'?modelsFromText(v[k]):[],mimes:k.indexOf('mime')===0?mimeCandidates(v[k]):[]};
              else known[k]=valueShape(v[k],d+1);
            }
          });
          if(Object.keys(known).length)out.known=known;
        }
      }
    }catch(_){}
    return out;
  }
  function webChannelEnvelopeMeta(body){
    const out={kind:typeOf(body),bytes:byteLength(body),textChars:0,paramCount:0,paramNames:[],params:[],models:[],mimes:[]};
    try{
      if(typeof body!=='string')return out;
      out.textChars=body.length;out.models=modelsFromText(body);out.mimes=mimeCandidates(body);
      if(body.indexOf('=')>=0){
        const sp=new URLSearchParams(body);
        const seen={};
        sp.forEach(function(v,k){
          const name=String(k||'').slice(0,160);seen[name]=(seen[name]||0)+1;
          if(out.params.length<32){
            const p={name:name,valueChars:String(v||'').length};
            if(/data|req|message|payload/i.test(name))p.shape=valueShape(v,0);
            out.params.push(p);
          }
        });
        out.paramNames=Object.keys(seen).slice(0,60).sort();
        out.paramCount=out.paramNames.reduce(function(n,k){return n+(seen[k]||0);},0);
        out.repeated=Object.keys(seen).filter(function(k){return seen[k]>1;}).slice(0,20).map(function(k){return {name:k,count:seen[k]};});
      }
    }catch(_){}
    return out;
  }
  function responseMeta(text,prevChars){
    const s=String(text||'');
    const out={chars:s.length,delta:Math.max(0,s.length-(prevChars||0)),models:modelsFromText(s),mimes:mimeCandidates(s)};
    try{
      out.flags={
        setupComplete:/setupComplete|setup_complete/.test(s),
        serverContent:/serverContent|server_content/.test(s),
        sessionResumption:/sessionResumption|session_resumption/.test(s),
        goAway:/goAway|go_away/.test(s),
        turnComplete:/turnComplete|turn_complete/.test(s),
        interrupted:/interrupted/.test(s),
        inputTranscription:/inputTranscription|input_transcription/.test(s),
        outputTranscription:/outputTranscription|output_transcription/.test(s)
      };
      const tail=s.slice(Math.max(0,s.length-180000));
      const jsonStarts=[];
      for(let i=0;i<tail.length&&jsonStarts.length<8;i++)if(tail.charAt(i)==='{'||tail.charAt(i)==='[')jsonStarts.push(i);
      for(let i=jsonStarts.length-1;i>=0;i--){
        try{const parsed=JSON.parse(tail.slice(jsonStarts[i]));out.tailJson=valueShape(parsed,0);break;}catch(_){}
      }
    }catch(_){}
    return out;
  }
  function messageMeta(msg){
    const out={type:typeOf(msg),bytes:byteLength(msg)};
    try{
      if(typeof msg==='string'){out.chars=msg.length;out.models=modelsFromText(msg);out.mimes=mimeCandidates(msg);try{out.shape=valueShape(JSON.parse(msg),0);}catch(_){}return out;}
      if(msg&&typeof msg==='object')out.shape=valueShape(msg,0);
    }catch(_){}
    return out;
  }
  function summarize(){
    const recent=events.slice(-120);
    const stackCounts={};
    recent.forEach(function(e){try{const fp=e.payload&&e.payload.stack&&e.payload.stack.fingerprint;if(fp)stackCounts[fp]=(stackCounts[fp]||0)+1;}catch(_){}});
    return {ok:true,version:VERSION,eventCount:events.length,markSeq:markSeq,counters:Object.assign({},counters),recentKinds:recent.slice(-50).map(function(e){return e.kind;}),stackFingerprints:Object.keys(stackCounts).map(function(k){return {fingerprint:k,count:stackCounts[k]};}).sort(function(a,b){return b.count-a.count;}).slice(0,12)};
  }
  function reset(label){events.length=0;Object.keys(counters).forEach(function(k){counters[k]=0;});markSeq++;push('RESET',{markSeq:markSeq,label:String(label||'').slice(0,160)});return summarize();}
  function mark(label){markSeq++;push('MARK',{markSeq:markSeq,label:String(label||'').slice(0,160)});return summarize();}

  // Deep XHR/WebChannel instrumentation. It wraps the already-safe R13.1 XHR hooks.
  try{
    const X=window.XMLHttpRequest;
    if(X&&X.prototype&&!X.prototype.__aisR132Wrapped){
      const nativeOpen=X.prototype.open;
      const nativeSend=X.prototype.send;
      const nativeSetHeader=X.prototype.setRequestHeader;
      const nativeAbort=X.prototype.abort;

      X.prototype.open=function(method,url,async){
        const raw=String(url||'');
        this.__aisR132={raw:raw,url:safeUrl(raw),method:String(method||'GET').toUpperCase(),async:async!==false,headers:[],lastResponseChars:0};
        if(isBidi(raw)){counters.bidiOpen++;push('BIDI_OPEN',{ordinal:counters.bidiOpen,url:this.__aisR132.url,method:this.__aisR132.method,async:this.__aisR132.async,stack:stackMeta()});}
        return nativeOpen.apply(this,arguments);
      };
      X.prototype.setRequestHeader=function(name,value){
        try{const m=this.__aisR132;if(m&&isBidi(m.raw)&&m.headers.length<40)m.headers.push({name:String(name||'').slice(0,120),valueChars:String(value||'').length});}catch(_){}
        return nativeSetHeader.apply(this,arguments);
      };
      X.prototype.send=function(body){
        const xhr=this;const m=xhr.__aisR132||{raw:'',url:{scheme:'',host:'',path:''},method:'GET',headers:[],lastResponseChars:0};
        if(isBidi(m.raw)){
          counters.bidiSend++;
          const ordinal=counters.bidiSend;
          push('BIDI_SEND',{ordinal:ordinal,url:m.url,method:m.method,headers:m.headers,envelope:webChannelEnvelopeMeta(body),stack:stackMeta()});
          try{xhr.addEventListener('readystatechange',function(){
            if(xhr.readyState!==3&&xhr.readyState!==4)return;
            let text='';try{text=typeof xhr.responseText==='string'?xhr.responseText:'';}catch(_){}
            const meta=responseMeta(text,m.lastResponseChars||0);m.lastResponseChars=meta.chars;
            if(xhr.readyState===3){counters.bidiReady3++;if(meta.delta>0)push('BIDI_RESPONSE_PROGRESS',{ordinal:ordinal,status:Number(xhr.status||0),response:meta,progressOrdinal:counters.bidiReady3});}
            else{counters.bidiReady4++;push('BIDI_RESPONSE_FINAL',{ordinal:ordinal,status:Number(xhr.status||0),response:meta,finalOrdinal:counters.bidiReady4});}
          });}catch(_){}
        }
        return nativeSend.apply(this,arguments);
      };
      X.prototype.abort=function(){
        try{const m=this.__aisR132;if(m&&isBidi(m.raw)){counters.bidiAbort++;push('BIDI_ABORT',{ordinal:counters.bidiAbort,url:m.url,method:m.method,stack:stackMeta()});}}catch(_){}
        return nativeAbort.apply(this,arguments);
      };
      X.prototype.__aisR132Wrapped=true;
      push('HOOK',{target:'XMLHttpRequest-deep'});
    }
  }catch(e){push('HOOK_ERROR',{target:'XMLHttpRequest-deep',name:String(e&&e.name||'Error')});}

  function instrumentPort(port,label){
    try{
      if(!port||port.__aisR132Wrapped)return port;
      const nativePost=port.postMessage;
      if(typeof nativePost==='function')port.postMessage=function(msg){counters.audioPortOut++;push('AUDIO_PORT_OUT',{label:String(label||'').slice(0,120),ordinal:counters.audioPortOut,message:messageMeta(msg)},false);return nativePost.apply(this,arguments);};
      if(port.addEventListener)port.addEventListener('message',function(ev){counters.audioPortIn++;push('AUDIO_PORT_IN',{label:String(label||'').slice(0,120),ordinal:counters.audioPortIn,message:messageMeta(ev&&ev.data)},false);});
      port.__aisR132Wrapped=true;
    }catch(_){}
    return port;
  }
  function instrumentAudioContext(ctx){
    try{
      if(!ctx||ctx.__aisR132Wrapped)return ctx;
      ['createMediaStreamSource','createMediaStreamTrackSource','createScriptProcessor','createMediaStreamDestination','createAnalyser','createGain','createChannelMerger','createChannelSplitter'].forEach(function(name){
        const fn=ctx[name];if(typeof fn!=='function')return;
        ctx[name]=function(){counters.audioNode++;const args=Array.prototype.slice.call(arguments);push('AUDIO_NODE_CREATE',{ordinal:counters.audioNode,name:name,argTypes:args.slice(0,8).map(typeOf)});return fn.apply(ctx,arguments);};
      });
      const aw=ctx.audioWorklet;
      if(aw&&typeof aw.addModule==='function'&&!aw.__aisR132Wrapped){const nativeAdd=aw.addModule.bind(aw);aw.addModule=function(url,options){push('AUDIO_WORKLET_MODULE',{url:safeUrl(url),hasCredentialsOption:!!(options&&Object.prototype.hasOwnProperty.call(options,'credentials'))});return nativeAdd(url,options);};aw.__aisR132Wrapped=true;}
      ctx.__aisR132Wrapped=true;
    }catch(_){}
    return ctx;
  }
  try{
    const NativeAC=window.AudioContext||window.webkitAudioContext;
    if(typeof NativeAC==='function'&&!NativeAC.__aisR132Wrapped){
      function WrappedAC(options){counters.audioContext++;push('AUDIO_CONTEXT_CREATE',{ordinal:counters.audioContext,sampleRate:Number(options&&options.sampleRate||0),latencyHintType:typeOf(options&&options.latencyHint)});return instrumentAudioContext(new NativeAC(options));}
      WrappedAC.prototype=NativeAC.prototype;WrappedAC.__aisR132Wrapped=true;
      window.AudioContext=WrappedAC;if(window.webkitAudioContext)window.webkitAudioContext=WrappedAC;
      push('HOOK',{target:'AudioContext'});
    }
  }catch(e){push('HOOK_ERROR',{target:'AudioContext',name:String(e&&e.name||'Error')});}

  try{
    const NativeAWN=window.AudioWorkletNode;
    if(typeof NativeAWN==='function'&&!NativeAWN.__aisR132Wrapped){
      function WrappedAWN(context,name,options){
        counters.audioWorkletNode++;
        const opts=options||{};
        push('AUDIO_WORKLET_NODE_CREATE',{ordinal:counters.audioWorkletNode,nameChars:String(name||'').length,numberOfInputs:Number(opts.numberOfInputs||0),numberOfOutputs:Number(opts.numberOfOutputs||0),outputChannelCount:Array.isArray(opts.outputChannelCount)?opts.outputChannelCount.slice(0,8):[],processorOptionKeys:keysOf(opts.processorOptions,30)});
        const node=new NativeAWN(context,name,options);instrumentPort(node&&node.port,'AudioWorkletNode');return node;
      }
      WrappedAWN.prototype=NativeAWN.prototype;WrappedAWN.__aisR132Wrapped=true;window.AudioWorkletNode=WrappedAWN;
      push('HOOK',{target:'AudioWorkletNode'});
    }
  }catch(e){push('HOOK_ERROR',{target:'AudioWorkletNode',name:String(e&&e.name||'Error')});}

  try{
    const NativeMR=window.MediaRecorder;
    if(typeof NativeMR==='function'&&!NativeMR.__aisR132Wrapped){
      function WrappedMR(stream,options){
        counters.mediaRecorder++;const mr=new NativeMR(stream,options);push('MEDIA_RECORDER_CREATE',{ordinal:counters.mediaRecorder,mime:String(options&&options.mimeType||'').slice(0,120),audioBitsPerSecond:Number(options&&options.audioBitsPerSecond||0),videoBitsPerSecond:Number(options&&options.videoBitsPerSecond||0)});
        try{mr.addEventListener('dataavailable',function(ev){counters.mediaRecorderChunk++;push('MEDIA_RECORDER_CHUNK',{ordinal:counters.mediaRecorderChunk,bytes:Number(ev&&ev.data&&ev.data.size||0),mime:String(ev&&ev.data&&ev.data.type||'').slice(0,120)},false);});}catch(_){}
        return mr;
      }
      WrappedMR.prototype=NativeMR.prototype;WrappedMR.__aisR132Wrapped=true;window.MediaRecorder=WrappedMR;push('HOOK',{target:'MediaRecorder'});
    }
  }catch(e){push('HOOK_ERROR',{target:'MediaRecorder',name:String(e&&e.name||'Error')});}

  try{
    const NativeTP=window.MediaStreamTrackProcessor;
    if(typeof NativeTP==='function'&&!NativeTP.__aisR132Wrapped){
      function WrappedTP(init){counters.trackProcessor++;push('TRACK_PROCESSOR_CREATE',{ordinal:counters.trackProcessor,kind:String(init&&init.track&&init.track.kind||'')});return new NativeTP(init);}
      WrappedTP.prototype=NativeTP.prototype;WrappedTP.__aisR132Wrapped=true;window.MediaStreamTrackProcessor=WrappedTP;push('HOOK',{target:'MediaStreamTrackProcessor'});
    }
  }catch(e){push('HOOK_ERROR',{target:'MediaStreamTrackProcessor',name:String(e&&e.name||'Error')});}

  const api={version:VERSION,describe:summarize,reset:reset,mark:mark,recent:function(limit){return {ok:true,version:VERSION,events:events.slice(-Math.max(1,Math.min(260,Number(limit||140))))};}};
  window.__AIS_LIVE_DEEP_PROBE__=api;
  push('DEEP_PROBE_INSTALLED',{version:VERSION,href:safeUrl(location.href)});
})();
    """.trimIndent()
}
