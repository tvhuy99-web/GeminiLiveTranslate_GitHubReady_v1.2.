package com.oai.geminilivetranslate.network

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.AppPreferences
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Temporary device-debug support for AI Studio Live Start activation. */
internal object AiStudioNativeTapDocumentStart {
    const val VERSION = "2026-09-04-r18.7-native-action-tap-debug"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_NATIVE_START_TAP__&&window.__AIS_NATIVE_START_TAP__.version)return;
  const VERSION='2026-09-04-r18.7-native-action-tap-debug';
  const bridge=window.AIStudioNativeTapBridge;
  if(!bridge)return;

  function safeText(v,n){return String(v||'').replace(/\s+/g,' ').trim().slice(0,n||160);}
  function attr(el,name){try{return el&&el.getAttribute?safeText(el.getAttribute(name)||'',120):'';}catch(_){return '';}}
  function role(el){return attr(el,'role').toLowerCase();}
  function tag(el){try{return String(el&&el.tagName||'').toUpperCase();}catch(_){return '';}}
  function label(el){
    try{return safeText([attr(el,'aria-label'),attr(el,'data-testid'),attr(el,'name'),attr(el,'id'),safeText(el&&el.title||'',80),safeText(el&&el.value||'',100),safeText(el&&el.textContent||'',180)].filter(Boolean).join(' '),280).toLowerCase();}catch(_){return '';}
  }
  function startLike(el){
    const l=label(el),r=role(el),t=tag(el);if(!l||l.indexOf('stop')>=0)return false;
    if(!(t==='BUTTON'||t==='A'||r==='button'||r==='menuitem'||r==='tab'||r==='link'))return false;
    return /\b(start|begin|connect|talk|speak|join)\b/.test(l)||l.indexOf('go live')>=0||l.indexOf('start session')>=0||l.indexOf('start live')>=0;
  }
  function clickableAncestor(node){
    let el=node&&node.nodeType===1?node:null;
    for(let i=0;i<7&&el;i++,el=el.parentElement){if(startLike(el))return el;}
    return null;
  }
  function reportGesture(kind,ev){
    try{
      const el=clickableAncestor(ev&&ev.target);if(!el)return;
      bridge.reportStartGesture(JSON.stringify({kind:kind,trusted:!!ev.isTrusted,tag:tag(el)||'none',role:role(el)||'none'}));
    }catch(_){}
  }
  ['pointerdown','touchstart','mousedown','pointerup','touchend','mouseup','click'].forEach(function(kind){
    try{document.addEventListener(kind,function(ev){reportGesture(kind,ev);},true);}catch(_){}
  });

  const proto=window.HTMLElement&&window.HTMLElement.prototype;
  const nativeClick=proto&&proto.click;
  if(proto&&typeof nativeClick==='function'&&!nativeClick.__aisNativeStartTapWrapped){
    const wrapped=function(){
      try{
        if(startLike(this)){
          let r=this.getBoundingClientRect();
          if(r&&r.width>1&&r.height>1){
            const vw=Math.max(1,window.innerWidth||document.documentElement.clientWidth||1);
            const vh=Math.max(1,window.innerHeight||document.documentElement.clientHeight||1);
            let cx=r.left+r.width/2,cy=r.top+r.height/2;
            if(cx<0||cy<0||cx>vw||cy>vh){
              try{this.scrollIntoView({block:'center',inline:'center'});}catch(_){}
              r=this.getBoundingClientRect();cx=r.left+r.width/2;cy=r.top+r.height/2;
            }
            if(cx>=0&&cy>=0&&cx<=vw&&cy<=vh){
              bridge.requestNativeTap(JSON.stringify({xRatio:cx/vw,yRatio:cy/vh,tag:tag(this)||'none',role:role(this)||'none'}));
              return;
            }
          }
        }
      }catch(_){}
      return nativeClick.apply(this,arguments);
    };
    wrapped.__aisNativeStartTapWrapped=true;
    proto.click=wrapped;
  }
  window.__AIS_NATIVE_START_TAP__={version:VERSION};
})();
    """.trimIndent()
}

internal class AiStudioNativeTapController(
    private val webView: WebView,
    private val logger: SessionLogger?,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastTapAt = 0L

    @JavascriptInterface
    fun requestNativeTap(json: String?) {
        val parsed = runCatching { JSONObject(json.orEmpty()) }.getOrNull()
        val xRatio = parsed?.optDouble("xRatio", Double.NaN) ?: Double.NaN
        val yRatio = parsed?.optDouble("yRatio", Double.NaN) ?: Double.NaN
        val tag = parsed?.optString("tag").orEmpty().take(32)
        val role = parsed?.optString("role").orEmpty().take(48)
        val purpose = parsed?.optString("purpose").orEmpty().take(48).ifBlank { "start-live" }
        if (!xRatio.isFinite() || !yRatio.isFinite() || xRatio !in 0.0..1.0 || yRatio !in 0.0..1.0) {
            logger?.log(1, "AiStudioNativeTap", "START_TAP_REJECT invalidCoordinates=true")
            return
        }
        main.post {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapAt < 1200L) {
                logger?.log(3, "AiStudioNativeTap", "ACTION_TAP_SKIPPED purpose=$purpose debounce=true")
                return@post
            }
            val width = webView.width
            val height = webView.height
            if (width < 4 || height < 4 || !webView.isShown) {
                logger?.log(1, "AiStudioNativeTap", "ACTION_TAP_REJECT purpose=$purpose laidOut=${width >= 4 && height >= 4} shown=${webView.isShown} width=$width height=$height")
                return@post
            }
            lastTapAt = now
            val x = (xRatio * width).toFloat().coerceIn(1f, (width - 2).toFloat())
            val y = (yRatio * height).toFloat().coerceIn(1f, (height - 2).toFloat())
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val downHandled = runCatching { webView.dispatchTouchEvent(down) }.getOrDefault(false)
            down.recycle()
            logger?.log(2, "AiStudioNativeTap", "ACTION_TAP_DOWN purpose=$purpose x=${x.roundToInt()} y=${y.roundToInt()} width=$width height=$height handled=$downHandled tag=$tag role=$role")
            main.postDelayed({
                if (!webView.isAttachedToWindow) {
                    logger?.log(1, "AiStudioNativeTap", "ACTION_TAP_UP purpose=$purpose skipped=detached")
                    return@postDelayed
                }
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                val upHandled = runCatching { webView.dispatchTouchEvent(up) }.getOrDefault(false)
                up.recycle()
                logger?.log(2, "AiStudioNativeTap", "ACTION_TAP_UP purpose=$purpose x=${x.roundToInt()} y=${y.roundToInt()} handled=$upHandled durationMs=${upTime - downTime}")
            }, 72L)
        }
    }

    @JavascriptInterface
    fun reportStartGesture(json: String?) {
        val parsed = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return
        val kind = parsed.optString("kind").take(24)
        val trusted = parsed.optBoolean("trusted", false)
        val tag = parsed.optString("tag").take(32)
        val role = parsed.optString("role").take(48)
        logger?.log(2, "AiStudioNativeTap", "START_GESTURE kind=$kind trusted=$trusted tag=$tag role=$role")
    }
}

internal object AiStudioDebugWebViewHost {
    const val VERSION = "2026-09-05-r18.14-toggle-hidden-webview-debug"
    private val main = Handler(Looper.getMainLooper())
    private val panels = WeakHashMap<WebView, WeakReference<ViewGroup>>()

    fun attach(webView: WebView, logger: SessionLogger?, retry: Int = 0) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { attach(webView, logger, retry) }
            return
        }
        val activity = GeminiTranslateApp.currentActivity()
        if (activity == null) {
            if (retry < 12) main.postDelayed({ attach(webView, logger, retry + 1) }, 150L)
            else logger?.log(1, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_ATTACH_FAILED reason=no-foreground-activity")
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            logger?.log(1, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_ATTACH_FAILED reason=no-content-root")
            return
        }
        panels.keys.toList().filter { it !== webView }.forEach { staleView ->
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
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 16f * resources.displayMetrics.density
        }
        val label = TextView(activity).apply {
            text = "AI Studio kiểm tra tạm thời - đây là chính phiên AI Studio ứng dụng đang dùng"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
            textSize = 14f
        }
        panel.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val screenHeight = activity.resources.displayMetrics.heightPixels
        val height = (screenHeight * 0.48f).roundToInt().coerceAtLeast(dp(activity, 300))
        content.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { retain(webView, logger, reason) }
            return
        }
        val panel = panels[webView]?.get()
        if (panel != null && panel.parent != null && webView.parent != null) {
            val visible = AppPreferences(panel.context).loadAiStudioWebViewVisible()
            applyPresentation(webView, panel, visible, logger, "retain:$reason")
            logger?.log(2, "AiStudioDebugWeb", "AI_STUDIO_WEBVIEW_RETAINED reason=$reason visible=$visible")
            return
        }
        attach(webView, logger)
        logger?.log(2, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_RETAIN_REQUEST reason=$reason")
    }

    fun detach(webView: WebView, logger: SessionLogger?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { detach(webView, logger) }
            return
        }
        val panel = panels.remove(webView)?.get()
        (webView.parent as? ViewGroup)?.removeView(webView)
        (panel?.parent as? ViewGroup)?.removeView(panel)
        logger?.log(3, "AiStudioDebugWeb", "VISIBLE_WEBVIEW_DETACHED")
    }

    private fun dp(activity: android.app.Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
