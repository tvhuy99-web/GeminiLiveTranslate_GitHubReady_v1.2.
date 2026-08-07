package com.oai.geminilivetranslate.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.core.view.ViewCompat
import java.util.WeakHashMap

object SettingsAccessibilityBridge : Application.ActivityLifecycleCallbacks {
    private val tabLabels = setOf("Cơ bản", "Âm thanh", "Độ ổn định", "Lưu và xuất", "Hệ thống")
    private val patchedProfileSpinners = WeakHashMap<Spinner, Boolean>()
    private val layoutListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is SettingsActivity) return
        val root = activity.window.decorView
        patchTree(root)
        if (layoutListeners.containsKey(activity)) return

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            patchTree(root)
        }
        layoutListeners[activity] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is SettingsActivity) return
        val root = activity.window.decorView
        val listener = layoutListeners.remove(activity) ?: return
        if (root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    private fun patchTree(root: View) {
        val editTexts = ArrayList<EditText>(2)
        walk(root) { view ->
            when (view) {
                is Button -> patchTabButton(view)
                is Spinner -> patchSpinner(view, root)
                is EditText -> editTexts += view
            }
        }

        if (editTexts.isNotEmpty()) {
            labelEditText(editTexts[0], "Tên bộ máy dịch")
        }
        if (editTexts.size > 1) {
            labelEditText(editTexts[1], "Mã ngôn ngữ nhập thủ công")
        }
    }

    private fun patchTabButton(button: Button) {
        val label = button.text?.toString().orEmpty()
        if (label !in tabLabels) return
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setStateDescription(
            button,
            if (button.isSelected) "Đang chọn" else "Chưa chọn",
        )
    }

    private fun patchSpinner(spinner: Spinner, root: View) {
        spinner.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val description = spinner.contentDescription?.toString().orEmpty()
        if (!description.startsWith("Cách ứng dụng ưu tiên")) return
        if (patchedProfileSpinners.put(spinner, true) != null) return

        val original = spinner.onItemSelectedListener ?: return
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                original.onItemSelected(parent, view, position, id)
                root.post {
                    findProfileSpinner(root)?.performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                        null,
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                original.onNothingSelected(parent)
            }
        }
    }

    private fun labelEditText(editText: EditText, label: String) {
        editText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val value = editText.text?.toString().orEmpty()
        editText.contentDescription = if (value.isBlank()) {
            label
        } else {
            "$label. Giá trị hiện tại: $value"
        }
    }

    private fun findProfileSpinner(root: View): Spinner? {
        var result: Spinner? = null
        walk(root) { view ->
            if (result == null && view is Spinner &&
                view.contentDescription?.toString().orEmpty().startsWith("Cách ứng dụng ưu tiên")
            ) {
                result = view
            }
        }
        return result
    }

    private inline fun walk(root: View, crossinline visitor: (View) -> Unit) {
        visitor(root)
        if (root !is ViewGroup) return
        for (index in 0 until root.childCount) {
            walk(root.getChildAt(index), visitor)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
