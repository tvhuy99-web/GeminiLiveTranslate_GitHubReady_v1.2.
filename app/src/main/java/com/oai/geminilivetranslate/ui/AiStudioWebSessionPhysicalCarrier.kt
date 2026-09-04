package com.oai.geminilivetranslate.ui

/**
 * Silent page-local audio carrier for the one-trusted-tap production fallback.
 *
 * The user starts AI Studio Live with a real physical tap. This layer only supplies a silent
 * MediaStream for audio-only getUserMedia so AI Studio can keep its own carrier clock while the
 * Android R14/R15 bridge replaces outgoing PCM payloads. No UI element is inspected or activated.
 */
object AiStudioWebSessionPhysicalCarrier {
    const val VERSION = "2026-09-04-r19-trusted-start-silent-carrier"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_PHYSICAL_CARRIER__&&window.__AIS_PHYSICAL_CARRIER__.version)return;
  const VERSION='2026-09-04-r19-trusted-start-silent-carrier';
  const state={installed:false,requests:0,audioOnlyRequests:0,served:0,fallthrough:0,errors:0,created:false,sampleRate:0,lastError:''};
  let stream=null,ctx=null,osc=null,gain=null;

  function bridge(kind,payload){
    try{const b=window.AIStudioWebSessionLab;if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R19C_'+kind,payload:payload||{}}));}catch(_){}
  }
  function createCarrier(){
    if(stream)return stream;
    try{
      const C=window.AudioContext||window.webkitAudioContext;if(!C)throw new Error('AudioContext unavailable');
      ctx=new C({sampleRate:16000});osc=ctx.createOscillator();gain=ctx.createGain();const dest=ctx.createMediaStreamDestination();
      osc.type='sine';osc.frequency.value=173;gain.gain.value=0;osc.connect(gain);gain.connect(dest);osc.start();
      try{const p=ctx.resume();if(p&&typeof p.catch==='function')p.catch(function(){});}catch(_){}
      stream=dest.stream;state.created=true;state.sampleRate=Number(ctx.sampleRate||0);
      bridge('READY',{sampleRate:state.sampleRate,tracks:stream.getAudioTracks().length});return stream;
    }catch(e){state.errors++;state.lastError=String(e&&e.name||'Error');bridge('ERROR',{name:state.lastError,count:state.errors});return null;}
  }
  function describe(){return {ok:true,version:VERSION,installed:state.installed,requests:state.requests,audioOnlyRequests:state.audioOnlyRequests,served:state.served,fallthrough:state.fallthrough,errors:state.errors,created:state.created,sampleRate:state.sampleRate,lastError:state.lastError};}
  function reset(){state.requests=0;state.audioOnlyRequests=0;state.served=0;state.fallthrough=0;state.errors=0;state.lastError='';return describe();}

  try{
    const md=navigator.mediaDevices;
    if(md&&typeof md.getUserMedia==='function'&&!md.getUserMedia.__aisPhysicalCarrier){
      const native=md.getUserMedia.bind(md);
      const wrapped=function(constraints){
        state.requests++;
        try{
          const c=constraints||{},audio=!!c.audio,video=!!c.video;
          if(audio&&!video){
            state.audioOnlyRequests++;const s=createCarrier();
            if(s){state.served++;bridge('SERVED',{served:state.served});return Promise.resolve(s);}
          }
        }catch(e){state.errors++;state.lastError=String(e&&e.name||'Error');}
        state.fallthrough++;return native(constraints);
      };
      wrapped.__aisPhysicalCarrier=true;md.getUserMedia=wrapped;state.installed=true;
    }
  }catch(e){state.errors++;state.lastError=String(e&&e.name||'Error');}
  window.__AIS_PHYSICAL_CARRIER__={version:VERSION,describe:describe,reset:reset,ensure:createCarrier};
  bridge('INSTALLED',{version:VERSION,installed:state.installed});
})();
    """.trimIndent()
}
