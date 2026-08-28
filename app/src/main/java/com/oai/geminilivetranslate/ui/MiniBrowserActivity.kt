package com.oai.geminilivetranslate.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.oai.geminilivetranslate.core.SourceMode
import com.oai.geminilivetranslate.databinding.ActivityMiniBrowserBinding
import com.oai.geminilivetranslate.service.TranslationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MiniBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMiniBrowserBinding
    private var service: TranslationService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var updatingProgress = false
    private var stateJob: Job? = null
    private var pendingProjectionResultCode: Int? = null
    private var pendingProjectionData: Intent? = null

    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection() else toast("Cần quyền Microphone để thu âm thanh nội bộ")
    }

    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val projectionData = result.data
        if (result.resultCode == Activity.RESULT_OK && projectionData != null) {
            startService(Intent(this, TranslationService::class.java))
            val currentService = service
            if (currentService != null) {
                currentService.setSourceMode(SourceMode.INTERNAL)
                currentService.startTranslation(SourceMode.INTERNAL, result.resultCode, projectionData)
            } else {
                pendingProjectionResultCode = result.resultCode
                pendingProjectionData = projectionData
            }
        } else toast("Bạn chưa cấp quyền thu âm thanh nội bộ")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TranslationService.LocalBinder)?.getService()
            bound = service != null
            observeService()
            val resultCode = pendingProjectionResultCode
            val projectionData = pendingProjectionData
            if (resultCode != null && projectionData != null) {
                pendingProjectionResultCode = null
                pendingProjectionData = null
                service?.setSourceMode(SourceMode.INTERNAL)
                service?.startTranslation(SourceMode.INTERNAL, resultCode, projectionData)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; bound = false }
    }

    private val statusLoop = object : Runnable {
        override fun run() {
            updateVideoState()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiniBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView()
        setupControls()
        binding.urlInput.setText("https://www.google.com")
        safeLoad("https://www.google.com")
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TranslationService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(statusLoop)
    }

    override fun onStop() {
        handler.removeCallbacks(statusLoop)
        stateJob?.cancel()
        stateJob = null
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private fun setupWebView() = with(binding.webView) {
        WebView.setWebContentsDebuggingEnabled(false)
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= 21) setAcceptThirdPartyCookies(this@with, false)
        }
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                return if (url.scheme == "http" || url.scheme == "https") false else {
                    openExternal(url); true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                setStatus("Đang tải:\n$url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                setStatus("Đã tải xong:\n$url")
                binding.urlInput.setText(url.orEmpty())
                hideKeyboard()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) setStatus("Lỗi tải trang:\n${request.url}\n${error?.description}")
            }

        }
    }

    private fun setupControls() = with(binding) {
        goButton.setOnClickListener { safeLoad(normalizeInput(urlInput.text.toString())) }
        urlInput.setOnEditorActionListener { _, _, _ -> safeLoad(normalizeInput(urlInput.text.toString())); true }
        browserBackButton.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        closeBrowserButton.setOnClickListener { finish() }
        videoRewindButton.setOnClickListener { runVideoJs("v.currentTime=Math.max(0,v.currentTime-10);return 'ok';") }
        videoForwardButton.setOnClickListener { runVideoJs("v.currentTime=Math.min(v.duration||v.currentTime+10,v.currentTime+10);return 'ok';") }
        videoPlayButton.setOnClickListener {
            runVideoJs("if(v.paused){v.play();return 'playing';}else{v.pause();return 'paused';}")
        }
        videoProgressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingProgress) runVideoJs("if(isFinite(v.duration)&&v.duration>0){v.currentTime=v.duration*${progress.coerceIn(0, 100)}/100;}return 'ok';")
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        videoVolumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) runVideoJs("v.volume=${progress.coerceIn(0, 100) / 100.0};return 'ok';")
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        browserTranslateButton.setOnClickListener {
            if (service?.state?.value?.running == true) service?.stopTranslation()
            else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    toast("Thu âm nội bộ yêu cầu Android 10 trở lên")
                    return@setOnClickListener
                }
                if (ContextCompat.checkSelfPermission(this@MiniBrowserActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else requestProjection()
            }
        }
    }

    private fun observeService() {
        val current = service ?: return
        stateJob?.cancel()
        stateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                current.state.collect { state ->
                    binding.browserTranslateButton.text = if (state.running) "Dừng dịch" else "Bắt đầu dịch"
                    val mode = if (state.running) "Đang dịch ${state.currentLanguage}" else "Chưa dịch"
                    binding.browserStatusText.text = "Chế độ hiện tại: $mode\nTrạng thái: ${state.status}"
                }
            }
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun normalizeInput(value: String): String {
        val raw = value.trim().trim('"', '\'', '<', '>').replace("\n", "").replace("\t", "")
        if (raw.isBlank()) return "https://www.google.com"
        val compact = raw.replace(" ", "")
        return when {
            compact.startsWith("https://", true) || compact.startsWith("http://", true) -> compact
            compact.startsWith("www.", true) || compact.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9._-]+.*")) -> "https://$compact"
            else -> "https://www.google.com/search?q=${Uri.encode(raw)}"
        }
    }

    private fun safeLoad(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") { openExternal(uri); return }
        setStatus("Đang mở:\n$url")
        binding.webView.loadUrl(url)
    }

    private fun openExternal(uri: Uri) {
        val allowed = setOf("mailto", "tel", "sms", "smsto", "geo")
        if (uri.scheme !in allowed) {
            setStatus("Đã chặn liên kết ngoài không an toàn:\n$uri")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { toast("Không mở được liên kết này") }
    }

    private fun runVideoJs(body: String) {
        val script = """
            (function(){
              const v=document.querySelector('video');
              if(!v) return 'no-video';
              $body
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { result ->
            if (result == "\"no-video\"") setStatus("Không tìm thấy video HTML5 trên trang hiện tại")
        }
    }

    private fun updateVideoState() {
        if (!::binding.isInitialized) return
        val script = """
            (function(){const v=document.querySelector('video');if(!v)return 'none';
            return [v.duration||0,v.currentTime||0,v.paused?1:0,v.volume||0].join('|');})();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { raw ->
            val value = raw.trim('"').replace("\\u003C", "<")
            if (value == "none" || value.isBlank()) return@evaluateJavascript
            val parts = value.split('|')
            val duration = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val current = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val paused = parts.getOrNull(2) == "1"
            val volume = parts.getOrNull(3)?.toDoubleOrNull() ?: 1.0
            updatingProgress = true
            binding.videoProgressSeekBar.progress = if (duration > 0) ((current / duration) * 100).toInt().coerceIn(0, 100) else 0
            binding.videoVolumeSeekBar.progress = (volume * 100).toInt().coerceIn(0, 100)
            binding.videoPlayButton.text = if (paused) "Phát" else "Tạm dừng"
            updatingProgress = false
        }
    }

    private fun setStatus(message: String) { binding.browserStatusText.text = message }

    private fun hideKeyboard() {
        binding.urlInput.clearFocus()
        binding.webView.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
