package com.oai.geminilivetranslate.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
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
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
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
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR19ScreenVideoBridge
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR20ForensicDiagnostics
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR21DesktopShareScreenExperiment
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
    private val screenDescription: Boolean = false,
    private val screenDescriptionModel: String = AiStudioWebSessionR17ProductionBootstrap.SCREEN_DESCRIPTION_MODEL,
    private val screenDescriptionPrompt: String? = null,
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
    private val screenFrameCalls = AtomicLong(0L)
    private val screenFrameNotReady = AtomicLong(0L)
    private val screenFramePosted = AtomicLong(0L)
    private val screenFrameJsCallbacks = AtomicLong(0L)
    private val screenKickoffArmed = AtomicBoolean(false)
    private val screenKickoffInjected = AtomicBoolean(false)
    private val screenKickoffAttempts = AtomicLong(0L)
    private val screenAudioBeforeSetupDrops = AtomicLong(0L)

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
    @Volatile private var lastScreenVideoState = ""
    @Volatile private var lastScreenFramesDrawn = 0L
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
    @Volatile private var lastHealthTickAt = 0L
    @Volatile private var backgroundDeferredMs = 0L
    @Volatile private var healthWasBackground = false
    @Volatile private var lastScreenSetupGateSignature = ""
    @Volatile private var lastScreenSetupGateAt = 0L

    fun connect() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        connectingStartedAt = now
        lastBootstrapProgressAt = now
        lastHealthTickAt = now
        backgroundDeferredMs = 0L
        healthWasBackground = false
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

    fun sendVideoFrame(jpeg: ByteArray, diagnosticFrameId: Long = 0L): GeminiLiveClient.SendResult {
        val call = screenFrameCalls.incrementAndGet()
        val frameSeq = diagnosticFrameId.takeIf { it > 0L } ?: call
        val trace = call <= 10L || call % 30L == 0L
        if (trace) {
            logger.log(
                3,
                "AiStudioScreenVideo",
                "FRAME_CAUSAL id=$frameSeq call=$call stage=client-entry jpegBytes=${jpeg.size} " +
                    "screenDescription=$screenDescription closed=${closed.get()} setupDelivered=${setupDelivered.get()} " +
                    "serverSetupSeen=$serverSetupSeen webView=${webView != null}",
            )
        }
        if (!screenDescription) {
            logger.log(0, "AiStudioScreenVideo", "FRAME_CAUSAL id=$frameSeq stage=client-reject reason=screen-description-disabled")
            return GeminiLiveClient.SendResult.FAILED
        }
        if (jpeg.isEmpty()) {
            logger.log(1, "AiStudioScreenVideo", "FRAME_CAUSAL id=$frameSeq stage=client-reject reason=empty-jpeg")
            return GeminiLiveClient.SendResult.SENT
        }
        if (closed.get()) {
            logger.log(1, "AiStudioScreenVideo", "FRAME_CAUSAL id=$frameSeq stage=client-reject reason=client-closed")
            return GeminiLiveClient.SendResult.CLOSED
        }
        if (!setupDelivered.get()) {
            val count = screenFrameNotReady.incrementAndGet()
            logger.log(
                1,
                "AiStudioScreenVideo",
                "FRAME_CAUSAL id=$frameSeq call=$call stage=client-gate reason=setup-not-delivered notReadyCount=$count " +
                    "serverSetupSeen=$serverSetupSeen bootstrap=${safe(lastBootstrapState, 700)} " +
                    "direct=${safe(lastDirectState, 700)} screen=${safe(lastScreenVideoState, 900)}",
            )
            return GeminiLiveClient.SendResult.NOT_READY
        }
        val current = webView
        if (current == null) {
            val count = screenFrameNotReady.incrementAndGet()
            logger.log(
                1,
                "AiStudioScreenVideo",
                "FRAME_CAUSAL id=$frameSeq call=$call stage=client-gate reason=webview-null notReadyCount=$count setupDelivered=true",
            )
            return GeminiLiveClient.SendResult.NOT_READY
        }
        lastInputAt = SystemClock.elapsedRealtime()
        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        if (encoded.length > SCREEN_DESCRIPTION_MAX_BASE64_CHARS) {
            backpressureEvents.incrementAndGet()
            logger.log(
                1,
                "AiStudioScreenVideo",
                "FRAME_CAUSAL id=$frameSeq stage=client-reject reason=too-large jpegBytes=${jpeg.size} " +
                    "base64Chars=${encoded.length} limit=$SCREEN_DESCRIPTION_MAX_BASE64_CHARS",
            )
            return GeminiLiveClient.SendResult.BACKPRESSURED
        }
        val quoted = JSONObject.quote(encoded)
        val posted = screenFramePosted.incrementAndGet()
        if (trace) {
            logger.log(
                3,
                "AiStudioScreenVideo",
                "FRAME_CAUSAL id=$frameSeq call=$call stage=post-to-webview posted=$posted jpegBytes=${jpeg.size} " +
                    "base64Chars=${encoded.length}",
            )
        }
        current.post {
            if (closed.get()) {
                logger.log(1, "AiStudioScreenVideo", "FRAME_CAUSAL id=$frameSeq stage=webview-post-abort reason=client-closed")
                return@post
            }
            if (trace) {
                logger.log(3, "AiStudioScreenVideo", "FRAME_CAUSAL id=$frameSeq stage=js-eval-begin r19InstalledExpected=true")
            }
            current.evaluateJavascript(
                "JSON.stringify(window.__AIS_R19_SCREEN_VIDEO__?window.__AIS_R19_SCREEN_VIDEO__.pushJpeg($quoted,$frameSeq):({ok:false,error:'r19-not-installed',nativeSeq:$frameSeq}))",
            ) { raw ->
                val callbackCount = screenFrameJsCallbacks.incrementAndGet()
                val decoded = decodeEvalValue(raw)
                val frameDrawn = !decoded.contains("\"ok\":false") && decoded.contains("\"framesDrawn\":")
                logger.log(
                    if (decoded.contains("\"ok\":false")) 1 else 3,
                    "AiStudioScreenVideo",
                    "FRAME_CAUSAL id=$frameSeq stage=js-eval-result callbacks=$callbackCount jpegBytes=${jpeg.size} " +
                        "result=${safe(decoded, 1800)}",
                )
                if (frameDrawn && screenDescription && setupDelivered.get() && !screenKickoffInjected.get()) {
                    queueInitialScreenKickoff(current, frameSeq)
                }
            }
        }
        return GeminiLiveClient.SendResult.SENT
    }

    private fun buildScreenKickoffText(): String {
        val base = screenDescriptionPrompt
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: GeminiScreenDescriptionLiveClient.systemInstruction(
                if (targetLanguage.equals("vi", ignoreCase = true)) "tiếng Việt" else targetLanguage,
            )
        val heartbeat = GeminiScreenDescriptionLiveClient.HEARTBEAT_TEXT.trim()
        val baseBudget = (SCREEN_KICKOFF_MAX_CHARS - heartbeat.length - 2).coerceAtLeast(0)
        return (base.take(baseBudget) + "\n\n" + heartbeat)
            .trim()
            .take(SCREEN_KICKOFF_MAX_CHARS)
    }

    private fun queueInitialScreenKickoff(current: WebView, frameSeq: Long) {
        if (!screenDescription || closed.get() || !setupDelivered.get() || screenKickoffInjected.get()) return
        if (!screenKickoffArmed.compareAndSet(false, true)) return
        val attempt = screenKickoffAttempts.incrementAndGet()
        logger.log(
            2,
            "AiStudioScreenVideo",
            "SCREEN_KICKOFF_ARM attempt=$attempt frame=$frameSeq setupDelivered=${setupDelivered.get()} " +
                "serverSetupSeen=$serverSetupSeen frameCallbacks=${screenFrameJsCallbacks.get()}",
        )
        current.evaluateJavascript(
            "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__&&typeof window.__AIS_LIVE_DIRECT_ENGINE__.queueScreenHeartbeat==='function'?window.__AIS_LIVE_DIRECT_ENGINE__.queueScreenHeartbeat():({ok:false,error:'r14-kickoff-unavailable'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            val result = runCatching { JSONObject(decoded) }.getOrNull()
            val ok = result?.optBoolean("ok", false) == true
            if (ok) {
                screenKickoffInjected.set(true)
                logger.log(
                    2,
                    "AiStudioScreenVideo",
                    "SCREEN_KICKOFF_QUEUED attempt=$attempt frame=$frameSeq pending=${result?.optBoolean("pending", false)} " +
                        "inFlight=${result?.optBoolean("inFlight", false)} queued=${result?.optLong("queued", 0L)} " +
                        "injects=${result?.optLong("injects", 0L)}",
                )
            } else {
                screenKickoffArmed.set(false)
                logger.log(
                    1,
                    "AiStudioScreenVideo",
                    "SCREEN_KICKOFF_RETRY attempt=$attempt frame=$frameSeq result=${safe(decoded, 1200)}",
                )
            }
        }
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
            "CLOSE hidden=false debugVisible=true graceful=$graceful setup=${setupDelivered.get()} server=${stats.serverContentEvents} inputText=${stats.inputTranscriptEvents} outputText=${stats.outputTextEvents} modelText=${stats.modelTextEvents} audioChunks=${stats.audioChunks} audioBytes=${stats.audioBytes} turns=${stats.turnCompleteEvents} backpressure=${backpressureEvents.get()} bootstrapRecoveries=$bootstrapRecoveryAttempts screenFrameCalls=${screenFrameCalls.get()} screenFrameNotReady=${screenFrameNotReady.get()} screenFramePosted=${screenFramePosted.get()} screenJsCallbacks=${screenFrameJsCallbacks.get()} kickoffAttempts=${screenKickoffAttempts.get()} kickoffQueued=${screenKickoffInjected.get()} audioBeforeSetupDrops=${screenAudioBeforeSetupDrops.get()}",
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
        AiStudioDebugWebViewHost.attach(
            created,
            logger,
            desktopShareExperiment = screenDescription,
        )

        if (screenDescription) {
            WebViewCompat.addDocumentStartJavaScript(
                created,
                AiStudioWebSessionR21DesktopShareScreenExperiment.DOCUMENT_START,
                setOf(AI_STUDIO_ORIGIN),
            )
        }
        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioNativeTapDocumentStart.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        if (screenDescription) {
            WebViewCompat.addDocumentStartJavaScript(
                created,
                AiStudioWebSessionR20ForensicDiagnostics.DOCUMENT_START,
                setOf(AI_STUDIO_ORIGIN),
            )
        }
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
        WebViewCompat.addDocumentStartJavaScript(
            created,
            AiStudioWebSessionR19ScreenVideoBridge.DOCUMENT_START,
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
                    if (closed.get()) return
                    if (!setupDelivered.get()) {
                        val dropped = screenAudioBeforeSetupDrops.incrementAndGet()
                        if (dropped == 1L || dropped % 25L == 0L) {
                            logger.log(
                                1,
                                "AiStudioOutput",
                                "AUDIO_DROP_BEFORE_SETUP count=$dropped bytes=${pcm24kMono.size} mime=${safe(mimeType, 100)} " +
                                    "screenDescription=$screenDescription serverSetupSeen=$serverSetupSeen",
                            )
                        }
                        return
                    }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.settings.offscreenPreRaster = true
        }
        logger.log(
            2,
            "AiStudioLive",
            "R38_LIVE_WEBVIEW_BACKGROUND_POLICY rendererPriority=important waiveWhenNotVisible=false offscreenPreRaster=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.M}",
        )
        view.settings.apply {
            javaScriptEnabled = true
            if (screenDescription) {
                val originalUa = userAgentString.orEmpty()
                userAgentString = DESKTOP_SHARE_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
                var uaMetadata = "unsupported"
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    uaMetadata = runCatching {
                        val chromium = UserAgentMetadata.BrandVersion.Builder()
                            .setBrand("Chromium")
                            .setMajorVersion("138")
                            .setFullVersion("138.0.7204.179")
                            .build()
                        val builder = UserAgentMetadata.Builder()
                            .setBrandVersionList(listOf(chromium))
                            .setFullVersion("138.0.7204.179")
                            .setPlatform("Linux")
                            .setPlatformVersion("6.0.0")
                            .setArchitecture("x86")
                            .setMobile(false)
                            .setModel(null)
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
                            builder.setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
                        }
                        WebSettingsCompat.setUserAgentMetadata(this, builder.build())
                        "desktop-linux"
                    }.getOrElse { "error:${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
                }
                logger.log(
                    2,
                    "AiStudioDesktopShare",
                    "DESKTOP_WEBVIEW_MODE enabled=true originalUa=${safe(originalUa, 260)} " +
                        "desktopUa=${safe(userAgentString.orEmpty(), 260)} wideViewport=$useWideViewPort overview=$loadWithOverviewMode " +
                        "uaMetadata=$uaMetadata",
                )
            }
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
                val allowRealMic = !screenDescription
                if (audioOnly && granted && allowRealMic) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    logger.log(2, "AiStudioAuthMedia", "WEB_PERMISSION audio=true video=false androidMic=true realMicAllowed=true result=granted")
                } else {
                    req.deny()
                    logger.log(
                        if (screenDescription && audioOnly) 2 else 1,
                        "AiStudioAuthMedia",
                        "WEB_PERMISSION audioOnly=$audioOnly androidMic=$granted realMicAllowed=$allowRealMic screenDescription=$screenDescription result=denied",
                    )
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

    private fun shiftLiveControlClocks(deferredMs: Long) {
        if (deferredMs <= 0L) return
        connectingStartedAt += deferredMs
        lastBootstrapProgressAt += deferredMs
        if (lastProgressAt > 0L) lastProgressAt += deferredMs
        if (lastRouteRepairAt > 0L) lastRouteRepairAt += deferredMs
        if (lastBootstrapRecoveryAt > 0L) lastBootstrapRecoveryAt += deferredMs
        if (lastStartSessionRecoveryAt > 0L) lastStartSessionRecoveryAt += deferredMs
    }

    private val healthTick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val current = webView ?: return
            val now = SystemClock.elapsedRealtime()
            val rawHealthGap = (now - lastHealthTickAt).coerceAtLeast(0L)
            lastHealthTickAt = now
            val appBackground = GeminiTranslateApp.currentActivity() == null
            var suppressTimeouts = false
            when {
                appBackground -> {
                    shiftLiveControlClocks(rawHealthGap)
                    backgroundDeferredMs += rawHealthGap
                    if (!healthWasBackground) {
                        logger.log(2, "AiStudioLive", "R38_LIVE_BACKGROUND_DEFER state=enter operation=$operationMode")
                    }
                    healthWasBackground = true
                    suppressTimeouts = true
                    runCatching { current.onResume() }
                    runCatching { current.resumeTimers() }
                }
                healthWasBackground -> {
                    shiftLiveControlClocks(rawHealthGap)
                    backgroundDeferredMs += rawHealthGap
                    healthWasBackground = false
                    suppressTimeouts = true
                    logger.log(2, "AiStudioLive", "R38_LIVE_BACKGROUND_DEFER state=exit deferredMs=$backgroundDeferredMs operation=$operationMode")
                    runCatching { current.onResume() }
                    runCatching { current.resumeTimers() }
                }
                rawHealthGap >= HEALTH_SCHEDULER_GAP_MS -> {
                    val deferred = (rawHealthGap - HEALTH_TICK_MS).coerceAtLeast(0L)
                    shiftLiveControlClocks(deferred)
                    suppressTimeouts = true
                    logger.log(1, "AiStudioLive", "R38_LIVE_SCHEDULER_GAP gapMs=$rawHealthGap deferredMs=$deferred operation=$operationMode")
                    runCatching { current.onResume() }
                    runCatching { current.resumeTimers() }
                }
            }
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

            if (!suppressTimeouts && !setupDelivered.get()) {
                val stalledFor = now - lastBootstrapProgressAt.coerceAtLeast(connectingStartedAt)
                val totalFor = now - connectingStartedAt
                if (totalFor > SETUP_HARD_TIMEOUT_MS || stalledFor > SETUP_STALL_TIMEOUT_MS) {
                    val reason = if (totalFor > SETUP_HARD_TIMEOUT_MS) {
                        "AI_STUDIO_LIVE_SETUP_HARD_TIMEOUT"
                    } else {
                        "AI_STUDIO_LIVE_SETUP_STALLED"
                    }
                    logger.log(
                        0,
                        "AiStudioScreenVideo",
                        "SETUP_TIMEOUT_FORENSIC reason=$reason totalMs=$totalFor stalledMs=$stalledFor " +
                            "bootstrapInstalled=$bootstrapInstalled configured=$configured serverSetupSeen=$serverSetupSeen " +
                            "setupDelivered=${setupDelivered.get()} frameCalls=${screenFrameCalls.get()} " +
                            "frameNotReady=${screenFrameNotReady.get()} framePosted=${screenFramePosted.get()} " +
                            "jsCallbacks=${screenFrameJsCallbacks.get()} recoveryAttempts=$bootstrapRecoveryAttempts " +
                            "lastInstall=${safe(lastBootstrapInstallError, 400)} " +
                            "bootstrap=${safe(lastBootstrapState, 2400)} direct=${safe(lastDirectState, 2400)} " +
                            "screen=${safe(lastScreenVideoState, 3200)} output=${safe(lastOutputState, 2400)} " +
                            "language=${safe(lastLanguageGuardState, 1800)}",
                    )
                    fail(IllegalStateException("$reason bootstrapInstalled=$bootstrapInstalled configured=$configured recoveryAttempts=$bootstrapRecoveryAttempts lastInstall=$lastBootstrapInstallError"))
                    return
                }
            }
            if (!screenDescription && !suppressTimeouts && setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {
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
        val screenJs = if (screenDescription) "true" else "false"
        val requestedModel = JSONObject.quote(targetLiveModel())
        val requestedPrompt = JSONObject.quote(screenDescriptionPrompt.orEmpty())
        val kickoffText = buildScreenKickoffText()
        val screenHeartbeat = JSONObject.quote(kickoffText)
        val languageCall = if (transcribe || screenDescription) {
            "null"
        } else {
            "(window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.configure($language):({ok:false,error:'r183-language-not-installed'}))"
        }
        val screenCall = if (screenDescription) {
            "(window.__AIS_R19_SCREEN_VIDEO__?window.__AIS_R19_SCREEN_VIDEO__.configure(true,false):({ok:false,error:'r19-not-installed'}))"
        } else {
            "null"
        }
        val directScreenCall = if (screenDescription) {
            "(window.__AIS_LIVE_DIRECT_ENGINE__&&typeof window.__AIS_LIVE_DIRECT_ENGINE__.configureScreenHeartbeat==='function'?window.__AIS_LIVE_DIRECT_ENGINE__.configureScreenHeartbeat(true,$screenHeartbeat):({ok:false,error:'r14-screen-heartbeat-not-installed'}))"
        } else {
            "null"
        }
        current.evaluateJavascript(
            "JSON.stringify({screen:$screenCall,directScreen:$directScreenCall,bootstrap:(window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.configure($language,$transcribeJs,false,$requestedModel,$screenJs,$requestedPrompt):({ok:false,error:'r17-not-installed'})),language:$languageCall})",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            val bootstrap = root?.optJSONObject("bootstrap")
            val languageGuard = root?.optJSONObject("language")
            val screenBridge = root?.optJSONObject("screen")
            val directScreen = root?.optJSONObject("directScreen")
            val bootstrapOk = bootstrap?.optBoolean("ok") == true
            val languageOk = transcribe || screenDescription || languageGuard?.optBoolean("ok") == true
            val screenOk = !screenDescription || screenBridge?.optBoolean("ok") == true
            val directScreenOk = !screenDescription || directScreen?.optBoolean("ok") == true
            if (bootstrapOk && languageOk && screenOk && directScreenOk) {
                configured = true
                languageGuardConfigured = !transcribe && !screenDescription && languageOk
                lastBootstrapState = bootstrap.toString()
                if (!transcribe && languageGuard != null) lastLanguageGuardState = languageGuard.toString()
                if (screenBridge != null) lastScreenVideoState = screenBridge.toString()
                if (directScreen != null) lastDirectState = directScreen.toString()
                updateBootstrapProgress(bootstrap)
                logger.log(
                    2,
                    "AiStudioBootstrap",
                    "CONFIGURED target=$targetLanguage transcribe=$transcribe screenDescription=$screenDescription model=${targetLiveModel()} " +
                        "languageGuardConfigured=$languageGuardConfigured screenHeartbeat=${directScreen?.optBoolean("screenHeartbeatEnabled", false) == true} " +
                        "kickoffMode=${if (screenDescription) "first-frame-once" else "disabled"} kickoffChars=${kickoffText.length}",
                )
            } else if (decoded.isNotBlank()) {
                logger.log(2, "AiStudioBootstrap", "CONFIG_PENDING bootstrapOk=$bootstrapOk languageOk=$languageOk screenOk=$screenOk directScreenOk=$directScreenOk ${safe(decoded, 1200)}")
            }
        }
    }

    private fun requestStates() {
        val current = webView ?: return
        val js = "JSON.stringify({desktop:window.__AIS_R21_DESKTOP_SHARE__?window.__AIS_R21_DESKTOP_SHARE__.describe():null,bootstrap:window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.describe():null,language:window.__AIS_R183_LANGUAGE__?window.__AIS_R183_LANGUAGE__.describe():null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,screen:window.__AIS_R19_SCREEN_VIDEO__?window.__AIS_R19_SCREEN_VIDEO__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null})"
        current.evaluateJavascript(js) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return@evaluateJavascript
            root.optJSONObject("desktop")?.let { desktop ->
                logger.log(3, "AiStudioDesktopShare", "STATE ${safe(desktop.toString(), 1800)}")
            }
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
            root.optJSONObject("screen")?.let { screen ->
                lastScreenVideoState = screen.toString()
                val drawn = screen.optLong("framesDrawn", 0L)
                if (drawn > lastScreenFramesDrawn) {
                    lastScreenFramesDrawn = drawn
                    if (drawn == 1L || drawn % 20L == 0L) {
                        logger.log(3, "AiStudioScreenVideo", "STATE framesDrawn=$drawn gumVideoRequests=${screen.optLong("gumVideoRequests", 0L)} displayRequests=${screen.optLong("displayRequests", 0L)} cameraClicks=${screen.optInt("cameraClicks", 0)}")
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
        if (closed.get()) {
            logScreenSetupGate("client-closed", "setupDelivered=${setupDelivered.get()} serverSetupSeen=$serverSetupSeen")
            return
        }
        if (setupDelivered.get()) return
        if (!serverSetupSeen) {
            logScreenSetupGate(
                "server-setup-not-seen",
                "screenDescription=$screenDescription frameCalls=${screenFrameCalls.get()} framePosted=${screenFramePosted.get()} " +
                    "bootstrap=${safe(lastBootstrapState, 600)} direct=${safe(lastDirectState, 600)} screen=${safe(lastScreenVideoState, 800)}",
            )
            return
        }
        if (!screenDescription && operationMode == GeminiLiveClient.OperationMode.TRANSLATE) {
            val language = runCatching { JSONObject(lastLanguageGuardState) }.getOrNull() ?: return
            if (!languageGuardConfigured || !language.optBoolean("targetLanguageVerified", false)) {
                logger.log(2, "AiStudioLanguage", "WAITING_TARGET_LANGUAGE target=$targetLanguage configured=$languageGuardConfigured verified=${language.optBoolean("targetLanguageVerified", false)} strategy=${safe(language.optString("lastStrategy", "none"), 120)} bidiRequests=${language.optLong("bidiRequests", 0L)} setupRequests=${language.optLong("setupRequests", 0L)} translateSetup=${language.optLong("translateSetupRequests", 0L)} fallbackCandidates=${language.optInt("lastFallbackCandidates", 0)}")
                return
            }
        }
        if (screenDescription) {
            val screen = runCatching { JSONObject(lastScreenVideoState) }.getOrNull()
            if (screen == null) {
                logScreenSetupGate(
                    "desktop-share-state-missing",
                    "serverSetupSeen=$serverSetupSeen frameCalls=${screenFrameCalls.get()} direct=${safe(lastDirectState, 700)}",
                )
                return
            }
            val videoReady = screen.optBoolean("videoTrackReady", false)
            val transportReady = screen.optBoolean("cameraTransportReady", false)
            val displayRequests = screen.optLong("displayRequests", 0L)
            val displayStreamsReturned = screen.optLong("displayStreamsReturned", 0L)
            val displayHookInstalled = screen.optBoolean("displayHookInstalled", false)
            if (!displayHookInstalled || !videoReady || !transportReady || displayRequests <= 0L || displayStreamsReturned <= 0L) {
                logScreenSetupGate(
                    "WAITING_DESKTOP_SHARE_STREAM",
                    "hookInstalled=$displayHookInstalled videoTrackReady=$videoReady transportReady=$transportReady " +
                        "displayRequests=$displayRequests displayStreamsReturned=$displayStreamsReturned " +
                        "displayNativeAvailable=${screen.optBoolean("displayNativeAvailable", false)} " +
                        "framesQueued=${screen.optLong("framesQueued", 0L)} framesDrawn=${screen.optLong("framesDrawn", 0L)} " +
                        "lastDisplayRequestAgeMs=${screen.optLong("lastDisplayRequestAgeMs", -1L)} " +
                        "lastDisplayReturnAgeMs=${screen.optLong("lastDisplayReturnAgeMs", -1L)} " +
                        "nativeFrameCalls=${screenFrameCalls.get()} nativeFrameNotReady=${screenFrameNotReady.get()}",
                )
                return
            }
            logScreenSetupGate(
                "desktop-share-setup-gates-passed",
                "videoTrackReady=$videoReady transportReady=$transportReady displayRequests=$displayRequests " +
                    "displayStreamsReturned=$displayStreamsReturned framesQueued=${screen.optLong("framesQueued", 0L)} " +
                    "framesDrawn=${screen.optLong("framesDrawn", 0L)} armSettleMs=$ARM_SETTLE_MS",
                force = true,
            )
            main.postDelayed({
                if (closed.get() || setupDelivered.get()) return@postDelayed
                setupDelivered.set(true)
                lastProgressAt = SystemClock.elapsedRealtime()
                logger.log(
                    2,
                    "AiStudioLive",
                    "READY model=${targetLiveModel()} operation=$operationMode target=$targetLanguage " +
                        "transport=desktop-share-screen-experiment videoTrackReady=true transportReady=true " +
                        "displayRequests=$displayRequests displayStreamsReturned=$displayStreamsReturned " +
                        "hidden=false debugVisible=true isolatedLiveHost=true",
                )
                listener.onSetupComplete()
            }, ARM_SETTLE_MS)
            return
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

    private fun logScreenSetupGate(reason: String, detail: String, force: Boolean = false) {
        if (!screenDescription) return
        val now = SystemClock.elapsedRealtime()
        val signature = "$reason|$detail"
        if (!force && signature == lastScreenSetupGateSignature && now - lastScreenSetupGateAt < SCREEN_SETUP_GATE_LOG_INTERVAL_MS) {
            return
        }
        lastScreenSetupGateSignature = signature
        lastScreenSetupGateAt = now
        logger.log(
            2,
            "AiStudioScreenVideo",
            "SETUP_GATE reason=$reason setupDelivered=${setupDelivered.get()} serverSetupSeen=$serverSetupSeen " +
                "frameCalls=${screenFrameCalls.get()} frameNotReady=${screenFrameNotReady.get()} " +
                "framePosted=${screenFramePosted.get()} jsCallbacks=${screenFrameJsCallbacks.get()} $detail",
        )
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

    private fun targetLiveModel(): String = if (screenDescription) {
        screenDescriptionModel
    } else {
        when (operationMode) {
            GeminiLiveClient.OperationMode.TRANSLATE -> AiStudioWebSessionR17ProductionBootstrap.TRANSLATE_MODEL
            GeminiLiveClient.OperationMode.TRANSCRIBE -> AiStudioWebSessionR17ProductionBootstrap.TRANSCRIBE_MODEL
        }
    }

    private fun liveRouteUrl(): String = "$AI_STUDIO_LIVE?model=${Uri.encode(targetLiveModel())}"

    private fun fail(error: Throwable) {
        if (closed.get() || !terminalDelivered.compareAndSet(false, true)) return
        logger.log(
            0,
            "AiStudioLive",
            "FAIL hidden=false debugVisible=true isolatedLiveHost=true setup=${setupDelivered.get()} operation=$operationMode model=${targetLiveModel()} target=$targetLanguage routeRepairs=$routeRepairAttempts bootstrapInstalled=$bootstrapInstalled configured=$configured languageGuardConfigured=$languageGuardConfigured bootstrapRecoveries=$bootstrapRecoveryAttempts lastBootstrapInstallError=${safe(lastBootstrapInstallError, 600)} bootstrap=${safe(lastBootstrapState, 2400)} language=${safe(lastLanguageGuardState, 2200)} direct=${safe(lastDirectState, 1800)} screen=${safe(lastScreenVideoState, 1800)} output=${safe(lastOutputState, 1800)}",
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
                kind.startsWith("R21_") -> logger.log(if (kind.contains("ERROR")) 1 else 2, "AiStudioDesktopShare", "JS_$kind ${safe(text, 3200)}")
                kind.startsWith("R17_") -> logger.log(3, "AiStudioBootstrap", "JS_$kind ${safe(text, 2800)}")
                kind.startsWith("R183_") -> logger.log(if (kind.contains("ERROR")) 1 else 2, "AiStudioLanguage", "JS_$kind ${safe(text, 2800)}")
                kind.startsWith("R19_") -> logger.log(if (kind.contains("ERROR")) 1 else 2, "AiStudioScreenVideo", "JS_$kind ${safe(text, 2400)}")
                kind == "R14_AUDIO_TEMPLATE_CAPTURED" ||
                    kind == "R14_VIDEO_QUEUE" ||
                    kind == "R14_VIDEO_REPLACED" ||
                    kind == "R14_MEDIA_REPLACED" ||
                    kind == "R14_INJECT_HTTP_2XX" ||
                    kind == "R14_INJECT_HTTP_ERROR" ||
                    kind == "R14_INJECT_ZERO_STATUS_END" ||
                    kind == "R14_SCREEN_HEARTBEAT_TURN_BOUNDARY" ||
                    kind == "R14_SCREEN_HEARTBEAT_INJECTED" ||
                    kind == "R14_SCREEN_TURN_COMPLETE" ||
                    kind == "R14_SCREEN_TURN_STALLED" ->
                    logger.log(if (kind.contains("ERROR") || kind.contains("STALLED")) 1 else 2, "AiStudioTransport", "JS_$kind ${safe(text, 2200)}")
                kind == "R16_AUDIO_OUT" || kind == "R16_TEXT_OUT" ->
                    logger.log(2, "AiStudioOutput", "JS_$kind ${safe(text, 1800)}")
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
        const val VERSION = "2026-09-18-production-ai-studio-live-r9-camera-ready-gate"
        private const val DIAGNOSTIC_BRIDGE_NAME = "AIStudioWebSessionLab"
        private const val NATIVE_TAP_BRIDGE_NAME = "AIStudioNativeTapBridge"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_LIVE = "https://aistudio.google.com/live"
        private const val HEALTH_TICK_MS = 650L
        private const val HEALTH_SCHEDULER_GAP_MS = 5_000L
        private const val SETUP_STALL_TIMEOUT_MS = 20_000L
        private const val SETUP_HARD_TIMEOUT_MS = 120_000L
        private const val LIVE_STALE_TIMEOUT_MS = 12_000L
        private const val ARM_SETTLE_MS = 180L
        private const val INPUT_IDLE_TO_SILENCE_MS = 650L
        private const val STREAM_END_CARRIER_GRACE_MS = 900L
        private const val SCREEN_DESCRIPTION_MAX_BASE64_CHARS = 3_000_000
        private const val SCREEN_KICKOFF_MAX_CHARS = 3_900
        private const val DESKTOP_SHARE_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/138.0.7204.179 Safari/537.36"
        private const val SCREEN_SETUP_GATE_LOG_INTERVAL_MS = 2_500L
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
