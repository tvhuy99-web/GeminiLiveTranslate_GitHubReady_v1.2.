package com.oai.geminilivetranslate

import android.app.Application
import android.os.Build
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.DiagnosticContext
import com.oai.geminilivetranslate.core.SessionLogger
import java.util.Locale

class GeminiTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val logger = SessionLogger(this, AppPreferences(this))
        DiagnosticContext.updateAll(mapOf(
            "app.versionName" to BuildConfig.VERSION_NAME,
            "app.versionCode" to BuildConfig.VERSION_CODE,
            "app.androidApi" to Build.VERSION.SDK_INT,
            "app.androidRelease" to Build.VERSION.RELEASE,
            "app.device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "app.locale" to Locale.getDefault().toLanguageTag(),
        ))
        logger.log(2, "App", "Khởi động ứng dụng version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) api=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logger.log(0, "Crash", "Lỗi chưa được xử lý thread=${thread.name}", throwable)
                logger.flush()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
