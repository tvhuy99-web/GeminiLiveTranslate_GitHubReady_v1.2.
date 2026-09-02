package com.oai.geminilivetranslate.ui

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * R12.1 entry point.
 *
 * This activity deliberately uses Android's system account chooser instead of enumerating accounts
 * itself. The chosen Google account becomes a one-shot hint for Google's own web AccountChooser;
 * no password, auth token, cookie or Google credential is read by the app.
 */
class AiStudioWebSessionR12LauncherActivity : AppCompatActivity() {
    private lateinit var accountState: TextView

    private val chooseGoogleAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            accountState.text = "Chưa chọn tài khoản Google."
            return@registerForActivityResult
        }
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty().trim()
        val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE).orEmpty().trim()
        if (name.isBlank() || type != GOOGLE_ACCOUNT_TYPE) {
            accountState.text = "Không nhận được tài khoản Google hợp lệ từ màn hệ thống."
            return@registerForActivityResult
        }
        AiStudioGoogleAccountBootstrap.arm(this, name)
        accountState.text = "Đã chọn: ${AiStudioGoogleAccountBootstrap.mask(name)}\nĐang mở AI Studio với gợi ý tài khoản này..."
        openR12Lab()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::accountState.isInitialized) refreshAccountState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "AI STUDIO WEB SESSION R12.1"
            textSize = 22f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "Direct Engine + watchdog thời gian + thử nghiệm chọn tài khoản Google trên máy"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        }, fullWidth())

        accountState = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        root.addView(accountState, fullWidth())

        root.addView(actionButton("Chọn tài khoản Google trên máy") {
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf(GOOGLE_ACCOUNT_TYPE),
                "Chọn tài khoản Google dùng cho AI Studio",
                null,
                null,
                null,
            )
            chooseGoogleAccount.launch(intent)
        }, fullWidth())

        root.addView(actionButton("Mở R12 Direct Engine không chọn lại tài khoản") {
            openR12Lab()
        }, fullWidth())

        root.addView(actionButton("Xóa gợi ý tài khoản đã chọn") {
            AiStudioGoogleAccountBootstrap.clear(this)
            refreshAccountState()
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "Lưu ý: thử nghiệm này chỉ dùng email đã chọn làm gợi ý cho trang đăng nhập Google. Ứng dụng không đọc mật khẩu, token đăng nhập hoặc cookie của tài khoản Android."
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }, fullWidth())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        refreshAccountState()
    }

    private fun refreshAccountState() {
        val masked = AiStudioGoogleAccountBootstrap.maskedAccount(this)
        accountState.text = if (masked.isBlank()) {
            "Tài khoản gợi ý: chưa chọn"
        } else {
            "Tài khoản gợi ý hiện tại: $masked"
        }
    }

    private fun openR12Lab() {
        startActivity(Intent(this, AiStudioWebSessionR11R2Activity::class.java))
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minimumHeight = dp(54)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(6) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val VERSION = "2026-09-02-web-session-r12.1-account-chooser-launcher"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
