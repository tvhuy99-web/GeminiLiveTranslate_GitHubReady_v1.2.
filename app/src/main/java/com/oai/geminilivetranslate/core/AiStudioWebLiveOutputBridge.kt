package com.oai.geminilivetranslate.core

import android.annotation.SuppressLint
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated non-diagnostic bridge for R16 Live output.
 *
 * Actual model audio/transcription crosses this bridge to the Android client, while the existing
 * AIStudioWebSessionLab bridge continues to receive metadata-only diagnostics. Payload contents are
 * never forwarded to the logger by this class.
 */
@SuppressLint("AddJavascriptInterface")
class AiStudioWebLiveOutputBridge(
    private val webView: WebView,
    private val listener: Listener,
    private val logger: (name: String, detail: String) -> Unit = { _, _ -> },
) {
    interface Listener {
        fun onAudio(pcm24kMono: ByteArray, mimeType: String) = Unit
        fun onText(kind: String, text: String) = Unit
        fun onSignal(kind: String, value: String) = Unit
    }

    data class Stats(
        val audioChunks: Long,
        val audioBytes: Long,
        val textEvents: Long,
        val textChars: Long,
        val signalEvents: Long,
        val decodeErrors: Long,
        val lastMime: String,
        val lastTextKind: String,
        val lastSignalKind: String,
    )

    private val closed = AtomicBoolean(false)
    private val audioChunks = AtomicLong(0L)
    private val audioBytes = AtomicLong(0L)
    private val textEvents = AtomicLong(0L)
    private val textChars = AtomicLong(0L)
    private val signalEvents = AtomicLong(0L)
    private val decodeErrors = AtomicLong(0L)

    @Volatile private var lastMime = ""
    @Volatile private var lastTextKind = ""
    @Volatile private var lastSignalKind = ""

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AiStudioWebLiveOutputBridge must be created on main thread" }
        webView.addJavascriptInterface(Bridge(), JS_INTERFACE_NAME)
        logger("R16_OUTPUT_BRIDGE_INSTALLED", "version=$VERSION interface=$JS_INTERFACE_NAME")
    }

    fun stats(): Stats = Stats(
        audioChunks = audioChunks.get(),
        audioBytes = audioBytes.get(),
        textEvents = textEvents.get(),
        textChars = textChars.get(),
        signalEvents = signalEvents.get(),
        decodeErrors = decodeErrors.get(),
        lastMime = lastMime,
        lastTextKind = lastTextKind,
        lastSignalKind = lastSignalKind,
    )

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        webView.post { runCatching { webView.removeJavascriptInterface(JS_INTERFACE_NAME) } }
        val s = stats()
        logger(
            "R16_OUTPUT_BRIDGE_CLOSE",
            "audioChunks=${s.audioChunks} audioBytes=${s.audioBytes} textEvents=${s.textEvents} textChars=${s.textChars} signals=${s.signalEvents} decodeErrors=${s.decodeErrors}",
        )
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onAudioChunk(mimeType: String?, base64: String?) {
            if (closed.get()) return
            val encoded = base64.orEmpty()
            val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrElse {
                val errors = decodeErrors.incrementAndGet()
                if (errors == 1L || errors % 10L == 0L) {
                    logger("R16_OUTPUT_AUDIO_DECODE_ERROR", "errors=$errors payloadChars=${encoded.length}")
                }
                return
            }
            if (decoded.isEmpty()) return
            val mime = mimeType.orEmpty().take(120)
            val chunks = audioChunks.incrementAndGet()
            val bytes = audioBytes.addAndGet(decoded.size.toLong())
            lastMime = mime
            if (chunks == 1L || chunks % 25L == 0L) {
                logger("R16_OUTPUT_AUDIO", "chunks=$chunks lastBytes=${decoded.size} totalBytes=$bytes mime=$mime")
            }
            listener.onAudio(decoded, mime)
        }

        @JavascriptInterface
        fun onText(kind: String?, text: String?) {
            if (closed.get()) return
            val value = text.orEmpty()
            if (value.isEmpty()) return
            val safeKind = kind.orEmpty().take(80)
            val events = textEvents.incrementAndGet()
            val chars = textChars.addAndGet(value.length.toLong())
            lastTextKind = safeKind
            logger("R16_OUTPUT_TEXT", "kind=$safeKind chars=${value.length} events=$events totalChars=$chars")
            listener.onText(safeKind, value)
        }

        @JavascriptInterface
        fun onSignal(kind: String?, value: String?) {
            if (closed.get()) return
            val safeKind = kind.orEmpty().take(80)
            val safeValue = value.orEmpty().replace('\n', ' ').take(240)
            val events = signalEvents.incrementAndGet()
            lastSignalKind = safeKind
            logger("R16_OUTPUT_SIGNAL", "kind=$safeKind valueChars=${safeValue.length} events=$events")
            listener.onSignal(safeKind, safeValue)
        }
    }

    companion object {
        const val VERSION = "2026-09-03-r16-private-live-output-bridge"
        const val JS_INTERFACE_NAME = "AIStudioWebLiveOutput"
    }
}
