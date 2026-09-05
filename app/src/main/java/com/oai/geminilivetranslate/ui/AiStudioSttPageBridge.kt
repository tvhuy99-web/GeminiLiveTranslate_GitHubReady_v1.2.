package com.oai.geminilivetranslate.ui

/** Dedicated bridge for AI Studio's Speech-to-Text surface opened with the transcribe model URL. */
object AiStudioSttPageBridge {
    const val VERSION = "2026-09-05-stt-page-r4-viewport-hit-test"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_STT_PAGE__ && window.__AIS_STT_PAGE__.version === '$VERSION') return;
          const state={fileChooserServed:false,fileName:'',fileMime:'',fileSize:-1,fileServedAt:0,baselineLines:null,runStartedAt:0};
          function emit(kind,payload){try{if(window.AIStudioWebSessionLab&&window.AIStudioWebSessionLab.onJsEvent)window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));}catch(_){}}
          function visible(el){try{if(!el||!el.isConnected)return false;const r=el.getBoundingClientRect(),s=getComputedStyle(el);return r.width>=1&&r.height>=1&&s.display!=='none'&&s.visibility!=='hidden';}catch(_){return false;}}
          function txt(el){try{return [el.textContent||'',el.getAttribute&&el.getAttribute('aria-label')||'',el.getAttribute&&el.getAttribute('title')||''].join(' ').replace(/\s+/g,' ').trim();}catch(_){return '';}}
          function model(raw){return String(raw||'').trim().replace(/^models\//i,'').slice(0,140);}
          function surface(){let m='';try{m=model(new URL(location.href).searchParams.get('model'));}catch(_){}const zero=!!document.querySelector('ms-stt-zero-state');const input=!!document.querySelector('input[data-test-upload-file-input],input[type="file"][accept*="audio"]');let title=false;try{title=/turn speech to transcriptions/i.test(String(document.body&&document.body.innerText||'').slice(0,120000));}catch(_){}return{urlModel:m,zeroState:zero,audioInput:input,title:title,stt:zero||input||title};}
          function sttContext(el){try{let n=el;for(let i=0;n&&i<10;i++,n=n.parentElement){const tag=String(n.tagName||'').toLowerCase(),cls=String(n.className||''),id=String(n.id||''),test=String((n.getAttribute&&n.getAttribute('data-test-id'))||'')+' '+String((n.getAttribute&&n.getAttribute('data-testid'))||'');if(tag.indexOf('ms-stt')===0||/\b(stt|transcrib|transcription)\b/i.test(cls+' '+id+' '+test))return true;}}catch(_){}return false;}
          function actionMeta(el){try{return [String(el.id||''),String(el.className||''),String((el.getAttribute&&el.getAttribute('name'))||''),String((el.getAttribute&&el.getAttribute('data-test-id'))||''),String((el.getAttribute&&el.getAttribute('data-testid'))||'')].join(' ').replace(/\s+/g,' ').trim();}catch(_){return '';}}
          function runButton(){
            let nodes=[];try{nodes=document.querySelectorAll('button,[role="button"],input[type="button"],input[type="submit"]');}catch(_){}
            let best=null,score=-9999;const ranked=[];
            for(let i=0;i<nodes.length&&i<2600;i++){
              const el=nodes[i];if(!visible(el))continue;
              const t=txt(el),m=actionMeta(el),ctx=sttContext(el);let s=0;
              if(/^(start transcription|start transcribing|transcribe|start|begin|run|generate|bắt đầu|chép lời|phiên âm|chạy)$/i.test(t))s+=6500;
              else if(/\b(start transcription|start transcribing|transcribe|transcription|start|begin|run|generate|bắt đầu|chép lời|phiên âm|chạy)\b/i.test(t))s+=2600;
              if(/\b(run|start|transcrib|generate|submit)\b/i.test(m))s+=2600;
              if(ctx)s+=900;
              if(String(el.tagName||'')==='BUTTON')s+=300;
              if(/skip to main content|upload|record audio|\brecord\b|drive|settings|tune|search|microphone|\bmic\b|share prompt|new chat|view more actions|toggle navigation/i.test(t))s-=9000;
              if(/upload|record|drive|settings|search|nav|menu/i.test(m)&&!/run|start|transcrib|generate|submit/i.test(m))s-=3500;
              const disabled=!!(el.disabled||(el.getAttribute&&el.getAttribute('aria-disabled')==='true'));
              if(disabled)s-=5000;
              ranked.push({s:s,label:t.slice(0,180),meta:m.slice(0,220),tag:String(el.tagName||''),ctx:ctx,disabled:disabled});
              if(s>score){best=el;score=s;}
            }
            ranked.sort(function(a,b){return b.s-a.s});
            return{el:best,score:score,candidates:ranked.slice(0,8)};
          }
          function point(el){
            if(!el)return{ok:false,error:'NO_TARGET'};
            try{
              const vw=Math.max(1,innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,innerHeight||document.documentElement.clientHeight||1);
              let r=el.getBoundingClientRect();
              let scrolled=false;
              const centerX=r.left+r.width*.5,centerY=r.top+r.height*.5;
              if(r.top<1||r.bottom>vh-1||r.left<1||r.right>vw-1||centerX<0||centerX>vw||centerY<0||centerY>vh){
                try{el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});scrolled=true;}catch(_){try{el.scrollIntoView();scrolled=true;}catch(__){}}
                r=el.getBoundingClientRect();
              }
              if(r.width<1||r.height<1)return{ok:false,error:'TARGET_ZERO_SIZE',label:txt(el).slice(0,160),scrolled:scrolled};
              const l=Math.max(0,r.left),t=Math.max(0,r.top),rr=Math.min(vw,r.right),bb=Math.min(vh,r.bottom);
              if(rr-l<2||bb-t<2)return{ok:false,error:'TARGET_OUT_OF_VIEW',label:txt(el).slice(0,160),scrolled:scrolled,rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},viewport:{width:vw,height:vh}};
              const xs=[.5,.35,.65,.2,.8],ys=[.5,.35,.65,.2,.8];
              let firstHit=null,firstX=(l+rr)*.5,firstY=(t+bb)*.5;
              for(let yi=0;yi<ys.length;yi++){
                for(let xi=0;xi<xs.length;xi++){
                  const x=l+(rr-l)*xs[xi],y=t+(bb-t)*ys[yi],hit=document.elementFromPoint(x,y);
                  if(!firstHit){firstHit=hit;firstX=x;firstY=y;}
                  if(hit&&(hit===el||el.contains(hit))){
                    return{ok:true,error:'',xRatio:x/vw,yRatio:y/vh,label:txt(el).slice(0,160),scrolled:scrolled,hit:{tag:String(hit.tagName||''),label:txt(hit).slice(0,160)},rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},viewport:{width:vw,height:vh}};
                  }
                }
              }
              return{ok:false,error:'TARGET_COVERED',xRatio:firstX/vw,yRatio:firstY/vh,label:txt(el).slice(0,160),scrolled:scrolled,hit:firstHit?{tag:String(firstHit.tagName||''),label:txt(firstHit).slice(0,160)}:null,rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},viewport:{width:vw,height:vh}};
            }catch(e){return{ok:false,error:String(e)}}
          }
          function clean(s){s=String(s||'').replace(/\s+/g,' ').trim();if(!s)return'';if(/^(turn speech to transcriptions|record or provide audio.*|click or drop file here|audio files only|upload|drive|record audio|run|start transcription|start transcribing|transcribe|generate|playground)$/i.test(s))return'';return s;}
          function captureBaseline(){const set=new Set();try{String(document.body&&document.body.innerText||'').split(/\r?\n/).forEach(function(x){x=clean(x);if(x)set.add(x);});}catch(_){}state.baselineLines=set;}
          function resultText(){const candidates=[];let nodes=[];try{nodes=document.querySelectorAll('ms-stt-transcription,ms-transcription,[data-test-id*="transcript"],[data-testid*="transcript"],[class*="transcript"],[class*="transcription"],[aria-label*="transcript"],ms-chat-turn,[class*="response"]');}catch(_){}for(let i=0;i<nodes.length&&i<1800;i++){if(!visible(nodes[i]))continue;const t=clean(nodes[i].innerText||nodes[i].textContent||'');if(t.length>=2)candidates.push({t:t,s:t.length+500});}try{const base=state.baselineLines||new Set();String(document.body&&document.body.innerText||'').split(/\r?\n/).forEach(function(line){const t=clean(line);if(t.length>=2&&!base.has(t))candidates.push({t:t,s:t.length});});}catch(_){}candidates.sort(function(a,b){return b.s-a.s});return candidates.length?candidates[0].t:'';}
          const api={version:'$VERSION',
            pageState:function(expected){try{const s=surface(),e=model(expected),modelOk=!e||!s.urlModel||s.urlModel===e,out={ok:true,ready:!!(s.stt&&modelOk),expectedModel:e,urlModel:s.urlModel,sttSurface:s.stt,zeroState:s.zeroState,audioInput:s.audioInput,title:s.title,path:String(location.pathname||'').slice(0,300)};emit('R28_STT_PAGE_STATE',out);return out;}catch(e){const out={ok:false,ready:false,error:'PAGE_STATE_EXCEPTION',message:String(e),path:String(location.pathname||'').slice(0,300)};emit('R28_STT_PAGE_STATE_ERROR',out);return out;}},
            uploadTarget:function(){let nodes=[];try{nodes=document.querySelectorAll('button.upload-file-menu-item,button[aria-label="Upload"],button,[role="button"]');}catch(_){}let best=null,score=-9999;for(let i=0;i<nodes.length&&i<1800;i++){const el=nodes[i];if(!visible(el))continue;const t=txt(el);let s=0;if(el.matches&&el.matches('button.upload-file-menu-item'))s+=6000;if(/^upload$/i.test(t))s+=4500;else if(/\bupload\b/i.test(t))s+=1800;if(/record|drive|run|search/i.test(t))s-=3500;if(s>score){best=el;score=s;}}const out=point(best);out.score=score;emit('R28_STT_UPLOAD_TARGET',{ok:out.ok,error:out.error||'',score:score,label:out.label||'',scrolled:!!out.scrolled,hit:out.hit||null,rect:out.rect||null,viewport:out.viewport||null});return out;},
            markFileChooserServed:function(name,mime,size){state.fileChooserServed=true;state.fileName=String(name||'').slice(0,260);state.fileMime=String(mime||'').slice(0,180);state.fileSize=Number(size||-1);state.fileServedAt=Date.now();const out={ok:true,name:state.fileName,mime:state.fileMime,size:state.fileSize};emit('R28_STT_FILE_CHOOSER_SERVED',out);return out;},
            fileState:function(expectedName){const name=String(expectedName||state.fileName||'');let nameVisible=false,busy=false;try{const body=String(document.body&&document.body.innerText||'').slice(-180000);nameVisible=!!name&&body.indexOf(name)>=0;busy=/uploading|processing|preparing|đang\s*tải|đang\s*xử\s*lý/i.test(body.slice(-40000));}catch(_){}const r=runButton(),disabled=!!(r.el&&(r.el.disabled||(r.el.getAttribute&&r.el.getAttribute('aria-disabled')==='true'))),runReady=!!(r.el&&r.score>=1800&&!disabled),settled=state.fileChooserServed&&Date.now()-state.fileServedAt>=700,ready=!!(state.fileChooserServed&&runReady&&!busy&&(nameVisible||settled)),out={ok:true,present:state.fileChooserServed,ready:ready,busy:busy,nameVisible:nameVisible,runReady:runReady,runScore:r.score,runLabel:r.el?txt(r.el).slice(0,160):'',runCandidates:r.candidates,servedMs:state.fileChooserServed?Date.now()-state.fileServedAt:-1};emit('R28_STT_FILE_STATE',out);return out;},
            runTarget:function(){const r=runButton(),out=point(r.el);out.score=r.score;out.candidates=r.candidates;let base=-1;try{base=Number(window.__AIS_WEB_SESSION__&&window.__AIS_WEB_SESSION__.captureCount||0);}catch(_){}out.baselineCaptureCount=base;captureBaseline();state.runStartedAt=Date.now();emit('R28_STT_RUN_TARGET',{ok:out.ok,error:out.error||'',score:r.score,label:out.label||'',scrolled:!!out.scrolled,candidates:r.candidates,baselineCaptureCount:base,hit:out.hit||null,rect:out.rect||null,viewport:out.viewport||null});return out;},
            resultState:function(){const t=resultText();let net=null,status=-1,responseChars=0,partial=true,netOk=false;try{net=window.__AIS_WEB_SESSION__&&window.__AIS_WEB_SESSION__.getLastSafeResponse?window.__AIS_WEB_SESSION__.getLastSafeResponse():null;if(net){status=Number(net.status||-1);responseChars=Number(net.responseChars||0);partial=!!net.partial;netOk=!!net.ok;}}catch(_){}const out={ok:true,text:t,textChars:t.length,source:t?'dom':'',status:status,responseChars:responseChars,partial:partial,networkOk:netOk,terminal:!!(net&&netOk&&!partial),elapsedMs:state.runStartedAt?Date.now()-state.runStartedAt:-1};emit('R28_STT_RESULT_STATE',{ok:true,textChars:out.textChars,source:out.source,status:status,responseChars:responseChars,partial:partial,networkOk:netOk,terminal:out.terminal,elapsedMs:out.elapsedMs});return out;}
          };
          window.__AIS_STT_PAGE__=api;emit('R28_STT_BRIDGE_INSTALLED',{version:api.version,path:String(location.pathname||'').slice(0,300)});
        })();
    """.trimIndent()
}
