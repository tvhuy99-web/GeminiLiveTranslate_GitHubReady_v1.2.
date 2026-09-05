package com.oai.geminilivetranslate.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.AiStudioWebLiveClient
import com.oai.geminilivetranslate.core.AiStudioWebLiveOutputBridge
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR14DirectLiveEngine
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR16LiveOutputEngine
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR17ProductionBootstrap
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR18LanguageGuard
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
internal class AiStudioWebRealtimeClient(
    private val targetLanguage: String,
    private val operationMode: GeminiLiveClient.OperationMode,
    private val logger: SessionLogger,
    private val listener: GeminiLiveClient.Listener,
    private val maxQueuedWireBytes: Long,
) {
    private val appContext = GeminiTranslateApp.requireAppContext()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)
    private val setupDelivered = AtomicBoolean(false)
    private val responseServerEvents = AtomicLong(0L)
    private val inputTranscriptEvents = AtomicLong(0L)
    private val outputTranscriptEvents = AtomicLong(0L)
    private val modelTextEvents = AtomicLong(0L)
    private val textDispatchEvents = AtomicLong(0L)
    private val audioChunks = AtomicLong(0L)
    private val audioBytes = AtomicLong(0L)
    private val turnCompleteEvents = AtomicLong(0L)
    private val backpressureEvents = AtomicLong(0L)
    private val maxObservedQueuedBytes = AtomicLong(0L)

    @Volatile private var webView: WebView? = null
    @Volatile private var inputClient: AiStudioWebLiveClient? = null
    @Volatile private var outputBridge: AiStudioWebLiveOutputBridge? = null
    @Volatile private var configured = false
    @Volatile private var bootstrapInstalled = false
    @Volatile private var serverSetupSeen = false
    @Volatile private var connectingStartedAt = 0L
    @Volatile private var lastBootstrapProgressAt = 0L
    @Volatile private var lastBootstrapSignature = ""
    @Volatile private var lastInputAt = 0L
    @Volatile private var carrierEnabled = false
    @Volatile private var lastBootstrapState = ""
    @Volatile private var lastDirectState = ""
    @Volatile private var lastOutputState = ""
    @Volatile private var lastLanguageGuardState = ""
    @Volatile private var languageGuardConfigured = false
    @Volatile private var lastCarrierRequests = 0L
    @Volatile private var lastBrowserChunks = 0L
    @Volatile private var lastProgressAt = 0L
    @Volatile private var routeRepairAttempts = 0
    @Volatile private var lastRouteRepairAt = 0L
    @Volatile private var bootstrapRecoveryAttempts = 0
    @Volatile private var bootstrapRecoveryInFlight = false
    @Volatile private var lastBootstrapRecoveryAt = 0L
    @Volatile private var lastBootstrapInstallError = ""
    @Volatile private var pageGeneration = 0
    @Volatile private var startSessionRecoveryAttempts = 0
    @Volatile private var lastStartSessionRecoveryAt = 0L

    fun connect() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        connectingStartedAt = now
        lastBootstrapProgressAt = now
        lastBootstrapSignature = ""
        logger.log(
            2,
            "AiStudioLive",
            "CONNECT hidden=false debugVisible=true isolatedLiveHost=true operation=$operationMode model=${targetLiveModel()} target=$targetLanguage bootstrap=${AiStudioWebSessionR17ProductionBootstrap.VERSION} languageGuard=${AiStudioWebSessionR18LanguageGuard.VERSION}",
        )
        main.post { startOnMain() }
    }

    fun sendAudio(pcm16kMono: ByteArray): GeminiLiveClient.SendResult {
        if (pcm16kMono.isEmpty()) return GeminiLiveClient.SendResult.SENT
        if (closed.get()) return GeminiLiveClient.SendResult.CLOSED
        if (!setupDelivered.get()) return GeminiLiveClient.SendResult.NOT_READY
        lastInputAt = SystemClock.elapsedRealtime()
        setCarrierActive(true)
        return when (inputClient?.sendAudio(pcm16kMono)) {
            AiStudioWebLiveClient.SendResult.QUEUED -> GeminiLiveClient.SendResult.SENT
            AiStudioWebLiveClient.SendResult.BACKPRESSURED -> {
                val count = backpressureEvents.incrementAndGet()
                if (count == 1L || count % 25L == 0L) {
                    logger.log(1, "AiStudioInput", "BACKPRESSURE events=$count queuedWire=${estimatedQueuedWireBytes()}")
                }
                GeminiLiveClient.SendResult.BACKPRESSURED
            }
            AiStudioWebLiveClient.SendResult.NOT_ARMED, null -> GeminiLiveClient.SendResult.NOT_READY
            AiStudioWebLiveClient.SendResult.CLOSED -> GeminiLiveClient.SendResult.CLOSED
        }.also { updateBackpressureHighWater() }
    }

    fun sendAudioStreamEnd(): GeminiLiveClient.SendResult {
        if (closed.get()) return GeminiLiveClient.SendResult.CLOSED
        if (!setupDelivered.get()) return GeminiLiveClient.SendResult.NOT_READY
        val result = when (inputClient?.sendAudioStreamEnd()) {
            AiStudioWebLiveClient.SendResult.QUEUED -> GeminiLiveClient.SendResult.SENT
            AiStudioWebLiveClient.SendResult.BACKPRESSURED -> {
                backpressureEvents.incrementAndGet()
                GeminiLiveClient.SendResult.BACKPRESSURED
            }
            AiStudioWebLiveClient.SendResult.NOT_ARMED, null -> GeminiLiveClient.SendResult.NOT_READY
            AiStudioWebLiveClient.SendResult.CLOSED -> GeminiLiveClient.SendResult.CLOSED
        }
        logger.log(3, "AiStudioInput", "STREAM_END result=$result queuedWire=${estimatedQueuedWireBytes()}")
        main.postDelayed({ maybeSilenceCarrier(force = true) }, STREAM_END_CARRIER_GRACE_MS)
        return result.also { updateBackpressureHighWater() }
    }

    fun backpressureStats(): GeminiLiveClient.BackpressureStats {
        val queued = estimatedQueuedWireBytes()
        updateHighWater(queued)
        return GeminiLiveClient.BackpressureStats(
            queuedWireBytes = queued,
            maxObservedWireBytes = maxObservedQueuedBytes.get(),
            backpressureEvents = backpressureEvents.get(),
        )
    }

    fun responseStats(): GeminiLiveClient.ResponseStats = GeminiLiveClient.ResponseStats(
        serverContentEvents = responseServerEvents.get(),
        inputTranscriptEvents = inputTranscriptEvents.get(),
        outputTranscriptionObjects = outputTranscriptEvents.get(),
        outputTextEvents = outputTranscriptEvents.get(),
        modelTextEvents = modelTextEvents.get(),
        textDispatchEvents = textDispatchEvents.get(),
        audioChunks = audioChunks.get(),
        audioBytes = audioBytes.get(),
        turnCompleteEvents = turnCompleteEvents.get(),
    )

    fun close(graceful: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        terminalDelivered.set(true)
        main.removeCallbacksAndMessages(null)
        main.post {
            setCarrierActive(false)
            runCatching { inputClient?.clear() }
            runCatching { inputClient?.close() }
            inputClient = null
            runCatching { outputBridge?.close() }
            outputBridge = null
            val current = webView
            webView = null
            if (current != null) {
                runCatching { current.stopLoading() }
                runCatching { current.onPause() }
                runCatching { current.removeJavascriptInterface(DIAGNOSTIC_BRIDGE_NAME) }
                runCatching { current.removeJavascriptInterface(NATIVE_TAP_BRIDGE_NAME) }
                AiStudioDebugWebViewHost.retain(current, logger, "live-close-${if (setupDelivered.get()) "after-setup" else "before-setup"}")
            }
        }
        val stats = responseStats()
        logger.log(
            2,
            "AiStudioLive",
            "CLOSE hidden=false debugVisible=true graceful=$graceful setup=${setupDelivered.get()} server=${stats.serverContentEvents} inputText=${stats.inputTranscriptEvents} outputText=${stats.outputTextEvents} modelText=${stats.modelTextEvents} audioChunks=${stats.audioChunks} audioBytes=${stats.audioBytes} turns=${stats.turnCompleteEvents} backpressure=${backpressureEvents.get()} bootstrapRecoveries=$bootstrapRecoveryAttempts",
        )
    }

    private fun startOnMain() {
        if (closed.get() || webView != null) return
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fail(IllegalStateException("WebView không hỗ trợ DOCUMENT_START_SCRIPT"))
            return
        }

        val created = WebView(appContext)
        webView = created
        configureWebView(created)
        created.addJavascriptInterface(DiagnosticBridge(), DIAGNOSTIC_BRIDGE_NAME)
        created.addJavascriptInterface(AiStudioNativeTapController(created, logger), NATIVE_TAP_BRIDGE_NAME)
        AiStudioDebugWebViewHost.attach(created, logger)

        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioNativeTapDocumentStart.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioWebSessionR18LanguageGuard.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )

        inputClient = AiStudioWebLiveClient(created) { name, detail ->
            when (name) {
                "R15_CLIENT_BATCH", "R15_CLIENT_ARM", "R15_CLIENT_STREAM_END", "R15_CLIENT_CLEAR", "R15_CLIENT_CLOSE" ->
                    logger.log(3, "AiStudioInput", "$name ${safe(detail, 1500)}")
                else -> Unit
            }
        }
        outputBridge = AiStudioWebLiveOutputBridge(
            created,
            object : AiStudioWebLiveOutputBridge.Listener {
                override fun onAudio(pcm24kMono: ByteArray, mimeType: String) {
                    if (closed.get() || !setupDelivered.get()) return
                    val chunks = audioChunks.incrementAndGet()
                    val bytes = audioBytes.addAndGet(pcm24kMono.size.toLong())
                    responseServerEvents.incrementAndGet()
                    if (chunks == 1L || chunks % 25L == 0L) {
                        logger.log(3, "AiStudioOutput", "AUDIO chunks=$chunks totalBytes=$bytes lastBytes=${pcm24kMono.size} mime=${safe(mimeType, 100)}")
                    }
                    listener.onAudio(pcm24kMono)
                }

                override fun onText(kind: String, text: String) {
                    if (closed.get() || text.isBlank()) return
                    responseServerEvents.incrementAndGet()
                    when (kind) {
                        "inputTranscription" -> {
                            inputTranscriptEvents.incrementAndGet()
                            listener.onInputTranscript(text)
                        }
                        "interimInputTranscription" -> listener.onInterimTranscript(text)
                        "outputTranscription" -> {
                            outputTranscriptEvents.incrementAndGet()
                            textDispatchEvents.incrementAndGet()
                            listener.onText(text)
                        }
                        "modelText" -> {
                            modelTextEvents.incrementAndGet()
                            textDispatchEvents.incrementAndGet()
                            listener.onText(text)
                        }
                    }
                    logger.log(3, "AiStudioOutput", "TEXT kind=$kind chars=${text.length} serverEvents=${responseServerEvents.get()}")
                }

                override fun onSignal(kind: String, value: String) {
                    if (closed.get()) return
                    logger.log(3, "AiStudioOutput", "SIGNAL kind=$kind valueChars=${value.length}")
                    when (kind) {
                        "setupComplete" -> {
                            serverSetupSeen = true
                            markBootstrapProgress("server-setup-complete")
                            logger.log(2, "AiStudioLive", "SERVER_SETUP_COMPLETE waitingCarrier=true")
                            maybeDeliverSetup()
                        }
                        "generationComplete" -> logger.log(3, "AiStudioLive", "generationComplete")
                        "turnComplete" -> {
                            turnCompleteEvents.incrementAndGet()
                            setCarrierActive(false)
                            listener.onTurnComplete()
                        }
                        "interrupted" -> {
                            setCarrierActive(false)
                            listener.onInterrupted()
                        }
                        "sessionResumption" -> listener.onSessionResumptionUpdate(value.equals("true", ignoreCase = true), null)
                        "goAway" -> listener.onGoAway(value.takeIf(String::isNotBlank))
                    }
                }
            },
        ) { name, detail -> logger.log(3, "AiStudioOutputBridge", "$name ${safe(detail, 1400)}") }

        listener.onOpen()
        runCatching { created.onResume() }
        runCatching { created.resumeTimers() }
        val liveUrl = liveRouteUrl()
        logger.log(2, "AiStudioLive", "RUNTIME_STARTED hidden=false debugVisible=true isolatedLiveHost=true route=/live model=${targetLiveModel()} operation=$operationMode target=$targetLanguage")
        created.loadUrl(liveUrl)
        scheduleBootstrapRecovery(created, pageGeneration)
        main.postDelayed(healthTick, HEALTH_TICK_MS)
    }

    private fun configureWebView(view: WebView) {
        view.settings.apply {
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
            setAcceptThirdPartyCookies(view, true)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val resources = req.resources.orEmpty()
                val audioOnly = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    !resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (audioOnly && granted) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    logger.log(2, "AiStudioAuthMedia", "WEB_PERMISSION audio=true video=false androidMic=true result=granted")
                } else {
                    req.deny()
                    logger.log(1, "AiStudioAuthMedia", "WEB_PERMISSION audioOnly=$audioOnly androidMic=$granted result=denied")
                }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageGeneration += 1
                configured = false
                bootstrapInstalled = false
                lastBootstrapSignature = ""
                serverSetupSeen = false
                lastDirectState = ""
                lastOutputState = ""
                lastLanguageGuardState = ""
                languageGuardConfigured = false
                logger.log(3, "AiStudioLive", "PAGE_STARTED generation=$pageGeneration host=${hostOf(url)} path=${pathOf(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val current = view ?: return
                logger.log(2, "AiStudioLive", "PAGE_FINISHED generation=$pageGeneration host=${hostOf(url)} path=${pathOf(url)}")
                if (hostOf(url) == "aistudio.google.com") {
                    scheduleBootstrapRecovery(current, pageGeneration)
                    main.post { ensureBootstrapInstalled(current, pageGeneration, force = true) }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    fail(IllegalStateException("AI Studio WebView error ${error?.errorCode}: ${safe(error?.description?.toString().orEmpty(), 240)}"))
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    fail(IllegalStateException("AI Studio HTTP ${errorResponse?.statusCode}"))
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                fail(IllegalStateException("AI Studio SSL error ${error?.primaryError}"))
            }
        }
    }

    private fun scheduleBootstrapRecovery(current: WebView, generation: Int) {
        BOOTSTRAP_RECOVERY_DELAYS_MS.forEach { delay ->
            main.postDelayed({ ensureBootstrapInstalled(current, generation, force = false) }, delay)
        }
    }

    private fun ensureBootstrapInstalled(current: WebView, generation: Int, force: Boolean = false) {
        if (closed.get() || webView !== current || generation != pageGeneration || bootstrapRecoveryInFlight) return
        val url = current.url.orEmpty()
        val host = hostOf(url)
        if (host != "aistudio.google.com") return
        if (bootstrapInstalled) return

        val now = SystemClock.elapsedRealtime()
        if (!force && lastBootstrapRecoveryAt > 0L && now - lastBootstrapRecoveryAt < BOOTSTRAP_RECOVERY_MIN_INTERVAL_MS) return
        if (bootstrapRecoveryAttempts >= MAX_BOOTSTRAP_RECOVERY_ATTEMPTS) {
            fail(
                IllegalStateException(
                    "R17_BOOTSTRAP_INSTALL_FAILED attempts=$bootstrapRecoveryAttempts last=${lastBootstrapInstallError.ifBlank { "unknown" }}",
                ),
            )
            return
        }

        bootstrapRecoveryInFlight = true
        current.evaluateJavascript(bootstrapProbeScript()) { rawProbe ->
            if (closed.get() || webView !== current || generation != pageGeneration) {
                bootstrapRecoveryInFlight = false
                return@evaluateJavascript
            }
            val probe = parseObject(rawProbe)
            if (probe?.optBoolean("ok") == true) {
                bootstrapRecoveryInFlight = false
                bootstrapInstalled = true
                lastBootstrapInstallError = ""
                markBootstrapProgress("bootstrap-present-${probe.optString("version")}")
                logger.log(2, "AiStudioBootstrap", "INSTALL_PRESENT version=${safe(probe.optString("version"), 120)} attempt=$bootstrapRecoveryAttempts")
                configureBootstrapIfNeeded()
                return@evaluateJavascript
            }

            bootstrapRecoveryAttempts += 1
            lastBootstrapRecoveryAt = SystemClock.elapsedRealtime()
            logger.log(1, "AiStudioBootstrap", "RECOVERY_BEGIN attempt=$bootstrapRecoveryAttempts/$MAX_BOOTSTRAP_RECOVERY_ATTEMPTS host=$host path=${pathOf(url)}")
            current.evaluateJavascript(bootstrapRecoveryScript()) { rawInstall ->
                bootstrapRecoveryInFlight = false
                if (closed.get() || webView !== current || generation != pageGeneration) return@evaluateJavascript
                val result = parseObject(rawInstall)
                val ok = result?.optBoolean("ok") == true
                if (ok) {
                    bootstrapInstalled = true
                    configured = false
                    lastBootstrapInstallError = ""
                    val version = safe(result?.optString("version").orEmpty(), 120)
                    markBootstrapProgress("bootstrap-installed-$bootstrapRecoveryAttempts-$version")
                    logger.log(2, "AiStudioBootstrap", "RECOVERY_OK attempt=$bootstrapRecoveryAttempts version=$version")
                    configureBootstrapIfNeeded()
                } else {
                    val name = safe(result?.optString("name").orEmpty(), 80).ifBlank { "UnknownError" }
                    val message = safe(result?.optString("message").orEmpty(), 500)
                    val stack = safe(result?.optString("stack").orEmpty(), 1200)
                    lastBootstrapInstallError = "$name:$message"
                    logger.log(
                        0,
                        "AiStudioBootstrap",
                        "RECOVERY_FAILED attempt=$bootstrapRecoveryAttempts/$MAX_BOOTSTRAP_RECOVERY_ATTEMPTS name=$name message=$message stack=$stack",
                    )
                    if (bootstrapRecoveryAttempts >= MAX_BOOTSTRAP_RECOVERY_ATTEMPTS) {
                        fail(IllegalStateException("R17_BOOTSTRAP_INSTALL_FAILED attempts=$bootstrapRecoveryAttempts last=$lastBootstrapInstallError"))
                    }
                }
            }
        }
    }

    private fun bootstrapProbeScript(): String =
        "(function(){try{var r=window.__AIS_R17_PRODUCTION__;return JSON.stringify({ok:!!(r&&r.version),version:r&&String(r.version)||''});}catch(e){return JSON.stringify({ok:false,name:String(e&&e.name||'Error'),message:String(e&&e.message||'')});}})()"

    private fun bootstrapRecoveryScript(): String = """
        (function(){
          try{
            ${AiStudioWebSessionR18LanguageGuard.DOCUMENT_START}
            ${AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START}
            var r=window.__AIS_R17_PRODUCTION__;
            var l=window.__AIS_R183_LANGUAGE__;
            return JSON.stringify({ok:!!(r&&r.version),version:r&&String(r.version)||'',type:typeof r,languageGuard:!!(l&&l.version),languageVersion:l&&String(l.version)||''});
          }catch(e){
            return JSON.stringify({
              ok:false,
              name:String(e&&e.name||'Error'),
              message:String(e&&e.message||''),
              stack:String(e&&e.stack||'').slice(0,1400)
            });
          }
        })()
    """.trimIndent()

    private val healthTick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val current = webView ?: return
            val now = SystemClock.elapsedRealtime()
            val currentUri = runCatching { Uri.parse(current.url.orEmpty()) }.getOrNull()
            val host = currentUri?.host.orEmpty()

            if (host.contains("accounts.google.")) {
                fail(IllegalStateException("AI_STUDIO_AUTH_REQUIRED"))
                return
            }
            if (repairLiveRouteIfNeeded(current, currentUri, now)) {
                main.postDelayed(this, HEALTH_TICK_MS)
                return
            }

            ensureBootstrapInstalled(current, pageGeneration, force = false)
            configureBootstrapIfNeeded()
            requestStates()
            maybeSilenceCarrier(force = false)
            if (!setupDelivered.get() && recoverStartSessionIfNeeded(current, now)) {
                main.postDelayed(this, HEALTH_TICK_MS)
                return
            }

            if (!setupDelivered.get()) {
                val stalledFor = now - lastBootstrapProgressAt.coerceAtLeast(connectingStartedAt)
                val totalFor = now - connectingStartedAt
                if (totalFor > SETUP_HARD_TIMEOUT_MS || stalledFor > SETUP_STALL_TIMEOUT_MS) {
                    val reason = if (totalFor > SETUP_HARD_TIMEOUT_MS) {
                        "AI_STUDIO_LIVE_SETUP_HARD_TIMEOUT"
                    } else {
                        "AI_STUDIO_LIVE_SETUP_STALLED"
                    }
                    fail(IllegalStateException("$reason bootstrapInstalled=$bootstrapInstalled configured=$configured recoveryAttempts=$bootstrapRecoveryAttempts lastInstall=$lastBootstrapInstallError"))
                    return
                }
            }
            if (setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {
                fail(IllegalStateException("AI_STUDIO_LIVE_CARRIER_STALE"))
                return
            }
            main.postDelayed(this, HEALTH_TICK_MS)
        }
    }

    private fun recoverStartSessionIfNeeded(current: WebView, now: Long): Boolean {
        if (serverSetupSeen || setupDelivered.get() || startSessionRecoveryAttempts >= MAX_START_SESSION_RECOVERY_ATTEMPTS) return false
        val bootstrap = runCatching { JSONObject(lastBootstrapState) }.getOrNull() ?: return false
        val timedOut = bootstrap.optInt("startAckTimeouts", 0) > 0 && bootstrap.optString("lastAction") == "start-ack-timeout"
        val noCandidate = bootstrap.optInt("startCandidates", 0) == 0
        if (!timedOut || !noCandidate) return false
        if (lastStartSessionRecoveryAt > 0L && now - lastStartSessionRecoveryAt < START_SESSION_RECOVERY_MIN_INTERVAL_MS) return false
        startSessionRecoveryAttempts += 1
        lastStartSessionRecoveryAt = now
        configured = false
        bootstrapInstalled = false
        bootstrapRecoveryAttempts = 0
        bootstrapRecoveryInFlight = false
        lastBootstrapRecoveryAt = 0L
        lastBootstrapInstallError = ""
        lastBootstrapState = ""
        lastBootstrapSignature = ""
        lastLanguageGuardState = ""
        languageGuardConfigured = false
        serverSetupSeen = false
        markBootstrapProgress("start-session-recovery-$startSessionRecoveryAttempts")
        logger.log(1, "AiStudioLive", "START_SESSION_RECOVERY attempt=$startSessionRecoveryAttempts/$MAX_START_SESSION_RECOVERY_ATTEMPTS reason=ack-timeout-no-start-control model=${targetLiveModel()} operation=$operationMode")
        current.loadUrl(liveRouteUrl())
        return true
    }

    private fun repairLiveRouteIfNeeded(current: WebView, currentUri: Uri?, now: Long): Boolean {
        val host = currentUri?.host.orEmpty()
        if (host != "aistudio.google.com") return false
        val path = currentUri?.path.orEmpty().lowercase()
        if (path == "/live" || path.startsWith("/live/")) return false
        if (now - connectingStartedAt < ROUTE_REPAIR_GRACE_MS) return false
        if (routeRepairAttempts >= MAX_ROUTE_REPAIR_ATTEMPTS) return false
        if (lastRouteRepairAt > 0L && now - lastRouteRepairAt < ROUTE_REPAIR_MIN_INTERVAL_MS) return false

        routeRepairAttempts += 1
        lastRouteRepairAt = now
        configured = false
        bootstrapInstalled = false
        bootstrapRecoveryAttempts = 0
        lastBootstrapRecoveryAt = 0L
        lastBootstrapInstallError = ""
        lastBootstrapState = ""
        lastBootstrapSignature = ""
        lastLanguageGuardState = ""
        languageGuardConfigured = false
        markBootstrapProgress("route-repair-$routeRepairAttempts")
        logger.log(1, "AiStudioLive", "ROUTE_REPAIR attempt=$routeRepairAttempts from=${path.take(100)} to=/live model=${targetLiveModel()}")
        current.loadUrl(liveRouteUrl())
        return true
    }

    private fun configureBootstrapIfNeeded() {
        if (configured || !bootstrapInstalled || closed.get()) return
        val current = webView ?: return
        val language = JSONObject.quote(targetLanguage)
        val transcribe = operationMode == GeminiLiveClient.OperationMode.TRANSCRIBE
        val transcribeJs = if (transcribe) "true" else "false"
        val languageCall = if (transcribe) {
            "null"
        } else {
            "(window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.configure($language):({ok:false,error:'r183-language-not-installed'}))"
        }
        current.evaluateJavascript(
            "JSON.stringify({bootstrap:(window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.configure($language,$transcribeJs):({ok:false,error:'r17-not-installed'})),language:$languageCall})",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            val bootstrap = root?.optJSONObject("bootstrap")
            val languageGuard = root?.optJSONObject("language")
            val bootstrapOk = bootstrap?.optBoolean("ok") == true
            val languageOk = transcribe || languageGuard?.optBoolean("ok") == true
            if (bootstrapOk && languageOk) {
                configured = true
                languageGuardConfigured = !transcribe && languageOk
                lastBootstrapState = bootstrap.toString()
                if (!transcribe && languageGuard != null) lastLanguageGuardState = languageGuard.toString()
                updateBootstrapProgress(bootstrap)
                logger.log(2, "AiStudioBootstrap", "CONFIGURED target=$targetLanguage transcribe=$transcribe model=${targetLiveModel()} languageGuardConfigured=$languageGuardConfigured")
            } else if (decoded.isNotBlank()) {
                logger.log(2, "AiStudioBootstrap", "CONFIG_PENDING bootstrapOk=$bootstrapOk languageOk=$languageOk ${safe(decoded, 1200)}")
            }
        }
    }

    private fun requestStates() {
        val current = webView ?: return
        val js = "JSON.stringify({bootstrap:window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.describe():null,language:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null})"
        current.evaluateJavascript(js) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return@evaluateJavascript
            root.optJSONObject("bootstrap")?.let { bootstrap ->
                bootstrapInstalled = true
                lastBootstrapState = bootstrap.toString()
                updateBootstrapProgress(bootstrap)
            }
            root.optJSONObject("language")?.let { language ->
                lastLanguageGuardState = language.toString()
                if (language.optBoolean("targetLanguageVerified", false)) {
                    markBootstrapProgress("language-verified-${language.optString("lastStrategy", "unknown")}")
                }
            }
            root.optJSONObject("direct")?.let { direct ->
                lastDirectState = direct.toString()
                val requests = direct.optLong("carrierRequests", 0L)
                if (requests > lastCarrierRequests) {
                    lastCarrierRequests = requests
                    lastProgressAt = SystemClock.elapsedRealtime()
                    markBootstrapProgress("carrier-request-$requests")
                    if (requests == 1L || requests % 25L == 0L) {
                        logger.log(3, "AiStudioTransport", "CARRIER requests=$requests frames=${direct.optLong("carrierFrames", 0L)} replaced=${direct.optLong("replacedFrames", 0L)} template=${direct.optBoolean("templateObserved", false)} queue=${direct.optInt("queueDepth", 0)}")
                    }
                }
            }
            root.optJSONObject("output")?.let { output ->
                lastOutputState = output.toString()
                val chunks = output.optLong("browserChunks", 0L)
                if (chunks > lastBrowserChunks) {
                    lastBrowserChunks = chunks
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                if (output.optLong("setupCompleteEvents", 0L) > 0L) serverSetupSeen = true
            }
            maybeDeliverSetup()
        }
    }

    private fun updateBootstrapProgress(bootstrap: JSONObject) {
        val signature = listOf(
            bootstrap.optString("stage"),
            bootstrap.optString("lastBlocker"),
            bootstrap.optBoolean("streamSelected", false),
            bootstrap.optBoolean("modelSeen", false),
            bootstrap.optBoolean("modelVerified", false),
            bootstrap.optBoolean("modelRouteRequested", false),
            bootstrap.optBoolean("targetLanguageVerified", false),
            bootstrap.optBoolean("setupObserved", false),
            bootstrap.optString("lastAction"),
            bootstrap.optInt("streamAttempts", 0),
            bootstrap.optInt("modelAttempts", 0),
            bootstrap.optInt("modelSearchAttempts", 0),
            bootstrap.optInt("languageAttempts", 0),
            bootstrap.optInt("startAttempts", 0),
            bootstrap.optInt("modelGuardRequests", 0),
            bootstrap.optInt("modelRewriteRequests", 0),
        ).joinToString("|")
        if (signature == lastBootstrapSignature) return
        lastBootstrapSignature = signature
        markBootstrapProgress(signature.take(180))
        val r17LanguageVerified = bootstrap.optBoolean("targetLanguageVerified", false)
        val r18LanguageVerified = runCatching {
            JSONObject(lastLanguageGuardState).optBoolean("targetLanguageVerified", false)
        }.getOrDefault(false)
        val effectiveLanguageVerified =
            operationMode == GeminiLiveClient.OperationMode.TRANSCRIBE || r18LanguageVerified
        logger.log(
            2,
            "AiStudioStage",
            "stage=${bootstrap.optString("stage", "?")} blocker=${bootstrap.optString("lastBlocker", "?")} route=${bootstrap.optString("routeKind", "?")} model=${bootstrap.optString("targetModel", targetLiveModel())} modelSeen=${bootstrap.optBoolean("modelSeen", false)} modelVerified=${bootstrap.optBoolean("modelVerified", false)} language=$targetLanguage languageUi=${bootstrap.optBoolean("languageUiSelected", false)} languageVerified=$effectiveLanguageVerified r17LanguageVerified=$r17LanguageVerified r18LanguageVerified=$r18LanguageVerified startScans=${bootstrap.optInt("startScans", 0)} startCandidates=${bootstrap.optInt("startCandidates", 0)} startAttempts=${bootstrap.optInt("startAttempts", 0)} setup=${bootstrap.optBoolean("setupObserved", false)} carrier=${bootstrap.optBoolean("carrierActive", false)} syntheticCarrier=${bootstrap.optBoolean("syntheticCarrier", false)} lastAction=${safe(bootstrap.optString("lastAction", ""), 100)}",
        )
    }

    private fun markBootstrapProgress(reason: String) {
        lastBootstrapProgressAt = SystemClock.elapsedRealtime()
        logger.log(3, "AiStudioLive", "PROGRESS ${safe(reason, 220)}")
    }

    private fun maybeDeliverSetup() {
        if (closed.get() || setupDelivered.get() || !serverSetupSeen) return
        if (operationMode == GeminiLiveClient.OperationMode.TRANSLATE) {
            val language = runCatching { JSONObject(lastLanguageGuardState) }.getOrNull() ?: return
            if (!languageGuardConfigured || !language.optBoolean("targetLanguageVerified", false)) {
                logger.log(2, "AiStudioLanguage", "WAITING_TARGET_LANGUAGE target=$targetLanguage configured=$languageGuardConfigured verified=${language.optBoolean("targetLanguageVerified", false)} strategy=${safe(language.optString("lastStrategy", "none"), 120)} bidiRequests=${language.optLong("bidiRequests", 0L)} setupRequests=${language.optLong("setupRequests", 0L)} translateSetup=${language.optLong("translateSetupRequests", 0L)} fallbackCandidates=${language.optInt("lastFallbackCandidates", 0)}")
                return
            }
        }
        val direct = runCatching { JSONObject(lastDirectState) }.getOrNull() ?: return
        val template = direct.optBoolean("templateObserved", false)
        val carriers = direct.optLong("carrierRequests", 0L)
        if (!template || carriers <= 0L) return
        inputClient?.arm(true)
        main.postDelayed({
            if (closed.get() || setupDelivered.get()) return@postDelayed
            setupDelivered.set(true)
            lastProgressAt = SystemClock.elapsedRealtime()
            logger.log(2, "AiStudioLive", "READY model=${targetLiveModel()} operation=$operationMode target=$targetLanguage carrierRequests=$carriers template=${safe(direct.optString("templateMime"), 100)} hidden=false debugVisible=true isolatedLiveHost=true")
            listener.onSetupComplete()
        }, ARM_SETTLE_MS)
    }

    private fun setCarrierActive(enabled: Boolean) {
        if (closed.get() || carrierEnabled == enabled) return
        carrierEnabled = enabled
        logger.log(3, "AiStudioTransport", "CARRIER_ACTIVE enabled=$enabled")
        val current = webView ?: return
        current.post {
            if (closed.get()) return@post
            current.evaluateJavascript(
                "JSON.stringify(window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.setCarrierActive(${if (enabled) "true" else "false"}):({ok:false}))",
                null,
            )
        }
    }

    private fun maybeSilenceCarrier(force: Boolean) {
        if (!carrierEnabled) return
        val idle = SystemClock.elapsedRealtime() - lastInputAt
        val stats = inputClient?.stats()
        val noLocalQueue = (stats?.localQueueFrames ?: 0) == 0
        val directQueue = runCatching { JSONObject(lastDirectState).optInt("queueDepth", 0) }.getOrDefault(0)
        if ((force || idle >= INPUT_IDLE_TO_SILENCE_MS) && noLocalQueue && directQueue == 0) setCarrierActive(false)
    }

    private fun estimatedQueuedWireBytes(): Long {
        val local = inputClient?.stats()?.localQueueFrames ?: 0
        val direct = runCatching { JSONObject(lastDirectState).optInt("queueDepth", 0) }.getOrDefault(0)
        return ((local + direct).toLong() * AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES * 4L / 3L)
            .coerceAtMost(maxQueuedWireBytes.coerceAtLeast(64L * 1024L))
    }

    private fun updateBackpressureHighWater() = updateHighWater(estimatedQueuedWireBytes())

    private fun updateHighWater(value: Long) {
        var previous = maxObservedQueuedBytes.get()
        while (value > previous && !maxObservedQueuedBytes.compareAndSet(previous, value)) {
            previous = maxObservedQueuedBytes.get()
        }
    }

    private fun targetLiveModel(): String = when (operationMode) {
        GeminiLiveClient.OperationMode.TRANSLATE -> AiStudioWebSessionR17ProductionBootstrap.TRANSLATE_MODEL
        GeminiLiveClient.OperationMode.TRANSCRIBE -> AiStudioWebSessionR17ProductionBootstrap.TRANSCRIBE_MODEL
    }

    private fun liveRouteUrl(): String = "$AI_STUDIO_LIVE?model=${Uri.encode(targetLiveModel())}"

    private fun fail(error: Throwable) {
        if (closed.get() || !terminalDelivered.compareAndSet(false, true)) return
        logger.log(
            0,
            "AiStudioLive",
            "FAIL hidden=false debugVisible=true isolatedLiveHost=true setup=${setupDelivered.get()} operation=$operationMode model=${targetLiveModel()} target=$targetLanguage routeRepairs=$routeRepairAttempts bootstrapInstalled=$bootstrapInstalled configured=$configured languageGuardConfigured=$languageGuardConfigured bootstrapRecoveries=$bootstrapRecoveryAttempts lastBootstrapInstallError=${safe(lastBootstrapInstallError, 600)} bootstrap=${safe(lastBootstrapState, 2400)} language=${safe(lastLanguageGuardState, 2200)} direct=${safe(lastDirectState, 1800)} output=${safe(lastOutputState, 1800)}",
            error,
        )
        listener.onError(error)
    }

    private inner class DiagnosticBridge {
        @JavascriptInterface
        fun onJsEvent(json: String?) {
            if (closed.get()) return
            val text = json.orEmpty()
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            val kind = parsed?.optString("kind").orEmpty()
            when {
                kind.startsWith("R17_") -> logger.log(3, "AiStudioBootstrap", "JS_$kind ${safe(text, 2800)}")
                kind.startsWith("R183_") -> logger.log(if (kind.contains("ERROR")) 1 else 2, "AiStudioLanguage", "JS_$kind ${safe(text, 2800)}")
                kind == "R14_AUDIO_TEMPLATE_CAPTURED" ||
                    kind == "R14_INJECT_HTTP_2XX" ||
                    kind == "R14_INJECT_HTTP_ERROR" ||
                    kind == "R14_INJECT_ZERO_STATUS_END" ->
                    logger.log(if (kind.contains("ERROR")) 1 else 3, "AiStudioTransport", "JS_$kind ${safe(text, 1800)}")
                kind == "R16_CHUNK_PARSE_ERROR" || kind == "R16_OUTPUT_BRIDGE_ERROR" ->
                    logger.log(1, "AiStudioOutput", "JS_$kind ${safe(text, 1800)}")
            }
        }
    }

    private fun parseObject(raw: String?): JSONObject? =
        runCatching { JSONObject(decodeEvalValue(raw)) }.getOrNull()

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun hostOf(url: String?): String =
        runCatching { Uri.parse(url.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")

    private fun pathOf(url: String?): String =
        runCatching { Uri.parse(url.orEmpty()).path.orEmpty() }.getOrDefault("")

    private fun safe(value: String, max: Int): String =
        value.replace('\u0000', ' ').replace('\n', ' ').replace('\r', ' ').take(max)

    companion object {
        const val VERSION = "2026-09-05-production-ai-studio-live-r7-fast-start-recovery-debug"
        private const val DIAGNOSTIC_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val NATIVE_TAP_BRIDGE_NAME = "AIStudioNativeTapBridge"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_LIVE = "https://aistudio.google.com/live"
        private const val HEALTH_TICK_MS = 650L
        private const val SETUP_STALL_TIMEOUT_MS = 20_000L
        private const val SETUP_HARD_TIMEOUT_MS = 120_000L
        private const val LIVE_STALE_TIMEOUT_MS = 12_000L
        private const val ARM_SETTLE_MS = 180L
        private const val INPUT_IDLE_TO_SILENCE_MS = 650L
        private const val STREAM_END_CARRIER_GRACE_MS = 900L
        private const val ROUTE_REPAIR_GRACE_MS = 2_500L
        private const val ROUTE_REPAIR_MIN_INTERVAL_MS = 3_000L
        private const val MAX_ROUTE_REPAIR_ATTEMPTS = 2
        private const val MAX_START_SESSION_RECOVERY_ATTEMPTS = 2
        private const val START_SESSION_RECOVERY_MIN_INTERVAL_MS = 10_000L
        private const val BOOTSTRAP_RECOVERY_MIN_INTERVAL_MS = 1_250L
        private const val MAX_BOOTSTRAP_RECOVERY_ATTEMPTS = 5
        private val BOOTSTRAP_RECOVERY_DELAYS_MS = longArrayOf(900L, 2_200L, 4_500L, 7_500L, 11_500L)
    }
}
