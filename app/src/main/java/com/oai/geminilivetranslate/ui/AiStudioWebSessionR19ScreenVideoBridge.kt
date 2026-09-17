package com.oai.geminilivetranslate.ui

object AiStudioWebSessionR19ScreenVideoBridge {
    const val VERSION = "2026-09-17-r19.8-camera-screen-transport"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R19_SCREEN_VIDEO__&&window.__AIS_R19_SCREEN_VIDEO__.version)return;

  const VERSION='2026-09-17-r19.8-camera-screen-transport';
  const CAMERA_RETRY_MS=2500;
  const MIC_PERMISSION_TIMEOUT_MS=15000;
  const PAGE_ERROR_SCAN_MS=900;
  const state={
    enabled:false,
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
    micPermissionRequests:0,
    micPermissionTimeouts:0,
    videoClonesCreated:0,
    videoClonesEnded:0,
    masterTrackEnds:0,
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
    pageErrorCount:0,
    lastPageError:'',
    lastPageErrorAt:0,
    networkEvents:0,
    lastNetworkStatus:0,
    lastNetworkPath:'',
    lastNetworkError:'',
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
    diag(kind,Object.assign({count:state.pageErrorCount,text:clean},extra||{}));
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
  function recordNetwork(kind,url,status,error,extra){
    if(!state.enabled||!networkRelevant(url))return;
    const path=safeNetworkPath(url),code=Number(status||0),err=safe(error||'',220);
    state.networkEvents++;state.lastNetworkStatus=code;state.lastNetworkPath=path;state.lastNetworkError=err;state.lastNetworkAt=Date.now();
    diag(kind,Object.assign({count:state.networkEvents,path:path,status:code,error:err},extra||{}));
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
              const xhr=this;
              xhr.addEventListener('loadend',function(){recordNetwork('NETWORK_XHR',xhr.__aisR19Url,xhr.status,'',{method:safe(xhr.__aisR19Method,16),readyState:Number(xhr.readyState||0)});});
              xhr.addEventListener('error',function(){recordNetwork('NETWORK_XHR_ERROR',xhr.__aisR19Url,xhr.status,'xhr-error',{method:safe(xhr.__aisR19Method,16)});});
              xhr.addEventListener('abort',function(){recordNetwork('NETWORK_XHR_ERROR',xhr.__aisR19Url,xhr.status,'xhr-abort',{method:safe(xhr.__aisR19Method,16)});});
              xhr.addEventListener('timeout',function(){recordNetwork('NETWORK_XHR_ERROR',xhr.__aisR19Url,xhr.status,'xhr-timeout',{method:safe(xhr.__aisR19Method,16)});});
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
          return nativeFetch.apply(this,arguments).then(function(resp){recordNetwork('NETWORK_FETCH',url,resp&&resp.status||0,'',{method:method});return resp;},function(err){recordNetwork('NETWORK_FETCH_ERROR',url,0,String(err&&err.message||err||'fetch-error'),{method:method});throw err;});
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
      diag('PAGE_DIAGNOSTICS_INSTALLED',{networkDiagnostics:true,redacted:true});
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

  function combineVideoAndRealAudio(videoStream,audioStream){
    const videos=videoStream&&videoStream.getVideoTracks?videoStream.getVideoTracks():[];
    const audios=audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks():[];
    if(!videos.length)throw new Error('synthetic-video-track-unavailable');
    if(!audios.length)throw new Error('real-audio-track-unavailable');
    state.realAudioTracks+=audios.length;
    return new MediaStream(videos.concat(audios));
  }

  function markCameraTransportReady(source,stream){
    const videos=stream&&stream.getVideoTracks?stream.getVideoTracks():[];
    if(!videos.length)return;
    state.cameraTransportReady=true;state.cameraTransportReadyAt=Date.now();
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
            state.gumVideoRequests++;if(!!c.audio)state.gumCombinedRequests++;
            const videoStream=syntheticVideoStream();
            if(!videoStream)return inherited(constraints);
            if(!!c.audio){
              state.realAudioRequests++;
              return waitForRealMicPermission().then(function(){
                return platformGum({audio:c.audio,video:false});
              }).then(function(audioStream){
                const combined=combineVideoAndRealAudio(videoStream,audioStream);
                markCameraTransportReady('gum-combined',combined);
                diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:true,video:true,realAudio:true,realAudioTracks:audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks().length:0,tracks:combined&&combined.getTracks?combined.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
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
            state.realAudioRequests++;
            return waitForRealMicPermission().then(function(){
              return platformGum(constraints);
            }).then(function(audioStream){
              const tracks=audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks():[];
              state.realAudioTracks+=tracks.length;
              diag('GUM_AUDIO_ONLY',{realAudio:true,realAudioTracks:tracks.length,request:state.realAudioRequests});
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
    if(state.cameraClicks>=5||state.lastCameraClickAt&&now-state.lastCameraClickAt<CAMERA_RETRY_MS)return true;
    state.cameraClicks++;state.lastCameraClickAt=now;
    const nativeTap=nativeTapElement(best.el,'camera-input');
    if(!nativeTap){try{best.el.click();}catch(_){} }
    diag('CAMERA_CLICK',{attempt:state.cameraClicks,score:best.score,label:safe(best.label,240),tag:tag(best.el),role:role(best.el),retryAfterMs:CAMERA_RETRY_MS,nativeTap:nativeTap});
    return true;
  }

  function tryEnableVideoInput(){
    if(!state.enabled||state.cameraTransportReady||state.gumVideoRequests>0)return;
    let path='';try{path=String(location.pathname||'').toLowerCase();}catch(_){}
    if(path.indexOf('/live')<0)return;
    const snapshot=collectDeep();scanPageErrors(snapshot);state.cameraScans++;
    const camera=[];
    for(let i=0;i<snapshot.interactive.length;i++){const score=cameraScore(snapshot.interactive[i]);if(score>=20)camera.push({el:snapshot.interactive[i],score:score,label:label(snapshot.interactive[i])});}
    state.cameraCandidates=camera.length;
    if(clickBestCamera(camera))return;
    if(state.cameraScans===1||state.cameraScans%8===0)diag('CAMERA_SCAN',{scan:state.cameraScans,candidates:0,gumVideoRequests:state.gumVideoRequests,configuredAgeMs:state.configuredAt?Date.now()-state.configuredAt:-1});
  }

  function pushJpeg(base64){
    const s=String(base64||'');if(!state.enabled)return {ok:false,error:'disabled'};
    if(!s||s.length<32)return {ok:false,error:'empty'};
    if(s.length>3000000)return {ok:false,error:'too-large',chars:s.length};
    if(!ensureVideo())return {ok:false,error:'video-track-unavailable'};
    const seq=++state.framesQueued;const img=new Image();
    img.onload=function(){
      if(seq<state.lastDrawSeq){state.staleFrameDrops++;return;}
      try{
        const canvas=state.canvas,ctx=state.ctx;if(!canvas||!ctx)return;
        if(img.naturalWidth>0&&img.naturalHeight>0&&(canvas.width!==img.naturalWidth||canvas.height!==img.naturalHeight)){canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;}
        ctx.drawImage(img,0,0,canvas.width,canvas.height);state.lastDrawSeq=seq;state.framesDrawn++;state.lastFrameAt=Date.now();
        try{if(state.videoTrack&&typeof state.videoTrack.requestFrame==='function')state.videoTrack.requestFrame();}catch(_){}
        if(state.framesDrawn===1||state.framesDrawn%20===0)diag('FRAME_DRAWN',{frames:state.framesDrawn,width:canvas.width,height:canvas.height,base64Chars:s.length,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
      }catch(e){state.frameErrors++;diag('FRAME_DRAW_ERROR',{count:state.frameErrors,name:String(e&&e.name||'Error')});}
    };
    img.onerror=function(){state.frameErrors++;diag('FRAME_DECODE_ERROR',{count:state.frameErrors,base64Chars:s.length});};
    img.src='data:image/jpeg;base64,'+s;
    return {ok:true,queued:true,seq:seq,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),cameraTransportReady:state.cameraTransportReady};
  }

  function configure(enabled){
    state.enabled=enabled!==false;state.configuredAt=Date.now();installPageDiagnostics();installTransportDiagnostics();installMediaHooks();if(state.enabled)ensureVideo();
    if(state.enabled){setTimeout(tryEnableVideoInput,0);setTimeout(tryEnableVideoInput,250);setTimeout(tryEnableVideoInput,650);}
    diag('CONFIG',{enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),transport:'camera-gum',cameraBeforeStart:true,displayMediaPassiveFallback:true,realMicBypassesSynthetic:true,pageDiagnostics:true,networkDiagnostics:true});return describe();
  }
  function describe(){return {ok:true,version:VERSION,enabled:state.enabled,transport:'camera-gum',cameraBeforeStart:true,cameraTransportReady:state.cameraTransportReady,cameraTransportReadyAgeMs:state.cameraTransportReadyAt?Date.now()-state.cameraTransportReadyAt:-1,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),masterTrackEnds:state.masterTrackEnds,videoClonesCreated:state.videoClonesCreated,videoClonesEnded:state.videoClonesEnded,gumVideoRequests:state.gumVideoRequests,gumCombinedRequests:state.gumCombinedRequests,displayRequests:state.displayRequests,realAudioRequests:state.realAudioRequests,realAudioTracks:state.realAudioTracks,realAudioErrors:state.realAudioErrors,micPermissionRequests:state.micPermissionRequests,micPermissionTimeouts:state.micPermissionTimeouts,framesQueued:state.framesQueued,framesDrawn:state.framesDrawn,frameErrors:state.frameErrors,staleFrameDrops:state.staleFrameDrops,cameraScans:state.cameraScans,cameraCandidates:state.cameraCandidates,cameraClicks:state.cameraClicks,lastCameraClickAgeMs:state.lastCameraClickAt?Date.now()-state.lastCameraClickAt:-1,lastCameraLabel:state.lastCameraLabel,pageErrorCount:state.pageErrorCount,lastPageError:state.lastPageError,lastPageErrorAgeMs:state.lastPageErrorAt?Date.now()-state.lastPageErrorAt:-1,networkEvents:state.networkEvents,lastNetworkStatus:state.lastNetworkStatus,lastNetworkPath:state.lastNetworkPath,lastNetworkError:state.lastNetworkError,lastNetworkAgeMs:state.lastNetworkAt?Date.now()-state.lastNetworkAt:-1,lastFrameAgeMs:state.lastFrameAt?Date.now()-state.lastFrameAt:-1};}

  installPageDiagnostics();installTransportDiagnostics();installMediaHooks();
  window.__AIS_R19_SCREEN_VIDEO__={version:VERSION,configure:configure,pushJpeg:pushJpeg,describe:describe};
  setInterval(function(){installMediaHooks();installTransportDiagnostics();tryEnableVideoInput();},800);
  setInterval(function(){if(state.enabled)scanPageErrors();},PAGE_ERROR_SCAN_MS);
  diag('ENGINE_INSTALLED',{version:VERSION,transport:'camera-gum',cameraBeforeStart:true,displayMediaPassiveFallback:true,pageDiagnostics:true,networkDiagnostics:true});
})();
    """.trimIndent()
}
