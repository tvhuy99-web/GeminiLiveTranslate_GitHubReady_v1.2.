package com.oai.geminilivetranslate.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary R17.5.1 device-test surface.
 *
 * This does NOT create a second browser/session. It reparents the exact production WebView owned by
 * AiStudioWebRealtimeClient into the foreground MainActivity so device testing can see and touch the
 * same AI Studio session that TranslationService is using. R17.5.1 also provides a defensive runtime
 * bootstrap recovery: if DOCUMENT_START misses R17 while R14/R16 are present, the same production
 * bootstrap is injected into the current page before the session is allowed to remain stuck.
 * When the experiment stabilizes, set ENABLED=false or remove this helper and the backend returns to
 * fully hidden operation.
 */
object AiStudioLiveDebugSurface : Application.ActivityLifecycleCallbacks {
    const val VERSION = "2026-09-03-r17.5.1-visible-production-webview-recovery"
    const val ENABLED = true

    private const val MAIN_ACTIVITY = "com.oai.geminilivetranslate.MainActivity"
    private val installed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var foregroundActivity: WeakReference<Activity>? = null
    @Volatile private var currentWebView: WeakReference<WebView>? = null
    @Volatile private var latestStatus: String = "R17.5.1 • đang chuẩn bị AI Studio Live"
    @Volatile private var recoveryGeneration: Long = 0L

    private var overlay: FrameLayout? = null
    private var webContainer: FrameLayout? = null
    private var statusView: TextView? = null
    private var showButton: Button? = null

    fun install(application: Application) {
        if (!ENABLED || !installed.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(this)
    }

    fun show(webView: WebView, status: String) {
        if (!ENABLED) return
        latestStatus = status
        currentWebView = WeakReference(webView)
        recoveryGeneration += 1L
        val generation = recoveryGeneration
        main.post { attachIfPossible() }
        scheduleBootstrapRecovery(webView, generation)
    }

    fun updateStatus(status: String) {
        if (!ENABLED) return
        latestStatus = status
        main.post { statusView?.text = latestStatus }
    }

    fun detach(webView: WebView) {
        if (!ENABLED) return
        main.post {
            if (currentWebView?.get() !== webView) return@post
            recoveryGeneration += 1L
            (webView.parent as? ViewGroup)?.removeView(webView)
            currentWebView = null
            removeOverlay()
        }
    }

    private fun attachIfPossible() {
        val activity = foregroundActivity?.get() ?: return
        if (activity.isFinishing || activity.javaClass.name != MAIN_ACTIVITY) return
        val webView = currentWebView?.get() ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return

        if (overlay?.parent !== decor) {
            removeOverlay()
            buildOverlay(activity, decor)
        }

        val container = webContainer ?: return
        if (webView.parent !== container) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.removeAllViews()
            container.addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        statusView?.text = latestStatus
        overlay?.visibility = android.view.View.VISIBLE
        showButton?.visibility = android.view.View.GONE
        runCatching {
            webView.isClickable = true
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.onResume()
            webView.requestFocus()
            webView.requestFocusFromTouch()
        }
    }

    private fun scheduleBootstrapRecovery(webView: WebView, generation: Long) {
        val delays = longArrayOf(550L, 1_200L, 2_200L, 4_000L, 7_000L)
        delays.forEachIndexed { index, delay ->
            main.postDelayed({ ensureBootstrapInstalled(webView, generation, index + 1) }, delay)
        }
    }

    private fun ensureBootstrapInstalled(webView: WebView, generation: Long, attempt: Int) {
        if (!ENABLED || generation != recoveryGeneration || currentWebView?.get() !== webView) return
        if (webView.url.isNullOrBlank() || !webView.url.orEmpty().startsWith("https://aistudio.google.com")) return
        webView.evaluateJavascript(
            "Boolean(window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.version)",
        ) { raw ->
            if (generation != recoveryGeneration || currentWebView?.get() !== webView) return@evaluateJavascript
            if (raw == "true") return@evaluateJavascript
            latestStatus = "R17.5.1 • khôi phục bootstrap • lần $attempt"
            statusView?.text = latestStatus
            webView.evaluateJavascript(AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START) {
                if (generation != recoveryGeneration || currentWebView?.get() !== webView) return@evaluateJavascript
                webView.evaluateJavascript(
                    "Boolean(window.__AIS_R17_PRODUCTION__&&window.__AIS_R17_PRODUCTION__.version)",
                ) { verified ->
                    if (verified == "true") {
                        latestStatus = "R17.5.1 • TRANSLATION_GUARD_READY • chờ cấu hình phiên"
                        statusView?.text = latestStatus
                    }
                }
            }
        }
    }

    private fun buildOverlay(activity: Activity, decor: ViewGroup) {
        val density = activity.resources.displayMetrics.density
        val toolbarHeight = (52 * density).toInt().coerceAtLeast(52)
        val margin = (8 * density).toInt().coerceAtLeast(8)

        val frame = FrameLayout(activity).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            contentDescription = "AI Studio Web đang hiển thị để kiểm tra R17.5.1"
        }
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(margin, 0, margin, 0)
            setBackgroundColor(0xFFF3F3F3.toInt())
        }
        val status = TextView(activity).apply {
            text = latestStatus
            textSize = 13f
            isSingleLine = false
            contentDescription = "Trạng thái AI Studio Web"
        }
        val hide = Button(activity).apply {
            text = "Ẩn Web"
            contentDescription = "Ẩn tạm giao diện AI Studio Web nhưng giữ phiên đang chạy"
            setOnClickListener {
                frame.visibility = android.view.View.GONE
                showButton?.visibility = android.view.View.VISIBLE
            }
        }
        toolbar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        toolbar.addView(hide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val container = FrameLayout(activity)
        frame.addView(
            toolbar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight, Gravity.TOP),
        )
        frame.addView(
            container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = toolbarHeight
            },
        )
        decor.addView(
            frame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val show = Button(activity).apply {
            text = "Hiện Web"
            contentDescription = "Hiện lại giao diện AI Studio Web đang chạy"
            visibility = android.view.View.GONE
            setOnClickListener {
                visibility = android.view.View.GONE
                frame.visibility = android.view.View.VISIBLE
                currentWebView?.get()?.let { web ->
                    web.isClickable = true
                    web.isFocusable = true
                    web.isFocusableInTouchMode = true
                    web.requestFocus()
                    web.requestFocusFromTouch()
                }
            }
        }
        decor.addView(
            show,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = margin
                bottomMargin = margin
            },
        )

        overlay = frame
        webContainer = container
        statusView = status
        showButton = show
    }

    private fun removeOverlay() {
        val current = currentWebView?.get()
        if (current != null && current.parent === webContainer) {
            webContainer?.removeView(current)
        }
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        (showButton?.parent as? ViewGroup)?.removeView(showButton)
        overlay = null
        webContainer = null
        statusView = null
        showButton = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (!ENABLED || activity.javaClass.name != MAIN_ACTIVITY) return
        foregroundActivity = WeakReference(activity)
        main.post { attachIfPossible() }
    }

    override fun onActivityPaused(activity: Activity) {
        if (foregroundActivity?.get() !== activity) return
        foregroundActivity = null
        main.post {
            val webView = currentWebView?.get()
            if (webView != null && webView.parent === webContainer) webContainer?.removeView(webView)
            removeOverlay()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (foregroundActivity?.get() === activity) foregroundActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
