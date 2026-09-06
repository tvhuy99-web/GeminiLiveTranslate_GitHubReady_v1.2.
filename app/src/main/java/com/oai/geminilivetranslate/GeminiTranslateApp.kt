package com.oai.geminilivetranslate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.oai.geminilivetranslate.core.AiApiSettingsStore
import com.oai.geminilivetranslate.core.AiConnectionModeStore
import com.oai.geminilivetranslate.core.AiFunctionModelCatalog
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.DiagnosticContext
import com.oai.geminilivetranslate.core.SessionLogger
import java.lang.ref.WeakReference
import java.util.Locale

class GeminiTranslateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivity?.get() === activity) foregroundActivity = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (foregroundActivity?.get() === activity) foregroundActivity = null
            }
        })

        val logger = SessionLogger(this, AppPreferences(this))
        val connectionMode = AiConnectionModeStore(this).load()
        val videoModel = AiApiSettingsStore(this).load().geminiModel
        DiagnosticContext.updateAll(mapOf(
            "app.versionName" to BuildConfig.VERSION_NAME,
            "app.versionCode" to BuildConfig.VERSION_CODE,
            "app.androidApi" to Build.VERSION.SDK_INT,
            "app.androidRelease" to Build.VERSION.RELEASE,
            "app.device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "app.locale" to Locale.getDefault().toLanguageTag(),
            "ai.connectionMode" to connectionMode,
        ))
        logger.log(
            2,
            "App",
            "Khởi động ứng dụng version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) api=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} connectionMode=$connectionMode models={${AiFunctionModelCatalog.summary(videoModel)}}",
        )

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logger.log(0, "Crash", "Lỗi chưa được xử lý thread=${thread.name}", throwable)
                logger.flush()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        @Volatile private var appContext: Context? = null
        @Volatile private var foregroundActivity: WeakReference<Activity>? = null

        fun requireAppContext(): Context =
            requireNotNull(appContext) { "GeminiTranslateApp chưa được khởi tạo" }

        fun currentActivity(): Activity? = foregroundActivity?.get()
    }
}
