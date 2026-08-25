package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8Diagnostic
import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase

internal class R8CompilerFailure(
    val code: R8ErrorCode,
    val phase: R8FailurePhase,
    message: String,
    cause: Throwable? = null,
    val diagnostics: List<R8Diagnostic> = emptyList(),
) : Exception(message, cause)
