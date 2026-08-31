package com.oai.geminilivetranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.HistorySession
import com.oai.geminilivetranslate.core.SessionHistoryStore
import com.oai.geminilivetranslate.core.SessionLogger
import com.oai.geminilivetranslate.core.SourceMode
import com.oai.geminilivetranslate.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: SessionHistoryStore
    private lateinit var logger: SessionLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SessionHistoryStore(this)
        logger = SessionLogger(this, AppPreferences(this))
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val sessions = store.listRecent(SessionHistoryStore.MAX_SESSIONS)
        binding.historyContainer.removeAllViews()
        binding.emptyText.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE

        sessions.forEachIndexed { index, session ->
            val button = Button(this).apply {
                setAllCaps(false)
                minHeight = dp(48)
                text = session.title
                contentDescription = buildDescription(index, session)
                setOnClickListener {
                    logger.log(
                        2,
                        TAG,
                        "Mở phiên id=${session.id} title=${session.title} source=${session.sourceMode} updatedAt=${session.updatedAtMs}",
                    )
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_SESSION_ID, session.id),
                    )
                    finish()
                }
                setOnLongClickListener {
                    confirmDelete(session)
                    true
                }
            }
            binding.historyContainer.addView(button)
        }

        logger.log(2, TAG, "Hiển thị lịch sử count=${sessions.size}")
    }

    private fun buildDescription(index: Int, session: HistorySession): String {
        val source = when (session.sourceMode) {
            SourceMode.FILE.name -> "tệp"
            SourceMode.MICROPHONE.name -> "microphone"
            SourceMode.INTERNAL.name -> "âm thanh nội bộ"
            else -> "không rõ"
        }
        val processing = when (session.processingMode) {
            AppPreferences.PROCESSING_MODE_VIDEO_DESCRIPTION ->
                if (session.videoDescriptionMode == AppPreferences.VIDEO_DESCRIPTION_SUMMARY) {
                    "mô tả video tổng hợp"
                } else {
                    "mô tả video theo thời gian"
                }
            AppPreferences.PROCESSING_MODE_TRANSCRIBE -> "chép lời"
            else -> "dịch thuật"
        }
        val translated = if (session.hasVietnamese) ", đã có bản dịch tiếng Việt" else ""
        val videoAvailable = buildString {
            if (!session.geminiFileUri.isNullOrBlank()) append(", video đã tải lên Gemini")
            if (session.videoTimelineSrt.isNotBlank()) append(", có mô tả theo thời gian")
            if (session.videoSummaryText.isNotBlank()) append(", có mô tả tổng hợp")
        }
        val time = DATE_FORMAT.format(Date(session.updatedAtMs))
        return "${index + 1}. ${session.title}, $source, $processing, $time$translated$videoAvailable. Nhấn để mở. Nhấn giữ để xóa."
    }

    private fun confirmDelete(session: HistorySession) {
        AlertDialog.Builder(this)
            .setTitle("Xóa phiên này?")
            .setMessage(session.title)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                val deleted = store.delete(session.id)
                logger.log(
                    if (deleted) 2 else 1,
                    TAG,
                    "Xóa phiên id=${session.id} title=${session.title} success=$deleted",
                )
                render()
            }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(value)

    companion object {
        const val EXTRA_SESSION_ID = "history.sessionId"
        private const val TAG = "History"
        private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }
}
