package com.oai.geminilivetranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.oai.geminilivetranslate.MainActivity
import com.oai.geminilivetranslate.R
import com.oai.geminilivetranslate.core.SessionUiState
import com.oai.geminilivetranslate.core.SourceMode

class NotificationController(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ACTIVE, "Dịch trực tiếp", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CAPTURE, "Thu âm nội bộ", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun start(service: TranslationService, state: SessionUiState) {
        val type = when (state.sourceMode) {
            SourceMode.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            SourceMode.INTERNAL -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            SourceMode.FILE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        ServiceCompat.startForeground(service, NOTIFICATION_ID, build(state), type)
    }

    fun update(state: SessionUiState) {
        manager.notify(NOTIFICATION_ID, build(state))
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun build(state: SessionUiState): Notification {
        val channel = if (state.sourceMode == SourceMode.INTERNAL) CHANNEL_CAPTURE else CHANNEL_ACTIVE
        val contentIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseAction = if (state.paused) TranslationService.ACTION_RESUME else TranslationService.ACTION_PAUSE
        val pauseLabel = if (state.paused) "Tiếp tục" else "Tạm dừng"
        val pauseIntent = PendingIntent.getService(
            context,
            11,
            Intent(context, TranslationService::class.java).setAction(pauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            12,
            Intent(context, TranslationService::class.java).setAction(TranslationService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mode = when (state.sourceMode) {
            SourceMode.FILE -> "Tệp"
            SourceMode.MICROPHONE -> "Microphone"
            SourceMode.INTERNAL -> "Âm thanh nội bộ"
        }
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Gemini Live Translate")
            .setContentText(if (state.running) "$mode → ${state.currentLanguage}: ${state.status}" else state.status)
            .setContentIntent(contentIntent)
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, "Dừng", stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 23032
        private const val CHANNEL_ACTIVE = "gemini_translate_active"
        private const val CHANNEL_CAPTURE = "gemini_translate_audio_capture"
    }
}
