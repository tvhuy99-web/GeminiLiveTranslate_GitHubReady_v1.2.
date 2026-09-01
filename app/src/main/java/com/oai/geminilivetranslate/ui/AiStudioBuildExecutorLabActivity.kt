package com.oai.geminilivetranslate.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * POC client cho AI Studio Build Executor.
 *
 * Khác Browser Bridge cũ, Activity này không chứa WebView và không thao tác DOM/Run button.
 * Nó chỉ gọi protocol HTTPS/SSE do app Build của chúng ta sở hữu rồi hiển thị response.
 */
class AiStudioBuildExecutorLabActivity : AppCompatActivity() {
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var logView: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val uiLog = StringBuilder()
    private lateinit var logFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFile = createLogFile()
        buildUi()
        log("LAB_CREATE", "AI Studio Build Executor direct HTTPS/SSE client")
    }

    override fun onDestroy() {
        destroyed.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "AI Studio Build Executor Lab"
            textSize = 20f
            contentDescription = "Phòng thử nghiệm AI Studio Build Executor"
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "Không dùng Playground/WebView. APK chỉ gửi HTTPS/SSE tới app Build của chúng ta và nhận phản hồi Gemini."
        }, fullWidth())

        val controlsScroll = ScrollView(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlsScroll.addView(controls)

        endpointInput = EditText(this).apply {
            setSingleLine(true)
            hint = "https://<shared-or-deployed-build-url>"
            contentDescription = "Địa chỉ app AI Studio Build Executor"
            setText(getPreferences(MODE_PRIVATE).getString(PREF_ENDPOINT, "").orEmpty())
        }
        controls.addView(endpointInput, fullWidth())

        tokenInput = EditText(this).apply {
            setSingleLine(true)
            hint = "BRIDGE_TOKEN nếu Build app yêu cầu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription = "Bridge token, không được ghi vào nhật ký"
        }
        controls.addView(tokenInput, fullWidth())

        modelInput = EditText(this).apply {
            setSingleLine(true)
            setText(DEFAULT_MODEL)
            contentDescription = "Model Gemini dùng cho phép thử"
        }
        controls.addView(modelInput, fullWidth())

        promptInput = EditText(this).apply {
            minLines = 3
            maxLines = 6
            setText(DEFAULT_PROMPT)
            contentDescription = "Prompt thử nghiệm"
        }
        controls.addView(promptInput, fullWidth())

        controls.addView(horizontalButtons(
            actionButton("1. Health") { runHealth() },
            actionButton("2. Generate") { runGenerate() },
            actionButton("3. Stream") { runStream() },
            actionButton("Xóa kết quả") { outputView.text = "" },
            actionButton("Chia sẻ log") { shareLog() },
        ))

        statusView = TextView(this).apply {
            text = "Trạng thái: sẵn sàng"
            setPadding(0, dp(6), 0, dp(6))
            contentDescription = "Trạng thái Build Executor"
        }
        controls.addView(statusView, fullWidth())

        outputView = TextView(this).apply {
            text = "Kết quả sẽ xuất hiện ở đây."
            textSize = 14f
            setTextIsSelectable(true)
            contentDescription = "Phản hồi từ AI Studio Build Executor"
        }
        controls.addView(outputView, fullWidth())

        root.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)))

        val logScroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            contentDescription = "Nhật ký trực tiếp Build Executor Lab"
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun runHealth() = launchRequest("HEALTH") { config ->
        val response = request(config, "GET", "${config.baseUrl}/api/bridge/health", null, accept = "application/json")
        publishOutput("HEALTH HTTP ${response.status}\n${response.body}")
        log("HEALTH_RESULT", "status=${response.status} chars=${response.body.length}")
    }

    private fun runGenerate() = launchRequest("GENERATE") { config ->
        val requestId = requestId("generate")
        val body = requestJson(config, requestId).toString()
        val response = request(config, "POST", "${config.baseUrl}/api/bridge/generate", body, accept = "application/json")
        publishOutput("GENERATE HTTP ${response.status}\n${response.body}")
        val parsed = runCatching { JSONObject(response.body) }.getOrNull()
        val text = parsed?.optString("text").orEmpty()
        log("GENERATE_RESULT", "status=${response.status} requestId=$requestId textChars=${text.length} bodyChars=${response.body.length}")
    }

    private fun runStream() = launchRequest("STREAM") { config ->
        val requestId = requestId("stream")
        val url = URL("${config.baseUrl}/api/bridge/stream")
        val connection = openConnection(config, url, "POST", "text/event-stream")
        val payload = requestJson(config, requestId).toString().toByteArray(StandardCharsets.UTF_8)
        connection.doOutput = true
        connection.setRequestProperty("content-type", "application/json")
        connection.outputStream.use { it.write(payload) }

        val status = connection.responseCode
        log("STREAM_HEADERS", "status=$status requestId=$requestId contentType=${connection.contentType.orEmpty()}")
        if (status !in 200..299) {
            val error = readWhole(connection)
            publishOutput("STREAM HTTP $status\n$error")
            log("STREAM_ERROR", "status=$status chars=${error.length}")
            connection.disconnect()
            return@launchRequest
        }

        publishOutput("STREAM HTTP $status\n")
        val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
        var event = "message"
        var dataLines = mutableListOf<String>()
        var lineCount = 0
        while (!destroyed.get()) {
            val line = reader.readLine() ?: break
            lineCount++
            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) {
                    val data = dataLines.joinToString("\n")
                    appendOutput("event: $event\ndata: $data\n\n")
                    log("STREAM_EVENT", "event=$event dataChars=${data.length}")
                }
                event = "message"
                dataLines = mutableListOf()
                continue
            }
            when {
                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                line.startsWith("data:") -> dataLines += line.substringAfter(':').trim()
            }
        }
        log("STREAM_COMPLETE", "requestId=$requestId lines=$lineCount")
        connection.disconnect()
    }

    private fun launchRequest(name: String, block: (RequestConfig) -> Unit) {
        val config = runCatching { captureConfig() }.getOrElse { error ->
            status(error.message.orEmpty().ifBlank { "Cấu hình Build Executor không hợp lệ" })
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString(PREF_ENDPOINT, config.baseUrl).apply()
        status("$name đang chạy")
        log("REQUEST_START", "name=$name endpoint=${safeEndpoint(config.baseUrl)} model=${config.model} tokenConfigured=${config.token.isNotBlank()}")
        executor.execute {
            runCatching { block(config) }.onFailure { error ->
                log("REQUEST_FAILURE", "name=$name type=${error.javaClass.simpleName} message=${error.message.orEmpty().take(3000)}")
                publishOutput("$name LỖI\n${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
            status("$name đã kết thúc")
        }
    }

    private fun requestJson(config: RequestConfig, requestId: String): JSONObject = JSONObject()
        .put("request_id", requestId)
        .put("model", config.model)
        .put("prompt", config.prompt)

    private fun request(config: RequestConfig, method: String, url: String, body: String?, accept: String): HttpResult {
        val connection = openConnection(config, URL(url), method, accept)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = connection.responseCode
        val result = readWhole(connection)
        val contentType = connection.contentType.orEmpty()
        log("HTTP_RESULT", "method=$method status=$status contentType=${contentType.take(200)} bodyChars=${result.length} url=${safeEndpoint(url)}")
        connection.disconnect()
        return HttpResult(status, result)
    }

    private fun openConnection(config: RequestConfig, url: URL, method: String, accept: String): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = if (accept == "text/event-stream") 180_000 else 90_000
        connection.useCaches = false
        connection.setRequestProperty("accept", accept)
        connection.setRequestProperty("cache-control", "no-cache")
        connection.setRequestProperty("x-build-executor-client", "android-lab-v1")
        if (config.token.isNotBlank()) connection.setRequestProperty("x-bridge-token", config.token)
        return connection
    }

    private fun readWhole(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.take(MAX_RESPONSE_CHARS)
    }

    private fun captureConfig(): RequestConfig {
        val base = endpointInput.text.toString().trim().trimEnd('/')
        require(base.isNotBlank()) { "Hãy nhập Shared/Deployed URL của Build Executor trước." }
        require(base.startsWith("https://")) { "POC chỉ chấp nhận HTTPS Shared/Deployed URL" }
        return RequestConfig(
            baseUrl = base,
            token = tokenInput.text.toString(),
            model = modelInput.text.toString().trim().ifBlank { DEFAULT_MODEL },
            prompt = promptInput.text.toString(),
        )
    }

    private fun requestId(prefix: String): String = "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun horizontalButtons(vararg buttons: Button) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = true
        addView(LinearLayout(this@AiStudioBuildExecutorLabActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it) }
        })
    }

    private fun status(value: String) {
        if (destroyed.get()) return
        mainHandler.post { if (!destroyed.get()) statusView.text = "Trạng thái: $value" }
    }

    private fun publishOutput(value: String) {
        if (destroyed.get()) return
        mainHandler.post { if (!destroyed.get()) outputView.text = value }
    }

    private fun appendOutput(value: String) {
        if (destroyed.get()) return
        mainHandler.post { if (!destroyed.get()) outputView.append(value) }
    }

    @Synchronized
    private fun log(event: String, detail: String) {
        val stamp = LOG_TIME_FORMAT.format(Date())
        val line = "$stamp [$event] ${detail.replace('\n', ' ').take(8000)}\n"
        runCatching { logFile.appendText(line) }
        mainHandler.post {
            if (destroyed.get() || !::logView.isInitialized) return@post
            uiLog.append(line)
            if (uiLog.length > MAX_UI_LOG_CHARS) uiLog.delete(0, uiLog.length - MAX_UI_LOG_CHARS)
            logView.text = uiLog.toString()
        }
    }

    private fun createLogFile(): File {
        val dir = File(filesDir, "aistudio-build-executor-lab").apply { mkdirs() }
        return File(dir, "BuildExecutorLab-${System.currentTimeMillis()}.log").apply { writeText("AI Studio Build Executor Lab\n") }
    }

    private fun shareLog() {
        runCatching {
            log("SHARE_LOG", "file=${logFile.name} bytes=${logFile.length()}")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", logFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("AI Studio Build Executor log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ nhật ký Build Executor Lab"))
        }.onFailure { status("Không chia sẻ được log: ${it.message.orEmpty()}") }
    }

    private fun safeEndpoint(raw: String): String = runCatching {
        val url = URL(raw)
        "${url.protocol}://${url.host}${if (url.port > 0) ":${url.port}" else ""}${url.path.take(500)}"
    }.getOrElse { raw.take(500) }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class RequestConfig(val baseUrl: String, val token: String, val model: String, val prompt: String)
    private data class HttpResult(val status: Int, val body: String)

    companion object {
        private const val PREF_ENDPOINT = "build_executor_endpoint"
        private const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        private const val DEFAULT_PROMPT = "Chỉ trả lời đúng chuỗi sau, không thêm nội dung khác: AIS_BUILD_BRIDGE_OK_20260901"
        private const val MAX_UI_LOG_CHARS = 36_000
        private const val MAX_RESPONSE_CHARS = 1_000_000
        private val LOG_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
