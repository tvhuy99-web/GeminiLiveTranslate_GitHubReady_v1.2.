package com.oai.geminilivetranslate.core

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import com.oai.geminilivetranslate.ui.AiStudioWebSessionR14DirectLiveEngine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


class AiStudioWebLiveClient(
    private val webView: WebView,
    private val logger: (name: String, detail: String) -> Unit = { _, _ -> },
) {
    enum class SendResult {
        QUEUED,
        NOT_ARMED,
        BACKPRESSURED,
        CLOSED,
    }

    data class Stats(
        val armed: Boolean,
        val localQueueFrames: Int,
        val pcmBytesReceived: Long,
        val framesCreated: Long,
        val framesSubmittedToJs: Long,
        val framesAcceptedByJs: Long,
        val framesRejectedByJs: Long,
        val framesDroppedByJs: Long,
        val framesDroppedLocally: Long,
        val jsBatches: Long,
        val jsCallbacks: Long,
        val engineState: String,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    private val pumpScheduled = AtomicBoolean(false)
    private val localQueue = ConcurrentLinkedQueue<String>()
    private val localQueueSize = AtomicInteger(0)
    private val framer = PcmFramer(AiStudioWebSessionR14DirectLiveEngine.FRAME_BYTES)

    private val pcmBytesReceived = AtomicLong(0L)
    private val framesCreated = AtomicLong(0L)
    private val framesSubmittedToJs = AtomicLong(0L)
    private val framesAcceptedByJs = AtomicLong(0L)
    private val framesRejectedByJs = AtomicLong(0L)
    private val framesDroppedByJs = AtomicLong(0L)
    private val framesDroppedLocally = AtomicLong(0L)
    private val jsBatches = AtomicLong(0L)
    private val jsCallbacks = AtomicLong(0L)

    @Volatile private var lastEngineState = ""

    fun arm(enabled: Boolean = true) {
        if (closed.get()) return
        armed.set(enabled)
        val js = "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.arm(${if (enabled) "true" else "false"}):({ok:false,error:'r14-engine-not-installed'}))"
        webView.post {
            if (closed.get()) return@post
            webView.evaluateJavascript(js) { raw ->
                val decoded = decodeEvalValue(raw)
                lastEngineState = decoded
                logger("R15_CLIENT_ARM", "armed=$enabled result=${safe(decoded, 1600)}")
            }
        }
        if (enabled) schedulePump()
    }

    fun sendAudio(pcm16kMono: ByteArray): SendResult {
        if (pcm16kMono.isEmpty()) return SendResult.QUEUED
        if (closed.get()) return SendResult.CLOSED
        if (!armed.get()) return SendResult.NOT_ARMED
        pcmBytesReceived.addAndGet(pcm16kMono.size.toLong())
        val frames = framer.append(pcm16kMono)
        if (frames.isEmpty()) return SendResult.QUEUED
        framesCreated.addAndGet(frames.size.toLong())

        var backpressured = false
        frames.forEach { frame ->
            if (localQueueSize.get() >= MAX_LOCAL_QUEUE_FRAMES) {
                framesDroppedLocally.incrementAndGet()
                backpressured = true
            } else {
                localQueue.add(Base64.encodeToString(frame, Base64.NO_WRAP))
                localQueueSize.incrementAndGet()
            }
        }
        schedulePump()
        return if (backpressured) SendResult.BACKPRESSURED else SendResult.QUEUED
    }


    fun sendAudioStreamEnd(): SendResult {
        if (closed.get()) return SendResult.CLOSED
        if (!armed.get()) return SendResult.NOT_ARMED
        framer.flushPadded()?.let { frame ->
            framesCreated.incrementAndGet()
            if (localQueueSize.get() >= MAX_LOCAL_QUEUE_FRAMES) {
                framesDroppedLocally.incrementAndGet()
                return SendResult.BACKPRESSURED
            }
            localQueue.add(Base64.encodeToString(frame, Base64.NO_WRAP))
            localQueueSize.incrementAndGet()
            schedulePump()
        }
        logger("R15_CLIENT_STREAM_END", "localQueue=${localQueueSize.get()} framesCreated=${framesCreated.get()}")
        return SendResult.QUEUED
    }

    fun clear() {
        framer.reset()
        while (localQueue.poll() != null) Unit
        localQueueSize.set(0)
        if (closed.get()) return
        webView.post {
            if (closed.get()) return@post
            webView.evaluateJavascript(
                "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.clearQueue():({ok:false,error:'r14-engine-not-installed'}))",
            ) { raw ->
                lastEngineState = decodeEvalValue(raw)
                logger("R15_CLIENT_CLEAR", safe(lastEngineState, 1600))
            }
        }
    }

    fun reset() {
        clearLocalCounters()
        armed.set(false)
        framer.reset()
        while (localQueue.poll() != null) Unit
        localQueueSize.set(0)
        if (closed.get()) return
        webView.post {
            if (closed.get()) return@post
            webView.evaluateJavascript(
                "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.reset():({ok:false,error:'r14-engine-not-installed'}))",
            ) { raw ->
                lastEngineState = decodeEvalValue(raw)
                logger("R15_CLIENT_RESET", safe(lastEngineState, 2000))
            }
        }
    }

    fun requestEngineSnapshot(callback: (Stats) -> Unit = {}) {
        if (closed.get()) {
            callback(stats())
            return
        }
        webView.post {
            if (closed.get()) {
                callback(stats())
                return@post
            }
            webView.evaluateJavascript(
                "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.describe():({ok:false,error:'r14-engine-not-installed'}))",
            ) { raw ->
                lastEngineState = decodeEvalValue(raw)
                callback(stats())
            }
        }
    }

    fun stats(): Stats = Stats(
        armed = armed.get(),
        localQueueFrames = localQueueSize.get(),
        pcmBytesReceived = pcmBytesReceived.get(),
        framesCreated = framesCreated.get(),
        framesSubmittedToJs = framesSubmittedToJs.get(),
        framesAcceptedByJs = framesAcceptedByJs.get(),
        framesRejectedByJs = framesRejectedByJs.get(),
        framesDroppedByJs = framesDroppedByJs.get(),
        framesDroppedLocally = framesDroppedLocally.get(),
        jsBatches = jsBatches.get(),
        jsCallbacks = jsCallbacks.get(),
        engineState = lastEngineState,
    )

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        armed.set(false)
        framer.reset()
        while (localQueue.poll() != null) Unit
        localQueueSize.set(0)
        mainHandler.removeCallbacksAndMessages(null)
        logger("R15_CLIENT_CLOSE", "framesCreated=${framesCreated.get()} submitted=${framesSubmittedToJs.get()} accepted=${framesAcceptedByJs.get()} localDropped=${framesDroppedLocally.get()}")
    }

    private fun schedulePump() {
        if (closed.get() || !armed.get()) return
        if (!pumpScheduled.compareAndSet(false, true)) return
        mainHandler.post(pumpRunnable)
    }

    private val pumpRunnable = object : Runnable {
        override fun run() {
            pumpScheduled.set(false)
            if (closed.get() || !armed.get()) return
            val batch = ArrayList<String>(MAX_FRAMES_PER_JS_BATCH)
            while (batch.size < MAX_FRAMES_PER_JS_BATCH) {
                val next = localQueue.poll() ?: break
                localQueueSize.decrementAndGet()
                batch += next
            }
            if (batch.isEmpty()) return

            val payload = JSONArray()
            batch.forEach(payload::put)
            val count = batch.size
            framesSubmittedToJs.addAndGet(count.toLong())
            val batchOrdinal = jsBatches.incrementAndGet()
            val js = "JSON.stringify(window.__AIS_LIVE_DIRECT_ENGINE__?window.__AIS_LIVE_DIRECT_ENGINE__.enqueuePcmBase64($payload):({ok:false,error:'r14-engine-not-installed'}))"
            webView.evaluateJavascript(js) { raw ->
                jsCallbacks.incrementAndGet()
                val decoded = decodeEvalValue(raw)
                runCatching { JSONObject(decoded) }.getOrNull()?.let { obj ->
                    framesAcceptedByJs.addAndGet(obj.optLong("accepted", 0L))
                    framesRejectedByJs.addAndGet(obj.optLong("rejected", 0L))
                    framesDroppedByJs.addAndGet(obj.optLong("dropped", 0L))
                    lastEngineState = decoded
                }
                if (batchOrdinal == 1L || batchOrdinal % 25L == 0L) {
                    logger(
                        "R15_CLIENT_BATCH",
                        "batch=$batchOrdinal frames=$count localQueue=${localQueueSize.get()} submitted=${framesSubmittedToJs.get()} accepted=${framesAcceptedByJs.get()} rejected=${framesRejectedByJs.get()} jsDropped=${framesDroppedByJs.get()} result=${safe(decoded, 1000)}",
                    )
                }
            }
            if (localQueueSize.get() > 0) {
                if (pumpScheduled.compareAndSet(false, true)) mainHandler.postDelayed(this, PUMP_DELAY_MS)
            }
        }
    }

    private fun clearLocalCounters() {
        pcmBytesReceived.set(0L)
        framesCreated.set(0L)
        framesSubmittedToJs.set(0L)
        framesAcceptedByJs.set(0L)
        framesRejectedByJs.set(0L)
        framesDroppedByJs.set(0L)
        framesDroppedLocally.set(0L)
        jsBatches.set(0L)
        jsCallbacks.set(0L)
        lastEngineState = ""
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

    private fun safe(value: String, max: Int): String = value
        .replace('\u0000', ' ')
        .replace('\n', ' ')
        .take(max)

    private class PcmFramer(private val frameBytes: Int) {
        private var carry = ByteArray(0)

        @Synchronized
        fun append(data: ByteArray): List<ByteArray> {
            if (data.isEmpty()) return emptyList()
            val combined = ByteArray(carry.size + data.size)
            carry.copyInto(combined, 0)
            data.copyInto(combined, carry.size)
            val out = ArrayList<ByteArray>()
            var offset = 0
            while (combined.size - offset >= frameBytes) {
                out += combined.copyOfRange(offset, offset + frameBytes)
                offset += frameBytes
            }
            carry = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else ByteArray(0)
            return out
        }

        @Synchronized
        fun flushPadded(): ByteArray? {
            if (carry.isEmpty()) return null
            val out = ByteArray(frameBytes)
            carry.copyInto(out, 0, 0, carry.size.coerceAtMost(frameBytes))
            carry = ByteArray(0)
            return out
        }

        @Synchronized
        fun reset() {
            carry = ByteArray(0)
        }
    }

    companion object {
        const val VERSION = "2026-09-03-r15-android-pcm-web-live-client"
        private const val MAX_LOCAL_QUEUE_FRAMES = 192
        private const val MAX_FRAMES_PER_JS_BATCH = 12
        private const val PUMP_DELAY_MS = 8L
    }
}
