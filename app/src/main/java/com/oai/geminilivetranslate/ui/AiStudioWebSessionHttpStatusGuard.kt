package com.oai.geminilivetranslate.ui


object AiStudioWebSessionHttpStatusGuard {
    const val VERSION = "2026-09-02-web-session-http-status-guard-r1"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_HTTP_STATUS_GUARD__ && window.__AIS_HTTP_STATUS_GUARD__.version === '$VERSION') return;
          const X=window.XMLHttpRequest;
          if(!X||!X.prototype)return;

          const wrappedOpen=X.prototype.open;
          const wrappedSend=X.prototype.send;
          const nativeAdd=window.EventTarget&&window.EventTarget.prototype?window.EventTarget.prototype.addEventListener:null;

          function emit(kind,payload){
            try{
              if(window.AIStudioWebSessionLab&&window.AIStudioWebSessionLab.onJsEvent){
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            }catch(_){}
          }

          function isGenerateUrl(url){
            const s=String(url||'');
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(s)||/\/GenerateContent(?:$|[/?])/i.test(s);
          }

          function attach(target,name,fn){
            try{if(nativeAdd){nativeAdd.call(target,name,fn,false);return true;}}catch(_){}
            try{target.addEventListener(name,fn,false);return true;}catch(_){return false;}
          }

          function textNow(xhr){
            let type='';
            try{type=String(xhr.responseType||'');}catch(_){}
            try{
              if(type===''||type==='text')return {text:typeof xhr.responseText==='string'?xhr.responseText:'',type:type};
              if(type==='json'){let t='';try{t=JSON.stringify(xhr.response);}catch(_){}return {text:t,type:type};}
              if(type==='arraybuffer'){let t='';try{if(xhr.response)t=new TextDecoder('utf-8').decode(new Uint8Array(xhr.response));}catch(_){}return {text:t,type:type};}
              return {text:typeof xhr.response==='string'?xhr.response:'',type:type};
            }catch(_){return {text:'',type:type};}
          }

          function contentType(xhr){try{return String(xhr.getResponseHeader('content-type')||'');}catch(_){return '';}}

          function publish(xhr,meta,phase){
            if(meta.recorded)return;
            let status=-1,rs=-1;
            try{status=Number(xhr.status);}catch(_){}
            try{rs=Number(xhr.readyState);}catch(_){}
            if(status<400||status>599)return;
            const snap=textNow(xhr);
            const text=String(snap.text||'');
            if(text.length>=String(meta.bestText||'').length){meta.bestText=text;meta.bestType=snap.type;meta.bestContentType=contentType(xhr);}
            meta.bestStatus=status;


            if(rs===3||rs===4||phase==='error'||phase==='load'||phase==='loadend'){
              meta.recorded=true;
              const net=window.__AIS_WEB_SESSION__;
              if(net){
                const marker=String(net.expectedMarker||'');
                const raw=String(meta.bestText||'');
                net.lastResult={
                  source:'xhr-http-error',
                  status:meta.bestStatus,
                  ok:false,
                  error:'HTTP_'+String(meta.bestStatus),
                  responseChars:raw.length,
                  responseType:String(meta.bestType||''),
                  contentType:String(meta.bestContentType||''),
                  phase:'http-error-'+String(phase||'rs3'),
                  partial:false,
                  marker:marker,
                  markerFound:!!marker&&raw.indexOf(marker)>=0,
                  responseText:raw.slice(0,16000),
                  at:Date.now()
                };
              }
              emit('GENERATE_HTTP_ERROR',{status:meta.bestStatus,responseChars:String(meta.bestText||'').length,phase:String(phase||''),readyState:rs});
            }
          }

          X.prototype.open=function(method,url){
            this.__aisHttpGuard={url:String(url||''),recorded:false,bestStatus:-1,bestText:'',bestType:'',bestContentType:''};
            return wrappedOpen.apply(this,arguments);
          };

          X.prototype.send=function(){
            const xhr=this;
            const meta=xhr.__aisHttpGuard||{url:'',recorded:false,bestStatus:-1,bestText:'',bestType:'',bestContentType:''};
            if(isGenerateUrl(meta.url)){
              attach(xhr,'readystatechange',function(){publish(xhr,meta,'readystatechange');});
              attach(xhr,'progress',function(){publish(xhr,meta,'progress');});
              attach(xhr,'load',function(){publish(xhr,meta,'load');});
              attach(xhr,'loadend',function(){publish(xhr,meta,'loadend');});
              attach(xhr,'error',function(){publish(xhr,meta,'error');});
            }
            return wrappedSend.apply(xhr,arguments);
          };

          window.__AIS_HTTP_STATUS_GUARD__={version:'$VERSION'};
          emit('HTTP_STATUS_GUARD_INSTALLED',{version:'$VERSION'});
        })();
    """.trimIndent()
}
