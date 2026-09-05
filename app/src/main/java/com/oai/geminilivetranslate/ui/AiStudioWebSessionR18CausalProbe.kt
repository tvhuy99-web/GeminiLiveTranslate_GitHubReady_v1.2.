package com.oai.geminilivetranslate.ui

/**
 * R18.1/R18.2 observational probe for finding AI Studio's non-UI Live bootstrap path.
 *
 * R18.1 records a causal timeline around the one manual Start action used only for research:
 * trusted browser event -> getUserMedia / AudioContext -> WebChannel Bidi open/send/response.
 *
 * R18.2 clusters Bidi call stacks and recurring bundle frames so later work can identify the
 * page-local service/function that creates the Live session without depending on DOM controls,
 * labels, coordinates, MotionEvent injection or synthetic click automation.
 *
 * Security/privacy: query strings/fragments, cookies, Authorization values, request/response bodies,
 * media payloads, API keys and access tokens never cross the Android bridge. Stack URLs are reduced
 * to scheme/host/path plus line/column when the browser supplies them.
 */
object AiStudioWebSessionR18CausalProbe {
    const val VERSION = "2026-09-03-r18.2-causal-live-bootstrap-probe"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R18_CAUSAL__&&window.__AIS_R18_CAUSAL__.version){return;}

  const VERSION='2026-09-03-r18.2-causal-live-bootstrap-probe';
  const MAX_EVENTS=900;
  const events=[];
  let seq=0;
  let captureId=0;
  let captureActive=false;
  let captureLabel='';
  let captureStartedAt=0;
  let captureStoppedAt=0;
  let lastTrustedAt=0;
  let lastTrustedKind='';
  let lastTrustedTarget={};
  let firstGumAt=0;
  let firstAudioResumeAt=0;
  let firstBidiOpenAt=0;
  let firstBidiSendAt=0;
  let lastBidiAt=0;
  const counters={
    trusted:0,getUserMedia:0,getUserMediaResolved:0,getUserMediaRejected:0,
    audioResume:0,mediaStreamSource:0,
    bidiOpen:0,bidiSend:0,bidiProgress:0,bidiFinal:0,bidiAbort:0
  };

  function bridge(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R18_'+kind,payload:payload||{}}));
    }catch(_){}
  }
  function safeText(v,n){return String(v==null?'':v).replace(/\s+/g,' ').trim().slice(0,n||300);}
  function safeUrl(raw){
    try{
      const u=new URL(String(raw||''),location.href);
      return {scheme:String(u.protocol||'').replace(':',''),host:String(u.host||''),path:String(u.pathname||'').slice(0,700)};
    }catch(_){return {scheme:'',host:'',path:''};}
  }
  function sanitizeStackLine(line){
    try{
      let s=String(line||'').replace(/\s+/g,' ').trim();
      s=s.replace(/https?:\/\/[^\s)]+/g,function(raw){
        try{
          const withoutQuery=String(raw).split('?')[0].split('#')[0];
          const match=withoutQuery.match(/^(https?:\/\/.*?)(?::(\d+))?(?::(\d+))?$/);
          if(!match)return safeText(withoutQuery,320);
          const u=safeUrl(match[1]);
          const base=(u.scheme?u.scheme+'://':'')+u.host+u.path;
          return base+(match[2]?':'+match[2]:'')+(match[3]?':'+match[3]:'');
        }catch(_){return safeText(String(raw).split('?')[0].split('#')[0],320);}
      });
      return s.slice(0,420);
    }catch(_){return '';}
  }
  function hashText(s){
    let h=2166136261;const value=String(s||'');
    for(let i=0;i<value.length;i++){h^=value.charCodeAt(i);h=Math.imul(h,16777619);}
    return (h>>>0).toString(16);
  }
  function stackMeta(skip){
    try{
      const raw=String(new Error().stack||'');
      const lines=raw.split('\n').slice(2+(skip||0),16+(skip||0)).map(sanitizeStackLine).filter(Boolean);
      const normalized=lines.join('|');
      let firstBundle='';
      for(let i=0;i<lines.length;i++){
        const line=lines[i];
        if(/aistudio\.google\.com\//i.test(line)&&!/AiStudioWebSessionR18CausalProbe|__AIS_R18_CAUSAL__/i.test(line)){firstBundle=line;break;}
      }
      return {fingerprint:hashText(normalized),firstBundle:firstBundle,lines:lines};
    }catch(_){return {fingerprint:'',firstBundle:'',lines:[]};}
  }
  function elapsed(at){return captureStartedAt>0&&at>0?Math.max(0,at-captureStartedAt):-1;}
  function push(kind,payload,force){
    if(!captureActive&&!force&&kind!=='ENGINE_INSTALLED')return null;
    try{
      const now=Date.now();
      const e={id:++seq,at:now,tMs:elapsed(now),captureId:captureId,kind:String(kind||''),payload:payload||{}};
      events.push(e);if(events.length>MAX_EVENTS)events.splice(0,events.length-MAX_EVENTS);
      bridge(kind,e);
      return e;
    }catch(_){return null;}
  }
  function targetMeta(target){
    try{
      if(!target)return {kind:'none'};
      const tag=String(target.tagName||'').slice(0,50);
      const role=target.getAttribute?String(target.getAttribute('role')||'').slice(0,80):'';
      const type=target.getAttribute?String(target.getAttribute('type')||'').slice(0,60):'';
      const aria=target.getAttribute?String(target.getAttribute('aria-label')||'').slice(0,160):'';
      const testId=target.getAttribute?String(target.getAttribute('data-testid')||'').slice(0,120):'';
      return {kind:'element',tag:tag,role:role,type:type,ariaChars:aria.length,testId:testId};
    }catch(_){return {kind:'unknown'};}
  }
  function relativeToTrusted(now){return lastTrustedAt>0?Math.max(0,now-lastTrustedAt):-1;}
  function isBidi(raw){const u=safeUrl(raw);return /webchannel/i.test(u.host)&&/\/v1\/bidiGenerateContent/i.test(u.path);}
  function modelsFromText(text){
    try{
      const out=[];const matches=String(text||'').match(/(?:models\/)?gemini-[a-z0-9._-]+/ig)||[];
      matches.forEach(function(v){const n=String(v).replace(/^models\//i,'');if(out.indexOf(n)<0&&out.length<10)out.push(n.slice(0,180));});
      return out;
    }catch(_){return [];}
  }
  function mimesFromText(text){
    try{
      const out=[];const matches=String(text||'').match(/(?:audio|video|image)\/[a-z0-9.+_-]+/ig)||[];
      matches.forEach(function(v){const n=String(v).toLowerCase();if(out.indexOf(n)<0&&out.length<12)out.push(n.slice(0,100));});
      return out;
    }catch(_){return [];}
  }
  function bodyMeta(body){
    const out={kind:'none',textChars:0,bytes:0,paramCount:0,paramNames:[],models:[],mimes:[],hasAudioPcm:false};
    try{
      if(body==null)return out;
      if(typeof body==='string'){
        out.kind='string';out.textChars=body.length;out.models=modelsFromText(body);out.mimes=mimesFromText(body);out.hasAudioPcm=/audio\/pcm/i.test(body);
        if(body.indexOf('=')>=0){
          const sp=new URLSearchParams(body),names=[];sp.forEach(function(_,k){if(names.indexOf(k)<0&&names.length<40)names.push(String(k).slice(0,120));});
          out.paramNames=names.sort();out.paramCount=names.length;
        }
        return out;
      }
      if(body instanceof ArrayBuffer){out.kind='ArrayBuffer';out.bytes=body.byteLength;return out;}
      if(ArrayBuffer.isView(body)){out.kind=(body.constructor&&body.constructor.name)||'TypedArray';out.bytes=body.byteLength||0;return out;}
      if(typeof Blob!=='undefined'&&body instanceof Blob){out.kind='Blob';out.bytes=body.size||0;return out;}
      out.kind=(body&&body.constructor&&body.constructor.name)||typeof body;
    }catch(_){}
    return out;
  }

  function onTrustedEvent(ev){
    try{
      if(!captureActive||!ev||ev.isTrusted!==true)return;
      const type=String(ev.type||'');
      if(type!=='pointerdown'&&type!=='pointerup'&&type!=='click'&&type!=='submit'&&type!=='keydown')return;
      const now=Date.now();lastTrustedAt=now;lastTrustedKind=type;lastTrustedTarget=targetMeta(ev.target);counters.trusted++;
      push('TRUSTED_EVENT',{ordinal:counters.trusted,type:type,target:lastTrustedTarget,isTrusted:true,stack:stackMeta(1)});
    }catch(_){}
  }
  try{
    ['pointerdown','pointerup','click','submit','keydown'].forEach(function(type){window.addEventListener(type,onTrustedEvent,true);});
  }catch(_){}

  try{
    const md=navigator.mediaDevices;
    if(md&&typeof md.getUserMedia==='function'&&!md.getUserMedia.__aisR18Wrapped){
      const native=md.getUserMedia.bind(md);
      const wrapped=function(constraints){
        const now=Date.now();counters.getUserMedia++;if(!firstGumAt)firstGumAt=now;
        let audio=false,video=false;try{audio=!!(constraints&&constraints.audio);video=!!(constraints&&constraints.video);}catch(_){}
        push('GET_USER_MEDIA_CALL',{ordinal:counters.getUserMedia,audio:audio,video:video,afterTrustedMs:relativeToTrusted(now),trustedKind:lastTrustedKind,stack:stackMeta(1)});
        let result;
        try{result=native(constraints);}catch(e){counters.getUserMediaRejected++;push('GET_USER_MEDIA_THROW',{name:String(e&&e.name||'Error'),afterTrustedMs:relativeToTrusted(Date.now())});throw e;}
        try{
          if(result&&typeof result.then==='function'){
            result.then(function(stream){
              counters.getUserMediaResolved++;let tracks=0,audioTracks=0,videoTracks=0;
              try{tracks=stream.getTracks().length;audioTracks=stream.getAudioTracks().length;videoTracks=stream.getVideoTracks().length;}catch(_){}
              push('GET_USER_MEDIA_RESOLVED',{ordinal:counters.getUserMediaResolved,tracks:tracks,audioTracks:audioTracks,videoTracks:videoTracks,afterTrustedMs:relativeToTrusted(Date.now())});
            }).catch(function(e){counters.getUserMediaRejected++;push('GET_USER_MEDIA_REJECTED',{ordinal:counters.getUserMediaRejected,name:String(e&&e.name||'Error'),afterTrustedMs:relativeToTrusted(Date.now())});});
          }
        }catch(_){}
        return result;
      };
      wrapped.__aisR18Wrapped=true;md.getUserMedia=wrapped;
    }
  }catch(e){push('HOOK_ERROR',{target:'getUserMedia',name:String(e&&e.name||'Error')},true);}

  function installAudioHooks(){
    try{
      const AC=window.AudioContext||window.webkitAudioContext;if(!AC||!AC.prototype)return;
      const p=AC.prototype;
      if(typeof p.resume==='function'&&!p.resume.__aisR18Wrapped){
        const nativeResume=p.resume;const wrappedResume=function(){const now=Date.now();counters.audioResume++;if(!firstAudioResumeAt)firstAudioResumeAt=now;push('AUDIO_CONTEXT_RESUME',{ordinal:counters.audioResume,state:String(this&&this.state||''),afterTrustedMs:relativeToTrusted(now),stack:stackMeta(1)});return nativeResume.apply(this,arguments);};wrappedResume.__aisR18Wrapped=true;p.resume=wrappedResume;
      }
      if(typeof p.createMediaStreamSource==='function'&&!p.createMediaStreamSource.__aisR18Wrapped){
        const nativeSource=p.createMediaStreamSource;const wrappedSource=function(){const now=Date.now();counters.mediaStreamSource++;push('MEDIA_STREAM_SOURCE',{ordinal:counters.mediaStreamSource,afterTrustedMs:relativeToTrusted(now),stack:stackMeta(1)});return nativeSource.apply(this,arguments);};wrappedSource.__aisR18Wrapped=true;p.createMediaStreamSource=wrappedSource;
      }
    }catch(e){push('HOOK_ERROR',{target:'AudioContext',name:String(e&&e.name||'Error')},true);}
  }
  installAudioHooks();

  try{
    const X=window.XMLHttpRequest;
    if(X&&X.prototype&&!X.prototype.__aisR18Wrapped){
      const nativeOpen=X.prototype.open,nativeSend=X.prototype.send,nativeAbort=X.prototype.abort;
      X.prototype.open=function(method,url){
        const raw=String(url||'');this.__aisR18={raw:raw,url:safeUrl(raw),method:String(method||'GET').toUpperCase(),openedAt:Date.now(),lastChars:0};
        if(captureActive&&isBidi(raw)){
          const now=Date.now();counters.bidiOpen++;if(!firstBidiOpenAt)firstBidiOpenAt=now;lastBidiAt=now;
          push('BIDI_OPEN',{ordinal:counters.bidiOpen,url:this.__aisR18.url,method:this.__aisR18.method,afterTrustedMs:relativeToTrusted(now),trustedKind:lastTrustedKind,trustedTarget:lastTrustedTarget,stack:stackMeta(1)});
        }
        return nativeOpen.apply(this,arguments);
      };
      X.prototype.send=function(body){
        const xhr=this,m=xhr.__aisR18||{raw:'',url:{},method:'GET',lastChars:0};
        if(captureActive&&isBidi(m.raw)){
          const now=Date.now();counters.bidiSend++;if(!firstBidiSendAt)firstBidiSendAt=now;lastBidiAt=now;
          const ordinal=counters.bidiSend;push('BIDI_SEND',{ordinal:ordinal,url:m.url,method:m.method,afterTrustedMs:relativeToTrusted(now),body:bodyMeta(body),stack:stackMeta(1)});
          try{xhr.addEventListener('readystatechange',function(){
            if(!captureActive||(xhr.readyState!==3&&xhr.readyState!==4))return;
            let chars=0,delta=0,status=0;try{const text=typeof xhr.responseText==='string'?xhr.responseText:'';chars=text.length;delta=Math.max(0,chars-(m.lastChars||0));m.lastChars=chars;status=Number(xhr.status||0);}catch(_){}
            if(xhr.readyState===3&&delta>0){counters.bidiProgress++;push('BIDI_RESPONSE_PROGRESS',{ordinal:ordinal,progressOrdinal:counters.bidiProgress,status:status,responseChars:chars,delta:delta,afterTrustedMs:relativeToTrusted(Date.now())});}
            if(xhr.readyState===4){counters.bidiFinal++;push('BIDI_RESPONSE_FINAL',{ordinal:ordinal,finalOrdinal:counters.bidiFinal,status:status,responseChars:chars,afterTrustedMs:relativeToTrusted(Date.now())});}
          });}catch(_){}
        }
        return nativeSend.apply(this,arguments);
      };
      X.prototype.abort=function(){try{const m=this.__aisR18;if(captureActive&&m&&isBidi(m.raw)){counters.bidiAbort++;push('BIDI_ABORT',{ordinal:counters.bidiAbort,url:m.url,afterTrustedMs:relativeToTrusted(Date.now()),stack:stackMeta(1)});}}catch(_){}return nativeAbort.apply(this,arguments);};
      X.prototype.__aisR18Wrapped=true;
    }
  }catch(e){push('HOOK_ERROR',{target:'XMLHttpRequest',name:String(e&&e.name||'Error')},true);}

  function stackCandidates(){
    const map={};
    events.forEach(function(e){
      if(e.kind!=='BIDI_OPEN'&&e.kind!=='BIDI_SEND')return;
      const s=e.payload&&e.payload.stack;if(!s||!s.fingerprint)return;
      const key=e.kind+'|'+s.fingerprint;if(!map[key])map[key]={kind:e.kind,fingerprint:s.fingerprint,count:0,firstBundle:s.firstBundle||'',firstAt:e.at,lastAt:e.at,lines:s.lines||[]};
      map[key].count++;map[key].lastAt=e.at;
    });
    return Object.keys(map).map(function(k){return map[k];}).sort(function(a,b){return b.count-a.count||(a.firstAt-b.firstAt);}).slice(0,40);
  }
  function recurringFrames(){
    const counts={};
    events.forEach(function(e){
      if(e.kind!=='BIDI_OPEN'&&e.kind!=='BIDI_SEND'&&e.kind!=='GET_USER_MEDIA_CALL'&&e.kind!=='AUDIO_CONTEXT_RESUME')return;
      const lines=e.payload&&e.payload.stack&&e.payload.stack.lines||[];
      lines.forEach(function(line){
        const s=String(line||'');if(!/aistudio\.google\.com\//i.test(s))return;
        if(/__AIS_R18_CAUSAL__|R18CausalProbe/i.test(s))return;
        if(!counts[s])counts[s]={frame:s,count:0,kinds:{}};counts[s].count++;counts[s].kinds[e.kind]=(counts[s].kinds[e.kind]||0)+1;
      });
    });
    return Object.keys(counts).map(function(k){return counts[k];}).sort(function(a,b){return b.count-a.count;}).slice(0,50);
  }
  function causalWindow(){
    const sends=events.filter(function(e){return e.kind==='BIDI_SEND';});
    const send=sends.length?sends[sends.length-1]:null;
    if(!send)return {present:false};
    const before=events.filter(function(e){return e.at<=send.at&&send.at-e.at<=10000;}).slice(-80);
    return {present:true,bidiSendId:send.id,bidiSendAt:send.at,windowMs:10000,events:before.map(function(e){return {id:e.id,tMs:e.tMs,kind:e.kind,at:e.at,afterTrustedMs:e.payload&&typeof e.payload.afterTrustedMs==='number'?e.payload.afterTrustedMs:undefined,fingerprint:e.payload&&e.payload.stack?e.payload.stack.fingerprint:undefined,firstBundle:e.payload&&e.payload.stack?e.payload.stack.firstBundle:undefined};})};
  }
  function summary(){
    const trustedEvents=events.filter(function(e){return e.kind==='TRUSTED_EVENT';});
    const firstTrustedAt=trustedEvents.length?trustedEvents[0].at:0;
    return {
      ok:true,version:VERSION,captureActive:captureActive,captureId:captureId,label:captureLabel,
      captureAgeMs:captureStartedAt?Date.now()-captureStartedAt:-1,eventCount:events.length,counters:Object.assign({},counters),
      firstTrustedMs:elapsed(firstTrustedAt),firstGumMs:elapsed(firstGumAt),firstAudioResumeMs:elapsed(firstAudioResumeAt),firstBidiOpenMs:elapsed(firstBidiOpenAt),firstBidiSendMs:elapsed(firstBidiSendAt),
      lastBidiAgeMs:lastBidiAt?Date.now()-lastBidiAt:-1,lastTrustedKind:lastTrustedKind,lastTrustedTarget:lastTrustedTarget,
      stackCandidates:stackCandidates(),recurringFrames:recurringFrames(),causalWindow:causalWindow()
    };
  }
  function resetCounters(){Object.keys(counters).forEach(function(k){counters[k]=0;});firstGumAt=0;firstAudioResumeAt=0;firstBidiOpenAt=0;firstBidiSendAt=0;lastBidiAt=0;lastTrustedAt=0;lastTrustedKind='';lastTrustedTarget={};}
  function startCapture(label){events.length=0;resetCounters();captureId++;captureLabel=safeText(label||'manual-live-bootstrap',160);captureStartedAt=Date.now();captureStoppedAt=0;captureActive=true;push('CAPTURE_START',{label:captureLabel,url:safeUrl(location.href),version:VERSION});return summary();}
  function stopCapture(label){if(captureActive)push('CAPTURE_STOP',{label:safeText(label||captureLabel,160),durationMs:Date.now()-captureStartedAt});captureActive=false;captureStoppedAt=Date.now();return summary();}
  function mark(label){push('MARK',{label:safeText(label||'',180),stack:stackMeta(1)});return summary();}
  function recent(limit){const n=Math.max(1,Math.min(Number(limit||240),MAX_EVENTS));return {ok:true,version:VERSION,captureId:captureId,events:events.slice(-n)};}
  function describe(){return summary();}

  window.__AIS_R18_CAUSAL__={version:VERSION,startCapture:startCapture,stopCapture:stopCapture,mark:mark,describe:describe,recent:recent};
  push('ENGINE_INSTALLED',{version:VERSION,url:safeUrl(location.href)},true);
})();
    """.trimIndent()
}
