package com.oai.geminilivetranslate.service

import com.oai.geminilivetranslate.core.DiagnosticContext

fun DiagnosticContext.put(key: String, value: Any?) = update(key, value)
