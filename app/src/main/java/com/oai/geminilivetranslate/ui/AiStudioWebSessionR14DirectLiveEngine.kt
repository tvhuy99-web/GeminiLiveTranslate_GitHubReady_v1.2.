package com.oai.geminilivetranslate.ui

/**
 * R14 page-local Direct Live Engine PoC.
 *
 * R13.2 proved that AI Studio Live sends audio/pcm through Google WebChannel form envelopes at
 * /v1/bidiGenerateContent. R14 deliberately does NOT recreate WebChannel and does NOT export auth.
 * It piggybacks on an already-live AI Studio session: when the page is about to send a normal audio
 * carrier frame, a queued Android PCM frame can replace only the Base64 audio payload while all
 * session/channel state remains owned by AI Studio.
 *
 * Security: PCM payload values, request bodies, cookies, Authorization values and access tokens are
 * never emitted through the Android bridge. Diagnostics contain counts, lengths and structural state.
 */
object AiStudioWebSessionR14DirectLiveEngine {
    const val VERSION = "2026-09-02-web-session-r14.0-direct-live-audio-piggyback"
    const val FRAME_BYTES = 1_280
    const val FRAME_MS = 40

    val DOCUMENT_START = """
(function(){
  'use strict';
  if(window.__AIS_LIVE_DIRECT_ENGINE__&&window.__AIS_LIVE_DIRECT_ENGINE__.version){return;}

  const VERSION='2026-09-02-web-session-r14.0-direct-live-audio-piggyback';
  const MAX_QUEUE=256;
  const state={
    armed:false,
    queue:[],
    carrierRequests:0,
    carrierFrames:0,
    replacedFrames:0,
    rejectedFrames:0,
    droppedFrames:0,
    injectedRequests:0,
    injectedHttp2xx:0,
    injectedHttpError:0,
    templateObserved:false,
    templateMime:'',
    templatePayloadChars:0,
    lastCarrierAt:0,
    lastReplaceAt:0,
    lastStatus:0
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
  function findAudioSlot(node,path,depth){
    const d=depth||0;if(d>10||node==null)return null;
    try{
      if(Array.isArray(node)){
        for(let i=0;i+1<node.length;i++){
          if(typeof node[i]==='string'&&/^audio\/pcm(?:;|$)/i.test(node[i])&&typeof node[i+1]==='string'){
            return {parent:node,key:i+1,mime:String(node[i]),path:(path||[]).concat([i+1]),chars:String(node[i+1]).length};
          }
        }
        for(let i=0;i<node.length;i++){const hit=findAudioSlot(node[i],(path||[]).concat([i]),d+1);if(hit)return hit;}
      }else if(typeof node==='object'){
        const keys=Object.keys(node);
        for(let i=0;i<keys.length;i++){
          const k=keys[i];
          if((k==='mimeType'||k==='mime_type')&&typeof node[k]==='string'&&/^audio\/pcm(?:;|$)/i.test(node[k])){
            for(const dk of ['data','bytes','inlineData','inline_data']){
              if(typeof node[dk]==='string')return {parent:node,key:dk,mime:String(node[k]),path:(path||[]).concat([dk]),chars:String(node[dk]).length};
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
    if(typeof body!=='string'||body.indexOf('req')<0||body.indexOf('=')<0)return {body:body,carrierFrames:0,replaced:0};
    let sp;try{sp=new URLSearchParams(body);}catch(_){return {body:body,carrierFrames:0,replaced:0};}
    let carrierFrames=0,replaced=0;
    const names=[];sp.forEach(function(_,k){if(/^req\d+___data__$/.test(String(k||'')))names.push(String(k));});
    for(let i=0;i<names.length;i++){
      const name=names[i];const raw=sp.get(name);const p=parseReq(raw);if(!p)continue;
      carrierFrames++;
      if(!state.templateObserved){
        state.templateObserved=true;state.templateMime=p.slot.mime;state.templatePayloadChars=p.slot.chars;
        emit('AUDIO_TEMPLATE_CAPTURED',{mime:p.slot.mime,payloadChars:p.slot.chars,pathDepth:p.slot.path.length});
      }
      if(state.armed&&state.queue.length){
        const next=state.queue.shift();
        p.slot.parent[p.slot.key]=next;
        sp.set(name,JSON.stringify(p.parsed));
        replaced++;state.replacedFrames++;state.lastReplaceAt=Date.now();
      }
    }
    if(carrierFrames){state.carrierRequests++;state.carrierFrames+=carrierFrames;state.lastCarrierAt=Date.now();}
    if(replaced){
      state.injectedRequests++;
      emit('AUDIO_REPLACED',{replaced:replaced,carrierFrames:carrierFrames,remaining:state.queue.length,totalReplaced:state.replacedFrames,requestOrdinal:state.carrierRequests});
      return {body:sp.toString(),carrierFrames:carrierFrames,replaced:replaced};
    }
    return {body:body,carrierFrames:carrierFrames,replaced:0};
  }
  function describe(){
    return {ok:true,version:VERSION,armed:state.armed,queueDepth:state.queue.length,carrierRequests:state.carrierRequests,carrierFrames:state.carrierFrames,replacedFrames:state.replacedFrames,rejectedFrames:state.rejectedFrames,droppedFrames:state.droppedFrames,injectedRequests:state.injectedRequests,injectedHttp2xx:state.injectedHttp2xx,injectedHttpError:state.injectedHttpError,templateObserved:state.templateObserved,templateMime:state.templateMime,templatePayloadChars:state.templatePayloadChars,lastCarrierAgeMs:state.lastCarrierAt?Date.now()-state.lastCarrierAt:-1,lastReplaceAgeMs:state.lastReplaceAt?Date.now()-state.lastReplaceAt:-1,lastStatus:state.lastStatus};
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
  function arm(enabled){state.armed=enabled!==false;emit('ARM',{armed:state.armed,queueDepth:state.queue.length,templateObserved:state.templateObserved});return describe();}
  function clearQueue(){const n=state.queue.length;state.queue.length=0;emit('QUEUE_CLEARED',{cleared:n});return describe();}
  function reset(){state.queue.length=0;state.armed=false;state.carrierRequests=0;state.carrierFrames=0;state.replacedFrames=0;state.rejectedFrames=0;state.droppedFrames=0;state.injectedRequests=0;state.injectedHttp2xx=0;state.injectedHttpError=0;state.templateObserved=false;state.templateMime='';state.templatePayloadChars=0;state.lastCarrierAt=0;state.lastReplaceAt=0;state.lastStatus=0;emit('RESET',{version:VERSION});return describe();}

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
        if(rewritten.replaced>0){
          try{xhr.addEventListener('loadend',function(){const status=Number(xhr.status||0);state.lastStatus=status;if(status>=200&&status<300)state.injectedHttp2xx++;else state.injectedHttpError++;emit('INJECT_RESULT',{status:status,ok:status>=200&&status<300,replaced:rewritten.replaced,queueDepth:state.queue.length});},{once:true});}catch(_){}
          return nativeSend.call(this,rewritten.body);
        }
        return nativeSend.apply(this,arguments);
      };
      X.prototype.__aisR14Wrapped=true;
      emit('HOOK',{target:'XMLHttpRequest'});
    }
  }catch(e){emit('HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')});}

  window.__AIS_LIVE_DIRECT_ENGINE__={version:VERSION,describe:describe,enqueuePcmBase64:enqueue,arm:arm,clearQueue:clearQueue,reset:reset};
  emit('ENGINE_INSTALLED',{version:VERSION,frameBytes:1280,frameMs:40,maxQueue:MAX_QUEUE,host:safeUrl(location.href).host});
})();
    """.trimIndent()
}
