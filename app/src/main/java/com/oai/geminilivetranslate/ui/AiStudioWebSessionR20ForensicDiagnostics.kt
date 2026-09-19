package com.oai.geminilivetranslate.ui

/** Temporary, screen-description-only forensic instrumentation. Remove after root cause is isolated. */
object AiStudioWebSessionR20ForensicDiagnostics {
    const val VERSION = "2026-09-19-r20.5-hot-upgrade-output-state"
    const val PREVIOUS_VERSION = "2026-09-19-r20.4-output-turn-state"
    const val LEGACY_VERSION = "2026-09-18-r20.1-redacted-screen-forensic"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  const VERSION='2026-09-19-r20.5-hot-upgrade-output-state';
  const previousForensic=window.__AIS_R20_FORENSIC__||null;
  const previousVersion=previousForensic&&previousForensic.version?String(previousForensic.version):'';
  if(previousVersion===VERSION)return;
  const BODY_CAP=4096;
  const CHUNK=2048;
  const state={xhrSeq:0,fetchSeq:0,bodyChars:0,responseChars:0,lastState:'',lastOutputTurnState:'',installedAt:Date.now()};

  function active(){
    try{
      if(/gemini-3\.8-live/i.test(String(location.href||'')))return true;
      const r=window.__AIS_R19_SCREEN_VIDEO__;
      const d=r&&typeof r.describe==='function'?r.describe():null;
      return !!(d&&d.enabled);
    }catch(_){return false;}
  }
  function now(){try{return Math.round(performance.now()*1000)/1000;}catch(_){return -1;}}
  function stack(){try{return String(new Error('forensic').stack||'').split('\n').slice(1,10).join('\n').slice(0,4000);}catch(_){return '';}}
  function bridge(kind,payload){
    if(!active())return;
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R19_FORENSIC_'+kind,payload:payload||{}}));}catch(_){}
  }
  function secretName(name){return /authorization|cookie|set-cookie|x-goog-api-key|api[-_]?key|access[-_]?token|id[-_]?token|\x24httpheaders|^sid$|^gsessionid$/i.test(String(name||''));}
  function safeHeader(name,value){const v=String(value||'');return secretName(name)?'<redacted chars='+v.length+'>':v.slice(0,12000);}
  function safeUrl(raw){
    try{
      const u=new URL(String(raw||''),location.href);
      const params=[];
      u.searchParams.forEach(function(v,k){
        const raw=String(v||''),sensitive=secretName(k)||/authorization|sapisid|x-goog-api-key/i.test(raw);
        params.push(encodeURIComponent(k)+'='+encodeURIComponent(sensitive?'<redacted chars='+raw.length+'>':raw));
      });
      return u.origin+u.pathname+(params.length?'?'+params.join('&'):'');
    }catch(_){return String(raw||'').slice(0,12000);}
  }
  function relevant(raw){const s=String(raw||'').toLowerCase();return s.indexOf('bidigeneratecontent')>=0||s.indexOf('webchannel')>=0||s.indexOf('/live')>=0||s.indexOf('generativelanguage')>=0;}
  function chunk(kind,id,label,text){
    const s=String(text==null?'':text);const limited=s.slice(0,BODY_CAP);const total=Math.max(1,Math.ceil(limited.length/CHUNK));
    for(let i=0;i<total;i++)bridge(kind,{id:id,label:label,part:i+1,total:total,chars:s.length,truncated:s.length>BODY_CAP,data:limited.slice(i*CHUNK,(i+1)*CHUNK)});
  }
  function bodyText(body){
    try{
      if(body==null)return '';
      if(typeof body==='string')return body;
      if(body instanceof URLSearchParams)return body.toString();
      if(typeof FormData!=='undefined'&&body instanceof FormData){const out=[];body.forEach(function(v,k){out.push(String(k)+'='+(typeof v==='string'?v:'<blob '+String(v&&v.type||'')+' '+Number(v&&v.size||0)+'>'));});return out.join('&');}
      if(body instanceof ArrayBuffer)return new TextDecoder('utf-8').decode(new Uint8Array(body));
      if(ArrayBuffer.isView&&ArrayBuffer.isView(body))return new TextDecoder('utf-8').decode(new Uint8Array(body.buffer,body.byteOffset,body.byteLength));
      return String(body);
    }catch(e){return '<body-read-error '+String(e&&e.message||e||'')+'>';}
  }
  function safeShape(value,depth){
    const d=depth||0;if(d>4)return '<depth>';
    if(value===null)return 'null';
    if(Array.isArray(value)){
      const items=[];for(let i=0;i<value.length&&i<12;i++)items.push(safeShape(value[i],d+1));
      return {type:'array',length:value.length,items:items};
    }
    const t=typeof value;
    if(t==='object'){
      const keys=Object.keys(value).slice(0,20),fields={};
      for(let i=0;i<keys.length;i++)fields[keys[i]]=safeShape(value[keys[i]],d+1);
      return {type:'object',keys:keys,fields:fields};
    }
    if(t==='string')return '<str:'+String(value).length+'>';
    if(t==='number')return '<number>';
    if(t==='boolean')return '<bool>';
    return '<'+t+'>';
  }
  function bodySummary(text){
    const s=String(text==null?'':text);
    const out={chars:s.length,form:false,keys:[],requestPayloads:0,audioPcm:false,imageJpeg:false,model38:false,payloads:[]};
    try{
      const p=new URLSearchParams(s);let seen=0;
      p.forEach(function(v,k){
        if(seen<40)out.keys.push(String(k||'').slice(0,80));
        seen++;
        const key=String(k||''),value=String(v||'');
        if(/^req\d+___data__$/.test(key)){
          out.requestPayloads++;
          const item={key:key,valueChars:value.length,json:false,shape:null};
          if(/audio\/pcm/i.test(value))out.audioPcm=true;
          if(/image\/jpeg/i.test(value))out.imageJpeg=true;
          if(/gemini-3\.8-live/i.test(value))out.model38=true;
          try{const parsed=JSON.parse(value);item.json=true;item.shape=safeShape(parsed,0);}catch(_){}
          if(out.payloads.length<4)out.payloads.push(item);
        }
      });
      out.form=seen>0;
    }catch(_){}
    return out;
  }
  function responseText(xhr){try{if(xhr.responseType===''||xhr.responseType==='text')return String(xhr.responseText||'');if(xhr.responseType==='json')return JSON.stringify(xhr.response);return '';}catch(_){return '';}}
  function headersText(xhr){try{return String(xhr.getAllResponseHeaders()||'');}catch(_){return '';}}
  function env(){
    let conn={};try{const c=navigator.connection||navigator.mozConnection||navigator.webkitConnection;if(c)conn={effectiveType:c.effectiveType||'',downlink:c.downlink||0,rtt:c.rtt||0,saveData:!!c.saveData};}catch(_){}
    let uaData={};try{if(navigator.userAgentData)uaData={mobile:!!navigator.userAgentData.mobile,platform:String(navigator.userAgentData.platform||''),brands:navigator.userAgentData.brands||[]};}catch(_){}
    return {href:safeUrl(location.href),origin:String(location.origin||''),readyState:document.readyState,visibility:document.visibilityState,online:navigator.onLine,secure:window.isSecureContext,ua:String(navigator.userAgent||''),platform:String(navigator.platform||''),languages:navigator.languages||[],uaData:uaData,hardwareConcurrency:Number(navigator.hardwareConcurrency||0),deviceMemory:Number(navigator.deviceMemory||0),inner:[window.innerWidth,window.innerHeight],screen:[screen.width,screen.height],dpr:Number(window.devicePixelRatio||1),connection:conn,hasGum:!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia),hasDisplay:!!(navigator.mediaDevices&&navigator.mediaDevices.getDisplayMedia),t:Date.now(),p:now()};
  }
  function trackInfo(track){
    if(!track)return null;
    let settings={},constraints={},capabilities={};
    try{settings=track.getSettings?track.getSettings():{};}catch(_){}
    try{constraints=track.getConstraints?track.getConstraints():{};}catch(_){}
    try{capabilities=track.getCapabilities?track.getCapabilities():{};}catch(_){}
    return {kind:String(track.kind||''),id:String(track.id||''),label:String(track.label||''),enabled:!!track.enabled,muted:!!track.muted,readyState:String(track.readyState||''),settings:settings,constraints:constraints,capabilities:capabilities};
  }
  function watchTrack(track,source){
    if(!track||track.__aisR20Watched)return;track.__aisR20Watched=true;
    bridge('MEDIA_TRACK',{source:source,event:'created',track:trackInfo(track),t:Date.now(),p:now()});
    ['mute','unmute','ended'].forEach(function(name){try{track.addEventListener(name,function(){bridge('MEDIA_TRACK',{source:source,event:name,track:trackInfo(track),t:Date.now(),p:now()});});}catch(_){}});
  }
  function watchStream(stream,source){try{const tracks=stream&&stream.getTracks?stream.getTracks():[];bridge('MEDIA_STREAM',{source:source,id:String(stream&&stream.id||''),active:!!(stream&&stream.active),tracks:tracks.map(trackInfo),t:Date.now(),p:now()});tracks.forEach(function(t){watchTrack(t,source);});}catch(_){} }
  function resources(reason){
    try{
      const list=performance.getEntriesByType('resource').filter(function(e){return relevant(e.name);}).slice(-20).map(function(e){return {name:safeUrl(e.name),initiatorType:e.initiatorType,startTime:e.startTime,duration:e.duration,fetchStart:e.fetchStart,requestStart:e.requestStart,responseStart:e.responseStart,responseEnd:e.responseEnd,transferSize:e.transferSize,encodedBodySize:e.encodedBodySize,decodedBodySize:e.decodedBodySize,nextHopProtocol:e.nextHopProtocol};});
      bridge('RESOURCE_TIMING',{reason:reason,entries:list,t:Date.now(),p:now()});
    }catch(e){bridge('RESOURCE_TIMING_ERROR',{reason:reason,message:String(e&&e.message||e||'')});}
  }

  try{
    const X=window.XMLHttpRequest;
    if(X&&X.prototype&&!X.prototype.__aisR20Forensic){
      const p=X.prototype,nOpen=p.open,nSend=p.send,nAbort=p.abort,nSet=p.setRequestHeader;
      p.open=function(method,url,async,user){
        const id=++state.xhrSeq;this.__aisR20={id:id,method:String(method||'GET'),url:String(url||''),relevant:relevant(url),openedAt:Date.now(),openedP:now(),lastRespChars:0,headers:[],firstResponseLogged:false,firstResponseChars:0};
        if(this.__aisR20.relevant){bridge('XHR_OPEN',{id:id,method:this.__aisR20.method,url:safeUrl(url),async:async!==false,userProvided:!!user,t:Date.now(),p:now()});}
        return nOpen.apply(this,arguments);
      };
      p.setRequestHeader=function(name,value){try{const m=this.__aisR20;if(m&&m.relevant)m.headers.push([String(name||''),safeHeader(name,value)]);}catch(_){}return nSet.apply(this,arguments);};
      p.send=function(body){
        const xhr=this,m=xhr.__aisR20;
        if(m&&m.relevant){
          const text=bodyText(body);state.bodyChars+=text.length;
          bridge('XHR_SEND',{id:m.id,method:m.method,url:safeUrl(m.url),bodyChars:text.length,headerCount:m.headers.length,timeout:Number(xhr.timeout||0),withCredentials:!!xhr.withCredentials,responseType:String(xhr.responseType||''),t:Date.now(),p:now()});
          bridge('XHR_REQUEST_BODY_SUMMARY',Object.assign({id:m.id},bodySummary(text)));
          const captureHandshake=function(event){
            try{
              if(m.firstResponseLogged)return;
              const rs=Number(xhr.readyState||0);if(rs<3)return;
              const textNow=responseText(xhr);if(!textNow)return;
              m.firstResponseLogged=true;m.firstResponseChars=textNow.length;
              state.responseChars+=Math.min(textNow.length,BODY_CAP);
              bridge('XHR_HANDSHAKE_RESPONSE_META',{
                id:m.id,event:event,readyState:rs,status:Number(xhr.status||0),
                chars:textNow.length,elapsedMs:Date.now()-m.openedAt,url:safeUrl(m.url)
              });
              chunk('XHR_HANDSHAKE_RESPONSE',m.id,'first-response',textNow);
            }catch(e){bridge('XHR_HANDSHAKE_CAPTURE_ERROR',{id:m.id,event:event,message:String(e&&e.message||e||'')});}
          };
          const snap=function(event){
            let status=0,statusText='',rs=0,responseURL='';try{status=Number(xhr.status||0);statusText=String(xhr.statusText||'');rs=Number(xhr.readyState||0);responseURL=String(xhr.responseURL||'');}catch(_){}
            captureHandshake(event);
            const failed=event==='error'||event==='timeout'||(status>=400)||(status===0&&event!=='loadend');
            bridge('XHR_EVENT',{id:m.id,event:event,readyState:rs,status:status,statusText:statusText,responseURL:safeUrl(responseURL),elapsedMs:Date.now()-m.openedAt,failed:failed,t:Date.now(),p:now()});
            if(failed&&!m.responseHeadersLogged){m.responseHeadersLogged=true;chunk('XHR_RESPONSE_HEADERS',m.id,'headers',headersText(xhr));}
            if(failed){const textNow=responseText(xhr);if(textNow.length>m.lastRespChars){const delta=textNow.slice(m.lastRespChars);m.lastRespChars=textNow.length;state.responseChars+=delta.length;chunk('XHR_RESPONSE_DELTA',m.id,'terminal',delta);}resources('xhr-'+event+'-'+m.id);}
          };
          try{xhr.addEventListener('readystatechange',function(){captureHandshake('readystatechange');});}catch(_){}
          try{xhr.addEventListener('progress',function(){captureHandshake('progress');});}catch(_){}
          ['error','timeout','abort','loadend'].forEach(function(name){try{xhr.addEventListener(name,function(){snap(name);});}catch(_){}});
        }
        return nSend.apply(this,arguments);
      };
      p.abort=function(){const m=this.__aisR20;if(m&&m.relevant){const rs=Number(this.readyState||0),status=(function(x){try{return Number(x.status||0);}catch(_){return 0;}})(this);if(rs<4||status===0||status>=400){bridge('XHR_ABORT_CALL',{id:m.id,method:m.method,url:safeUrl(m.url),readyState:rs,status:status,stack:stack(),t:Date.now(),p:now()});resources('xhr-abort-call-'+m.id);}}return nAbort.apply(this,arguments);};
      p.__aisR20Forensic=true;
    }
  }catch(e){bridge('HOOK_ERROR',{target:'xhr',message:String(e&&e.message||e||''),stack:String(e&&e.stack||'')});}

  try{
    const nFetch=window.fetch;
    if(typeof nFetch==='function'&&!nFetch.__aisR20Forensic){
      const wFetch=function(input,init){
        const id=++state.fetchSeq;let url='',method='GET',body=null,headers=null;
        try{url=typeof input==='string'?input:String(input&&input.url||'');method=String(init&&init.method||input&&input.method||'GET');body=init&&Object.prototype.hasOwnProperty.call(init,'body')?init.body:null;headers=init&&init.headers||input&&input.headers||null;}catch(_){}
        const rel=relevant(url);
        if(rel){const text=bodyText(body);bridge('FETCH_START',{id:id,method:method,url:safeUrl(url),bodyChars:text.length,bodySummary:bodySummary(text),stack:stack(),t:Date.now(),p:now()});try{const h=new Headers(headers||{});h.forEach(function(v,k){bridge('FETCH_REQUEST_HEADER',{id:id,name:k,value:safeHeader(k,v)});});}catch(_){} }
        return nFetch.apply(this,arguments).then(function(resp){if(rel){const status=Number(resp.status||0);bridge('FETCH_RESPONSE',{id:id,status:status,statusText:String(resp.statusText||''),url:safeUrl(resp.url||url),type:String(resp.type||''),redirected:!!resp.redirected,t:Date.now(),p:now()});if(status>=400){try{const hs=[];resp.headers.forEach(function(v,k){hs.push(k+': '+safeHeader(k,v));});chunk('FETCH_RESPONSE_HEADERS',id,'headers',hs.join('\n'));}catch(_){}try{resp.clone().text().then(function(text){state.responseChars+=String(text||'').length;chunk('FETCH_RESPONSE_BODY',id,'response',text);resources('fetch-response-error-'+id);}).catch(function(e){bridge('FETCH_BODY_ERROR',{id:id,message:String(e&&e.message||e||'')});});}catch(_){}} }return resp;},function(err){if(rel){bridge('FETCH_REJECT',{id:id,name:String(err&&err.name||''),message:String(err&&err.message||err||''),stack:String(err&&err.stack||''),t:Date.now(),p:now()});resources('fetch-reject-'+id);}throw err;});
      };
      wFetch.__aisR20Forensic=true;window.fetch=wFetch;
    }
  }catch(e){bridge('HOOK_ERROR',{target:'fetch',message:String(e&&e.message||e||''),stack:String(e&&e.stack||'')});}

  try{
    const AC=window.AbortController;
    if(AC&&AC.prototype&&typeof AC.prototype.abort==='function'&&!AC.prototype.abort.__aisR20Forensic){
      const nAbort=AC.prototype.abort;const w=function(reason){if(active())bridge('ABORT_CONTROLLER',{reason:String(reason&&reason.message||reason||''),stack:stack(),t:Date.now(),p:now()});return nAbort.apply(this,arguments);};w.__aisR20Forensic=true;AC.prototype.abort=w;
    }
  }catch(_){}

  try{
    const md=navigator.mediaDevices;
    if(md&&typeof md.getUserMedia==='function'&&!md.getUserMedia.__aisR20Forensic){const n=md.getUserMedia.bind(md);const w=function(c){if(active())bridge('GUM_CALL',{constraints:c||{},stack:stack(),t:Date.now(),p:now()});return n(c).then(function(s){if(active())watchStream(s,'gum');return s;},function(e){if(active())bridge('GUM_REJECT',{name:String(e&&e.name||''),message:String(e&&e.message||e||''),stack:String(e&&e.stack||''),t:Date.now(),p:now()});throw e;});};w.__aisR20Forensic=true;md.getUserMedia=w;}
    if(md&&typeof md.getDisplayMedia==='function'&&!md.getDisplayMedia.__aisR20Forensic){const n=md.getDisplayMedia.bind(md);const w=function(c){if(active())bridge('DISPLAY_CALL',{constraints:c||{},stack:stack(),t:Date.now(),p:now()});return n(c).then(function(s){if(active())watchStream(s,'display');return s;},function(e){if(active())bridge('DISPLAY_REJECT',{name:String(e&&e.name||''),message:String(e&&e.message||e||''),stack:String(e&&e.stack||''),t:Date.now(),p:now()});throw e;});};w.__aisR20Forensic=true;md.getDisplayMedia=w;}
    const T=window.MediaStreamTrack;if(T&&T.prototype&&typeof T.prototype.stop==='function'&&!T.prototype.stop.__aisR20Forensic){const n=T.prototype.stop;const w=function(){if(active())bridge('TRACK_STOP_CALL',{track:trackInfo(this),stack:stack(),t:Date.now(),p:now()});return n.apply(this,arguments);};w.__aisR20Forensic=true;T.prototype.stop=w;}
  }catch(e){bridge('HOOK_ERROR',{target:'media',message:String(e&&e.message||e||''),stack:String(e&&e.stack||'')});}

  try{
    const names=['error','warn'];names.forEach(function(name){const n=console&&console[name];if(typeof n!=='function'||n.__aisR20Forensic)return;const w=function(){if(active()){const args=[];for(let i=0;i<arguments.length&&i<12;i++){try{args.push(typeof arguments[i]==='string'?arguments[i]:JSON.stringify(arguments[i]));}catch(_){args.push(String(arguments[i]));}}chunk('CONSOLE',0,name,args.join(' | '));}return n.apply(this,arguments);};w.__aisR20Forensic=true;console[name]=w;});
  }catch(_){}

  try{window.addEventListener('error',function(ev){if(active()){bridge('WINDOW_ERROR',{message:String(ev&&ev.message||''),source:safeUrl(ev&&ev.filename||''),line:Number(ev&&ev.lineno||0),column:Number(ev&&ev.colno||0),name:String(ev&&ev.error&&ev.error.name||''),stack:String(ev&&ev.error&&ev.error.stack||''),t:Date.now(),p:now()});resources('window-error');}},true);}catch(_){}
  try{window.addEventListener('unhandledrejection',function(ev){if(active()){const r=ev&&ev.reason;bridge('UNHANDLED_REJECTION',{name:String(r&&r.name||''),message:String(r&&r.message||r||''),stack:String(r&&r.stack||''),t:Date.now(),p:now()});resources('unhandled-rejection');}},true);}catch(_){}
  ['visibilitychange','pageshow','pagehide','online','offline','beforeunload'].forEach(function(name){try{window.addEventListener(name,function(){if(active())bridge('PAGE_LIFECYCLE',{event:name,readyState:document.readyState,visibility:document.visibilityState,online:navigator.onLine,href:safeUrl(location.href),t:Date.now(),p:now()});},true);}catch(_){}});

  function snapshot(){
    if(!active())return;
    let r21=null,r17=null,r19=null,r16=null,r14=null;
    try{r21=window.__AIS_R21_DESKTOP_SHARE__&&window.__AIS_R21_DESKTOP_SHARE__.describe?window.__AIS_R21_DESKTOP_SHARE__.describe():null;}catch(_){}
    try{r17=window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.describe?window.__AIS_R17_PRODUCTION__.describe():null;}catch(_){}
    try{r19=window.__AIS_R19_SCREEN_VIDEO__&&window.__AIS_R19_SCREEN_VIDEO__.describe?window.__AIS_R19_SCREEN_VIDEO__.describe():null;}catch(_){}
    try{r16=window.__AIS_LIVE_OUTPUT_ENGINE__&&window.__AIS_LIVE_OUTPUT_ENGINE__.describe?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null;}catch(_){}
    try{r14=window.__AIS_LIVE_DIRECT_ENGINE__&&window.__AIS_LIVE_DIRECT_ENGINE__.describe?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null;}catch(_){}
    const s=JSON.stringify({r21:r21,r17:r17,r19:r19,r16:r16,r14:r14});if(s!==state.lastState){state.lastState=s;chunk('STATE_SNAPSHOT',0,'state',s);}
    const compact={
      frame:{
        drawn:Number(r19&&r19.framesDrawn||0),
        nativeSeq:Number(r19&&r19.lastNativeFrameSeq||0),
        visualHash:String(r19&&r19.visualHash||''),
        visualChanges:Number(r19&&r19.visualChanges||0),
        driverInitial:Number(r19&&r19.turnDriverInitialQueues||0),
        driverRecurring:Number(r19&&r19.turnDriverRecurringQueues||0),
        driverCoalesced:Number(r19&&r19.turnDriverCoalesced||0),
        driverSkips:Number(r19&&r19.turnDriverSkips||0),
        driverReason:String(r19&&r19.lastTurnDriverReason||''),
        driverAgeMs:Number(r19&&r19.lastTurnDriverAgeMs||-1)
      },
      turn:{
        enabled:!!(r14&&r14.screenHeartbeatEnabled),
        setup:!!(r14&&r14.screenSetupComplete),
        pending:!!(r14&&r14.screenHeartbeatPending),
        pendingTextChars:Number(r14&&r14.screenHeartbeatPendingTextChars||0),
        inFlight:!!(r14&&r14.screenTurnInFlight),
        queued:Number(r14&&r14.screenHeartbeatsQueued||0),
        coalesced:Number(r14&&r14.screenHeartbeatsCoalesced||0),
        overrides:Number(r14&&r14.screenHeartbeatOverrides||0),
        injected:Number(r14&&r14.screenHeartbeatsInjected||0),
        audioEnds:Number(r14&&r14.screenAudioStreamEndsInjected||0),
        completes:Number(r14&&r14.screenTurnCompletes||0),
        stalls:Number(r14&&r14.screenTurnStallWarnings||0),
        lastHeartbeatAgeMs:Number(r14&&r14.lastScreenHeartbeatAgeMs||-1)
      },
      output:{
        browserChunks:Number(r16&&r16.browserChunks||0),
        parsedChunks:Number(r16&&r16.parsedChunks||0),
        serverMessages:Number(r16&&r16.jspbServerMessages||0),
        serverContent:Number(r16&&r16.jspbServerContentMessages||0),
        audioChunks:Number(r16&&r16.audioChunks||0),
        audioPayloadChars:Number(r16&&r16.audioPayloadChars||0),
        outputTranscript:Number(r16&&r16.outputTranscriptEvents||0),
        modelText:Number(r16&&r16.modelTextEvents||0),
        generationComplete:Number(r16&&r16.generationCompleteEvents||0),
        turnComplete:Number(r16&&r16.turnCompleteEvents||0),
        interrupted:Number(r16&&r16.interruptedEvents||0),
        bridgeErrors:Number(r16&&r16.bridgeErrors||0),
        lastAudioAgeMs:Number(r16&&r16.lastAudioAgeMs||-1)
      }
    };
    const cs=JSON.stringify(compact);
    if(cs!==state.lastOutputTurnState){state.lastOutputTurnState=cs;bridge('OUTPUT_TURN_STATE',compact);}
  }

  window.__AIS_R20_FORENSIC__={version:VERSION,describe:function(){return {ok:true,version:VERSION,active:active(),xhrSeq:state.xhrSeq,fetchSeq:state.fetchSeq,bodyChars:state.bodyChars,responseChars:state.responseChars,ageMs:Date.now()-state.installedAt};},dumpResources:function(){resources('manual');return true;}};
  if(active()){
    if(previousVersion)bridge('ENGINE_UPGRADE',{from:previousVersion,to:VERSION});
    bridge('INSTALL',{version:VERSION,mode:'screen-description-only',bodyCap:BODY_CAP,chunkChars:CHUNK,warning:'handshake-plus-output-turn-state-redacted-forensic-logging'});bridge('ENVIRONMENT',env());resources('install');
  }
  setInterval(snapshot,2000);
})();
    """.trimIndent()
}
