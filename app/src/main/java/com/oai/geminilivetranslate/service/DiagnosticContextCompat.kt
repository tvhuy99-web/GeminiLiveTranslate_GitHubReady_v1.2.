package com.oai.geminilivetranslate.service

import com.oai.geminilivetranslate.core.DiagnosticContext

/** Keep single-value diagnostic updates readable inside the service package. */
fun DiagnosticContext.put(key: String, value: Any?) = update(key, value)
