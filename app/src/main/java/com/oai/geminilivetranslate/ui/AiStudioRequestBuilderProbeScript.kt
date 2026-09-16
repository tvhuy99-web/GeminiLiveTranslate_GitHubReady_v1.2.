package com.oai.geminilivetranslate.ui

/**
 * Runtime-only probe for the JavaScript call stack that constructs authenticated AI Studio
 * GenerateContent requests.
 *
 * This deliberately does not block, rewrite or replay network traffic. It records only sanitized
 * structural metadata and stack frames so we can identify the request-builder/controller without
 * exporting the raw request body, headers, cookies, proof snapshot or attachment token to Kotlin.
 */
object AiStudioRequestBuilderProbeScript {
    const val VERSION = "2026-09-16-request-builder-probe-v1"

    val INSTALL: String = """
        (function() {
          'use strict';
          const current = window.__AIS_REQUEST_BUILDER_PROBE__;
          if (current && current.version === '$VERSION') return current.status();

          const XHR = window.XMLHttpRequest;
          if (!XHR || !XHR.prototype || typeof XHR.prototype.send !== 'function') {
            return {ok:false,error:'XHR_UNAVAILABLE',version:'$VERSION'};
          }

          const currentSend = XHR.prototype.send;
          const previousSend = currentSend.__aisRequestBuilderProbeOriginal || currentSend;
          let observedCount = 0;
          let last = null;

          function emit(kind, payload) {
            try {
              if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
              }
            } catch (_) {}
          }

          function trustedGenerateUrl(raw) {
            try {
              const u = new URL(String(raw || ''), location.href);
              const host = String(u.hostname || '').toLowerCase();
              const trusted = host === 'aistudio.google.com' ||
                host === 'google.com' || host.endsWith('.google.com') ||
                host === 'googleapis.com' || host.endsWith('.googleapis.com');
              if (!trusted) return false;
              const target = String(u.pathname || '');
              return /MakerSuiteService\/GenerateContent/i.test(target) || /\/GenerateContent(?:$|\/)/i.test(target);
            } catch (_) { return false; }
          }

          function kindOf(v) {
            if (v === null || typeof v === 'undefined') return 'null';
            if (Array.isArray(v)) return 'array';
            return typeof v;
          }

          function fingerprint(root) {
            if (!Array.isArray(root)) return kindOf(root);
            const parts = [];
            for (let i=0;i<Math.min(root.length,14);i++) parts.push(i+'='+kindOf(root[i]));
            return 'len='+root.length+':'+parts.join(',');
          }

          function inspectContents(root) {
            const out = {
              contentCount:0,
              userContentCount:0,
              textPartCount:0,
              nonTextPartCount:0,
              mediaProfile:'unknown'
            };
            if (!Array.isArray(root) || !Array.isArray(root[1])) return out;
            const contents = root[1];
            out.contentCount = contents.length;
            for (let i=0;i<contents.length;i++) {
              const content = contents[i];
              if (!Array.isArray(content) || !Array.isArray(content[0])) {
                out.nonTextPartCount += 1;
                continue;
              }
              if (content[1] === 'user') out.userContentCount += 1;
              const parts = content[0];
              for (let j=0;j<parts.length;j++) {
                const part = parts[j];
                const plainText = Array.isArray(part) && part.length >= 2 &&
                  (part[0] === null || typeof part[0] === 'undefined') && typeof part[1] === 'string';
                if (plainText) out.textPartCount += 1;
                else out.nonTextPartCount += 1;
              }
            }
            if (out.nonTextPartCount === 0 && out.textPartCount > 0) {
              out.mediaProfile = 'text-only';
            } else if (root.length === 14 && out.contentCount === 1 && out.userContentCount === 1 &&
                out.textPartCount === 1 && out.nonTextPartCount === 1) {
              out.mediaProfile = 'video-attachment';
            } else if (root.length === 5 && out.contentCount === 1 && out.userContentCount === 1 &&
                out.textPartCount === 0 && out.nonTextPartCount === 1) {
              out.mediaProfile = 'stt-attachment';
            } else if (out.nonTextPartCount > 0) {
              out.mediaProfile = 'media-other';
            }
            return out;
          }

          function normalizeModel(root) {
            if (!Array.isArray(root) || typeof root[0] !== 'string') return '';
            return String(root[0]).replace(/^models\//i,'').slice(0,160);
          }

          function sanitizeStack() {
            let lines = [];
            try { lines = String((new Error('ais-builder-probe')).stack || '').split('\n').slice(1,14); } catch (_) {}
            return lines.map(function(frame) {
              let value = String(frame || '');
              value = value.replace(/https?:\/\/[^\s)]+/g, function(rawUrl) {
                try {
                  const u = new URL(rawUrl);
                  return u.origin + u.pathname;
                } catch (_) {
                  return String(rawUrl).split(/[?#]/)[0];
                }
              });
              value = value.replace(/([?&](?:token|key|auth|code|session|sig)=)[^&\s)]+/ig, '${'$'}1<redacted>');
              return value.slice(0,280);
            }).filter(function(frame) { return frame.length > 0; });
          }

          function observe(xhr, body) {
            const meta = xhr && xhr.__aisRequestGatewayMeta || {};
            const url = String(meta.url || '');
            if (xhr && xhr.__aisRequestGatewayReplay) return;
            if (!trustedGenerateUrl(url) || typeof body !== 'string' || body.length < 8) return;
            let root;
            try { root = JSON.parse(body); } catch (_) { return; }
            if (!Array.isArray(root)) return;
            const profile = inspectContents(root);
            observedCount += 1;
            last = {
              model:normalizeModel(root),
              fingerprint:fingerprint(root),
              topLevelSize:root.length,
              bodyChars:body.length,
              contentCount:profile.contentCount,
              textPartCount:profile.textPartCount,
              nonTextPartCount:profile.nonTextPartCount,
              mediaProfile:profile.mediaProfile,
              stackFrames:sanitizeStack(),
              observedAt:Date.now()
            };
            emit('REQUEST_GATEWAY_BUILDER_OBSERVED', {
              version:'$VERSION',
              observedCount:observedCount,
              model:last.model,
              fingerprint:last.fingerprint,
              topLevelSize:last.topLevelSize,
              bodyChars:last.bodyChars,
              contentCount:last.contentCount,
              textPartCount:last.textPartCount,
              nonTextPartCount:last.nonTextPartCount,
              mediaProfile:last.mediaProfile,
              stackFrames:last.stackFrames
            });
          }

          const wrappedSend = function(body) {
            try { observe(this, body); } catch (_) {}
            return previousSend.apply(this, arguments);
          };
          wrappedSend.__aisRequestBuilderProbeVersion = '$VERSION';
          wrappedSend.__aisRequestBuilderProbeOriginal = previousSend;
          XHR.prototype.send = wrappedSend;

          const api = {
            version:'$VERSION',
            status:function() {
              return {
                ok:true,
                version:'$VERSION',
                observedCount:observedCount,
                builderObserved:!!last,
                model:last ? last.model : '',
                fingerprint:last ? last.fingerprint : '',
                topLevelSize:last ? last.topLevelSize : 0,
                bodyChars:last ? last.bodyChars : 0,
                contentCount:last ? last.contentCount : 0,
                textPartCount:last ? last.textPartCount : 0,
                nonTextPartCount:last ? last.nonTextPartCount : 0,
                mediaProfile:last ? last.mediaProfile : '',
                stackFrames:last ? last.stackFrames : []
              };
            }
          };
          window.__AIS_REQUEST_BUILDER_PROBE__ = api;
          emit('REQUEST_GATEWAY_BUILDER_PROBE_INSTALLED',{version:'$VERSION'});
          return api.status();
        })();
    """.trimIndent()
}
