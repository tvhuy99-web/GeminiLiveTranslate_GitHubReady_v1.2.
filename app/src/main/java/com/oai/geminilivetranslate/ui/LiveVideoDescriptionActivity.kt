package com.oai.geminilivetranslate.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.oai.geminilivetranslate.core.ApiKeyStore
import com.oai.geminilivetranslate.network.GeminiScreenDescriptionLiveClient
import com.oai.geminilivetranslate.service.LiveVideoDescriptionService
import kotlinx.coroutines.launch

class LiveVideoDescriptionActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var startStopButton: Button
    private lateinit var transcriptScroll: ScrollView

    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            statusText.text = "Bạn chưa cấp quyền chia sẻ màn hình"
            return@registerForActivityResult
        }
        val serviceIntent = Intent(this, LiveVideoDescriptionService::class.java).apply {
            action = LiveVideoDescriptionService.ACTION_START
            putExtra(LiveVideoDescriptionService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
            putExtra(LiveVideoDescriptionService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mô tả video trực tiếp"
        setContentView(buildContentView())
        observeSession()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Mô tả màn hình trực tiếp"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            isAccessibilityHeading = true
            setPadding(0, dp(6), 0, dp(10))
        })

        root.addView(TextView(this).apply {
            text = "${GeminiScreenDescriptionLiveClient.MODEL} · Màn hình được gửi dưới dạng hình ảnh; microphone không được mở hoặc gửi. Giọng nói trả lời của Gemini vẫn phát bình thường."
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        })

        startStopButton = Button(this).apply {
            text = "Bắt đầu mô tả trực tiếp"
            minHeight = dp(48)
            contentDescription = text
            setOnClickListener {
                if (LiveVideoDescriptionService.uiState.value.running) {
                    stopLiveSession()
                } else {
                    requestScreenCapture()
                }
            }
        }
        root.addView(startStopButton)

        statusText = TextView(this).apply {
            text = "Sẵn sàng"
            textSize = 16f
            isFocusable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusText)

        root.addView(TextView(this).apply {
            text = "Mô tả đã nói"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        })

        transcriptText = TextView(this).apply {
            text = "Chưa có mô tả"
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        transcriptScroll = ScrollView(this).apply {
            contentDescription = "Nội dung mô tả trực tiếp"
            isFocusable = true
            addView(
                transcriptText,
                ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        root.addView(TextView(this).apply {
            text = "Lưu ý: Android có thể che đen những ứng dụng hoặc nội dung được bảo vệ bằng FLAG_SECURE/DRM."
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        return root
    }

    private fun requestScreenCapture() {
        val keys = ApiKeyStore(this).load().keys
        if (keys.isEmpty()) {
            statusText.text = "Chưa có Gemini API Key"
            Toast.makeText(
                this,
                "Hãy thêm Gemini API Key trong Cài đặt API trước khi dùng mô tả trực tiếp",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        statusText.text = "Đang chờ quyền chia sẻ màn hình..."
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun stopLiveSession() {
        startService(
            Intent(this, LiveVideoDescriptionService::class.java)
                .setAction(LiveVideoDescriptionService.ACTION_STOP),
        )
    }

    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveVideoDescriptionService.uiState.collect { state ->
                    statusText.text = state.status
                    startStopButton.text = if (state.running) {
                        "Dừng mô tả trực tiếp"
                    } else {
                        "Bắt đầu mô tả trực tiếp"
                    }
                    startStopButton.contentDescription = startStopButton.text
                    transcriptText.text = state.transcript.ifBlank { "Chưa có mô tả" }
                    if (state.transcript.isNotBlank()) {
                        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
