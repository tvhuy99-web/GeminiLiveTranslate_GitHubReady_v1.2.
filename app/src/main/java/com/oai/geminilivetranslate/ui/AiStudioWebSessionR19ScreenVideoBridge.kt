package com.oai.geminilivetranslate.ui

object AiStudioWebSessionR19ScreenVideoBridge {
    const val VERSION = "2026-09-17-r19.7-start-before-share-page-errors"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R19_SCREEN_VIDEO__&&window.__AIS_R19_SCREEN_VIDEO__.version)return;

  const VERSION='2026-09-17-r19.7-start-before-share-page-errors';
  const SHARE_RETRY_MS=2500;
  const START_TO_SHARE_GRACE_MS=1200;
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
    shareScans:0,
    shareCandidates:0,
    shareClicks:0,
    lastShareClickAt:0,
    lastShareLabel:'',
    startGateScans:0,
    startGateReady:false,
    startGateReason:'waiting-r17-start',
    startAttemptSeen:0,
    startActionAgeMs:-1,
    pageErrorCount:0,
    lastPageError:'',
    lastPageErrorAt:0,
    lastFrameAt:0,
    lastDrawSeq:0,
    configuredAt:0
  };
  const pageErrorSignatures=new Map();

  function diag(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R19_'+kind,payload:payload||{}}));}catch(_){}
  }
  function safe(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||240);}
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

  function recordPageError(kind,text,extra){
    const clean=safe(text,900);if(!state.enabled||!clean)return;
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
      const text=safe(el.textContent||attr(el,'aria-label')||'',900);if(!text||text.length<3)continue;
      const lower=text.toLowerCase();
      const errorWords=/\b(error|failed|failure|unavailable|unsupported|denied|blocked|permission|problem|could not|couldn't|cannot|can't|try again|something went wrong)\b|lỗi|thất bại|không thể|bị chặn|quyền/;
      const errorContainer=r==='alert'||/error|snackbar|toast|alert|banner|notification/.test(marker);
      if(errorContainer&&errorWords.test(lower)){
        recordPageError('PAGE_ERROR_UI',text,{tag:tag(el),role:r||'none',id:safe(id,120),className:safe(cls,180),testId:safe(testid,160)});
      }
    }
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
      diag('PAGE_DIAGNOSTICS_INSTALLED',{});
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
      try{track.addEventListener('ended',function(){state.masterTrackEnds++;diag('MASTER_TRACK_ENDED',{count:state.masterTrackEnds,readyState:String(track.readyState||'')});},{once:true});}catch(_){}
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
                diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:true,video:true,realAudio:true,realAudioTracks:audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks().length:0,tracks:combined&&combined.getTracks?combined.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
                return combined;
              }).catch(function(e){
                state.realAudioErrors++;
                diag('REAL_AUDIO_ERROR',{count:state.realAudioErrors,name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',280),combined:true});
                throw e;
              });
            }
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
        diag('GUM_HOOK',{realMicBypassesSynthetic:true});
      }
      if(typeof md.getDisplayMedia==='function'&&!md.getDisplayMedia.__aisR19ScreenVideo){
        const nativeDisplay=md.getDisplayMedia.bind(md);
        const wrappedDisplay=function(constraints){
          const c=constraints||{};
          if(state.enabled){
            state.displayRequests++;
            diag('DISPLAY_REQUEST',{count:state.displayRequests,audioRequested:!!c.audio,constraints:safe(JSON.stringify(c),700)});
            const stream=syntheticVideoStream();
            diag('DISPLAY_VIDEO',{count:state.displayRequests,audioRequested:!!c.audio,realAudioInjected:false,tracks:stream&&stream.getTracks?stream.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
            if(stream)return Promise.resolve(stream);
          }
          return nativeDisplay(constraints);
        };
        wrappedDisplay.__aisR19ScreenVideo=true;md.getDisplayMedia=wrappedDisplay;
      }
    }catch(e){diag('HOOK_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',300)});}
  }

  function shareScore(el){
    const l=label(el);if(!l)return 0;
    if(/stop sharing|stop share|end sharing|turn off screen/.test(l))return -100;
    let s=0;
    if(/share screen|screen share|share your screen|chia sẻ màn hình/.test(l))s+=60;
    if(/present screen|present now|share display|share window|share tab/.test(l))s+=45;
    if(/\bscreen\b|\bdisplay\b|\bwindow\b|\btab\b/.test(l))s+=8;
    if(/share|present/.test(l))s+=6;
    if(/camera|webcam/.test(l))s-=30;
    return s;
  }

  function startGate(){
    state.startGateScans++;
    try{
      const r17=window.__AIS_R17_PRODUCTION__;
      const d=r17&&typeof r17.describe==='function'?r17.describe():null;
      if(!d||!d.configured){state.startGateReady=false;state.startGateReason='waiting-r17-configured';return false;}
      const attempts=Number(d.startAttempts||0),action=String(d.lastAction||''),stage=String(d.stage||''),age=Number(d.lastActionAgeMs||-1);
      state.startAttemptSeen=Math.max(state.startAttemptSeen,attempts);state.startActionAgeMs=age;
      if(!!d.setupObserved){state.startGateReady=true;state.startGateReason='server-setup-seen';return true;}
      if(attempts<=0&&action!=='start-live'&&stage!=='start-clicked'){
        state.startGateReady=false;state.startGateReason='waiting-start-live-click';return false;
      }
      if(age>=0&&age<START_TO_SHARE_GRACE_MS){
        state.startGateReady=false;state.startGateReason='waiting-start-live-settle';return false;
      }
      state.startGateReady=true;state.startGateReason='start-live-clicked';return true;
    }catch(e){state.startGateReady=false;state.startGateReason='start-gate-error';diag('START_GATE_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',260)});return false;}
  }

  function clickBestShare(scored){
    if(!scored.length)return false;
    scored.sort(function(a,b){return b.score-a.score;});
    const best=scored[0],now=Date.now();state.shareCandidates=scored.length;state.lastShareLabel=best.label;
    if(state.shareClicks>=6||state.lastShareClickAt&&now-state.lastShareClickAt<SHARE_RETRY_MS)return true;
    state.shareClicks++;state.lastShareClickAt=now;
    try{best.el.click();diag('SHARE_CLICK',{attempt:state.shareClicks,score:best.score,label:safe(best.label,240),tag:tag(best.el),role:role(best.el),retryAfterMs:SHARE_RETRY_MS,startGateReason:state.startGateReason,startAttemptSeen:state.startAttemptSeen,startActionAgeMs:state.startActionAgeMs});}catch(e){diag('SHARE_CLICK_ERROR',{attempt:state.shareClicks,name:String(e&&e.name||'Error')});}
    return true;
  }

  function tryEnableVideoInput(){
    if(!state.enabled||state.gumVideoRequests>0||state.displayRequests>0)return;
    let path='';try{path=String(location.pathname||'').toLowerCase();}catch(_){}
    if(path.indexOf('/live')<0)return;
    if(!startGate()){
      if(state.startGateScans===1||state.startGateScans%8===0)diag('SHARE_WAIT_START',{scan:state.startGateScans,reason:state.startGateReason,startAttemptSeen:state.startAttemptSeen,startActionAgeMs:state.startActionAgeMs});
      return;
    }
    const snapshot=collectDeep();scanPageErrors(snapshot);state.shareScans++;
    const share=[];
    for(let i=0;i<snapshot.interactive.length;i++){const score=shareScore(snapshot.interactive[i]);if(score>=20)share.push({el:snapshot.interactive[i],score:score,label:label(snapshot.interactive[i])});}
    state.shareCandidates=share.length;
    if(clickBestShare(share))return;
    if(state.shareScans===1||state.shareScans%8===0)diag('SHARE_SCAN',{scan:state.shareScans,candidates:0,startGateReason:state.startGateReason});
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
    return {ok:true,queued:true,seq:seq,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended')};
  }

  function configure(enabled){
    state.enabled=enabled!==false;state.configuredAt=Date.now();installPageDiagnostics();installMediaHooks();if(state.enabled)ensureVideo();
    diag('CONFIG',{enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),startBeforeShare:true,cameraFallback:false,realMicBypassesSynthetic:true,pageDiagnostics:true});return describe();
  }
  function describe(){return {ok:true,version:VERSION,enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),masterTrackEnds:state.masterTrackEnds,videoClonesCreated:state.videoClonesCreated,videoClonesEnded:state.videoClonesEnded,gumVideoRequests:state.gumVideoRequests,gumCombinedRequests:state.gumCombinedRequests,displayRequests:state.displayRequests,realAudioRequests:state.realAudioRequests,realAudioTracks:state.realAudioTracks,realAudioErrors:state.realAudioErrors,micPermissionRequests:state.micPermissionRequests,micPermissionTimeouts:state.micPermissionTimeouts,framesQueued:state.framesQueued,framesDrawn:state.framesDrawn,frameErrors:state.frameErrors,staleFrameDrops:state.staleFrameDrops,shareScans:state.shareScans,shareCandidates:state.shareCandidates,shareClicks:state.shareClicks,lastShareClickAgeMs:state.lastShareClickAt?Date.now()-state.lastShareClickAt:-1,lastShareLabel:state.lastShareLabel,startGateScans:state.startGateScans,startGateReady:state.startGateReady,startGateReason:state.startGateReason,startAttemptSeen:state.startAttemptSeen,startActionAgeMs:state.startActionAgeMs,pageErrorCount:state.pageErrorCount,lastPageError:state.lastPageError,lastPageErrorAgeMs:state.lastPageErrorAt?Date.now()-state.lastPageErrorAt:-1,lastFrameAgeMs:state.lastFrameAt?Date.now()-state.lastFrameAt:-1};}

  installPageDiagnostics();installMediaHooks();
  window.__AIS_R19_SCREEN_VIDEO__={version:VERSION,configure:configure,pushJpeg:pushJpeg,describe:describe};
  setInterval(function(){installMediaHooks();tryEnableVideoInput();},800);
  setInterval(function(){if(state.enabled)scanPageErrors();},PAGE_ERROR_SCAN_MS);
  diag('ENGINE_INSTALLED',{version:VERSION,startBeforeShare:true,cameraFallback:false,pageDiagnostics:true});
})();
    """.trimIndent()
}