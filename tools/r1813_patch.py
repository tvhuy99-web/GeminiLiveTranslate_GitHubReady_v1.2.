from pathlib import Path

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly 1 match, got {count}: {old[:120]!r}')
    write(path, text.replace(old, new, 1))


def replace_between(path, start, end, replacement):
    text = read(path)
    a = text.find(start)
    if a < 0:
        raise SystemExit(f'{path}: start marker missing: {start!r}')
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f'{path}: end marker missing: {end!r}')
    write(path, text[:a] + replacement + text[b:])

SUBMIT = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt'
REQ = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt'
EXEC = 'app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt'
VIDEO = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt'
TRANS = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt'
TEST = 'app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt'

# --- Video submit target: cache prepared target + real DOM hit-test. ---
replace_once(SUBMIT,
    'const val VERSION = "2026-09-05-web-session-r11.8-semantic-submit-file-only"',
    'const val VERSION = "2026-09-05-web-session-r11.9-cached-hit-test-submit"')
replace_once(SUBMIT,
    '          let submitAttempts=0;\n',
    '          let submitAttempts=0;\n          let preparedButton=null;\n          let preparedFingerprint=null;\n          let preparedScore=-1;\n          let preparedAt=0;\n')
replace_once(SUBMIT,
    "          function attachmentPresent(){\n            return attachmentWindowActive()&&!!findAttachmentSurface();\n          }",
    "          function attachmentPresent(){\n            const s=requestFixState();\n            return attachmentWindowActive()&&(!!findAttachmentSurface()||!!s.attachmentFileChangeMatched);\n          }")

insert_marker = '          function discover(){\n'
insert = r'''          function cachedPreparedTarget(){
            try{
              if(!preparedButton||!preparedButton.isConnected||!visible(preparedButton)||disabled(preparedButton))return null;
              if(Date.now()-preparedAt>15000)return null;
              return {button:preparedButton,score:preparedScore,label:labelOf(preparedButton),fingerprint:preparedFingerprint};
            }catch(_){return null;}
          }

          function hitSummary(el){
            try{return {tag:String(el&&el.tagName||'').slice(0,40),role:String(el&&el.getAttribute&&el.getAttribute('role')||'').slice(0,60),label:labelOf(el).slice(0,160)};}
            catch(_){return {tag:'',role:'',label:''};}
          }

          function safeNativePoint(button){
            try{
              if(!button||!button.isConnected||!visible(button)||disabled(button))return {ok:false,error:'SUBMIT_NOT_VISIBLE'};
              let r=button.getBoundingClientRect();
              const vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||1);
              let cx=r.left+r.width/2,cy=r.top+r.height/2;
              if(r.width<2||r.height<2)return {ok:false,error:'SUBMIT_EMPTY_GEOMETRY'};
              if(cx<0||cy<0||cx>vw||cy>vh){
                try{button.scrollIntoView({block:'center',inline:'center'});}catch(_){}
                r=button.getBoundingClientRect();cx=r.left+r.width/2;cy=r.top+r.height/2;
              }
              const fx=[0.50,0.28,0.72,0.50,0.50,0.28,0.72,0.28,0.72];
              const fy=[0.50,0.50,0.50,0.28,0.72,0.28,0.28,0.72,0.72];
              let lastHit=null;
              for(let i=0;i<fx.length;i++){
                const x=r.left+r.width*fx[i],y=r.top+r.height*fy[i];
                if(x<0||y<0||x>vw||y>vh)continue;
                const hit=document.elementFromPoint?document.elementFromPoint(x,y):button;
                lastHit=hit;
                if(hit&&(hit===button||(button.contains&&button.contains(hit)))){
                  return {ok:true,xRatio:x/vw,yRatio:y/vh,x:Math.round(x),y:Math.round(y),sample:i,hit:hitSummary(hit)};
                }
              }
              return {ok:false,error:'SUBMIT_POINT_COVERED',cover:hitSummary(lastHit),rect:{x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)}};
            }catch(err){return {ok:false,error:'SUBMIT_HIT_TEST_ERROR',detail:String(err).slice(0,500)};}
          }

'''
replace_once(SUBMIT, insert_marker, insert + insert_marker)

old_prepare = """            const readiness=submissionReadinessIfAttachment();\n            const out={ok:!!set.ok,baselineCaptureCount:baseline,promptChars:String(promptText||'').length,observedChars:Number(set.observedChars||0),tag:String(set.tag||''),role:String(set.role||''),submitReady:!!readiness.ready,submitDisabled:!!readiness.disabled,submitScore:Number(readiness.score||-1),submitLabel:String(readiness.label||'').slice(0,180),fingerprint:readiness.fingerprint||null};\n            emit('R19_MANUAL_PROMPT_PREPARED',out);\n            return out;"""
new_prepare = """            const readiness=submissionReadinessIfAttachment();\n            try{\n              const post=discover(),semantic=semanticSubmitCandidates(post),best=semantic.length?semantic[0]:null;\n              if(best&&!best.disabled&&best.score>=900){\n                preparedButton=best.button;preparedScore=best.score;preparedAt=Date.now();preparedFingerprint=fingerprint(best.button,post.composerRoot,post.prompt,post.attachment);\n                emit('R23_PREPARED_SUBMIT_TARGET',{score:best.score,label:best.label.slice(0,180),fingerprint:preparedFingerprint});\n              }\n            }catch(_){}\n            const out={ok:!!set.ok,baselineCaptureCount:baseline,promptChars:String(promptText||'').length,observedChars:Number(set.observedChars||0),tag:String(set.tag||''),role:String(set.role||''),submitReady:!!readiness.ready,submitDisabled:!!readiness.disabled,submitScore:Number(readiness.score||-1),submitLabel:String(readiness.label||'').slice(0,180),fingerprint:readiness.fingerprint||null,cachedTarget:!!cachedPreparedTarget()};\n            emit('R19_MANUAL_PROMPT_PREPARED',out);\n            return out;"""
replace_once(SUBMIT, old_prepare, new_prepare)

new_native = r'''          function nativeTargetIfAttachment(){
            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);
            const known=attachmentPresent();
            const d=discover(),semantic=semanticSubmitCandidates(d),cached=cachedPreparedTarget();
            let best=null,fromCache=false;
            if(cached){best={button:cached.button,score:cached.score,label:cached.label,disabled:false};fromCache=true;}
            else if(semantic.length)best=semantic[0];
            emit('R11_NATIVE_SUBMIT_DISCOVERY',{expectedName:expectedName(),attachmentKnown:known,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,baselineCaptureCount:baseline,count:semantic.length,cachedTarget:!!cached,top:semantic.slice(0,8).map(function(x){return {score:x.score,label:x.label.slice(0,180),disabled:x.disabled,fingerprint:fingerprint(x.button,d.composerRoot,d.prompt,d.attachment)};})});
            if(!known&&!cached)return {ok:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};
            if(!best)return {ok:false,error:'NO_SEMANTIC_SUBMIT',baselineCaptureCount:baseline};
            if(best.disabled||Number(best.score||0)<900)return {ok:false,error:'NO_HIGH_CONFIDENCE_SUBMIT',score:Number(best.score||-1),label:String(best.label||'').slice(0,180),baselineCaptureCount:baseline};
            const point=safeNativePoint(best.button);
            emit('R23_NATIVE_SUBMIT_HIT_TEST',{ok:!!point.ok,error:String(point.error||''),fromCache:fromCache,score:Number(best.score||-1),label:String(best.label||'').slice(0,180),point:point.ok?{x:point.x,y:point.y,sample:point.sample,hit:point.hit}:null,cover:point.cover||null,rect:point.rect||null});
            if(!point.ok)return {ok:false,error:String(point.error||'SUBMIT_HIT_TEST_FAILED'),baselineCaptureCount:baseline,score:Number(best.score||-1),cover:point.cover||null,rect:point.rect||null};
            return {ok:true,native:true,hitTest:true,cachedTarget:fromCache,xRatio:point.xRatio,yRatio:point.yRatio,baselineCaptureCount:baseline,score:Number(best.score||-1),label:String(best.label||'').slice(0,180),fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};
          }
'''
replace_between(SUBMIT,
    '          function nativeTargetIfAttachment(){',
    '\n\n          function nativeTargetIfAttachmentFileOnly(){',
    new_native)

# Video may fall back to the page's own composer click if every trusted hit-test point is covered.
replace_once(EXEC,
    '            tryNativeAttachmentSubmit(request.seq, "native-file-primary", 0, allowProgrammaticFallback = false)',
    '            events?.onLog("R23_VIDEO_AUTO_SUBMIT_POLICY", "seq=${request.seq} nativeHitTest=true cachedPreparedTarget=true programmaticFallback=true")\n            tryNativeAttachmentSubmit(request.seq, "native-file-primary", 0, allowProgrammaticFallback = true)')

# Manual transcribe monitor should report the selected model from the public R11 support API.
replace_once(EXEC,
    'var n=window.__AIS_WEB_SESSION__;var f=window.__AIS_R11_REQUEST_FIX__;return {ok:true,captureCount:Number(n&&n.captureCount||0),selectedModel:String(f&&f.selectedModel||\'\'),requestedModel:String(f&&f.requestedModel||\'\')};',
    'var n=window.__AIS_WEB_SESSION__;var s=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.selectionState?window.__AIS_R11_SUPPORT__.selectionState():{};return {ok:true,captureCount:Number(n&&n.captureCount||0),selectedModel:String(s&&s.selectedModel||\'\'),requestedModel:String(s&&s.requestedModel||\'\')};')
replace_once(EXEC,
    'var n=window.__AIS_WEB_SESSION__;var f=window.__AIS_R11_REQUEST_FIX__;var c=Number(n&&n.captureCount||0);return {ok:true,baseline:b,captureCount:c,manualSubmitSeen:b>=0&&c>b,present:!!a.present,ready:!!a.ready,busy:!!a.busy,submitReady:!!a.submitReady,localReadReady:!!a.localReadReady,serverPayloadObserved:!!a.serverPayloadObserved,serverPayloadSettled:!!a.serverPayloadSettled,domState:String(a.domState||\'\'),domReadyAfterBusy:!!a.domReadyAfterBusy,selectedModel:String(f&&f.selectedModel||\'\'),requestedModel:String(f&&f.requestedModel||\'\'),',
    'var n=window.__AIS_WEB_SESSION__;var s=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.selectionState?window.__AIS_R11_SUPPORT__.selectionState():{};var c=Number(n&&n.captureCount||0);return {ok:true,baseline:b,captureCount:c,manualSubmitSeen:b>=0&&c>b,present:!!a.present,ready:!!a.ready,busy:!!a.busy,submitReady:!!a.submitReady,localReadReady:!!a.localReadReady,serverPayloadObserved:!!a.serverPayloadObserved,serverPayloadSettled:!!a.serverPayloadSettled,domState:String(a.domState||\'\'),domReadyAfterBusy:!!a.domReadyAfterBusy,selectedModel:String(s&&s.selectedModel||\'\'),requestedModel:String(s&&s.requestedModel||\'\'),')

# --- GenerateContent diagnostics independent of attachment-payload detection. ---
replace_once(REQ,
    'const val VERSION = "2026-09-05-web-session-r11.10-manual-config-trace"',
    'const val VERSION = "2026-09-05-web-session-r11.11-http-rpc-diagnostic"')
replace_once(REQ,
    "              s = s.replace(/[A-Za-z0-9+\\/_=-]{256,}/g,function(m){return '<LONG_TOKEN_'+m.length+'>';});\n              return s.slice(0,1800);",
    "              s = s.replace(/[A-Za-z0-9+\\/_=-]{256,}/g,function(m){return '<LONG_TOKEN_'+m.length+'>';});\n              s = s.replace(/\\b(?=[A-Za-z0-9_-]{24,80}\\b)(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*[0-9])[A-Za-z0-9_-]+\\b/g,function(m){return '<OPAQUE_'+m.length+'>';});\n              return s.slice(0,1800);")

summary_code = r'''          function summarizeGenerateBody(body) {
            const out={parsed:false,topType:'',topLength:-1,numericVectors:[],literalStrings:[],opaqueStrings:[]};
            try {
              if(typeof body!=='string')return out;
              const root=JSON.parse(body);out.parsed=true;out.topType=Array.isArray(root)?'array':typeof root;out.topLength=Array.isArray(root)?root.length:-1;
              const walk=function(v,path,depth){
                if(depth>7)return;
                if(Array.isArray(v)){
                  const nums=[];
                  for(let i=0;i<v.length;i++)if(typeof v[i]==='number'&&Number.isFinite(v[i]))nums.push({i:i,v:v[i]});
                  if(nums.length>=3&&out.numericVectors.length<16)out.numericVectors.push({path:path,length:v.length,values:nums.slice(0,32)});
                  for(let i=0;i<v.length&&i<40;i++)walk(v[i],path+'['+i+']',depth+1);
                  return;
                }
                if(v&&typeof v==='object'){
                  const keys=Object.keys(v).slice(0,30);
                  for(let i=0;i<keys.length;i++)walk(v[keys[i]],path+'.'+keys[i],depth+1);
                  return;
                }
                if(typeof v==='string'){
                  const s=String(v);
                  if(/^(?:models\/)?gemini-[a-z0-9._-]+$/i.test(s)||/^(?:user|model|assistant)$/i.test(s)||/^[A-Za-z_]+\/[A-Za-z_]+$/.test(s)||/^(?:audio|video)\//i.test(s)){
                    if(out.literalStrings.length<24)out.literalStrings.push({path:path,value:s.slice(0,120)});
                  }else if(out.opaqueStrings.length<32){
                    out.opaqueStrings.push({path:path,length:s.length,kind:s.length>200?'long-token':(s.length>=20?'opaque':'short')});
                  }
                }
              };
              walk(root,'$',0);
            } catch (err) { out.error=String(err).slice(0,300); }
            return out;
          }

          function responseHeaderSummary(xhr) {
            const out={names:[],contentType:'',grpcStatus:'',grpcMessage:''};
            try {
              const raw=String(xhr.getAllResponseHeaders&&xhr.getAllResponseHeaders()||'');
              raw.split(/\r?\n/).forEach(function(line){
                const p=line.indexOf(':');if(p<=0)return;
                const name=line.slice(0,p).trim().toLowerCase(),value=line.slice(p+1).trim();
                if(name&&out.names.indexOf(name)<0&&out.names.length<40)out.names.push(name.slice(0,80));
                if(name==='content-type')out.contentType=value.slice(0,160);
                else if(name==='grpc-status')out.grpcStatus=value.slice(0,80);
                else if(name==='grpc-message')out.grpcMessage=sanitizeTraceText(value).slice(0,400);
              });
            } catch (_) {}
            return out;
          }

          function emitGenerateHttpDiagnostic(xhr, meta, body, phase) {
            try {
              const url=String(meta&&meta.url||'');if(!isGenerateUrl(url))return;
              const status=Number(xhr&&xhr.status||-1);if(status<400)return;
              let text='';try{text=String(xhr.responseText||'');}catch(_){}
              const hp=hostPath(url),headers=responseHeaderSummary(xhr),headerNames=Array.isArray(meta&&meta.headerNames)?meta.headerNames.slice(0,40):[];
              emit('R23_GENERATE_HTTP_DIAGNOSTIC',{
                source:'xhr',phase:String(phase||''),host:hp.host,path:hp.path,method:String(meta&&meta.method||'POST'),status:status,readyState:Number(xhr&&xhr.readyState||-1),
                responseChars:text.length,responsePreview:sanitizeTraceText(text),responseHeaders:headers,
                withCredentials:!!(xhr&&xhr.withCredentials),requestHeaderNames:headerNames,requestBody:summarizeGenerateBody(body)
              });
            } catch (err) { emit('R23_GENERATE_HTTP_DIAGNOSTIC_ERROR',{error:String(err).slice(0,500)}); }
          }

'''
replace_once(REQ, '          function emitGenerateRequestShape(source, url, body, stage) {\n', summary_code + '          function emitGenerateRequestShape(source, url, body, stage) {\n')
replace_once(REQ,
    '                hasDriveRef:/drive|resource[_-]?name|file[_-]?(?:uri|id)|attachment/i.test(body),\n                preview:sanitizeTraceText(body)',
    '                hasDriveRef:/drive|resource[_-]?name|file[_-]?(?:uri|id)|attachment/i.test(body),\n                rpcSummary:summarizeGenerateBody(body),\n                preview:sanitizeTraceText(body)')

new_xhr = r'''          function installXhrRewrite() {
            try {
              if (!window.__AIS_R11_SUPPORT__ || !window.XMLHttpRequest || !XMLHttpRequest.prototype) return false;
              const proto = XMLHttpRequest.prototype;
              const current = proto.send;
              if (!current || current.__aisR11RequestFix === true) {
                fix.xhrRewriteInstalled = !!current;
                return !!current;
              }
              const currentSetHeader=proto.setRequestHeader;
              if(currentSetHeader&&currentSetHeader.__aisR23HeaderNames!==true){
                const wrappedHeader=function(name,value){
                  try{
                    const meta=this.__aisR11||(this.__aisR11={});
                    const n=String(name||'').trim().toLowerCase();
                    if(n){if(!Array.isArray(meta.headerNames))meta.headerNames=[];if(meta.headerNames.indexOf(n)<0&&meta.headerNames.length<40)meta.headerNames.push(n.slice(0,80));}
                  }catch(_){}
                  return currentSetHeader.apply(this,arguments);
                };
                wrappedHeader.__aisR23HeaderNames=true;proto.setRequestHeader=wrappedHeader;
              }
              const wrapped = function(body) {
                let nextBody = body;
                let netToken = null;
                try {
                  const meta = this.__aisR11 || {};
                  nextBody = rewriteBody(meta.url || '', body, 'xhr');
                  const xhr=this;
                  if(isGenerateUrl(meta.url||'')){
                    let lastDiagKey='';
                    const diag=function(phase){
                      try{
                        const status=Number(xhr.status||-1),readyState=Number(xhr.readyState||-1);
                        if(status<400)return;
                        let chars=-1;try{chars=String(xhr.responseText||'').length;}catch(_){}
                        const key=status+':'+readyState+':'+chars+':'+String(phase||'');
                        if(key===lastDiagKey)return;lastDiagKey=key;
                        emitGenerateHttpDiagnostic(xhr,meta,nextBody,phase);
                      }catch(_){}
                    };
                    xhr.addEventListener('readystatechange',function(){if(Number(xhr.readyState||0)>=3)diag('readystatechange');},false);
                    xhr.addEventListener('loadend',function(){diag('loadend');},{once:true});
                  }
                  netToken = noteAttachmentNetStart('xhr',meta.url||'',meta.method||'POST',nextBody);
                  if (netToken) {
                    if (netToken.payloadCandidate && xhr.upload && xhr.upload.addEventListener) {
                      xhr.upload.addEventListener('progress',function(ev){
                        try {
                          if (!ev || !ev.lengthComputable || Number(ev.total||0) <= 0) return;
                          const ratio = Math.max(0,Math.min(1,Number(ev.loaded||0)/Number(ev.total||1)));
                          const bucket = Math.floor(ratio * 10);
                          if (bucket === netToken.payloadProgressBucket) return;
                          netToken.payloadProgressBucket = bucket;
                          emit('R20_ATTACHMENT_PAYLOAD_PROGRESS',{
                            id:netToken.payloadId,loaded:Number(ev.loaded||0),total:Number(ev.total||0),percent:Math.round(ratio*100),
                            host:netToken.host,path:netToken.path
                          });
                        } catch (_) {}
                      },false);
                    }
                    xhr.addEventListener('loadend',function(){
                      let status=-1;try{status=Number(xhr.status||-1);}catch(_){}
                      noteAttachmentNetDone(netToken,status);
                    },{once:true});
                  }
                } catch (err) {
                  emit('R11_MODEL_REWRITE_ERROR',{source:'xhr',error:String(err).slice(0,800)});
                }
                return current.call(this,nextBody);
              };
              wrapped.__aisR11RequestFix = true;
              proto.send = wrapped;
              fix.xhrRewriteInstalled = true;
              emit('R11_XHR_MODEL_REWRITE_INSTALLED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_XHR_MODEL_REWRITE_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }
'''
replace_between(REQ,
    '          function installXhrRewrite() {',
    '\n\n          function installFetchObserver() {',
    new_xhr)

# Surface R23 markers in the normal app log.
for path in (VIDEO, TRANS):
    replace_once(path,
        'name.startsWith("R22_") || name.startsWith("JS_R22_")',
        'name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_")')

# Update source lock and add assertions for the new behavior.
replace_once(TEST,
    'assertTrue(requestFix.contains("2026-09-05-web-session-r11.10-manual-config-trace"))',
    'assertTrue(requestFix.contains("2026-09-05-web-session-r11.11-http-rpc-diagnostic"))')
replace_once(TEST,
    '        assertTrue(submitFix.contains("nativeTargetIfAttachmentFileOnly"))\n',
    '        assertTrue(submitFix.contains("nativeTargetIfAttachmentFileOnly"))\n        assertTrue(submitFix.contains("2026-09-05-web-session-r11.9-cached-hit-test-submit"))\n        assertTrue(submitFix.contains("R23_PREPARED_SUBMIT_TARGET"))\n        assertTrue(submitFix.contains("R23_NATIVE_SUBMIT_HIT_TEST"))\n        assertTrue(submitFix.contains("document.elementFromPoint"))\n        assertTrue(requestFix.contains("R23_GENERATE_HTTP_DIAGNOSTIC"))\n        assertTrue(requestFix.contains("summarizeGenerateBody"))\n        assertTrue(requestFix.contains("requestHeaderNames"))\n')

print('R18.13 patch applied')
