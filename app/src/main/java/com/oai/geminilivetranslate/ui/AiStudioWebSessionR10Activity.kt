package com.oai.geminilivetranslate.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.oai.geminilivetranslate.core.AiStudioWebSessionExecutor
import com.oai.geminilivetranslate.core.AiStudioWebSessionLabLog

/** Thin UI shell for the R10 executor. All send/discovery/response logic lives in the executor. */
class AiStudioWebSessionR10Activity : AppCompatActivity(), AiStudioWebSessionExecutor.Events {
    private lateinit var executor: AiStudioWebSessionExecutor
    private lateinit var labLog: AiStudioWebSessionLabLog
    private lateinit var promptInput: EditText
    private lateinit var stateView: TextView
    private lateinit var resultView: TextView
    private lateinit var logView: TextView
    private val uiLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labLog = AiStudioWebSessionLabLog(this)
        executor = AiStudioWebSessionExecutor(this, this)
        buildUi()
        executor.start()
    }

    override fun onDestroy() {
        executor.destroy()
        super.onDestroy()
    }

    override fun onStateChanged(state: AiStudioWebSessionExecutor.State, detail: String) {
        runOnUiThread {
            stateView.text = "R10 state: $state | $detail"
        }
        labLog.event("I", "R10_STATE", "state=$state detail=$detail")
    }

    override fun onLog(name: String, detail: String) {
        labLog.event("I", name, detail)
        runOnUiThread {
            if (uiLog.length > 20_000) uiLog.delete(0, uiLog.length - 14_000)
            uiLog.append('[').append(name).append("] ").append(detail.take(2_500)).append('\n')
            logView.text = uiLog.toString()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "AI Studio Web Session R10 - EXECUTOR"
            textSize = 20f
            contentDescription = "AI Studio Web Session R10 executor"
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "R10 dùng AiStudioWebSessionExecutor.generate(prompt). Runtime tự discovery controller, không tìm textarea bằng selector và response core tự ghép model text."
            textSize = 14f
        }, fullWidth())

        promptInput = EditText(this).apply {
            setText(DEFAULT_PROMPT)
            minLines = 2
            maxLines = 5
            contentDescription = "Prompt thử nghiệm executor R10"
        }
        root.addView(promptInput, fullWidth())

        root.addView(actionButton("R10. Executor generate") { runGenerate() }, fullWidth())
        root.addView(actionButton("Refresh discovery") { executor.refreshDiscovery() }, fullWidth())
        root.addView(actionButton("Hủy request hiện tại") { executor.cancelCurrent() }, fullWidth())
        root.addView(actionButton("Mở Nhật ký AI Studio") {
            startActivity(Intent(this, AiStudioWebSessionLogShareActivity::class.java))
        }, fullWidth())

        resultView = TextView(this).apply {
            text = "R10 result: chưa gửi"
            textSize = 15f
            setTextIsSelectable(true)
            contentDescription = "Kết quả executor R10"
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(resultView, fullWidth())

        stateView = TextView(this).apply {
            text = "R10 state: NEW"
            setTextIsSelectable(true)
            contentDescription = "Trạng thái executor R10"
        }
        root.addView(stateView, fullWidth())

        root.addView(executor.webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký executor R10"
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(170),
        ))
        setContentView(root)
    }

    private fun runGenerate() {
        val prompt = promptInput.text.toString()
        val marker = Regex("AIS_[A-Z0-9_.-]+").find(prompt)?.value.orEmpty()
        resultView.text = "R10 result: đang chờ..."
        val accepted = executor.generate(prompt, marker) { result ->
            runOnUiThread {
                resultView.text = if (result.ok) {
                    "HTTP ${result.status} | complete=${result.complete} | markerFound=${result.markerFound} | phase=${result.phase}\nModel text: ${result.modelText}"
                } else {
                    "R10 ERROR: ${result.error}"
                }
            }
        }
        if (!accepted) onLog("R10_GENERATE_REJECTED", "state=${executor.currentState()}")
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(56)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(5) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DEFAULT_PROMPT = "Reply with exactly AIS_WEB_SESSION_R10_OK_20260902 and nothing else."
    }
}
