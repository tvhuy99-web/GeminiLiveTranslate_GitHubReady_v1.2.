package com.oai.geminilivetranslate.ui


object AiStudioWebSessionResponseCore {
    const val VERSION = "2026-09-02-web-session-r7.1-response-core"

    val DOCUMENT_START: String = """
        (function() {
          'use strict';
          if (window.__AIS_RESPONSE_CORE__ && window.__AIS_RESPONSE_CORE__.version === '$VERSION') return;

          function emit(kind,payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function decodeJsonString(escaped) {
            try { return JSON.parse('"' + String(escaped||'') + '"'); }
            catch (_) { return null; }
          }

          function extractModelText(raw) {
            const text=String(raw||'');
            const pieces=[];
            const re=/\[null,"((?:\\.|[^"\\])*)"\]\],"model"/g;
            let m;
            while ((m=re.exec(text)) !== null) {
              const decoded=decodeJsonString(m[1]);
              if (typeof decoded === 'string') pieces.push(decoded);
              if (pieces.length >= 512) break;
            }
            return pieces.join('');
          }

          function terminalSignal(raw) {
            const text=String(raw||'');
            return /\],"model"\],1\]\]/.test(text);
          }

          function normalize(value) {
            const input=value && typeof value === 'object' ? value : {ok:false,error:'no-result'};
            const raw=String(input.responseText||'');
            const modelText=extractModelText(raw);
            const terminal=terminalSignal(raw);
            const complete=!!input.ok && (terminal || input.partial === false);
            const out={};
            Object.keys(input).forEach(function(k){out[k]=input[k];});
            out.modelText=modelText;
            out.modelTextChars=modelText.length;
            out.terminalSignal=terminal;
            out.complete=complete;
            out.partial=!!input.partial && !complete;
            out.normalizedBy='$VERSION';
            if (complete && String(out.phase||'').indexOf('normalized')<0) {
              out.phase=String(out.phase||'response')+'-normalized';
            }
            return out;
          }

          const state={
            version:'$VERSION',
            installed:false,
            lastFingerprint:'',
            normalize:normalize,
            extractModelText:extractModelText,
            getNormalized:function() {
              const net=window.__AIS_WEB_SESSION__;
              if (!net || typeof net.getLastSafeResponse !== 'function') return {ok:false,error:'network-probe-not-installed'};
              try { return normalize(net.getLastSafeResponse()); }
              catch (e) { return {ok:false,error:String(e)}; }
            }
          };
          window.__AIS_RESPONSE_CORE__=state;

          function install() {
            const net=window.__AIS_WEB_SESSION__;
            if (!net || typeof net.getLastSafeResponse !== 'function') return false;
            if (!net.__aisR71OriginalGetLastSafeResponse) {
              net.__aisR71OriginalGetLastSafeResponse=net.getLastSafeResponse.bind(net);
              net.getLastSafeResponse=function(){
                return normalize(net.__aisR71OriginalGetLastSafeResponse());
              };
            }
            state.installed=true;
            emit('RESPONSE_CORE_INSTALLED',{version:state.version,probeVersion:String(net.version||'')});
            return true;
          }

          let installTries=0;
          const installTimer=setInterval(function(){
            installTries+=1;
            if (install() || installTries>200) clearInterval(installTimer);
          },25);
          install();

          setInterval(function(){
            if (!state.installed) { install(); return; }
            const net=window.__AIS_WEB_SESSION__;
            if (!net) return;
            const source=net.lastResult || net.lastProgress;
            if (!source) return;
            const normalized=normalize(source);
            const fp=[normalized.at,normalized.responseChars,normalized.phase,normalized.modelTextChars].join('|');
            if (fp===state.lastFingerprint) return;
            state.lastFingerprint=fp;
            emit('NORMALIZED_GENERATE_RESULT',normalized);
          },80);
        })();
    """.trimIndent()
}
