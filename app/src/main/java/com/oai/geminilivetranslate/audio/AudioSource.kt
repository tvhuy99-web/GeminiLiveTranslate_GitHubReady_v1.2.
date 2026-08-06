package com.oai.geminilivetranslate.audio

interface AudioSource {
    val supportsSeek: Boolean get() = false
    suspend fun run(listener: Listener)
    fun pause() = Unit
    fun resume() = Unit
    fun seekBy(deltaMs: Long) = Unit
    fun seekToPercent(percent: Int) = Unit
    fun stop()

    interface Listener {
        fun onPcm16Mono16k(data: ByteArray)
        fun onProgress(percent: Int, positionMs: Long, durationMs: Long) = Unit
        fun onCompleted() = Unit
        fun onError(error: Throwable)
    }
}
