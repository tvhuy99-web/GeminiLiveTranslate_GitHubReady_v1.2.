package com.oai.geminilivetranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.oai.geminilivetranslate.core.AppLogRepository
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewerActivity : AppCompatActivity() {
    private lateinit var logger: SessionLogger
    private lateinit var levelSpinner: Spinner
    private lateinit var tagSpinner: Spinner
    private lateinit var searchInput: EditText
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private lateinit var scroll: ScrollView
    private var autoScroll = true

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh(false)
            logText.postDelayed(this, 1_500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger = SessionLogger(this, AppPreferences(this))
        setContentView(buildUi())
        refresh(true)
    }

    override fun onStart() {
        super.onStart()
        logText.post(refreshRunnable)
    }

    override fun onStop() {
        logText.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        root.addView(TextView(this).apply {
            text = "Nhật ký & chẩn đoán"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        })

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        levelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LogViewerActivity,
                android.R.layout.simple_spinner_item,
                listOf("Chỉ lỗi", "Đến cảnh báo", "Đến thông thường", "Tất cả chi tiết"),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(3)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            onItemSelectedListener = selectionListener { refresh(false) }
        }
        tagSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            onItemSelectedListener = selectionListener { refresh(false) }
        }
        filters.addView(levelSpinner)
        filters.addView(tagSpinner)
        root.addView(filters)

        searchInput = EditText(this).apply {
            hint = "Tìm trong nhật ký"
            isSingleLine = true
            doAfterTextChanged { refresh(false) }
        }
        root.addView(searchInput)

        statusText = TextView(this).apply {
            textSize = 12f
            setPadding(4, 4, 4, 6)
        }
        root.addView(statusText)

        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(10, 10, 10, 10)
        }
        scroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        val firstActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstActions.addView(actionButton("Làm mới") { refresh(true) })
        firstActions.addView(actionButton("Sao chép") { copyVisibleLog() })
        firstActions.addView(actionButton("Tự cuộn: Bật") { button ->
            autoScroll = !autoScroll
            button.text = "Tự cuộn: ${if (autoScroll) "Bật" else "Tắt"}"
            if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        })
        root.addView(firstActions)

        val secondActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondActions.addView(actionButton("Gửi báo cáo") { shareDiagnostics() })
        secondActions.addView(actionButton("Xóa log") { confirmClear() })
        secondActions.addView(actionButton("Đóng") { finish() })
        root.addView(secondActions)
        return root
    }

    private fun refresh(forceTags: Boolean) {
        if (!::logText.isInitialized || !::tagSpinner.isInitialized) return
        val selectedTag = tagSpinner.selectedItem?.toString()
        val desiredTags = listOf("Tất cả") + logger.tags()
        val currentAdapter = tagSpinner.adapter
        val currentTags = (0 until (currentAdapter?.count ?: 0)).map { index ->
            currentAdapter?.getItem(index)?.toString().orEmpty()
        }
        if (forceTags || currentTags != desiredTags) {
            tagSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                desiredTags,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            tagSpinner.setSelection(desiredTags.indexOf(selectedTag).coerceAtLeast(0), false)
        }
        val maxLevel = levelSpinner.selectedItemPosition.coerceIn(0, 3)
        val tag = tagSpinner.selectedItem?.toString()
        val query = searchInput.text?.toString()
        val entries = logger.entries(maxLevel, tag, query)
        val newText = entries.joinToString("\n", transform = AppLogRepository.Entry::format)
            .ifBlank { "Chưa có nhật ký phù hợp bộ lọc." }
        if (logText.text.toString() != newText) {
            logText.text = newText
            if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val (fileCount, totalBytes) = logger.fileStats()
        statusText.text = "${entries.size} dòng đang hiển thị · $fileCount tệp log · ${totalBytes / 1024L} KB"
    }

    private fun copyVisibleLog() {
        val text = logText.text.toString()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Gemini Live Translate log", text))
        toast("Đã sao chép nhật ký đang hiển thị")
    }

    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { logger.createDiagnosticBundle() } }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@LogViewerActivity, "$packageName.files", file)
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Gemini Live Translate - báo cáo chẩn đoán")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Gửi báo cáo chẩn đoán"))
                }.onFailure {
                    logger.log(0, "Diagnostics", "Không mở được bảng chia sẻ báo cáo", it)
                    toast("Thiết bị không có ứng dụng nhận tệp ZIP")
                }
            }.onFailure { toast("Không tạo được báo cáo: ${it.message}") }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Xóa toàn bộ nhật ký?")
            .setMessage("Thao tác này xóa log trong bộ nhớ và tất cả tệp log xoay vòng. API Key, cài đặt và bản ghi âm không bị ảnh hưởng.")
            .setPositiveButton("Xóa") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { logger.clear() }
                    refresh(true)
                    toast("Đã xóa nhật ký")
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun actionButton(label: String, action: (Button) -> Unit): Button = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { action(this) }
    }

    private fun selectionListener(action: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
