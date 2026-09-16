package com.oai.geminilivetranslate.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/** Entry point shown only inside the existing Video Description section. */
class LiveVideoDescriptionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : AppCompatButton(context, attrs, defStyleAttr) {
    init {
        if (text.isNullOrBlank()) text = "Mô tả trực tiếp màn hình (Gemini 3.8 Live)"
        contentDescription = text
        setOnClickListener {
            context.startActivity(Intent(context, LiveVideoDescriptionActivity::class.java))
        }
    }
}
