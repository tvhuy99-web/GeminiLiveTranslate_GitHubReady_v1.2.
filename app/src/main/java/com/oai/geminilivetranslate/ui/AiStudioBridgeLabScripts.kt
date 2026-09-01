package com.oai.geminilivetranslate.ui

/** JavaScript payloads used only by the AI Studio Browser Bridge laboratory. */
object AiStudioBridgeLabScripts {
    val INSTALL: String = """
        (function() {
          try {
            if (window.__AIS_LAB__ && window.__AIS_LAB__.version === '2026-09-01-r1') {
              window.__AIS_LAB__.emit('INSTALL_REUSE', {href: location.href});
              return JSON.stringify({ok:true,reused:true,version:window.__AIS_LAB__.version});
            }

            var bridge = {};
            bridge.version = '2026-09-01-r1';
            bridge.observer = null;
            bridge.observerTimer = null;
            bridge.lastBodyText = '';
            bridge.lastMutationAt = 0;
            bridge.fetchHooked = false;
            bridge.xhrHooked = false;
            bridge.wsHooked = false;
            bridge.networkSequence = 0;

            bridge.safeString = function(value, max) {
              try {
                var text = typeof value === 'string' ? value : JSON.stringify(value);
                if (!text) text = String(value);
                return text.substring(0, max || 4000);
              } catch (e) {
                return String(value).substring(0, max || 4000);
              }
            };

            bridge.emit = function(kind, payload) {
              try {
                if (window.AIStudioLab && window.AIStudioLab.onJsEvent) {
                  window.AIStudioLab.onJsEvent(JSON.stringify({
                    t: Date.now(),
                    kind: kind,
                    payload: payload || {}
                  }));
                }
              } catch (e) {}
            };

            bridge.emitChunk = function(source, text) {
              try {
                if (window.AIStudioLab && window.AIStudioLab.onStreamChunk) {
                  window.AIStudioLab.onStreamChunk(source || 'unknown', bridge.safeString(text, 12000));
                }
              } catch (e) {}
            };

            bridge.describeElement = function(el) {
              if (!el) return null;
              var rect = null;
              try { rect = el.getBoundingClientRect(); } catch (e) {}
              return {
                tag: (el.tagName || '').toLowerCase(),
                id: bridge.safeString(el.id || '', 120),
                name: bridge.safeString(el.getAttribute && el.getAttribute('name') || '', 120),
                role: bridge.safeString(el.getAttribute && el.getAttribute('role') || '', 120),
                aria: bridge.safeString(el.getAttribute && el.getAttribute('aria-label') || '', 220),
                placeholder: bridge.safeString(el.getAttribute && el.getAttribute('placeholder') || '', 220),
                type: bridge.safeString(el.getAttribute && el.getAttribute('type') || '', 80),
                cls: bridge.safeString(el.className && String(el.className) || '', 260),
                text: bridge.safeString((el.innerText || el.textContent || '').trim(), 500),
                editable: !!(el.isContentEditable || (el.getAttribute && el.getAttribute('contenteditable') === 'true')),
                disabled: !!el.disabled,
                visible: !!(rect && rect.width > 0 && rect.height > 0),
                rect: rect ? {
                  x: Math.round(rect.x), y: Math.round(rect.y),
                  w: Math.round(rect.width), h: Math.round(rect.height)
                } : null
              };
            };

            bridge.allDeep = function(selector) {
              var out = [];
              var seen = new Set();
              function scanRoot(root, depth) {
                if (!root || depth > 12) return;
                var items = [];
                try { items = Array.from(root.querySelectorAll(selector)); } catch (e) {}
                items.forEach(function(el) {
                  if (!seen.has(el)) { seen.add(el); out.push(el); }
                });
                var all = [];
                try { all = Array.from(root.querySelectorAll('*')); } catch (e) {}
                all.forEach(function(el) {
                  try { if (el.shadowRoot) scanRoot(el.shadowRoot, depth + 1); } catch (e) {}
                });
                var frames = [];
                try { frames = Array.from(root.querySelectorAll('iframe,frame')); } catch (e) {}
                frames.forEach(function(frame) {
                  try {
                    if (frame.contentDocument) scanRoot(frame.contentDocument, depth + 1);
                  } catch (e) {}
                });
              }
              scanRoot(document, 0);
              return out;
            };

            bridge.visible = function(el) {
              try {
                if (!el) return false;
                var s = getComputedStyle(el);
                var r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0 && r.width > 1 && r.height > 1;
              } catch (e) { return false; }
            };

            bridge.promptCandidates = function() {
              var candidates = bridge.allDeep('textarea,input,[contenteditable="true"],[role="textbox"],[contenteditable="plaintext-only"]');
              return candidates.map(function(el, index) {
                var d = bridge.describeElement(el);
                var hay = ((d.placeholder || '') + ' ' + (d.aria || '') + ' ' + (d.role || '') + ' ' + (d.name || '') + ' ' + (d.id || '')).toLowerCase();
                var score = 0;
                if (d.tag === 'textarea') score += 130;
                if (d.editable) score += 110;
                if (d.role === 'textbox') score += 90;
                if (d.tag === 'input' && (d.type === 'text' || d.type === 'search' || !d.type)) score += 55;
                if (hay.indexOf('prompt') >= 0) score += 90;
                if (hay.indexOf('message') >= 0) score += 65;
                if (hay.indexOf('type') >= 0) score += 25;
                if (hay.indexOf('chat') >= 0) score += 30;
                if (bridge.visible(el)) score += 100;
                if (d.disabled) score -= 300;
                return {el:el,index:index,score:score,desc:d};
              }).sort(function(a,b) { return b.score - a.score; });
            };

            bridge.buttonCandidates = function() {
              var buttons = bridge.allDeep('button,[role="button"],input[type="submit"],input[type="button"],a');
              return buttons.map(function(el, index) {
                var d = bridge.describeElement(el);
                var hay = ((d.text || '') + ' ' + (d.aria || '') + ' ' + (d.id || '') + ' ' + (d.cls || '')).toLowerCase();
                var score = 0;
                if (d.tag === 'button') score += 45;
                if (d.role === 'button') score += 30;
                if (bridge.visible(el)) score += 80;
                if (hay.indexOf('run') >= 0) score += 170;
                if (hay.indexOf('send') >= 0) score += 170;
                if (hay.indexOf('submit') >= 0) score += 120;
                if (hay.indexOf('generate') >= 0) score += 100;
                if (hay.indexOf('ask') >= 0) score += 70;
                if (hay.indexOf('stop') >= 0) score -= 220;
                if (hay.indexOf('model') >= 0) score -= 80;
                if (hay.indexOf('settings') >= 0) score -= 80;
                if (d.disabled) score -= 300;
                return {el:el,index:index,score:score,desc:d};
              }).sort(function(a,b) { return b.score - a.score; });
            };

            bridge.domScan = function() {
              var frames = bridge.allDeep('iframe,frame').map(function(f) {
                var accessible = false;
                var frameUrl = '';
                try { accessible = !!f.contentDocument; frameUrl = f.contentWindow && f.contentWindow.location ? f.contentWindow.location.href : ''; } catch (e) {
                  try { frameUrl = f.src || ''; } catch (ignored) {}
                }
                return {src:bridge.safeString(f.src || frameUrl || '',600), accessible:accessible};
              }).slice(0,40);
              var prompts = bridge.promptCandidates().slice(0,15).map(function(x) { return {score:x.score,desc:x.desc}; });
              var buttons = bridge.buttonCandidates().slice(0,20).map(function(x) { return {score:x.score,desc:x.desc}; });
              var bodyText = '';
              try { bodyText = (document.body && document.body.innerText) || ''; } catch (e) {}
              var data = {
                href: location.href,
                origin: location.origin,
                title: document.title,
                readyState: document.readyState,
                visibility: document.visibilityState,
                active: bridge.describeElement(document.activeElement),
                bodyTextLength: bodyText.length,
                bodyTail: bridge.safeString(bodyText.slice(-3500),3500),
                promptCandidates: prompts,
                buttonCandidates: buttons,
                frames: frames,
                counts: {
                  all: bridge.allDeep('*').length,
                  textarea: bridge.allDeep('textarea').length,
                  contenteditable: bridge.allDeep('[contenteditable="true"],[contenteditable="plaintext-only"]').length,
                  roleTextbox: bridge.allDeep('[role="textbox"]').length,
                  button: bridge.allDeep('button,[role="button"]').length,
                  iframe: bridge.allDeep('iframe,frame').length
                }
              };
              bridge.emit('DOM_SCAN', data);
              return data;
            };

            bridge.environment = function() {
              var storage = {local:[],session:[]};
              try { storage.local = Object.keys(localStorage).slice(0,100); } catch (e) {}
              try { storage.session = Object.keys(sessionStorage).slice(0,100); } catch (e) {}
              var data = {
                href: location.href,
                title: document.title,
                userAgent: navigator.userAgent,
                language: navigator.language,
                languages: navigator.languages,
                platform: navigator.platform,
                webdriver: navigator.webdriver,
                online: navigator.onLine,
                cookieEnabled: navigator.cookieEnabled,
                visibility: document.visibilityState,
                readyState: document.readyState,
                localStorageKeys: storage.local,
                sessionStorageKeys: storage.session,
                deviceMemory: navigator.deviceMemory || null,
                hardwareConcurrency: navigator.hardwareConcurrency || null
              };
              bridge.emit('ENVIRONMENT', data);
              return data;
            };

            bridge.setNativeValue = function(el, value) {
              try {
                var proto = null;
                if (el.tagName === 'TEXTAREA') proto = window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype;
                else if (el.tagName === 'INPUT') proto = window.HTMLInputElement && window.HTMLInputElement.prototype;
                if (proto) {
                  var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                  if (descriptor && descriptor.set) descriptor.set.call(el, value); else el.value = value;
                } else {
                  el.textContent = value;
                }
                return true;
              } catch (e) { return false; }
            };

            bridge.dispatchInput = function(el, value) {
              try {
                el.focus();
                try { el.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,inputType:'insertText',data:value})); } catch (e) {}
                bridge.setNativeValue(el, value);
                try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value})); }
                catch (e) { el.dispatchEvent(new Event('input',{bubbles:true})); }
                el.dispatchEvent(new Event('change',{bubbles:true}));
                return true;
              } catch (e) {
                bridge.emit('FILL_ERROR',{error:String(e)});
                return false;
              }
            };

            bridge.fillSemantic = function(text) {
              var list = bridge.promptCandidates();
              var attempts = [];
              for (var i=0;i<Math.min(list.length,8);i++) {
                var item = list[i];
                var ok = bridge.dispatchInput(item.el, text);
                var observed = '';
                try { observed = item.el.value != null ? String(item.el.value) : String(item.el.innerText || item.el.textContent || ''); } catch (e) {}
                attempts.push({score:item.score,ok:ok,observedLength:observed.length,desc:item.desc});
                if (ok && observed.indexOf(text.substring(0,Math.min(20,text.length))) >= 0) {
                  bridge.emit('FILL_SUCCESS',{strategy:'semantic',selected:item.desc,attempts:attempts,textLength:text.length});
                  return {ok:true,strategy:'semantic',selected:item.desc,attempts:attempts};
                }
              }
              bridge.emit('FILL_FAIL',{strategy:'semantic',attempts:attempts,textLength:text.length});
              return {ok:false,strategy:'semantic',attempts:attempts};
            };

            bridge.fillExecCommand = function(text) {
              var list = bridge.promptCandidates();
              if (!list.length) {
                bridge.emit('FILL_FAIL',{strategy:'execCommand',reason:'no candidate'});
                return {ok:false,reason:'no candidate'};
              }
              var el = list[0].el;
              var ok = false;
              try {
                el.focus();
                if (el.isContentEditable) {
                  try { document.execCommand('selectAll',false,null); } catch (e) {}
                  try { ok = document.execCommand('insertText',false,text); } catch (e) {}
                  if (!ok) ok = bridge.dispatchInput(el,text);
                } else {
                  ok = bridge.dispatchInput(el,text);
                }
              } catch (e) {
                bridge.emit('FILL_ERROR',{strategy:'execCommand',error:String(e)});
              }
              bridge.emit(ok ? 'FILL_SUCCESS':'FILL_FAIL',{strategy:'execCommand',selected:bridge.describeElement(el),textLength:text.length});
              return {ok:ok,selected:bridge.describeElement(el)};
            };

            bridge.sendByButton = function() {
              var list = bridge.buttonCandidates();
              var attempts = [];
              for (var i=0;i<Math.min(list.length,12);i++) {
                var item = list[i];
                attempts.push({score:item.score,desc:item.desc});
                if (item.score < 90) continue;
                try {
                  item.el.scrollIntoView({block:'center',inline:'center'});
                  item.el.focus();
                  item.el.click();
                  bridge.emit('SEND_ATTEMPT',{strategy:'buttonClick',selected:item.desc,score:item.score,attempts:attempts});
                  return {ok:true,selected:item.desc,score:item.score,attempts:attempts};
                } catch (e) {
                  attempts[attempts.length-1].error = String(e);
                }
              }
              bridge.emit('SEND_FAIL',{strategy:'buttonClick',attempts:attempts});
              return {ok:false,attempts:attempts};
            };

            bridge.sendByForm = function() {
              var list = bridge.promptCandidates();
              if (!list.length) {
                bridge.emit('SEND_FAIL',{strategy:'form',reason:'no prompt candidate'});
                return {ok:false,reason:'no prompt candidate'};
              }
              var el = list[0].el;
              var parent = el;
              var form = null;
              while (parent) {
                if (parent.tagName === 'FORM') { form = parent; break; }
                parent = parent.parentElement || (parent.getRootNode && parent.getRootNode().host) || null;
              }
              if (!form) {
                bridge.emit('SEND_FAIL',{strategy:'form',reason:'no form',selected:bridge.describeElement(el)});
                return {ok:false,reason:'no form'};
              }
              try {
                if (form.requestSubmit) form.requestSubmit();
                else form.submit();
                bridge.emit('SEND_ATTEMPT',{strategy:'form',form:bridge.describeElement(form),selected:bridge.describeElement(el)});
                return {ok:true};
              } catch (e) {
                bridge.emit('SEND_FAIL',{strategy:'form',error:String(e)});
                return {ok:false,error:String(e)};
              }
            };

            bridge.sendByEnter = function() {
              var list = bridge.promptCandidates();
              if (!list.length) {
                bridge.emit('SEND_FAIL',{strategy:'enter',reason:'no prompt candidate'});
                return {ok:false,reason:'no prompt candidate'};
              }
              var el = list[0].el;
              try {
                el.focus();
                ['keydown','keypress','keyup'].forEach(function(type) {
                  el.dispatchEvent(new KeyboardEvent(type,{
                    bubbles:true,cancelable:true,key:'Enter',code:'Enter',keyCode:13,which:13
                  }));
                });
                bridge.emit('SEND_ATTEMPT',{strategy:'enter',selected:bridge.describeElement(el)});
                return {ok:true,warning:'Synthetic keyboard events may be ignored by the page'};
              } catch (e) {
                bridge.emit('SEND_FAIL',{strategy:'enter',error:String(e)});
                return {ok:false,error:String(e)};
              }
            };

            bridge.responseCandidates = function() {
              var selectors = [
                '[data-message-author-role="model"]','[data-role="model"]','[role="article"]',
                '[role="log"]','article','.markdown','.prose','model-response','ms-chat-turn',
                '[class*="response"]','[class*="message"]','[class*="output"]','[class*="markdown"]'
              ];
              var seen = new Set();
              var rows = [];
              selectors.forEach(function(selector) {
                bridge.allDeep(selector).forEach(function(el) {
                  if (seen.has(el) || !bridge.visible(el)) return;
                  seen.add(el);
                  var d = bridge.describeElement(el);
                  var text = (el.innerText || el.textContent || '').trim();
                  var score = Math.min(text.length,4000);
                  var hay = ((d.cls || '') + ' ' + (d.role || '') + ' ' + (d.tag || '')).toLowerCase();
                  if (hay.indexOf('response') >= 0) score += 1200;
                  if (hay.indexOf('markdown') >= 0) score += 900;
                  if (hay.indexOf('article') >= 0) score += 400;
                  rows.push({el:el,score:score,text:text,desc:d});
                });
              });
              return rows.sort(function(a,b) {
                var ay = a.desc && a.desc.rect ? a.desc.rect.y : 0;
                var by = b.desc && b.desc.rect ? b.desc.rect.y : 0;
                if (Math.abs(ay-by) > 200) return by-ay;
                return b.score-a.score;
              });
            };

            bridge.readResponse = function() {
              var list = bridge.responseCandidates().slice(0,12).map(function(x) {
                return {score:x.score,desc:x.desc,text:bridge.safeString(x.text,9000)};
              });
              var body = '';
              try { body = (document.body && document.body.innerText) || ''; } catch (e) {}
              var data = {candidates:list,bodyTail:bridge.safeString(body.slice(-9000),9000),bodyLength:body.length};
              bridge.emit('RESPONSE_SCAN',data);
              return data;
            };

            bridge.installObserver = function() {
              if (bridge.observer) {
                bridge.emit('OBSERVER_REUSE',{});
                return {ok:true,reused:true};
              }
              bridge.lastBodyText = '';
              try { bridge.lastBodyText = (document.body && document.body.innerText) || ''; } catch (e) {}
              bridge.observer = new MutationObserver(function(mutations) {
                bridge.lastMutationAt = Date.now();
                if (bridge.observerTimer) clearTimeout(bridge.observerTimer);
                bridge.observerTimer = setTimeout(function() {
                  var current = '';
                  try { current = (document.body && document.body.innerText) || ''; } catch (e) {}
                  if (current !== bridge.lastBodyText) {
                    var oldLength = bridge.lastBodyText.length;
                    var newLength = current.length;
                    var prefix = 0;
                    var max = Math.min(oldLength,newLength);
                    while (prefix < max && bridge.lastBodyText.charCodeAt(prefix) === current.charCodeAt(prefix)) prefix++;
                    var changed = current.substring(Math.max(0,prefix-250));
                    bridge.emitChunk('mutation-body', changed.slice(-10000));
                    bridge.emit('MUTATION_TEXT',{
                      mutations:mutations.length,
                      oldLength:oldLength,newLength:newLength,prefix:prefix,
                      changedTail:bridge.safeString(changed.slice(-2500),2500)
                    });
                    bridge.lastBodyText = current;
                  }
                  try {
                    var responses = bridge.responseCandidates();
                    if (responses.length) bridge.emitChunk('response-candidate', responses[0].text.slice(-12000));
                  } catch (e) {}
                }, 350);
              });
              bridge.observer.observe(document.documentElement || document,{subtree:true,childList:true,characterData:true,attributes:false});
              bridge.emit('OBSERVER_INSTALLED',{bodyLength:bridge.lastBodyText.length});
              return {ok:true};
            };

            bridge.stopObserver = function() {
              if (bridge.observer) bridge.observer.disconnect();
              bridge.observer = null;
              if (bridge.observerTimer) clearTimeout(bridge.observerTimer);
              bridge.observerTimer = null;
              bridge.emit('OBSERVER_STOPPED',{});
              return {ok:true};
            };

            bridge.resourceScan = function() {
              var entries = [];
              try {
                entries = performance.getEntriesByType('resource').slice(-250).map(function(x) {
                  return {name:bridge.safeString(x.name,1200),type:x.initiatorType,duration:Math.round(x.duration),transfer:x.transferSize || 0};
                });
              } catch (e) {}
              bridge.emit('RESOURCE_SCAN',{count:entries.length,entries:entries});
              return {count:entries.length,entries:entries};
            };

            bridge.installNetworkHooks = function() {
              var result = {fetch:false,xhr:false,websocket:false};

              if (!bridge.fetchHooked && window.fetch) {
                bridge.fetchHooked = true;
                bridge.nativeFetch = window.fetch;
                window.fetch = function(input, init) {
                  var seq = ++bridge.networkSequence;
                  var url = '';
                  var method = 'GET';
                  try { url = typeof input === 'string' ? input : input.url; } catch (e) {}
                  try { method = init && init.method ? init.method : (input && input.method ? input.method : 'GET'); } catch (e) {}
                  bridge.emit('FETCH_START',{seq:seq,method:method,url:bridge.safeString(url,1600)});
                  var start = performance.now();
                  return bridge.nativeFetch.apply(this,arguments).then(function(resp) {
                    bridge.emit('FETCH_RESPONSE',{
                      seq:seq,status:resp.status,ok:resp.ok,url:bridge.safeString(resp.url || url,1600),
                      type:resp.type,contentType:resp.headers && resp.headers.get ? (resp.headers.get('content-type') || '') : '',
                      duration:Math.round(performance.now()-start)
                    });
                    try {
                      var ct = resp.headers && resp.headers.get ? (resp.headers.get('content-type') || '') : '';
                      if (/json|text|event-stream|javascript/i.test(ct)) {
                        var clone = resp.clone();
                        if (clone.body && clone.body.getReader) {
                          var reader = clone.body.getReader();
                          reader.read().then(function(part) {
                            if (part && part.value) {
                              try {
                                var preview = new TextDecoder().decode(part.value);
                                bridge.emit('FETCH_PREVIEW',{seq:seq,preview:bridge.safeString(preview,5000),done:!!part.done});
                              } catch (e) {}
                            }
                            try { reader.cancel(); } catch (e) {}
                          }).catch(function() {});
                        }
                      }
                    } catch (e) {}
                    return resp;
                  }).catch(function(err) {
                    bridge.emit('FETCH_ERROR',{seq:seq,url:bridge.safeString(url,1600),error:String(err),duration:Math.round(performance.now()-start)});
                    throw err;
                  });
                };
                result.fetch = true;
              } else result.fetch = bridge.fetchHooked;

              if (!bridge.xhrHooked && window.XMLHttpRequest) {
                bridge.xhrHooked = true;
                bridge.nativeXhrOpen = XMLHttpRequest.prototype.open;
                bridge.nativeXhrSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method,url) {
                  this.__aisLab = {seq:++bridge.networkSequence,method:method,url:String(url),start:0};
                  return bridge.nativeXhrOpen.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                  var meta = this.__aisLab || {seq:++bridge.networkSequence,method:'?',url:'?'};
                  meta.start = performance.now();
                  bridge.emit('XHR_START',{seq:meta.seq,method:meta.method,url:bridge.safeString(meta.url,1600),bodyType:body == null ? 'null' : typeof body});
                  this.addEventListener('loadend',function() {
                    var preview = '';
                    try {
                      var ct = this.getResponseHeader('content-type') || '';
                      if (/json|text|event-stream/i.test(ct) && typeof this.responseText === 'string') preview = this.responseText.substring(0,5000);
                    } catch (e) {}
                    bridge.emit('XHR_END',{seq:meta.seq,status:this.status,url:bridge.safeString(this.responseURL || meta.url,1600),duration:Math.round(performance.now()-meta.start),preview:preview});
                  });
                  return bridge.nativeXhrSend.apply(this,arguments);
                };
                result.xhr = true;
              } else result.xhr = bridge.xhrHooked;

              if (!bridge.wsHooked && window.WebSocket) {
                bridge.wsHooked = true;
                bridge.NativeWebSocket = window.WebSocket;
                var LabWebSocket = function(url, protocols) {
                  var seq = ++bridge.networkSequence;
                  var ws;
                  if (arguments.length > 1) ws = new bridge.NativeWebSocket(url,protocols);
                  else ws = new bridge.NativeWebSocket(url);
                  bridge.emit('WS_CREATE',{seq:seq,url:bridge.safeString(String(url),1800),protocols:bridge.safeString(protocols || '',500)});
                  var nativeSend = ws.send;
                  ws.send = function(data) {
                    var info = {seq:seq,type:typeof data,size:0,preview:''};
                    try {
                      if (typeof data === 'string') { info.size=data.length; info.preview=bridge.safeString(data,5000); }
                      else if (data && data.byteLength != null) info.size=data.byteLength;
                      else if (data && data.size != null) info.size=data.size;
                    } catch (e) {}
                    bridge.emit('WS_SEND',info);
                    return nativeSend.apply(ws,arguments);
                  };
                  ws.addEventListener('open',function() { bridge.emit('WS_OPEN',{seq:seq,url:bridge.safeString(String(url),1800)}); });
                  ws.addEventListener('close',function(ev) { bridge.emit('WS_CLOSE',{seq:seq,code:ev.code,reason:bridge.safeString(ev.reason || '',1000),clean:ev.wasClean}); });
                  ws.addEventListener('error',function() { bridge.emit('WS_ERROR',{seq:seq}); });
                  ws.addEventListener('message',function(ev) {
                    var info = {seq:seq,type:typeof ev.data,size:0,preview:''};
                    try {
                      if (typeof ev.data === 'string') { info.size=ev.data.length; info.preview=bridge.safeString(ev.data,5000); }
                      else if (ev.data && ev.data.size != null) info.size=ev.data.size;
                      else if (ev.data && ev.data.byteLength != null) info.size=ev.data.byteLength;
                    } catch (e) {}
                    bridge.emit('WS_MESSAGE',info);
                  });
                  return ws;
                };
                LabWebSocket.prototype = bridge.NativeWebSocket.prototype;
                try {
                  LabWebSocket.CONNECTING = bridge.NativeWebSocket.CONNECTING;
                  LabWebSocket.OPEN = bridge.NativeWebSocket.OPEN;
                  LabWebSocket.CLOSING = bridge.NativeWebSocket.CLOSING;
                  LabWebSocket.CLOSED = bridge.NativeWebSocket.CLOSED;
                } catch (e) {}
                window.WebSocket = LabWebSocket;
                result.websocket = true;
              } else result.websocket = bridge.wsHooked;

              bridge.emit('NETWORK_HOOKS',{result:result});
              return result;
            };

            bridge.highlight = function() {
              var prompts = bridge.promptCandidates().slice(0,5);
              var buttons = bridge.buttonCandidates().slice(0,8);
              prompts.forEach(function(x,i) {
                try { x.el.style.outline = '4px solid #ff00ff'; x.el.setAttribute('data-ais-lab-score',String(x.score)); } catch (e) {}
              });
              buttons.forEach(function(x,i) {
                try { x.el.style.outline = '3px solid #00d5ff'; x.el.setAttribute('data-ais-lab-score',String(x.score)); } catch (e) {}
              });
              var data = {prompt:prompts.map(function(x){return {score:x.score,desc:x.desc};}),buttons:buttons.map(function(x){return {score:x.score,desc:x.desc};})};
              bridge.emit('HIGHLIGHT',data);
              return data;
            };

            window.__AIS_LAB__ = bridge;
            bridge.emit('INSTALL_OK',{version:bridge.version,href:location.href,title:document.title});
            bridge.environment();
            return JSON.stringify({ok:true,version:bridge.version,href:location.href,title:document.title});
          } catch (e) {
            try {
              if (window.AIStudioLab && window.AIStudioLab.onJsEvent) window.AIStudioLab.onJsEvent(JSON.stringify({kind:'INSTALL_FATAL',payload:{error:String(e),stack:String(e && e.stack || '')}}));
            } catch (ignored) {}
            return JSON.stringify({ok:false,error:String(e),stack:String(e && e.stack || '')});
          }
        })();
    """.trimIndent()

    fun call(expression: String): String = """
        (function(){
          try {
            if (!window.__AIS_LAB__) return JSON.stringify({ok:false,error:'lab-not-installed'});
            var value = ($expression);
            return JSON.stringify({ok:true,value:value});
          } catch(e) {
            try { window.__AIS_LAB__ && window.__AIS_LAB__.emit('CALL_ERROR',{expression:${quoteForJs(expression.take(300))},error:String(e),stack:String(e && e.stack || '')}); } catch(ignored) {}
            return JSON.stringify({ok:false,error:String(e),stack:String(e && e.stack || '')});
          }
        })();
    """.trimIndent()

    private fun quoteForJs(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
