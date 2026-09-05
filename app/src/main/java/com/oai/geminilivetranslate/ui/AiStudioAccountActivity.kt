package com.oai.geminilivetranslate.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.oai.geminilivetranslate.core.AiConnectionModeStore
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger


class AiStudioAccountActivity : AppCompatActivity() {
    private lateinit var logger: SessionLogger
    private lateinit var statusView: TextView
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger = SessionLogger(this, AppPreferences(this))
        buildUi()
        configureWebView()
        logger.log(2, "AiStudioAccount", "Mở quản lý tài khoản mode=${AiConnectionModeStore(this).load()}")
        openAiStudio("open")
    }

    override fun onDestroy() {
        logger.log(2, "AiStudioAccount", "Đóng quản lý tài khoản host=${safeHost(webView.url)}")
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(TextView(this).apply {
            text = "TÀI KHOẢN GOOGLE / AI STUDIO"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityHeading(this, true)
        }, fullWidth())
        statusView = TextView(this).apply {
            text = "Đang mở AI Studio..."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(statusView, fullWidth())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("ĐĂNG NHẬP / MỞ") { openAiStudio("login-open") }, weighted())
        actions.addView(action("CHUYỂN TÀI KHOẢN") { clearSessionAndOpen("switch-account") }, weighted())
        root.addView(actions, fullWidth())
        val actions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions2.addView(action("ĐĂNG XUẤT") { clearSessionAndOpen("logout") }, weighted())
        actions2.addView(action("XONG") { finish() }, weighted())
        root.addView(actions2, fullWidth())

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val host = safeHost(url)
                statusView.text = if (host.contains("accounts.google")) {
                    "Hãy đăng nhập hoặc chọn tài khoản Google trong trang bên dưới"
                } else {
                    "Đang mở AI Studio..."
                }
                logger.log(3, "AiStudioAccount", "pageStarted host=$host")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val host = safeHost(url)
                val ready = host == "aistudio.google.com"
                statusView.text = when {
                    ready -> "AI Studio đã mở. Nếu thấy nội dung AI Studio, phiên đăng nhập đã sẵn sàng."
                    host.contains("accounts.google") -> "Hãy hoàn tất đăng nhập hoặc chọn tài khoản Google."
                    else -> "Trang tài khoản đang ở host=$host"
                }
                logger.log(2, "AiStudioAccount", "pageFinished host=$host aiStudioReady=$ready")
            }
        }
    }

    private fun openAiStudio(reason: String) {
        logger.log(2, "AiStudioAccount", "action=$reason loadHost=aistudio.google.com")
        statusView.text = "Đang mở AI Studio..."
        webView.loadUrl(LIVE_URL)
    }

    private fun clearSessionAndOpen(reason: String) {
        logger.log(2, "AiStudioAccount", "action=$reason clearWebSession=true")
        statusView.text = if (reason == "logout") "Đang đăng xuất phiên WebView..." else "Đang chuẩn bị chuyển tài khoản..."
        runCatching { webView.stopLoading() }
        runCatching { webView.clearHistory() }
        runCatching { webView.clearCache(true) }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            runOnUiThread {
                logger.log(2, "AiStudioAccount", "Web session cleared reason=$reason; reopening AI Studio")
                openAiStudio("$reason-reopen")
            }
        }
    }

    private fun safeHost(raw: String?): String = runCatching {
        android.net.Uri.parse(raw.orEmpty()).host.orEmpty().lowercase()
    }.getOrDefault("")

    private fun action(label: String, run: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minimumHeight = dp(52)
        contentDescription = label
        setOnClickListener { run() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val LIVE_URL = "https://aistudio.google.com/live?model=gemini-3.5-live-translate-preview"
    }
}
