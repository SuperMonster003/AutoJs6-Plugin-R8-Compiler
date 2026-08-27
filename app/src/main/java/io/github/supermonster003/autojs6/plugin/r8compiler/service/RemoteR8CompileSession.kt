package io.github.supermonster003.autojs6.plugin.r8compiler.service

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import io.github.supermonster003.autojs6.plugin.r8compiler.MaterializedR8Inputs
import io.github.supermonster003.autojs6.plugin.r8compiler.PrivateSessionWorkspace
import io.github.supermonster003.autojs6.plugin.r8compiler.ProducedR8Artifact
import io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerEngine
import io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerFailure
import io.github.supermonster003.autojs6.plugin.r8compiler.R8InputMaterializer
import io.github.supermonster003.autojs6.plugin.r8compiler.RuntimeLibrarySet
import org.autojs.plugin.r8compiler.api.IR8CompilerCallback
import org.autojs.plugin.r8compiler.api.IR8CompilerSession
import org.autojs.plugin.r8compiler.api.R8Cancellation
import org.autojs.plugin.r8compiler.api.R8CancellationReason
import org.autojs.plugin.r8compiler.api.R8CompileRequest
import org.autojs.plugin.r8compiler.api.R8CompilerCapabilities
import org.autojs.plugin.r8compiler.api.R8CompilerCodec
import org.autojs.plugin.r8compiler.api.R8CompilerValidation
import org.autojs.plugin.r8compiler.api.R8ContractException
import org.autojs.plugin.r8compiler.api.R8ContractViolation
import org.autojs.plugin.r8compiler.api.R8Error
import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import org.autojs.plugin.r8compiler.api.R8Progress
import org.autojs.plugin.r8compiler.api.R8ProgressStage
import org.autojs.plugin.r8compiler.api.R8RequestId
import org.autojs.plugin.r8compiler.api.R8Result
import org.autojs.plugin.r8compiler.api.R8Started
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class RemoteR8CompileSession(
    private val context: Context,
    private val ownerUid: Int,
    requestMetadata: ByteArray,
    private val descriptors: OwnedParcelFileDescriptors,
    private val callback: IR8CompilerCallback,
    private val capabilities: R8CompilerCapabilities,
    runtimeLibraries: RuntimeLibrarySet,
    private val callerVerifier: HostCallerVerifier,
    private val worker: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val callbackLane: SerialCallbackLane,
    private val onFinished: (RemoteR8ServiceSession) -> Unit,
) : IR8CompilerSession.Stub(), RemoteR8ServiceSession {
    private val decodedRequest: Result<R8CompileRequest> = try {
        Result.success(R8CompilerCodec.decodeRequest(requestMetadata))
    } catch (error: VirtualMachineError) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
    private val requestIdHint = decodedRequest.getOrNull()?.requestId ?: ZERO_REQUEST_ID
    private val engine = R8CompilerEngine(runtimeLibraries)
    private val outputClaimed = AtomicBoolean(false)
    private val callbackDeathLinked = AtomicBoolean(false)
    private val workScheduled = AtomicBoolean(false)
    private val workerThread = AtomicReference<Thread?>()
    private val timeoutFuture = AtomicReference<ScheduledFuture<*>?>()
    private val currentPhase = AtomicReference(R8FailurePhase.INPUT_VALIDATION)
    private val terminalController = RemoteSessionTerminalController(
        stopWork = ::stopWork,
        cleanup = ::cleanupResources,
    )
    private val sequence = AtomicLong(0L)
    private val createdAtMillis = SystemClock.elapsedRealtime()
    private val callbackBinder = callback.asBinder()
    private val callbackDeathRecipient = IBinder.DeathRecipient(::callbackDied)

    @Volatile
    private var workspace: PrivateSessionWorkspace? = null

    override fun cancel() {
        callerVerifier.enforceSessionOwner(ownerUid)
        finishCancellation(R8CancellationReason.REQUESTED)
    }

    override fun close() {
        callerVerifier.enforceSessionOwner(ownerUid)
        finishCancellation(R8CancellationReason.SESSION_CLOSED)
    }

    override fun start() {
        if (!terminalController.runBeforeWorker(::linkCallbackDeath)) {
            cleanup()
            return
        }
        submitWorker()
    }

    override fun rejectBusy() {
        currentPhase.set(R8FailurePhase.NEGOTIATION)
        if (!terminalController.runBeforeWorker(::linkCallbackDeath)) {
            cleanup()
            return
        }
        terminalController.finishWithoutWorker {
            finishError(
                requestIdHint,
                R8ErrorCode.BUSY,
                R8FailurePhase.NEGOTIATION,
                "R8 compiler already has an active session",
            )
        }
    }

    override fun serviceDestroyed() {
        abortAndCleanupIfNoWorker()
    }

    private fun submitWorker() {
        workScheduled.set(true)
        try {
            worker.execute {
                workerThread.set(Thread.currentThread())
                try {
                    runCompilation()
                } finally {
                    workerThread.compareAndSet(Thread.currentThread(), null)
                    cleanup()
                }
            }
        } catch (error: RejectedExecutionException) {
            workScheduled.set(false)
            terminalController.finishWithoutWorker {
                finishError(
                    requestIdHint,
                    R8ErrorCode.INTERNAL,
                    R8FailurePhase.NEGOTIATION,
                    "R8 compiler worker is unavailable",
                )
            }
        } catch (error: Throwable) {
            workScheduled.set(false)
            try {
                terminalController.abort()
            } finally {
                cleanup()
            }
            throw error
        }
    }

    private fun runCompilation() {
        val request = try {
            decodedRequest.getOrThrow().also { R8CompilerValidation.validateRequestAgainst(it, capabilities) }
        } catch (error: R8ContractException) {
            finishContractError(error)
            return
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            finishError(
                requestIdHint,
                R8ErrorCode.INVALID_REQUEST,
                R8FailurePhase.INPUT_VALIDATION,
                "R8 request metadata is malformed",
            )
            return
        }

        try {
            armTimeout(request.timeoutMillis)
            ensureActive()
            emitStarted(request)
            currentPhase.set(R8FailurePhase.INPUT_VALIDATION)
            emitProgress(request, R8ProgressStage.VALIDATING)
            ensureActive()

            val privateWorkspace = PrivateSessionWorkspace.create(context).also { workspace = it }
            val materialized = readInputs(request, privateWorkspace)

            currentPhase.set(R8FailurePhase.COMPILATION)
            emitProgress(request, R8ProgressStage.COMPILING)
            ensureActive()
            val artifact = engine.compile(
                request = request,
                capabilities = capabilities,
                inputs = materialized,
                workspace = privateWorkspace,
                isActive = ::isActive,
                ensureActive = ::ensureActive,
                beforePackaging = {
                    currentPhase.set(R8FailurePhase.OUTPUT_PACKAGING)
                    emitProgress(request, R8ProgressStage.PACKAGING)
                },
            )

            currentPhase.set(R8FailurePhase.OUTPUT_WRITE)
            emitProgress(request, R8ProgressStage.WRITING)
            ensureActive()
            writeArtifact(artifact)
            ensureActive()
            finishCompleted(request, artifact)
        } catch (_: SessionStopped) {
            Unit
        } catch (error: R8CompilerFailure) {
            finishError(request.requestId, error.code, error.phase, error.message ?: "R8 compilation failed", error.diagnostics)
        } catch (error: IOException) {
            if (!terminalController.isTerminal) {
                finishError(
                    request.requestId,
                    R8ErrorCode.INTERNAL,
                    currentPhase.get(),
                    "R8 provider storage operation failed",
                )
            }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            if (!terminalController.isTerminal) {
                finishError(
                    request.requestId,
                    R8ErrorCode.INTERNAL,
                    currentPhase.get(),
                    "Unexpected R8 provider failure",
                )
            }
        }
    }

    private fun readInputs(
        request: R8CompileRequest,
        privateWorkspace: PrivateSessionWorkspace,
    ): MaterializedR8Inputs {
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptors.input)
        return try {
            R8InputMaterializer.materialize(
                request,
                capabilities,
                input,
                privateWorkspace,
                ::ensureActive,
            ).also { descriptors.input.checkError() }
        } finally {
            input.close()
        }
    }

    private fun armTimeout(timeoutMillis: Long) {
        val future = scheduler.schedule(::timeout, timeoutMillis, TimeUnit.MILLISECONDS)
        if (!timeoutFuture.compareAndSet(null, future)) {
            future.cancel(false)
            throw IllegalStateException("R8 timeout was already armed")
        }
    }

    private fun timeout() {
        finishError(
            requestIdHint,
            R8ErrorCode.TIMEOUT,
            currentPhase.get(),
            "R8 request exceeded its explicit deadline",
            stopWorkAfterDispatch = true,
        )
    }

    private fun writeArtifact(artifact: ProducedR8Artifact) {
        check(outputClaimed.compareAndSet(false, true)) { "R8 output descriptor may only be claimed once" }
        var copied = 0L
        try {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptors.output)
            try {
                artifact.file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        copied = Math.addExact(copied, read.toLong())
                    }
                }
                output.flush()
                descriptors.output.checkError()
            } finally {
                output.close()
            }
        } catch (error: SessionStopped) {
            throw error
        } catch (error: IOException) {
            throw R8CompilerFailure(
                R8ErrorCode.INTERNAL,
                R8FailurePhase.OUTPUT_WRITE,
                "Failed to write the R8 artifact bundle",
                error,
            )
        }
        if (copied != artifact.summary.sizeBytes) {
            throw R8CompilerFailure(
                R8ErrorCode.INTERNAL,
                R8FailurePhase.OUTPUT_WRITE,
                "R8 artifact bundle changed while being written",
            )
        }
    }

    private fun emitStarted(request: R8CompileRequest) {
        val payload = R8CompilerCodec.encodeStarted(
            R8Started(
                requestId = request.requestId,
                sequence = sequence.getAndIncrement(),
                protocolVersion = request.protocolVersion,
                compilerFamily = request.compilerFamily,
                compilerVersion = capabilities.compilerVersion,
                capabilityFingerprint = capabilities.capabilityFingerprint,
                runtimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
                profile = request.profile,
                queueElapsedMillis = elapsedMillis(),
            ),
        )
        dispatchCallback("started") { callback.onStarted(payload) }
    }

    private fun emitProgress(request: R8CompileRequest, stage: R8ProgressStage) {
        val payload = R8CompilerCodec.encodeProgress(
            R8Progress(request.requestId, sequence.getAndIncrement(), stage),
        )
        dispatchCallback("progress") { callback.onProgress(payload) }
    }

    private fun finishCompleted(request: R8CompileRequest, artifact: ProducedR8Artifact) {
        terminalController.dispatchTerminal(
            encode = {
                R8CompilerCodec.encodeResult(
                    R8Result(
                        requestId = request.requestId,
                        compilerFamily = request.compilerFamily,
                        compilerVersion = capabilities.compilerVersion,
                        capabilityFingerprint = capabilities.capabilityFingerprint,
                        runtimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
                        inputSetFingerprint = request.inputSetFingerprint,
                        profile = request.profile,
                        minApi = request.minApi,
                        outputLayout = request.outputLayout,
                        outputBundleSizeBytes = artifact.summary.sizeBytes,
                        outputBundleSha256 = artifact.summary.contentSha256,
                        artifactIdentities = artifact.summary.identities,
                        determinismClaim = capabilities.determinismClaim,
                        elapsedMillis = elapsedMillis(),
                        diagnostics = artifact.diagnostics,
                    ),
                )
            },
            dispatch = { payload -> dispatchCallback("completed") { callback.onCompleted(payload) } },
        )
    }

    private fun finishContractError(error: R8ContractException) {
        val code = when (error.violation) {
            R8ContractViolation.PROTOCOL_INCOMPATIBLE -> R8ErrorCode.UNSUPPORTED_PROTOCOL
            R8ContractViolation.CAPABILITY_INCOMPATIBLE -> R8ErrorCode.UNSUPPORTED_CAPABILITY
            else -> R8ErrorCode.INVALID_REQUEST
        }
        val phase = if (code == R8ErrorCode.INVALID_REQUEST) {
            R8FailurePhase.INPUT_VALIDATION
        } else {
            R8FailurePhase.NEGOTIATION
        }
        finishError(requestIdHint, code, phase, "R8 request is incompatible with the provider")
    }

    private fun finishError(
        requestId: R8RequestId,
        code: R8ErrorCode,
        phase: R8FailurePhase,
        message: String,
        diagnostics: Collection<org.autojs.plugin.r8compiler.api.R8Diagnostic> = emptyList(),
        stopWorkAfterDispatch: Boolean = false,
    ) {
        terminalController.dispatchTerminal(
            stopWorkAfterDispatch = stopWorkAfterDispatch,
            encode = {
                val budget = decodedRequest.getOrNull()?.diagnosticByteLimit
                    ?.coerceAtMost(capabilities.limits.maxDiagnosticBytes)
                    ?: capabilities.limits.maxDiagnosticBytes
                val boundedMessage = boundedUtf8(message, budget)
                val admittedDiagnostics = diagnostics.toList().takeIf { values ->
                    val bytes = values.sumOf {
                        it.code.toByteArray(StandardCharsets.UTF_8).size +
                            it.message.toByteArray(StandardCharsets.UTF_8).size
                    }
                    bytes <= budget - boundedMessage.toByteArray(StandardCharsets.UTF_8).size
                }.orEmpty()
                R8CompilerCodec.encodeError(
                    R8Error(requestId, code, phase, boundedMessage, elapsedMillis(), admittedDiagnostics),
                )
            },
            dispatch = { payload -> dispatchCallback("failed") { callback.onFailed(payload) } },
        )
    }

    private fun finishCancellation(reason: R8CancellationReason) {
        terminalController.dispatchTerminal(
            stopWorkAfterDispatch = true,
            encode = {
                R8CompilerCodec.encodeCancellation(
                    R8Cancellation(requestIdHint, reason, R8FailurePhase.CLEANUP, elapsedMillis()),
                )
            },
            dispatch = { payload -> dispatchCallback("cancelled") { callback.onCancelled(payload) } },
        )
    }

    private fun dispatchCallback(label: String, block: () -> Unit) {
        callbackLane.dispatch(label, block, ::callbackFailed)
    }

    private fun callbackFailed(@Suppress("UNUSED_PARAMETER") error: Throwable) {
        abortAndCleanupIfNoWorker()
    }

    private fun callbackDied() {
        abortAndCleanupIfNoWorker()
    }

    private fun linkCallbackDeath(): Boolean {
        if (!callbackDeathLinked.compareAndSet(false, true)) return !terminalController.isTerminal
        try {
            callbackBinder.linkToDeath(callbackDeathRecipient, 0)
        } catch (_: RemoteException) {
            callbackDeathLinked.set(false)
            callbackDied()
            return false
        }
        if (!callbackBinder.isBinderAlive) {
            callbackDied()
            return false
        }
        return true
    }

    private fun unlinkCallbackDeath() {
        if (!callbackDeathLinked.compareAndSet(true, false)) return
        try {
            callbackBinder.unlinkToDeath(callbackDeathRecipient, 0)
        } catch (error: VirtualMachineError) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    private fun cleanup() {
        terminalController.cleanupOnce()
    }

    private fun abortAndCleanupIfNoWorker() {
        if (workScheduled.get()) terminalController.abort() else terminalController.abortWithoutWorker()
    }

    private fun cleanupResources() {
        timeoutFuture.getAndSet(null)?.cancel(false)
        try {
            descriptors.close()
        } finally {
            try {
                workspace?.close()
            } finally {
                workspace = null
                try {
                    unlinkCallbackDeath()
                } finally {
                    onFinished(this)
                }
            }
        }
    }

    private fun stopWork() {
        try {
            descriptors.close()
        } finally {
            workerThread.get()?.interrupt()
        }
    }

    private fun isActive(): Boolean = !terminalController.isTerminal && !Thread.currentThread().isInterrupted

    private fun ensureActive() {
        if (!isActive()) throw SessionStopped()
    }

    private fun elapsedMillis(): Long = (SystemClock.elapsedRealtime() - createdAtMillis).coerceAtLeast(0L)

    private fun boundedUtf8(message: String, maximumBytes: Int): String {
        val fallback = if (maximumBytes > 0) "E" else error("Diagnostic budget must be positive")
        val candidate = message.takeIf(String::isNotBlank) ?: fallback
        if (candidate.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) return candidate
        val output = StringBuilder()
        var index = 0
        var used = 0
        while (index < candidate.length) {
            val codePoint = candidate.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val bytes = piece.toByteArray(StandardCharsets.UTF_8).size
            if (used > maximumBytes - bytes) break
            output.append(piece)
            used += bytes
            index += Character.charCount(codePoint)
        }
        return output.toString().ifBlank { fallback.take(maximumBytes) }
    }

    private class SessionStopped : RuntimeException()

    private companion object {
        val ZERO_REQUEST_ID = R8RequestId.fromBytes(ByteArray(R8RequestId.BYTE_COUNT))
    }
}
