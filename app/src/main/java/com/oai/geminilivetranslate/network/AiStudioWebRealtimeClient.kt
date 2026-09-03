package com.oai.geminilivetranslate.network

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.GeminiTranslateApp
import com.oai.geminilivetranslate.core.AiStudioWebLiveClient
import com.oai.geminilivetranslate.core.AiStudioWebLiveOutputBridge
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR14DirectLiveEngine
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR16LiveOutputEngine
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR17ProductionBootstrap
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * R17 production-shaped AI Studio Live backend.
 *
 * Authentication and BrowserChannel credentials remain owned by the WebView page. This client owns
 * only the hidden WebView lifecycle, automatic Live bootstrap, R14 PCM carrier replacement and
 * R16.1 reverse-direction decoding. R17.3 enters the dedicated /live route directly instead of
 * opening Chat and trying to discover a Stream control that may not exist in that DOM.
 */
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

    @Volatile private var executor: AiStudioWebSessionExecutor? = null
    @Volatile private var inputClient: AiStudioWebLiveClient? = null
    @Volatile private var outputBridge: AiStudioWebLiveOutputBridge? = null
    @Volatile private var configured = false
    @Volatile private var serverSetupSeen = false
    @Volatile private var connectingStartedAt = 0L
    @Volatile private var lastInputAt = 0L
    @Volatile private var carrierEnabled = false
    @Volatile private var lastBootstrapState = ""
    @Volatile private var lastDirectState = ""
    @Volatile private var lastOutputState = ""
    @Volatile private var lastCarrierRequests = 0L
    @Volatile private var lastBrowserChunks = 0L
    @Volatile private var lastProgressAt = 0L
    @Volatile private var routeRepairAttempts = 0
    @Volatile private var lastRouteRepairAt = 0L

    fun connect() {
        if (closed.get()) return
        connectingStartedAt = SystemClock.elapsedRealtime()
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
                backpressureEvents.incrementAndGet()
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
        main.post {
            setCarrierActive(false)
            runCatching { inputClient?.clear() }
            runCatching { inputClient?.close() }
            inputClient = null
            runCatching { outputBridge?.close() }
            outputBridge = null
            runCatching { executor?.webView?.stopLoading() }
            runCatching { executor?.webView?.onPause() }
            runCatching { executor?.destroy() }
            executor = null
            main.removeCallbacks(healthTick)
        }
        val stats = responseStats()
        logger.log(
            2,
            "AiStudioLive",
            "Đóng hidden client graceful=$graceful server=${stats.serverContentEvents} inputText=${stats.inputTranscriptEvents} outputText=${stats.outputTextEvents} modelText=${stats.modelTextEvents} audioChunks=${stats.audioChunks} audioBytes=${stats.audioBytes} turns=${stats.turnCompleteEvents}",
        )
    }

    private fun startOnMain() {
        if (closed.get() || executor != null) return
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fail(IllegalStateException("WebView không hỗ trợ DOCUMENT_START_SCRIPT"))
            return
        }
        val created = AiStudioWebSessionExecutor(appContext, object : AiStudioWebSessionExecutor.Events {
            override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
                if (closed.get()) return
                logger.log(3, "AiStudioLive", "executor=$state detail=${safe(detail, 240)}")
                if (state == AiStudioWebSessionExecutor.State.ERROR) {
                    fail(IllegalStateException("AI Studio WebView: $detail"))
                }
            }

            override fun onLog(name: String, detail: String) {
                if (closed.get()) return
                when {
                    name.startsWith("JS_R17_") -> logger.log(3, "AiStudioR17", "$name ${safe(detail, 1800)}")
                    name == "JS_R14_INJECT_HTTP_ERROR" -> logger.log(1, "AiStudioR14", safe(detail, 1200))
                    name == "JS_R16_CHUNK_PARSE_ERROR" || name == "JS_R16_OUTPUT_BRIDGE_ERROR" ->
                        logger.log(1, "AiStudioR16", "$name ${safe(detail, 1200)}")
                }
            }
        })
        executor = created

        WebViewCompat.addDocumentStartJavaScript(
            created.webView,
            AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            created.webView,
            AiStudioWebSessionR16LiveOutputEngine.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )
        WebViewCompat.addDocumentStartJavaScript(
            created.webView,
            AiStudioWebSessionR17ProductionBootstrap.DOCUMENT_START,
            setOf(AI_STUDIO_ORIGIN),
        )

        inputClient = AiStudioWebLiveClient(created.webView) { name, detail ->
            when (name) {
                "R15_CLIENT_BATCH", "R15_CLIENT_ARM", "R15_CLIENT_STREAM_END" ->
                    logger.log(3, "AiStudioInput", "$name ${safe(detail, 1000)}")
                else -> Unit
            }
        }
        outputBridge = AiStudioWebLiveOutputBridge(
            created.webView,
            object : AiStudioWebLiveOutputBridge.Listener {
                override fun onAudio(pcm24kMono: ByteArray, mimeType: String) {
                    if (closed.get() || !setupDelivered.get()) return
                    audioChunks.incrementAndGet()
                    audioBytes.addAndGet(pcm24kMono.size.toLong())
                    responseServerEvents.incrementAndGet()
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
                }

                override fun onSignal(kind: String, value: String) {
                    if (closed.get()) return
                    when (kind) {
                        "setupComplete" -> {
                            serverSetupSeen = true
                            logger.log(2, "AiStudioLive", "Nhận setupComplete; chờ carrier R14 sẵn sàng")
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
        ) { name, detail -> logger.log(3, "AiStudioOutput", "$name ${safe(detail, 1000)}") }

        created.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val resources = req.resources.orEmpty()
                val audioOnly = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    !resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (audioOnly && granted) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    logger.log(2, "AiStudioLive", "Fallback WebView microphone permission granted")
                } else {
                    req.deny()
                    logger.log(1, "AiStudioLive", "WebView permission denied audioOnly=$audioOnly androidMic=$granted")
                }
            }
        }
        runCatching { created.webView.onResume() }
        runCatching { created.webView.resumeTimers() }
        listener.onOpen()
        val liveUrl = liveRouteUrl()
        created.start(liveUrl)
        main.removeCallbacks(healthTick)
        main.postDelayed(healthTick, HEALTH_TICK_MS)
        logger.log(
            2,
            "AiStudioLive",
            "Khởi động hidden WebSession route=live model=${targetLiveModel()} mode=$operationMode target=$targetLanguage",
        )
    }

    private val healthTick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val current = executor ?: return
            val now = SystemClock.elapsedRealtime()
            val currentUri = runCatching { Uri.parse(current.webView.url.orEmpty()) }.getOrNull()
            val host = currentUri?.host.orEmpty()
            if (host.contains("accounts.google.") || host == "accounts.google.com") {
                fail(IllegalStateException("AI_STUDIO_AUTH_REQUIRED"))
                return
            }
            if (repairLiveRouteIfNeeded(current, currentUri, now)) {
                main.postDelayed(this, HEALTH_TICK_MS)
                return
            }
            configureBootstrapIfNeeded()
            requestStates()
            maybeSilenceCarrier(force = false)
            if (!setupDelivered.get() && now - connectingStartedAt > SETUP_TIMEOUT_MS) {
                fail(IllegalStateException("AI_STUDIO_LIVE_SETUP_TIMEOUT"))
                return
            }
            if (setupDelivered.get() && lastProgressAt > 0L && now - lastProgressAt > LIVE_STALE_TIMEOUT_MS) {
                fail(IllegalStateException("AI_STUDIO_LIVE_CARRIER_STALE"))
                return
            }
            main.postDelayed(this, HEALTH_TICK_MS)
        }
    }

    private fun repairLiveRouteIfNeeded(
        current: AiStudioWebSessionExecutor,
        currentUri: Uri?,
        now: Long,
    ): Boolean {
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
        lastBootstrapState = ""
        logger.log(
            1,
            "AiStudioLive",
            "Sửa route hidden WebSession attempt=$routeRepairAttempts from=${path.take(100)} to=/live model=${targetLiveModel()}",
        )
        current.start(liveRouteUrl())
        return true
    }

    private fun configureBootstrapIfNeeded() {
        if (configured || closed.get()) return
        val current = executor ?: return
        val language = JSONObject.quote(targetLanguage)
        val transcribe = operationMode == GeminiLiveClient.OperationMode.TRANSCRIBE
        current.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.configure($language,${if (transcribe) "true" else "false"}):({ok:false,error:'r17-not-installed'}))",
        ) { raw ->
            val decoded = decodeEvalValue(raw)
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            if (obj?.optBoolean("ok") == true) {
                configured = true
                lastBootstrapState = decoded
                logger.log(2, "AiStudioLive", "R17 bootstrap configured target=$targetLanguage transcribe=$transcribe")
            }
        }
    }

    private fun requestStates() {
        val current = executor ?: return
        val js = "JSON.stringify({bootstrap:window.__AIS_R17_PRODUCTION__?window.__AIS_R17_PRODUCTION__.describe():null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,output:window.__AIS_LIVE_OUTPUT_ENGINE__?window.__AIS_LIVE_OUTPUT_ENGINE__.describe():null})"
        current.webView.evaluateJavascript(js) { raw ->
            val decoded = decodeEvalValue(raw)
            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return@evaluateJavascript
            root.optJSONObject("bootstrap")?.let { lastBootstrapState = it.toString() }
            root.optJSONObject("direct")?.let { direct ->
                lastDirectState = direct.toString()
                val requests = direct.optLong("carrierRequests", 0L)
                if (requests > lastCarrierRequests) {
                    lastCarrierRequests = requests
                    lastProgressAt = SystemClock.elapsedRealtime()
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

    private fun maybeDeliverSetup() {
        if (closed.get() || setupDelivered.get() || !serverSetupSeen) return
        val direct = runCatching { JSONObject(lastDirectState) }.getOrNull() ?: return
        val template = direct.optBoolean("templateObserved", false)
        val carriers = direct.optLong("carrierRequests", 0L)
        if (!template || carriers <= 0L) return
        inputClient?.arm(true)
        main.postDelayed({
            if (closed.get() || setupDelivered.get()) return@postDelayed
            setupDelivered.set(true)
            lastProgressAt = SystemClock.elapsedRealtime()
            logger.log(2, "AiStudioLive", "Production setup ready carrierRequests=$carriers template=${direct.optString("templateMime")}")
            listener.onSetupComplete()
        }, ARM_SETTLE_MS)
    }

    private fun setCarrierActive(enabled: Boolean) {
        if (closed.get() || carrierEnabled == enabled) return
        carrierEnabled = enabled
        val current = executor ?: return
        current.webView.post {
            if (closed.get()) return@post
            current.webView.evaluateJavascript(
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
        while (value > previous && !maxObservedQueuedBytes.compareAndSet(previous, value)) previous = maxObservedQueuedBytes.get()
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
            "Hidden WebSession thất bại setup=${setupDelivered.get()} routeRepairs=$routeRepairAttempts bootstrap=${safe(lastBootstrapState, 1200)} direct=${safe(lastDirectState, 1200)} output=${safe(lastOutputState, 1200)}",
            error,
        )
        listener.onError(error)
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

    private fun safe(value: String, max: Int): String = value.replace('\u0000', ' ').replace('\n', ' ').take(max)

    companion object {
        const val VERSION = "2026-09-03-r17.3-direct-live-route"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_LIVE = "https://aistudio.google.com/live"
        private const val HEALTH_TICK_MS = 650L
        private const val SETUP_TIMEOUT_MS = 35_000L
        private const val LIVE_STALE_TIMEOUT_MS = 12_000L
        private const val ARM_SETTLE_MS = 180L
        private const val INPUT_IDLE_TO_SILENCE_MS = 650L
        private const val STREAM_END_CARRIER_GRACE_MS = 900L
        private const val ROUTE_REPAIR_GRACE_MS = 2_500L
        private const val ROUTE_REPAIR_MIN_INTERVAL_MS = 3_000L
        private const val MAX_ROUTE_REPAIR_ATTEMPTS = 2
    }
}
