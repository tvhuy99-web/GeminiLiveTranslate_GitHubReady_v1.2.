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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.oai.geminilivetranslate.core.AppLogRepository
import com.oai.geminilivetranslate.core.AppPreferences
import com.oai.geminilivetranslate.core.SessionLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewerActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
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
        preferences = AppPreferences(this)
        logger = SessionLogger(this, preferences)
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Nhật ký hoạt động"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        })

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        levelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LogViewerActivity,
                android.R.layout.simple_spinner_item,
                listOf("Chỉ lỗi", "Lỗi và cảnh báo", "Thông tin thông thường", "Tất cả chi tiết"),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(3)
            minimumHeight = dp(48)
            contentDescription = "Lọc theo mức thông tin"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            onItemSelectedListener = selectionListener { refresh(false) }
        }
        tagSpinner = Spinner(this).apply {
            minimumHeight = dp(48)
            contentDescription = "Lọc theo nhóm hoạt động"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            onItemSelectedListener = selectionListener { refresh(false) }
        }
        filters.addView(levelSpinner)
        filters.addView(tagSpinner)
        root.addView(filters)

        searchInput = EditText(this).apply {
            hint = "Tìm trong nhật ký"
            isSingleLine = true
            minHeight = dp(48)
            doAfterTextChanged { refresh(false) }
        }
        root.addView(searchInput)

        statusText = TextView(this).apply {
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        root.addView(statusText)

        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll = ScrollView(this).apply {
            contentDescription = "Nội dung nhật ký hoạt động"
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(scroll)

        val firstActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        firstActions.addView(actionButton("Làm mới") { refresh(true) })
        firstActions.addView(actionButton("Sao chép") { copyVisibleLog() })
        firstActions.addView(actionButton("Tự cuộn: Bật") { button ->
            autoScroll = !autoScroll
            button.text = "Tự cuộn: ${if (autoScroll) "Bật" else "Tắt"}"
            button.contentDescription = button.text
            if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        })
        root.addView(firstActions)

        val secondActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        secondActions.addView(actionButton("Cách ghi") { showLogSettings() })
        secondActions.addView(actionButton("Chia sẻ") { shareDiagnostics() })
        secondActions.addView(actionButton("Xóa") { confirmClear() })
        root.addView(secondActions)

        root.addView(actionButton("Đóng") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        return root
    }

    private fun showLogSettings() {
        val current = preferences.load()
        val levelLabels = listOf(
            "Chỉ lỗi nghiêm trọng",
            "Lỗi và cảnh báo",
            "Thông tin hoạt động thông thường",
            "Đầy đủ để tìm lỗi khó",
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        panel.addView(TextView(this).apply {
            text = "Mức thông tin được ghi"
            textSize = 18f
            setPadding(0, dp(8), 0, dp(4))
            ViewCompat.setAccessibilityHeading(this, true)
        })

        val selectedLevel = current.logLevel.coerceIn(0, levelLabels.lastIndex)
        val loggingLevel = Spinner(this).apply {
            id = View.generateViewId()
            minimumHeight = dp(48)
            adapter = ArrayAdapter(
                this@LogViewerActivity,
                android.R.layout.simple_spinner_item,
                levelLabels,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selectedLevel)
            contentDescription = "Mức thông tin được ghi. Đang chọn ${levelLabels[selectedLevel]}"
        }
        loggingLevel.onItemSelectedListener = selectionListener {
            val position = loggingLevel.selectedItemPosition.coerceIn(0, levelLabels.lastIndex)
            loggingLevel.contentDescription =
                "Mức thông tin được ghi. Đang chọn ${levelLabels[position]}"
        }
        panel.addView(loggingLevel)
        panel.addView(detailText(
            "Mức cao hơn ghi nhiều thông tin hơn, hữu ích khi cần tìm nguyên nhân lỗi.",
        ))

        val saveToFile = CheckBox(this).apply {
            text = "Giữ nhật ký sau khi đóng ứng dụng"
            isChecked = current.logToFile
            minHeight = dp(48)
        }
        panel.addView(saveToFile)
        panel.addView(detailText(
            "Khi tắt, nhật ký vẫn xem được trong lần sử dụng hiện tại nhưng không được lưu thêm vào tệp. Các tệp cũ không tự bị xóa.",
        ))

        val includeConversation = CheckBox(this).apply {
            text = "Ghi nội dung hội thoại vào nhật ký"
            isChecked = current.logIncludeTranscript
            minHeight = dp(48)
        }
        panel.addView(includeConversation)
        panel.addView(detailText(
            "Nội dung này có thể riêng tư. Chỉ bật khi bạn cần kiểm tra lỗi liên quan đến câu nói hoặc lời dịch.",
        ))

        AlertDialog.Builder(this)
            .setTitle("Cách ghi nhật ký")
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("Lưu") { _, _ ->
                preferences.save(
                    preferences.load().copy(
                        logLevel = loggingLevel.selectedItemPosition.coerceIn(0, 3),
                        logToFile = saveToFile.isChecked,
                        logIncludeTranscript = includeConversation.isChecked,
                    ),
                )
                logger.log(1, "Settings", "Đã cập nhật cách ghi nhật ký")
                refresh(true)
                toast("Đã lưu cách ghi nhật ký")
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun detailText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setPadding(0, 0, 0, dp(10))
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
        statusText.text =
            "${entries.size} dòng đang hiển thị · $fileCount tệp nhật ký · ${totalBytes / 1024L} KB"
    }

    private fun copyVisibleLog() {
        val text = logText.text.toString()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Gemini Live Translate nhật ký", text),
        )
        toast("Đã sao chép nhật ký đang hiển thị")
    }

    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { logger.createDiagnosticBundle() }
            }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(
                    this@LogViewerActivity,
                    "$packageName.files",
                    file,
                )
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    "Gemini Live Translate - nhật ký hoạt động",
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "Chia sẻ nhật ký",
                        ),
                    )
                }.onFailure {
                    logger.log(0, "Diagnostics", "Không mở được bảng chia sẻ nhật ký", it)
                    toast("Thiết bị không có ứng dụng nhận tệp")
                }
            }.onFailure {
                toast("Không tạo được tệp chia sẻ: ${it.message}")
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Xóa toàn bộ nhật ký?")
            .setMessage(
                "Thao tác này xóa các dòng đang có và những tệp nhật ký đã lưu. Khóa truy cập, cài đặt và bản ghi âm không bị ảnh hưởng.",
            )
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

    private fun actionButton(
        label: String,
        action: (Button) -> Unit,
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
        setOnClickListener { action(this) }
    }

    private fun selectionListener(action: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) = action()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
