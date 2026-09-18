package com.oai.geminilivetranslate.ui

object AiStudioWebSessionR19ScreenVideoBridge {
    const val VERSION = "2026-09-18-r19.13-causal-frame-trace"
    const val PREVIOUS_VERSION = "2026-09-18-r19.12-frame-heartbeat"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R19_SCREEN_VIDEO__&&window.__AIS_R19_SCREEN_VIDEO__.version)return;

  const VERSION='2026-09-18-r19.13-causal-frame-trace';
  const CAMERA_POST_START_MIN_MS=350;
  const CAMERA_RETRY_NO_GUM_MS=3000;
  const MAX_CAMERA_TAP_ATTEMPTS=2;
  const MIC_PERMISSION_TIMEOUT_MS=15000;
  const PAGE_ERROR_SCAN_MS=900;
  const state={
    enabled:false,
    allowRealMicInput:false,
    canvas:null,
    ctx:null,
    videoStream:null,
    videoTrack:null,
    gumVideoRequests:0,
    gumCombinedRequests:0,
    displayRequests:0,
    realAudioRequests:0,
    realAudioTracks:0,
    realAudioErrors:0,
    silentAudioRequests:0,
    silentAudioTracks:0,
    silentAudioErrors:0,
    silentAudioContext:null,
    silentAudioSource:null,
    silentAudioGain:null,
    silentAudioStream:null,
    micPermissionRequests:0,
    micPermissionTimeouts:0,
    videoClonesCreated:0,
    videoClonesEnded:0,
    masterTrackEnds:0,
    framePushCalls:0,
    frameRejectDisabled:0,
    frameRejectEmpty:0,
    frameRejectTooLarge:0,
    frameRejectVideoUnavailable:0,
    frameDecodeStarts:0,
    frameDecodeSuccess:0,
    frameRequestAttempts:0,
    frameRequestErrors:0,
    heartbeatAttempts:0,
    heartbeatErrors:0,
    lastNativeFrameSeq:0,
    framesQueued:0,
    framesDrawn:0,
    frameErrors:0,
    staleFrameDrops:0,
    cameraScans:0,
    cameraCandidates:0,
    cameraClicks:0,
    lastCameraClickAt:0,
    lastCameraLabel:'',
    cameraTransportReady:false,
    cameraTransportReadyAt:0,
    cameraGateScans:0,
    cameraGateReason:'waiting-start-live',
    cameraBlockedByPageError:false,
    cameraNoGumReported:false,
    cameraRetries:0,
    cameraRetryExhaustedReported:false,
    startObservedAt:0,
    startAttemptSeen:0,
    startNetworkBaseline:0,
    startPositiveNetworkBaseline:0,
    startRealAudioBaseline:0,
    postStartProgress:false,
    postStartProgressKind:'none',
    postStartProgressAt:0,
    pageErrorCount:0,
    lastPageError:'',
    lastPageErrorAt:0,
    networkEvents:0,
    positiveNetworkEvents:0,
    lastNetworkStatus:0,
    lastNetworkReadyState:0,
    lastNetworkPath:'',
    lastNetworkError:'',
    lastNetworkResponseError:'',
    lastNetworkAt:0,
    lastFrameAt:0,
    lastDrawSeq:0,
    configuredAt:0
  };
  const pageErrorSignatures=new Map();

  function diag(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R19_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safe(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||240);}
  function redact(v,n){return safe(v,n||900).replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/ig,'<email>');}
  function tag(el){try{return String(el&&el.tagName||'').toUpperCase();}catch(_){return '';}}
  function attr(el,name){try{return el&&el.getAttribute?safe(el.getAttribute(name)||'',180):'';}catch(_){return '';}}
  function role(el){return attr(el,'role').toLowerCase();}
  function label(el){try{return safe([attr(el,'aria-label'),attr(el,'title'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safe(el&&el.textContent||'',260)].filter(Boolean).join(' '),520).toLowerCase();}catch(_){return '';}}
  function interactive(el){const t=tag(el),r=role(el);return t==='BUTTON'||t==='A'||r==='button'||r==='menuitem'||r==='tab'||r==='link';}

  function collectDeep(){
    const roots=[document],seen=new Set(),all=[],interactiveNodes=[];
    while(roots.length&&all.length<6000){
      const root=roots.shift();if(!root||seen.has(root))continue;seen.add(root);
      let nodes=[];try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&all.length<6000;i++){
        const el=nodes[i];all.push(el);if(interactive(el))interactiveNodes.push(el);
        try{if(el.shadowRoot)roots.push(el.shadowRoot);}catch(_){}
        try{const t=tag(el);if((t==='IFRAME'||t==='FRAME')&&el.contentDocument)roots.push(el.contentDocument);}catch(_){}
      }
    }
    return {all:all,interactive:interactiveNodes};
  }

  function visible(el){
    try{
      if(!el||!el.getBoundingClientRect)return false;
      const s=(el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView:window).getComputedStyle(el);
      if(!s||s.display==='none'||s.visibility==='hidden'||Number(s.opacity||1)===0)return false;
      const r=el.getBoundingClientRect();return r.width>1&&r.height>1&&r.bottom>=0&&r.right>=0;
    }catch(_){return false;}
  }

  function compactErrorText(text){
    const clean=redact(text,900);
    const patterns=[
      /connection failed/ig,
      /something went wrong\.?/ig,
      /permission denied/ig,
      /permission blocked/ig,
      /not supported/ig,
      /unsupported/ig,
      /try again/ig,
      /could not connect/ig,
      /unable to connect/ig
    ];
    for(let i=0;i<patterns.length;i++){
      const m=clean.match(patterns[i]);if(m&&m.length)return safe(m[0],220);
    }
    return clean.length<=320?clean:safe(clean,320);
  }

  function recordPageError(kind,text,extra){
    const clean=compactErrorText(text);if(!state.enabled||!clean)return;
    const sig=kind+'|'+clean.toLowerCase();const now=Date.now();const previous=Number(pageErrorSignatures.get(sig)||0);
    if(previous&&now-previous<10000)return;
    pageErrorSignatures.set(sig,now);state.pageErrorCount++;state.lastPageError=clean;state.lastPageErrorAt=now;
    if(state.startObservedAt>0)state.cameraBlockedByPageError=true;
    diag(kind,Object.assign({count:state.pageErrorCount,text:clean,cameraBlocked:state.cameraBlockedByPageError},extra||{}));
  }

  function scanPageErrors(snapshot){
    if(!state.enabled)return;
    const all=(snapshot&&snapshot.all)||collectDeep().all;
    for(let i=0;i<all.length;i++){
      const el=all[i];if(!visible(el))continue;
      const r=role(el),id=attr(el,'id'),cls=attr(el,'class'),testid=attr(el,'data-testid');
      const marker=(r+' '+id+' '+cls+' '+testid).toLowerCase();
      const text=redact(el.textContent||attr(el,'aria-label')||'',900);if(!text||text.length<3)continue;
      const lower=text.toLowerCase();
      const errorWords=/\b(error|failed|failure|unavailable|unsupported|denied|blocked|permission|problem|could not|couldn't|cannot|can't|try again|something went wrong)\b|lỗi|thất bại|không thể|bị chặn|quyền/;
      const errorContainer=r==='alert'||/error|snackbar|toast|alert|banner|notification/.test(marker);
      if(errorContainer&&errorWords.test(lower)){
        recordPageError('PAGE_ERROR_UI',text,{tag:tag(el),role:r||'none',id:safe(id,120),className:safe(cls,180),testId:safe(testid,160),rawChars:String(text).length});
      }
    }
  }

  function safeNetworkPath(url){
    try{
      const u=new URL(String(url||''),location.href);
      return safe((u.host===location.host?'':u.host)+u.pathname,300);
    }catch(_){return safe(String(url||'').split('?')[0],300);}
  }
  function networkRelevant(url){
    const p=safeNetworkPath(url).toLowerCase();
    return p.indexOf('bidigeneratecontent')>=0||p.indexOf('/live')>=0||p.indexOf('streamgenerate')>=0||p.indexOf('generativelanguage')>=0;
  }
  function extractErrorObject(node,depth){
    const d=depth||0;if(d>8||node==null)return null;
    if(Array.isArray(node)){for(let i=0;i<node.length&&i<24;i++){const found=extractErrorObject(node[i],d+1);if(found)return found;}return null;}
    if(typeof node!=='object')return null;
    if(Object.prototype.hasOwnProperty.call(node,'error')){
      const e=node.error;
      if(typeof e==='string')return {message:safe(e,240)};
      if(e&&typeof e==='object')return {code:safe(e.code||'',60),status:safe(e.status||'',100),message:redact(e.message||e.reason||'',320)};
    }
    const keys=Object.keys(node);for(let i=0;i<keys.length&&i<40;i++){const found=extractErrorObject(node[keys[i]],d+1);if(found)return found;}
    return null;
  }
  function responseErrorSummary(xhr){
    try{
      if(!xhr||Number(xhr.readyState||0)<4)return '';
      let text='';try{text=String(xhr.responseText||'');}catch(_){return '';}
      if(!text||text.length>200000)return '';
      let parsed=null;try{parsed=JSON.parse(text);}catch(_){return '';}
      const e=extractErrorObject(parsed,0);if(!e)return '';
      return safe(['code='+safe(e.code||'',60),'status='+safe(e.status||'',100),'message='+redact(e.message||'',320)].filter(function(v){return v&&v.indexOf('=')<v.length-1;}).join(' '),520);
    }catch(_){return '';}
  }
  function observeStart(){
    try{
      const r=window.__AIS_R17_PRODUCTION__;const d=r&&typeof r.describe==='function'?r.describe():null;
      if(!d||!d.configured)return {started:false,age:-1,setup:false};
      const attempts=Number(d.startAttempts||0),action=String(d.lastAction||''),stage=String(d.stage||''),age=Number(d.lastActionAgeMs||-1);
      const started=attempts>0&&(action==='start-live'||stage==='start-clicked'||age>=0)||!!d.setupObserved;
      if(started&&state.startObservedAt<=0){
        state.startObservedAt=Date.now();state.startAttemptSeen=attempts;state.startNetworkBaseline=state.networkEvents;state.startPositiveNetworkBaseline=state.positiveNetworkEvents;state.startRealAudioBaseline=state.realAudioRequests;
        diag('START_OBSERVED',{attempts:attempts,action:action,stage:stage,ageMs:age,networkBaseline:state.startNetworkBaseline,positiveNetworkBaseline:state.startPositiveNetworkBaseline,realAudioBaseline:state.startRealAudioBaseline});
      }else if(started){state.startAttemptSeen=Math.max(state.startAttemptSeen,attempts);}
      return {started:started,age:age,setup:!!d.setupObserved,action:action,stage:stage};
    }catch(_){return {started:false,age:-1,setup:false};}
  }
  function markPostStartProgress(kind,extra){
    const start=observeStart();if(!start.started)return;
    if(!state.postStartProgress){
      state.postStartProgress=true;state.postStartProgressKind=String(kind||'unknown');state.postStartProgressAt=Date.now();
      diag('POST_START_PROGRESS',Object.assign({kind:state.postStartProgressKind,startAgeMs:state.startObservedAt?Date.now()-state.startObservedAt:-1},extra||{}));
    }
  }
  function recordNetwork(kind,url,status,error,extra){
    if(!state.enabled||!networkRelevant(url))return;
    const path=safeNetworkPath(url),code=Number(status||0),err=safe(error||'',220),readyState=Number(extra&&extra.readyState||0),responseError=safe(extra&&extra.responseError||'',520);
    state.networkEvents++;if(code>=200&&code<400)state.positiveNetworkEvents++;
    state.lastNetworkStatus=code;state.lastNetworkReadyState=readyState;state.lastNetworkPath=path;state.lastNetworkError=err;state.lastNetworkResponseError=responseError;state.lastNetworkAt=Date.now();
    if(code>=200&&code<400)markPostStartProgress('network-http-'+code,{path:path,readyState:readyState});
    diag(kind,Object.assign({count:state.networkEvents,positiveCount:state.positiveNetworkEvents,path:path,status:code,readyState:readyState,error:err,responseError:responseError},extra||{}));
  }

  function installTransportDiagnostics(){
    try{
      const X=window.XMLHttpRequest;
      if(X&&X.prototype&&typeof X.prototype.open==='function'&&!X.prototype.open.__aisR19Network){
        const p=X.prototype,nativeOpen=p.open,nativeSend=p.send;
        const wrappedOpen=function(method,url){try{this.__aisR19Method=String(method||'GET');this.__aisR19Url=String(url||'');}catch(_){}return nativeOpen.apply(this,arguments);};
        wrappedOpen.__aisR19Network=true;p.open=wrappedOpen;
        p.send=function(){
          try{
            if(networkRelevant(this.__aisR19Url)&&!this.__aisR19Observed){
              this.__aisR19Observed=true;
              const xhr=this;let lastReadyState=-1;
              xhr.addEventListener('readystatechange',function(){
                const rs=Number(xhr.readyState||0);if(rs<2||rs===lastReadyState)return;lastReadyState=rs;
                const responseError=rs===4?responseErrorSummary(xhr):'';
                recordNetwork('NETWORK_XHR_STATE',xhr.__aisR19Url,xhr.status,'',{method:safe(xhr.__aisR19Method,16),readyState:rs,responseError:responseError});
              });
              xhr.addEventListener('loadend',function(){recordNetwork('NETWORK_XHR',xhr.__aisR19Url,xhr.status,'',{method:safe(xhr.__aisR19Method,16),readyState:Number(xhr.readyState||0),responseError:responseErrorSummary(xhr)});});
              xhr.addEventListener('error',function(){recordNetwork('NETWORK_XHR_ERROR',xhr.__aisR19Url,xhr.status,'xhr-error',{method:safe(xhr.__aisR19Method,16),readyState:Number(xhr.readyState||0),responseError:responseErrorSummary(xhr)});});
              xhr.addEventListener('abort',function(){recordNetwork('NETWORK_XHR_ERROR',xhr.__aisR19Url,xhr.status,'xhr-abort',{method:safe(xhr.__aisR19Method,16),readyState:Number(xhr.readyState||0),responseError:responseErrorSummary(xhr)});});
              xhr.addEventListener('timeout',function(){recordNetwork('NETWORK_XHR_ERROR',xhr.__aisR19Url,xhr.status,'xhr-timeout',{method:safe(xhr.__aisR19Method,16),readyState:Number(xhr.readyState||0),responseError:responseErrorSummary(xhr)});});
            }
          }catch(_){}
          return nativeSend.apply(this,arguments);
        };
      }
    }catch(e){diag('NETWORK_HOOK_ERROR',{target:'xhr',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}
    try{
      const nativeFetch=window.fetch;
      if(typeof nativeFetch==='function'&&!nativeFetch.__aisR19Network){
        const wrappedFetch=function(input,init){
          let url='';try{url=typeof input==='string'?input:String(input&&input.url||'');}catch(_){}
          const method=safe(init&&init.method||'GET',16);
          return nativeFetch.apply(this,arguments).then(function(resp){
            const status=Number(resp&&resp.status||0);recordNetwork('NETWORK_FETCH',url,status,'',{method:method,readyState:4});
            if(status>=400&&resp&&typeof resp.clone==='function'){
              try{resp.clone().text().then(function(text){let summary='';try{const parsed=JSON.parse(String(text||''));const e=extractErrorObject(parsed,0);if(e)summary=safe(['code='+safe(e.code||'',60),'status='+safe(e.status||'',100),'message='+redact(e.message||'',320)].filter(function(v){return v&&v.indexOf('=')<v.length-1;}).join(' '),520);}catch(_){}if(summary)recordNetwork('NETWORK_FETCH_BODY_ERROR',url,status,'',{method:method,readyState:4,responseError:summary});}).catch(function(){});}catch(_){}
            }
            return resp;
          },function(err){recordNetwork('NETWORK_FETCH_ERROR',url,0,String(err&&err.message||err||'fetch-error'),{method:method,readyState:0});throw err;});
        };
        wrappedFetch.__aisR19Network=true;window.fetch=wrappedFetch;
      }
    }catch(e){diag('NETWORK_HOOK_ERROR',{target:'fetch',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}
  }

  function installPageDiagnostics(){
    try{
      if(window.__AIS_R19_PAGE_DIAGNOSTICS__)return;
      window.__AIS_R19_PAGE_DIAGNOSTICS__=true;
      window.addEventListener('error',function(ev){
        if(!state.enabled)return;
        recordPageError('PAGE_JS_ERROR',String(ev&&ev.message||'javascript-error'),{source:safe(ev&&ev.filename||'',300),line:Number(ev&&ev.lineno||0),column:Number(ev&&ev.colno||0)});
      },true);
      window.addEventListener('unhandledrejection',function(ev){
        if(!state.enabled)return;
        const reason=ev&&ev.reason;recordPageError('PAGE_UNHANDLED_REJECTION',String(reason&&reason.message||reason||'unhandled-rejection'),{name:safe(reason&&reason.name||'',120),stack:safe(reason&&reason.stack||'',900)});
      },true);
      try{
        const nativeAlert=window.alert;
        if(typeof nativeAlert==='function'&&!nativeAlert.__aisR19Logged){
          const wrappedAlert=function(message){recordPageError('PAGE_ALERT',String(message||''),{});return nativeAlert.apply(this,arguments);};
          wrappedAlert.__aisR19Logged=true;window.alert=wrappedAlert;
        }
      }catch(_){}
      installTransportDiagnostics();
      diag('PAGE_DIAGNOSTICS_INSTALLED',{networkDiagnostics:true,readyStateDiagnostics:true,responseErrorOnly:true,redacted:true});
    }catch(e){diag('PAGE_DIAGNOSTICS_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',300)});}
  }

  function ensureVideo(){
    if(state.videoTrack&&state.videoTrack.readyState!=='ended')return true;
    try{
      const canvas=document.createElement('canvas');canvas.width=573;canvas.height=1280;
      const ctx=canvas.getContext('2d',{alpha:false});if(!ctx)throw new Error('2d-context-unavailable');
      ctx.fillStyle='#000';ctx.fillRect(0,0,canvas.width,canvas.height);
      const capture=canvas.captureStream||canvas.mozCaptureStream;
      if(typeof capture!=='function')throw new Error('canvas-captureStream-unavailable');
      const stream=capture.call(canvas,1);const track=stream&&stream.getVideoTracks?stream.getVideoTracks()[0]:null;
      if(!track)throw new Error('canvas-video-track-unavailable');
      state.canvas=canvas;state.ctx=ctx;state.videoStream=stream;state.videoTrack=track;
      try{track.addEventListener('ended',function(){state.masterTrackEnds++;state.cameraTransportReady=false;diag('MASTER_TRACK_ENDED',{count:state.masterTrackEnds,readyState:String(track.readyState||'')});},{once:true});}catch(_){}
      diag('VIDEO_TRACK_READY',{width:canvas.width,height:canvas.height,readyState:String(track.readyState||''),requestFrame:typeof track.requestFrame==='function'});
      return true;
    }catch(e){diag('VIDEO_TRACK_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',300)});return false;}
  }

  function cloneVideoTrack(){
    if(!ensureVideo()||!state.videoTrack)return null;
    try{
      const clone=typeof state.videoTrack.clone==='function'?state.videoTrack.clone():state.videoTrack;
      state.videoClonesCreated++;
      if(clone!==state.videoTrack){
        try{clone.addEventListener('ended',function(){state.videoClonesEnded++;diag('CLONE_TRACK_ENDED',{created:state.videoClonesCreated,ended:state.videoClonesEnded,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});},{once:true});}catch(_){}
      }
      diag('VIDEO_TRACK_CLONED',{created:state.videoClonesCreated,cloneReadyState:String(clone.readyState||''),masterReadyState:String(state.videoTrack.readyState||'')});
      return clone;
    }catch(e){diag('VIDEO_TRACK_CLONE_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',260)});return null;}
  }

  function syntheticVideoStream(){
    const video=cloneVideoTrack();if(!video)return null;
    try{return new MediaStream([video]);}catch(_){return state.videoStream;}
  }

  function combineVideoAndAudio(videoStream,audioStream){
    const videos=videoStream&&videoStream.getVideoTracks?videoStream.getVideoTracks():[];
    const audios=audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks():[];
    if(!videos.length)throw new Error('synthetic-video-track-unavailable');
    if(!audios.length)throw new Error('audio-track-unavailable');
    return new MediaStream(videos.concat(audios));
  }

  function buildSilentAudioStream(){
    try{
      const existing=state.silentAudioStream&&state.silentAudioStream.getAudioTracks?state.silentAudioStream.getAudioTracks():[];
      if(existing.length&&existing[0].readyState!=='ended')return state.silentAudioStream;
      const C=window.AudioContext||window.webkitAudioContext;if(!C)throw new Error('AudioContext unavailable');
      const context=new C({sampleRate:16000}),source=context.createOscillator(),gain=context.createGain(),dest=context.createMediaStreamDestination();
      source.type='sine';source.frequency.value=173;gain.gain.value=0;source.connect(gain);gain.connect(dest);source.start();
      try{const p=context.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}
      const stream=dest.stream,tracks=stream&&stream.getAudioTracks?stream.getAudioTracks():[];
      if(!tracks.length)throw new Error('silent-audio-track-unavailable');
      state.silentAudioContext=context;state.silentAudioSource=source;state.silentAudioGain=gain;state.silentAudioStream=stream;state.silentAudioTracks=tracks.length;
      diag('SILENT_AUDIO_READY',{tracks:tracks.length,readyState:String(tracks[0]&&tracks[0].readyState||''),sampleRate:Number(context.sampleRate||0),realMic:false});
      return stream;
    }catch(e){
      state.silentAudioErrors++;
      diag('SILENT_AUDIO_ERROR',{count:state.silentAudioErrors,name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',280)});
      return null;
    }
  }

  function markCameraTransportReady(source,stream){
    const videos=stream&&stream.getVideoTracks?stream.getVideoTracks():[];
    if(!videos.length)return;
    state.cameraTransportReady=true;state.cameraTransportReadyAt=Date.now();
    markPostStartProgress('camera-video-track',{source:source});
    diag('CAMERA_TRANSPORT_READY',{source:source,videoTracks:videos.length,readyState:String(videos[0]&&videos[0].readyState||''),gumVideoRequests:state.gumVideoRequests,displayRequests:state.displayRequests});
  }

  function waitForRealMicPermission(){
    const bridge=window.AIStudioNativeTapBridge;
    if(!bridge||typeof bridge.hasMicrophonePermission!=='function')return Promise.resolve(true);
    try{if(bridge.hasMicrophonePermission())return Promise.resolve(true);}catch(_){return Promise.resolve(true);}
    state.micPermissionRequests++;
    try{if(typeof bridge.requestMicrophonePermission==='function')bridge.requestMicrophonePermission();}catch(_){}
    diag('MIC_PERMISSION_WAIT',{request:state.micPermissionRequests,timeoutMs:MIC_PERMISSION_TIMEOUT_MS});
    return new Promise(function(resolve,reject){
      const started=Date.now();
      const poll=function(){
        try{
          if(bridge.hasMicrophonePermission()){
            diag('MIC_PERMISSION_GRANTED',{waitMs:Date.now()-started});
            resolve(true);return;
          }
        }catch(_){}
        if(Date.now()-started>=MIC_PERMISSION_TIMEOUT_MS){
          state.micPermissionTimeouts++;
          reject(new Error('android-microphone-permission-timeout'));
          return;
        }
        setTimeout(poll,250);
      };
      setTimeout(poll,250);
    });
  }

  function installMediaHooks(){
    try{
      const md=navigator.mediaDevices;if(!md)return;
      if(typeof md.getUserMedia==='function'&&!md.getUserMedia.__aisR19ScreenVideo){
        const inherited=md.getUserMedia.bind(md);
        const platformGum=(window.MediaDevices&&window.MediaDevices.prototype&&typeof window.MediaDevices.prototype.getUserMedia==='function')
          ?window.MediaDevices.prototype.getUserMedia.bind(md)
          :inherited;
        const wrapped=function(constraints){
          const c=constraints||{};
          if(state.enabled)diag('GUM_REQUEST',{audio:!!c.audio,video:!!c.video,constraints:safe(JSON.stringify(c),700)});
          if(state.enabled&&!!c.video){
            observeStart();state.gumVideoRequests++;if(!!c.audio)state.gumCombinedRequests++;
            const videoStream=syntheticVideoStream();
            if(!videoStream)return inherited(constraints);
            if(!!c.audio){
              markPostStartProgress('audio-gum-request',{combined:true,realMic:state.allowRealMicInput});
              if(!state.allowRealMicInput){
                state.silentAudioRequests++;
                const silent=buildSilentAudioStream();
                if(!silent)return Promise.reject(new Error('silent-audio-track-unavailable'));
                const combined=combineVideoAndAudio(videoStream,silent);
                markCameraTransportReady('gum-combined-silent-audio',combined);
                diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:true,video:true,realAudio:false,silentAudio:true,silentAudioTracks:silent.getAudioTracks?silent.getAudioTracks().length:0,tracks:combined&&combined.getTracks?combined.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
                return Promise.resolve(combined);
              }
              state.realAudioRequests++;
              return waitForRealMicPermission().then(function(){
                return platformGum({audio:c.audio,video:false});
              }).then(function(audioStream){
                const audioTracks=audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks():[];
                state.realAudioTracks+=audioTracks.length;
                const combined=combineVideoAndAudio(videoStream,audioStream);
                markPostStartProgress('real-mic-track',{combined:true,tracks:audioTracks.length});
                markCameraTransportReady('gum-combined',combined);
                diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:true,video:true,realAudio:true,realAudioTracks:audioTracks.length,tracks:combined&&combined.getTracks?combined.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
                return combined;
              }).catch(function(e){
                state.realAudioErrors++;
                diag('REAL_AUDIO_ERROR',{count:state.realAudioErrors,name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',280),combined:true});
                throw e;
              });
            }
            markCameraTransportReady('gum-video',videoStream);
            diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:false,video:true,realAudio:false,tracks:videoStream&&videoStream.getTracks?videoStream.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
            return Promise.resolve(videoStream);
          }
          if(state.enabled&&!!c.audio&&!c.video){
            observeStart();markPostStartProgress('audio-gum-request',{combined:false,realMic:state.allowRealMicInput});
            if(!state.allowRealMicInput){
              state.silentAudioRequests++;
              const silent=buildSilentAudioStream();
              if(!silent)return Promise.reject(new Error('silent-audio-track-unavailable'));
              diag('GUM_AUDIO_ONLY',{realAudio:false,silentAudio:true,silentAudioTracks:silent.getAudioTracks?silent.getAudioTracks().length:0,request:state.silentAudioRequests});
              return Promise.resolve(silent);
            }
            state.realAudioRequests++;
            return waitForRealMicPermission().then(function(){
              return platformGum(constraints);
            }).then(function(audioStream){
              const tracks=audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks():[];
              state.realAudioTracks+=tracks.length;markPostStartProgress('real-mic-track',{combined:false,tracks:tracks.length});
              diag('GUM_AUDIO_ONLY',{realAudio:true,silentAudio:false,realAudioTracks:tracks.length,request:state.realAudioRequests});
              return audioStream;
            }).catch(function(e){
              state.realAudioErrors++;
              diag('REAL_AUDIO_ERROR',{count:state.realAudioErrors,name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',280),audioOnly:true});
              throw e;
            });
          }
          return inherited(constraints);
        };
        wrapped.__aisR19ScreenVideo=true;md.getUserMedia=wrapped;
        diag('GUM_HOOK',{realMicBypassesSynthetic:true,cameraVideoReplacedByScreen:true});
      }
      if(typeof md.getDisplayMedia==='function'&&!md.getDisplayMedia.__aisR19ScreenVideo){
        const nativeDisplay=md.getDisplayMedia.bind(md);
        const wrappedDisplay=function(constraints){
          const c=constraints||{};
          if(state.enabled){
            state.displayRequests++;
            diag('DISPLAY_REQUEST',{count:state.displayRequests,audioRequested:!!c.audio,constraints:safe(JSON.stringify(c),700),passiveFallback:true});
            const stream=syntheticVideoStream();
            if(stream){markCameraTransportReady('display-fallback',stream);diag('DISPLAY_VIDEO',{count:state.displayRequests,audioRequested:!!c.audio,realAudioInjected:false,tracks:stream.getTracks?stream.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||''),passiveFallback:true});return Promise.resolve(stream);}
          }
          return nativeDisplay(constraints);
        };
        wrappedDisplay.__aisR19ScreenVideo=true;md.getDisplayMedia=wrappedDisplay;
      }
    }catch(e){diag('HOOK_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',300)});}
  }

  function cameraScore(el){
    const l=label(el);if(!l)return 0;
    if(/turn camera off|turn off camera|disable camera|stop camera|camera enabled|video off|videocam_off/.test(l))return -100;
    if(/share screen|screen share|present screen|share display/.test(l))return -80;
    let s=0;
    if(/\bwebcam\b|\bcamera\b|videocam|video camera|máy ảnh/.test(l))s+=60;
    if(/turn camera on|turn on camera|enable camera|start camera|start video|turn on video|enable video|share video/.test(l))s+=35;
    if(/\bvideo\b/.test(l))s+=8;
    return s;
  }

  function liveUiProgress(snapshot){
    const items=(snapshot&&snapshot.interactive)||[];
    for(let i=0;i<items.length;i++){
      const l=label(items[i]);
      if(/\b(stop|end|disconnect|leave)\b/.test(l)&&/\b(live|session|talk|stream|recording)\b/.test(l))return safe(l,220);
    }
    return '';
  }

  function cameraGate(snapshot){
    state.cameraGateScans++;
    const start=observeStart();
    if(!start.started){state.cameraGateReason='waiting-start-live';return false;}
    if(state.cameraBlockedByPageError){state.cameraGateReason='page-error-after-start';return false;}
    const startAge=state.startObservedAt?Date.now()-state.startObservedAt:-1;
    if(start.setup)markPostStartProgress('server-setup',{});
    if(!state.postStartProgress){
      const ui=liveUiProgress(snapshot);if(ui)markPostStartProgress('live-ui-transition',{label:ui});
    }
    if(startAge>=0&&startAge<CAMERA_POST_START_MIN_MS){state.cameraGateReason='waiting-start-settle';return false;}
    if(!state.postStartProgress){state.cameraGateReason='waiting-post-start-progress';return false;}
    if(state.cameraClicks>=1){
      if(state.gumVideoRequests>0){state.cameraGateReason='camera-gum-seen';return false;}
      const sinceCamera=state.lastCameraClickAt?Date.now()-state.lastCameraClickAt:-1;
      if(sinceCamera>=0&&sinceCamera<CAMERA_RETRY_NO_GUM_MS){state.cameraGateReason='camera-clicked-waiting-gum';return false;}
      if(!state.cameraNoGumReported){
        state.cameraNoGumReported=true;
        diag('CAMERA_NO_GUM_AFTER_CLICK',{attempt:state.cameraClicks,waitMs:sinceCamera,label:safe(state.lastCameraLabel,220),postStartProgressKind:state.postStartProgressKind});
      }
      if(state.cameraClicks>=MAX_CAMERA_TAP_ATTEMPTS){
        state.cameraGateReason='camera-retries-exhausted';
        if(!state.cameraRetryExhaustedReported){
          state.cameraRetryExhaustedReported=true;
          diag('CAMERA_RETRY_EXHAUSTED',{attempts:state.cameraClicks,waitMs:sinceCamera,label:safe(state.lastCameraLabel,220)});
        }
        return false;
      }
      state.cameraGateReason='camera-retry-no-gum';
      return true;
    }
    state.cameraGateReason='ready-after-'+state.postStartProgressKind;return true;
  }

  function nativeTapElement(el,purpose){
    try{
      const bridge=window.AIStudioNativeTapBridge;if(!bridge||typeof bridge.requestNativeTap!=='function')return false;
      let r=el&&el.getBoundingClientRect?el.getBoundingClientRect():null;if(!r||r.width<=1||r.height<=1)return false;
      const vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||1);
      let cx=r.left+r.width/2,cy=r.top+r.height/2;
      if(cx<0||cy<0||cx>vw||cy>vh){try{el.scrollIntoView({block:'center',inline:'center'});}catch(_){}r=el.getBoundingClientRect();cx=r.left+r.width/2;cy=r.top+r.height/2;}
      if(cx<0||cy<0||cx>vw||cy>vh)return false;
      bridge.requestNativeTap(JSON.stringify({xRatio:cx/vw,yRatio:cy/vh,tag:tag(el)||'none',role:role(el)||'none',purpose:purpose||'camera-input'}));return true;
    }catch(_){return false;}
  }

  function clickBestCamera(scored){
    if(!scored.length)return false;
    scored.sort(function(a,b){return b.score-a.score;});
    const best=scored[0],now=Date.now();state.cameraCandidates=scored.length;state.lastCameraLabel=best.label;
    if(state.cameraClicks>=MAX_CAMERA_TAP_ATTEMPTS)return false;
    state.cameraClicks++;
    if(state.cameraClicks>1)state.cameraRetries++;
    state.lastCameraClickAt=now;state.cameraNoGumReported=false;
    const nativeTap=nativeTapElement(best.el,'camera-input');
    if(!nativeTap){try{best.el.click();}catch(_){} }
    diag(state.cameraClicks===1?'CAMERA_CLICK':'CAMERA_RETRY',{attempt:state.cameraClicks,retries:state.cameraRetries,maxAttempts:MAX_CAMERA_TAP_ATTEMPTS,score:best.score,label:safe(best.label,240),tag:tag(best.el),role:role(best.el),nativeTap:nativeTap,postStartProgressKind:state.postStartProgressKind,startAgeMs:state.startObservedAt?now-state.startObservedAt:-1});
    return true;
  }

  function tryEnableVideoInput(){
    if(!state.enabled||state.cameraTransportReady||state.gumVideoRequests>0)return;
    let path='';try{path=String(location.pathname||'').toLowerCase();}catch(_){}
    if(path.indexOf('/live')<0)return;
    const snapshot=collectDeep();scanPageErrors(snapshot);state.cameraScans++;
    if(!cameraGate(snapshot)){
      if(state.cameraGateScans===1||state.cameraGateScans%8===0)diag('CAMERA_WAIT',{scan:state.cameraScans,gateScan:state.cameraGateScans,reason:state.cameraGateReason,startAttemptSeen:state.startAttemptSeen,postStartProgress:state.postStartProgress,postStartProgressKind:state.postStartProgressKind,pageError:state.lastPageError,networkEvents:state.networkEvents,positiveNetworkEvents:state.positiveNetworkEvents,realAudioRequests:state.realAudioRequests});
      return;
    }
    const camera=[];
    for(let i=0;i<snapshot.interactive.length;i++){const score=cameraScore(snapshot.interactive[i]);if(score>=20)camera.push({el:snapshot.interactive[i],score:score,label:label(snapshot.interactive[i])});}
    state.cameraCandidates=camera.length;
    if(clickBestCamera(camera))return;
    if(state.cameraScans===1||state.cameraScans%8===0)diag('CAMERA_SCAN',{scan:state.cameraScans,candidates:0,gateReason:state.cameraGateReason,gumVideoRequests:state.gumVideoRequests,configuredAgeMs:state.configuredAt?Date.now()-state.configuredAt:-1});
  }

  function pushJpeg(base64,nativeSeq){
    const s=String(base64||''),call=++state.framePushCalls,nseq=Number(nativeSeq||0);
    state.lastNativeFrameSeq=nseq;
    const trace=call<=10||call%30===0;
    if(trace)diag('FRAME_PUSH_ENTER',{call:call,nativeSeq:nseq,chars:s.length,enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),cameraTransportReady:state.cameraTransportReady,framesQueued:state.framesQueued,framesDrawn:state.framesDrawn});
    if(!state.enabled){
      state.frameRejectDisabled++;diag('FRAME_REJECT',{call:call,nativeSeq:nseq,reason:'disabled',count:state.frameRejectDisabled});
      return {ok:false,error:'disabled',call:call,nativeSeq:nseq};
    }
    if(!s||s.length<32){
      state.frameRejectEmpty++;diag('FRAME_REJECT',{call:call,nativeSeq:nseq,reason:'empty',count:state.frameRejectEmpty,chars:s.length});
      return {ok:false,error:'empty',call:call,nativeSeq:nseq};
    }
    if(s.length>3000000){
      state.frameRejectTooLarge++;diag('FRAME_REJECT',{call:call,nativeSeq:nseq,reason:'too-large',count:state.frameRejectTooLarge,chars:s.length});
      return {ok:false,error:'too-large',chars:s.length,call:call,nativeSeq:nseq};
    }
    if(!ensureVideo()){
      state.frameRejectVideoUnavailable++;diag('FRAME_REJECT',{call:call,nativeSeq:nseq,reason:'video-track-unavailable',count:state.frameRejectVideoUnavailable});
      return {ok:false,error:'video-track-unavailable',call:call,nativeSeq:nseq};
    }
    const seq=++state.framesQueued,img=new Image();
    state.frameDecodeStarts++;
    if(trace)diag('FRAME_QUEUED',{call:call,nativeSeq:nseq,seq:seq,decodeStarts:state.frameDecodeStarts,chars:s.length,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
    img.onload=function(){
      state.frameDecodeSuccess++;
      if(trace)diag('FRAME_DECODED',{call:call,nativeSeq:nseq,seq:seq,decoded:state.frameDecodeSuccess,naturalWidth:Number(img.naturalWidth||0),naturalHeight:Number(img.naturalHeight||0),lastDrawSeq:state.lastDrawSeq});
      if(seq<state.lastDrawSeq){
        state.staleFrameDrops++;diag('FRAME_DROP',{call:call,nativeSeq:nseq,seq:seq,reason:'stale-after-decode',lastDrawSeq:state.lastDrawSeq,count:state.staleFrameDrops});
        return;
      }
      try{
        const canvas=state.canvas,ctx=state.ctx;
        if(!canvas||!ctx){
          state.frameErrors++;diag('FRAME_DRAW_ERROR',{call:call,nativeSeq:nseq,seq:seq,count:state.frameErrors,reason:'canvas-or-context-missing',canvas:!!canvas,context:!!ctx});
          return;
        }
        if(img.naturalWidth>0&&img.naturalHeight>0&&(canvas.width!==img.naturalWidth||canvas.height!==img.naturalHeight)){
          if(trace)diag('FRAME_CANVAS_RESIZE',{call:call,nativeSeq:nseq,seq:seq,fromWidth:canvas.width,fromHeight:canvas.height,toWidth:img.naturalWidth,toHeight:img.naturalHeight});
          canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;
        }
        if(trace)diag('FRAME_DRAW_BEGIN',{call:call,nativeSeq:nseq,seq:seq,width:canvas.width,height:canvas.height,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
        ctx.drawImage(img,0,0,canvas.width,canvas.height);
        state.lastDrawSeq=seq;state.framesDrawn++;state.lastFrameAt=Date.now();
        let requestFrame=false,requestFrameError='';
        try{
          if(state.videoTrack&&typeof state.videoTrack.requestFrame==='function'){
            state.frameRequestAttempts++;state.videoTrack.requestFrame();requestFrame=true;
          }
        }catch(e){
          state.frameRequestErrors++;requestFrameError=safe(e&&e.message||e||'requestFrame-error',240);
          diag('FRAME_REQUEST_ERROR',{call:call,nativeSeq:nseq,seq:seq,count:state.frameRequestErrors,message:requestFrameError,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
        }
        let heartbeatAttempted=false,heartbeatResult='',heartbeatError='';
        try{
          const d=window.__AIS_LIVE_DIRECT_ENGINE__;
          if(d&&typeof d.queueScreenHeartbeat==='function'){
            heartbeatAttempted=true;state.heartbeatAttempts++;
            const h=d.queueScreenHeartbeat();
            try{heartbeatResult=safe(typeof h==='string'?h:JSON.stringify(h||{}),300);}catch(_){heartbeatResult=safe(String(h||''),300);}
          }
        }catch(e){
          state.heartbeatErrors++;heartbeatError=safe(e&&e.message||e||'heartbeat-error',240);
          diag('FRAME_HEARTBEAT_ERROR',{call:call,nativeSeq:nseq,seq:seq,count:state.heartbeatErrors,message:heartbeatError});
        }
        if(trace||state.framesDrawn===1||state.framesDrawn%20===0)diag('FRAME_DRAWN',{call:call,nativeSeq:nseq,seq:seq,frames:state.framesDrawn,width:canvas.width,height:canvas.height,base64Chars:s.length,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||''),requestFrame:requestFrame,requestFrameAttempts:state.frameRequestAttempts,requestFrameErrors:state.frameRequestErrors,heartbeatAttempted:heartbeatAttempted,heartbeatAttempts:state.heartbeatAttempts,heartbeatErrors:state.heartbeatErrors,heartbeatResult:heartbeatResult,requestFrameError:requestFrameError,heartbeatError:heartbeatError,cameraTransportReady:state.cameraTransportReady});
      }catch(e){
        state.frameErrors++;diag('FRAME_DRAW_ERROR',{call:call,nativeSeq:nseq,seq:seq,count:state.frameErrors,name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',300),stack:safe(e&&e.stack||'',900)});
      }
    };
    img.onerror=function(){
      state.frameErrors++;diag('FRAME_DECODE_ERROR',{call:call,nativeSeq:nseq,seq:seq,count:state.frameErrors,base64Chars:s.length});
    };
    img.src='data:image/jpeg;base64,'+s;
    return {ok:true,queued:true,call:call,nativeSeq:nseq,seq:seq,decodeStarts:state.frameDecodeStarts,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),cameraTransportReady:state.cameraTransportReady};
  }

  function configure(enabled,allowRealMicInput){
    state.enabled=enabled!==false;state.allowRealMicInput=allowRealMicInput===true;state.configuredAt=Date.now();installPageDiagnostics();installTransportDiagnostics();installMediaHooks();if(state.enabled)ensureVideo();
    diag('CONFIG',{enabled:state.enabled,allowRealMicInput:state.allowRealMicInput,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),transport:'camera-gum',cameraBeforeStart:false,cameraAfterStartProgress:true,singleCameraClick:false,maxCameraTapAttempts:MAX_CAMERA_TAP_ATTEMPTS,displayMediaPassiveFallback:true,silentAudioWhenMicDisabled:true,pageDiagnostics:true,networkDiagnostics:true});return describe();
  }
  function describe(){return {ok:true,version:VERSION,enabled:state.enabled,allowRealMicInput:state.allowRealMicInput,transport:'camera-gum',cameraBeforeStart:false,cameraAfterStartProgress:true,cameraTransportReady:state.cameraTransportReady,cameraTransportReadyAgeMs:state.cameraTransportReadyAt?Date.now()-state.cameraTransportReadyAt:-1,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),masterTrackEnds:state.masterTrackEnds,videoClonesCreated:state.videoClonesCreated,videoClonesEnded:state.videoClonesEnded,gumVideoRequests:state.gumVideoRequests,gumCombinedRequests:state.gumCombinedRequests,displayRequests:state.displayRequests,realAudioRequests:state.realAudioRequests,realAudioTracks:state.realAudioTracks,realAudioErrors:state.realAudioErrors,silentAudioRequests:state.silentAudioRequests,silentAudioTracks:state.silentAudioTracks,silentAudioErrors:state.silentAudioErrors,micPermissionRequests:state.micPermissionRequests,micPermissionTimeouts:state.micPermissionTimeouts,framePushCalls:state.framePushCalls,frameRejectDisabled:state.frameRejectDisabled,frameRejectEmpty:state.frameRejectEmpty,frameRejectTooLarge:state.frameRejectTooLarge,frameRejectVideoUnavailable:state.frameRejectVideoUnavailable,frameDecodeStarts:state.frameDecodeStarts,frameDecodeSuccess:state.frameDecodeSuccess,frameRequestAttempts:state.frameRequestAttempts,frameRequestErrors:state.frameRequestErrors,heartbeatAttempts:state.heartbeatAttempts,heartbeatErrors:state.heartbeatErrors,lastNativeFrameSeq:state.lastNativeFrameSeq,framesQueued:state.framesQueued,framesDrawn:state.framesDrawn,frameErrors:state.frameErrors,staleFrameDrops:state.staleFrameDrops,cameraScans:state.cameraScans,cameraCandidates:state.cameraCandidates,cameraClicks:state.cameraClicks,cameraRetries:state.cameraRetries,maxCameraTapAttempts:MAX_CAMERA_TAP_ATTEMPTS,lastCameraClickAgeMs:state.lastCameraClickAt?Date.now()-state.lastCameraClickAt:-1,lastCameraLabel:state.lastCameraLabel,cameraGateScans:state.cameraGateScans,cameraGateReason:state.cameraGateReason,cameraBlockedByPageError:state.cameraBlockedByPageError,startAttemptSeen:state.startAttemptSeen,startObservedAgeMs:state.startObservedAt?Date.now()-state.startObservedAt:-1,postStartProgress:state.postStartProgress,postStartProgressKind:state.postStartProgressKind,postStartProgressAgeMs:state.postStartProgressAt?Date.now()-state.postStartProgressAt:-1,pageErrorCount:state.pageErrorCount,lastPageError:state.lastPageError,lastPageErrorAgeMs:state.lastPageErrorAt?Date.now()-state.lastPageErrorAt:-1,networkEvents:state.networkEvents,positiveNetworkEvents:state.positiveNetworkEvents,lastNetworkStatus:state.lastNetworkStatus,lastNetworkReadyState:state.lastNetworkReadyState,lastNetworkPath:state.lastNetworkPath,lastNetworkError:state.lastNetworkError,lastNetworkResponseError:state.lastNetworkResponseError,lastNetworkAgeMs:state.lastNetworkAt?Date.now()-state.lastNetworkAt:-1,lastFrameAgeMs:state.lastFrameAt?Date.now()-state.lastFrameAt:-1};}

  installPageDiagnostics();installTransportDiagnostics();installMediaHooks();
  window.__AIS_R19_SCREEN_VIDEO__={version:VERSION,configure:configure,pushJpeg:pushJpeg,describe:describe};
  setInterval(function(){installMediaHooks();installTransportDiagnostics();tryEnableVideoInput();},800);
  setInterval(function(){if(state.enabled)scanPageErrors();},PAGE_ERROR_SCAN_MS);
  diag('ENGINE_INSTALLED',{version:VERSION,transport:'camera-gum',cameraBeforeStart:false,cameraAfterStartProgress:true,singleCameraClick:false,maxCameraTapAttempts:MAX_CAMERA_TAP_ATTEMPTS,displayMediaPassiveFallback:true,silentAudioWhenMicDisabled:true,pageDiagnostics:true,networkDiagnostics:true});
})();
    """.trimIndent()
}
