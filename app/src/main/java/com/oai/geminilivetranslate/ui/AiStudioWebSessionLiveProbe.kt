package com.oai.geminilivetranslate.ui

/**
 * R13 document-start transport probe for AI Studio Live.
 *
 * This probe is intentionally observational. It records transport metadata, model identifiers and
 * JSON/frame shapes only. It strips URL query/fragment data and never emits request bodies, WebSocket
 * payload contents, cookies, auth headers, API keys, audio bytes, image bytes or video bytes.
 *
 * R13 starts with audio Live, but modality detection is deliberately future-proofed for image/video.
 */
object AiStudioWebSessionLiveProbe {
    const val VERSION = "2026-09-02-web-session-r13.1-live-transport-multimodal-probe"
    const val TARGET_MODEL = "gemini-3.1-flash-live-preview"

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_LIVE_PROBE__&&window.__AIS_LIVE_PROBE__.version){return;}
  const VERSION='2026-09-02-web-session-r13.1-live-transport-multimodal-probe';
  const TARGET_MODEL='gemini-3.1-flash-live-preview';
  const MAX_EVENTS=520;
  const events=[];
  const seenModels=new Set();
  const counters={
    wsCreate:0,wsSend:0,wsMessage:0,
    webSocketStream:0,webTransport:0,
    fetch:0,xhr:0,worker:0,rtc:0,beacon:0,resource:0,
    audioFramesOut:0,videoFramesOut:0,imageFramesOut:0,textFramesOut:0,
    audioFramesIn:0,videoFramesIn:0,imageFramesIn:0,textFramesIn:0
  };
  let seq=0;
  let markSeq=0;

  function bridge(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function'){
        b.onJsEvent(JSON.stringify({kind:kind,payload:payload||{}}));
      }
    }catch(_){}
  }
  function safeUrl(raw){
    try{
      const u=new URL(String(raw||''),location.href);
      return {scheme:String(u.protocol||'').replace(':',''),host:String(u.host||''),path:String(u.pathname||'').slice(0,500)};
    }catch(_){return {scheme:'',host:'',path:''};}
  }
  function interestingUrl(raw){
    const u=safeUrl(raw);
    const s=(u.host+' '+u.path).toLowerCase();
    return /live|bidi|generative|maker|token|auth|session|stream|realtime|socket|speech|audio|video|media|prompt/.test(s);
  }
  function keysOf(obj,limit){
    try{return Object.keys(obj||{}).slice(0,limit||30).sort();}catch(_){return [];}
  }
  function jsonType(v){
    if(v===null)return 'null';
    if(Array.isArray(v))return 'array';
    try{return v&&v.constructor&&v.constructor.name||typeof v;}catch(_){return typeof v;}
  }
  function normalizeModel(raw){
    const v=String(raw||'').trim().replace(/^models\//,'');
    if(!/^gemini-[a-z0-9._-]+$/i.test(v))return '';
    seenModels.add(v);
    return v.slice(0,160);
  }
  function modelsFromText(text){
    const out=[];
    try{
      const matches=String(text||'').match(/(?:models\/)?gemini-[a-z0-9._-]+/ig)||[];
      matches.forEach(function(m){const n=normalizeModel(m);if(n&&out.indexOf(n)<0&&out.length<12)out.push(n);});
    }catch(_){}
    return out;
  }
  function mimeMeta(obj){
    try{
      if(!obj||typeof obj!=='object')return {};
      const mime=String(obj.mimeType||obj.mime_type||'').slice(0,120);
      const data=obj.data;
      return {mime:mime,dataChars:typeof data==='string'?data.length:0,dataType:jsonType(data)};
    }catch(_){return {};}
  }
  function bodyMeta(body){
    const out={kind:'none',bytes:0,textChars:0,topKeys:[],modelCandidates:[]};
    try{
      if(body==null)return out;
      if(typeof body==='string'){
        out.kind='string';out.textChars=body.length;out.modelCandidates=modelsFromText(body);
        try{const j=JSON.parse(body);out.topKeys=keysOf(j,30);out.json=true;}catch(_){}
        return out;
      }
      if(body instanceof ArrayBuffer){out.kind='ArrayBuffer';out.bytes=body.byteLength;return out;}
      if(ArrayBuffer.isView(body)){out.kind=body.constructor&&body.constructor.name||'TypedArray';out.bytes=body.byteLength||0;return out;}
      if(typeof Blob!=='undefined'&&body instanceof Blob){out.kind='Blob';out.bytes=body.size||0;out.mime=String(body.type||'').slice(0,120);return out;}
      if(typeof FormData!=='undefined'&&body instanceof FormData){
        out.kind='FormData';
        const fields=[];let files=0;
        try{body.forEach(function(v,k){if(fields.length<30)fields.push(String(k).slice(0,120));if(typeof File!=='undefined'&&v instanceof File)files++;});}catch(_){}
        out.fields=fields;out.files=files;return out;
      }
      if(typeof URLSearchParams!=='undefined'&&body instanceof URLSearchParams){out.kind='URLSearchParams';return out;}
      out.kind=(body&&body.constructor&&body.constructor.name)||typeof body;
    }catch(_){}
    return out;
  }
  function modalityFromInline(inline){
    const mime=String(inline&&inline.mimeType||inline&&inline.mime_type||'').toLowerCase();
    if(mime.startsWith('audio/'))return 'audio';
    if(mime.startsWith('video/'))return 'video';
    if(mime.startsWith('image/'))return 'image';
    return 'other';
  }
  function bumpModality(direction,kind){
    const suffix=direction==='out'?'Out':'In';
    const key=kind+'Frames'+suffix;
    if(Object.prototype.hasOwnProperty.call(counters,key))counters[key]++;
  }
  function inspectRealtimeInput(ri,direction){
    const out={keys:keysOf(ri,30),audio:false,video:false,image:false,text:false,mediaChunks:0,mediaMimes:[]};
    try{
      if(!ri||typeof ri!=='object')return out;
      if(ri.audio){out.audio=true;out.audioMeta=mimeMeta(ri.audio);bumpModality(direction,'audio');}
      if(ri.video){out.video=true;out.videoMeta=mimeMeta(ri.video);bumpModality(direction,'video');}
      if(typeof ri.text==='string'){out.text=true;out.textChars=ri.text.length;bumpModality(direction,'text');}
      const chunks=Array.isArray(ri.mediaChunks)?ri.mediaChunks:(Array.isArray(ri.media_chunks)?ri.media_chunks:[]);
      out.mediaChunks=chunks.length;
      chunks.slice(0,20).forEach(function(ch){
        const m=mimeMeta(ch);if(m.mime&&out.mediaMimes.indexOf(m.mime)<0)out.mediaMimes.push(m.mime);
        const modality=modalityFromInline(ch);
        if(modality==='audio'){out.audio=true;bumpModality(direction,'audio');}
        else if(modality==='video'){out.video=true;bumpModality(direction,'video');}
        else if(modality==='image'){out.image=true;bumpModality(direction,'image');}
      });
      ['image','imageData','image_data'].forEach(function(k){if(ri[k]){out.image=true;bumpModality(direction,'image');}});
      out.audioStreamEnd=!!(ri.audioStreamEnd||ri.audio_stream_end);
      out.activityStart=!!(ri.activityStart||ri.activity_start);
      out.activityEnd=!!(ri.activityEnd||ri.activity_end);
    }catch(_){}
    return out;
  }
  function inspectServerContent(sc){
    const out={keys:keysOf(sc,30),turnComplete:false,generationComplete:false,interrupted:false,partShapes:[]};
    try{
      if(!sc||typeof sc!=='object')return out;
      out.turnComplete=!!(sc.turnComplete||sc.turn_complete);
      out.generationComplete=!!(sc.generationComplete||sc.generation_complete);
      out.interrupted=!!sc.interrupted;
      const inTx=sc.inputTranscription||sc.input_transcription||sc.interimInputTranscription||sc.interim_input_transcription;
      const outTx=sc.outputTranscription||sc.output_transcription;
      if(inTx&&typeof inTx.text==='string')out.inputTranscriptChars=inTx.text.length;
      if(outTx&&typeof outTx.text==='string'){out.outputTranscriptChars=outTx.text.length;bumpModality('in','text');}
      const mt=sc.modelTurn||sc.model_turn;
      const parts=mt&&Array.isArray(mt.parts)?mt.parts:[];
      parts.slice(0,20).forEach(function(p){
        const shape={keys:keysOf(p,20)};
        if(p&&typeof p.text==='string'){shape.textChars=p.text.length;bumpModality('in','text');}
        const inline=p&&(p.inlineData||p.inline_data);
        if(inline){
          const mm=mimeMeta(inline);shape.inlineMime=mm.mime;shape.inlineChars=mm.dataChars;
          const modality=modalityFromInline(inline);
          shape.modality=modality;
          if(modality==='audio'||modality==='video'||modality==='image')bumpModality('in',modality);
        }
        out.partShapes.push(shape);
      });
    }catch(_){}
    return out;
  }
  function wsFrameMeta(data,direction){
    const out={direction:direction||'',kind:'unknown',bytes:0,textChars:0,topKeys:[],modelCandidates:[]};
    try{
      if(typeof data==='string'){
        out.kind='string';out.textChars=data.length;out.modelCandidates=modelsFromText(data);
        try{
          const j=JSON.parse(data);out.json=true;out.topKeys=keysOf(j,30);
          const setup=j&&j.setup;
          if(setup&&typeof setup==='object'){
            out.setupKeys=keysOf(setup,30);
            const model=normalizeModel(setup.model);if(model)out.model=model;
            const gc=setup.generationConfig||setup.generation_config;
            if(gc&&typeof gc==='object'){
              out.generationConfigKeys=keysOf(gc,30);
              const rm=gc.responseModalities||gc.response_modalities;
              if(Array.isArray(rm))out.responseModalities=rm.slice(0,10).map(function(v){return String(v).slice(0,40);});
            }
            out.hasInputAudioTranscription=!!(setup.inputAudioTranscription||setup.input_audio_transcription);
            out.hasOutputAudioTranscription=!!(setup.outputAudioTranscription||setup.output_audio_transcription);
            out.hasSessionResumption=!!(setup.sessionResumption||setup.session_resumption);
          }
          const ri=j&&(j.realtimeInput||j.realtime_input);
          if(ri&&typeof ri==='object')out.realtime=inspectRealtimeInput(ri,direction);
          const sc=j&&(j.serverContent||j.server_content);
          if(sc&&typeof sc==='object')out.serverContent=inspectServerContent(sc);
          const cc=j&&(j.clientContent||j.client_content);
          if(cc&&typeof cc==='object')out.clientContentKeys=keysOf(cc,30);
          if(j&&(j.setupComplete!=null||j.setup_complete!=null))out.setupComplete=true;
          const sr=j&&(j.sessionResumptionUpdate||j.session_resumption_update);
          if(sr)out.sessionResumptionKeys=keysOf(sr,20);
          const ga=j&&(j.goAway||j.go_away);if(ga)out.goAwayKeys=keysOf(ga,20);
          if(j&&j.error){out.errorKeys=keysOf(j.error,20);out.errorCode=Number(j.error.code||0);}
        }catch(_){}
        return out;
      }
      if(data instanceof ArrayBuffer){out.kind='ArrayBuffer';out.bytes=data.byteLength;return out;}
      if(ArrayBuffer.isView(data)){out.kind=data.constructor&&data.constructor.name||'TypedArray';out.bytes=data.byteLength||0;return out;}
      if(typeof Blob!=='undefined'&&data instanceof Blob){out.kind='Blob';out.bytes=data.size||0;out.mime=String(data.type||'').slice(0,120);return out;}
      out.kind=(data&&data.constructor&&data.constructor.name)||typeof data;
    }catch(_){}
    return out;
  }
  function push(kind,payload,emit){
    try{
      const e={id:++seq,at:Date.now(),kind:kind,payload:payload||{}};
      events.push(e);if(events.length>MAX_EVENTS)events.splice(0,events.length-MAX_EVENTS);
      if(emit!==false)bridge('R13_'+kind,e.payload);
      return e;
    }catch(_){return null;}
  }
  function summarize(){
    const recent=events.slice(-160);
    const hosts={};
    recent.forEach(function(e){try{const h=e.payload&&e.payload.url&&e.payload.url.host;if(h)hosts[h]=(hosts[h]||0)+1;}catch(_){}});
    return {
      ok:true,version:VERSION,targetModel:TARGET_MODEL,eventCount:events.length,counters:Object.assign({},counters),
      markSeq:markSeq,models:Array.from(seenModels).slice(0,20),targetObserved:seenModels.has(TARGET_MODEL),
      recentKinds:recent.slice(-50).map(function(e){return e.kind;}),hosts:hosts
    };
  }
  function reset(label){events.length=0;seenModels.clear();Object.keys(counters).forEach(function(k){counters[k]=0;});markSeq++;push('PROBE_RESET',{markSeq:markSeq,label:String(label||'').slice(0,200),targetModel:TARGET_MODEL});return summarize();}
  function mark(label){markSeq++;push('MARK',{markSeq:markSeq,label:String(label||'').slice(0,200)});return summarize();}

  try{
    const NativeWS=window.WebSocket;
    if(typeof NativeWS==='function'&&!NativeWS.__aisR13Wrapped){
      function WrappedWebSocket(url,protocols){
        const meta=safeUrl(url);counters.wsCreate++;push('WS_CREATE',{url:meta,protocolCount:Array.isArray(protocols)?protocols.length:(protocols?1:0),ordinal:counters.wsCreate});
        const ws=protocols===undefined?new NativeWS(url):new NativeWS(url,protocols);
        try{
          const nativeSend=ws.send;
          ws.send=function(data){counters.wsSend++;push('WS_SEND',{url:meta,ordinal:counters.wsSend,frame:wsFrameMeta(data,'out')});return nativeSend.call(ws,data);};
          ws.addEventListener('open',function(){push('WS_OPEN',{url:meta,protocolChars:String(ws.protocol||'').length});});
          ws.addEventListener('message',function(ev){counters.wsMessage++;push('WS_MESSAGE',{url:meta,ordinal:counters.wsMessage,frame:wsFrameMeta(ev&&ev.data,'in')});});
          ws.addEventListener('close',function(ev){push('WS_CLOSE',{url:meta,code:Number(ev&&ev.code||0),clean:!!(ev&&ev.wasClean),reasonChars:String(ev&&ev.reason||'').length});});
          ws.addEventListener('error',function(){push('WS_ERROR',{url:meta});});
        }catch(_){}
        return ws;
      }
      WrappedWebSocket.prototype=NativeWS.prototype;
      try{Object.defineProperty(WrappedWebSocket,'CONNECTING',{value:NativeWS.CONNECTING});Object.defineProperty(WrappedWebSocket,'OPEN',{value:NativeWS.OPEN});Object.defineProperty(WrappedWebSocket,'CLOSING',{value:NativeWS.CLOSING});Object.defineProperty(WrappedWebSocket,'CLOSED',{value:NativeWS.CLOSED});}catch(_){}
      WrappedWebSocket.__aisR13Wrapped=true;window.WebSocket=WrappedWebSocket;push('HOOK',{target:'WebSocket'});
    }
  }catch(e){push('HOOK_ERROR',{target:'WebSocket',name:String(e&&e.name||'Error')});}

  try{
    const NativeWSS=window.WebSocketStream;
    if(typeof NativeWSS==='function'&&!NativeWSS.__aisR13Wrapped){
      function WrappedWSS(url,options){counters.webSocketStream++;push('WEBSOCKET_STREAM_CREATE',{url:safeUrl(url),ordinal:counters.webSocketStream});return new NativeWSS(url,options);}
      WrappedWSS.prototype=NativeWSS.prototype;WrappedWSS.__aisR13Wrapped=true;window.WebSocketStream=WrappedWSS;push('HOOK',{target:'WebSocketStream'});
    }
  }catch(e){push('HOOK_ERROR',{target:'WebSocketStream',name:String(e&&e.name||'Error')});}

  try{
    const NativeWT=window.WebTransport;
    if(typeof NativeWT==='function'&&!NativeWT.__aisR13Wrapped){
      function WrappedWT(url,options){counters.webTransport++;push('WEBTRANSPORT_CREATE',{url:safeUrl(url),ordinal:counters.webTransport});const wt=new NativeWT(url,options);try{wt.ready&&wt.ready.then(function(){push('WEBTRANSPORT_READY',{url:safeUrl(url)});},function(err){push('WEBTRANSPORT_ERROR',{url:safeUrl(url),name:String(err&&err.name||'Error')});});wt.closed&&wt.closed.then(function(){push('WEBTRANSPORT_CLOSED',{url:safeUrl(url)});},function(err){push('WEBTRANSPORT_ERROR',{url:safeUrl(url),name:String(err&&err.name||'Error')});});}catch(_){}return wt;}
      WrappedWT.prototype=NativeWT.prototype;WrappedWT.__aisR13Wrapped=true;window.WebTransport=WrappedWT;push('HOOK',{target:'WebTransport'});
    }
  }catch(e){push('HOOK_ERROR',{target:'WebTransport',name:String(e&&e.name||'Error')});}

  try{
    const nativeFetch=window.fetch;
    if(typeof nativeFetch==='function'&&!nativeFetch.__aisR13Wrapped){
      const wrapped=function(input,init){
        let url='';try{url=typeof input==='string'?input:(input&&input.url)||'';}catch(_){}
        const meta=safeUrl(url);const method=String(init&&init.method||(input&&input.method)||'GET').toUpperCase();const bm=bodyMeta(init&&init.body);counters.fetch++;
        if(interestingUrl(url))push('FETCH_START',{url:meta,method:method,body:bm,ordinal:counters.fetch});
        return nativeFetch.apply(this,arguments).then(function(resp){if(interestingUrl(url))push('FETCH_RESULT',{url:meta,status:Number(resp&&resp.status||0),ok:!!(resp&&resp.ok)});return resp;},function(err){if(interestingUrl(url))push('FETCH_ERROR',{url:meta,name:String(err&&err.name||'Error')});throw err;});
      };
      wrapped.__aisR13Wrapped=true;window.fetch=wrapped;push('HOOK',{target:'fetch'});
    }
  }catch(e){push('HOOK_ERROR',{target:'fetch',name:String(e&&e.name||'Error')});}

  try{
    const X=window.XMLHttpRequest;
    if(X&&X.prototype&&!X.prototype.__aisR13Wrapped){
      const open=X.prototype.open,send=X.prototype.send;
      X.prototype.open=function(method,url){this.__aisR13Meta={method:String(method||'GET').toUpperCase(),url:safeUrl(url),raw:String(url||'')};return open.apply(this,arguments);};
      X.prototype.send=function(body){const m=this.__aisR13Meta||{method:'GET',url:{scheme:'',host:'',path:''},raw:''};counters.xhr++;const bm=bodyMeta(body);if(interestingUrl(m.raw))push('XHR_START',{url:m.url,method:m.method,body:bm,ordinal:counters.xhr});try{this.addEventListener('loadend',function(){if(interestingUrl(m.raw))push('XHR_RESULT',{url:m.url,status:Number(this.status||0),responseChars:typeof this.responseText==='string'?this.responseText.length:0});});}catch(_){}return send.apply(this,arguments);};
      X.prototype.__aisR13Wrapped=true;push('HOOK',{target:'XMLHttpRequest'});
    }
  }catch(e){push('HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}

  function wrapWorker(name){
    try{
      const Native=window[name];if(typeof Native!=='function'||Native.__aisR13Wrapped)return;
      function Wrapped(url,options){const meta=safeUrl(url);counters.worker++;push('WORKER_CREATE',{workerType:name,url:meta,ordinal:counters.worker});const w=new Native(url,options);try{const pm=w.postMessage;w.postMessage=function(msg){push('WORKER_POST',{workerType:name,url:meta,message:bodyMeta(msg)},false);return pm.apply(w,arguments);};w.addEventListener&&w.addEventListener('message',function(ev){push('WORKER_MESSAGE',{workerType:name,url:meta,message:bodyMeta(ev&&ev.data)},false);});}catch(_){}return w;}
      Wrapped.prototype=Native.prototype;Wrapped.__aisR13Wrapped=true;window[name]=Wrapped;push('HOOK',{target:name});
    }catch(e){push('HOOK_ERROR',{target:name,name:String(e&&e.name||'Error')});}
  }
  wrapWorker('Worker');wrapWorker('SharedWorker');

  try{
    const sw=navigator&&navigator.serviceWorker;
    if(sw){sw.addEventListener&&sw.addEventListener('message',function(ev){push('SERVICE_WORKER_MESSAGE',{message:bodyMeta(ev&&ev.data)},false);});push('HOOK',{target:'serviceWorker'});}
  }catch(e){push('HOOK_ERROR',{target:'serviceWorker',name:String(e&&e.name||'Error')});}

  try{
    const NativeRTC=window.RTCPeerConnection||window.webkitRTCPeerConnection;
    if(typeof NativeRTC==='function'&&!NativeRTC.__aisR13Wrapped){
      function WrappedRTC(config,constraints){counters.rtc++;const pc=new NativeRTC(config,constraints);push('RTC_CREATE',{ordinal:counters.rtc,iceServers:Array.isArray(config&&config.iceServers)?config.iceServers.length:0});try{const adc=pc.createDataChannel;pc.createDataChannel=function(label,opts){push('RTC_DATA_CHANNEL',{labelChars:String(label||'').length,ordered:opts&&opts.ordered!==false});return adc.apply(pc,arguments);};const at=pc.addTransceiver;if(typeof at==='function')pc.addTransceiver=function(trackOrKind,init){push('RTC_TRANSCEIVER',{kind:typeof trackOrKind==='string'?trackOrKind:(trackOrKind&&trackOrKind.kind)||'',direction:String(init&&init.direction||'')});return at.apply(pc,arguments);};const addTrack=pc.addTrack;if(typeof addTrack==='function')pc.addTrack=function(track){push('RTC_ADD_TRACK',{kind:String(track&&track.kind||'')});return addTrack.apply(pc,arguments);};}catch(_){}return pc;}
      WrappedRTC.prototype=NativeRTC.prototype;WrappedRTC.__aisR13Wrapped=true;window.RTCPeerConnection=WrappedRTC;if(window.webkitRTCPeerConnection)window.webkitRTCPeerConnection=WrappedRTC;push('HOOK',{target:'RTCPeerConnection'});
    }
  }catch(e){push('HOOK_ERROR',{target:'RTCPeerConnection',name:String(e&&e.name||'Error')});}

  try{
    if(navigator&&navigator.sendBeacon){const nb=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(url,data){counters.beacon++;if(interestingUrl(url))push('BEACON',{url:safeUrl(url),body:bodyMeta(data),ordinal:counters.beacon});return nb(url,data);};push('HOOK',{target:'sendBeacon'});}
  }catch(e){push('HOOK_ERROR',{target:'sendBeacon',name:String(e&&e.name||'Error')});}

  try{
    if(typeof EventSource==='function'){
      const NativeES=EventSource;
      function WrappedES(url,config){const meta=safeUrl(url);push('EVENTSOURCE_CREATE',{url:meta});const es=new NativeES(url,config);try{es.addEventListener('message',function(ev){push('EVENTSOURCE_MESSAGE',{url:meta,textChars:String(ev&&ev.data||'').length},false);});}catch(_){}return es;}
      WrappedES.prototype=NativeES.prototype;window.EventSource=WrappedES;push('HOOK',{target:'EventSource'});
    }
  }catch(e){push('HOOK_ERROR',{target:'EventSource',name:String(e&&e.name||'Error')});}

  try{
    if(window.PerformanceObserver){
      const po=new PerformanceObserver(function(list){
        const entries=list.getEntries();
        for(let i=0;i<entries.length;i++){
          const e=entries[i];if(!e||!interestingUrl(e.name))continue;counters.resource++;
          push('RESOURCE',{url:safeUrl(e.name),initiator:String(e.initiatorType||''),durationMs:Math.round(Number(e.duration||0)),transfer:Number(e.transferSize||0),encoded:Number(e.encodedBodySize||0),decoded:Number(e.decodedBodySize||0),ordinal:counters.resource},false);
        }
      });
      po.observe({entryTypes:['resource']});push('HOOK',{target:'PerformanceObserver'});
    }
  }catch(e){push('HOOK_ERROR',{target:'PerformanceObserver',name:String(e&&e.name||'Error')});}

  try{
    const md=navigator&&navigator.mediaDevices;
    if(md&&typeof md.getUserMedia==='function'){
      const gum=md.getUserMedia.bind(md);
      md.getUserMedia=function(constraints){push('GET_USER_MEDIA',{audio:!!(constraints&&constraints.audio),video:!!(constraints&&constraints.video)});return gum(constraints).then(function(stream){try{const tracks=stream&&stream.getTracks?stream.getTracks():[];push('MEDIA_STREAM',{trackKinds:tracks.slice(0,10).map(function(t){return String(t&&t.kind||'');})});}catch(_){}return stream;});};
      push('HOOK',{target:'getUserMedia'});
    }
    if(md&&typeof md.getDisplayMedia==='function'){
      const gdm=md.getDisplayMedia.bind(md);
      md.getDisplayMedia=function(constraints){push('GET_DISPLAY_MEDIA',{audio:!!(constraints&&constraints.audio),video:!!(constraints&&constraints.video)});return gdm(constraints);};
      push('HOOK',{target:'getDisplayMedia'});
    }
  }catch(e){push('HOOK_ERROR',{target:'mediaDevices',name:String(e&&e.name||'Error')});}

  const api={
    version:VERSION,targetModel:TARGET_MODEL,
    describe:summarize,reset:reset,mark:mark,
    recent:function(limit){return {ok:true,version:VERSION,targetModel:TARGET_MODEL,events:events.slice(-Math.max(1,Math.min(240,Number(limit||100))))};}
  };
  window.__AIS_LIVE_PROBE__=api;
  push('LIVE_PROBE_INSTALLED',{version:VERSION,targetModel:TARGET_MODEL,href:safeUrl(location.href)});
})();
    """.trimIndent()
}
