from pathlib import Path

REQ = Path('app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11RequestFix.kt')
TEST = Path('app/src/test/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetSourceTest.kt')

s = REQ.read_text()
old_version = '2026-09-05-web-session-r11.13-transcribe-no-tools'
new_version = '2026-09-05-web-session-r11.14-transcribe-no-search-envelope'
assert old_version in s, 'old version not found'
s = s.replace(old_version, new_version, 1)

old_out = "const out={parsed:false,topType:'',topLength:-1,toolSlot:{kind:'unknown',count:-1,entries:[]},numericVectors:[],literalStrings:[],opaqueStrings:[]};"
new_out = "const out={parsed:false,topType:'',topLength:-1,toolSlot:{kind:'unknown',count:-1,entries:[]},searchEnvelopeSlot:{kind:'unknown',count:-1,shape:null},numericVectors:[],literalStrings:[],opaqueStrings:[]};"
assert old_out in s, 'summary out anchor not found'
s = s.replace(old_out, new_out, 1)

old_tools_end = """                if(Array.isArray(tools)){
                  out.toolSlot.entries=tools.slice(0,16).map(function(entry){
                    if(!Array.isArray(entry))return {kind:entry===null?'null':typeof entry};
                    const nums=[];
                    for(let j=0;j<entry.length&&j<24;j++)if(typeof entry[j]==='number'&&Number.isFinite(entry[j]))nums.push({i:j,v:entry[j]});
                    return {kind:'array',length:entry.length,numbers:nums.slice(0,12)};
                  });
                }
              }
"""
new_tools_end = """                if(Array.isArray(tools)){
                  out.toolSlot.entries=tools.slice(0,16).map(function(entry){
                    if(!Array.isArray(entry))return {kind:entry===null?'null':typeof entry};
                    const nums=[];
                    for(let j=0;j<entry.length&&j<24;j++)if(typeof entry[j]==='number'&&Number.isFinite(entry[j]))nums.push({i:j,v:entry[j]});
                    return {kind:'array',length:entry.length,numbers:nums.slice(0,12)};
                  });
                }
                const searchEnvelope=root.length>6?root[6]:null;
                out.searchEnvelopeSlot.kind=Array.isArray(searchEnvelope)?'array':(searchEnvelope===null?'null':typeof searchEnvelope);
                out.searchEnvelopeSlot.count=Array.isArray(searchEnvelope)?searchEnvelope.length:-1;
                const shapeOnly=function(v,depth){
                  if(depth>7)return '<depth>';
                  if(Array.isArray(v))return v.slice(0,16).map(function(x){return shapeOnly(x,depth+1);});
                  if(v===null)return null;
                  return '<'+typeof v+'>';
                };
                out.searchEnvelopeSlot.shape=shapeOnly(searchEnvelope,0);
              }
"""
assert old_tools_end in s, 'tool summary block anchor not found'
s = s.replace(old_tools_end, new_tools_end, 1)

insert_anchor = "          function rewriteBody(url, body, source) {\n"
assert insert_anchor in s, 'rewriteBody anchor not found'
new_fn = r'''          function stripUnsupportedTranscribeSearchEnvelope(body, source) {
            try {
              if (typeof body !== 'string') return body;
              const root = JSON.parse(body);
              const model = Array.isArray(root) ? normalizeModel(root[0]) : '';
              if (model !== 'gemini-3.5-transcribe') return body;
              if (!Array.isArray(root) || root.length < 7) {
                emit('R27_TRANSCRIBE_SEARCH_ENVELOPE_NOOP',{source:String(source||''),model:model,reason:'RPC_SHAPE_MISMATCH',topLength:Array.isArray(root)?root.length:-1});
                return body;
              }
              const slot = root[6];
              const matchesKnownSearchEnvelope = Array.isArray(slot) && slot.length === 1 &&
                Array.isArray(slot[0]) && slot[0].length === 4 &&
                slot[0][0] === null && slot[0][1] === null && slot[0][2] === null &&
                Array.isArray(slot[0][3]) && slot[0][3].length === 2 &&
                slot[0][3][0] === null && Array.isArray(slot[0][3][1]) &&
                slot[0][3][1].length === 1 && Array.isArray(slot[0][3][1][0]) &&
                slot[0][3][1][0].length === 0;
              if (!matchesKnownSearchEnvelope) {
                emit('R27_TRANSCRIBE_SEARCH_ENVELOPE_NOOP',{
                  source:String(source||''),model:model,reason:'FINGERPRINT_MISMATCH',
                  slotKind:Array.isArray(slot)?'array':(slot===null?'null':typeof slot),
                  slotCount:Array.isArray(slot)?slot.length:-1
                });
                return body;
              }
              root[6] = [];
              const rewritten = JSON.stringify(root);
              emit('R27_TRANSCRIBE_SEARCH_ENVELOPE_STRIPPED',{
                source:String(source||''),model:model,path:'$[6]',fingerprint:'[[null,null,null,[null,[[]]]]]',
                bodyCharsBefore:body.length,bodyCharsAfter:rewritten.length
              });
              return rewritten;
            } catch (err) {
              emit('R27_TRANSCRIBE_SEARCH_ENVELOPE_ERROR',{source:String(source||''),error:String(err).slice(0,500)});
              return body;
            }
          }

'''
s = s.replace(insert_anchor, new_fn + insert_anchor, 1)

old_chain = """            rewritten = stripUnsupportedTranscribeThinking(rewritten, source);
            rewritten = stripUnsupportedTranscribeTools(rewritten, source);
            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');
"""
new_chain = """            rewritten = stripUnsupportedTranscribeThinking(rewritten, source);
            rewritten = stripUnsupportedTranscribeTools(rewritten, source);
            rewritten = stripUnsupportedTranscribeSearchEnvelope(rewritten, source);
            emitGenerateRequestShape(source,url,rewritten,'post-rewrite');
"""
assert old_chain in s, 'rewrite chain anchor not found'
s = s.replace(old_chain, new_chain, 1)

REQ.write_text(s)

t = TEST.read_text()
assert old_version in t, 'test old version not found'
t = t.replace(old_version, new_version, 1)
anchor = '        assertTrue(requestFix.contains("root[2] = []"))\n'
assert anchor in t, 'test root2 anchor not found'
t = t.replace(anchor, anchor +
'''        assertTrue(requestFix.contains("stripUnsupportedTranscribeSearchEnvelope"))
        assertTrue(requestFix.contains("R27_TRANSCRIBE_SEARCH_ENVELOPE_STRIPPED"))
        assertTrue(requestFix.contains("R27_TRANSCRIBE_SEARCH_ENVELOPE_NOOP"))
        assertTrue(requestFix.contains("root[6] = []"))
        assertTrue(requestFix.contains("searchEnvelopeSlot:{kind:'unknown',count:-1,shape:null}"))
''', 1)
TEST.write_text(t)

# Patch-level invariants.
r = REQ.read_text()
checks = [
    new_version,
    'stripUnsupportedTranscribeSearchEnvelope',
    'R27_TRANSCRIBE_SEARCH_ENVELOPE_STRIPPED',
    "fingerprint:'[[null,null,null,[null,[[]]]]]'",
    'root[6] = []',
    "searchEnvelopeSlot:{kind:'unknown',count:-1,shape:null}",
    'stripUnsupportedTranscribeThinking(rewritten, source)',
    'stripUnsupportedTranscribeTools(rewritten, source)',
]
for x in checks:
    assert x in r, f'missing invariant: {x}'
print('R18.17 patch invariants OK')
