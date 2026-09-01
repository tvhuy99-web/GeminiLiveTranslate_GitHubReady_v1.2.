package com.oai.geminilivetranslate.ui

/** JavaScript cho phòng thử nghiệm riêng về lỗi TalkBack / accessibility khi gửi prompt trong AI Studio. */
object AiStudioAccessibilitySendLabScripts {
    val INSTALL: String = """
        (function() {
          try {
            if (window.__AIS_A11Y_LAB__ && window.__AIS_A11Y_LAB__.version === '2026-09-01-a11y-r2') {
              window.__AIS_A11Y_LAB__.emit('INSTALL_REUSE',{href:location.href});
              return JSON.stringify({ok:true,reused:true,version:window.__AIS_A11Y_LAB__.version});
            }

            var lab = {};
            lab.version = '2026-09-01-a11y-r2';
            lab.currentMethod = 'UNARMED';
            lab.networkSequence = 0;
            lab.eventsInstalled = false;
            lab.networkInstalled = false;
            lab.lastPrompt = null;
            lab.lastSend = null;

            lab.safe = function(value, max) {
              try {
                var text = typeof value === 'string' ? value : JSON.stringify(value);
                if (text == null) text = String(value);
                return text.substring(0,max || 6000);
              } catch (e) { return String(value).substring(0,max || 6000); }
            };

            lab.emit = function(kind,payload) {
              try {
                if (window.AIStudioA11yLab && window.AIStudioA11yLab.onJsEvent) {
                  window.AIStudioA11yLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload || {}}));
                }
              } catch (e) {}
            };

            lab.describe = function(el) {
              if (!el) return null;
              var r = null;
              try { r = el.getBoundingClientRect(); } catch(e) {}
              return {
                tag:(el.tagName || '').toLowerCase(),
                id:lab.safe(el.id || '',120),
                role:lab.safe(el.getAttribute && el.getAttribute('role') || '',100),
                aria:lab.safe(el.getAttribute && el.getAttribute('aria-label') || '',260),
                title:lab.safe(el.getAttribute && el.getAttribute('title') || '',220),
                placeholder:lab.safe(el.getAttribute && el.getAttribute('placeholder') || '',260),
                cls:lab.safe(el.className && String(el.className) || '',320),
                text:lab.safe((el.innerText || el.textContent || '').trim(),600),
                disabled:!!el.disabled,
                editable:!!(el.isContentEditable || (el.getAttribute && /true|plaintext-only/.test(el.getAttribute('contenteditable') || ''))),
                rect:r ? {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)} : null
              };
            };

            lab.visible = function(el) {
              try {
                if (!el) return false;
                var s = getComputedStyle(el), r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0 && r.width > 2 && r.height > 2;
              } catch(e) { return false; }
            };

            lab.allDeep = function(selector) {
              var out = [], seen = new Set();
              function scan(root,depth) {
                if (!root || depth > 10) return;
                try {
                  Array.from(root.querySelectorAll(selector)).forEach(function(el){ if(!seen.has(el)){seen.add(el);out.push(el);} });
                  Array.from(root.querySelectorAll('*')).forEach(function(el){ try{ if(el.shadowRoot) scan(el.shadowRoot,depth+1); }catch(e){} });
                  Array.from(root.querySelectorAll('iframe,frame')).forEach(function(fr){ try{ if(fr.contentDocument) scan(fr.contentDocument,depth+1); }catch(e){} });
                } catch(e) {}
              }
              scan(document,0);
              return out;
            };

            lab.promptCandidates = function() {
              return lab.allDeep('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"],[role="textbox"]').map(function(el){
                var d = lab.describe(el), hay = ((d.placeholder || '')+' '+(d.aria || '')+' '+(d.id || '')+' '+(d.role || '')).toLowerCase();
                var score = 0;
                if (d.tag === 'textarea') score += 160;
                if (d.editable) score += 130;
                if (d.role === 'textbox') score += 100;
                if (hay.indexOf('prompt') >= 0) score += 100;
                if (hay.indexOf('message') >= 0 || hay.indexOf('chat') >= 0) score += 70;
                if (lab.visible(el)) score += 120;
                if (d.disabled) score -= 500;
                return {el:el,desc:d,score:score};
              }).sort(function(a,b){return b.score-a.score;});
            };

            lab.findPrompt = function() {
              var list = lab.promptCandidates();
              return list.length ? list[0].el : null;
            };

            lab.buttonCandidates = function() {
              var prompt = lab.findPrompt();
              var pr = null;
              try { pr = prompt && prompt.getBoundingClientRect(); } catch(e) {}
              return lab.allDeep('button,[role="button"],input[type="submit"],input[type="button"]').map(function(el){
                var d = lab.describe(el), hay = ((d.text || '')+' '+(d.aria || '')+' '+(d.title || '')+' '+(d.id || '')+' '+(d.cls || '')).toLowerCase();
                var score = 0, distance = 99999;
                if (lab.visible(el)) score += 120;
                if (d.tag === 'button') score += 40;
                if (hay.indexOf('arrow_upward') >= 0 || hay.indexOf('send') >= 0 || hay.indexOf('submit') >= 0 || hay.indexOf('run') >= 0) score += 420;
                if (hay.indexOf('stop') >= 0) score -= 600;
                if (/generate music|generate video|generate image|create image|google drive|google sheets|gmail|model|settings|feature|chip|card/.test(hay)) score -= 700;
                if (d.disabled) score -= 800;
                if (pr && d.rect) {
                  var cx = d.rect.x + d.rect.w/2, cy = d.rect.y + d.rect.h/2;
                  var px = pr.x + pr.width/2, py = pr.y + pr.height/2;
                  distance = Math.sqrt((cx-px)*(cx-px)+(cy-py)*(cy-py));
                  if (distance < 90) score += 340;
                  else if (distance < 180) score += 220;
                  else if (distance < 300) score += 100;
                  else if (distance > 700) score -= 180;
                  if (d.rect.y >= pr.y-120 && d.rect.y <= pr.y+pr.height+160) score += 120;
                }
                return {el:el,desc:d,score:score,distance:Math.round(distance)};
              }).sort(function(a,b){return b.score-a.score;});
            };

            lab.findSend = function() {
              var list = lab.buttonCandidates();
              for (var i=0;i<list.length;i++) {
                if (list[i].score >= 200 && !list[i].desc.disabled) return list[i];
              }
              return null;
            };

            lab.scan = function() {
              var prompts = lab.promptCandidates().slice(0,8).map(function(x){return {score:x.score,desc:x.desc};});
              var buttons = lab.buttonCandidates().slice(0,15).map(function(x){return {score:x.score,distance:x.distance,desc:x.desc};});
              var selected = lab.findSend();
              var data = {
                href:location.href,title:document.title,ready:document.readyState,visibility:document.visibilityState,
                active:lab.describe(document.activeElement),
                prompt:prompts,buttons:buttons,
                selectedSend:selected ? {score:selected.score,distance:selected.distance,desc:selected.desc} : null,
                innerWidth:window.innerWidth,innerHeight:window.innerHeight,
                devicePixelRatio:window.devicePixelRatio,
                currentMethod:lab.currentMethod
              };
              lab.emit('SCAN',data);
              return data;
            };

            lab.setNativeValue = function(el,value) {
              try {
                var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : (el.tagName === 'INPUT' ? HTMLInputElement.prototype : null);
                if (proto) {
                  var d = Object.getOwnPropertyDescriptor(proto,'value');
                  if (d && d.set) d.set.call(el,value); else el.value=value;
                } else el.textContent=value;
                return true;
              } catch(e) { return false; }
            };

            lab.fill = function(text) {
              var el = lab.findPrompt();
              if (!el) { lab.emit('FILL_FAIL',{reason:'no-prompt'}); return {ok:false,reason:'no-prompt'}; }
              try {
                el.focus();
                try { el.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,inputType:'insertText',data:text})); } catch(e) {}
                lab.setNativeValue(el,text);
                try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text})); } catch(e) { el.dispatchEvent(new Event('input',{bubbles:true})); }
                el.dispatchEvent(new Event('change',{bubbles:true}));
                var observed = el.value != null ? String(el.value) : String(el.innerText || el.textContent || '');
                lab.lastPrompt = lab.describe(el);
                lab.emit('FILL_OK',{observedLength:observed.length,selected:lab.lastPrompt});
                return {ok:true,observedLength:observed.length,selected:lab.lastPrompt};
              } catch(e) { lab.emit('FILL_FAIL',{error:String(e)}); return {ok:false,error:String(e)}; }
            };

            lab.arm = function(method) {
              lab.currentMethod = String(method || 'UNKNOWN');
              lab.emit('METHOD_ARMED',{method:lab.currentMethod,active:lab.describe(document.activeElement)});
              return {ok:true,method:lab.currentMethod};
            };

            lab.sendClick = function() {
              var item = lab.findSend();
              if (!item) { lab.emit('SEND_FAIL',{method:lab.currentMethod,reason:'no-send'}); return {ok:false,reason:'no-send'}; }
              try {
                item.el.scrollIntoView({block:'center',inline:'center'});
                lab.lastSend = item.desc;
                item.el.click();
                lab.emit('SEND_DISPATCH',{method:lab.currentMethod,event:'HTMLElement.click',selected:item.desc,score:item.score,distance:item.distance});
                return {ok:true,selected:item.desc,score:item.score,distance:item.distance};
              } catch(e) { lab.emit('SEND_FAIL',{method:lab.currentMethod,error:String(e)}); return {ok:false,error:String(e)}; }
            };

            lab.sendMouse = function() {
              var item = lab.findSend();
              if (!item) { lab.emit('SEND_FAIL',{method:lab.currentMethod,reason:'no-send'}); return {ok:false,reason:'no-send'}; }
              try {
                item.el.scrollIntoView({block:'center',inline:'center'});
                var r=item.el.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
                ['mousedown','mouseup','click'].forEach(function(type){ item.el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,button:0,buttons:type==='mousedown'?1:0})); });
                lab.emit('SEND_DISPATCH',{method:lab.currentMethod,event:'MouseEvent-sequence',selected:item.desc});
                return {ok:true,selected:item.desc};
              } catch(e) { lab.emit('SEND_FAIL',{method:lab.currentMethod,error:String(e)}); return {ok:false,error:String(e)}; }
            };

            lab.sendPointer = function() {
              var item = lab.findSend();
              if (!item) { lab.emit('SEND_FAIL',{method:lab.currentMethod,reason:'no-send'}); return {ok:false,reason:'no-send'}; }
              try {
                item.el.scrollIntoView({block:'center',inline:'center'});
                var r=item.el.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
                if (window.PointerEvent) {
                  item.el.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerId:41,pointerType:'touch',isPrimary:true,clientX:x,clientY:y,button:0,buttons:1}));
                  item.el.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,pointerId:41,pointerType:'touch',isPrimary:true,clientX:x,clientY:y,button:0,buttons:0}));
                }
                item.el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:1}));
                item.el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:0}));
                item.el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:0}));
                lab.emit('SEND_DISPATCH',{method:lab.currentMethod,event:'Pointer+Mouse-sequence',selected:item.desc});
                return {ok:true,selected:item.desc};
              } catch(e) { lab.emit('SEND_FAIL',{method:lab.currentMethod,error:String(e)}); return {ok:false,error:String(e)}; }
            };

            lab.sendRect = function() {
              var item = lab.findSend();
              if (!item || !item.desc || !item.desc.rect) return {ok:false,reason:'no-send-rect'};
              var r=item.desc.rect;
              var data={ok:true,method:lab.currentMethod,rect:r,centerX:r.x+r.w/2,centerY:r.y+r.h/2,innerWidth:window.innerWidth,innerHeight:window.innerHeight,devicePixelRatio:window.devicePixelRatio,selected:item.desc,score:item.score,distance:item.distance};
              lab.emit('SEND_RECT',data);
              return data;
            };

            lab.eventRelevant = function(target) {
              if (!target) return false;
              var prompt=lab.findPrompt(), send=lab.findSend();
              if (target===prompt || (send && target===send.el)) return true;
              try { if (prompt && (prompt.contains(target) || target.contains(prompt))) return true; } catch(e) {}
              try { if (send && (send.el.contains(target) || target.contains(send.el))) return true; } catch(e) {}
              return false;
            };

            lab.installEventHooks = function() {
              if (lab.eventsInstalled) return {ok:true,reused:true};
              lab.eventsInstalled=true;
              ['focusin','focusout','click','pointerdown','pointerup','mousedown','mouseup','keydown','keyup'].forEach(function(type){
                document.addEventListener(type,function(ev){
                  if (!lab.eventRelevant(ev.target)) return;
                  lab.emit('DOM_EVENT',{type:type,isTrusted:!!ev.isTrusted,detail:ev.detail || 0,key:ev.key || '',code:ev.code || '',pointerType:ev.pointerType || '',buttons:ev.buttons || 0,target:lab.describe(ev.target),active:lab.describe(document.activeElement),method:lab.currentMethod});
                },true);
              });
              ['focus','blur','pageshow','pagehide'].forEach(function(type){ window.addEventListener(type,function(ev){lab.emit('LIFECYCLE_EVENT',{type:type,isTrusted:!!ev.isTrusted,visibility:document.visibilityState,hasFocus:document.hasFocus(),method:lab.currentMethod});},true); });
              document.addEventListener('visibilitychange',function(ev){lab.emit('LIFECYCLE_EVENT',{type:'visibilitychange',isTrusted:!!ev.isTrusted,visibility:document.visibilityState,hasFocus:document.hasFocus(),method:lab.currentMethod});},true);
              lab.emit('EVENT_HOOKS_INSTALLED',{});
              return {ok:true};
            };

            lab.rpcName = function(url) {
              var s=String(url || '');
              var idx=s.lastIndexOf('/');
              return idx>=0 ? s.substring(idx+1).split('?')[0] : s;
            };

            lab.bodyPreview = function(body) {
              try {
                if (body == null) return '';
                if (typeof body === 'string') return lab.safe(body,10000);
                if (body instanceof URLSearchParams) return lab.safe(body.toString(),10000);
                if (body instanceof FormData) return '[FormData]';
                if (body instanceof ArrayBuffer) return '[ArrayBuffer '+body.byteLength+']';
                if (ArrayBuffer.isView(body)) return '[TypedArray '+body.byteLength+']';
                return lab.safe(body,10000);
              } catch(e) { return '[unreadable-body '+String(e)+']'; }
            };

            lab.installNetworkHooks = function() {
              if (lab.networkInstalled) return {ok:true,reused:true};
              lab.networkInstalled=true;

              if (window.XMLHttpRequest) {
                var nativeOpen=XMLHttpRequest.prototype.open, nativeSend=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(method,url){ this.__aisA11y={seq:++lab.networkSequence,method:String(method),url:String(url),op:lab.rpcName(url)}; return nativeOpen.apply(this,arguments); };
                XMLHttpRequest.prototype.send=function(body){
                  var meta=this.__aisA11y || {seq:++lab.networkSequence,method:'?',url:'?',op:'?'};
                  meta.start=performance.now(); meta.sendMethod=lab.currentMethod;
                  var important=/GenerateContent|CountTokens|GenerateAccessToken|BenefitTier/i.test(meta.op) || /MakerSuiteService/i.test(meta.url);
                  if (important) lab.emit('RPC_START',{seq:meta.seq,op:meta.op,url:lab.safe(meta.url,1800),sendMethod:meta.sendMethod,body:lab.bodyPreview(body)});
                  this.addEventListener('loadend',function(){
                    var preview='';
                    try { if (typeof this.responseText==='string') preview=this.responseText.substring(0,this.status>=400?14000:7000); } catch(e) {}
                    var data={seq:meta.seq,op:meta.op,status:this.status,url:lab.safe(this.responseURL || meta.url,1800),duration:Math.round(performance.now()-meta.start),sendMethod:meta.sendMethod,preview:preview};
                    if (important) lab.emit('RPC_END',data); else if (this.status>=400) lab.emit('XHR_ERROR',data);
                  });
                  return nativeSend.apply(this,arguments);
                };
              }

              if (window.fetch) {
                var nativeFetch=window.fetch;
                window.fetch=function(input,init){
                  var seq=++lab.networkSequence, url='', method='GET', body='';
                  try{url=typeof input==='string'?input:input.url;}catch(e){}
                  try{method=init&&init.method?init.method:(input&&input.method?input.method:'GET');}catch(e){}
                  try{body=init&&init.body?lab.bodyPreview(init.body):'';}catch(e){}
                  var op=lab.rpcName(url), sendMethod=lab.currentMethod, start=performance.now();
                  var important=/GenerateContent|CountTokens|GenerateAccessToken|BenefitTier/i.test(op) || /MakerSuiteService/i.test(url);
                  if (important) lab.emit('RPC_START',{seq:seq,op:op,url:lab.safe(url,1800),sendMethod:sendMethod,body:body,transport:'fetch'});
                  return nativeFetch.apply(this,arguments).then(function(resp){
                    try {
                      var clone=resp.clone();
                      clone.text().then(function(text){ if (important || resp.status>=400) lab.emit('RPC_END',{seq:seq,op:op,status:resp.status,url:lab.safe(resp.url || url,1800),duration:Math.round(performance.now()-start),sendMethod:sendMethod,preview:lab.safe(text,resp.status>=400?14000:7000),transport:'fetch'}); }).catch(function(){});
                    } catch(e) {}
                    return resp;
                  }).catch(function(err){ lab.emit('RPC_ERROR',{seq:seq,op:op,url:lab.safe(url,1800),duration:Math.round(performance.now()-start),sendMethod:sendMethod,error:String(err),transport:'fetch'}); throw err; });
                };
              }
              lab.emit('NETWORK_HOOKS_INSTALLED',{});
              return {ok:true};
            };

            window.__AIS_A11Y_LAB__=lab;
            lab.installEventHooks();
            lab.installNetworkHooks();
            lab.emit('INSTALL_OK',{version:lab.version,href:location.href,title:document.title,userAgent:navigator.userAgent,visibility:document.visibilityState});
            return JSON.stringify({ok:true,version:lab.version,href:location.href,title:document.title});
          } catch(e) {
            try { window.AIStudioA11yLab && window.AIStudioA11yLab.onJsEvent(JSON.stringify({kind:'INSTALL_FATAL',payload:{error:String(e),stack:String(e&&e.stack||'')}})); } catch(ignored) {}
            return JSON.stringify({ok:false,error:String(e),stack:String(e&&e.stack||'')});
          }
        })();
    """.trimIndent()

    fun call(expression: String): String = """
        (function(){
          try {
            if (!window.__AIS_A11Y_LAB__) return JSON.stringify({ok:false,error:'a11y-lab-not-installed'});
            return JSON.stringify({ok:true,value:($expression)});
          } catch(e) {
            try { window.__AIS_A11Y_LAB__ && window.__AIS_A11Y_LAB__.emit('CALL_ERROR',{error:String(e),stack:String(e&&e.stack||'')}); } catch(ignored) {}
            return JSON.stringify({ok:false,error:String(e),stack:String(e&&e.stack||'')});
          }
        })();
    """.trimIndent()
}
