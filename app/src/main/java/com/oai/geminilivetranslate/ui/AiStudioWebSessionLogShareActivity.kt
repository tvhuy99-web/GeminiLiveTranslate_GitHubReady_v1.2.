package com.oai.geminilivetranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog

/**
 * Simple diagnostics viewer for the latest Web Session Lab session.
 * No WebView, ZIP creation or Android share sheet is required. The latest bounded text report is
 * loaded on a worker thread, rendered directly on screen and can be copied to the clipboard.
 */
class AiStudioWebSessionLogShareActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private lateinit var copyButton: Button
    private lateinit var refreshButton: Button

    @Volatile
    private var loading = false
    private var reportText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadLatestReport()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Nhật ký AI Studio Web Session"
            textSize = 20f
            contentDescription = "Nhật ký AI Studio Web Session"
        }, fullWidth())

        statusView = TextView(this).apply {
            text = "Đang đọc nhật ký gần nhất..."
            textSize = 15f
            setPadding(0, dp(10), 0, dp(10))
            contentDescription = "Trạng thái đọc nhật ký"
        }
        root.addView(statusView, fullWidth())

        copyButton = Button(this).apply {
            text = "Sao chép toàn bộ"
            isAllCaps = false
            minHeight = dp(60)
            isEnabled = false
            contentDescription = "Sao chép toàn bộ nhật ký vào bộ nhớ tạm"
            setOnClickListener { copyReportToClipboard() }
        }
        root.addView(copyButton, fullWidth())

        refreshButton = Button(this).apply {
            text = "Làm mới nhật ký"
            isAllCaps = false
            minHeight = dp(56)
            contentDescription = "Đọc lại nhật ký gần nhất"
            setOnClickListener { loadLatestReport() }
        }
        root.addView(refreshButton, fullWidth())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            contentDescription = "Nội dung nhật ký AI Studio"
        }
        reportView = TextView(this).apply {
            text = "Đang tải..."
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(24))
            contentDescription = "Nội dung nhật ký AI Studio có thể chọn và sao chép"
        }
        scroll.addView(reportView, fullWidth())
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        root.addView(Button(this).apply {
            text = "Đóng"
            isAllCaps = false
            minHeight = dp(56)
            contentDescription = "Đóng màn hình nhật ký"
            setOnClickListener { finish() }
        }, fullWidth())

        setContentView(root)
    }

    private fun loadLatestReport() {
        if (loading) return
        loading = true
        copyButton.isEnabled = false
        refreshButton.isEnabled = false
        statusView.text = "Đang đọc nhật ký gần nhất ở luồng nền..."

        Thread({
            val result = runCatching { AiStudioWebSessionLabLog.createLatestTextReport(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    loading = false
                    return@runOnUiThread
                }

                result.onSuccess { text ->
                    reportText = text
                    reportView.text = text
                    statusView.text = "Đã tải ${text.length} ký tự. Có thể đọc trực tiếp hoặc bấm Sao chép toàn bộ."
                    copyButton.isEnabled = text.isNotBlank()
                }.onFailure {
                    reportText = ""
                    reportView.text = "Không đọc được nhật ký: ${it.message ?: it.javaClass.simpleName}"
                    statusView.text = "Không đọc được nhật ký gần nhất."
                    copyButton.isEnabled = false
                }

                refreshButton.isEnabled = true
                loading = false
            }
        }, "AIStudioWebSessionTextReport").start()
    }

    private fun copyReportToClipboard() {
        val text = reportText
        if (text.isBlank()) {
            statusView.text = "Chưa có nội dung để sao chép."
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Studio Web Session diagnostics", text))
        statusView.text = "Đã sao chép ${text.length} ký tự vào bộ nhớ tạm."
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(6)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
