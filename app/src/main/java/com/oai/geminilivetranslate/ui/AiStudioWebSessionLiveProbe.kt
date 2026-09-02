package com.oai.geminilivetranslate.ui

/**
 * R13 document-start transport probe for AI Studio Live.
 *
 * The probe intentionally records only transport metadata and JSON shape information. It strips
 * query strings and never emits request bodies, WebSocket payload contents, cookies, auth headers,
 * API keys, audio bytes or video bytes to Android diagnostics.
 */
object AiStudioWebSessionLiveProbe {
    const val VERSION = "2026-09-02-web-session-r13-live-transport-probe"

    val DOCUMENT_START = r"""
(function(){
  'use strict';
  if(window.__AIS_LIVE_PROBE__&&window.__AIS_LIVE_PROBE__.version){return;}
  const VERSION='2026-09-02-web-session-r13-live-transport-probe';
  const MAX_EVENTS=420;
  const events=[];
  const counters={wsOpen:0,wsSend:0,wsMessage:0,fetch:0,xhr:0,worker:0,rtc:0,beacon:0,resource:0};
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
  function bodyMeta(body){
    const out={kind:'none',bytes:0,textChars:0,topKeys:[]};
    try{
      if(body==null)return out;
      if(typeof body==='string'){
        out.kind='string';out.textChars=body.length;
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
  function wsFrameMeta(data,direction){
    const out={direction:direction||'',kind:'unknown',bytes:0,textChars:0,topKeys:[]};
    try{
      if(typeof data==='string'){
        out.kind='string';out.textChars=data.length;
        try{
          const j=JSON.parse(data);
          out.json=true;out.topKeys=keysOf(j,30);
          const sc=j&&j.serverContent;
          if(sc&&typeof sc==='object'){
            out.serverContentKeys=keysOf(sc,30);
            const mt=sc.modelTurn;
            if(mt&&Array.isArray(mt.parts)){
              out.partShapes=mt.parts.slice(0,12).map(function(p){
                const shape={keys:keysOf(p,20)};
                if(p&&p.inlineData){shape.inlineMime=String(p.inlineData.mimeType||'').slice(0,120);shape.inlineChars=String(p.inlineData.data||'').length;}
                if(p&&typeof p.text==='string')shape.textChars=p.text.length;
                return shape;
              });
            }
          }
          if(j&&j.setup)out.setupKeys=keysOf(j.setup,30);
          if(j&&j.realtimeInput)out.realtimeInputKeys=keysOf(j.realtimeInput,30);
          if(j&&j.clientContent)out.clientContentKeys=keysOf(j.clientContent,30);
          if(j&&j.setupComplete!=null)out.setupComplete=true;
          if(j&&j.sessionResumptionUpdate)out.sessionResumptionKeys=keysOf(j.sessionResumptionUpdate,20);
          if(j&&j.goAway)out.goAwayKeys=keysOf(j.goAway,20);
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
    const recent=events.slice(-120);
    const wsHosts={};
    recent.forEach(function(e){try{const h=e.payload&&e.payload.url&&e.payload.url.host;if(h)wsHosts[h]=(wsHosts[h]||0)+1;}catch(_){}});
    return {ok:true,version:VERSION,eventCount:events.length,counters:Object.assign({},counters),markSeq:markSeq,recentKinds:recent.slice(-40).map(function(e){return e.kind;}),hosts:wsHosts};
  }
  function reset(label){events.length=0;Object.keys(counters).forEach(function(k){counters[k]=0;});markSeq++;push('PROBE_RESET',{markSeq:markSeq,label:String(label||'').slice(0,200)});return summarize();}
  function mark(label){markSeq++;push('MARK',{markSeq:markSeq,label:String(label||'').slice(0,200)});return summarize();}

  try{
    const NativeWS=window.WebSocket;
    if(typeof NativeWS==='function'&&!NativeWS.__aisR13Wrapped){
      function WrappedWebSocket(url,protocols){
        const meta=safeUrl(url);counters.wsOpen++;push('WS_CREATE',{url:meta,protocols:Array.isArray(protocols)?protocols.length:(protocols?1:0),ordinal:counters.wsOpen});
        const ws=protocols===undefined?new NativeWS(url):new NativeWS(url,protocols);
        try{
          const nativeSend=ws.send;
          ws.send=function(data){counters.wsSend++;const frame=wsFrameMeta(data,'out');push('WS_SEND',{url:meta,ordinal:counters.wsSend,frame:frame});return nativeSend.call(ws,data);};
          ws.addEventListener('open',function(){push('WS_OPEN',{url:meta,protocol:String(ws.protocol||'').slice(0,160)});});
          ws.addEventListener('message',function(ev){counters.wsMessage++;push('WS_MESSAGE',{url:meta,ordinal:counters.wsMessage,frame:wsFrameMeta(ev&&ev.data,'in')});});
          ws.addEventListener('close',function(ev){push('WS_CLOSE',{url:meta,code:Number(ev&&ev.code||0),clean:!!(ev&&ev.wasClean),reasonChars:String(ev&&ev.reason||'').length});});
          ws.addEventListener('error',function(){push('WS_ERROR',{url:meta});});
        }catch(_){}
        return ws;
      }
      WrappedWebSocket.prototype=NativeWS.prototype;
      try{Object.defineProperty(WrappedWebSocket,'CONNECTING',{value:NativeWS.CONNECTING});Object.defineProperty(WrappedWebSocket,'OPEN',{value:NativeWS.OPEN});Object.defineProperty(WrappedWebSocket,'CLOSING',{value:NativeWS.CLOSING});Object.defineProperty(WrappedWebSocket,'CLOSED',{value:NativeWS.CLOSED});}catch(_){}
      WrappedWebSocket.__aisR13Wrapped=true;
      window.WebSocket=WrappedWebSocket;
      push('HOOK',{target:'WebSocket'});
    }
  }catch(e){push('HOOK_ERROR',{target:'WebSocket',name:String(e&&e.name||'Error')});}

  try{
    const nativeFetch=window.fetch;
    if(typeof nativeFetch==='function'&&!nativeFetch.__aisR13Wrapped){
      const wrapped=function(input,init){
        let url='';try{url=typeof input==='string'?input:(input&&input.url)||'';}catch(_){}
        const meta=safeUrl(url);const method=String(init&&init.method||(input&&input.method)||'GET').toUpperCase();
        const bm=bodyMeta(init&&init.body);counters.fetch++;
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
      X.prototype.send=function(body){const m=this.__aisR13Meta||{method:'GET',url:{scheme:'',host:'',path:''},raw:''};counters.xhr++;if(interestingUrl(m.raw))push('XHR_START',{url:m.url,method:m.method,body:bodyMeta(body),ordinal:counters.xhr});try{this.addEventListener('loadend',function(){if(interestingUrl(m.raw))push('XHR_RESULT',{url:m.url,status:Number(this.status||0),responseChars:typeof this.responseText==='string'?this.responseText.length:0});});}catch(_){}return send.apply(this,arguments);};
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
    const NativeRTC=window.RTCPeerConnection||window.webkitRTCPeerConnection;
    if(typeof NativeRTC==='function'&&!NativeRTC.__aisR13Wrapped){
      function WrappedRTC(config,constraints){counters.rtc++;const pc=new NativeRTC(config,constraints);push('RTC_CREATE',{ordinal:counters.rtc,iceServers:Array.isArray(config&&config.iceServers)?config.iceServers.length:0});try{const adc=pc.createDataChannel;pc.createDataChannel=function(label,opts){push('RTC_DATA_CHANNEL',{labelChars:String(label||'').length,ordered:opts&&opts.ordered!==false});return adc.apply(pc,arguments);};const at=pc.addTransceiver; if(typeof at==='function')pc.addTransceiver=function(trackOrKind,init){push('RTC_TRANSCEIVER',{kind:typeof trackOrKind==='string'?trackOrKind:(trackOrKind&&trackOrKind.kind)||'',direction:String(init&&init.direction||'')});return at.apply(pc,arguments);};}catch(_){}return pc;}
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
      md.getUserMedia=function(constraints){push('GET_USER_MEDIA',{audio:!!(constraints&&constraints.audio),video:!!(constraints&&constraints.video)});return gum(constraints);};
      push('HOOK',{target:'getUserMedia'});
    }
  }catch(e){push('HOOK_ERROR',{target:'getUserMedia',name:String(e&&e.name||'Error')});}

  const api={
    version:VERSION,
    describe:summarize,
    reset:reset,
    mark:mark,
    recent:function(limit){return {ok:true,version:VERSION,events:events.slice(-Math.max(1,Math.min(200,Number(limit||80))))};}
  };
  window.__AIS_LIVE_PROBE__=api;
  push('LIVE_PROBE_INSTALLED',{version:VERSION,href:safeUrl(location.href)});
})();

    """.trimIndent()
}
