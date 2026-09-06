package com.oai.geminilivetranslate.ui

object AiStudioWebSessionAdaptiveRuntime {
    const val VERSION = "2026-09-05-web-session-discovery-r1"

    val DOCUMENT_START: String = """
        (function(){
          'use strict';
          if(window.__AIS_ADAPTIVE_RUNTIME__&&window.__AIS_ADAPTIVE_RUNTIME__.version==='$VERSION')return;
          if(!window.EventTarget||!window.EventTarget.prototype)return;
          const nativeAdd=window.EventTarget.prototype.addEventListener;
          const nativeRemove=window.EventTarget.prototype.removeEventListener;
          const entries=[];
          const groups=[];
          let nextEntryId=1,nextGroupId=1,generation=1;
          function tracked(type){const t=String(type||'');return t==='input'||t==='change'||t==='keydown';}
          function targetMeta(target){
            try{
              if(target===window)return {kind:'window',tag:'',role:'',valueCapable:false,contentEditable:false,connected:true};
              if(target===document)return {kind:'document',tag:'',role:'',valueCapable:false,contentEditable:false,connected:true};
              if(target&&target.nodeType===11&&target.host)return {kind:'shadow-root',tag:String(target.host.tagName||''),role:'',valueCapable:false,contentEditable:false,connected:!!target.host.isConnected};
              return {kind:'element',tag:String(target&&target.tagName||'').slice(0,40),role:String(target&&target.getAttribute&&target.getAttribute('role')||'').slice(0,80),valueCapable:!!(target&&('value' in target)),contentEditable:!!(target&&target.isContentEditable),connected:target&&typeof target.isConnected==='boolean'?!!target.isConnected:true};
            }catch(_){return {kind:'unknown',tag:'',role:'',valueCapable:false,contentEditable:false,connected:false};}
          }
          function groupFor(target){for(let i=0;i<groups.length;i++)if(groups[i].target===target)return groups[i];const g={id:nextGroupId++,target:target};groups.push(g);return g;}
          function capture(type,target,listener,options){if(!tracked(type)||!listener||entries.length>=2400)return;const g=groupFor(target);entries.push({id:nextEntryId++,groupId:g.id,type:String(type),target:target,listener:listener,options:options,active:true,at:Date.now()});generation+=1;}
          function captureFlag(options){try{return typeof options==='boolean'?options:!!(options&&options.capture);}catch(_){return false;}}
          window.EventTarget.prototype.addEventListener=function(type,listener,options){try{capture(type,this,listener,options);}catch(_){}return nativeAdd.apply(this,arguments);};
          window.EventTarget.prototype.removeEventListener=function(type,listener,options){try{if(tracked(type)&&listener){const cap=captureFlag(options);for(let i=entries.length-1;i>=0;i--){const e=entries[i];if(e.active&&e.type===String(type)&&e.target===this&&e.listener===listener&&captureFlag(e.options)===cap){e.active=false;generation+=1;break;}}}}catch(_){}return nativeRemove.apply(this,arguments);};
          function activeFor(groupId,type){return entries.filter(function(e){return e.active&&e.groupId===groupId&&(!type||e.type===type);});}
          function candidateScore(group){const ins=activeFor(group.id,'input'),keys=activeFor(group.id,'keydown');if(!ins.length||!keys.length)return -100000;const meta=targetMeta(group.target);let score=2200;if(meta.connected)score+=180;if(meta.valueCapable)score+=420;if(meta.contentEditable)score+=220;if(meta.role==='textbox')score+=180;if(meta.tag==='TEXTAREA')score+=160;if(meta.tag==='INPUT')score+=80;const gap=Math.abs(Number(ins[ins.length-1].at)-Number(keys[keys.length-1].at));if(gap<=25)score+=360;else if(gap<=100)score+=240;else if(gap<=500)score+=100;return score;}
          function isReadyCandidate(item){const m=item&&item.meta||{};if(!m.connected)return false;if(!(m.valueCapable||m.contentEditable||m.role==='textbox'))return false;if(item.score<3000)return false;return activeFor(item.group.id,'input').length>0&&activeFor(item.group.id,'keydown').length>0;}
          function candidates(){return groups.map(function(g){return {group:g,score:candidateScore(g),meta:targetMeta(g.target)};}).filter(function(x){return x.score>-50000;}).sort(function(a,b){return b.score-a.score;});}
          window.__AIS_ADAPTIVE_RUNTIME__={
            version:'$VERSION',
            discover:function(){const all=candidates(),ready=all.filter(isReadyCandidate);return {ok:true,version:this.version,generation:generation,entryCount:entries.filter(function(e){return e.active;}).length,candidateCount:all.length,readyCandidateCount:ready.length,controllerReady:ready.length>0,top:all.slice(0,10).map(function(x){return {groupId:x.group.id,score:x.score,ready:isReadyCandidate(x),meta:x.meta};})};}
          };
        })();
    """.trimIndent()
}
