package com.oai.geminilivetranslate.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog

/**
 * Accessibility-safe escape hatch for exporting the latest Web Session Lab diagnostics.
 * ZIP creation runs on a worker thread so the screen never freezes while logs are compressed.
 */
class AiStudioWebSessionLogShareActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var shareButton: Button
    private var autoShareAttempted = false

    @Volatile
    private var sharing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        statusView.postDelayed({
            if (!isFinishing && !autoShareAttempted) {
                autoShareAttempted = true
                shareLatestBundle()
            }
        }, 350)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Chia sẻ nhật ký AI Studio Web Session"
            textSize = 20f
            contentDescription = "Chia sẻ nhật ký AI Studio Web Session"
        }, fullWidth())

        statusView = TextView(this).apply {
            text = "Sẵn sàng tạo ZIP của phiên thử nghiệm gần nhất."
            textSize = 16f
            setPadding(0, dp(16), 0, dp(16))
            contentDescription = "Trạng thái chuẩn bị nhật ký"
        }
        root.addView(statusView, fullWidth())

        shareButton = Button(this).apply {
            text = "Chia sẻ log ZIP gần nhất"
            isAllCaps = false
            minHeight = dp(64)
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Chia sẻ log ZIP gần nhất"
            setOnClickListener { shareLatestBundle() }
        }
        root.addView(shareButton, fullWidth())

        root.addView(Button(this).apply {
            text = "Đóng"
            isAllCaps = false
            minHeight = dp(56)
            contentDescription = "Đóng màn hình chia sẻ nhật ký"
            setOnClickListener { finish() }
        }, fullWidth())

        setContentView(root)
    }

    private fun shareLatestBundle() {
        if (sharing) return
        sharing = true
        shareButton.isEnabled = false
        statusView.text = "Đang tạo ZIP ở luồng nền. Màn hình vẫn có thể thao tác."

        Thread({
            val result = runCatching { AiStudioWebSessionLabLog.createLatestBundle(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    sharing = false
                    return@runOnUiThread
                }

                result.onSuccess { bundle ->
                    runCatching {
                        val uri = FileProvider.getUriForFile(this, "$packageName.files", bundle)
                        statusView.text = "Đã tạo ${bundle.name}, ${bundle.length()} byte. Đang mở bảng chia sẻ."
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("AI Studio Web Session diagnostics", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Chia sẻ AI Studio Web Session Lab log"))
                    }.onFailure {
                        statusView.text = "ZIP đã tạo nhưng không mở được bảng chia sẻ: ${it.message ?: it.javaClass.simpleName}"
                    }
                }.onFailure {
                    statusView.text = "Không tạo được ZIP: ${it.message ?: it.javaClass.simpleName}"
                }

                sharing = false
                shareButton.isEnabled = true
            }
        }, "AIStudioWebSessionLogZip").start()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(8)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
