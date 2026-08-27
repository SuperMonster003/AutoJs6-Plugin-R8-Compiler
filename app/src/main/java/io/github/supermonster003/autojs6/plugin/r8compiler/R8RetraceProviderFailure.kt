package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8Diagnostic
import org.autojs.plugin.r8compiler.api.R8RetraceErrorCode
import org.autojs.plugin.r8compiler.api.R8RetraceFailurePhase

internal class R8RetraceProviderFailure(
    val code: R8RetraceErrorCode,
    val phase: R8RetraceFailurePhase,
    message: String,
    cause: Throwable? = null,
    val diagnostics: List<R8Diagnostic> = emptyList(),
) : Exception(message, cause)
