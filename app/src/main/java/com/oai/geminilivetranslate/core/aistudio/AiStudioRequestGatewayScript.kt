package com.oai.geminilivetranslate.core.aistudio

/**
 * Passive document-start hook for AI Studio GenerateContent requests.
 *
 * The script captures an authenticated request template and can replay a caller-supplied body
 * inside the same WebView context. It does not modify production traffic by itself.
 */
object AiStudioRequestGatewayScript {
    const val VERSION = "2026-09-16-request-gateway-v1"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_REQUEST_GATEWAY__ && window.__AIS_REQUEST_GATEWAY__.version === '$VERSION') return;

          const NativeXHR = window.XMLHttpRequest;
          const nativeFetch = window.fetch ? window.fetch.bind(window) : null;
          const templates = Object.create(null);
          const streams = Object.create(null);
          let lastTemplateKey = '';
          let nextReplayId = 1;

          function isGenerateUrl(raw) {
            const s = String(raw || '');
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(s) || /\/GenerateContent(?:[/?]|$)/i.test(s);
          }

          function modelFromBody(body) {
            try {
              if (typeof body !== 'string') return '';
              const root = JSON.parse(body);
              if (!Array.isArray(root) || typeof root[0] !== 'string') return '';
              return String(root[0] || '').replace(/^models\//, '').slice(0,160);
            } catch (_) { return ''; }
          }

          function shapeOf(body) {
            try {
              const root = JSON.parse(String(body || ''));
              if (!Array.isArray(root)) return {valid:false, reason:'root-not-array'};
              const types = root.slice(0,20).map(function(v) {
                if (v === null) return 'n';
                if (Array.isArray(v)) return 'a';
                if (typeof v === 'string') return 's';
                if (typeof v === 'number') return 'd';
                if (typeof v === 'boolean') return 'b';
                if (typeof v === 'object') return 'o';
                return '?';
              }).join(',');
              const model = typeof root[0] === 'string' ? root[0] : '';
              const contentsOk = root.length > 1 && Array.isArray(root[1]);
              return {
                valid: !!model && contentsOk,
                reason: !model ? 'model-missing' : (!contentsOk ? 'contents-not-array' : ''),
                rootLength: root.length,
                model: model,
                fingerprint: types + (root.length > 20 ? ',+' : '')
              };
            } catch (e) {
              return {valid:false, reason:'json-parse-failed:' + String(e).slice(0,180)};
            }
          }

          function copyHeaders(input) {
            const out = {};
            try {
              const h = new Headers(input || {});
              h.forEach(function(value, name) { out[String(name)] = String(value); });
            } catch (_) {}
            return out;
          }

          function safeHeaderMap(raw) {
            const out = {};
            if (!raw || typeof raw !== 'object') return out;
            Object.keys(raw).forEach(function(name) {
              const lower = String(name || '').toLowerCase();
              if (!lower) return;
              if (lower === 'host' || lower === 'content-length' || lower === 'cookie' || lower === 'origin' || lower === 'referer') return;
              if (lower.indexOf('sec-') === 0) return;
              out[String(name)] = String(raw[name]);
            });
            return out;
          }

          function storeTemplate(url, method, headers, body, source) {
            if (!isGenerateUrl(url) || typeof body !== 'string' || !body) return false;
            const shape = shapeOf(body);
            if (!shape.valid) return false;
            const model = modelFromBody(body) || '__unknown__';
            const key = model;
            templates[key] = {
              url: String(url || ''),
              method: String(method || 'POST').toUpperCase(),
              headers: safeHeaderMap(headers),
              body: body,
              source: String(source || ''),
              capturedAt: Date.now(),
              shape: shape
            };
            lastTemplateKey = key;
            return true;
          }

          function templateFor(model) {
            const key = String(model || '').replace(/^models\//, '');
            if (key && templates[key]) return templates[key];
            if (lastTemplateKey && templates[lastTemplateKey]) return templates[lastTemplateKey];
            const keys = Object.keys(templates);
            return keys.length ? templates[keys[keys.length - 1]] : null;
          }

          function summarizeTemplate(t) {
            if (!t) return null;
            return {
              model: t.shape && t.shape.model || '',
              source: t.source || '',
              capturedAt: Number(t.capturedAt || 0),
              rootLength: t.shape && Number(t.shape.rootLength || 0),
              fingerprint: t.shape && String(t.shape.fingerprint || ''),
              bodyChars: String(t.body || '').length,
              headerNames: Object.keys(t.headers || {}).sort(),
              host: (function(){ try { return new URL(t.url).host; } catch (_) { return ''; } })(),
              path: (function(){ try { return new URL(t.url).pathname; } catch (_) { return ''; } })()
            };
          }

          if (NativeXHR && NativeXHR.prototype) {
            const originalOpen = NativeXHR.prototype.open;
            const originalSetHeader = NativeXHR.prototype.setRequestHeader;
            const originalSend = NativeXHR.prototype.send;

            NativeXHR.prototype.open = function(method, url) {
              this.__aisGatewayMeta = {method:String(method || 'GET'), url:String(url || ''), headers:{}};
              return originalOpen.apply(this, arguments);
            };

            NativeXHR.prototype.setRequestHeader = function(name, value) {
              try {
                const meta = this.__aisGatewayMeta || (this.__aisGatewayMeta = {method:'POST',url:'',headers:{}});
                meta.headers[String(name)] = String(value);
              } catch (_) {}
              return originalSetHeader.apply(this, arguments);
            };

            NativeXHR.prototype.send = function(body) {
              try {
                const meta = this.__aisGatewayMeta || {method:'POST',url:'',headers:{}};
                storeTemplate(meta.url, meta.method, meta.headers, body, 'xhr');
              } catch (_) {}
              return originalSend.apply(this, arguments);
            };
          }

          if (nativeFetch) {
            window.fetch = function(input, init) {
              let url = '', method = 'GET', headers = {}, body = null;
              try {
                url = typeof input === 'string' ? input : (input && input.url) || '';
                method = String((init && init.method) || (input && input.method) || 'GET');
                headers = copyHeaders((init && init.headers) || (input && input.headers) || {});
                body = init && Object.prototype.hasOwnProperty.call(init, 'body') ? init.body : null;
                if (typeof body === 'string') storeTemplate(url, method, headers, body, 'fetch');
                else if (isGenerateUrl(url) && input && typeof input.clone === 'function') {
                  try {
                    input.clone().text().then(function(text) {
                      storeTemplate(url, method, headers, String(text || ''), 'fetch-request');
                    }).catch(function(){});
                  } catch (_) {}
                }
              } catch (_) {}
              return nativeFetch(input, init);
            };
          }

          function pushEvent(state, event) {
            state.events.push(event);
            if (state.events.length > 256) state.events.splice(0, state.events.length - 256);
          }

          function startReplay(body, timeoutMs, model) {
            const t = templateFor(model);
            if (!t) return {ok:false,error:'NO_CAPTURED_TEMPLATE'};
            const shape = shapeOf(body);
            if (!shape.valid) return {ok:false,error:'INVALID_REPLAY_BODY',detail:shape.reason || ''};

            const rid = 'r' + (nextReplayId++);
            const state = {events:[], xhr:null, recvPos:0, statusSent:false, startedAt:Date.now()};
            streams[rid] = state;

            try {
              const xhr = new XMLHttpRequest();
              state.xhr = xhr;
              xhr.open(t.method || 'POST', t.url);
              const headers = safeHeaderMap(t.headers || {});
              Object.keys(headers).forEach(function(name) {
                try { xhr.setRequestHeader(name, headers[name]); } catch (_) {}
              });
              xhr.withCredentials = true;
              xhr.timeout = Math.max(1000, Number(timeoutMs || 120000));

              function status() {
                if (state.statusSent || xhr.readyState < 2) return;
                state.statusSent = true;
                pushEvent(state, {type:'status', status:Number(xhr.status || 0)});
              }
              function chunk() {
                if (xhr.readyState < 3) return;
                let text = '';
                try { text = String(xhr.responseText || ''); } catch (_) { return; }
                if (text.length <= state.recvPos) return;
                const delta = text.substring(state.recvPos);
                state.recvPos = text.length;
                pushEvent(state, {type:'chunk', text:delta});
              }
              xhr.onreadystatechange = function(){ status(); chunk(); };
              xhr.onprogress = function(){ status(); chunk(); };
              xhr.onload = function(){ status(); chunk(); pushEvent(state,{type:'done'}); };
              xhr.onerror = function(){ pushEvent(state,{type:'error',message:'network error'}); };
              xhr.ontimeout = function(){ pushEvent(state,{type:'error',message:'timeout'}); };
              xhr.onabort = function(){ pushEvent(state,{type:'aborted'}); };
              xhr.send(body);
              return {ok:true,rid:rid,template:summarizeTemplate(t)};
            } catch (e) {
              delete streams[rid];
              return {ok:false,error:'REPLAY_START_FAILED',detail:String(e).slice(0,500)};
            }
          }

          function nextEvent(rid) {
            const state = streams[String(rid || '')];
            if (!state) return {type:'missing'};
            if (state.events.length) return state.events.shift();
            return {type:'idle'};
          }

          function abortReplay(rid) {
            const key = String(rid || '');
            const state = streams[key];
            if (!state) return false;
            try { if (state.xhr && state.xhr.readyState !== 4) state.xhr.abort(); } catch (_) {}
            delete streams[key];
            return true;
          }

          function proofProbe() {
            try {
              const dms = window.default_MakerSuite;
              if (!dms || typeof dms !== 'object') return {makerSuite:false,candidateCount:0};
              let count = 0;
              Object.keys(dms).forEach(function(key) {
                try {
                  const fn = dms[key];
                  if (typeof fn !== 'function') return;
                  const src = Function.prototype.toString.call(fn);
                  if (/snapshot/i.test(src) && /content/i.test(src)) count += 1;
                } catch (_) {}
              });
              return {makerSuite:true,candidateCount:count};
            } catch (e) { return {makerSuite:false,candidateCount:0,error:String(e).slice(0,300)}; }
          }

          window.__AIS_REQUEST_GATEWAY__ = {
            version: '$VERSION',
            describe: function() {
              const keys = Object.keys(templates);
              return {
                ok:true,
                version:'$VERSION',
                templateCount:keys.length,
                lastTemplate:lastTemplateKey ? summarizeTemplate(templates[lastTemplateKey]) : null,
                models:keys,
                proof:proofProbe()
              };
            },
            getCapturedBody: function(model) {
              const t = templateFor(model);
              return t ? String(t.body || '') : '';
            },
            startReplay:startReplay,
            nextEvent:nextEvent,
            abortReplay:abortReplay,
            proofProbe:proofProbe
          };
        })();
    """.trimIndent()
}
