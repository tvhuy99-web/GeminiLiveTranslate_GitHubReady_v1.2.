package com.oai.geminilivetranslate.network


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
