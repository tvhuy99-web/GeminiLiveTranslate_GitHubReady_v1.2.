from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(path, old, new):
    p = ROOT / path
    s = p.read_text()
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{path}: expected 1 match, got {n} for {old[:80]!r}")
    p.write_text(s.replace(old, new, 1))

# 1) Strip stale AI Studio Thinking config only for gemini-3.5-transcribe.
path = "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"
replace_once(path,
    'const val VERSION = "2026-09-05-web-session-r11.11-http-rpc-diagnostic"',
    'const val VERSION = "2026-09-05-web-session-r11.12-transcribe-no-thinking"')
insert_anchor = '''          function rewriteBody(url, body, source) {\n            if (typeof body !== 'string' || !isGenerateUrl(url)) return body;'''
insert_block = '''          function stripUnsupportedTranscribeThinking(body, source) {\n            if (typeof body !== 'string' || normalizeModel(fix.selectedModel) !== 'gemini-3.5-transcribe') return body;\n            try {\n              const root = JSON.parse(body);\n              const model = Array.isArray(root) ? normalizeModel(root[0]) : '';\n              if (model !== 'gemini-3.5-transcribe') return body;\n              const generation = Array.isArray(root[3]) ? root[3] : null;\n              const thinking = generation && generation.length > 16 ? generation[16] : null;\n              const observedSignature = Array.isArray(thinking) && thinking.length === 4 && thinking[0] === 1 && thinking[1] == null && thinking[2] == null && Number.isFinite(Number(thinking[3]));\n              if (!observedSignature) {\n                emit('R25_TRANSCRIBE_THINKING_GUARD_NOOP',{source:String(source||''),model:model,generationLength:generation?generation.length:-1,thinkingKind:Array.isArray(thinking)?'array':typeof thinking});\n                return body;\n              }\n              const previousLevel = Number(thinking[3]);\n              generation[16] = null;\n              const rewritten = JSON.stringify(root);\n              emit('R25_TRANSCRIBE_THINKING_STRIPPED',{source:String(source||''),model:model,path:'$[3][16]',previousLevel:previousLevel,bodyCharsBefore:body.length,bodyCharsAfter:rewritten.length});\n              return rewritten;\n            } catch (err) {\n              emit('R25_TRANSCRIBE_THINKING_GUARD_ERROR',{source:String(source||''),error:String(err).slice(0,500)});\n              return body;\n            }\n          }\n\n          function rewriteBody(url, body, source) {\n            if (typeof body !== 'string' || !isGenerateUrl(url)) return body;'''
replace_once(path, insert_anchor, insert_block)
replace_once(path,
'''            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');\n            return rewritten;''',
'''            rewritten = stripUnsupportedTranscribeThinking(rewritten, source);\n            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');\n            return rewritten;''')

# 2) Remove verbose debug notes from Settings.
path = "app/src/main/java/com/oai/geminilivetranslate/ui/SettingsActivity.kt"
replace_once(path,
'''        check(\n            label = "Hiển thị trang web AI Studio",\n            checked = preferences.loadAiStudioWebViewVisible(),\n            detail = "Mặc định tắt. Chỉ bật khi cần xem trực tiếp trang AI Studio để kiểm tra lỗi; khi tắt, phiên web vẫn chạy ẩn và không xuất hiện với trình đọc màn hình.",\n        ) { visible ->''',
'''        check(\n            label = "Hiển thị trang web AI Studio",\n            checked = preferences.loadAiStudioWebViewVisible(),\n        ) { visible ->''')
replace_once(path,
'''        description("Công tắc này chỉ thay đổi cách hiển thị trang gỡ lỗi, không đổi model, prompt hay cách gửi tệp.")\n\n''',
'''\n''')

# 3) Hide Gemini API key section in Google / AI Studio mode and don't validate/overwrite hidden keys.
path = "app/src/main/java/com/oai/geminilivetranslate/ui/ApiSettingsActivity.kt"
replace_once(path,
'''    private lateinit var geminiFields: LinearLayout\n    private lateinit var proxyFields: LinearLayout''',
'''    private lateinit var geminiKeyFields: LinearLayout\n    private lateinit var geminiFields: LinearLayout\n    private lateinit var proxyFields: LinearLayout''')
replace_once(path,
'''        root.addView(label("Gemini API Key"))\n        geminiKey = multiKeyEdit().apply {\n            contentDescription = "Gemini API Key. Mỗi dòng một khóa"\n        }\n        root.addView(geminiKey)''',
'''        geminiKeyFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }\n        geminiKeyFields.addView(label("Gemini API Key"))\n        geminiKey = multiKeyEdit().apply {\n            contentDescription = "Gemini API Key. Mỗi dòng một khóa"\n        }\n        geminiKeyFields.addView(geminiKey)\n        root.addView(geminiKeyFields)''')
replace_once(path,
'''        val invalidGeminiKey = geminiValues.firstOrNull {\n            it.length < 20 || it.any(Char::isWhitespace)\n        }''',
'''        val invalidGeminiKey = if (connectionMode == AiConnectionModeStore.MODE_API_KEY) {\n            geminiValues.firstOrNull { it.length < 20 || it.any(Char::isWhitespace) }\n        } else null''')
replace_once(path,
'''            keys.setGeminiKeys(geminiValues)\n            keys.setProxyKey(proxyValue)''',
'''            if (connectionMode == AiConnectionModeStore.MODE_API_KEY) {\n                keys.setGeminiKeys(geminiValues)\n            }\n            keys.setProxyKey(proxyValue)''')
replace_once(path,
'''    private fun refreshConnectionModeFields() {\n        val mode = currentConnectionMode()\n        accountButton.isVisible = mode == AiConnectionModeStore.MODE_AI_STUDIO\n    }''',
'''    private fun refreshConnectionModeFields() {\n        val mode = currentConnectionMode()\n        val aiStudio = mode == AiConnectionModeStore.MODE_AI_STUDIO\n        accountButton.isVisible = aiStudio\n        geminiKeyFields.isVisible = !aiStudio\n    }''')

# 4) Lock the transcribe Thinking guard in source tests.
path = "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"
replace_once(path,
'''        assertTrue(requestFix.contains("2026-09-05-web-session-r11.11-http-rpc-diagnostic"))''',
'''        assertTrue(requestFix.contains("2026-09-05-web-session-r11.12-transcribe-no-thinking"))\n        assertTrue(requestFix.contains("stripUnsupportedTranscribeThinking"))\n        assertTrue(requestFix.contains("R25_TRANSCRIBE_THINKING_STRIPPED"))\n        assertTrue(requestFix.contains("generation[16] = null"))''')

# Additional invariants.
request_fix = (ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt").read_text()
settings = (ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/SettingsActivity.kt").read_text()
api = (ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/ApiSettingsActivity.kt").read_text()
assert "R25_TRANSCRIBE_THINKING_STRIPPED" in request_fix
assert "model !== 'gemini-3.5-transcribe'" in request_fix
assert "Mặc định tắt. Chỉ bật khi cần xem trực tiếp trang AI Studio" not in settings
assert "Công tắc này chỉ thay đổi cách hiển thị trang gỡ lỗi" not in settings
assert "geminiKeyFields.isVisible = !aiStudio" in api
assert "connectionMode == AiConnectionModeStore.MODE_API_KEY" in api
print("R18.15 patch invariants OK")
