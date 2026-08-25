package io.github.supermonster003.autojs6.plugin.r8compiler

import android.annotation.TargetApi
import android.annotation.SuppressLint
import android.os.Build
import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.StringConsumer
import org.autojs.plugin.r8compiler.api.R8ArtifactRole
import org.autojs.plugin.r8compiler.api.R8CompileRequest
import org.autojs.plugin.r8compiler.api.R8CompilerCapabilities
import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class R8CompilerEngine(
    private val runtimeLibraries: RuntimeLibrarySet,
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
    private val commandRunner: R8CommandRunner = AndroidR8CommandRunner,
    private val cliRunner: R8CliRunner = AndroidR8CliRunner,
) {
    fun compile(
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
        inputs: MaterializedR8Inputs,
        workspace: PrivateSessionWorkspace,
        isActive: () -> Boolean,
        ensureActive: () -> Unit,
        beforePackaging: () -> Unit,
    ): ProducedR8Artifact {
        val diagnostics = R8DiagnosticCollector(
            minOf(request.diagnosticByteLimit, capabilities.limits.maxDiagnosticBytes),
        )
        ensureActive()
        try {
            if (sdkInt() >= Build.VERSION_CODES.O) {
                commandRunner.run(
                    request,
                    inputs,
                    workspace,
                    runtimeLibraries.files,
                    diagnostics,
                    isActive,
                )
            } else {
                writeProviderControlRules(workspace)
                cliRunner.run(cliArguments(request, inputs, workspace, runtimeLibraries.files))
            }
        } catch (error: CompilationFailedException) {
            ensureActive()
            throw R8CompilerFailure(
                R8ErrorCode.COMPILATION_FAILED,
                R8FailurePhase.COMPILATION,
                "R8 compilation failed",
                error,
                diagnostics.snapshot(),
            )
        } catch (error: ReportLimitExceeded) {
            throw R8CompilerFailure(
                R8ErrorCode.OUTPUT_TOO_LARGE,
                R8FailurePhase.OUTPUT_PACKAGING,
                "An R8 report exceeds its admitted limit",
                error,
                diagnostics.snapshot(),
            )
        } catch (error: R8CompilerFailure) {
            throw error
        } catch (error: IOException) {
            throw R8CompilerFailure(
                R8ErrorCode.INTERNAL,
                R8FailurePhase.COMPILATION,
                "R8 could not access its private workspace",
                error,
                diagnostics.snapshot(),
            )
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            ensureActive()
            throw R8CompilerFailure(
                R8ErrorCode.COMPILATION_FAILED,
                R8FailurePhase.COMPILATION,
                "R8 compilation failed",
                error,
                diagnostics.snapshot(),
            )
        }
        ensureActive()
        beforePackaging()
        ensureActive()
        return R8ArtifactPackager.packageArtifacts(
            request,
            capabilities,
            workspace,
            diagnostics.snapshot(),
            ensureActive,
        )
    }

    private fun writeProviderControlRules(workspace: PrivateSessionWorkspace) {
        workspace.providerControlRules.writeText(
            buildString {
                append("-printmapping ").append(quotedRulePath(workspace.mapping)).append('\n')
                append("-printseeds ").append(quotedRulePath(workspace.seeds)).append('\n')
                append("-printusage ").append(quotedRulePath(workspace.usage)).append('\n')
            },
            Charsets.UTF_8,
        )
    }

    private fun quotedRulePath(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        if (path.any { it == '\n' || it == '\r' || it == '\u0000' || it == '\'' }) {
            throw IOException("Private R8 report path is not representable")
        }
        return "'$path'"
    }

    private fun cliArguments(
        request: R8CompileRequest,
        inputs: MaterializedR8Inputs,
        workspace: PrivateSessionWorkspace,
        runtimeLibraries: List<File>,
    ): Array<String> = buildList {
        add("--release")
        add("--min-api")
        add(request.minApi.toString())
        add("--output")
        add(workspace.r8OutputDirectory.absolutePath)
        runtimeLibraries.forEach { add("--lib"); add(it.absolutePath) }
        inputs.classpathJars.forEach { add("--classpath"); add(it.absolutePath) }
        inputs.rules.forEach { add("--pg-conf"); add(it.file.absolutePath) }
        add("--pg-conf")
        add(workspace.providerControlRules.absolutePath)
        add(inputs.programJar.absolutePath)
    }.toTypedArray()
}

internal fun interface R8CliRunner {
    fun run(arguments: Array<String>)
}

internal fun interface R8CommandRunner {
    fun run(
        request: R8CompileRequest,
        inputs: MaterializedR8Inputs,
        workspace: PrivateSessionWorkspace,
        runtimeLibraries: List<File>,
        diagnostics: DiagnosticsHandler,
        isActive: () -> Boolean,
    )
}

private object AndroidR8CommandRunner : R8CommandRunner {
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.O)
    override fun run(
        request: R8CompileRequest,
        inputs: MaterializedR8Inputs,
        workspace: PrivateSessionWorkspace,
        runtimeLibraries: List<File>,
        diagnostics: DiagnosticsHandler,
        isActive: () -> Boolean,
    ) {
        val mappingConsumer = CanonicalTextConsumer(
            workspace.mapping,
            request.requestedArtifacts.single { it.role == R8ArtifactRole.MAPPING_TEXT }.maxBytes,
        )
        val seedsConsumer = CanonicalTextConsumer(
            workspace.seeds,
            request.requestedArtifacts.single { it.role == R8ArtifactRole.SEEDS_TEXT }.maxBytes,
        )
        val usageConsumer = CanonicalTextConsumer(
            workspace.usage,
            request.requestedArtifacts.single { it.role == R8ArtifactRole.USAGE_TEXT }.maxBytes,
        )
        try {
            val builder = R8Command.builder(diagnostics)
                .addProgramFiles(inputs.programJar.toPath())
                .setOutput(workspace.r8OutputDirectory.toPath(), OutputMode.DexIndexed)
                .setMode(CompilationMode.RELEASE)
                .setMinApiLevel(request.minApi)
                .setDisableTreeShaking(false)
                .setDisableMinification(false)
                .setProguardCompatibility(false)
                .setProguardMapConsumer(mappingConsumer)
                .setProguardSeedsConsumer(seedsConsumer)
                .setProguardUsageConsumer(usageConsumer)
                .setCancelCompilationChecker { !isActive() }
            runtimeLibraries.forEach { builder.addLibraryFiles(it.toPath()) }
            inputs.classpathJars.forEach { builder.addClasspathFiles(it.toPath()) }
            inputs.rules.forEach { builder.addProguardConfigurationFiles(it.file.toPath()) }
            R8.run(builder.build())
            mappingConsumer.finishIfNeeded()
            seedsConsumer.finishIfNeeded()
            usageConsumer.finishIfNeeded()
        } catch (error: Throwable) {
            mappingConsumer.discard()
            seedsConsumer.discard()
            usageConsumer.discard()
            throw error
        }
    }
}

private object AndroidR8CliRunner : R8CliRunner {
    override fun run(arguments: Array<String>) = R8.main(arguments)
}

private class CanonicalTextConsumer(
    private val destination: File,
    maximumBytes: Long,
) : StringConsumer {
    private val maximum = maximumBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val content = StringBuilder()
    private var finished = false

    @Synchronized
    override fun accept(value: String, handler: DiagnosticsHandler) {
        check(!finished) { "R8 report consumer is already finished" }
        if (value.length > maximum - content.length) throw ReportLimitExceeded()
        content.append(value)
    }

    @Synchronized
    override fun finished(handler: DiagnosticsHandler) {
        finishIfNeeded()
    }

    @Synchronized
    fun finishIfNeeded() {
        if (finished) return
        var canonical = content.toString().replace("\r\n", "\n").replace('\r', '\n')
        if (canonical.isNotEmpty() && !canonical.endsWith('\n')) canonical += '\n'
        val bytes = canonical.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maximum) throw ReportLimitExceeded()
        destination.writeBytes(bytes)
        finished = true
        content.clear()
    }

    @Synchronized
    fun discard() {
        content.clear()
        destination.delete()
        finished = true
    }
}

private class ReportLimitExceeded : RuntimeException()
