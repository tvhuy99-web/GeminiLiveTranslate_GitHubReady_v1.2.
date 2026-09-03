package com.oai.geminilivetranslate.network

/**
 * Backend-neutral realtime session contract prepared in R17.
 *
 * GeminiLiveClient is the compatibility facade currently used by TranslationService. This contract
 * lets the next UI-only milestone expose backend selection without changing the audio/service
 * semantics again.
 */
interface RealtimeSessionClient {
    fun connect()
    fun sendAudio(pcm16kMono: ByteArray): GeminiLiveClient.SendResult
    fun sendAudioStreamEnd(): GeminiLiveClient.SendResult
    fun backpressureStats(): GeminiLiveClient.BackpressureStats
    fun responseStats(): GeminiLiveClient.ResponseStats
    fun close(graceful: Boolean = true)
}

internal class GeminiRealtimeSessionClientAdapter(
    private val delegate: GeminiLiveClient,
) : RealtimeSessionClient {
    override fun connect() = delegate.connect()
    override fun sendAudio(pcm16kMono: ByteArray) = delegate.sendAudio(pcm16kMono)
    override fun sendAudioStreamEnd() = delegate.sendAudioStreamEnd()
    override fun backpressureStats() = delegate.backpressureStats()
    override fun responseStats() = delegate.responseStats()
    override fun close(graceful: Boolean) = delegate.close(graceful)
}
