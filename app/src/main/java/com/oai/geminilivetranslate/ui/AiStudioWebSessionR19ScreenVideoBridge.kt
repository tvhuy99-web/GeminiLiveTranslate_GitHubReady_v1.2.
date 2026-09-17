package com.oai.geminilivetranslate.ui

object AiStudioWebSessionR19ScreenVideoBridge {
    const val VERSION = "2026-09-17-r19.6-desktop-share-real-mic-all-requests"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R19_SCREEN_VIDEO__&&window.__AIS_R19_SCREEN_VIDEO__.version)return;

  const VERSION='2026-09-17-r19.6-desktop-share-real-mic-all-requests';
  const SHARE_RETRY_MS=1800;
  const CAMERA_RETRY_MS=3000;
  const CAMERA_FALLBACK_AFTER_SCANS=5;
  const MIC_PERMISSION_TIMEOUT_MS=15000;
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
    cameraScans:0,
    cameraCandidates:0,
    cameraClicks:0,
    lastCameraClickAt:0,
    lastCameraLabel:'',
    lastFrameAt:0,
    lastDrawSeq:0,
    configuredAt:0
  };

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
    const roots=[document],seen=new Set(),out=[];
    while(roots.length&&out.length<6000){
      const root=roots.shift();if(!root||seen.has(root))continue;seen.add(root);
      let nodes=[];try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&out.length<6000;i++){
        const el=nodes[i];if(interactive(el))out.push(el);
        try{if(el.shadowRoot)roots.push(el.shadowRoot);}catch(_){}
        try{const t=tag(el);if((t==='IFRAME'||t==='FRAME')&&el.contentDocument)roots.push(el.contentDocument);}catch(_){}
      }
    }
    return out;
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
        const native=md.getUserMedia.bind(md);
        const wrapped=function(constraints){
          const c=constraints||{};
          if(state.enabled&&!!c.video){
            state.gumVideoRequests++;if(!!c.audio)state.gumCombinedRequests++;
            const videoStream=syntheticVideoStream();
            if(!videoStream)return native(constraints);
            if(!!c.audio){
              state.realAudioRequests++;
              return waitForRealMicPermission().then(function(){
                return native({audio:c.audio,video:false});
              }).then(function(audioStream){
                const combined=combineVideoAndRealAudio(videoStream,audioStream);
                diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:true,video:true,realAudio:true,realAudioTracks:audioStream&&audioStream.getAudioTracks?audioStream.getAudioTracks().length:0,tracks:combined&&combined.getTracks?combined.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
                return combined;
              }).catch(function(e){
                state.realAudioErrors++;
                diag('REAL_AUDIO_ERROR',{count:state.realAudioErrors,name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',280)});
                throw e;
              });
            }
            diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:false,video:true,realAudio:false,tracks:videoStream&&videoStream.getTracks?videoStream.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
            return Promise.resolve(videoStream);
          }
          if(state.enabled&&!!c.audio&&!c.video){
            state.realAudioRequests++;
            return waitForRealMicPermission().then(function(){
              return native(constraints);
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
          return native(constraints);
        };
        wrapped.__aisR19ScreenVideo=true;md.getUserMedia=wrapped;
      }
      if(typeof md.getDisplayMedia==='function'&&!md.getDisplayMedia.__aisR19ScreenVideo){
        const nativeDisplay=md.getDisplayMedia.bind(md);
        const wrappedDisplay=function(constraints){
          const c=constraints||{};
          if(state.enabled){
            state.displayRequests++;
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

  function cameraScore(el){
    const l=label(el);if(!l)return 0;
    if(/turn camera off|turn off camera|disable camera|stop camera|camera enabled/.test(l))return -100;
    let s=0;
    if(/turn camera on|turn on camera|enable camera|start camera/.test(l))s+=20;
    if(/\bcamera\b|\bwebcam\b|máy ảnh/.test(l))s+=10;
    if(/share video|start video|enable video|turn video on/.test(l))s+=8;
    if(/video/.test(l))s+=2;
    return s;
  }

  function clickBest(scored,kind){
    if(!scored.length)return false;
    scored.sort(function(a,b){return b.score-a.score;});
    const best=scored[0],now=Date.now();
    if(kind==='share'){
      state.shareCandidates=scored.length;state.lastShareLabel=best.label;
      if(state.shareClicks>=6||state.lastShareClickAt&&now-state.lastShareClickAt<SHARE_RETRY_MS)return true;
      state.shareClicks++;state.lastShareClickAt=now;
      try{best.el.click();diag('SHARE_CLICK',{attempt:state.shareClicks,score:best.score,label:safe(best.label,240),tag:tag(best.el),role:role(best.el),retryAfterMs:SHARE_RETRY_MS});}catch(e){diag('SHARE_CLICK_ERROR',{attempt:state.shareClicks,name:String(e&&e.name||'Error')});}
      return true;
    }
    state.cameraCandidates=scored.length;state.lastCameraLabel=best.label;
    if(state.cameraClicks>=6||state.lastCameraClickAt&&now-state.lastCameraClickAt<CAMERA_RETRY_MS)return true;
    state.cameraClicks++;state.lastCameraClickAt=now;
    try{best.el.click();diag('CAMERA_CLICK',{attempt:state.cameraClicks,score:best.score,label:safe(best.label,220),tag:tag(best.el),role:role(best.el),retryAfterMs:CAMERA_RETRY_MS});}catch(e){diag('CAMERA_CLICK_ERROR',{attempt:state.cameraClicks,name:String(e&&e.name||'Error')});}
    return true;
  }

  function tryEnableVideoInput(){
    if(!state.enabled||state.gumVideoRequests>0||state.displayRequests>0)return;
    let path='';try{path=String(location.pathname||'').toLowerCase();}catch(_){}
    if(path.indexOf('/live')<0)return;
    const nodes=collectDeep();
    state.shareScans++;
    const share=[];
    for(let i=0;i<nodes.length;i++){const score=shareScore(nodes[i]);if(score>=20)share.push({el:nodes[i],score:score,label:label(nodes[i])});}
    state.shareCandidates=share.length;
    if(clickBest(share,'share'))return;
    if(state.shareScans===1||state.shareScans%10===0)diag('SHARE_SCAN',{scan:state.shareScans,candidates:0});
    if(state.shareScans<CAMERA_FALLBACK_AFTER_SCANS)return;

    state.cameraScans++;
    const camera=[];
    for(let i=0;i<nodes.length;i++){const score=cameraScore(nodes[i]);if(score>=10)camera.push({el:nodes[i],score:score,label:label(nodes[i])});}
    state.cameraCandidates=camera.length;
    if(clickBest(camera,'camera'))return;
    if(state.cameraScans===1||state.cameraScans%10===0)diag('CAMERA_SCAN',{scan:state.cameraScans,candidates:0});
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

  function configure(enabled){state.enabled=enabled!==false;state.configuredAt=Date.now();installMediaHooks();if(state.enabled)ensureVideo();diag('CONFIG',{enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended')});return describe();}
  function describe(){return {ok:true,version:VERSION,enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),masterTrackEnds:state.masterTrackEnds,videoClonesCreated:state.videoClonesCreated,videoClonesEnded:state.videoClonesEnded,gumVideoRequests:state.gumVideoRequests,gumCombinedRequests:state.gumCombinedRequests,displayRequests:state.displayRequests,realAudioRequests:state.realAudioRequests,realAudioTracks:state.realAudioTracks,realAudioErrors:state.realAudioErrors,micPermissionRequests:state.micPermissionRequests,micPermissionTimeouts:state.micPermissionTimeouts,framesQueued:state.framesQueued,framesDrawn:state.framesDrawn,frameErrors:state.frameErrors,staleFrameDrops:state.staleFrameDrops,shareScans:state.shareScans,shareCandidates:state.shareCandidates,shareClicks:state.shareClicks,lastShareClickAgeMs:state.lastShareClickAt?Date.now()-state.lastShareClickAt:-1,lastShareLabel:state.lastShareLabel,cameraScans:state.cameraScans,cameraCandidates:state.cameraCandidates,cameraClicks:state.cameraClicks,lastCameraClickAgeMs:state.lastCameraClickAt?Date.now()-state.lastCameraClickAt:-1,lastCameraLabel:state.lastCameraLabel,lastFrameAgeMs:state.lastFrameAt?Date.now()-state.lastFrameAt:-1};}

  installMediaHooks();
  window.__AIS_R19_SCREEN_VIDEO__={version:VERSION,configure:configure,pushJpeg:pushJpeg,describe:describe};
  setInterval(function(){installMediaHooks();tryEnableVideoInput();},800);
  diag('ENGINE_INSTALLED',{version:VERSION});
})();
    """.trimIndent()
}
