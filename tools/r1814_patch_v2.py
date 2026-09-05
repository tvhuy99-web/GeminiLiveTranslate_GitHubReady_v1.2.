from pathlib import Path

base_path = Path('tools/r1814_patch.py')
script = base_path.read_text()
start_marker = "# 4) File-only target now uses the same real hit-test as video instead of blindly tapping center.\n"
end_marker = "# 5) Executor explicitly logs automatic file-only policy. Keep programmatic fallback disabled to avoid broad wrong-target clicks.\n"
start = script.index(start_marker)
end = script.index(end_marker, start)
replacement = r'''# 4) File-only target now uses the same real hit-test as video instead of blindly tapping center.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt'
replace(p,
''' + "'''" + r'''    const val VERSION = "2026-09-05-web-session-r11.9-cached-hit-test-submit"
''' + "'''" + r''',
''' + "'''" + r'''    const val VERSION = "2026-09-05-web-session-r11.10-file-only-hit-test"
''' + "'''" + r''')
src = Path(p).read_text()
func = src.index("          function nativeTargetIfAttachmentFileOnly(){")
best = src.index("            const best=list[0];", func)
try_start = src.index("            try{", best)
catch_start = src.index("            }catch(err){return {ok:false,error:'SUBMIT_GEOMETRY_ERROR'", try_start)
catch_end = src.index("\n", catch_start) + 1
new_block = ''' + "'''" + r'''            try{
              const point=safeNativePoint(best.button);
              emit('R24_FILE_ONLY_NATIVE_HIT_TEST',{ok:!!point.ok,error:String(point.error||''),score:Number(best.score||-1),label:String(best.label||'').slice(0,180),point:point.ok?{x:point.x,y:point.y,sample:point.sample,hit:point.hit}:null,cover:point.cover||null,rect:point.rect||null});
              if(!point.ok)return {ok:false,error:String(point.error||'FILE_ONLY_HIT_TEST_FAILED'),baselineCaptureCount:baseline,score:best.score,cover:point.cover||null,rect:point.rect||null};
              return {ok:true,native:true,fileOnly:true,hitTest:true,xRatio:point.xRatio,yRatio:point.yRatio,baselineCaptureCount:baseline,score:best.score,label:best.label.slice(0,180),fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};
            }catch(err){return {ok:false,error:'FILE_ONLY_TARGET_ERROR',detail:String(err).slice(0,500),baselineCaptureCount:baseline};}
''' + "'''" + r'''
Path(p).write_text(src[:try_start] + new_block + src[catch_end:])

'''
patched = script[:start] + replacement + script[end:]
exec(compile(patched, str(base_path), 'exec'), {'__name__': '__main__'})
