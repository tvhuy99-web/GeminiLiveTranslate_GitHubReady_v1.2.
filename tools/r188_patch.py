from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match in {path}, got {count}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {label}: {path}")

# 1) Video upload readiness: do not treat attachment presence as upload completion.
submit = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt"
replace_once(
    submit,
    'const val VERSION = "2026-09-04-web-session-r11.5-native-composer-submit"',
    'const val VERSION = "2026-09-04-web-session-r11.6-upload-readiness-native-submit"',
    'submit-version',
)
anchor = """          function nativeTargetIfAttachment(){
"""
readiness_fn = """          function submissionReadinessIfAttachment(){
            const net=window.__AIS_WEB_SESSION__,baseline=Number(net&&net.captureCount||0);
            if(!attachmentPresent())return {ok:true,ready:false,error:'NO_ATTACHMENT',baselineCaptureCount:baseline};
            const d=discover(),list=d.candidates;
            if(!list.length)return {ok:true,ready:false,error:'NO_BUTTON_CANDIDATE',baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot};
            const best=list[0],ready=!best.disabled&&best.score>=2500;
            return {ok:true,ready:ready,disabled:!!best.disabled,score:best.score,label:best.label.slice(0,180),baselineCaptureCount:baseline,hasAttachment:!!d.attachment,hasPrompt:!!d.prompt,hasComposerRoot:!!d.composerRoot,fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};
          }

"""
replace_once(submit, anchor, readiness_fn + anchor, 'submit-readiness-function')
replace_once(
    submit,
    "            nativeTargetIfAttachment:nativeTargetIfAttachment,\n            submitIfAttachment:submitIfAttachment,\n",
    "            submissionReadinessIfAttachment:submissionReadinessIfAttachment,\n            nativeTargetIfAttachment:nativeTargetIfAttachment,\n            submitIfAttachment:submitIfAttachment,\n",
    'submit-readiness-export',
)

request_fix = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"
replace_once(
    request_fix,
    'const val VERSION = "2026-09-02-web-session-r11.3-attachment-submit"',
    'const val VERSION = "2026-09-04-web-session-r11.6-upload-ready-gate"',
    'request-fix-version',
)
replace_once(
    request_fix,
    "fix.attachmentWindowUntil = Date.now() + 90000;",
    "fix.attachmentWindowUntil = Date.now() + 300000;",
    'attachment-window-duration',
)
replace_once(
    request_fix,
    "windowMs:90000",
    "windowMs:300000",
    'attachment-window-log',
)
old_evidence = """              api.attachmentEvidence = function() {
                return {
                  ok:true,version:fix.version,windowActive:attachmentWindowActive(),present:attachmentPresent(),nameVisible:attachmentNameVisible(),
                  expectedName:fix.attachmentExpectedName,expectedMime:fix.attachmentExpectedMime,expectedSize:fix.attachmentExpectedSize,
                  fileChangeCount:fix.attachmentFileChangeCount,fileChangeMatched:fix.attachmentFileChangeMatched,
                  lastChangedName:fix.attachmentLastChangedName,lastChangedMime:fix.attachmentLastChangedMime,lastChangedSize:fix.attachmentLastChangedSize,
                  fileReadCount:fix.attachmentFileReadCount,lastReadKind:fix.attachmentLastReadKind,
                  networkStarted:fix.attachmentNetworkStarted,networkCompleted:fix.attachmentNetworkCompleted,networkFailed:fix.attachmentNetworkFailed,
                  submitFallbacks:fix.attachmentSubmitFallbacks,buttonClicks:fix.attachmentSubmitButtonClicks,
                  listenerInvokes:fix.attachmentSubmitListenerInvokes,lastSubmitLabel:fix.attachmentLastSubmitLabel,lastSubmitPath:fix.attachmentLastSubmitPath,
                  clickEntryCount:clickEntries.filter(function(e){return e.active;}).length,lastNet:fix.attachmentLastNet
                };
              };
"""
new_evidence = """              api.attachmentEvidence = function() {
                let support={},submit={};
                try{support=window.__AIS_R11_SUPPORT__&&window.__AIS_R11_SUPPORT__.attachmentState?window.__AIS_R11_SUPPORT__.attachmentState(fix.attachmentExpectedName):{};}catch(_){}
                try{submit=window.__AIS_R11_SUBMIT_TARGET__&&window.__AIS_R11_SUBMIT_TARGET__.submissionReadinessIfAttachment?window.__AIS_R11_SUBMIT_TARGET__.submissionReadinessIfAttachment():{};}catch(_){}
                const present=attachmentPresent();
                const activeUploads=Number(support.activeUploads||0),uploadStarted=Number(support.uploadStarted||0),uploadCompleted=Number(support.uploadCompleted||0),uploadFailed=Number(support.uploadFailed||0);
                const uploadSettled=activeUploads===0&&(uploadStarted===0||(uploadCompleted+uploadFailed)>=uploadStarted);
                const busy=!!support.busy,submitReady=!!submit.ready;
                const ready=present&&!busy&&uploadSettled&&submitReady;
                return {
                  ok:true,version:fix.version,windowActive:attachmentWindowActive(),present:present,ready:ready,nameVisible:attachmentNameVisible(),busy:busy,submitReady:submitReady,
                  submitScore:Number(submit.score||-1),submitDisabled:!!submit.disabled,submitLabel:String(submit.label||'').slice(0,180),
                  uploadSettled:uploadSettled,activeUploads:activeUploads,uploadStarted:uploadStarted,uploadCompleted:uploadCompleted,uploadFailed:uploadFailed,
                  expectedName:fix.attachmentExpectedName,expectedMime:fix.attachmentExpectedMime,expectedSize:fix.attachmentExpectedSize,
                  fileChangeCount:fix.attachmentFileChangeCount,fileChangeMatched:fix.attachmentFileChangeMatched,
                  lastChangedName:fix.attachmentLastChangedName,lastChangedMime:fix.attachmentLastChangedMime,lastChangedSize:fix.attachmentLastChangedSize,
                  fileReadCount:fix.attachmentFileReadCount,lastReadKind:fix.attachmentLastReadKind,
                  networkStarted:fix.attachmentNetworkStarted,networkCompleted:fix.attachmentNetworkCompleted,networkFailed:fix.attachmentNetworkFailed,
                  submitFallbacks:fix.attachmentSubmitFallbacks,buttonClicks:fix.attachmentSubmitButtonClicks,
                  listenerInvokes:fix.attachmentSubmitListenerInvokes,lastSubmitLabel:fix.attachmentLastSubmitLabel,lastSubmitPath:fix.attachmentLastSubmitPath,
                  clickEntryCount:clickEntries.filter(function(e){return e.active;}).length,lastNet:fix.attachmentLastNet
                };
              };
"""
replace_once(request_fix, old_evidence, new_evidence, 'attachment-ready-evidence')

executor = "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt"
replace_once(
    executor,
    'const val VERSION = "2026-09-04-web-session-r12.2-native-submit-persistent-debug"',
    'const val VERSION = "2026-09-04-web-session-r12.3-upload-ready-native-submit"',
    'executor-version',
)
replace_once(
    executor,
    """        val startedAt: Long,
        val callback: (Boolean, String) -> Unit,
    )
""",
    """        val startedAt: Long,
        val callback: (Boolean, String) -> Unit,
        var readyScans: Int = 0,
        var readySince: Long = 0L,
    )
""",
    'pending-attachment-ready-state',
)
old_poll = """            val present = obj?.optBoolean(\"present\", false) == true
            events?.onLog(\"R18_ATTACHMENT_STATE\", decoded.take(7000))
            if (present) finishAttachment(token, true, decoded)
            else main.postDelayed({ pollAttachment(token) }, 500L)
"""
new_poll = """            val present = obj?.optBoolean(\"present\", false) == true
            val ready = obj?.optBoolean(\"ready\", false) == true
            val now = SystemClock.uptimeMillis()
            if (ready) {
                item.readyScans += 1
                if (item.readySince == 0L) item.readySince = now
            } else {
                item.readyScans = 0
                item.readySince = 0L
            }
            events?.onLog(\"R18_ATTACHMENT_STATE\", \"readyScans=${item.readyScans} ${decoded.take(7000)}\")
            if (ready && item.readyScans >= ATTACHMENT_READY_STABLE_SCANS && now - item.readySince >= ATTACHMENT_READY_SETTLE_MS) {
                events?.onLog(\"R18_ATTACHMENT_UPLOAD_READY\", \"token=$token stableScans=${item.readyScans} waitedMs=${now - item.startedAt}\")
                finishAttachment(token, true, decoded)
            } else {
                if (present && !ready) {
                    events?.onLog(\"R18_ATTACHMENT_WAIT_UPLOAD\", \"token=$token busy=${obj?.optBoolean(\"busy\", false)} uploadSettled=${obj?.optBoolean(\"uploadSettled\", false)} submitReady=${obj?.optBoolean(\"submitReady\", false)} activeUploads=${obj?.optInt(\"activeUploads\", 0)}\")
                }
                main.postDelayed({ pollAttachment(token) }, 500L)
            }
"""
replace_once(executor, old_poll, new_poll, 'executor-upload-ready-poll')
replace_once(executor, "private const val ATTACHMENT_TIMEOUT_MS = 90_000L", "private const val ATTACHMENT_TIMEOUT_MS = 300_000L", 'attachment-timeout')
replace_once(
    executor,
    "private const val FIXED_TIMEOUT_MAX_MS = 300_000L",
    "private const val ATTACHMENT_READY_SETTLE_MS = 1_200L\n        private const val ATTACHMENT_READY_STABLE_SCANS = 3\n        private const val FIXED_TIMEOUT_MAX_MS = 300_000L",
    'attachment-ready-constants',
)

video = "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt"
replace_once(video, 'onProgress("Đang gắn nguyên video vào AI Studio...", 12)', 'onProgress("Đang tải và chờ AI Studio xử lý nguyên video...", 12)', 'video-progress-text')
replace_once(video, 'logger.log(2, TAG, "Attachment ready name=$displayName size=$size")', 'logger.log(2, TAG, "Attachment upload-ready name=$displayName size=$size")', 'video-ready-log')

# 2/3) Live transcription + translation: make Start acknowledgement progress-aware and bounded-recoverable.
r17 = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR17ProductionBootstrap.kt"
replace_once(
    r17,
    'const val VERSION = "2026-09-04-web-session-r17.7-start-ack-retry"',
    'const val VERSION = "2026-09-04-web-session-r17.8-progress-aware-start-ack"',
    'r17-version-kotlin',
)
replace_once(
    r17,
    "const VERSION='2026-09-04-web-session-r17.7-start-ack-retry';",
    "const VERSION='2026-09-04-web-session-r17.8-progress-aware-start-ack';",
    'r17-version-js',
)
old_try = """  function tryStart(snapshot){
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
new_try = """  function startProgressEvidence(){
    try{const o=window.__AIS_LIVE_OUTPUT_ENGINE__;const d=o&&typeof o.describe==='function'?o.describe():null;if(d&&Number(d.setupCompleteEvents||0)>0)return {progress:true,kind:'server-setup'};}catch(_){}
    try{const l=window.__AIS_R183_LANGUAGE__;const d=l&&typeof l.describe==='function'?l.describe():null;if(d&&(d.targetLanguageVerified||Number(d.rewriteRequests||0)>0||Number(d.translateSetupRequests||0)>0))return {progress:true,kind:'language-setup'};}catch(_){}
    try{const r=window.__AIS_LIVE_DIRECT_ENGINE__;const d=r&&typeof r.describe==='function'?r.describe():null;if(d&&(d.templateObserved||Number(d.carrierRequests||0)>0))return {progress:true,kind:'carrier-template'};}catch(_){}
    return {progress:false,kind:'none'};
  }
  function tryStart(snapshot){
    state.startScans++;if(setupSeen()){state.setupObserved=true;state.stage='setup-complete';state.lastBlocker='none';return;}
    if(!state.streamSelected){state.lastBlocker='waiting-stream';return;}if(!state.modelSeen&&!state.modelGuardInstalled){state.lastBlocker='waiting-model';return;}
    const now=Date.now();
    if(state.lastAction==='start-live'&&state.lastActionAt){
      const age=now-state.lastActionAt,progress=startProgressEvidence(),limit=progress.progress?30000:10000;
      if(age<limit){state.stage='start-clicked';state.lastBlocker=progress.progress?'waiting-start-ack-progress-'+progress.kind:'waiting-start-ack';return;}
      state.startAckTimeouts++;diag('START_ACK_TIMEOUT',{attempt:state.startAttempts,ageMs:age,ackTimeouts:state.startAckTimeouts,progress:progress.progress,progressKind:progress.kind,limitMs:limit});
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
replace_once(r17, old_try, new_try, 'r17-progress-aware-start')

live = "app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt"
replace_once(
    live,
    'const val VERSION = "2026-09-04-production-ai-studio-live-r5-start-ack-persistent-debug"',
    'const val VERSION = "2026-09-04-production-ai-studio-live-r6-progress-aware-start-debug"',
    'live-version',
)
replace_once(
    live,
    "@Volatile private var pageGeneration = 0\n",
    "@Volatile private var pageGeneration = 0\n    @Volatile private var startSessionRecoveryAttempts = 0\n    @Volatile private var lastStartSessionRecoveryAt = 0L\n",
    'live-start-recovery-state',
)
replace_once(
    live,
    """            requestStates()
            maybeSilenceCarrier(force = false)

            if (!setupDelivered.get()) {
""",
    """            requestStates()
            maybeSilenceCarrier(force = false)
            if (!setupDelivered.get() && recoverStartSessionIfNeeded(current, now)) {
                main.postDelayed(this, HEALTH_TICK_MS)
                return
            }

            if (!setupDelivered.get()) {
""",
    'live-health-start-recovery-call',
)
repair_anchor = """    private fun repairLiveRouteIfNeeded(current: WebView, currentUri: Uri?, now: Long): Boolean {
"""
recover_fn = """    private fun recoverStartSessionIfNeeded(current: WebView, now: Long): Boolean {
        if (serverSetupSeen || setupDelivered.get() || startSessionRecoveryAttempts >= MAX_START_SESSION_RECOVERY_ATTEMPTS) return false
        val bootstrap = runCatching { JSONObject(lastBootstrapState) }.getOrNull() ?: return false
        val timedOut = bootstrap.optInt("startAckTimeouts", 0) > 0 && bootstrap.optString("lastAction") == "start-ack-timeout"
        val noCandidate = bootstrap.optInt("startCandidates", 0) == 0
        if (!timedOut || !noCandidate) return false
        if (lastStartSessionRecoveryAt > 0L && now - lastStartSessionRecoveryAt < START_SESSION_RECOVERY_MIN_INTERVAL_MS) return false
        startSessionRecoveryAttempts += 1
        lastStartSessionRecoveryAt = now
        configured = false
        bootstrapInstalled = false
        bootstrapRecoveryAttempts = 0
        bootstrapRecoveryInFlight = false
        lastBootstrapRecoveryAt = 0L
        lastBootstrapInstallError = ""
        lastBootstrapState = ""
        lastBootstrapSignature = ""
        lastLanguageGuardState = ""
        languageGuardConfigured = false
        serverSetupSeen = false
        markBootstrapProgress("start-session-recovery-$startSessionRecoveryAttempts")
        logger.log(1, "AiStudioLive", "START_SESSION_RECOVERY attempt=$startSessionRecoveryAttempts/$MAX_START_SESSION_RECOVERY_ATTEMPTS reason=ack-timeout-no-start-control model=${targetLiveModel()} operation=$operationMode")
        current.loadUrl(liveRouteUrl())
        return true
    }

"""
replace_once(live, repair_anchor, recover_fn + repair_anchor, 'live-start-recovery-function')
replace_once(
    live,
    "private const val MAX_ROUTE_REPAIR_ATTEMPTS = 2\n",
    "private const val MAX_ROUTE_REPAIR_ATTEMPTS = 2\n        private const val MAX_START_SESSION_RECOVERY_ATTEMPTS = 2\n        private const val START_SESSION_RECOVERY_MIN_INTERVAL_MS = 10_000L\n",
    'live-start-recovery-constants',
)

# Tests: lock in the new readiness and Start behavior.
test_r11 = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"
replace_once(test_r11, '2026-09-04-web-session-r12.2-native-submit-persistent-debug', '2026-09-04-web-session-r12.3-upload-ready-native-submit', 'test-r11-executor-version')
replace_once(
    test_r11,
    '        assertTrue(src.contains("R12_NATIVE_SUBMIT_ACK"))\n',
    '        assertTrue(src.contains("R12_NATIVE_SUBMIT_ACK"))\n        assertTrue(src.contains("R18_ATTACHMENT_WAIT_UPLOAD"))\n        assertTrue(src.contains("R18_ATTACHMENT_UPLOAD_READY"))\n        val requestFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")\n        val submitFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt")\n        assertTrue(requestFix.contains("uploadSettled"))\n        assertTrue(requestFix.contains("submitReady"))\n        assertTrue(requestFix.contains("ready:ready"))\n        assertTrue(submitFix.contains("submissionReadinessIfAttachment"))\n',
    'test-r11-upload-readiness',
)

test_r12 = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12R1SourceTest.kt"
replace_once(test_r12, '2026-09-04-web-session-r12.2-native-submit-persistent-debug', '2026-09-04-web-session-r12.3-upload-ready-native-submit', 'test-r12-version')
replace_once(
    test_r12,
    '        assertTrue(executor.contains("NATIVE_SUBMIT_MAX_RETRIES = 3"))\n',
    '        assertTrue(executor.contains("NATIVE_SUBMIT_MAX_RETRIES = 3"))\n        assertTrue(executor.contains("ATTACHMENT_TIMEOUT_MS = 300_000L"))\n        assertTrue(executor.contains("ATTACHMENT_READY_STABLE_SCANS = 3"))\n',
    'test-r12-attachment-gate',
)

test_r18 = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18SourceTest.kt"
replace_once(test_r18, 'r17.7-start-ack-retry', 'r17.8-progress-aware-start-ack', 'test-r18-r17-version')
replace_once(
    test_r18,
    '        assertTrue(bootstrap.contains("waiting-start-ack"))\n',
    '        assertTrue(bootstrap.contains("waiting-start-ack"))\n        assertTrue(bootstrap.contains("startProgressEvidence"))\n        assertTrue(bootstrap.contains("progress.progress?30000:10000"))\n',
    'test-r18-progress-aware-ack',
)
replace_once(
    test_r18,
    '        assertTrue(realtime.contains("MAX_BOOTSTRAP_RECOVERY_ATTEMPTS = 5"))\n',
    '        assertTrue(realtime.contains("MAX_BOOTSTRAP_RECOVERY_ATTEMPTS = 5"))\n        assertTrue(realtime.contains("START_SESSION_RECOVERY"))\n        assertTrue(realtime.contains("MAX_START_SESSION_RECOVERY_ATTEMPTS = 2"))\n',
    'test-r18-start-recovery',
)

print('R18.8 patch complete')
