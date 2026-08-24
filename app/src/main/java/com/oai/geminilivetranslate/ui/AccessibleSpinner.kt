package com.oai.geminilivetranslate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatSpinner

class AccessibleSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.spinnerStyle,
) : AppCompatSpinner(context, attrs, defStyleAttr) {

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        selectedItem?.toString()?.takeIf { it.isNotBlank() }?.let { selectedLabel ->
            info.contentDescription = selectedLabel
        }
    }
}
