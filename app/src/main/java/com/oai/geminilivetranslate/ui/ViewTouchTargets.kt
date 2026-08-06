package com.oai.geminilivetranslate.ui

import android.view.View

/**
 * Gives non-TextView controls the same concise minimum-height property used by buttons and
 * check boxes. This keeps interactive controls at least 48dp high for touch accessibility.
 */
internal var View.minHeight: Int
    get() = minimumHeight
    set(value) {
        minimumHeight = value
    }
