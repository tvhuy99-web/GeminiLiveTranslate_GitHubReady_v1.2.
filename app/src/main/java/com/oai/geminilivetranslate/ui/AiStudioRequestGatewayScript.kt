package com.oai.geminilivetranslate.ui

/**
 * Browser-context request gateway for authenticated Google AI Studio sessions.
 *
 * Raw headers, cookies and captured request bodies stay inside the WebView. Native code receives
 * only explicit results/metadata. The gateway is intentionally conservative: an unknown wire
 * shape is rejected instead of being rewritten heuristically.
 */
object AiStudioRequestGatewayScript {
    const val VERSION = "2026-09-16-request-gateway-v1"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_REQUEST_GATEWAY__ && window.__AIS_REQUEST_GATEWAY__.version === '$VERSION') return;

          const NativeXHR = window.XMLHttpRequest;
          const previousFetch = window.fetch ? window.fetch.bind(window) : null;
          const previousOpen = NativeXHR && NativeXHR.prototype ? NativeXHR.prototype.open : null;
          const previousSend = NativeXHR && NativeXHR.prototype ? NativeXHR.prototype.send : null;
          const previousSetHeader = NativeXHR && NativeXHR.prototype ? NativeXHR.prototype.setRequestHeader : null;
          const MODEL_INDEX = 0;
          const CONTENTS_INDEX = 1;
          const GENERATION_CONFIG_INDEX = 3;
          const SNAPSHOT_INDEX = 4;
          const templates = Object.create(null);
          const active = Object.create(null);
          let lastTemplateKey = '';
          let nextRequestId = 1;
          let proofService = null;
          let proofFunctionKey = '';
          let proofHookInstalled = false;

          function emit(kind, payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function isGenerateUrl(raw) {
            const s = String(raw || '');
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(s) || /\/GenerateContent(?:$|[/?])/i.test(s);
          }

          function normalizeModel(raw) {
            return String(raw || '').trim().replace(/^models\//i, '').slice(0,160);
          }

          function typeOf(v) {
            if (v === null || typeof v === 'undefined') return 'null';
            if (Array.isArray(v)) return 'array';
            return typeof v;
          }

          function inspectWire(body) {
            try {
              const root = JSON.parse(String(body || ''));
              if (!Array.isArray(root)) return {ok:false,error:'ROOT_NOT_ARRAY',fingerprint:typeOf(root)};
              const fp = [];
              for (let i=0;i<Math.min(root.length,14);i++) fp.push(i+'='+typeOf(root[i]));
              const fingerprint = 'len='+root.length+':'+fp.join(',');
              if (root.length <= SNAPSHOT_INDEX) return {ok:false,error:'WIRE_TOO_SHORT',fingerprint:fingerprint};
              if (typeof root[MODEL_INDEX] !== 'string' || root[MODEL_INDEX].indexOf('models/') !== 0) {
                return {ok:false,error:'MODEL_SLOT_UNEXPECTED',fingerprint:fingerprint};
              }
              if (!Array.isArray(root[CONTENTS_INDEX])) return {ok:false,error:'CONTENTS_SLOT_NOT_ARRAY',fingerprint:fingerprint};
              const gc = root[GENERATION_CONFIG_INDEX];
              if (gc !== null && typeof gc !== 'undefined' && !Array.isArray(gc)) {
                return {ok:false,error:'GENERATION_CONFIG_SLOT_UNEXPECTED',fingerprint:fingerprint};
              }
              const snap = root[SNAPSHOT_INDEX];
              if (snap !== null && typeof snap !== 'undefined' && typeof snap !== 'string') {
                return {ok:false,error:'SNAPSHOT_SLOT_UNEXPECTED',fingerprint:fingerprint};
              }
              return {
                ok:true,
                root:root,
                model:normalizeModel(root[MODEL_INDEX]),
                fingerprint:fingerprint,
                topLevelSize:root.length,
                snapshotPresent:typeof snap === 'string' && snap.length > 0
              };
            } catch (e) {
              return {ok:false,error:'INVALID_JSON',detail:String(e||'').slice(0,300),fingerprint:''};
            }
          }

          function replaceLastUserText(contents, prompt) {
            for (let i=contents.length-1;i>=0;i--) {
              const content = contents[i];
              if (!Array.isArray(content) || content[1] !== 'user' || !Array.isArray(content[0])) continue;
              const parts = content[0];
              for (let j=parts.length-1;j>=0;j--) {
                const part = parts[j];
                if (!Array.isArray(part) || part.length < 2) continue;
                if ((part[0] === null || typeof part[0] === 'undefined') && typeof part[1] === 'string') {
                  part[1] = String(prompt);
                  return true;
                }
              }
              parts.push([null,String(prompt)]);
              return true;
            }
            return false;
          }

          function rewriteWire(body, args, snapshot) {
            const inspected = inspectWire(body);
            if (!inspected.ok) throw new Error('WIRE_SHAPE:'+inspected.error+':'+String(inspected.fingerprint||''));
            const root = inspected.root;
            if (args && args.model) root[MODEL_INDEX] = 'models/' + normalizeModel(args.model);
            if (args && Object.prototype.hasOwnProperty.call(args,'prompt')) {
              if (!replaceLastUserText(root[CONTENTS_INDEX], String(args.prompt || ''))) {
                root[CONTENTS_INDEX].push([[[null,String(args.prompt || '')]],'user']);
              }
            }
            if (typeof snapshot === 'string' && snapshot) root[SNAPSHOT_INDEX] = snapshot;
            return JSON.stringify(root);
          }

          function headersToObject(raw) {
            const out = Object.create(null);
            try {
              const h = new Headers(raw || {});
              h.forEach(function(v,k){ out[String(k)] = String(v); });
            } catch (_) {
              try {
                if (raw && typeof raw === 'object') Object.keys(raw).forEach(function(k){ out[String(k)] = String(raw[k]); });
              } catch (_) {}
            }
            return out;
          }

          function templateKey(model) {
            const m = normalizeModel(model);
            return m || '__last__';
          }

          function capture(source, url, method, headers, body) {
            if (!isGenerateUrl(url) || typeof body !== 'string' || body.length < 8) return;
            const inspected = inspectWire(body);
            if (!inspected.ok) {
              emit('REQUEST_GATEWAY_CAPTURE_REJECTED',{source:String(source||''),error:inspected.error,fingerprint:inspected.fingerprint||'',bodyChars:body.length});
              return;
            }
            const key = templateKey(inspected.model);
            templates[key] = {
              source:String(source||''),
              url:String(url||''),
              method:String(method||'POST'),
              headers:headersToObject(headers),
              body:String(body),
              model:inspected.model,
              fingerprint:inspected.fingerprint,
              capturedAt:Date.now()
            };
            lastTemplateKey = key;
            emit('REQUEST_GATEWAY_TEMPLATE_CAPTURED',{
              source:String(source||''),model:inspected.model,fingerprint:inspected.fingerprint,
              bodyChars:body.length,headerNames:Object.keys(templates[key].headers).slice(0,40)
            });
          }

          function installProofHook() {
            if (proofHookInstalled && proofService && proofFunctionKey) return true;
            try {
              const dms = window.default_MakerSuite;
              if (!dms) return false;
              let key = proofFunctionKey;
              if (!key || typeof dms[key] !== 'function') {
                key = '';
                const keys = Object.keys(dms);
                for (let i=0;i<keys.length;i++) {
                  const k = keys[i];
                  try {
                    if (typeof dms[k] !== 'function') continue;
                    const src = Function.prototype.toString.call(dms[k]);
                    if (src.indexOf('.snapshot({') >= 0 && src.indexOf('content') >= 0 && src.indexOf('yield') >= 0) {
                      key = k;
                      break;
                    }
                  } catch (_) {}
                }
              }
              if (!key || typeof dms[key] !== 'function') return false;
              proofFunctionKey = key;
              const current = dms[key];
              if (!current.__aisRequestGatewayProofHook) {
                const wrapped = function() {
                  try { proofService = arguments[0] || proofService; } catch (_) {}
                  const result = current.apply(this, arguments);
                  try {
                    if (result && typeof result.then === 'function') {
                      return result.then(function(v){ return v; });
                    }
                  } catch (_) {}
                  return result;
                };
                wrapped.__aisRequestGatewayProofHook = true;
                wrapped.__aisRequestGatewayOriginal = current;
                dms[key] = wrapped;
              }
              proofHookInstalled = true;
              return true;
            } catch (_) { return false; }
          }

          async function sha256Hex(text) {
            if (!window.crypto || !window.crypto.subtle || typeof TextEncoder === 'undefined') throw new Error('CRYPTO_UNAVAILABLE');
            const data = new TextEncoder().encode(String(text || ''));
            const digest = await window.crypto.subtle.digest('SHA-256', data);
            const bytes = new Uint8Array(digest);
            let out = '';
            for (let i=0;i<bytes.length;i++) out += bytes[i].toString(16).padStart(2,'0');
            return out;
          }

          async function generateSnapshot(prompt) {
            installProofHook();
            if (!proofService || !proofFunctionKey) throw new Error('PROOF_NOT_READY');
            const dms = window.default_MakerSuite;
            const fn = dms && dms[proofFunctionKey];
            if (typeof fn !== 'function') throw new Error('PROOF_FUNCTION_UNAVAILABLE');
            const hash = await sha256Hex(String(prompt || ''));
            const result = fn(proofService, hash);
            const snapshot = await Promise.resolve(result);
            if (!snapshot || typeof snapshot !== 'string') throw new Error('PROOF_EMPTY');
            return snapshot;
          }

          function chooseTemplate(model) {
            const requested = templateKey(model);
            if (templates[requested]) return templates[requested];
            if (lastTemplateKey && templates[lastTemplateKey]) return templates[lastTemplateKey];
            const keys = Object.keys(templates);
            return keys.length ? templates[keys[keys.length-1]] : null;
          }

          function normalizeResponse(raw, status, partial, phase) {
            try {
              const core = window.__AIS_RESPONSE_CORE__;
              if (core && typeof core.normalize === 'function') {
                return core.normalize({
                  ok:Number(status)>=200&&Number(status)<300,
                  status:Number(status),
                  responseText:String(raw||''),
                  responseType:'text',
                  contentType:'',
                  phase:String(phase||''),
                  partial:!!partial,
                  at:Date.now()
                });
              }
            } catch (_) {}
            return {
              ok:Number(status)>=200&&Number(status)<300,
              status:Number(status),
              responseText:String(raw||''),
              modelText:'',
              responseChars:String(raw||'').length,
              partial:!!partial,
              phase:String(phase||''),
              complete:!partial
            };
          }

          async function runReplay(id, args) {
            const item = active[id];
            if (!item) return;
            try {
              const template = chooseTemplate(args && args.model);
              if (!template) throw new Error('NO_CAPTURED_TEMPLATE');
              const prompt = String(args && args.prompt || '');
              let snapshot = null;
              if (!args || args.refreshSnapshot !== false) snapshot = await generateSnapshot(prompt);
              const body = rewriteWire(template.body, args || {}, snapshot);
              const xhr = new NativeXHR();
              item.xhr = xhr;
              xhr.__aisRequestGatewayReplay = true;
              xhr.open('POST', template.url);
              const headers = template.headers || {};
              Object.keys(headers).forEach(function(k){
                const low = String(k).toLowerCase();
                if (low === 'host' || low === 'content-length' || low === 'cookie' || low === 'origin' || low === 'referer') return;
                try { xhr.setRequestHeader(k, headers[k]); } catch (_) {}
              });
              xhr.withCredentials = true;
              xhr.timeout = Math.max(1000, Math.min(20*60*1000, Number(args && args.timeoutMs || 120000)));
              let lastChars = 0;
              xhr.onprogress = function() {
                let raw = '';
                try { raw = String(xhr.responseText || ''); } catch (_) {}
                if (raw.length <= lastChars) return;
                lastChars = raw.length;
                const normalized = normalizeResponse(raw, xhr.status, true, 'gateway-progress');
                item.progress = {
                  status:Number(xhr.status||0),responseChars:raw.length,
                  modelText:String(normalized.modelText||''),at:Date.now()
                };
                emit('REQUEST_GATEWAY_REPLAY_PROGRESS',{
                  id:id,status:Number(xhr.status||0),responseChars:raw.length,modelChars:String(normalized.modelText||'').length
                });
              };
              const finish = function(kind) {
                if (!active[id] || item.done) return;
                item.done = true;
                let raw = '';
                try { raw = String(xhr.responseText || ''); } catch (_) {}
                const status = Number(xhr.status || 0);
                const normalized = normalizeResponse(raw, status, false, 'gateway-'+kind);
                item.result = {
                  id:id,
                  ok:status>=200&&status<300,
                  status:status,
                  modelText:String(normalized.modelText||''),
                  responseChars:raw.length,
                  phase:'gateway-'+kind,
                  error:status>=200&&status<300?'':('HTTP_'+status),
                  fingerprint:template.fingerprint,
                  model:normalizeModel(args && args.model || template.model)
                };
                emit('REQUEST_GATEWAY_REPLAY_DONE',{
                  id:id,ok:item.result.ok,status:status,responseChars:raw.length,
                  modelChars:item.result.modelText.length,model:item.result.model
                });
              };
              xhr.onload = function(){ finish('load'); };
              xhr.onerror = function(){
                if (!item.done) {
                  item.done = true;
                  item.result = {id:id,ok:false,status:0,modelText:'',responseChars:0,phase:'gateway-error',error:'NETWORK_ERROR'};
                  emit('REQUEST_GATEWAY_REPLAY_ERROR',{id:id,error:'NETWORK_ERROR'});
                }
              };
              xhr.ontimeout = function(){
                if (!item.done) {
                  item.done = true;
                  item.result = {id:id,ok:false,status:0,modelText:'',responseChars:0,phase:'gateway-timeout',error:'TIMEOUT'};
                  emit('REQUEST_GATEWAY_REPLAY_ERROR',{id:id,error:'TIMEOUT'});
                }
              };
              xhr.onabort = function(){
                if (!item.done) {
                  item.done = true;
                  item.result = {id:id,ok:false,status:0,modelText:'',responseChars:0,phase:'gateway-abort',error:'ABORTED'};
                }
              };
              xhr.send(body);
            } catch (e) {
              item.done = true;
              item.result = {id:id,ok:false,status:0,modelText:'',responseChars:0,phase:'gateway-prepare',error:String(e&&e.message||e||'UNKNOWN').slice(0,500)};
              emit('REQUEST_GATEWAY_REPLAY_ERROR',{id:id,error:item.result.error});
            }
          }

          function startReplay(args) {
            const id = 'rg'+(nextRequestId++);
            active[id] = {id:id,startedAt:Date.now(),done:false,result:null,progress:null,xhr:null};
            Promise.resolve().then(function(){ return runReplay(id,args||{}); });
            return {ok:true,id:id};
          }

          function takeResult(id) {
            const item = active[String(id||'')];
            if (!item) return {ok:false,error:'UNKNOWN_REQUEST'};
            if (!item.done) return {ok:true,pending:true,progress:item.progress||null};
            const result = item.result || {id:String(id||''),ok:false,status:0,error:'EMPTY_RESULT'};
            delete active[String(id||'')];
            return {ok:true,pending:false,result:result};
          }

          function abort(id) {
            const item = active[String(id||'')];
            if (!item) return false;
            try { if (item.xhr && item.xhr.readyState !== 4) item.xhr.abort(); } catch (_) {}
            return true;
          }

          function status() {
            const tpl = chooseTemplate('');
            return {
              ok:true,
              version:'$VERSION',
              templateReady:!!tpl,
              templateModel:tpl?tpl.model:'',
              templateFingerprint:tpl?tpl.fingerprint:'',
              templateBodyChars:tpl?tpl.body.length:0,
              proofHookInstalled:!!proofHookInstalled,
              proofReady:!!(proofService&&proofFunctionKey),
              proofFunctionDetected:!!proofFunctionKey,
              activeRequests:Object.keys(active).length
            };
          }

          if (previousFetch) {
            window.fetch = function(input, init) {
              let url='', method='GET', headers={}, body=null;
              try {
                url = typeof input === 'string' ? input : (input && input.url) || '';
                method = String(init && init.method || input && input.method || 'GET');
                headers = init && init.headers || input && input.headers || {};
                body = init && Object.prototype.hasOwnProperty.call(init,'body') ? init.body : null;
                capture('fetch',url,method,headers,body);
              } catch (_) {}
              return previousFetch(input,init);
            };
          }

          if (NativeXHR && NativeXHR.prototype && previousOpen && previousSend && previousSetHeader) {
            NativeXHR.prototype.open = function(method,url) {
              this.__aisRequestGatewayMeta = {method:String(method||'GET'),url:String(url||''),headers:Object.create(null)};
              return previousOpen.apply(this,arguments);
            };
            NativeXHR.prototype.setRequestHeader = function(name,value) {
              try {
                const meta = this.__aisRequestGatewayMeta;
                if (meta) meta.headers[String(name)] = String(value);
              } catch (_) {}
              return previousSetHeader.apply(this,arguments);
            };
            NativeXHR.prototype.send = function(body) {
              try {
                const meta = this.__aisRequestGatewayMeta || {};
                if (!this.__aisRequestGatewayReplay) capture('xhr',meta.url||'',meta.method||'POST',meta.headers||{},body);
              } catch (_) {}
              return previousSend.apply(this,arguments);
            };
          }

          const gateway = {
            version:'$VERSION',
            status:status,
            inspectWire:function(body){
              const r=inspectWire(body);if(r&&r.root)delete r.root;return r;
            },
            startReplay:startReplay,
            takeResult:takeResult,
            abort:abort,
            clearTemplates:function(){Object.keys(templates).forEach(function(k){delete templates[k];});lastTemplateKey='';return true;},
            installProofHook:installProofHook
          };
          window.__AIS_REQUEST_GATEWAY__ = gateway;

          let hookTries = 0;
          const hookTimer = setInterval(function(){
            hookTries += 1;
            if (installProofHook() && proofService) {
              clearInterval(hookTimer);
              emit('REQUEST_GATEWAY_PROOF_READY',{version:'$VERSION'});
            } else if (hookTries >= 1200) {
              clearInterval(hookTimer);
              emit('REQUEST_GATEWAY_PROOF_WAIT_EXPIRED',{version:'$VERSION',functionDetected:!!proofFunctionKey});
            }
          },250);
          installProofHook();
          emit('REQUEST_GATEWAY_INSTALLED',{version:'$VERSION'});
        })();
    """.trimIndent()
}
