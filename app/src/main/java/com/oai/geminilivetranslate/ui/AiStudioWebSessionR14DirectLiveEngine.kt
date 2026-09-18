package com.oai.geminilivetranslate.ui


object AiStudioWebSessionR14DirectLiveEngine {
    const val VERSION = "2026-09-18-web-session-r14.4-screen-heartbeat"
    const val FRAME_BYTES = 1_280
    const val FRAME_MS = 40

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_LIVE_DIRECT_ENGINE__&&window.__AIS_LIVE_DIRECT_ENGINE__.version){return;}

  const VERSION='2026-09-18-web-session-r14.4-screen-heartbeat';
  const MAX_QUEUE=256;
  const state={
    armed:false,
    queue:[],
    latestVideo:'',
    videoEnqueued:0,
    videoReplaced:0,
    videoDropped:0,
    carrierRequests:0,
    carrierFrames:0,
    replacedFrames:0,
    rejectedFrames:0,
    droppedFrames:0,
    injectedRequests:0,
    injectedHttp2xx:0,
    injectedHttpError:0,
    injectedZeroStatusEnd:0,
    templateObserved:false,
    templateMime:'',
    templatePayloadChars:0,
    lastCarrierAt:0,
    lastReplaceAt:0,
    lastStatus:0,
    screenHeartbeatEnabled:false,
    screenHeartbeatText:'',
    screenHeartbeatPending:false,
    screenTurnInFlight:false,
    screenHeartbeatsQueued:0,
    screenHeartbeatsInjected:0,
    screenTurnCompletes:0,
    lastScreenHeartbeatAt:0
  };

  function bridge(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:kind,payload:payload||{}}));
    }catch(_){}
  }
  function emit(kind,payload){bridge('R14_'+kind,payload||{});}
  function safeUrl(raw){
    try{const u=new URL(String(raw||''),location.href);return {host:String(u.host||''),path:String(u.pathname||'').slice(0,500)};}
    catch(_){return {host:'',path:''};}
  }
  function isBidi(raw){const u=safeUrl(raw);return /webchannel/i.test(u.host)&&/\/v1\/bidiGenerateContent/i.test(u.path);}
  function validFrame(v){
    const s=String(v||'');
    if(s.length<4||s.length>12000||s.length%4!==0)return false;
    return /^[A-Za-z0-9+/]+={0,2}$/.test(s);
  }
  function validVideo(v){
    const s=String(v||'');
    if(s.length<32||s.length>3000000||s.length%4!==0)return false;
    return /^[A-Za-z0-9+/]+={0,2}$/.test(s);
  }
  function findAudioSlot(node,path,depth){
    const d=depth||0;if(d>10||node==null)return null;
    try{
      if(Array.isArray(node)){
        for(let i=0;i+1<node.length;i++){
          if(typeof node[i]==='string'&&/^audio\/pcm(?:;|$)/i.test(node[i])&&typeof node[i+1]==='string'){
            return {parent:node,key:i+1,mimeParent:node,mimeKey:i,mime:String(node[i]),path:(path||[]).concat([i+1]),chars:String(node[i+1]).length};
          }
        }
        for(let i=0;i<node.length;i++){const hit=findAudioSlot(node[i],(path||[]).concat([i]),d+1);if(hit)return hit;}
      }else if(typeof node==='object'){
        const keys=Object.keys(node);
        for(let i=0;i<keys.length;i++){
          const k=keys[i];
          if((k==='mimeType'||k==='mime_type')&&typeof node[k]==='string'&&/^audio\/pcm(?:;|$)/i.test(node[k])){
            for(const dk of ['data','bytes','inlineData','inline_data']){
              if(typeof node[dk]==='string')return {parent:node,key:dk,mimeParent:node,mimeKey:k,mime:String(node[k]),path:(path||[]).concat([dk]),chars:String(node[dk]).length};
            }
          }
        }
        for(let i=0;i<keys.length;i++){const k=keys[i];const hit=findAudioSlot(node[k],(path||[]).concat([k]),d+1);if(hit)return hit;}
      }
    }catch(_){}
    return null;
  }
  function parseReq(raw){
    try{const parsed=JSON.parse(String(raw||''));const slot=findAudioSlot(parsed,[],0);return slot?{parsed:parsed,slot:slot}:null;}catch(_){return null;}
  }
  function rewriteEnvelope(body){
    if(typeof body!=='string'||body.indexOf('req')<0||body.indexOf('=')<0)return {body:body,carrierFrames:0,replaced:0,videoReplaced:0};
    let sp;try{sp=new URLSearchParams(body);}catch(_){return {body:body,carrierFrames:0,replaced:0,videoReplaced:0};}
    let carrierFrames=0,replaced=0,videoReplaced=0,heartbeatInjected=0;
    const names=[];sp.forEach(function(_,k){if(/^req\d+___data__$/.test(String(k||'')))names.push(String(k));});
    for(let i=0;i<names.length;i++){
      const name=names[i];const raw=sp.get(name);const p=parseReq(raw);if(!p)continue;
      carrierFrames++;
      if(!state.templateObserved){
        state.templateObserved=true;state.templateMime=p.slot.mime;state.templatePayloadChars=p.slot.chars;
        emit('AUDIO_TEMPLATE_CAPTURED',{mime:p.slot.mime,payloadChars:p.slot.chars,pathDepth:p.slot.path.length});
      }
      if(state.screenHeartbeatEnabled&&state.screenHeartbeatPending&&!state.screenTurnInFlight&&!heartbeatInjected){
        const realtime=Array.isArray(p.parsed)&&Array.isArray(p.parsed[2])?p.parsed[2]:null;
        if(realtime&&state.screenHeartbeatText){
          while(realtime.length<5)realtime.push(null);
          realtime[4]=state.screenHeartbeatText;
          sp.set(name,JSON.stringify(p.parsed));
          heartbeatInjected++;state.screenHeartbeatsInjected++;state.screenHeartbeatPending=false;state.screenTurnInFlight=true;state.lastScreenHeartbeatAt=Date.now();
          emit('SCREEN_HEARTBEAT_INJECTED',{count:state.screenHeartbeatsInjected,textChars:state.screenHeartbeatText.length,realtimeTextField:5,requestOrdinal:state.carrierRequests+1});
        }
      }
      if(state.armed&&state.latestVideo){
        const video=state.latestVideo;state.latestVideo='';
        p.slot.parent[p.slot.key]=video;
        p.slot.mimeParent[p.slot.mimeKey]='image/jpeg';
        sp.set(name,JSON.stringify(p.parsed));
        replaced++;videoReplaced++;state.videoReplaced++;state.lastReplaceAt=Date.now();
        emit('VIDEO_REPLACED',{jpegBase64Chars:video.length,carrierFrames:carrierFrames,totalVideoReplaced:state.videoReplaced,requestOrdinal:state.carrierRequests+1});
      }else if(state.armed&&state.queue.length){
        const next=state.queue.shift();
        p.slot.parent[p.slot.key]=next;
        sp.set(name,JSON.stringify(p.parsed));
        replaced++;state.replacedFrames++;state.lastReplaceAt=Date.now();
      }
    }
    if(carrierFrames){state.carrierRequests++;state.carrierFrames+=carrierFrames;state.lastCarrierAt=Date.now();}
    if(replaced){
      state.injectedRequests++;
      emit('MEDIA_REPLACED',{replaced:replaced,videoReplaced:videoReplaced,carrierFrames:carrierFrames,remaining:state.queue.length,totalReplaced:state.replacedFrames,totalVideoReplaced:state.videoReplaced,requestOrdinal:state.carrierRequests});
      return {body:sp.toString(),carrierFrames:carrierFrames,replaced:replaced,videoReplaced:videoReplaced,heartbeatInjected:heartbeatInjected};
    }
    if(heartbeatInjected){
      state.injectedRequests++;
      return {body:sp.toString(),carrierFrames:carrierFrames,replaced:0,videoReplaced:0,heartbeatInjected:heartbeatInjected};
    }
    return {body:body,carrierFrames:carrierFrames,replaced:0,videoReplaced:0,heartbeatInjected:0};
  }
  function describe(){
    return {ok:true,version:VERSION,armed:state.armed,queueDepth:state.queue.length,videoPending:!!state.latestVideo,videoEnqueued:state.videoEnqueued,videoReplaced:state.videoReplaced,videoDropped:state.videoDropped,carrierRequests:state.carrierRequests,carrierFrames:state.carrierFrames,replacedFrames:state.replacedFrames,rejectedFrames:state.rejectedFrames,droppedFrames:state.droppedFrames,injectedRequests:state.injectedRequests,injectedHttp2xx:state.injectedHttp2xx,injectedHttpError:state.injectedHttpError,injectedZeroStatusEnd:state.injectedZeroStatusEnd,templateObserved:state.templateObserved,templateMime:state.templateMime,templatePayloadChars:state.templatePayloadChars,lastCarrierAgeMs:state.lastCarrierAt?Date.now()-state.lastCarrierAt:-1,lastReplaceAgeMs:state.lastReplaceAt?Date.now()-state.lastReplaceAt:-1,lastStatus:state.lastStatus,screenHeartbeatEnabled:state.screenHeartbeatEnabled,screenHeartbeatPending:state.screenHeartbeatPending,screenTurnInFlight:state.screenTurnInFlight,screenHeartbeatsQueued:state.screenHeartbeatsQueued,screenHeartbeatsInjected:state.screenHeartbeatsInjected,screenTurnCompletes:state.screenTurnCompletes,lastScreenHeartbeatAgeMs:state.lastScreenHeartbeatAt?Date.now()-state.lastScreenHeartbeatAt:-1};
  }
  function enqueue(frames){
    const input=Array.isArray(frames)?frames:[frames];let accepted=0,rejected=0,dropped=0;
    for(let i=0;i<input.length;i++){
      const s=String(input[i]||'');
      if(!validFrame(s)){rejected++;state.rejectedFrames++;continue;}
      if(state.queue.length>=MAX_QUEUE){state.queue.shift();dropped++;state.droppedFrames++;}
      state.queue.push(s);accepted++;
    }
    if(accepted||rejected||dropped)emit('QUEUE',{accepted:accepted,rejected:rejected,dropped:dropped,queueDepth:state.queue.length});
    return {ok:true,accepted:accepted,rejected:rejected,dropped:dropped,queueDepth:state.queue.length,armed:state.armed};
  }
  function enqueueVideo(frame){const s=String(frame||'');if(!validVideo(s)){state.videoDropped++;emit('VIDEO_QUEUE',{accepted:0,rejected:1,videoDropped:state.videoDropped});return {ok:false,accepted:0,rejected:1,videoPending:!!state.latestVideo};}if(state.latestVideo)state.videoDropped++;state.latestVideo=s;state.videoEnqueued++;emit('VIDEO_QUEUE',{accepted:1,videoPending:true,videoEnqueued:state.videoEnqueued,videoDropped:state.videoDropped});return {ok:true,accepted:1,rejected:0,videoPending:true,videoEnqueued:state.videoEnqueued,videoDropped:state.videoDropped};}
  function configureScreenHeartbeat(enabled,text){
    state.screenHeartbeatEnabled=enabled===true;state.screenHeartbeatText=state.screenHeartbeatEnabled?String(text||'').trim().slice(0,4000):'';
    if(!state.screenHeartbeatEnabled){state.screenHeartbeatPending=false;state.screenTurnInFlight=false;}
    emit('SCREEN_HEARTBEAT_CONFIG',{enabled:state.screenHeartbeatEnabled,textChars:state.screenHeartbeatText.length,realtimeTextField:5});
    return describe();
  }
  function queueScreenHeartbeat(){
    if(!state.screenHeartbeatEnabled||!state.screenHeartbeatText)return {ok:false,enabled:state.screenHeartbeatEnabled};
    state.screenHeartbeatPending=true;state.screenHeartbeatsQueued++;
    if(state.screenHeartbeatsQueued===1||state.screenHeartbeatsQueued%20===0)emit('SCREEN_HEARTBEAT_QUEUED',{count:state.screenHeartbeatsQueued,inFlight:state.screenTurnInFlight});
    return {ok:true,pending:true,inFlight:state.screenTurnInFlight,queued:state.screenHeartbeatsQueued,injects:state.screenHeartbeatsInjected};
  }
  function onScreenTurnComplete(){
    if(!state.screenHeartbeatEnabled)return describe();
    state.screenTurnInFlight=false;state.screenTurnCompletes++;
    emit('SCREEN_TURN_COMPLETE',{count:state.screenTurnCompletes,pending:state.screenHeartbeatPending});
    return describe();
  }
  function arm(enabled){state.armed=enabled!==false;emit('ARM',{armed:state.armed,queueDepth:state.queue.length,templateObserved:state.templateObserved});return describe();}
  function clearQueue(){const n=state.queue.length;state.queue.length=0;emit('QUEUE_CLEARED',{cleared:n});return describe();}
  function reset(){state.queue.length=0;state.latestVideo='';state.videoEnqueued=0;state.videoReplaced=0;state.videoDropped=0;state.armed=false;state.carrierRequests=0;state.carrierFrames=0;state.replacedFrames=0;state.rejectedFrames=0;state.droppedFrames=0;state.injectedRequests=0;state.injectedHttp2xx=0;state.injectedHttpError=0;state.injectedZeroStatusEnd=0;state.templateObserved=false;state.templateMime='';state.templatePayloadChars=0;state.lastCarrierAt=0;state.lastReplaceAt=0;state.lastStatus=0;state.screenHeartbeatEnabled=false;state.screenHeartbeatText='';state.screenHeartbeatPending=false;state.screenTurnInFlight=false;state.screenHeartbeatsQueued=0;state.screenHeartbeatsInjected=0;state.screenTurnCompletes=0;state.lastScreenHeartbeatAt=0;emit('RESET',{version:VERSION});return describe();}

  try{
    const X=window.XMLHttpRequest;
    if(X&&X.prototype&&!X.prototype.__aisR14Wrapped){
      const nativeOpen=X.prototype.open;
      const nativeSend=X.prototype.send;
      X.prototype.open=function(method,url){this.__aisR14={raw:String(url||''),method:String(method||'GET').toUpperCase()};return nativeOpen.apply(this,arguments);};
      X.prototype.send=function(body){
        const xhr=this;const meta=xhr.__aisR14||{raw:'',method:'GET'};
        if(!isBidi(meta.raw))return nativeSend.apply(this,arguments);
        const rewritten=rewriteEnvelope(body);
        if(rewritten.replaced>0||rewritten.heartbeatInjected>0){
          let successLatched=false;
          let errorLatched=false;
          function observe(phase){
            try{
              const status=Number(xhr.status||0);
              if(status>0)state.lastStatus=status;
              if(!successLatched&&status>=200&&status<300&&xhr.readyState>=2){
                successLatched=true;
                state.injectedHttp2xx++;
                emit('INJECT_HTTP_2XX',{status:status,readyState:Number(xhr.readyState||0),phase:phase,replaced:rewritten.replaced,queueDepth:state.queue.length,total2xx:state.injectedHttp2xx});
                return;
              }
              if(!successLatched&&!errorLatched&&status>=400&&xhr.readyState>=2){
                errorLatched=true;
                state.injectedHttpError++;
                emit('INJECT_HTTP_ERROR',{status:status,readyState:Number(xhr.readyState||0),phase:phase,replaced:rewritten.replaced,queueDepth:state.queue.length,totalErrors:state.injectedHttpError});
              }
            }catch(_){}
          }
          try{xhr.addEventListener('readystatechange',function(){observe('readystatechange');});}catch(_){}
          try{xhr.addEventListener('progress',function(){observe('progress');});}catch(_){}
          try{xhr.addEventListener('loadend',function(){
            observe('loadend');
            if(!successLatched&&!errorLatched){
              const status=Number(xhr.status||0);
              if(status===0){state.injectedZeroStatusEnd++;emit('INJECT_ZERO_STATUS_END',{replaced:rewritten.replaced,queueDepth:state.queue.length,totalZeroStatus:state.injectedZeroStatusEnd});}
            }
          },{once:true});}catch(_){}
          return nativeSend.call(this,rewritten.body);
        }
        return nativeSend.apply(this,arguments);
      };
      X.prototype.__aisR14Wrapped=true;
      emit('HOOK',{target:'XMLHttpRequest'});
    }
  }catch(e){emit('HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}

  window.__AIS_LIVE_DIRECT_ENGINE__={version:VERSION,describe:describe,enqueuePcmBase64:enqueue,enqueueVideoBase64:enqueueVideo,configureScreenHeartbeat:configureScreenHeartbeat,queueScreenHeartbeat:queueScreenHeartbeat,onScreenTurnComplete:onScreenTurnComplete,arm:arm,clearQueue:clearQueue,reset:reset};
  emit('ENGINE_INSTALLED',{version:VERSION,frameBytes:1280,frameMs:40,maxQueue:MAX_QUEUE,host:safeUrl(location.href).host});
})();
    """.trimIndent()
}
