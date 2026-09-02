package com.oai.geminilivetranslate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.oai.geminilivetranslate.audio.AudioSource
import com.oai.geminilivetranslate.audio.FileAudioSource
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * R14 Direct Live Engine PoC.
 *
 * The user still opens AI Studio Live manually once. R14 then piggybacks on AI Studio's own live
 * WebChannel requests: queued Android PCM16/16 kHz frames replace only outgoing audio/pcm Base64
 * payloads while AI Studio keeps ownership of auth, session IDs, offsets, acknowledgements and
 * channel lifecycle. The first milestone is transport proof, not production automation.
 */
class AiStudioWebSessionR14Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var stateView: TextView
    private lateinit var engineView: TextView
    private lateinit var sourceView: TextView
    private lateinit var micView: TextView

    private var selectedAudioUri: Uri? = null
    private var fileSource: FileAudioSource? = null
    private var fileJob: Job? = null
    private val fileFrameCount = AtomicInteger(0)
    private val fileFramer = PcmFramer(AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES)

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::micView.isInitialized) micView.text = if (granted) "Mic WebView: Android đã cấp quyền" else "Mic WebView: Android chưa cấp quyền"
        log("R14_ANDROID_MIC_PERMISSION", "granted=$granted")
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedAudioUri = uri
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceView.text = "Tệp thử: ${safe(uri.lastPathSegment.orEmpty(), 300)}"
            log("R14_AUDIO_FILE_SELECTED", "scheme=${uri.scheme.orEmpty()} nameChars=${uri.lastPathSegment.orEmpty().length}")
        } else {
            sourceView.text = "Tệp thử: chưa chọn"
            log("R14_AUDIO_FILE_SELECTED", "cancelled=true")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        installDocumentStartLayers()
        installLiveWebPermissions()
        buildUi()
        log(
            "R14_ACTIVITY_CREATE",
            "version=$VERSION direct=${AiStudioWebSessionR14DirectLiveEngine.VERSION} deep=${AiStudioWebSessionR13DeepProbe.VERSION} transport=${AiStudioWebSessionLiveProbe.VERSION} targetModel=${AiStudioWebSessionLiveProbe.TARGET_MODEL} executor=${AiStudioWebSessionExecutor.VERSION}",
        )
        executor.start(AI_STUDIO_NEW_CHAT)
    }

    override fun onDestroy() {
        stopFileInjection(clearQueue = true)
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread { if (::stateView.isInitialized) stateView.text = "Web Session: $state | $detail" }
        log("R14_EXECUTOR_STATE", "state=$state detail=${safe(detail, 1200)} url=${safeUrl(executor.webView.url)}")
    }

    override fun onLog(name: String, detail: String) = log(name, detail)

    private fun installDocumentStartLayers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("R14_DOCUMENT_START_UNSUPPORTED", "DOCUMENT_START_SCRIPT=false")
            return
        }
        listOf(
            AiStudioWebSessionLiveProbe.DOCUMENT_START,
            AiStudioWebSessionR13DeepProbe.DOCUMENT_START,
            AiStudioWebSessionR14DirectLiveEngine.DOCUMENT_START,
        ).forEach { script -> WebViewCompat.addDocumentStartJavaScript(executor.webView, script, setOf(AI_STUDIO_ORIGIN)) }
        log(
            "R14_DOCUMENT_START_REGISTERED",
            "origin=$AI_STUDIO_ORIGIN transport=${AiStudioWebSessionLiveProbe.VERSION} deep=${AiStudioWebSessionR13DeepProbe.VERSION} direct=${AiStudioWebSessionR14DirectLiveEngine.VERSION}",
        )
    }

    private fun installLiveWebPermissions() {
        executor.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val resources = req.resources.orEmpty()
                val asksAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val asksVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val androidGranted = hasMicPermission()
                log(
                    "R14_WEB_PERMISSION_REQUEST",
                    "origin=${safeUrl(req.origin?.toString())} asksAudio=$asksAudio asksVideo=$asksVideo androidMicGranted=$androidGranted resources=${resources.size}",
                )
                runOnUiThread {
                    // R14 remains audio-first. The existing page microphone acts only as the carrier clock.
                    if (asksAudio && androidGranted && !asksVideo) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        log("R14_WEB_PERMISSION_RESULT", "audioCapture=granted videoCapture=false")
                    } else {
                        req.deny()
                        log("R14_WEB_PERMISSION_RESULT", "denied asksAudio=$asksAudio asksVideo=$asksVideo")
                        if (asksAudio && !androidGranted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(TextView(this).apply {
            text = "AI STUDIO WEB SESSION R14 - DIRECT LIVE ENGINE"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())
        controls.addView(TextView(this).apply {
            text = "Mở Live thủ công bằng Gemini 3.1 Flash Live Preview và giữ microphone của AI Studio đang chạy. Sau đó R14 có thể thay các carrier frame audio/pcm bằng PCM từ Android mà không chạm auth/session WebChannel."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(6))
        }, fullWidth())

        stateView = TextView(this).apply { text = "Web Session: NEW"; textSize = 14f; setTextIsSelectable(true) }
        controls.addView(stateView, fullWidth())
        micView = TextView(this).apply { text = if (hasMicPermission()) "Mic WebView: Android đã cấp quyền" else "Mic WebView: Android chưa cấp quyền"; textSize = 14f }
        controls.addView(micView, fullWidth())
        sourceView = TextView(this).apply { text = "Tệp thử: chưa chọn"; textSize = 14f; setTextIsSelectable(true) }
        controls.addView(sourceView, fullWidth())
        engineView = TextView(this).apply { text = "R14 Engine: chưa chụp trạng thái"; textSize = 12f; setTextIsSelectable(true) }
        controls.addView(engineView, fullWidth())

        controls.addView(actionButton("Cấp quyền microphone cho AI Studio Live") { requestMic.launch(Manifest.permission.RECORD_AUDIO) }, fullWidth())
        controls.addView(actionButton("1. Reset R13 + R14 trước khi mở Live") { resetAll() }, fullWidth())
        controls.addView(actionButton("2. Đánh dấu rồi mở Live thủ công") { markAll() }, fullWidth())
        controls.addView(actionButton("3. ARM R14 để chờ carrier audio") { armEngine(true) }, fullWidth())
        controls.addView(actionButton("Tiêm tone PCM 440 Hz trong 1 giây") { injectTone() }, fullWidth())
        controls.addView(actionButton("Chọn tệp audio thật để thử") { pickAudio.launch(arrayOf("audio/*")) }, fullWidth())
        controls.addView(actionButton("Tiêm tối đa 8 giây từ tệp vào Live") { startFileInjection() }, fullWidth())
        controls.addView(actionButton("Dừng tiêm và xóa queue R14") { stopFileInjection(clearQueue = true) }, fullWidth())
        controls.addView(actionButton("Chụp trạng thái R14 + transport") { snapshotAll() }, fullWidth())
        controls.addView(actionButton("Tải lại AI Studio") { executor.start(AI_STUDIO_NEW_CHAT) }, fullWidth())
        controls.addView(actionButton("Mở / chia sẻ nhật ký AI Studio") { startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java)) }, fullWidth())

        root.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(455)))
        root.addView(executor.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun resetAll() {
        stopFileInjection(clearQueue = false)
        eval(
            "JSON.stringify({transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.reset('r14-before-live'):null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.reset('r14-before-live'):null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.reset():null})",
            "R14_RESET_NATIVE",
        )
    }

    private fun markAll() {
        eval(
            "JSON.stringify({transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.mark('r14-before-live'):null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.mark('r14-before-live'):null,direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null})",
            "R14_MARK_NATIVE",
        )
    }

    private fun armEngine(enabled: Boolean) {
        eval(
            "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.arm(${if (enabled) "true" else "false"}):({ok:false,error:'r14-engine-not-installed'}))",
            "R14_ARM_NATIVE",
        )
    }

    private fun injectTone() {
        armEngine(true)
        val samples = 16_000
        val pcm = ByteArray(samples * 2)
        val amplitude = Short.MAX_VALUE * 0.22
        for (i in 0 until samples) {
            val value = (sin(2.0 * PI * 440.0 * i / 16_000.0) * amplitude).toInt().toShort()
            pcm[i * 2] = (value.toInt() and 0xff).toByte()
            pcm[i * 2 + 1] = ((value.toInt() ushr 8) and 0xff).toByte()
        }
        val frames = chunkExact(pcm, AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES)
        queueFrames(frames, "tone")
        log("R14_TONE_QUEUED", "frames=${frames.size} frameBytes=${AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES} durationMs=1000 hz=440")
    }

    private fun startFileInjection() {
        val uri = selectedAudioUri ?: run {
            sourceView.text = "Tệp thử: hãy chọn một tệp audio trước"
            log("R14_FILE_INJECT_REJECTED", "reason=no-file")
            return
        }
        stopFileInjection(clearQueue = true)
        armEngine(true)
        fileFramer.reset()
        fileFrameCount.set(0)
        val source = FileAudioSource(
            context = this,
            uri = uri,
            pacingEnabled = true,
            leadMs = 80,
            initialPlaybackSpeed = 1f,
            logger = null,
        )
        fileSource = source
        sourceView.text = "Tệp thử: đang tiêm tối đa ${MAX_FILE_INJECT_MS / 1000} giây..."
        log("R14_FILE_INJECT_START", "maxMs=$MAX_FILE_INJECT_MS maxFrames=$MAX_FILE_FRAMES frameBytes=${AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES}")
        fileJob = lifecycleScope.launch(Dispatchers.IO) {
            source.run(object : AudioSource.Listener {
                override fun onPcm16Mono16k(data: ByteArray) {
                    val room = MAX_FILE_FRAMES - fileFrameCount.get()
                    if (room <= 0) { source.stop(); return }
                    val ready = fileFramer.append(data)
                    if (ready.isEmpty()) return
                    val accepted = ready.take(room)
                    val total = fileFrameCount.addAndGet(accepted.size)
                    queueFrames(accepted, "file")
                    if (total >= MAX_FILE_FRAMES) source.stop()
                }

                override fun onCompleted() {
                    runOnUiThread { sourceView.text = "Tệp thử: nguồn audio đã kết thúc, frames=${fileFrameCount.get()}" }
                    log("R14_FILE_INJECT_SOURCE_COMPLETED", "frames=${fileFrameCount.get()}")
                }

                override fun onError(error: Throwable) {
                    runOnUiThread { sourceView.text = "Tệp thử: lỗi ${safe(error.message.orEmpty(), 500)}" }
                    log("R14_FILE_INJECT_ERROR", "type=${error.javaClass.simpleName} message=${safe(error.message.orEmpty(), 800)}")
                }
            })
        }
    }

    private fun stopFileInjection(clearQueue: Boolean) {
        runCatching { fileSource?.stop() }
        fileSource = null
        fileJob?.cancel()
        fileJob = null
        if (clearQueue && ::executor.isInitialized) {
            executor.webView.post {
                eval(
                    "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.clearQueue():({ok:false,error:'r14-engine-not-installed'}))",
                    "R14_QUEUE_CLEAR_NATIVE",
                )
            }
        }
        if (::sourceView.isInitialized && selectedAudioUri != null) sourceView.text = "Tệp thử: đã dừng, frames=${fileFrameCount.get()}"
    }

    private fun queueFrames(frames: List<ByteArray>, source: String) {
        if (frames.isEmpty()) return
        val payload = JSONArray()
        frames.forEach { payload.put(Base64.encodeToString(it, Base64.NO_WRAP)) }
        executor.webView.post {
            val js = "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.enqueuePcmBase64($payload):({ok:false,error:'r14-engine-not-installed'}))"
            executor.webView.evaluateJavascript(js) { raw ->
                val decoded = decodeEvalValue(raw)
                engineView.text = "R14 Engine queue ($source): ${safe(decoded, 3000)}"
                if (source == "tone" || fileFrameCount.get() % 25 < frames.size) log("R14_QUEUE_NATIVE", "source=$source frames=${frames.size} result=${safe(decoded, 1600)}")
            }
        }
    }

    private fun snapshotAll() {
        val js = "JSON.stringify({direct:window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():null,deep:window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.describe():null,transport:window.__AIS_LIVE_PROBE__?window.__AIS_LIVE_PROBE__.describe():null})"
        executor.webView.evaluateJavascript(js) { raw ->
            val decoded = decodeEvalValue(raw)
            engineView.text = "R14 Engine: ${safe(decoded, 12000)}"
            log("R14_STATE_NATIVE", safe(decoded, 20000))
        }
        executor.webView.evaluateJavascript(
            "JSON.stringify(window.__AIS_LIVE_DEEP_PROBE__?window.__AIS_LIVE_DEEP_PROBE__.recent(120):({ok:false,error:'deep-probe-not-installed'}))",
        ) { raw -> log("R14_DEEP_RECENT_NATIVE", safe(decodeEvalValue(raw), 36000)) }
    }

    private fun eval(expression: String, logName: String) {
        executor.webView.evaluateJavascript(expression) { raw ->
            val decoded = decodeEvalValue(raw)
            if (::engineView.isInitialized) engineView.text = "R14 Engine: ${safe(decoded, 8000)}"
            log(logName, safe(decoded, 12000))
        }
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun decodeEvalValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            when (val first = JSONTokener(raw).nextValue()) {
                is String -> first
                else -> first.toString()
            }
        }.getOrElse { raw }
    }

    private fun log(name: String, detail: String) = labLog.event("I", name, detail)

    private fun safeUrl(raw: String?): String {
        val uri = runCatching { Uri.parse(raw.orEmpty()) }.getOrNull()
        return if (uri == null) "" else "${uri.scheme.orEmpty()}://${uri.host.orEmpty()}${uri.path.orEmpty()}".take(700)
    }

    private fun safe(text: String, max: Int): String = text.replace('\u0000', ' ').replace('\n', ' ').take(max)

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minimumHeight = dp(50)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun chunkExact(data: ByteArray, frameBytes: Int): List<ByteArray> {
        val count = data.size / frameBytes
        return List(count) { index -> data.copyOfRange(index * frameBytes, (index + 1) * frameBytes) }
    }

    private class PcmFramer(private val frameBytes: Int) {
        private var carry = ByteArray(0)

        @Synchronized
        fun append(data: ByteArray): List<ByteArray> {
            if (data.isEmpty()) return emptyList()
            val combined = ByteArray(carry.size + data.size)
            carry.copyInto(combined, 0)
            data.copyInto(combined, carry.size)
            val frames = ArrayList<ByteArray>()
            var offset = 0
            while (combined.size - offset >= frameBytes) {
                frames += combined.copyOfRange(offset, offset + frameBytes)
                offset += frameBytes
            }
            carry = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else ByteArray(0)
            return frames
        }

        @Synchronized
        fun reset() { carry = ByteArray(0) }
    }

    companion object {
        const val VERSION = "2026-09-02-web-session-r14.0-direct-live-engine-activity"
        private const val AI_STUDIO_ORIGIN = "https://aistudio.google.com"
        private const val AI_STUDIO_NEW_CHAT = "https://aistudio.google.com/prompts/new_chat"
        private const val MAX_FILE_INJECT_MS = 8_000
        private const val MAX_FILE_FRAMES = MAX_FILE_INJECT_MS / AiStudioWebSessionR14DirectLiveEngine.FRAME_MS
    }
}
