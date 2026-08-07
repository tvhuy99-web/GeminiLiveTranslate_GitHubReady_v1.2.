package com.oai.geminilivetranslate.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackSpeedAndRobustTtsSourceTest {
    @Test
    fun filePlaybackSpeedIsCappedAtThreeAndKeepsGeminiPcmRealtime() {
        val fileSource = source("audio/FileAudioSource.kt")
        val player = source("audio/StreamingPcmPlayer.kt")
        val service = source("service/TranslationService.kt")
        val activity = sourceRoot("MainActivity.kt")
        val layout = resource("layout/activity_main.xml")

        assertTrue(fileSource.contains("MAX_PLAYBACK_SPEED = 3f"))
        assertTrue(fileSource.contains("mediaElapsedMs / speed"))
        assertTrue(fileSource.contains("fun setPlaybackSpeed(speed: Float)"))
        assertTrue(player.contains("PlaybackParams()"))
        assertTrue(player.contains(".setSpeed(safe)"))
        assertTrue(player.contains("diagnosticName.startsWith(\"TranslatedPlayer-\")"))
        assertTrue(player.contains("Giữ PCM Gemini realtime speed=1.0x"))
        assertTrue(activity.contains("1f + value / 10f"))
        assertTrue(activity.contains("setFilePlaybackSpeed(selectedFilePlaybackSpeed)"))
        assertTrue(service.contains("val rate = if (currentMode == SourceMode.FILE) filePlaybackSpeed else 1f"))
        assertTrue(layout.contains("android:id=\"@+id/fileSpeedSeekBar\""))
        assertTrue(layout.contains("android:max=\"20\""))
    }

    @Test
    fun robustTtsScansEnginesKeepsBufferedTextAndRetriesSpeak() {
        val robust = source("audio/RobustTtsEngine.kt")
        val service = source("service/TranslationService.kt")
        val manifest = resource("AndroidManifest.xml")

        assertTrue(robust.contains("queryIntentServices"))
        assertTrue(robust.contains("INTENT_ACTION_TTS_SERVICE"))
        assertTrue(robust.contains("ACTION_CHECK_TTS_DATA"))
        assertTrue(robust.contains("OnInit timeout"))
        assertTrue(robust.contains("retrying without params") || robust.contains("thử lại không params"))
        assertTrue(service.contains("giữ văn bản trong buffer để thử lại"))
        assertTrue(service.contains("hoàn text chars="))
        assertTrue(service.contains("rate = rate"))
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"))
        assertTrue(manifest.contains("android.speech.tts.engine.CHECK_TTS_DATA"))
    }

    private fun source(relative: String): String = firstExisting(
        "src/main/java/com/oai/geminilivetranslate/$relative",
        "app/src/main/java/com/oai/geminilivetranslate/$relative",
    )

    private fun sourceRoot(relative: String): String = firstExisting(
        "src/main/java/com/oai/geminilivetranslate/$relative",
        "app/src/main/java/com/oai/geminilivetranslate/$relative",
    )

    private fun resource(relative: String): String = firstExisting(
        "src/main/res/$relative",
        "app/src/main/res/$relative",
        "src/main/$relative",
        relative,
        "app/src/main/$relative",
    )

    private fun firstExisting(vararg paths: String): String = paths.asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("Không tìm thấy source: ${paths.joinToString()}")
}
