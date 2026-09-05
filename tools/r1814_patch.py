from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text()
    n = s.count(old)
    if n != count:
        raise SystemExit(f"{path}: expected {count} matches, got {n} for {old[:120]!r}")
    p.write_text(s.replace(old, new, count))

# 1) Persisted debug toggle, default OFF.
p = 'app/src/main/java/com/oai/geminilivetranslate/core/AppPreferences.kt'
replace(p,
'''    fun setSpeakerDiarization(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAKER_DIARIZATION, enabled).apply()
    }

    fun restoreDefaultsPreservingKeys(): AppSettings = AppSettings().let(SettingsPolicy::sanitize).also(::save)
''',
'''    fun setSpeakerDiarization(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAKER_DIARIZATION, enabled).apply()
    }

    /** Debug-only AI Studio WebView presentation. Hidden by default. */
    fun loadAiStudioWebViewVisible(): Boolean = prefs.getBoolean(KEY_AI_STUDIO_WEBVIEW_VISIBLE, false)

    fun setAiStudioWebViewVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_AI_STUDIO_WEBVIEW_VISIBLE, visible).apply()
    }

    fun restoreDefaultsPreservingKeys(): AppSettings = AppSettings().let(SettingsPolicy::sanitize).also(::save)
''')
replace(p,
'''        private const val KEY_SPEAKER_DIARIZATION = "speakerDiarization"
        const val PROCESSING_MODE_TRANSLATE = "translate"
''',
'''        private const val KEY_SPEAKER_DIARIZATION = "speakerDiarization"
        private const val KEY_AI_STUDIO_WEBVIEW_VISIBLE = "aiStudioWebViewVisible"
        const val PROCESSING_MODE_TRANSLATE = "translate"
''')

# 2) Settings toggle under System. It writes immediately and also updates an active debug host.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/SettingsActivity.kt'
replace(p,
'''import com.oai.geminilivetranslate.service.TranslationService
''',
'''import com.oai.geminilivetranslate.service.TranslationService
import com.oai.geminilivetranslate.network.AiStudioDebugWebViewHost
''')
replace(p,
'''    private fun buildSystem() {
        title("Khôi phục và xóa dữ liệu")
''',
'''    private fun buildSystem() {
        title("Gỡ lỗi AI Studio")
        check(
            label = "Hiển thị trang web AI Studio",
            checked = preferences.loadAiStudioWebViewVisible(),
            detail = "Mặc định tắt. Chỉ bật khi cần xem trực tiếp trang AI Studio để kiểm tra lỗi; khi tắt, phiên web vẫn chạy ẩn và không xuất hiện với trình đọc màn hình.",
        ) { visible ->
            preferences.setAiStudioWebViewVisible(visible)
            AiStudioDebugWebViewHost.setVisibleForActive(visible, logger)
        }
        description("Công tắc này chỉ thay đổi cách hiển thị trang gỡ lỗi, không đổi model, prompt hay cách gửi tệp.")

        title("Khôi phục và xóa dữ liệu")
''')

# 3) Keep WebView attached/layouted for native automation, but move it off-screen and hide it from accessibility by default.
p = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioNativeTapDebugSupport.kt'
replace(p,
'''import com.oai.geminilivetranslate.core.SessionLogger
''',
'''import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.AppPreferences
''')
replace(p,
'''internal object AiStudioDebugWebViewHost {
    const val VERSION = "2026-09-04-r18.4-visible-live-webview-debug"
''',
'''internal object AiStudioDebugWebViewHost {
    const val VERSION = "2026-09-05-r18.14-toggle-hidden-webview-debug"
''')
replace(p,
'''        content.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
        panels[webView] = WeakReference(panel)
        logger?.log(2, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_ATTACHED visible=true height=$height screenHeight=$screenHeight accessibilityPreserved=true")
    }

    fun retain(webView: WebView, logger: SessionLogger?, reason: String) {
''',
'''        content.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
        panels[webView] = WeakReference(panel)
        val visible = AppPreferences(activity).loadAiStudioWebViewVisible()
        applyPresentation(webView, panel, visible, logger, "attach")
        logger?.log(2, "AiStudioDebugWeb", "AI_STUDIO_WEBVIEW_ATTACHED visible=$visible height=$height screenHeight=$screenHeight hiddenOffscreen=${!visible}")
    }

    fun setVisibleForActive(visible: Boolean, logger: SessionLogger?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setVisibleForActive(visible, logger) }
            return
        }
        var changed = 0
        panels.entries.toList().forEach { (webView, ref) ->
            val panel = ref.get() ?: return@forEach
            applyPresentation(webView, panel, visible, logger, "settings-toggle")
            changed += 1
        }
        logger?.log(2, "AiStudioDebugWeb", "R24_WEBVIEW_VISIBILITY_TOGGLE visible=$visible activePanels=$changed")
    }

    private fun applyPresentation(
        webView: WebView,
        panel: ViewGroup,
        visible: Boolean,
        logger: SessionLogger?,
        reason: String,
    ) {
        val screenHeight = panel.resources.displayMetrics.heightPixels
        val panelHeight = (panel.layoutParams?.height ?: panel.height).coerceAtLeast(1)
        // Keep the WebView VISIBLE and fully laid out so JS/native dispatch still works. Hidden mode
        // moves the entire debug panel outside the screen instead of using GONE/INVISIBLE.
        panel.translationY = if (visible) 0f else (screenHeight + panelHeight).toFloat()
        val accessibility = if (visible) {
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        panel.importantForAccessibility = accessibility
        webView.importantForAccessibility = accessibility
        panel.isFocusable = visible
        panel.isFocusableInTouchMode = visible
        logger?.log(2, "AiStudioDebugWeb", "R24_WEBVIEW_PRESENTATION visible=$visible hiddenOffscreen=${!visible} reason=$reason webShown=${webView.isShown} width=${webView.width} height=${webView.height}")
    }

    fun retain(webView: WebView, logger: SessionLogger?, reason: String) {
''')
replace(p,
'''        val panel = panels[webView]?.get()
        if (panel != null && panel.parent != null && webView.parent != null) {
            logger?.log(2, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_RETAINED reason=$reason visible=true")
            return
        }
''',
'''        val panel = panels[webView]?.get()
        if (panel != null && panel.parent != null && webView.parent != null) {
            val visible = AppPreferences(panel.context).loadAiStudioWebViewVisible()
            applyPresentation(webView, panel, visible, logger, "retain:$reason")
            logger?.log(2, "AiStudioDebugWeb", "AI_STUDIO_WEBVIEW_RETAINED reason=$reason visible=$visible")
            return
        }
''')

# 4) File-only target now uses the same real hit-test as video instead of blindly tapping center.
p = 'app/src/main/java/com/oai/geminilivetranslate/ui/AiStudioWebSessionR11SubmitTargetFix.kt'
replace(p,
'''    const val VERSION = "2026-09-05-web-session-r11.9-cached-hit-test-submit"
''',
'''    const val VERSION = "2026-09-05-web-session-r11.10-file-only-hit-test"
''')
replace(p,
'''            try{
              const r=best.button.getBoundingClientRect(),vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||1);
              const cx=r.left+r.width/2,cy=r.top+r.height/2;
              if(r.width<2||r.height<2||cx<0||cy<0||cx>vw||cy>vh)return {ok:false,error:'SUBMIT_OUT_OF_VIEW',baselineCaptureCount:baseline,score:best.score};
              return {ok:true,native:true,fileOnly:true,xRatio:cx/vw,yRatio:cy/vh,baselineCaptureCount:baseline,score:best.score,label:best.label.slice(0,180),fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};
            }catch(err){return {ok:false,error:'FILE_ONLY_TARGET_ERROR',detail:String(err).slice(0,500),baselineCaptureCount:baseline};}
''',
'''            try{
              const point=safeNativePoint(best.button);
              emit('R24_FILE_ONLY_NATIVE_HIT_TEST',{ok:!!point.ok,error:String(point.error||''),score:Number(best.score||-1),label:String(best.label||'').slice(0,180),point:point.ok?{x:point.x,y:point.y,sample:point.sample,hit:point.hit}:null,cover:point.cover||null,rect:point.rect||null});
              if(!point.ok)return {ok:false,error:String(point.error||'FILE_ONLY_HIT_TEST_FAILED'),baselineCaptureCount:baseline,score:best.score,cover:point.cover||null,rect:point.rect||null};
              return {ok:true,native:true,fileOnly:true,hitTest:true,xRatio:point.xRatio,yRatio:point.yRatio,baselineCaptureCount:baseline,score:best.score,label:best.label.slice(0,180),fingerprint:fingerprint(best.button,d.composerRoot,d.prompt,d.attachment)};
            }catch(err){return {ok:false,error:'FILE_ONLY_TARGET_ERROR',detail:String(err).slice(0,500),baselineCaptureCount:baseline};}
''')

# 5) Executor explicitly logs automatic file-only policy. Keep programmatic fallback disabled to avoid broad wrong-target clicks.
p = 'app/src/main/java/com/oai/geminilivetranslate/core/AiStudioWebSessionExecutor.kt'
replace(p,
'''        events?.onLog("R21_FILE_TRANSCRIBE_ARMED", "seq=${request.seq} prompt=false modelInput=file-only")
        tryNativeAttachmentSubmit(
''',
'''        events?.onLog("R21_FILE_TRANSCRIBE_ARMED", "seq=${request.seq} prompt=false modelInput=file-only")
        events?.onLog("R24_FILE_TRANSCRIBE_AUTO_SUBMIT_POLICY", "seq=${request.seq} autoSubmit=true prompt=false fileOnly=true nativeHitTest=true programmaticFallback=false")
        tryNativeAttachmentSubmit(
''')

# 6) FILE transcribe goes automatic again, still prompt-free, and retries the short post-model NOT_READY race.
p = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioFileTranscribeClient.kt'
replace(p,
'''        logger.log(2, TAG, "CONFIG model=$model prompt=false autoLanguage=true diarizationRequested=$speakerDiarization transport=aistudio-web-file-only manualRun=true")
        onProgress("Tệp đã tải/xử lý xong. Hãy tự nhấn Run trên trang AI Studio; ứng dụng chỉ theo dõi request/config.", 55)
        val result = awaitManualFileOnly(exec)
''',
'''        logger.log(2, TAG, "CONFIG model=$model prompt=false autoLanguage=true diarizationRequested=$speakerDiarization transport=aistudio-web-file-only manualRun=false autoSubmit=true")
        onProgress("Tệp đã tải/xử lý xong. Ứng dụng đang tự nhấn Run để bắt đầu chép lời...", 55)
        logger.log(2, TAG, "R24_FILE_TRANSCRIBE_AUTO_SUBMIT_START model=$model prompt=false fileOnly=true")
        val result = generateFileOnly(exec)
''')
replace(p,
'''                    val level = if (name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3
''',
'''                    val level = if (name.startsWith("R24_") || name.startsWith("JS_R24_") || name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R18_ATTACHMENT") || name.startsWith("R19_")) 2 else if (name.contains("ERROR") || name.contains("TIMEOUT")) 1 else 3
''')
replace(p,
'''    private fun attachAndWait(exec: AiStudioWebSessionExecutor, uri: Uri, name: String, mime: String, size: Long) {
        val latch = CountDownLatch(1); val ok = AtomicReference(false); val detail = AtomicReference("")
        exec.attachFile(uri, name, mime, size, requireUploadReady = true) { yes, d -> ok.set(yes); detail.set(d); latch.countDown() }
        if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải tệp chép lời")
        if (!ok.get()) error("AI Studio chưa xác nhận tệp sẵn sàng: ${detail.get().take(700)}")
        logger.log(2, TAG, "ATTACHMENT_PREPARED model=$model name=$name")
    }

    private fun awaitManualFileOnly(exec: AiStudioWebSessionExecutor): AiStudioWebSessionExecutor.Result {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()
        main.post {
            val accepted = exec.awaitManualAttachmentFileOnlyGenerate { r -> ref.set(r); latch.countDown() }
            if (!accepted && ref.get() == null) {
                ref.set(AiStudioWebSessionExecutor.Result(ok = false, error = "MANUAL_FILE_TRANSCRIBE_NOT_ARMED"))
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ bạn nhấn Run thủ công cho chép lời tệp")
        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp sau thao tác thủ công")
        if (!r.ok) error("AI Studio file transcribe sau thao tác thủ công thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")
        return r
    }
''',
'''    private fun attachAndWait(exec: AiStudioWebSessionExecutor, uri: Uri, name: String, mime: String, size: Long) {
        var lastDetail = ""
        repeat(12) { attempt ->
            val latch = CountDownLatch(1)
            val ok = AtomicReference(false)
            val detail = AtomicReference("")
            exec.attachFile(uri, name, mime, size, requireUploadReady = true) { yes, d ->
                ok.set(yes); detail.set(d); latch.countDown()
            }
            if (!latch.await(5, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio tải tệp chép lời")
            lastDetail = detail.get()
            if (ok.get()) {
                logger.log(2, TAG, "ATTACHMENT_PREPARED model=$model name=$name attempt=${attempt + 1}")
                return
            }
            if (lastDetail != "NOT_READY") error("AI Studio chưa xác nhận tệp sẵn sàng: ${lastDetail.take(700)}")
            logger.log(2, TAG, "R24_FILE_TRANSCRIBE_ATTACH_RETRY attempt=${attempt + 1}/12 reason=NOT_READY")
            Thread.sleep(500)
        }
        error("AI Studio chưa sẵn sàng để nhận tệp sau retry: ${lastDetail.take(700)}")
    }

    private fun generateFileOnly(exec: AiStudioWebSessionExecutor): AiStudioWebSessionExecutor.Result {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<AiStudioWebSessionExecutor.Result?>()
        main.post {
            val accepted = exec.generateAttachmentFileOnlyNative { r -> ref.set(r); latch.countDown() }
            if (!accepted && ref.get() == null) {
                ref.set(AiStudioWebSessionExecutor.Result(ok = false, error = "AUTO_FILE_TRANSCRIBE_NOT_ARMED"))
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.MINUTES)) error("Hết thời gian chờ AI Studio chép lời tệp tự động")
        val r = ref.get() ?: error("Không nhận được trạng thái chép lời tệp tự động")
        if (!r.ok) error("AI Studio file transcribe tự động thất bại: ${r.error.ifBlank { "HTTP ${r.status}" }}")
        return r
    }
''')

# Video logger should surface R24 host/trace events too.
p = 'app/src/main/java/com/oai/geminilivetranslate/network/AiStudioVideoDescriptionClient.kt'
replace(p,
'''                            name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2
''',
'''                            name.startsWith("R24_") || name.startsWith("JS_R24_") || name.startsWith("R23_") || name.startsWith("JS_R23_") || name.startsWith("R22_") || name.startsWith("JS_R22_") || name.startsWith("R21_") || name.startsWith("R20_") || name.startsWith("R19_") || name.startsWith("R18_ATTACHMENT") -> 2
''')

print('R18.14 patch applied')
