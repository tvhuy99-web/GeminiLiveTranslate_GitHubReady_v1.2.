package com.oai.geminilivetranslate.ui

object AiStudioWebSessionR19ScreenVideoBridge {
    const val VERSION = "2026-09-17-r19.2-screen-canvas-cloned-track"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R19_SCREEN_VIDEO__&&window.__AIS_R19_SCREEN_VIDEO__.version)return;

  const VERSION='2026-09-17-r19.2-screen-canvas-cloned-track';
  const state={
    enabled:false,
    canvas:null,
    ctx:null,
    videoStream:null,
    videoTrack:null,
    audioContext:null,
    audioStream:null,
    audioOscillator:null,
    audioGain:null,
    gumVideoRequests:0,
    gumCombinedRequests:0,
    displayRequests:0,
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
  function attr(el,name){try{return el&&el.getAttribute?safe(el.getAttribute(name)||'',160):'';}catch(_){return '';}}
  function role(el){return attr(el,'role').toLowerCase();}
  function label(el){try{return safe([attr(el,'aria-label'),attr(el,'title'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safe(el&&el.textContent||'',220)].filter(Boolean).join(' '),420).toLowerCase();}catch(_){return '';}}
  function interactive(el){const t=tag(el),r=role(el);return t==='BUTTON'||t==='A'||r==='button'||r==='menuitem'||r==='tab';}

  function collectDeep(){
    const roots=[document],seen=new Set(),out=[];
    while(roots.length&&out.length<5000){
      const root=roots.shift();if(!root||seen.has(root))continue;seen.add(root);
      let nodes=[];try{nodes=Array.from(root.querySelectorAll('*'));}catch(_){}
      for(let i=0;i<nodes.length&&out.length<5000;i++){
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

  function ensureSilentAudio(){
    if(state.audioStream&&state.audioStream.getAudioTracks&&state.audioStream.getAudioTracks().length)return state.audioStream;
    try{
      const C=window.AudioContext||window.webkitAudioContext;if(!C)return null;
      const ac=new C({sampleRate:16000});const osc=ac.createOscillator();const gain=ac.createGain();const dest=ac.createMediaStreamDestination();
      osc.type='sine';osc.frequency.value=173;gain.gain.value=0;osc.connect(gain);gain.connect(dest);osc.start();
      try{const p=ac.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}
      state.audioContext=ac;state.audioOscillator=osc;state.audioGain=gain;state.audioStream=dest.stream;return dest.stream;
    }catch(e){diag('AUDIO_TRACK_ERROR',{name:String(e&&e.name||'Error')});return null;}
  }

  function syntheticStream(constraints){
    const video=cloneVideoTrack();if(!video)return null;
    const tracks=[video];
    if(constraints&&constraints.audio){const a=ensureSilentAudio();if(a&&a.getAudioTracks)tracks.push.apply(tracks,a.getAudioTracks());}
    try{return new MediaStream(tracks);}catch(_){try{return new MediaStream([video]);}catch(__){return state.videoStream;}}
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
            const stream=syntheticStream(c);
            diag('GUM_VIDEO',{count:state.gumVideoRequests,audio:!!c.audio,video:true,tracks:stream&&stream.getTracks?stream.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
            if(stream)return Promise.resolve(stream);
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
            const stream=syntheticStream(c);
            diag('DISPLAY_VIDEO',{count:state.displayRequests,audio:!!c.audio,tracks:stream&&stream.getTracks?stream.getTracks().length:0,masterReadyState:String(state.videoTrack&&state.videoTrack.readyState||'')});
            if(stream)return Promise.resolve(stream);
          }
          return nativeDisplay(constraints);
        };
        wrappedDisplay.__aisR19ScreenVideo=true;md.getDisplayMedia=wrappedDisplay;
      }
    }catch(e){diag('HOOK_ERROR',{name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',300)});}
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

  function tryEnableCamera(){
    if(!state.enabled||state.gumVideoRequests>0||state.displayRequests>0)return;
    let path='';try{path=String(location.pathname||'').toLowerCase();}catch(_){}
    if(path.indexOf('/live')<0)return;
    state.cameraScans++;
    const nodes=collectDeep(),scored=[];
    for(let i=0;i<nodes.length;i++){const score=cameraScore(nodes[i]);if(score>=10)scored.push({el:nodes[i],score:score,label:label(nodes[i])});}
    scored.sort(function(a,b){return b.score-a.score;});state.cameraCandidates=scored.length;
    if(!scored.length){if(state.cameraScans===1||state.cameraScans%10===0)diag('CAMERA_SCAN',{scan:state.cameraScans,candidates:0});return;}
    const best=scored[0];state.lastCameraLabel=best.label;
    if(state.cameraClicks>=6)return;
    state.cameraClicks++;
    try{best.el.click();diag('CAMERA_CLICK',{attempt:state.cameraClicks,score:best.score,label:safe(best.label,220),tag:tag(best.el),role:role(best.el)});}catch(e){diag('CAMERA_CLICK_ERROR',{attempt:state.cameraClicks,name:String(e&&e.name||'Error')});}
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
  function describe(){return {ok:true,version:VERSION,enabled:state.enabled,videoTrackReady:!!(state.videoTrack&&state.videoTrack.readyState!=='ended'),masterTrackEnds:state.masterTrackEnds,videoClonesCreated:state.videoClonesCreated,videoClonesEnded:state.videoClonesEnded,gumVideoRequests:state.gumVideoRequests,gumCombinedRequests:state.gumCombinedRequests,displayRequests:state.displayRequests,framesQueued:state.framesQueued,framesDrawn:state.framesDrawn,frameErrors:state.frameErrors,staleFrameDrops:state.staleFrameDrops,cameraScans:state.cameraScans,cameraCandidates:state.cameraCandidates,cameraClicks:state.cameraClicks,lastCameraLabel:state.lastCameraLabel,lastFrameAgeMs:state.lastFrameAt?Date.now()-state.lastFrameAt:-1};}

  installMediaHooks();
  window.__AIS_R19_SCREEN_VIDEO__={version:VERSION,configure:configure,pushJpeg:pushJpeg,describe:describe};
  setInterval(function(){installMediaHooks();tryEnableCamera();},800);
  diag('ENGINE_INSTALLED',{version:VERSION});
})();
    """.trimIndent()
}
