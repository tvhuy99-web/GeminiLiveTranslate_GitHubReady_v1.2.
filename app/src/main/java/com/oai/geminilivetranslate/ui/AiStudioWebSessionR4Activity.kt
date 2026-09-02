package com.oai.geminilivetranslate.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import org.json.JSONObject
import org.json.JSONTokener

/**
 * R4 experiment: ask the authenticated AI Studio page to submit its own prompt without native
 * MotionEvent, coordinates, or locating/clicking the Run button. Credentials stay inside the page.
 * The proven R3 document-start network probe still captures GenerateContent response text.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class AiStudioWebSessionR4Activity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var liveLogView: TextView
    private lateinit var labLog: AiStudioWebSessionLabLog

    private val uiLog = StringBuilder()
    private var destroyed = false
    private var readSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        buildUi()
        configureWebView()
        lab("I", "R4_ACTIVITY_CREATE", "version=$R4_VERSION probe=${AiStudioWebSessionLabScripts.VERSION}")
        webView.loadUrl(NEW_CHAT_URL)
    }

    override fun onDestroy() {
        destroyed = true
        if (::webView.isInitialized) runCatching {
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "AI Studio Web Session R4 - KHÔNG CHẠM"
            textSize = 20f
            contentDescription = "AI Studio Web Session R4 không dùng thao tác chạm"
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "R4 không dùng MotionEvent, tọa độ hay nút Run. Trang AI Studio tự xử lý submit và tự tạo request bằng phiên đăng nhập hiện tại."
            textSize = 14f
        }, fullWidth())

        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 5
            contentDescription = "Prompt thử nghiệm R4"
        }
        root.addView(promptInput, fullWidth())

        root.addView(actionButton("R4. Gửi nội bộ không chạm") { internalSubmitAndCapture() }, fullWidth())
        root.addView(actionButton("Đọc kết quả network") { readNetworkResult("manual") }, fullWidth())
        root.addView(actionButton("Mở New Chat") { webView.loadUrl(NEW_CHAT_URL) }, fullWidth())
        root.addView(actionButton("Mở Nhật ký AI Studio") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        resultView = TextView(this).apply {
            text = "Kết quả R4: chưa thử"
            textSize = 15f
            setTextIsSelectable(true)
            contentDescription = "Kết quả network R4"
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(resultView, fullWidth())

        statusView = TextView(this).apply {
            text = "Trạng thái R4: đang mở AI Studio"
            setTextIsSelectable(true)
            contentDescription = "Trạng thái R4"
        }
        root.addView(statusView, fullWidth())

        webView = WebView(this).apply {
            contentDescription = "Google AI Studio dùng để giữ phiên đăng nhập cho R4"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val logScroll = ScrollView(this)
        liveLogView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký trực tiếp R4"
        }
        logScroll.addView(liveLogView)
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(150),
        ))

        setContentView(root)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JsBridge(), JS_BRIDGE_NAME)
        val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        lab("I", "R4_DOCUMENT_START_SUPPORT", "supported=$documentStartSupported")
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                AiStudioWebSessionLabScripts.DOCUMENT_START,
                setOf("https://aistudio.google.com"),
            )
            lab("I", "R4_DOCUMENT_START_REGISTERED", "probe=${AiStudioWebSessionLabScripts.VERSION}")
        } else {
            statusView.text = "Trạng thái R4: WebView không hỗ trợ document-start probe"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                lab("I", "R4_PAGE_STARTED", "url=${safeUrl(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lab("I", "R4_PAGE_FINISHED", "url=${safeUrl(url)} title=${view?.title.orEmpty().take(250)}")
                statusView.text = "Trạng thái R4: đã tải ${view?.title.orEmpty().ifBlank { "AI Studio" }}"
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    val url = request.url.toString()
                    if (url.contains("MakerSuiteService/GenerateContent", true)) {
                        val summary = request.requestHeaders.entries.joinToString { (name, value) ->
                            "$name=<${value.length} chars>"
                        }
                        lab(
                            "I",
                            "R4_WEB_GENERATE_REQUEST",
                            "method=${request.method} gesture=${request.hasGesture()} url=${safeUrl(url)} headers={$summary}",
                        )
                    }
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    lab("W", "R4_WEB_ERROR", "code=${error?.errorCode} desc=${error?.description} url=${safeUrl(request.url.toString())}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    lab("W", "R4_HTTP_ERROR", "status=${errorResponse?.statusCode} url=${safeUrl(request.url.toString())}")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                lab("E", "R4_SSL_ERROR", "primary=${error?.primaryError} url=${safeUrl(error?.url)}")
                handler?.cancel()
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onJsEvent(json: String) {
            val parsed = runCatching { JSONObject(json) }.getOrNull()
            val kind = parsed?.optString("kind", "JS_EVENT") ?: "JS_EVENT"
            val payload = parsed?.optJSONObject("payload")
            lab(if (kind.contains("ERROR") || kind.contains("FAIL")) "W" else "I", "JS_$kind", json.take(24_000))

            if (kind == "GENERATE_RESULT" && payload != null) {
                val status = payload.optInt("status", -1)
                val ok = payload.optBoolean("ok")
                val markerFound = payload.optBoolean("markerFound")
                val phase = payload.optString("phase")
                val response = payload.optString("responseText").take(5_000)
                runOnUiThread {
                    resultView.text = "R4 network: HTTP $status, ok=$ok, markerFound=$markerFound, phase=$phase\n$response"
                    statusView.text = "Trạng thái R4: đã nhận response trực tiếp từ network"
                }
            }
        }
    }

    private fun internalSubmitAndCapture() {
        val prompt = promptInput.text.toString()
        val marker = Regex("AIS_[A-Z0-9_]+").find(prompt)?.value ?: DEFAULT_MARKER
        readSeq += 1
        val seq = readSeq
        lab("I", "R4_INTERNAL_START", "seq=$seq promptChars=${prompt.length} marker=$marker")
        statusView.text = "Trạng thái R4: đang yêu cầu AI Studio submit nội bộ, không chạm"
        resultView.text = "Kết quả R4: đang chờ network..."

        webView.evaluateJavascript(internalSubmitScript(prompt, marker)) { raw ->
            val decoded = decodeEvalValue(raw)
            lab("I", "R4_INTERNAL_DISPATCH_RESULT", "seq=$seq result=${decoded.take(4_000)}")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val ok = obj?.optBoolean("ok") == true
            val strategy = obj?.optString("primaryStrategy").orEmpty()
            statusView.text = if (ok) {
                "Trạng thái R4: đã kích hoạt submit nội bộ ($strategy), đang chờ GenerateContent"
            } else {
                "Trạng thái R4: không kích hoạt được submit nội bộ: ${obj?.optString("error") ?: decoded.take(300)}"
            }
            scheduleNetworkReads(seq)
        }
    }

    private fun internalSubmitScript(prompt: String, marker: String): String {
        val quotedPrompt = JSONObject.quote(prompt)
        val quotedMarker = JSONObject.quote(marker)
        return """
            (function() {
              try {
                const state = window.__AIS_WEB_SESSION__;
                if (!state) return JSON.stringify({ok:false,error:'probe-not-installed'});

                const prompt = $quotedPrompt;
                const marker = $quotedMarker;
                state.expectedMarker = marker;
                state.lastResult = null;
                state.lastProgress = null;
                state.lastXhrLifecycle = null;

                function emit(kind, payload) {
                  try {
                    if (window.AIStudioWebSessionLab && window.AIStudioWebSessionLab.onJsEvent) {
                      window.AIStudioWebSessionLab.onJsEvent(JSON.stringify({t:Date.now(),kind:kind,payload:payload||{}}));
                    }
                  } catch (_) {}
                }

                function visible(el) {
                  try {
                    const r = el.getBoundingClientRect();
                    const s = getComputedStyle(el);
                    return r.width > 2 && r.height > 2 && s.display !== 'none' && s.visibility !== 'hidden';
                  } catch (_) { return false; }
                }

                const candidates = Array.from(document.querySelectorAll('textarea,input,[contenteditable="true"],[role="textbox"]'))
                  .map((el) => {
                    const hay = ((el.getAttribute('aria-label')||'') + ' ' + (el.getAttribute('placeholder')||'') + ' ' + (el.getAttribute('role')||'')).toLowerCase();
                    let score = 0;
                    if (el.tagName === 'TEXTAREA') score += 130;
                    if (el.isContentEditable) score += 100;
                    if (hay.includes('prompt')) score += 100;
                    if (visible(el)) score += 100;
                    return {el:el,score:score};
                  })
                  .sort((a,b) => b.score - a.score);

                if (!candidates.length) {
                  emit('R4_INTERNAL_FAIL',{reason:'prompt-not-found'});
                  return JSON.stringify({ok:false,error:'prompt-not-found'});
                }

                const el = candidates[0].el;
                el.focus();
                try {
                  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : (el.tagName === 'INPUT' ? HTMLInputElement.prototype : null);
                  const desc = proto && Object.getOwnPropertyDescriptor(proto,'value');
                  if (desc && desc.set) desc.set.call(el,prompt);
                  else if ('value' in el) el.value = prompt;
                  else el.textContent = prompt;
                } catch (_) {
                  if ('value' in el) el.value = prompt; else el.textContent = prompt;
                }
                try {
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:prompt}));
                } catch (_) {
                  el.dispatchEvent(new Event('input',{bubbles:true}));
                }
                el.dispatchEvent(new Event('change',{bubbles:true}));

                const baseline = Number(state.captureCount || 0);
                let form = null;
                try { form = el.form || el.closest('form'); } catch (_) {}
                if (!form) {
                  try {
                    const forms = Array.from(document.querySelectorAll('form'));
                    form = forms.find((f) => f.contains(el)) || (forms.length === 1 ? forms[0] : null);
                  } catch (_) {}
                }

                function started() { return Number(state.captureCount || 0) > baseline; }
                function note(strategy, extra) {
                  emit('R4_SUBMIT_ATTEMPT',Object.assign({strategy:strategy,baselineCaptureCount:baseline,currentCaptureCount:Number(state.captureCount||0)},extra||{}));
                }

                let primaryStrategy = 'no-form-keyboard-fallback';
                if (form) {
                  primaryStrategy = 'form-submit-event';
                  try {
                    const ev = typeof SubmitEvent === 'function'
                      ? new SubmitEvent('submit',{bubbles:true,cancelable:true})
                      : new Event('submit',{bubbles:true,cancelable:true});
                    const dispatchResult = form.dispatchEvent(ev);
                    note('form-submit-event',{dispatchResult:dispatchResult});
                  } catch (e) {
                    note('form-submit-event-error',{error:String(e)});
                  }
                } else {
                  note('form-not-found');
                }

                setTimeout(function() {
                  if (started()) {
                    emit('R4_STRATEGY_SUCCESS',{strategy:'form-submit-event',captureCount:Number(state.captureCount||0)});
                    return;
                  }
                  if (form && HTMLFormElement.prototype.requestSubmit) {
                    try {
                      HTMLFormElement.prototype.requestSubmit.call(form);
                      note('native-requestSubmit');
                    } catch (e) {
                      note('native-requestSubmit-error',{error:String(e)});
                    }
                  }
                }, 900);

                setTimeout(function() {
                  if (started()) {
                    emit('R4_STRATEGY_SUCCESS',{strategy:'submit-without-touch',captureCount:Number(state.captureCount||0)});
                    return;
                  }
                  try {
                    el.focus();
                    const down = new KeyboardEvent('keydown',{key:'Enter',code:'Enter',ctrlKey:true,bubbles:true,cancelable:true});
                    const up = new KeyboardEvent('keyup',{key:'Enter',code:'Enter',ctrlKey:true,bubbles:true,cancelable:true});
                    el.dispatchEvent(down);
                    el.dispatchEvent(up);
                    note('ctrl-enter-event',{isTrusted:false});
                  } catch (e) {
                    note('ctrl-enter-error',{error:String(e)});
                  }
                }, 2100);

                setTimeout(function() {
                  emit('R4_FINAL_STATE',{
                    captureStarted:started(),
                    baselineCaptureCount:baseline,
                    currentCaptureCount:Number(state.captureCount||0),
                    hasResult:!!state.lastResult,
                    hasProgress:!!state.lastProgress
                  });
                }, 3600);

                return JSON.stringify({
                  ok:true,
                  version:'2026-09-02-web-session-r4',
                  primaryStrategy:primaryStrategy,
                  formFound:!!form,
                  promptTag:String(el.tagName||''),
                  promptScore:candidates[0].score,
                  baselineCaptureCount:baseline,
                  runElementUsed:false,
                  motionEventUsed:false
                });
              } catch (e) {
                return JSON.stringify({ok:false,error:String(e),stack:String(e&&e.stack||'').slice(0,4000)});
              }
            })();
        """.trimIndent()
    }

    private fun scheduleNetworkReads(seq: Int) {
        listOf(1_300L, 2_800L, 4_800L, 7_500L).forEachIndexed { index, delay ->
            webView.postDelayed({
                if (!destroyed && seq == readSeq) readNetworkResult("auto-${index + 1}")
            }, delay)
        }
    }

    private fun readNetworkResult(source: String) {
        if (destroyed) return
        val script = AiStudioWebSessionLabScripts.call("window.__AIS_WEB_SESSION__.getLastSafeResponse()")
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeEvalValue(raw)
            lab("D", "R4_NETWORK_READ", "source=$source result=${decoded.take(20_000)}")
            val outer = runCatching { JSONObject(decoded) }.getOrNull()
            val value = outer?.optJSONObject("value")
            if (value != null) {
                val ok = value.optBoolean("ok")
                val status = value.optInt("status", -1)
                val markerFound = value.optBoolean("markerFound")
                val phase = value.optString("phase")
                val response = value.optString("responseText").take(5_000)
                resultView.text = "R4 network: HTTP $status, ok=$ok, markerFound=$markerFound, phase=$phase\n$response"
                if (ok || markerFound) statusView.text = "Trạng thái R4: thành công, response lấy trực tiếp từ AI Studio network"
            }
        }
    }

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun lab(level: String, name: String, detail: String) {
        labLog.event(level, name, detail)
        runOnUiThread {
            if (uiLog.length > 18_000) uiLog.delete(0, uiLog.length - 12_000)
            uiLog.append("[").append(level).append("][").append(name).append("] ")
                .append(detail.take(2_500)).append('\n')
            liveLogView.text = uiLog.toString()
        }
    }

    private fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val u = android.net.Uri.parse(raw)
            val path = u.path.orEmpty()
            "${u.scheme}://${u.host}$path"
        }.getOrElse { raw.substringBefore('?').take(500) }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(56)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(5) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val R4_VERSION = "2026-09-02-web-session-r4"
        private const val JS_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val NEW_CHAT_URL = "https://aistudio.google.com/prompts/new_chat"
        private const val DEFAULT_MARKER = "AIS_WEB_SESSION_R4_OK_20260902"
        private const val DEFAULT_PROMPT = "Reply with exactly AIS_WEB_SESSION_R4_OK_20260902 and nothing else."
    }
}
