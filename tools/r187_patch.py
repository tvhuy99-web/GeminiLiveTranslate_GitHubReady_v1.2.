from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing pattern {label} in {path}")
    if s.count(old) != 1:
        raise SystemExit(f"pattern {label} count={s.count(old)} in {path}")
    p.write_text(s.replace(old, new, 1))
    print(f"patched {label}: {path}")

# 1) R17 Start becomes acknowledgement-driven instead of click-driven.
r17 = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt"
replace_once(r17,
    'const val VERSION = "2026-09-04-web-session-r17.6-lean-live-bootstrap"',
    'const val VERSION = "2026-09-04-web-session-r17.7-start-ack-retry"',
    'r17-version-kotlin')
replace_once(r17,
    "const VERSION='2026-09-04-web-session-r17.6-lean-live-bootstrap';",
    "const VERSION='2026-09-04-web-session-r17.7-start-ack-retry';",
    'r17-version-js')
replace_once(r17,
    "    startScans:0,startCandidates:0,startAttempts:0,modelGuardInstalled:false,modelGuardRequests:0,\n    modelRewriteRequests:0,modelRewriteCount:0,routeKind:'other'\n",
    "    startScans:0,startCandidates:0,startAttempts:0,startAckTimeouts:0,startStableScans:0,lastStartSignature:'',modelGuardInstalled:false,modelGuardRequests:0,\n    modelRewriteRequests:0,modelRewriteCount:0,routeKind:'other'\n",
    'r17-state')
old_try = """  function tryStart(snapshot){
    state.startScans++;if(setupSeen()){state.setupObserved=true;state.stage='setup-complete';state.lastBlocker='none';return;}
    if(!state.streamSelected){state.lastBlocker='waiting-stream';return;}if(!state.modelSeen&&!state.modelGuardInstalled){state.lastBlocker='waiting-model';return;}
    const scored=[];for(let i=0;i<snapshot.interactive.length;i++){const score=startScore(snapshot.interactive[i]);if(score>=7)scored.push({el:snapshot.interactive[i],score:score});}
    scored.sort(function(a,b){return b.score-a.score;});state.startCandidates=scored.length;
    if(scored.length&&state.startAttempts<8){state.startAttempts++;if(clickElement(scored[0].el,'start-live')){state.stage='start-clicked';state.lastBlocker='waiting-live-setup';buildSyntheticCarrier();return;}}
    state.lastBlocker='start-control-not-found';
  }
"""
new_try = """  function tryStart(snapshot){
    state.startScans++;if(setupSeen()){state.setupObserved=true;state.stage='setup-complete';state.lastBlocker='none';return;}
    if(!state.streamSelected){state.lastBlocker='waiting-stream';return;}if(!state.modelSeen&&!state.modelGuardInstalled){state.lastBlocker='waiting-model';return;}
    const now=Date.now();
    if(state.lastAction==='start-live'&&state.lastActionAt){
      const age=now-state.lastActionAt;
      if(age<3000){state.stage='start-clicked';state.lastBlocker='waiting-start-ack';return;}
      state.startAckTimeouts++;diag('START_ACK_TIMEOUT',{attempt:state.startAttempts,ageMs:age,ackTimeouts:state.startAckTimeouts});
      state.lastAction='start-ack-timeout';state.lastActionAt=0;state.startStableScans=0;state.lastStartSignature='';
    }
    const scored=[];for(let i=0;i<snapshot.interactive.length;i++){const score=startScore(snapshot.interactive[i]);if(score>=7)scored.push({el:snapshot.interactive[i],score:score});}
    scored.sort(function(a,b){return b.score-a.score;});state.startCandidates=scored.length;
    if(!scored.length){state.startStableScans=0;state.lastStartSignature='';state.lastBlocker='start-control-not-found';return;}
    const best=scored[0],sig=[tag(best.el),role(best.el),label(best.el).slice(0,180)].join('|');
    if(sig===state.lastStartSignature)state.startStableScans++;else{state.lastStartSignature=sig;state.startStableScans=1;}
    if(state.startStableScans<2){state.lastBlocker='waiting-start-stable';return;}
    if(state.startAttempts<6){
      state.startAttempts++;
      if(clickElement(best.el,'start-live')){state.stage='start-clicked';state.lastBlocker='waiting-start-ack';buildSyntheticCarrier();diag('START_ATTEMPT',{attempt:state.startAttempts,score:best.score,stableScans:state.startStableScans});return;}
    }
    state.lastBlocker=state.startAttempts>=6?'start-retries-exhausted':'start-control-not-found';
  }
"""
replace_once(r17, old_try, new_try, 'r17-ack-start')
replace_once(r17,
    "      startScans:state.startScans,startCandidates:state.startCandidates,startAttempts:state.startAttempts,modelGuardInstalled:state.modelGuardInstalled,modelGuardRequests:state.modelGuardRequests,modelRewriteRequests:state.modelRewriteRequests,modelRewriteCount:state.modelRewriteCount,\n",
    "      startScans:state.startScans,startCandidates:state.startCandidates,startAttempts:state.startAttempts,startAckTimeouts:state.startAckTimeouts,startStableScans:state.startStableScans,modelGuardInstalled:state.modelGuardInstalled,modelGuardRequests:state.modelGuardRequests,modelRewriteRequests:state.modelRewriteRequests,modelRewriteCount:state.modelRewriteCount,\n",
    'r17-describe')
replace_once(r17,
    "  function resetAutomation(){state.startAttempts=0;state.startCandidates=0;state.setupObserved=false;state.lastAction='';state.stage='discover';state.lastBlocker='waiting-start';tick();return describe();}\n",
    "  function resetAutomation(){state.startAttempts=0;state.startCandidates=0;state.startAckTimeouts=0;state.startStableScans=0;state.lastStartSignature='';state.setupObserved=false;state.lastAction='';state.lastActionAt=0;state.stage='discover';state.lastBlocker='waiting-start';tick();return describe();}\n",
    'r17-reset')

# 2) R11 exposes the exact composer Send/Generate coordinates without clicking it.
r11 = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt"
replace_once(r11,
    'const val VERSION = "2026-09-02-web-session-r11.4-composer-submit-target"',
    'const val VERSION = "2026-09-04-web-session-r11.5-native-composer-submit"',
    'r11-version')
insert_anchor = """          function installClickTracking(){
"""
native_fn = """          function nativeTargetIfAttachment(){
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

"""
replace_once(r11, insert_anchor, native_fn + insert_anchor, 'r11-native-target-function')
replace_once(r11,
    "            submitIfAttachment:submitIfAttachment,\n            state:function(){return {ok:true,version:'$VERSION',submitAttempts:submitAttempts,proven:!!(provenButton&&provenButton.isConnected),provenFingerprint:provenFingerprint,clickEntries:clickEntries.filter(function(e){return e.active;}).length};}\n",
    "            nativeTargetIfAttachment:nativeTargetIfAttachment,\n            submitIfAttachment:submitIfAttachment,\n            state:function(){return {ok:true,version:'$VERSION',submitAttempts:submitAttempts,proven:!!(provenButton&&provenButton.isConnected),provenFingerprint:provenFingerprint,clickEntries:clickEntries.filter(function(e){return e.active;}).length};}\n",
    'r11-export-native-target')

# 3) R12 executor uses native Android tap first for attached-video Generate and keeps debug page visible after failure/stop.
executor = "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt"
replace_once(executor,
    'const val VERSION = "2026-09-02-web-session-r12.1-progress-watchdog"',
    'const val VERSION = "2026-09-04-web-session-r12.2-native-submit-persistent-debug"',
    'r12-version')
old_destroy = """    fun destroy() {
        if (destroyed) return
        cancelCurrent()
        destroyed = true
        state = State.DESTROYED
        activeAttachment?.let { it.callback(false, "DESTROYED") }
        activeAttachment = null
        AiStudioDebugWebViewHost.detach(webView, null)
        runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        events?.onStateChanged(State.DESTROYED, "destroyed")
    }
"""
new_destroy = """    fun destroy() {
        if (destroyed) return
        cancelCurrent()
        destroyed = true
        state = State.DESTROYED
        activeAttachment?.let { it.callback(false, "DESTROYED") }
        activeAttachment = null
        runCatching { webView.stopLoading() }
        runCatching { webView.onPause() }
        runCatching { webView.removeJavascriptInterface(JS_BRIDGE_NAME) }
        AiStudioDebugWebViewHost.retain(webView, null, "executor-destroy")
        events?.onStateChanged(State.DESTROYED, "destroyed-debug-webview-retained")
    }
"""
replace_once(executor, old_destroy, new_destroy, 'r12-persistent-debug')
old_legacy = """    private fun tryLegacyProgrammaticFallback(requestSeq: Int, reason: String) {
        if (pending?.seq != requestSeq) return
        events?.onLog("R12_LEGACY_FALLBACK_START", "seq=$requestSeq reason=$reason")
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.submitIfAttachment() : ({ok:false,error:'submit-target-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R12_LEGACY_FALLBACK_DISPATCH", decoded.take(10000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val attempted = obj?.optBoolean("attempted") == true || obj?.optBoolean("pending") == true
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            if (!attempted) {
                finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                return@evaluateJavascript
            }
            main.postDelayed({
                checkGenerateCapture(requestSeq, baseline, "legacy-programmatic") { started ->
                    if (pending?.seq != requestSeq) return@checkGenerateCapture
                    if (started) {
                        setState(State.GENERATING, "legacy diagnostic fallback triggered GenerateContent")
                        readNormalized(requestSeq, "legacy-fallback")
                    } else {
                        finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                    }
                }
            }, LEGACY_FALLBACK_CHECK_MS)
        }
    }
"""
new_legacy = """    private fun tryLegacyProgrammaticFallback(requestSeq: Int, reason: String) {
        tryNativeAttachmentSubmit(requestSeq, reason, 0)
    }

    private fun tryNativeAttachmentSubmit(requestSeq: Int, reason: String, attempt: Int) {
        if (pending?.seq != requestSeq) return
        events?.onLog("R12_NATIVE_SUBMIT_START", "seq=$requestSeq reason=$reason attempt=${attempt + 1}")
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.nativeTargetIfAttachment() : ({ok:false,error:'native-submit-target-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R12_NATIVE_SUBMIT_TARGET", "attempt=${attempt + 1} ${decoded.take(10000)}")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") != true) {
                if (attempt < NATIVE_SUBMIT_MAX_RETRIES - 1) {
                    main.postDelayed({ tryNativeAttachmentSubmit(requestSeq, "target-rescan", attempt + 1) }, NATIVE_SUBMIT_RETRY_MS)
                } else {
                    tryProgrammaticAttachmentFallback(requestSeq, "native-target-unavailable")
                }
                return@evaluateJavascript
            }
            val xRatio = obj.optDouble("xRatio", Double.NaN)
            val yRatio = obj.optDouble("yRatio", Double.NaN)
            val baseline = obj.optInt("baselineCaptureCount", -1)
            if (!xRatio.isFinite() || !yRatio.isFinite() || baseline < 0) {
                if (attempt < NATIVE_SUBMIT_MAX_RETRIES - 1) main.postDelayed({ tryNativeAttachmentSubmit(requestSeq, "invalid-native-target", attempt + 1) }, NATIVE_SUBMIT_RETRY_MS)
                else tryProgrammaticAttachmentFallback(requestSeq, "invalid-native-target")
                return@evaluateJavascript
            }
            nativeTapController.requestNativeTap(
                JSONObject()
                    .put("xRatio", xRatio)
                    .put("yRatio", yRatio)
                    .put("tag", "VIDEO_SEND")
                    .put("role", "composer-submit")
                    .put("purpose", "video-generate")
                    .toString(),
            )
            main.postDelayed({
                checkGenerateCapture(requestSeq, baseline, "native-submit-${attempt + 1}") { started ->
                    if (pending?.seq != requestSeq) return@checkGenerateCapture
                    if (started) {
                        events?.onLog("R12_NATIVE_SUBMIT_ACK", "seq=$requestSeq attempt=${attempt + 1} captureStarted=true")
                        setState(State.GENERATING, "native composer tap triggered GenerateContent")
                        readNormalized(requestSeq, "native-submit")
                    } else if (attempt < NATIVE_SUBMIT_MAX_RETRIES - 1) {
                        events?.onLog("R12_NATIVE_SUBMIT_RETRY", "seq=$requestSeq attempt=${attempt + 1} reason=no-capture")
                        main.postDelayed({ tryNativeAttachmentSubmit(requestSeq, "no-capture", attempt + 1) }, NATIVE_SUBMIT_RETRY_MS)
                    } else {
                        tryProgrammaticAttachmentFallback(requestSeq, "native-no-capture")
                    }
                }
            }, NATIVE_SUBMIT_ACK_MS)
        }
    }

    private fun tryProgrammaticAttachmentFallback(requestSeq: Int, reason: String) {
        if (pending?.seq != requestSeq) return
        events?.onLog("R12_LEGACY_FALLBACK_START", "seq=$requestSeq reason=$reason")
        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.submitIfAttachment() : ({ok:false,error:'submit-target-not-installed'}))"
        webView.evaluateJavascript(expression) { raw ->
            if (pending?.seq != requestSeq) return@evaluateJavascript
            val decoded = decodeEvalValue(raw)
            events?.onLog("R12_LEGACY_FALLBACK_DISPATCH", decoded.take(10000))
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val attempted = obj?.optBoolean("attempted") == true || obj?.optBoolean("pending") == true
            val baseline = obj?.optInt("baselineCaptureCount", -1) ?: -1
            if (!attempted) {
                finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                return@evaluateJavascript
            }
            main.postDelayed({
                checkGenerateCapture(requestSeq, baseline, "legacy-programmatic") { started ->
                    if (pending?.seq != requestSeq) return@checkGenerateCapture
                    if (started) {
                        setState(State.GENERATING, "legacy diagnostic fallback triggered GenerateContent")
                        readNormalized(requestSeq, "legacy-fallback")
                    } else {
                        finish(requestSeq, Result(ok = false, error = "NO_HANDLER_TRIGGERED_REQUEST"))
                    }
                }
            }, LEGACY_FALLBACK_CHECK_MS)
        }
    }
"""
replace_once(executor, old_legacy, new_legacy, 'r12-native-submit-flow')
replace_once(executor,
    "        private const val LEGACY_FALLBACK_CHECK_MS = 900L\n",
    "        private const val LEGACY_FALLBACK_CHECK_MS = 900L\n        private const val NATIVE_SUBMIT_ACK_MS = 1_250L\n        private const val NATIVE_SUBMIT_RETRY_MS = 900L\n        private const val NATIVE_SUBMIT_MAX_RETRIES = 3\n",
    'r12-native-submit-constants')

# 4) Native tap diagnostics + persistent visible WebView host.
native = "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioNativeTapDebugSupport.kt"
replace_once(native,
    'const val VERSION = "2026-09-04-r18.4-native-start-tap-debug"',
    'const val VERSION = "2026-09-04-r18.7-native-action-tap-debug"',
    'native-version-kotlin')
replace_once(native,
    "const VERSION='2026-09-04-r18.4-native-start-tap-debug';",
    "const VERSION='2026-09-04-r18.7-native-action-tap-debug';",
    'native-version-js')
replace_once(native,
    "        val role = parsed?.optString(\"role\").orEmpty().take(48)\n",
    "        val role = parsed?.optString(\"role\").orEmpty().take(48)\n        val purpose = parsed?.optString(\"purpose\").orEmpty().take(48).ifBlank { \"start-live\" }\n",
    'native-purpose-parse')
replace_once(native,
    'logger?.log(3, "AiStudioNativeTap", "START_TAP_SKIPPED debounce=true")',
    'logger?.log(3, "AiStudioNativeTap", "ACTION_TAP_SKIPPED purpose=$purpose debounce=true")',
    'native-debounce-log')
replace_once(native,
    'logger?.log(1, "AiStudioNativeTap", "START_TAP_REJECT laidOut=${width >= 4 && height >= 4} shown=${webView.isShown} width=$width height=$height")',
    'logger?.log(1, "AiStudioNativeTap", "ACTION_TAP_REJECT purpose=$purpose laidOut=${width >= 4 && height >= 4} shown=${webView.isShown} width=$width height=$height")',
    'native-layout-log')
replace_once(native,
    'logger?.log(2, "AiStudioNativeTap", "START_TAP_DOWN x=${x.roundToInt()} y=${y.roundToInt()} width=$width height=$height handled=$downHandled tag=$tag role=$role")',
    'logger?.log(2, "AiStudioNativeTap", "ACTION_TAP_DOWN purpose=$purpose x=${x.roundToInt()} y=${y.roundToInt()} width=$width height=$height handled=$downHandled tag=$tag role=$role")',
    'native-down-log')
replace_once(native,
    'logger?.log(1, "AiStudioNativeTap", "START_TAP_UP skipped=detached")',
    'logger?.log(1, "AiStudioNativeTap", "ACTION_TAP_UP purpose=$purpose skipped=detached")',
    'native-up-detached-log')
replace_once(native,
    'logger?.log(2, "AiStudioNativeTap", "START_TAP_UP x=${x.roundToInt()} y=${y.roundToInt()} handled=$upHandled durationMs=${upTime - downTime}")',
    'logger?.log(2, "AiStudioNativeTap", "ACTION_TAP_UP purpose=$purpose x=${x.roundToInt()} y=${y.roundToInt()} handled=$upHandled durationMs=${upTime - downTime}")',
    'native-up-log')
attach_anchor = """        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.setBackgroundColor(Color.WHITE)
"""
attach_new = """        panels.keys.toList().filter { it !== webView }.forEach { staleView ->
            val stalePanel = panels.remove(staleView)?.get()
            (staleView.parent as? ViewGroup)?.removeView(staleView)
            (stalePanel?.parent as? ViewGroup)?.removeView(stalePanel)
            runCatching { staleView.stopLoading() }
            runCatching { staleView.loadUrl("about:blank") }
            runCatching { staleView.destroy() }
            logger?.log(3, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_REPLACED previous=true")
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.setBackgroundColor(Color.WHITE)
"""
replace_once(native, attach_anchor, attach_new, 'debug-host-replace-old')
retain_anchor = """    fun detach(webView: WebView, logger: SessionLogger?) {
"""
retain_fn = """    fun retain(webView: WebView, logger: SessionLogger?, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { retain(webView, logger, reason) }
            return
        }
        val panel = panels[webView]?.get()
        if (panel != null && panel.parent != null && webView.parent != null) {
            logger?.log(2, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_RETAINED reason=$reason visible=true")
            return
        }
        attach(webView, logger)
        logger?.log(2, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_RETAIN_REQUEST reason=$reason")
    }

"""
replace_once(native, retain_anchor, retain_fn + retain_anchor, 'debug-host-retain')

# 5) Live client preserves failed/stopped page for inspection and updates version/log truth.
live = "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt"
replace_once(live,
    'const val VERSION = "2026-09-04-production-ai-studio-live-r4-native-tap-language-guard-debug"',
    'const val VERSION = "2026-09-04-production-ai-studio-live-r5-start-ack-persistent-debug"',
    'live-version')
old_close_view = """            if (current != null) {
                AiStudioDebugWebViewHost.detach(current, logger)
                runCatching { current.stopLoading() }
                runCatching { current.onPause() }
                runCatching { current.removeJavascriptInterface(DIAGNOSTIC_BRIDGE_NAME) }
                runCatching { current.removeJavascriptInterface(NATIVE_TAP_BRIDGE_NAME) }
                runCatching { current.loadUrl("about:blank") }
                runCatching { current.clearHistory() }
                runCatching { current.destroy() }
            }
"""
new_close_view = """            if (current != null) {
                runCatching { current.stopLoading() }
                runCatching { current.onPause() }
                runCatching { current.removeJavascriptInterface(DIAGNOSTIC_BRIDGE_NAME) }
                runCatching { current.removeJavascriptInterface(NATIVE_TAP_BRIDGE_NAME) }
                AiStudioDebugWebViewHost.retain(current, logger, "live-close-${if (setupDelivered.get()) "after-setup" else "before-setup"}")
            }
"""
replace_once(live, old_close_view, new_close_view, 'live-persistent-debug')
replace_once(live,
    'logger.log(2, "AiStudioLive", "READY model=${targetLiveModel()} operation=$operationMode target=$targetLanguage carrierRequests=$carriers template=${safe(direct.optString("templateMime"), 100)} hidden=true isolatedLiveHost=true")',
    'logger.log(2, "AiStudioLive", "READY model=${targetLiveModel()} operation=$operationMode target=$targetLanguage carrierRequests=$carriers template=${safe(direct.optString("templateMime"), 100)} hidden=false debugVisible=true isolatedLiveHost=true")',
    'live-ready-log')
replace_once(live,
    '"FAIL hidden=true isolatedLiveHost=true setup=${setupDelivered.get()} operation=$operationMode model=${targetLiveModel()} target=$targetLanguage routeRepairs=$routeRepairAttempts bootstrapInstalled=$bootstrapInstalled configured=$configured languageGuardConfigured=$languageGuardConfigured bootstrapRecoveries=$bootstrapRecoveryAttempts lastBootstrapInstallError=${safe(lastBootstrapInstallError, 600)} bootstrap=${safe(lastBootstrapState, 2400)} language=${safe(lastLanguageGuardState, 2200)} direct=${safe(lastDirectState, 1800)} output=${safe(lastOutputState, 1800)}",',
    '"FAIL hidden=false debugVisible=true isolatedLiveHost=true setup=${setupDelivered.get()} operation=$operationMode model=${targetLiveModel()} target=$targetLanguage routeRepairs=$routeRepairAttempts bootstrapInstalled=$bootstrapInstalled configured=$configured languageGuardConfigured=$languageGuardConfigured bootstrapRecoveries=$bootstrapRecoveryAttempts lastBootstrapInstallError=${safe(lastBootstrapInstallError, 600)} bootstrap=${safe(lastBootstrapState, 2400)} language=${safe(lastLanguageGuardState, 2200)} direct=${safe(lastDirectState, 1800)} output=${safe(lastOutputState, 1800)}",',
    'live-fail-log')

print('R18.7 source patch complete')
