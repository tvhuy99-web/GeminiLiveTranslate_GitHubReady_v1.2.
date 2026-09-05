from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt"
TEST = ROOT / "app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


src = SRC.read_text()
old_version = "2026-09-05-web-session-r11.12-transcribe-no-thinking"
new_version = "2026-09-05-web-session-r11.13-transcribe-no-tools"
src = replace_once(src, old_version, new_version, "version")

src = replace_once(
    src,
    "const out={parsed:false,topType:'',topLength:-1,numericVectors:[],literalStrings:[],opaqueStrings:[]};",
    "const out={parsed:false,topType:'',topLength:-1,toolSlot:{kind:'unknown',count:-1,entries:[]},numericVectors:[],literalStrings:[],opaqueStrings:[]};",
    "summary init",
)

old_root = "const root=JSON.parse(body);out.parsed=true;out.topType=Array.isArray(root)?'array':typeof root;out.topLength=Array.isArray(root)?root.length:-1;"
new_root = """const root=JSON.parse(body);out.parsed=true;out.topType=Array.isArray(root)?'array':typeof root;out.topLength=Array.isArray(root)?root.length:-1;
              if(Array.isArray(root)){
                const tools=root.length>2?root[2]:null;
                out.toolSlot.kind=Array.isArray(tools)?'array':(tools===null?'null':typeof tools);
                out.toolSlot.count=Array.isArray(tools)?tools.length:-1;
                if(Array.isArray(tools)){
                  out.toolSlot.entries=tools.slice(0,16).map(function(entry){
                    if(!Array.isArray(entry))return {kind:entry===null?'null':typeof entry};
                    const nums=[];
                    for(let j=0;j<entry.length&&j<24;j++)if(typeof entry[j]==='number'&&Number.isFinite(entry[j]))nums.push({i:j,v:entry[j]});
                    return {kind:'array',length:entry.length,numbers:nums.slice(0,12)};
                  });
                }
              }"""
src = replace_once(src, old_root, new_root, "tool slot summary")

marker = "\n          function rewriteBody(url, body, source) {"
if marker not in src:
    raise SystemExit("rewriteBody marker not found")
strip_tools = r'''

          function stripUnsupportedTranscribeTools(body, source) {
            if (typeof body !== 'string' || normalizeModel(fix.selectedModel) !== 'gemini-3.5-transcribe') return body;
            try {
              const root = JSON.parse(body);
              const model = Array.isArray(root) ? normalizeModel(root[0]) : '';
              if (model !== 'gemini-3.5-transcribe') return body;
              if (!Array.isArray(root) || root.length < 4 || !Array.isArray(root[1]) || !Array.isArray(root[3])) {
                emit('R26_TRANSCRIBE_TOOLS_GUARD_NOOP',{source:String(source||''),model:model,reason:'RPC_SHAPE_MISMATCH',topLength:Array.isArray(root)?root.length:-1});
                return body;
              }
              const tools = root[2];
              if (!Array.isArray(tools)) {
                emit('R26_TRANSCRIBE_TOOLS_GUARD_NOOP',{source:String(source||''),model:model,reason:'TOOL_SLOT_NOT_ARRAY',toolKind:tools===null?'null':typeof tools});
                return body;
              }
              if (tools.length === 0) {
                emit('R26_TRANSCRIBE_TOOLS_GUARD_NOOP',{source:String(source||''),model:model,reason:'NO_TOOLS',toolCount:0});
                return body;
              }
              const signatures = tools.slice(0,16).map(function(entry){
                if(!Array.isArray(entry))return {kind:entry===null?'null':typeof entry};
                const nums=[];
                for(let i=0;i<entry.length&&i<24;i++)if(typeof entry[i]==='number'&&Number.isFinite(entry[i]))nums.push({i:i,v:entry[i]});
                return {kind:'array',length:entry.length,numbers:nums.slice(0,12)};
              });
              const previousCount = tools.length;
              root[2] = [];
              const rewritten = JSON.stringify(root);
              emit('R26_TRANSCRIBE_TOOLS_STRIPPED',{
                source:String(source||''),model:model,path:'$[2]',previousCount:previousCount,signatures:signatures,
                bodyCharsBefore:body.length,bodyCharsAfter:rewritten.length
              });
              return rewritten;
            } catch (err) {
              emit('R26_TRANSCRIBE_TOOLS_GUARD_ERROR',{source:String(source||''),error:String(err).slice(0,500)});
              return body;
            }
          }
'''
src = src.replace(marker, strip_tools + marker, 1)

src = replace_once(
    src,
    "rewritten = stripUnsupportedTranscribeThinking(rewritten, source);\n            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');",
    "rewritten = stripUnsupportedTranscribeThinking(rewritten, source);\n            rewritten = stripUnsupportedTranscribeTools(rewritten, source);\n            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');",
    "rewrite pipeline",
)

# Source-lock assertions.
required = [
    new_version,
    "stripUnsupportedTranscribeTools",
    "R26_TRANSCRIBE_TOOLS_STRIPPED",
    "R26_TRANSCRIBE_TOOLS_GUARD_NOOP",
    "root[2] = []",
    "path:'$[2]'",
    "toolSlot:{kind:'unknown',count:-1,entries:[]}",
    "rewritten = stripUnsupportedTranscribeThinking(rewritten, source);",
    "rewritten = stripUnsupportedTranscribeTools(rewritten, source);",
]
for needle in required:
    if needle not in src:
        raise SystemExit(f"missing source invariant: {needle}")
if src.index("stripUnsupportedTranscribeThinking(rewritten, source)") > src.index("stripUnsupportedTranscribeTools(rewritten, source)"):
    raise SystemExit("thinking guard must run before tool guard")
SRC.write_text(src)

test = TEST.read_text()
test = replace_once(test, old_version, new_version, "test version")
test = replace_once(
    test,
    'assertTrue(requestFix.contains("generation[16] = null"))',
    'assertTrue(requestFix.contains("generation[16] = null"))\n        assertTrue(requestFix.contains("stripUnsupportedTranscribeTools"))\n        assertTrue(requestFix.contains("R26_TRANSCRIBE_TOOLS_STRIPPED"))\n        assertTrue(requestFix.contains("R26_TRANSCRIBE_TOOLS_GUARD_NOOP"))\n        assertTrue(requestFix.contains("root[2] = []"))\n        assertTrue(requestFix.contains("toolSlot:{kind:\'unknown\',count:-1,entries:[]}"))',
    "test tool guard assertions",
)
TEST.write_text(test)

print("R18.16 patch invariants OK")
