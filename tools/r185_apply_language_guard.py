from pathlib import Path

path = Path('app/src/main/java/com/oai/geminilivetranslate/network/AiStudioWebRealtimeClient.kt')
s = path.read_text()

def rep(old, new, name):
    global s
    if old not in s:
        raise SystemExit(f'anchor not found: {name}')
    s = s.replace(old, new, 1)

rep(
'''import com.oai.geminilivetranslate.ui.AiStudioWebSessionR17ProductionBootstrap\n''',
'''import com.oai.geminilivetranslate.ui.AiStudioWebSessionR17ProductionBootstrap\nimport com.oai.geminilivetranslate.ui.AiStudioWebSessionR18LanguageGuard\n''',
'import language guard',
)

rep(
''' * executor. Only the three proven Live engines are installed in this WebView: R14 input carrier,\n * R16 output bridge and R17 production model/language/Start automation.\n''',
''' * executor. Production now installs R14 input carrier, R16 output bridge, R18 request-level target\n * language guard and R17 route/model/Start automation. R18 is required for Translate because current\n * AI Studio Live can encode the target language positionally instead of exposing translationConfig.\n''',
'doc engines',
)

rep(
'''    @Volatile private var lastOutputState = ""\n    @Volatile private var lastCarrierRequests = 0L\n''',
'''    @Volatile private var lastOutputState = ""\n    @Volatile private var lastLanguageGuardState = ""\n    @Volatile private var languageGuardConfigured = false\n    @Volatile private var lastCarrierRequests = 0L\n''',
'state vars',
)

rep(
'''            "AiStudioLive",\n            "CONNECT hidden=false debugVisible=true isolatedLiveHost=true operation=$operationMode model=${targetLiveModel()} target=$targetLanguage bootstrap=${AiStudioWebSessionR17ProductionBootstrap.VERSION}",\n''',
'''            "AiStudioLive",\n            "CONNECT hidden=false debugVisible=true isolatedLiveHost=true operation=$operationMode model=${targetLiveModel()} target=$targetLanguage bootstrap=${AiStudioWebSessionR17ProductionBootstrap.VERSION} languageGuard=${AiStudioWebSessionR18LanguageGuard.VERSION}",\n''',
'connect log',
)

rep(
'''        WebViewCompat.addDocumentStartJavaScript(\n            created,\n            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,\n            setOf(AI_STUDIO_ORIGIN),\n        )\n        WebViewCompat.addDocumentStartJavaScript(\n            created,\n            AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START,\n''',
'''        WebViewCompat.addDocumentStartJavaScript(\n            created,\n            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,\n            setOf(AI_STUDIO_ORIGIN),\n        )\n        WebViewCompat.addDocumentStartJavaScript(\n            created,\n            AiStudioWebSessionR18LanguageGuard.DOCUMENT_START,\n            setOf(AI_STUDIO_ORIGIN),\n        )\n        WebViewCompat.addDocumentStartJavaScript(\n            created,\n            AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START,\n''',
'inject r18 language',
)

rep(
'''                lastDirectState = ""\n                lastOutputState = ""\n                logger.log(3, "AiStudioLive", "PAGE_STARTED generation=$pageGeneration host=${hostOf(url)} path=${pathOf(url)}")\n''',
'''                lastDirectState = ""\n                lastOutputState = ""\n                lastLanguageGuardState = ""\n                languageGuardConfigured = false\n                logger.log(3, "AiStudioLive", "PAGE_STARTED generation=$pageGeneration host=${hostOf(url)} path=${pathOf(url)}")\n''',
'page reset language',
)

rep(
'''            ${AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START}\n            var r=window.__AIS_R17_PRODUCTION__;\n            return JSON.stringify({ok:!!(r&&r.version),version:r&&String(r.version)||'',type:typeof r});\n''',
'''            ${AiStudioWebSessionR18LanguageGuard.DOCUMENT_START}\n            ${AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START}\n            var r=window.__AIS_R17_PRODUCTION__;\n            var l=window.__AIS_R183_LANGUAGE__;\n            return JSON.stringify({ok:!!(r&&r.version),version:r&&String(r.version)||'',type:typeof r,languageGuard:!!(l&&l.version),languageVersion:l&&String(l.version)||''});\n''',
'recovery language',
)

rep(
'''        lastBootstrapState = ""\n        lastBootstrapSignature = ""\n        markBootstrapProgress("route-repair-$routeRepairAttempts")\n''',
'''        lastBootstrapState = ""\n        lastBootstrapSignature = ""\n        lastLanguageGuardState = ""\n        languageGuardConfigured = false\n        markBootstrapProgress("route-repair-$routeRepairAttempts")\n''',
'route reset language',
)

old_config = '''    private fun configureBootstrapIfNeeded() {\n        if (configured || !bootstrapInstalled || closed.get()) return\n        val current = webView ?: return\n        val language = JSONObject.quote(targetLanguage)\n        val transcribe = operationMode == GeminiLiveClient.OperationMode.TRANSCRIBE\n        current.evaluateJavascript(\n            "JSON.stringify(window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.configure($language,${if (transcribe) \"true\" else \"false\"}):({ok:false,error:'r17-not-installed'}))",\n        ) { raw ->\n            val decoded = decodeEvalValue(raw)\n            val obj = runCatching { JSONObject(decoded) }.getOrNull()\n            if (obj?.optBoolean("ok") == true) {\n                configured = true\n                lastBootstrapState = decoded\n                updateBootstrapProgress(obj)\n                logger.log(2, "AiStudioBootstrap", "CONFIGURED target=$targetLanguage transcribe=$transcribe model=${targetLiveModel()}")\n            } else if (decoded.isNotBlank()) {\n                logger.log(2, "AiStudioBootstrap", "CONFIG_PENDING ${safe(decoded, 900)}")\n            }\n        }\n    }\n'''
new_config = '''    private fun configureBootstrapIfNeeded() {\n        if (configured || !bootstrapInstalled || closed.get()) return\n        val current = webView ?: return\n        val language = JSONObject.quote(targetLanguage)\n        val transcribe = operationMode == GeminiLiveClient.OperationMode.TRANSCRIBE\n        val transcribeJs = if (transcribe) "true" else "false"\n        val languageCall = if (transcribe) {\n            "null"\n        } else {\n            "(window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.configure($language):({ok:false,error:'r183-language-not-installed'}))"\n        }\n        current.evaluateJavascript(\n            "JSON.stringify({bootstrap:(window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.configure($language,$transcribeJs):({ok:false,error:'r17-not-installed'})),language:$languageCall})",\n        ) { raw ->\n            val decoded = decodeEvalValue(raw)\n            val root = runCatching { JSONObject(decoded) }.getOrNull()\n            val bootstrap = root?.optJSONObject("bootstrap")\n            val languageGuard = root?.optJSONObject("language")\n            val bootstrapOk = bootstrap?.optBoolean("ok") == true\n            val languageOk = transcribe || languageGuard?.optBoolean("ok") == true\n            if (bootstrapOk && languageOk) {\n                configured = true\n                languageGuardConfigured = !transcribe && languageOk\n                lastBootstrapState = bootstrap.toString()\n                if (!transcribe && languageGuard != null) lastLanguageGuardState = languageGuard.toString()\n                updateBootstrapProgress(bootstrap)\n                logger.log(2, "AiStudioBootstrap", "CONFIGURED target=$targetLanguage transcribe=$transcribe model=${targetLiveModel()} languageGuardConfigured=$languageGuardConfigured")\n            } else if (decoded.isNotBlank()) {\n                logger.log(2, "AiStudioBootstrap", "CONFIG_PENDING bootstrapOk=$bootstrapOk languageOk=$languageOk ${safe(decoded, 1200)}")\n            }\n        }\n    }\n'''
rep(old_config, new_config, 'configure function')

rep(
'''        val js = "JSON.stringify({bootstrap:window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.describe():null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null})"\n''',
'''        val js = "JSON.stringify({bootstrap:window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.describe():null,language:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null})"\n''',
'request state js',
)

rep(
'''            root.optJSONObject("direct")?.let { direct ->\n''',
'''            root.optJSONObject("language")?.let { language ->\n                lastLanguageGuardState = language.toString()\n                if (language.optBoolean("targetLanguageVerified", false)) {\n                    markBootstrapProgress("language-verified-${language.optString("lastStrategy", "unknown")}")\n                }\n            }\n            root.optJSONObject("direct")?.let { direct ->\n''',
'parse language state',
)

rep(
'''        val direct = runCatching { JSONObject(lastDirectState) }.getOrNull() ?: return\n        val template = direct.optBoolean("templateObserved", false)\n''',
'''        if (operationMode == GeminiLiveClient.OperationMode.TRANSLATE) {\n            val language = runCatching { JSONObject(lastLanguageGuardState) }.getOrNull() ?: return\n            if (!languageGuardConfigured || !language.optBoolean("targetLanguageVerified", false)) {\n                logger.log(2, "AiStudioLanguage", "WAITING_TARGET_LANGUAGE target=$targetLanguage configured=$languageGuardConfigured verified=${language.optBoolean("targetLanguageVerified", false)} strategy=${safe(language.optString("lastStrategy", "none"), 120)} bidiRequests=${language.optLong("bidiRequests", 0L)} setupRequests=${language.optLong("setupRequests", 0L)} translateSetup=${language.optLong("translateSetupRequests", 0L)} fallbackCandidates=${language.optInt("lastFallbackCandidates", 0)}")\n                return\n            }\n        }\n        val direct = runCatching { JSONObject(lastDirectState) }.getOrNull() ?: return\n        val template = direct.optBoolean("templateObserved", false)\n''',
'maybe deliver language gate',
)

rep(
'''            "FAIL hidden=true isolatedLiveHost=true setup=${setupDelivered.get()} operation=$operationMode model=${targetLiveModel()} target=$targetLanguage routeRepairs=$routeRepairAttempts bootstrapInstalled=$bootstrapInstalled configured=$configured bootstrapRecoveries=$bootstrapRecoveryAttempts lastBootstrapInstallError=${safe(lastBootstrapInstallError, 600)} bootstrap=${safe(lastBootstrapState, 2400)} direct=${safe(lastDirectState, 1800)} output=${safe(lastOutputState, 1800)}",\n''',
'''            "FAIL hidden=true isolatedLiveHost=true setup=${setupDelivered.get()} operation=$operationMode model=${targetLiveModel()} target=$targetLanguage routeRepairs=$routeRepairAttempts bootstrapInstalled=$bootstrapInstalled configured=$configured languageGuardConfigured=$languageGuardConfigured bootstrapRecoveries=$bootstrapRecoveryAttempts lastBootstrapInstallError=${safe(lastBootstrapInstallError, 600)} bootstrap=${safe(lastBootstrapState, 2400)} language=${safe(lastLanguageGuardState, 2200)} direct=${safe(lastDirectState, 1800)} output=${safe(lastOutputState, 1800)}",\n''',
'fail language state',
)

rep(
'''                kind.startsWith("R17_") -> logger.log(3, "AiStudioBootstrap", "JS_$kind ${safe(text, 2800)}")\n                kind == "R14_AUDIO_TEMPLATE_CAPTURED" ||\n''',
'''                kind.startsWith("R17_") -> logger.log(3, "AiStudioBootstrap", "JS_$kind ${safe(text, 2800)}")\n                kind.startsWith("R183_") -> logger.log(if (kind.contains("ERROR")) 1 else 2, "AiStudioLanguage", "JS_$kind ${safe(text, 2800)}")\n                kind == "R14_AUDIO_TEMPLATE_CAPTURED" ||\n''',
'diagnostic language',
)

rep(
'''        const val VERSION = "2026-09-04-production-ai-studio-live-r3-visible-native-tap-debug"\n''',
'''        const val VERSION = "2026-09-04-production-ai-studio-live-r4-native-tap-language-guard-debug"\n''',
'version',
)

path.write_text(s)
print('R18.5 production language guard patch applied')
