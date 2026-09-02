package com.oai.geminilivetranslate.ui

/**
 * R11.1 hardening layer discovered from the first real-device R11 diagnostic.
 *
 * Device evidence showed two independent failures:
 * 1) ListModels worked, but DOM model-picker discovery failed.
 * 2) input[type=file].click() returned normally, but WebView never entered onShowFileChooser().
 *
 * This layer fixes both without extracting credentials:
 * - model selection becomes request-layer selection. The chosen model id is substituted into the
 *   authenticated AI Studio GenerateContent request immediately before the existing R11/R10
 *   network wrappers send it, then the existing observer verifies the model actually sent;
 * - file selection uses a one-shot trusted-activation arm. Android injects one internal touch pulse
 *   into the hidden WebView, the capture listener receives an isTrusted click and invokes the real
 *   file input inside that same activation context, allowing WebChromeClient.onShowFileChooser().
 */
object AiStudioWebSessionR11RequestFix {
    const val VERSION = "2026-09-02-web-session-r11.1-request-model-trusted-file"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_R11_REQUEST_FIX__ && window.__AIS_R11_REQUEST_FIX__.version === '$VERSION') return;

          const fix = {
            version: '$VERSION',
            requestedModel: '',
            selectedModel: '',
            rewriteCount: 0,
            lastOriginalModel: '',
            lastAppliedModel: '',
            modelPatchInstalled: false,
            xhrRewriteInstalled: false,
            fileArmToken: 0,
            fileArmed: false,
            trustedActivationCount: 0,
            lastTrustedActivationAt: 0
          };

          function emit(kind, payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function normalizeModel(raw) {
            return String(raw || '').trim().replace(/^models\//i, '').replace(/[\"'`,;:)\]}]+$/g, '').slice(0,120);
          }

          function firstModel(text) {
            const m = String(text || '').match(/(?:models\/)?gemini-[a-z0-9][a-z0-9._-]{2,110}/i);
            return m ? normalizeModel(m[0]) : '';
          }

          function isGenerateUrl(raw) {
            const s = String(raw || '');
            return /MakerSuiteService\/(?:GenerateContent|BidiGenerateContent)/i.test(s) || /\/GenerateContent(?:[/?]|$)/i.test(s);
          }

          function rewriteBody(url, body, source) {
            if (!fix.selectedModel || typeof body !== 'string' || !isGenerateUrl(url)) return body;
            const original = firstModel(body);
            if (!original) {
              emit('R11_MODEL_REWRITE_SKIPPED',{reason:'MODEL_NOT_FOUND_IN_BODY',target:fix.selectedModel,source:source,bodyChars:body.length});
              return body;
            }
            let rewritten = body;
            if (original !== fix.selectedModel) rewritten = body.split(original).join(fix.selectedModel);
            fix.lastOriginalModel = original;
            fix.lastAppliedModel = fix.selectedModel;
            fix.rewriteCount += 1;
            emit('R11_GENERATE_MODEL_REWRITE',{
              source:source,
              originalModel:original,
              targetModel:fix.selectedModel,
              changed:rewritten!==body,
              rewriteCount:fix.rewriteCount,
              bodyChars:rewritten.length
            });
            return rewritten;
          }

          function installXhrRewrite() {
            try {
              if (!window.__AIS_R11_SUPPORT__ || !window.XMLHttpRequest || !XMLHttpRequest.prototype) return false;
              const proto = XMLHttpRequest.prototype;
              const current = proto.send;
              if (!current || current.__aisR11RequestFix === true) {
                fix.xhrRewriteInstalled = !!current;
                return !!current;
              }
              const wrapped = function(body) {
                let nextBody = body;
                try {
                  const meta = this.__aisR11 || {};
                  nextBody = rewriteBody(meta.url || '', body, 'xhr');
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

          function installApiPatch() {
            try {
              const api = window.__AIS_R11_SUPPORT__;
              if (!api || api.__r11RequestFixPatched) return !!api;
              const originalDiscover = typeof api.discoverModels === 'function' ? api.discoverModels.bind(api) : null;
              const originalSelectionState = typeof api.selectionState === 'function' ? api.selectionState.bind(api) : null;

              api.openModelPicker = function() {
                const result = {ok:true,path:'request-layer',pickerRequired:false};
                emit('R11_MODEL_PICKER_BYPASSED',result);
                return result;
              };

              api.selectModel = function(modelId) {
                const target = normalizeModel(modelId);
                if (!/^gemini-[a-z0-9][a-z0-9._-]{2,110}$/i.test(target)) {
                  return {ok:false,error:'INVALID_MODEL_ID',modelId:target};
                }
                let known = false;
                try {
                  const catalog = originalDiscover ? originalDiscover() : null;
                  const list = catalog && Array.isArray(catalog.models) ? catalog.models : [];
                  known = list.some(function(x){return x && normalizeModel(x.id)===target;});
                } catch (_) {}
                if (!known) {
                  emit('R11_MODEL_SELECT_RESULT',{ok:false,error:'MODEL_NOT_IN_CATALOG',modelId:target,path:'request-layer'});
                  return {ok:false,error:'MODEL_NOT_IN_CATALOG',modelId:target};
                }
                fix.requestedModel = target;
                fix.selectedModel = target;
                fix.lastAppliedModel = '';
                emit('R11_MODEL_SELECT_RESULT',{ok:true,modelId:target,path:'request-layer',pickerRequired:false});
                return {ok:true,pending:false,modelId:target,path:'request-layer'};
              };

              api.selectionState = function() {
                let base = {};
                try { base = originalSelectionState ? (originalSelectionState() || {}) : {}; } catch (_) {}
                return {
                  ok:true,
                  requestedModel:fix.requestedModel || String(base.requestedModel||''),
                  selectedModel:fix.selectedModel || String(base.selectedModel||''),
                  observedGenerateModel:String(base.observedGenerateModel||''),
                  rewriteCount:fix.rewriteCount,
                  lastOriginalModel:fix.lastOriginalModel,
                  lastAppliedModel:fix.lastAppliedModel,
                  path:'request-layer'
                };
              };

              api.armTrustedFileChooser = function() {
                let input = null;
                let count = 0;
                try {
                  const inputs = document.querySelectorAll('input[type="file"]');
                  count = inputs.length;
                  for (let i=inputs.length-1;i>=0;i--) {
                    if (inputs[i] && inputs[i].isConnected) { input = inputs[i]; break; }
                  }
                } catch (_) {}
                if (!input) {
                  emit('R11_FILE_ACTIVATION_ARM',{ok:false,error:'FILE_INPUT_NOT_FOUND',inputCount:count});
                  return {ok:false,error:'FILE_INPUT_NOT_FOUND',inputCount:count};
                }

                fix.fileArmToken += 1;
                const token = fix.fileArmToken;
                fix.fileArmed = true;
                let finished = false;

                function cleanup() {
                  if (finished) return;
                  finished = true;
                  fix.fileArmed = false;
                  try { document.removeEventListener('click',onTrustedClick,true); } catch (_) {}
                }

                function onTrustedClick(ev) {
                  if (token !== fix.fileArmToken || !fix.fileArmed) { cleanup(); return; }
                  if (!ev || ev.isTrusted !== true) {
                    emit('R11_FILE_ACTIVATION_IGNORED',{reason:'UNTRUSTED_CLICK',isTrusted:!!(ev&&ev.isTrusted)});
                    return;
                  }
                  cleanup();
                  try { ev.preventDefault(); } catch (_) {}
                  try { ev.stopImmediatePropagation(); } catch (_) {}
                  fix.trustedActivationCount += 1;
                  fix.lastTrustedActivationAt = Date.now();
                  let clicked = false;
                  let error = '';
                  try {
                    if (window.HTMLElement && HTMLElement.prototype && HTMLElement.prototype.click) {
                      HTMLElement.prototype.click.call(input);
                    } else {
                      input.click();
                    }
                    clicked = true;
                  } catch (err) { error = String(err).slice(0,800); }
                  emit('R11_FILE_TRUSTED_ACTIVATION',{
                    ok:clicked,
                    inputCount:count,
                    isTrusted:true,
                    activationCount:fix.trustedActivationCount,
                    error:error
                  });
                }

                document.addEventListener('click',onTrustedClick,true);
                setTimeout(function(){
                  if (token === fix.fileArmToken && fix.fileArmed) {
                    cleanup();
                    emit('R11_FILE_ACTIVATION_TIMEOUT',{token:token,inputCount:count});
                  }
                },3500);
                emit('R11_FILE_ACTIVATION_ARM',{ok:true,token:token,inputCount:count});
                return {ok:true,armed:true,token:token,inputCount:count};
              };

              api.requestFixState = function() {
                return {
                  ok:true,version:fix.version,
                  requestedModel:fix.requestedModel,selectedModel:fix.selectedModel,
                  rewriteCount:fix.rewriteCount,lastOriginalModel:fix.lastOriginalModel,lastAppliedModel:fix.lastAppliedModel,
                  xhrRewriteInstalled:fix.xhrRewriteInstalled,fileArmed:fix.fileArmed,
                  trustedActivationCount:fix.trustedActivationCount,lastTrustedActivationAt:fix.lastTrustedActivationAt
                };
              };

              api.__r11RequestFixPatched = true;
              fix.modelPatchInstalled = true;
              emit('R11_REQUEST_FIX_API_PATCHED',{version:fix.version});
              return true;
            } catch (err) {
              emit('R11_REQUEST_FIX_API_ERROR',{error:String(err).slice(0,800)});
              return false;
            }
          }

          function ensureInstalled() {
            const apiOk = installApiPatch();
            const xhrOk = installXhrRewrite();
            return apiOk && xhrOk;
          }

          window.__AIS_R11_REQUEST_FIX__ = {
            version:fix.version,
            ensureInstalled:ensureInstalled,
            state:function(){return Object.assign({ok:true},fix);}
          };

          let tries = 0;
          const timer = setInterval(function(){
            tries += 1;
            if (ensureInstalled() && tries >= 3) clearInterval(timer);
            if (tries >= 80) clearInterval(timer);
          },50);
          ensureInstalled();
          emit('R11_REQUEST_FIX_INSTALLED',{version:fix.version});
        })();
    """.trimIndent()
}
