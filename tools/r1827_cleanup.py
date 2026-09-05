#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def path(rel: str) -> Path:
    return ROOT / rel


def text(rel: str) -> str:
    return path(rel).read_text(encoding="utf-8")


def write(rel: str, value: str) -> None:
    path(rel).write_text(value, encoding="utf-8")


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return value.replace(old, new, 1)


def remove_file(rel: str) -> None:
    p = path(rel)
    if p.exists():
        p.unlink()
        print(f"removed {rel}")


def scan_matching_brace(value: str, opening: int) -> int:
    depth = 0
    i = opening
    state = "normal"
    block_depth = 0
    while i < len(value):
        if state == "normal":
            if value.startswith('"""', i):
                state = "triple"
                i += 3
                continue
            if value.startswith("//", i):
                state = "line"
                i += 2
                continue
            if value.startswith("/*", i):
                state = "block"
                block_depth = 1
                i += 2
                continue
            ch = value[i]
            if ch == '"':
                state = "double"
            elif ch == "'":
                state = "single"
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return i
            i += 1
            continue
        if state == "double":
            if value[i] == "\\":
                i += 2
                continue
            if value[i] == '"':
                state = "normal"
            i += 1
            continue
        if state == "single":
            if value[i] == "\\":
                i += 2
                continue
            if value[i] == "'":
                state = "normal"
            i += 1
            continue
        if state == "triple":
            if value.startswith('"""', i):
                state = "normal"
                i += 3
            else:
                i += 1
            continue
        if state == "line":
            if value[i] == "\n":
                state = "normal"
            i += 1
            continue
        if state == "block":
            if value.startswith("/*", i):
                block_depth += 1
                i += 2
                continue
            if value.startswith("*/", i):
                block_depth -= 1
                i += 2
                if block_depth == 0:
                    state = "normal"
                continue
            i += 1
    raise SystemExit("unmatched brace")


def remove_kotlin_function(value: str, name: str) -> str:
    match = re.search(rf"(?m)^\s*(?:(?:private|internal|public|protected)\s+)?fun\s+{re.escape(name)}\s*\(", value)
    if not match:
        raise SystemExit(f"missing Kotlin function {name}")
    start = value.rfind("\n", 0, match.start()) + 1
    opening = value.find("{", match.end())
    if opening < 0:
        raise SystemExit(f"missing body for {name}")
    end = scan_matching_brace(value, opening) + 1
    while end < len(value) and value[end] in " \t":
        end += 1
    if end < len(value) and value[end] == "\n":
        end += 1
    while start > 0 and value[start - 1] == "\n" and start > 1 and value[start - 2] == "\n":
        start -= 1
    return value[:start] + value[end:]


def js_matching_brace(value: str, opening: int) -> int:
    depth = 0
    i = opening
    state = "normal"
    while i < len(value):
        if state == "normal":
            if value.startswith("//", i):
                state = "line"; i += 2; continue
            if value.startswith("/*", i):
                state = "block"; i += 2; continue
            ch = value[i]
            if ch == "'": state = "single"
            elif ch == '"': state = "double"
            elif ch == "`": state = "template"
            elif ch == "{": depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0: return i
            i += 1; continue
        if state in ("single", "double", "template"):
            quote = {"single":"'", "double":'"', "template":"`"}[state]
            if value[i] == "\\": i += 2; continue
            if value[i] == quote: state = "normal"
            i += 1; continue
        if state == "line":
            if value[i] == "\n": state = "normal"
            i += 1; continue
        if state == "block":
            if value.startswith("*/", i): state = "normal"; i += 2
            else: i += 1
    raise SystemExit("unmatched JavaScript brace")


def remove_js_function(value: str, name: str) -> str:
    token = f"function {name}("
    start = value.find(token)
    if start < 0:
        raise SystemExit(f"missing JavaScript function {name}")
    line_start = value.rfind("\n", 0, start) + 1
    opening = value.find("{", start + len(token))
    end = js_matching_brace(value, opening) + 1
    while end < len(value) and value[end] in " \t": end += 1
    if end < len(value) and value[end] == "\n": end += 1
    return value[:line_start] + value[end:]


def remove_when_case(value: str, label: str) -> str:
    marker = f'                "{label}" ->'
    start = value.find(marker)
    if start < 0:
        raise SystemExit(f"missing when case {label}")
    line_start = value.rfind("\n", 0, start) + 1
    pos = value.find("\n", start) + 1
    while pos > 0 and pos < len(value):
        next_end = value.find("\n", pos)
        if next_end < 0: next_end = len(value)
        line = value[pos:next_end]
        if line.startswith('                "') or line == "            }":
            return value[:line_start] + value[pos:]
        pos = next_end + 1
    raise SystemExit(f"could not close when case {label}")


def strip_kotlin_comments(value: str) -> str:
    out = []
    i = 0
    state = "normal"
    block_depth = 0
    block_buf = []
    while i < len(value):
        if state == "normal":
            if value.startswith('"""', i):
                out.append('"""'); state = "triple"; i += 3; continue
            if value.startswith("//", i):
                j = value.find("\n", i)
                if j < 0: j = len(value)
                comment = value[i:j]
                if re.search(r"copyright|license|spdx", comment, re.I): out.append(comment)
                i = j
                continue
            if value.startswith("/*", i):
                state = "block"; block_depth = 1; block_buf = ["/*"]; i += 2; continue
            ch = value[i]
            out.append(ch)
            if ch == '"': state = "double"
            elif ch == "'": state = "single"
            i += 1; continue
        if state == "double":
            ch = value[i]; out.append(ch)
            if ch == "\\" and i + 1 < len(value): out.append(value[i+1]); i += 2; continue
            if ch == '"': state = "normal"
            i += 1; continue
        if state == "single":
            ch = value[i]; out.append(ch)
            if ch == "\\" and i + 1 < len(value): out.append(value[i+1]); i += 2; continue
            if ch == "'": state = "normal"
            i += 1; continue
        if state == "triple":
            if value.startswith('"""', i):
                out.append('"""'); state = "normal"; i += 3; continue
            if (i == 0 or value[i-1] == "\n"):
                line_end = value.find("\n", i)
                if line_end < 0: line_end = len(value)
                line = value[i:line_end]
                if line.lstrip().startswith("//"):
                    i = line_end
                    continue
            out.append(value[i]); i += 1; continue
        if state == "block":
            if value.startswith("/*", i): block_depth += 1; block_buf.append("/*"); i += 2; continue
            if value.startswith("*/", i):
                block_depth -= 1; block_buf.append("*/"); i += 2
                if block_depth == 0:
                    joined = "".join(block_buf)
                    if re.search(r"copyright|license|spdx", joined, re.I): out.append(joined)
                    else: out.extend("\n" for ch in joined if ch == "\n")
                    state = "normal"
                continue
            block_buf.append(value[i]); i += 1
    cleaned = "".join(out)
    cleaned = re.sub(r"\n[ \t]+\n", "\n\n", cleaned)
    cleaned = re.sub(r"\n{4,}", "\n\n\n", cleaned)
    return cleaned


def changed_kotlin_files() -> list[Path]:
    names = subprocess.check_output(
        ["git", "diff", "--name-only", "origin/main...HEAD"], cwd=ROOT, text=True
    ).splitlines()
    result = []
    for rel in names:
        p = path(rel)
        if p.is_file() and p.suffix in {".kt", ".kts", ".java"} and (rel.startswith("app/") or rel.endswith("build.gradle.kts")):
            result.append(p)
    return result


def cleanup_executor() -> None:
    rel = "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt"
    s = text(rel)
    s = s.replace("import com.oai.geminilivetranslate.ui.AiStudioGoogleAccountBootstrap\n", "")
    s = s.replace("import com.oai.geminilivetranslate.ui.AiStudioWebSessionDirectEngine\n", "")
    s = s.replace("    private val appContext = context.applicationContext\n", "")
    s = s.replace("    private var directRecoverySeq = -1\n", "")
    s = s.replace("        directRecoverySeq = -1\n", "")
    s = replace_once(
        s,
        '''        val bootstrapUrl = if (url == null) AiStudioGoogleAccountBootstrap.consumeStartUrl(appContext) else null\n        val resolvedUrl = url ?: bootstrapUrl ?: NEW_CHAT_URL\n        val source = when {\n            url != null -> "explicit"\n            bootstrapUrl != null -> "google-account-hint"\n            else -> "new-chat"\n        }\n''',
        '''        val resolvedUrl = url ?: NEW_CHAT_URL\n        val source = if (url != null) "explicit" else "new-chat"\n''',
        "executor start legacy bootstrap",
    )
    for fn in (
        "generateAttachmentFileOnlyNative",
        "awaitManualAttachmentFileOnlyGenerate",
        "monitorManualFileOnlyGenerate",
        "awaitManualAttachmentGenerate",
        "monitorManualAttachmentReadiness",
        "generate",
        "tryDirectEngineRecovery",
        "inspectDirectEngine",
    ):
        s = remove_kotlin_function(s, fn)
    for label in ("R9_HANDLER_FINAL", "R9_HANDLER_SUCCESS", "R12_DIRECT_SUBMIT_SUCCESS", "R12_DIRECT_SUBMIT_FINAL"):
        s = remove_when_case(s, label)
    s = s.replace("            WebViewCompat.addDocumentStartJavaScript(webView, R11_BROAD_FALLBACK_GUARD, setOf(AI_STUDIO_ORIGIN))\n", "")
    s = s.replace("            WebViewCompat.addDocumentStartJavaScript(webView, AiStudioWebSessionDirectEngine.DOCUMENT_START, setOf(AI_STUDIO_ORIGIN))\n", "")
    s = s.replace("                main.postDelayed({ inspectDirectEngine(\"page-finished\") }, 1_100L)\n", "")
    s = s.replace("        private const val DEFAULT_TIMEOUT_MS = 20_000L\n", "")
    s = s.replace("        private const val FIXED_TIMEOUT_MAX_MS = 300_000L\n", "")
    s = s.replace("        private const val DIRECT_ENGINE_WATCHDOG_MS = 7_500L\n", "")
    s = s.replace("        private const val MANUAL_READINESS_POLL_MS = 1_000L\n", "")
    s = re.sub(r'^\s*private const val R11_BROAD_FALLBACK_GUARD = .*\n', '', s, flags=re.M)
    s = replace_once(
        s,
        '''    private data class Pending(\n        val seq: Int,\n        val prompt: String,\n        val marker: String,\n        val callback: (Result) -> Unit,\n        val startedAt: Long,\n        val progressAware: Boolean,\n        var firstProgressAt: Long = 0L,\n        var lastProgressAt: Long = 0L,\n        var lastResponseChars: Int = 0,\n    )\n''',
        '''    private data class Pending(\n        val seq: Int,\n        val callback: (Result) -> Unit,\n        val startedAt: Long,\n        var firstProgressAt: Long = 0L,\n        var lastProgressAt: Long = 0L,\n        var lastResponseChars: Int = 0,\n    )\n''',
        "executor pending",
    )
    s = replace_once(
        s,
        '''    private fun beginPreparedAttachmentRequest(\n        prompt: String,\n        callback: (Result) -> Unit,\n        mode: String,\n    ): Pending {\n''',
        '''    private fun beginPreparedAttachmentRequest(\n        callback: (Result) -> Unit,\n        mode: String,\n    ): Pending {\n''',
        "executor begin signature",
    )
    s = s.replace("            prompt = prompt,\n            marker = \"\",\n", "")
    s = s.replace("            progressAware = true,\n", "")
    s = s.replace('beginPreparedAttachmentRequest(prompt, callback, "attachment-native-only")', 'beginPreparedAttachmentRequest(callback, "attachment-native-only")')
    s = s.replace('beginPreparedAttachmentRequest("",callback,"stt-direct-page-file")', 'beginPreparedAttachmentRequest(callback,"stt-direct-page-file")')
    s = s.replace("if (p.seq != requestSeq || !p.progressAware) return", "if (p.seq != requestSeq) return")
    s = s.replace("        val markerSatisfied = p.marker.isBlank() || result.markerFound\n", "")
    s = s.replace("        if (result.ok && result.complete && markerSatisfied) finish(requestSeq, result)\n", "        if (result.ok && result.complete) finish(requestSeq, result)\n")
    s = s.replace('events?.onLog("R12_TERMINAL_RESULT",\n                            "seq=${p.seq} ok=${result.ok} status=${result.status} complete=${result.complete} modelChars=${result.modelText.length} markerFound=${result.markerFound} phase=${result.phase}",\n                        )', 'events?.onLog("R12_TERMINAL_RESULT", "seq=${p.seq} ok=${result.ok} status=${result.status} complete=${result.complete} modelChars=${result.modelText.length} phase=${result.phase}")')
    s = s.replace("        val markerFound: Boolean = false,\n", "")
    s = s.replace(" markerFound=${result.markerFound}", "")
    s = s.replace("            markerFound = obj.optBoolean(\"markerFound\"),\n", "")
    s = replace_once(
        s,
        '''            events?.onLog("R23_VIDEO_AUTO_SUBMIT_POLICY", "seq=${request.seq} nativeHitTest=true cachedPreparedTarget=true programmaticFallback=true")\n            tryNativeAttachmentSubmit(request.seq, "native-file-primary", 0, allowProgrammaticFallback = true)\n''',
        '''            events?.onLog("R23_VIDEO_AUTO_SUBMIT_POLICY", "seq=${request.seq} nativeHitTest=true cachedPreparedTarget=true programmaticFallback=true")\n            tryNativeAttachmentSubmit(request.seq, "native-file-primary", 0)\n''',
        "video native call",
    )
    old_sig = '''    private fun tryNativeAttachmentSubmit(\n        requestSeq: Int,\n        reason: String,\n        attempt: Int,\n        allowProgrammaticFallback: Boolean = true,\n        fileOnly: Boolean = false,\n    ) {\n'''
    new_sig = '''    private fun tryNativeAttachmentSubmit(\n        requestSeq: Int,\n        reason: String,\n        attempt: Int,\n    ) {\n'''
    s = replace_once(s, old_sig, new_sig, "native submit signature")
    s = s.replace('events?.onLog("R12_NATIVE_SUBMIT_START", "seq=$requestSeq reason=$reason attempt=${attempt + 1} fileOnly=$fileOnly")\n        val targetFunction = if (fileOnly) "nativeTargetIfAttachmentFileOnly" else "nativeTargetIfAttachment"\n        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__[${JSONObject.quote(targetFunction)}]() : ({ok:false,error:\'native-submit-target-not-installed\'}))"', 'events?.onLog("R12_NATIVE_SUBMIT_START", "seq=$requestSeq reason=$reason attempt=${attempt + 1}")\n        val expression = "JSON.stringify(window.__AIS_R11_SUBMIT_TARGET__ ? window.__AIS_R11_SUBMIT_TARGET__.nativeTargetIfAttachment() : ({ok:false,error:\'native-submit-target-not-installed\'}))"')
    s = s.replace("tryNativeAttachmentSubmit(requestSeq, \"target-rescan\", attempt + 1, allowProgrammaticFallback, fileOnly)", "tryNativeAttachmentSubmit(requestSeq, \"target-rescan\", attempt + 1)")
    s = s.replace("tryNativeAttachmentSubmit(requestSeq, \"invalid-native-target\", attempt + 1, allowProgrammaticFallback, fileOnly)", "tryNativeAttachmentSubmit(requestSeq, \"invalid-native-target\", attempt + 1)")
    s = s.replace("tryNativeAttachmentSubmit(requestSeq, \"no-capture\", attempt + 1, allowProgrammaticFallback, fileOnly)", "tryNativeAttachmentSubmit(requestSeq, \"no-capture\", attempt + 1)")
    s = s.replace('if (allowProgrammaticFallback) tryProgrammaticAttachmentFallback(requestSeq, "native-target-unavailable")\n                    else finish(requestSeq, Result(ok = false, error = "NATIVE_SUBMIT_TARGET_UNAVAILABLE"))', 'tryProgrammaticAttachmentFallback(requestSeq, "native-target-unavailable")')
    s = s.replace('else if (allowProgrammaticFallback) tryProgrammaticAttachmentFallback(requestSeq, "invalid-native-target") else finish(requestSeq, Result(ok = false, error = "NATIVE_SUBMIT_INVALID_TARGET"))', 'else tryProgrammaticAttachmentFallback(requestSeq, "invalid-native-target")')
    s = s.replace('if (allowProgrammaticFallback) tryProgrammaticAttachmentFallback(requestSeq, "native-no-capture")\n                        else finish(requestSeq, Result(ok = false, error = "NATIVE_SUBMIT_NO_CAPTURE"))', 'tryProgrammaticAttachmentFallback(requestSeq, "native-no-capture")')
    s = s.replace('.put("tag", if (fileOnly) "FILE_TRANSCRIBE_RUN" else "VIDEO_SEND")\n                    .put("role", "composer-submit")\n                    .put("purpose", if (fileOnly) "file-transcribe-run" else "video-generate")', '.put("tag", "VIDEO_SEND")\n                    .put("role", "composer-submit")\n                    .put("purpose", "video-generate")')
    s = s.replace('const val VERSION = "2026-09-05-web-session-r12.7-video-partial-stream"', 'const val VERSION = "2026-09-05-web-session-r12.8-cleanup"')
    forbidden = [
        "AiStudioGoogleAccountBootstrap", "AiStudioWebSessionDirectEngine", "directRecoverySeq",
        "generateAttachmentFileOnlyNative", "awaitManualAttachment", "monitorManualAttachment",
        "monitorManualFileOnlyGenerate", "fun generate(\n", "nativeTargetIfAttachmentFileOnly",
        "R11_BROAD_FALLBACK_GUARD", "progressAware", "markerSatisfied",
    ]
    for token in forbidden:
        if token in s:
            raise SystemExit(f"executor still contains dead token: {token}")
    write(rel, s)


def cleanup_adaptive_runtime() -> None:
    rel = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionAdaptiveRuntime.kt"
    s = '''package com.oai.geminilivetranslate.ui\n\nobject AiStudioWebSessionAdaptiveRuntime {\n    const val VERSION = "2026-09-05-web-session-discovery-r1"\n\n    val DOCUMENT_START: String = """\n        (function(){\n          'use strict';\n          if(window.__AIS_ADAPTIVE_RUNTIME__&&window.__AIS_ADAPTIVE_RUNTIME__.version==='$VERSION')return;\n          if(!window.EventTarget||!window.EventTarget.prototype)return;\n          const nativeAdd=window.EventTarget.prototype.addEventListener;\n          const nativeRemove=window.EventTarget.prototype.removeEventListener;\n          const entries=[];\n          const groups=[];\n          let nextEntryId=1,nextGroupId=1,generation=1;\n          function tracked(type){const t=String(type||'');return t==='input'||t==='change'||t==='keydown';}\n          function targetMeta(target){\n            try{\n              if(target===window)return {kind:'window',tag:'',role:'',valueCapable:false,contentEditable:false,connected:true};\n              if(target===document)return {kind:'document',tag:'',role:'',valueCapable:false,contentEditable:false,connected:true};\n              if(target&&target.nodeType===11&&target.host)return {kind:'shadow-root',tag:String(target.host.tagName||''),role:'',valueCapable:false,contentEditable:false,connected:!!target.host.isConnected};\n              return {kind:'element',tag:String(target&&target.tagName||'').slice(0,40),role:String(target&&target.getAttribute&&target.getAttribute('role')||'').slice(0,80),valueCapable:!!(target&&('value' in target)),contentEditable:!!(target&&target.isContentEditable),connected:target&&typeof target.isConnected==='boolean'?!!target.isConnected:true};\n            }catch(_){return {kind:'unknown',tag:'',role:'',valueCapable:false,contentEditable:false,connected:false};}\n          }\n          function groupFor(target){for(let i=0;i<groups.length;i++)if(groups[i].target===target)return groups[i];const g={id:nextGroupId++,target:target};groups.push(g);return g;}\n          function capture(type,target,listener,options){if(!tracked(type)||!listener||entries.length>=2400)return;const g=groupFor(target);entries.push({id:nextEntryId++,groupId:g.id,type:String(type),target:target,listener:listener,options:options,active:true,at:Date.now()});generation+=1;}\n          function captureFlag(options){try{return typeof options==='boolean'?options:!!(options&&options.capture);}catch(_){return false;}}\n          window.EventTarget.prototype.addEventListener=function(type,listener,options){try{capture(type,this,listener,options);}catch(_){}return nativeAdd.apply(this,arguments);};\n          window.EventTarget.prototype.removeEventListener=function(type,listener,options){try{if(tracked(type)&&listener){const cap=captureFlag(options);for(let i=entries.length-1;i>=0;i--){const e=entries[i];if(e.active&&e.type===String(type)&&e.target===this&&e.listener===listener&&captureFlag(e.options)===cap){e.active=false;generation+=1;break;}}}}catch(_){}return nativeRemove.apply(this,arguments);};\n          function activeFor(groupId,type){return entries.filter(function(e){return e.active&&e.groupId===groupId&&(!type||e.type===type);});}\n          function candidateScore(group){const ins=activeFor(group.id,'input'),keys=activeFor(group.id,'keydown');if(!ins.length||!keys.length)return -100000;const meta=targetMeta(group.target);let score=2200;if(meta.connected)score+=180;if(meta.valueCapable)score+=420;if(meta.contentEditable)score+=220;if(meta.role==='textbox')score+=180;if(meta.tag==='TEXTAREA')score+=160;if(meta.tag==='INPUT')score+=80;const gap=Math.abs(Number(ins[ins.length-1].at)-Number(keys[keys.length-1].at));if(gap<=25)score+=360;else if(gap<=100)score+=240;else if(gap<=500)score+=100;return score;}\n          function isReadyCandidate(item){const m=item&&item.meta||{};if(!m.connected)return false;if(!(m.valueCapable||m.contentEditable||m.role==='textbox'))return false;if(item.score<3000)return false;return activeFor(item.group.id,'input').length>0&&activeFor(item.group.id,'keydown').length>0;}\n          function candidates(){return groups.map(function(g){return {group:g,score:candidateScore(g),meta:targetMeta(g.target)};}).filter(function(x){return x.score>-50000;}).sort(function(a,b){return b.score-a.score;});}\n          window.__AIS_ADAPTIVE_RUNTIME__={\n            version:'$VERSION',\n            discover:function(){const all=candidates(),ready=all.filter(isReadyCandidate);return {ok:true,version:this.version,generation:generation,entryCount:entries.filter(function(e){return e.active;}).length,candidateCount:all.length,readyCandidateCount:ready.length,controllerReady:ready.length>0,top:all.slice(0,10).map(function(x){return {groupId:x.group.id,score:x.score,ready:isReadyCandidate(x),meta:x.meta};})};}\n          };\n        })();\n    """.trimIndent()\n}\n'''
    write(rel, s)


def cleanup_lab_scripts() -> None:
    rel = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLabScripts.kt"
    s = text(rel)
    start = s.find("          function visible(el) {")
    end = s.find("          state.getLastSafeResponse = function() {", start)
    if start < 0 or end < 0:
        raise SystemExit("lab scripts legacy UI block not found")
    s = s[:start] + s[end:]
    call_start = s.find("\n    fun call(")
    object_end = s.rfind("\n}")
    if call_start < 0 or object_end < call_start:
        raise SystemExit("lab scripts call function boundary missing")
    s = s[:call_start] + s[object_end:]
    s = s.replace("            expectedMarker: '',\n", "")
    s = s.replace("            const marker = state.expectedMarker;\n            const markerFound = !!marker && raw.indexOf(marker) >= 0;\n", "")
    s = s.replace("              marker:marker,\n              markerFound:markerFound,\n", "")
    s = s.replace("              markerFound:payload.markerFound,\n", "")
    block = '''                  if (progress.markerFound) {\n                    m.resultRecorded = true;\n                    recordResult('xhr',status,true,text,snapshot.type,m.bestContentType,'stream-marker',false);\n                  }\n'''
    s = s.replace(block, "")
    if "prepareTrustedSend" in s or "promptCandidates" in s or "fun call(" in s or "expectedMarker" in s or "markerFound" in s:
        raise SystemExit("lab scripts still contain legacy send/marker code")
    write(rel, s)


def cleanup_response_core() -> None:
    rel = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionResponseCore.kt"
    s = text(rel)
    old = '''            const marker=String(input.marker||'');\n            const modelText=extractModelText(raw);\n            const rawMarkerFound=!!input.markerFound;\n            const markerFound=rawMarkerFound || (!!marker && modelText.indexOf(marker)>=0);\n            const terminal=terminalSignal(raw);\n            const complete=!!input.ok && (markerFound || terminal || input.partial === false);\n'''
    new = '''            const modelText=extractModelText(raw);\n            const terminal=terminalSignal(raw);\n            const complete=!!input.ok && (terminal || input.partial === false);\n'''
    s = replace_once(s, old, new, "response marker normalize")
    s = s.replace("            out.rawMarkerFound=rawMarkerFound;\n            out.markerFound=markerFound;\n", "")
    s = s.replace("            const fp=[normalized.at,normalized.responseChars,normalized.phase,normalized.markerFound,normalized.modelTextChars].join('|');\n", "            const fp=[normalized.at,normalized.responseChars,normalized.phase,normalized.modelTextChars].join('|');\n")
    if "markerFound" in s or "rawMarkerFound" in s:
        raise SystemExit("response core still contains marker logic")
    write(rel, s)


def cleanup_request_fix() -> None:
    rel = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"
    s = text(rel)
    for fn in (
        "stripUnsupportedTranscribeThinking",
        "stripUnsupportedTranscribeTools",
        "stripUnsupportedTranscribeSearchEnvelope",
        "installClickTracking",
        "clickRelationScore",
        "sendButtonCandidates",
        "invokeClickListener",
        "submitAttachmentViaButton",
        "installAdaptiveFallback",
    ):
        s = remove_js_function(s, fn)
    for line in (
        "            clickTrackingInstalled: false,\n",
        "            adaptiveFallbackInstalled: false,\n",
        "            attachmentSubmitFallbacks: 0,\n",
        "            attachmentSubmitButtonClicks: 0,\n",
        "            attachmentSubmitListenerInvokes: 0,\n",
        "            attachmentLastSubmitLabel: '',\n",
        "            attachmentLastSubmitPath: '',\n",
        "          const clickEntries = [];\n",
        "          let nextClickId = 1;\n",
        "            rewritten = stripUnsupportedTranscribeThinking(rewritten, source);\n",
        "            rewritten = stripUnsupportedTranscribeTools(rewritten, source);\n",
        "            rewritten = stripUnsupportedTranscribeSearchEnvelope(rewritten, source);\n",
        "                  submitFallbacks:fix.attachmentSubmitFallbacks,buttonClicks:fix.attachmentSubmitButtonClicks,\n",
        "                  listenerInvokes:fix.attachmentSubmitListenerInvokes,lastSubmitLabel:fix.attachmentLastSubmitLabel,lastSubmitPath:fix.attachmentLastSubmitPath,\n",
        "                  clickEntryCount:clickEntries.filter(function(e){return e.active;}).length,lastNet:fix.attachmentLastNet\n",
        "              api.submitAttachmentViaButton = function(reason) { return submitAttachmentViaButton(reason||'api'); };\n",
        "                  clickTrackingInstalled:fix.clickTrackingInstalled,adaptiveFallbackInstalled:fix.adaptiveFallbackInstalled,\n",
    ):
        s = s.replace(line, "")
    s = replace_once(
        s,
        '''          function ensureInstalled() {\n            const clickOk = installClickTracking();\n            const fileChangeOk = installFileChangeObserver();\n            const fileReadOk = installFileReadObserver();\n            const deepOk = installDeepAttachmentObserver();\n            const apiOk = installApiPatch();\n            const xhrOk = installXhrRewrite();\n            const fetchOk = installFetchObserver();\n            const adaptiveOk = installAdaptiveFallback();\n            return clickOk && fileChangeOk && fileReadOk && deepOk && apiOk && xhrOk && fetchOk && adaptiveOk;\n          }\n''',
        '''          function ensureInstalled() {\n            const fileChangeOk = installFileChangeObserver();\n            const fileReadOk = installFileReadObserver();\n            const deepOk = installDeepAttachmentObserver();\n            const apiOk = installApiPatch();\n            const xhrOk = installXhrRewrite();\n            const fetchOk = installFetchObserver();\n            return fileChangeOk && fileReadOk && deepOk && apiOk && xhrOk && fetchOk;\n          }\n''',
        "request fix install set",
    )
    s = s.replace("            state:function(){return Object.assign({ok:true},fix,{activeClickEntries:clickEntries.filter(function(e){return e.active;}).length});},\n", "            state:function(){return Object.assign({ok:true},fix);},\n")
    s = s.replace("            },\n            submitAttachmentViaButton:function(reason){return submitAttachmentViaButton(reason||'direct-api');}\n", "            }\n")
    s = s.replace('const val VERSION = "2026-09-05-web-session-r11.14-transcribe-no-search-envelope"', 'const val VERSION = "2026-09-05-web-session-r11.15-video-attachment"')
    forbidden = ["stripUnsupportedTranscribe", "adaptiveFallbackInstalled", "installAdaptiveFallback", "submitAttachmentViaButton", "clickEntries", "attachmentSubmitFallbacks", "attachmentSubmitButtonClicks", "attachmentSubmitListenerInvokes"]
    for token in forbidden:
        if token in s:
            raise SystemExit(f"request fix still contains dead token: {token}")
    write(rel, s)


def cleanup_submit_target() -> None:
    rel = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt"
    s = text(rel)
    s = remove_js_function(s, "nativeTargetIfAttachmentFileOnly")
    s = s.replace("            nativeTargetIfAttachmentFileOnly:nativeTargetIfAttachmentFileOnly,\n", "")
    s = s.replace('const val VERSION = "2026-09-05-web-session-r11.10-file-only-hit-test"', 'const val VERSION = "2026-09-05-web-session-r11.11-video-submit"')
    if "nativeTargetIfAttachmentFileOnly" in s or "R24_FILE_ONLY_NATIVE_HIT_TEST" in s or "R21_FILE_ONLY_TARGET_DISCOVERY" in s:
        raise SystemExit("submit target still contains generic file-only route")
    write(rel, s)


def restore_pr_workflow() -> None:
    rel = ".github/workflows/android-pr-checks.yml"
    base = subprocess.check_output(["git", "show", f"origin/main:{rel}"], cwd=ROOT, text=True)
    needle = "          python3 tools/verify_github_ready.py\n"
    if needle not in base:
        raise SystemExit("main PR workflow verification anchor missing")
    base = base.replace(needle, needle + "          python3 tools/check_embedded_js_syntax.py\n", 1)
    base = "\n".join(line for line in base.splitlines() if not line.lstrip().startswith("#")) + "\n"
    write(rel, base)


def write_current_executor_test() -> None:
    old1 = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"
    old2 = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR12R1SourceTest.kt"
    remove_file(old1)
    remove_file(old2)
    rel = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionExecutorSourceTest.kt"
    write(rel, '''package com.oai.geminilivetranslate.ui\n\nimport java.io.File\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass AiStudioWebSessionExecutorSourceTest {\n    private fun source(path: String): String = sequenceOf(File(path), File("app/$path"))\n        .firstOrNull(File::isFile)?.readText() ?: error("Không tìm thấy source: $path")\n\n    @Test\n    fun executorContainsOnlyCurrentVideoAndDedicatedSttGenerationPaths() {\n        val executor = source("src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt")\n        assertTrue(executor.contains("2026-09-05-web-session-r12.8-cleanup"))\n        assertTrue(executor.contains("startFileTranscribe"))\n        assertTrue(executor.contains("attachSttFile"))\n        assertTrue(executor.contains("generateSttFileNative"))\n        assertTrue(executor.contains("generateAttachmentNativeOnly"))\n        assertTrue(executor.contains("R35_VIDEO_PARTIAL_RAW"))\n        assertTrue(executor.contains("nativeTapController.requestNativeTap"))\n        assertFalse(executor.contains("generateAttachmentFileOnlyNative"))\n        assertFalse(executor.contains("awaitManualAttachment"))\n        assertFalse(executor.contains("AiStudioWebSessionDirectEngine"))\n        assertFalse(executor.contains("AiStudioGoogleAccountBootstrap"))\n    }\n\n    @Test\n    fun retainedWebScriptsExposeOnlyCurrentAttachmentSubmitAndDiscoveryApis() {\n        val requestFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt")\n        val submitFix = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt")\n        val discovery = source("src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionAdaptiveRuntime.kt")\n        assertTrue(requestFix.contains("2026-09-05-web-session-r11.15-video-attachment"))\n        assertTrue(requestFix.contains("attachmentEvidence"))\n        assertTrue(requestFix.contains("R20_ATTACHMENT_PAYLOAD_START"))\n        assertFalse(requestFix.contains("stripUnsupportedTranscribe"))\n        assertFalse(requestFix.contains("installAdaptiveFallback"))\n        assertFalse(requestFix.contains("submitAttachmentViaButton"))\n        assertTrue(submitFix.contains("2026-09-05-web-session-r11.11-video-submit"))\n        assertTrue(submitFix.contains("nativeTargetIfAttachment"))\n        assertTrue(submitFix.contains("submitIfAttachment"))\n        assertFalse(submitFix.contains("nativeTargetIfAttachmentFileOnly"))\n        assertTrue(discovery.contains("discover:function"))\n        assertFalse(discovery.contains("generate:function"))\n        assertFalse(discovery.contains("cancel:function"))\n    }\n}\n''')


def delete_dead_files() -> None:
    files = [
        "AI_STUDIO_WEB_SESSION_LAB.md",
        "app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionLabLog.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioGoogleAccountBootstrap.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionDirectEngine.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionLiveProbe.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionPhysicalCarrier.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR13DeepProbe.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18CausalProbe.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18RuntimeBootstrap.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18StartOracle.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR18StartOracleProbe.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR5HandlerCapture.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR6HandlerCapture.kt",
        "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR7HandlerCapture.kt",
    ]
    for rel in files:
        remove_file(rel)


def verify_no_dead_refs() -> None:
    roots = [path("app/src/main/java")]
    corpus = "\n".join(p.read_text(encoding="utf-8") for root in roots for p in root.rglob("*.kt"))
    tokens = [
        "AiStudioWebSessionLabLog", "AiStudioGoogleAccountBootstrap", "AiStudioWebSessionDirectEngine",
        "AiStudioWebSessionLiveProbe", "AiStudioWebSessionPhysicalCarrier", "AiStudioWebSessionR13DeepProbe",
        "AiStudioWebSessionR18CausalProbe", "AiStudioWebSessionR18RuntimeBootstrap", "AiStudioWebSessionR18StartOracle",
        "AiStudioWebSessionR18StartOracleProbe", "AiStudioWebSessionR5HandlerCapture", "AiStudioWebSessionR6HandlerCapture",
        "AiStudioWebSessionR7HandlerCapture", "generateAttachmentFileOnlyNative", "awaitManualAttachmentFileOnlyGenerate",
        "awaitManualAttachmentGenerate", "nativeTargetIfAttachmentFileOnly", "stripUnsupportedTranscribeThinking",
        "stripUnsupportedTranscribeTools", "stripUnsupportedTranscribeSearchEnvelope",
    ]
    for token in tokens:
        if token in corpus:
            raise SystemExit(f"dead reference remains: {token}")


def main() -> None:
    delete_dead_files()
    cleanup_executor()
    cleanup_adaptive_runtime()
    cleanup_lab_scripts()
    cleanup_response_core()
    cleanup_request_fix()
    cleanup_submit_target()
    write_current_executor_test()
    for p in changed_kotlin_files():
        cleaned = strip_kotlin_comments(p.read_text(encoding="utf-8"))
        cleaned = "\n".join(line.rstrip() for line in cleaned.splitlines()) + ("\n" if cleaned.endswith("\n") else "")
        p.write_text(cleaned, encoding="utf-8")
    verify_no_dead_refs()
    subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
    print("R18.27 cleanup applied")


if __name__ == "__main__":
    main()
