package io.github.supermonster003.autojs6.plugin.r8compiler

import android.os.Build
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.retrace.ProguardMapProducer
import com.android.tools.r8.retrace.ProguardMappingSupplier
import com.android.tools.r8.retrace.Retrace
import com.android.tools.r8.retrace.RetraceCommand
import org.autojs.plugin.r8compiler.api.R8ArtifactContentValidation
import org.autojs.plugin.r8compiler.api.R8Diagnostic
import org.autojs.plugin.r8compiler.api.R8RetraceCapabilities
import org.autojs.plugin.r8compiler.api.R8RetraceErrorCode
import org.autojs.plugin.r8compiler.api.R8RetraceFailurePhase
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundle
import org.autojs.plugin.r8compiler.api.R8RetraceRequest
import org.autojs.plugin.r8compiler.api.R8Sha256
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

internal data class ProducedR8Retrace(
    val bytes: ByteArray,
    val sha256: R8Sha256,
    val diagnostics: List<R8Diagnostic>,
)

internal class R8RetraceEngine(
    private val commandRunner: R8RetraceCommandRunner = AndroidR8RetraceCommandRunner,
) {
    fun retrace(
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
        input: R8RetraceInputBundle,
        ensureActive: () -> Unit,
    ): ProducedR8Retrace {
        val diagnostics = R8DiagnosticCollector(
            minOf(request.diagnosticByteLimit, capabilities.limits.maxDiagnosticBytes),
        )
        ensureActive()
        val lines = decodeStackLines(input.obfuscatedStackTraceBytes)
        val retraced = try {
            commandRunner.run(input.mappingBytes, lines, diagnostics)
        } catch (error: R8RetraceProviderFailure) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            ensureActive()
            throw R8RetraceProviderFailure(
                R8RetraceErrorCode.RETRACE_FAILED,
                R8RetraceFailurePhase.RETRACING,
                "R8 retrace failed",
                error,
                diagnostics.snapshot(),
            )
        }
        ensureActive()
        val canonical = retraced.joinToString("\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .let { value -> if (value.endsWith('\n')) value else "$value\n" }
        val bytes = canonical.toByteArray(StandardCharsets.UTF_8)
        if (canonical.all(Char::isWhitespace)) {
            throw R8RetraceProviderFailure(
                R8RetraceErrorCode.RETRACE_FAILED,
                R8RetraceFailurePhase.OUTPUT_VALIDATION,
                "R8 produced no retraced stack content",
                diagnostics = diagnostics.snapshot(),
            )
        }
        if (bytes.size.toLong() > request.maxOutputBytes ||
            bytes.size.toLong() > capabilities.limits.maxRetracedStackTraceBytes
        ) {
            throw R8RetraceProviderFailure(
                R8RetraceErrorCode.OUTPUT_TOO_LARGE,
                R8RetraceFailurePhase.OUTPUT_VALIDATION,
                "Retraced stack exceeds its admitted limit",
                diagnostics = diagnostics.snapshot(),
            )
        }
        try {
            R8ArtifactContentValidation.validateText(bytes, requireContent = true)
        } catch (error: IllegalArgumentException) {
            throw R8RetraceProviderFailure(
                R8RetraceErrorCode.RETRACE_FAILED,
                R8RetraceFailurePhase.OUTPUT_VALIDATION,
                "R8 produced a non-canonical retraced stack",
                error,
                diagnostics.snapshot(),
            )
        }
        return ProducedR8Retrace(bytes, R8Sha256.digest(bytes), diagnostics.snapshot())
    }

    private fun decodeStackLines(bytes: ByteArray): List<String> {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val withoutFinalLf = text.removeSuffix("\n")
        if (withoutFinalLf.isEmpty()) {
            throw R8RetraceProviderFailure(
                R8RetraceErrorCode.INVALID_STACK_TRACE,
                R8RetraceFailurePhase.INPUT_VALIDATION,
                "Obfuscated stack trace is empty",
            )
        }
        return withoutFinalLf.split('\n')
    }
}

internal fun interface R8RetraceCommandRunner {
    fun run(
        mappingBytes: ByteArray,
        obfuscatedStackTrace: List<String>,
        diagnostics: DiagnosticsHandler,
    ): List<String>
}

private object AndroidR8RetraceCommandRunner : R8RetraceCommandRunner {
    override fun run(
        mappingBytes: ByteArray,
        obfuscatedStackTrace: List<String>,
        diagnostics: DiagnosticsHandler,
    ): List<String> = if (Build.VERSION.SDK_INT in 1 until Build.VERSION_CODES.O) {
        Api25CompatibleR8RetraceCommandRunner.run(mappingBytes, obfuscatedStackTrace, diagnostics)
    } else {
        ModernAndroidR8RetraceCommandRunner.run(mappingBytes, obfuscatedStackTrace, diagnostics)
    }
}

private object ModernAndroidR8RetraceCommandRunner : R8RetraceCommandRunner {
    override fun run(
        mappingBytes: ByteArray,
        obfuscatedStackTrace: List<String>,
        diagnostics: DiagnosticsHandler,
    ): List<String> {
        val mappingSupplier = ProguardMappingSupplier.builder()
            .setProguardMapProducer(ProguardMapProducer.fromBytes(mappingBytes))
            .setLoadAllDefinitions(true)
            .build()
        val result = AtomicReference<List<String>?>()
        val command = RetraceCommand.builder(diagnostics)
            .setMappingSupplier(mappingSupplier)
            .setStackTrace(obfuscatedStackTrace)
            .setVerbose(false)
            .setRetracedStackTraceConsumer { lines ->
                check(result.compareAndSet(null, lines.toList())) {
                    "R8 retrace produced more than one terminal output"
                }
            }
            .build()
        Retrace.run(command)
        return checkNotNull(result.get()) { "R8 retrace produced no output" }
    }
}
