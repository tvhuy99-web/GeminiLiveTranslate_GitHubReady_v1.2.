package com.oai.geminilivetranslate.ui

/**
 * R11.4 attachment-composer submit target.
 *
 * Device evidence proved that a video attachment is valid and AI Studio can GenerateContent when
 * the real composer Send control is pressed manually, while the generic text Enter route and a
 * broad Send/Run search can miss the correct control. This document-start runtime therefore:
 * 1) scopes submit candidates to the composer that contains BOTH the prompt and attachment chip;
 * 2) scores structural/geometry/listener evidence instead of label text alone;
 * 3) learns a proven submit control when a trusted manual click is followed by GenerateContent;
 * 4) exposes submitIfAttachment() so Android can use the learned/composer-scoped control before
 *    declaring NO_HANDLER_TRIGGERED_REQUEST.
 *
 * It never reads cookies, auth headers, passwords, API keys, or file bytes.
 */
object AiStudioWebSessionR11SubmitTargetFix {
    const val VERSION = "2026-09-04-web-session-r11.5-native-composer-submit"

    val DOCUMENT_START: String = """
        (function(){
          'use strict';
          if(window.__AIS_R11_SUBMIT_TARGET__ && window.__AIS_R11_SUBMIT_TARGET__.version==='$VERSION')return;

          const clickEntries=[];
          let nextClickId=1;
          let provenButton=null;
          let provenFingerprint=null;
          let lastTrustedButton=null;
          let lastTrustedAt=0;
          let submitAttempts=0;

          function emit(kind,payload){
            try{
              if(window.AIStudioWebSessionLab&&window.AIStudioWebSessionLab.onJsEvent){
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            }catch(_){}
          }

          function visible(el){
            try{
              if(!el||!el.isConnected)return false;
              const r=el.getBoundingClientRect(),s=getComputedStyle(el);
              return r.width>=1&&r.height>=1&&s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity||1)>0;
            }catch(_){return false;}
          }

          function disabled(el){
            try{return !!el.disabled||String(el.getAttribute&&el.getAttribute('aria-disabled')||'').toLowerCase()==='true';}
            catch(_){return false;}
          }

          function labelOf(el){
            try{
              const icon=el&&el.querySelector&&el.querySelector('mat-icon,[class*="icon"],svg title');
              return [
                el&&el.textContent||'',
                el&&el.getAttribute&&el.getAttribute('aria-label')||'',
                el&&el.getAttribute&&el.getAttribute('title')||'',
                el&&el.getAttribute&&el.getAttribute('data-tooltip')||'',
                el&&el.getAttribute&&el.getAttribute('data-testid')||'',
                el&&el.getAttribute&&el.getAttribute('data-test-id')||'',
                icon&&icon.textContent||''
              ].join(' ').replace(/\s+/g,' ').trim().slice(0,500);
            }catch(_){return '';}
          }

          function elementFromEventTarget(target){
            try{
              if(!target)return null;
              const el=target.nodeType===1?target:target.parentElement;
              return el&&el.closest?el.closest('button,[role="button"]'):null;
            }catch(_){return null;}
          }

          function requestFixState(){
            try{
              const fix=window.__AIS_R11_REQUEST_FIX__;
              return fix&&typeof fix.state==='function'?(fix.state()||{}):{};
            }catch(_){return {};}
          }

          function expectedName(){
            const s=requestFixState();
            return String(s.attachmentExpectedName||'').slice(0,260);
          }

          function attachmentWindowActive(){
            const s=requestFixState();
            return !!s.attachmentExpectedName && Date.now()<=Number(s.attachmentWindowUntil||0);
          }

          function findAttachmentSurface(){
            const name=expectedName();
            if(!name)return null;
            try{
              const nodes=document.querySelectorAll('span,div,p,[aria-label],[title]');
              let best=null,bestChars=100000000;
              for(let i=0;i<nodes.length&&i<5000;i++){
                const n=nodes[i];if(!visible(n))continue;
                const text=[n.textContent||'',n.getAttribute&&n.getAttribute('aria-label')||'',n.getAttribute&&n.getAttribute('title')||''].join(' ');
                if(text.indexOf(name)<0)continue;
                const chars=text.length;
                if(chars<bestChars){best=n;bestChars=chars;}
              }
              return best;
            }catch(_){return null;}
          }

          function promptCandidates(){
            const out=[];
            try{
              const nodes=document.querySelectorAll('textarea,[role="textbox"],[contenteditable="true"],input[type="text"]');
              for(let i=0;i<nodes.length&&i<600;i++){
                const el=nodes[i];if(!visible(el)||disabled(el))continue;
                let score=100;
                const tag=String(el.tagName||'');
                const role=String(el.getAttribute&&el.getAttribute('role')||'');
                const ph=String(el.getAttribute&&el.getAttribute('placeholder')||'');
                if(tag==='TEXTAREA')score+=900;
                if(role==='textbox')score+=500;
                if(el.isContentEditable)score+=350;
                if(/prompt|message|chat|ask|type|nhập|tin nhắn/i.test(ph))score+=500;
                const r=el.getBoundingClientRect();score+=Math.min(400,Math.round((r.width*r.height)/2500));
                out.push({el:el,score:score});
              }
            }catch(_){}
            out.sort(function(a,b){return b.score-a.score;});
            return out;
          }

          function ancestorChain(el,limit){
            const out=[];let n=el;let guard=0;
            try{while(n&&guard++<(limit||16)){out.push(n);n=n.parentElement||null;}}catch(_){}
            return out;
          }

          function findComposerRoot(prompt,attachment){
            if(!prompt||!attachment)return null;
            const chain=ancestorChain(prompt,16);
            for(let i=0;i<chain.length;i++){
              const n=chain[i];
              try{
                if(n!==document.body&&n!==document.documentElement&&n.contains&&n.contains(attachment))return n;
              }catch(_){}
            }
            return null;
          }

          function distanceScore(prompt,button){
            try{
              const a=prompt.getBoundingClientRect(),b=button.getBoundingClientRect();
              const ax=a.right,ay=a.bottom,bx=(b.left+b.right)/2,by=(b.top+b.bottom)/2;
              const d=Math.sqrt(Math.pow(ax-bx,2)+Math.pow(ay-by,2));
              if(d<80)return 1000;
              if(d<160)return 800;
              if(d<280)return 600;
              if(d<450)return 350;
              if(d<700)return 150;
            }catch(_){}
            return 0;
          }

          function clickRelationScore(entry,button){
            if(!entry||!entry.active)return -100000;
            try{
              if(entry.target===button)return 1800;
              if(entry.target===button.parentElement)return 1200;
              if(entry.target&&typeof entry.target.contains==='function'&&entry.target.contains(button))return 900;
              if(entry.target===document.body)return 350;
              if(entry.target===document)return 300;
              if(entry.target===window)return 250;
            }catch(_){}
            return -100000;
          }

          function fingerprint(button,composerRoot,prompt,attachment){
            try{
              const r=button.getBoundingClientRect();
              return {
                tag:String(button.tagName||'').slice(0,40),
                role:String(button.getAttribute&&button.getAttribute('role')||'').slice(0,80),
                type:String(button.getAttribute&&button.getAttribute('type')||'').slice(0,40),
                label:labelOf(button).slice(0,180),
                testId:String(button.getAttribute&&button.getAttribute('data-testid')||button.getAttribute&&button.getAttribute('data-test-id')||'').slice(0,120),
                inComposer:!!(composerRoot&&composerRoot.contains&&composerRoot.contains(button)),
                hasPrompt:!!prompt,
                hasAttachment:!!attachment,
                x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)
              };
            }catch(_){return {label:labelOf(button).slice(0,180)};}
          }

          function discover(){
            const attachment=findAttachmentSurface();
            const prompts=promptCandidates();
            let prompt=prompts.length?prompts[0].el:null;
            let composerRoot=findComposerRoot(prompt,attachment);

            if(attachment&&prompts.length>1&&!composerRoot){
              for(let i=0;i<prompts.length;i++){
                const root=findComposerRoot(prompts[i].el,attachment);
                if(root){prompt=prompts[i].el;composerRoot=root;break;}
              }
            }

            const out=[];
            try{
              const nodes=document.querySelectorAll('button,[role="button"]');
              for(let i=0;i<nodes.length&&i<2200;i++){
                const b=nodes[i];if(!visible(b))continue;
                const label=labelOf(b);
                let score=0;
                const isDisabled=disabled(b);
                if(isDisabled)score-=5000;
                if(provenButton&&provenButton===b&&b.isConnected)score+=10000;
                if(composerRoot&&composerRoot.contains&&composerRoot.contains(b))score+=3500;
                if(String(b.getAttribute&&b.getAttribute('type')||'').toLowerCase()==='submit')score+=1600;
                if(/(^|\b)(send|gửi)(\b|$)/i.test(label))score+=1800;
                if(/(^|\b)(run|submit|chạy)(\b|$)/i.test(label))score+=1000;
                if(/send\s*(message|prompt)|gửi\s*(tin|yêu cầu)/i.test(label))score+=900;
                if(/attach|upload|add\s*file|remove|delete|close|cancel|stop|đính\s*kèm|tải\s*tệp|xóa|đóng|hủy/i.test(label))score-=3200;
                if(prompt)score+=distanceScore(prompt,b);
                let listenerCount=0,listenerScore=-100000;
                for(let j=0;j<clickEntries.length;j++){
                  const s=clickRelationScore(clickEntries[j],b);
                  if(s>-100000){listenerCount+=1;if(s>listenerScore)listenerScore=s;}
                }
                if(listenerScore>-100000)score+=Math.min(1000,Math.round(listenerScore/2));
                if(!label&&composerRoot&&composerRoot.contains&&composerRoot.contains(b)&&b.querySelector&&b.querySelector('svg,mat-icon'))score+=500;
                out.push({button:b,score:score,label:label,disabled:isDisabled,listenerCount:listenerCount,listenerScore:listenerScore});
              }
            }catch(_){}
            out.sort(function(a,b){return b.score-a.score;});
            return {attachment:attachment,prompt:prompt,composerRoot:composerRoot,candidates:out};
          }

          function invokeListener(entry,button){
            try{
              let ev=new MouseEvent('click',{bubbles:true,cancelable:true,composed:true,view:window});
              try{
                ev=new Proxy(ev,{get:function(obj,prop){
                  if(prop==='target'||prop==='srcElement')return button;
                  if(prop==='currentTarget')return entry.target;
                  if(prop==='composedPath')return function(){const p=[];let n=button;while(n){p.push(n);n=n.parentNode||n.host||null;}p.push(document);p.push(window);return p;};
                  const v=Reflect.get(obj,prop,obj);return typeof v==='function'?v.bind(obj):v;
                }});
              }catch(_){}
              if(typeof entry.listener==='function')entry.listener.call(entry.target,ev);
              else if(entry.listener&&typeof entry.listener.handleEvent==='function')entry.listener.handleEvent.call(entry.listener,ev);
              else return false;
              return true;
            }catch(err){emit('R11_SUBMIT_TARGET_LISTENER_ERROR',{error:String(err).slice(0,700)});return false;}
          }

          function attachmentPresent(){
            return attachmentWindowActive()&&!!findAttachmentSurface();
          }

          function submitIfAttachment(){
            const net=window.__AIS_WEB_SESSION__;
            const baseline=Number(net&&net.captureCount||0);
            if(!attachmentPresent())return {ok:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};
            const d=discover(),list=d.candidates;
            emit('R11_SUBMIT_TARGET_DISCOVERY',{
              expectedName:expectedName(),hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,
              baselineCaptureCount:baseline,count:list.length,
              top:list.slice(0,8).map(function(x){return {score:x.score,label:x.label.slice(0,180),disabled:x.disabled,listenerCount:x.listenerCount,listenerScore:x.listenerScore,fingerprint:fingerprint(x.button,d.composerRoot,d.prompt,d.attachment)};})
            });
            if(!list.length)return {ok:false,error:'NO_BUTTON_CANDIDATE',baselineCaptureCount:baseline};
            const best=list[0];
            if(best.disabled||best.score<2500)return {ok:false,error:'NO_HIGH_CONFIDENCE_SUBMIT',score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline};

            submitAttempts+=1;
            let clicked=false,error='';
            try{
              if(window.HTMLElement&&HTMLElement.prototype&&HTMLElement.prototype.click)HTMLElement.prototype.click.call(best.button);
              else best.button.click();
              clicked=true;
            }catch(err){error=String(err).slice(0,700);}
            emit('R11_SUBMIT_TARGET_CLICK',{
              ok:clicked,attempt:submitAttempts,score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline,
              proven:provenButton===best.button,fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment),error:error
            });

            setTimeout(function(){
              const now=Number(net&&net.captureCount||0);
              if(now>baseline){
                provenButton=best.button;
                provenFingerprint=fingerprint(best.button,d.composerRoot,d.prompt,d.attachment);
                emit('R11_SUBMIT_TARGET_RESULT',{ok:true,path:'composer-button-click',baselineCaptureCount:baseline,captureCount:now,proven:true,fingerprint:provenFingerprint});
                return;
              }
              const supports=clickEntries.map(function(e){return {entry:e,score:clickRelationScore(e,best.button)};})
                .filter(function(x){return x.entry.active&&x.score>=900;}).sort(function(a,b){return b.score-a.score;}).slice(0,6);
              let invoked=0;
              for(let i=0;i<supports.length;i++)if(invokeListener(supports[i].entry,best.button))invoked+=1;
              emit('R11_SUBMIT_TARGET_LISTENER_FALLBACK',{invoked:invoked,baselineCaptureCount:baseline,captureCount:Number(net&&net.captureCount||0),support:supports.map(function(x){return {entryId:x.entry.id,score:x.score};})});
              setTimeout(function(){
                const finalCount=Number(net&&net.captureCount||0),ok=finalCount>baseline;
                if(ok){provenButton=best.button;provenFingerprint=fingerprint(best.button,d.composerRoot,d.prompt,d.attachment);}
                emit('R11_SUBMIT_TARGET_RESULT',{ok:ok,path:invoked>0?'composer-click-listener':'composer-button-click',baselineCaptureCount:baseline,captureCount:finalCount,proven:ok,fingerprint:ok?provenFingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)});
              },280);
            },260);
            return {ok:clicked,pending:true,attempted:true,baselineCaptureCount:baseline,score:best.score,label:best.label.slice(0,180),proven:provenButton===best.button};
          }

          function nativeTargetIfAttachment(){
            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);
            if(!attachmentPresent())return {ok:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};
            const d=discover(),list=d.candidates;
            emit('R11_NATIVE_SUBMIT_DISCOVERY',{expectedName:expectedName(),hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,baselineCaptureCount:baseline,count:list.length,top:list.slice(0,8).map(function(x){return {score:x.score,label:x.label.slice(0,180),disabled:x.disabled,fingerprint:fingerprint(x.button,d.composerRoot,d.prompt,d.attachment)};})});
            if(!list.length)return {ok:false,error:'NO_BUTTON_CANDIDATE',baselineCaptureCount:baseline};
            const best=list[0];
            if(best.disabled||best.score<2500)return {ok:false,error:'NO_HIGH_CONFIDENCE_SUBMIT',score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline};
            try{
              const r=best.button.getBoundingClientRect(),vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||1);
              const cx=r.left+r.width/2,cy=r.top+r.height/2;
              if(r.width<2||r.height<2||cx<0||cy<0||cx>vw||cy>vh)return {ok:false,error:'SUBMIT_OUT_OF_VIEW',baselineCaptureCount:baseline,score:best.score};
              return {ok:true,native:true,xRatio:cx/vw,yRatio:cy/vh,baselineCaptureCount:baseline,score:best.score,label:best.label.slice(0,180),fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};
            }catch(err){return {ok:false,error:'SUBMIT_GEOMETRY_ERROR',detail:String(err).slice(0,500),baselineCaptureCount:baseline};}
          }

          function installClickTracking(){
            try{
              if(!window.EventTarget||!EventTarget.prototype)return false;
              const proto=EventTarget.prototype,currentAdd=proto.addEventListener,currentRemove=proto.removeEventListener;
              if(currentAdd&&currentAdd.__aisR11SubmitTargetTracking)return true;
              const addWrapped=function(type,listener,options){
                try{if(String(type||'')==='click'&&listener&&clickEntries.length<3000)clickEntries.push({id:nextClickId++,target:this,listener:listener,options:options,active:true,at:Date.now()});}catch(_){}
                return currentAdd.apply(this,arguments);
              };
              addWrapped.__aisR11SubmitTargetTracking=true;proto.addEventListener=addWrapped;
              if(currentRemove){
                const removeWrapped=function(type,listener,options){
                  try{if(String(type||'')==='click'&&listener){for(let i=clickEntries.length-1;i>=0;i--){const e=clickEntries[i];if(e.active&&e.target===this&&e.listener===listener){e.active=false;break;}}}}catch(_){}
                  return currentRemove.apply(this,arguments);
                };
                removeWrapped.__aisR11SubmitTargetTracking=true;proto.removeEventListener=removeWrapped;
              }
              return true;
            }catch(err){emit('R11_SUBMIT_TARGET_TRACKING_ERROR',{error:String(err).slice(0,700)});return false;}
          }

          function installTrustedLearning(){
            try{
              if(document.__aisR11SubmitLearning)return true;
              document.addEventListener('click',function(ev){
                if(!ev||ev.isTrusted!==true||!attachmentWindowActive())return;
                const b=elementFromEventTarget(ev.target);if(!b||!visible(b)||disabled(b))return;
                const d=discover();
                const item=d.candidates.find(function(x){return x.button===b;});
                if(!item||item.score<1800)return;
                const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);
                lastTrustedButton=b;lastTrustedAt=Date.now();
                emit('R11_SUBMIT_TRUSTED_CLICK_SEEN',{score:item.score,label:item.label.slice(0,180),baselineCaptureCount:baseline,fingerprint:fingerprint(b,d.composerRoot,d.prompt,d.attachment)});
                setTimeout(function(){
                  const now=Number(net&&net.captureCount||0);
                  if(lastTrustedButton===b&&Date.now()-lastTrustedAt<2500&&now>baseline){
                    provenButton=b;provenFingerprint=fingerprint(b,d.composerRoot,d.prompt,d.attachment);
                    emit('R11_SUBMIT_TARGET_LEARNED',{baselineCaptureCount:baseline,captureCount:now,fingerprint:provenFingerprint});
                  }
                },1200);
              },true);
              document.__aisR11SubmitLearning=true;
              return true;
            }catch(err){emit('R11_SUBMIT_TARGET_LEARNING_ERROR',{error:String(err).slice(0,700)});return false;}
          }

          installClickTracking();
          installTrustedLearning();

          window.__AIS_R11_SUBMIT_TARGET__={
            version:'$VERSION',
            discover:function(){
              const d=discover();
              return {ok:true,version:'$VERSION',attachmentPresent:attachmentPresent(),hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,
                proven:!!(provenButton&&provenButton.isConnected),provenFingerprint:provenFingerprint,
                candidates:d.candidates.slice(0,10).map(function(x){return {score:x.score,label:x.label.slice(0,180),disabled:x.disabled,listenerCount:x.listenerCount,listenerScore:x.listenerScore,fingerprint:fingerprint(x.button,d.composerRoot,d.prompt,d.attachment)};})};
            },
            nativeTargetIfAttachment:nativeTargetIfAttachment,
            submitIfAttachment:submitIfAttachment,
            state:function(){return {ok:true,version:'$VERSION',submitAttempts:submitAttempts,proven:!!(provenButton&&provenButton.isConnected),provenFingerprint:provenFingerprint,clickEntries:clickEntries.filter(function(e){return e.active;}).length};}
          };
          emit('R11_SUBMIT_TARGET_INSTALLED',{version:'$VERSION'});
        })();
    """.trimIndent()
}
