package io.github.supermonster003.autojs6.plugin.r8compiler.service

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import io.github.supermonster003.autojs6.plugin.r8compiler.ProducedR8Retrace
import io.github.supermonster003.autojs6.plugin.r8compiler.R8RetraceEngine
import io.github.supermonster003.autojs6.plugin.r8compiler.R8RetraceProviderFailure
import org.autojs.plugin.r8compiler.api.IR8CompilerCallback
import org.autojs.plugin.r8compiler.api.IR8CompilerSession
import org.autojs.plugin.r8compiler.api.R8BundleError
import org.autojs.plugin.r8compiler.api.R8BundleException
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8ContractException
import org.autojs.plugin.r8compiler.api.R8ContractViolation
import org.autojs.plugin.r8compiler.api.R8Diagnostic
import org.autojs.plugin.r8compiler.api.R8RequestId
import org.autojs.plugin.r8compiler.api.R8RetraceCancellation
import org.autojs.plugin.r8compiler.api.R8RetraceCancellationReason
import org.autojs.plugin.r8compiler.api.R8RetraceCapabilities
import org.autojs.plugin.r8compiler.api.R8RetraceCodec
import org.autojs.plugin.r8compiler.api.R8RetraceError
import org.autojs.plugin.r8compiler.api.R8RetraceErrorCode
import org.autojs.plugin.r8compiler.api.R8RetraceFailurePhase
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundle
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundleCodec
import org.autojs.plugin.r8compiler.api.R8RetraceInputRole
import org.autojs.plugin.r8compiler.api.R8RetraceProgress
import org.autojs.plugin.r8compiler.api.R8RetraceProgressStage
import org.autojs.plugin.r8compiler.api.R8RetraceRequest
import org.autojs.plugin.r8compiler.api.R8RetraceResult
import org.autojs.plugin.r8compiler.api.R8RetraceStarted
import org.autojs.plugin.r8compiler.api.R8RetraceValidation
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

internal class RemoteR8RetraceSession(
    private val ownerUid: Int,
    requestMetadata: ByteArray,
    private val descriptors: OwnedParcelFileDescriptors,
    private val callback: IR8CompilerCallback,
    private val capabilities: R8RetraceCapabilities,
    private val callerVerifier: HostCallerVerifier,
    private val worker: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val callbackLane: SerialCallbackLane,
    private val onFinished: (RemoteR8ServiceSession) -> Unit,
    private val engine: R8RetraceEngine = R8RetraceEngine(),
) : IR8CompilerSession.Stub(), RemoteR8ServiceSession {
    private val decodedRequest: Result<R8RetraceRequest> = try {
        Result.success(R8RetraceCodec.decodeRequest(requestMetadata))
    } catch (error: VirtualMachineError) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
    private val requestIdHint = decodedRequest.getOrNull()?.requestId ?: ZERO_REQUEST_ID
    private val outputClaimed = AtomicBoolean(false)
    private val callbackDeathLinked = AtomicBoolean(false)
    private val workScheduled = AtomicBoolean(false)
    private val workerThread = AtomicReference<Thread?>()
    private val timeoutFuture = AtomicReference<ScheduledFuture<*>?>()
    private val currentPhase = AtomicReference(R8RetraceFailurePhase.INPUT_VALIDATION)
    private val terminalController = RemoteSessionTerminalController(
        stopWork = ::stopWork,
        cleanup = ::cleanupResources,
    )
    private val sequence = AtomicLong(0L)
    private val createdAtMillis = SystemClock.elapsedRealtime()
    private val callbackBinder = callback.asBinder()
    private val callbackDeathRecipient = IBinder.DeathRecipient(::callbackDied)

    override fun cancel() {
        callerVerifier.enforceSessionOwner(ownerUid)
        finishCancellation(R8RetraceCancellationReason.REQUESTED)
    }

    override fun close() {
        callerVerifier.enforceSessionOwner(ownerUid)
        finishCancellation(R8RetraceCancellationReason.SESSION_CLOSED)
    }

    override fun start() {
        if (!terminalController.runBeforeWorker(::linkCallbackDeath)) {
            cleanup()
            return
        }
        submitWorker()
    }

    override fun rejectBusy() {
        currentPhase.set(R8RetraceFailurePhase.NEGOTIATION)
        if (!terminalController.runBeforeWorker(::linkCallbackDeath)) {
            cleanup()
            return
        }
        terminalController.finishWithoutWorker {
            finishError(
                requestIdHint,
                R8RetraceErrorCode.BUSY,
                R8RetraceFailurePhase.NEGOTIATION,
                "R8 provider already has an active compile or retrace session",
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
                    runRetrace()
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
                    R8RetraceErrorCode.INTERNAL,
                    R8RetraceFailurePhase.NEGOTIATION,
                    "R8 retrace worker is unavailable",
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

    private fun runRetrace() {
        val request = try {
            decodedRequest.getOrThrow().also {
                R8RetraceValidation.validateRequestAgainst(it, capabilities)
            }
        } catch (error: R8ContractException) {
            finishContractError(error)
            return
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            finishError(
                requestIdHint,
                R8RetraceErrorCode.INVALID_REQUEST,
                R8RetraceFailurePhase.INPUT_VALIDATION,
                "R8 retrace request metadata is malformed",
            )
            return
        }

        try {
            armTimeout(request.timeoutMillis)
            ensureActive()
            emitStarted(request)
            currentPhase.set(R8RetraceFailurePhase.INPUT_VALIDATION)
            emitProgress(request, R8RetraceProgressStage.VALIDATING)
            val input = readInput(request)
            ensureActive()

            currentPhase.set(R8RetraceFailurePhase.RETRACING)
            emitProgress(request, R8RetraceProgressStage.RETRACING)
            val produced = engine.retrace(request, capabilities, input, ::ensureActive)
            ensureActive()

            currentPhase.set(R8RetraceFailurePhase.OUTPUT_WRITE)
            emitProgress(request, R8RetraceProgressStage.WRITING)
            writeOutput(produced)
            ensureActive()
            finishCompleted(request, produced)
        } catch (_: SessionStopped) {
            Unit
        } catch (error: R8RetraceProviderFailure) {
            finishError(
                request.requestId,
                error.code,
                error.phase,
                error.message ?: "R8 retrace failed",
                error.diagnostics,
            )
        } catch (error: R8BundleException) {
            val code = when (error.error) {
                R8BundleError.LIMIT_EXCEEDED -> R8RetraceErrorCode.INPUT_TOO_LARGE
                R8BundleError.CONTENT_MISMATCH -> R8RetraceErrorCode.MAPPING_MISMATCH
                else -> R8RetraceErrorCode.INVALID_BUNDLE
            }
            finishError(
                request.requestId,
                code,
                R8RetraceFailurePhase.INPUT_VALIDATION,
                "R8 retrace input bundle failed validation",
            )
        } catch (error: R8ContractException) {
            finishError(
                request.requestId,
                R8RetraceErrorCode.METADATA_MISMATCH,
                R8RetraceFailurePhase.PROVENANCE_VALIDATION,
                "R8 retrace mapping provenance failed validation",
            )
        } catch (error: IOException) {
            if (!terminalController.isTerminal) {
                finishError(
                    request.requestId,
                    R8RetraceErrorCode.INTERNAL,
                    currentPhase.get(),
                    "R8 retrace descriptor operation failed",
                )
            }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            if (!terminalController.isTerminal) {
                finishError(
                    request.requestId,
                    R8RetraceErrorCode.INTERNAL,
                    currentPhase.get(),
                    "Unexpected R8 retrace provider failure",
                )
            }
        }
    }

    private fun readInput(request: R8RetraceRequest): R8RetraceInputBundle {
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptors.input)
        return try {
            R8RetraceInputBundleCodec.read(input, request, capabilities)
                .also { descriptors.input.checkError() }
        } finally {
            input.close()
        }
    }

    private fun writeOutput(produced: ProducedR8Retrace) {
        check(outputClaimed.compareAndSet(false, true)) {
            "R8 retrace output descriptor may only be claimed once"
        }
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptors.output)
        try {
            output.write(produced.bytes)
            output.flush()
            descriptors.output.checkError()
        } finally {
            output.close()
        }
    }

    private fun armTimeout(timeoutMillis: Long) {
        val future = scheduler.schedule(::timeout, timeoutMillis, TimeUnit.MILLISECONDS)
        if (!timeoutFuture.compareAndSet(null, future)) {
            future.cancel(false)
            throw IllegalStateException("R8 retrace timeout was already armed")
        }
    }

    private fun timeout() {
        finishError(
            requestIdHint,
            R8RetraceErrorCode.TIMEOUT,
            currentPhase.get(),
            "R8 retrace request exceeded its explicit deadline",
            stopWorkAfterDispatch = true,
        )
    }

    private fun emitStarted(request: R8RetraceRequest) {
        val payload = R8RetraceCodec.encodeStarted(
            R8RetraceStarted(
                request.requestId,
                sequence.getAndIncrement(),
                request.protocolVersion,
                capabilities.compilerVersion,
                capabilities.capabilityFingerprint,
                request.mappingProvenanceId,
                elapsedMillis(),
            ),
        )
        dispatchCallback("retrace-started") { callback.onStarted(payload) }
    }

    private fun emitProgress(request: R8RetraceRequest, stage: R8RetraceProgressStage) {
        val payload = R8RetraceCodec.encodeProgress(
            R8RetraceProgress(request.requestId, sequence.getAndIncrement(), stage),
        )
        dispatchCallback("retrace-progress") { callback.onProgress(payload) }
    }

    private fun finishCompleted(request: R8RetraceRequest, produced: ProducedR8Retrace) {
        terminalController.dispatchTerminal(
            encode = {
                val stackIdentity = request.inputIdentities.single {
                    it.role == R8RetraceInputRole.OBFUSCATED_STACK_TRACE
                }
                R8RetraceCodec.encodeResult(
                    R8RetraceResult(
                        request.requestId,
                        request.protocolVersion,
                        capabilities.compilerVersion,
                        capabilities.capabilityFingerprint,
                        request.mappingProvenanceId,
                        stackIdentity.contentSha256,
                        request.outputLayout,
                        produced.bytes.size.toLong(),
                        produced.sha256,
                        elapsedMillis(),
                        produced.diagnostics,
                    ),
                )
            },
            dispatch = { payload ->
                dispatchCallback("retrace-completed") { callback.onCompleted(payload) }
            },
        )
    }

    private fun finishContractError(error: R8ContractException) {
        val code = when (error.violation) {
            R8ContractViolation.PROTOCOL_INCOMPATIBLE -> R8RetraceErrorCode.UNSUPPORTED_PROTOCOL
            R8ContractViolation.CAPABILITY_INCOMPATIBLE -> R8RetraceErrorCode.UNSUPPORTED_CAPABILITY
            else -> R8RetraceErrorCode.INVALID_REQUEST
        }
        val phase = if (code == R8RetraceErrorCode.INVALID_REQUEST) {
            R8RetraceFailurePhase.INPUT_VALIDATION
        } else {
            R8RetraceFailurePhase.NEGOTIATION
        }
        finishError(requestIdHint, code, phase, "R8 retrace request is incompatible with the provider")
    }

    private fun finishError(
        requestId: R8RequestId,
        code: R8RetraceErrorCode,
        phase: R8RetraceFailurePhase,
        message: String,
        diagnostics: Collection<R8Diagnostic> = emptyList(),
        stopWorkAfterDispatch: Boolean = false,
    ) {
        terminalController.dispatchTerminal(
            stopWorkAfterDispatch = stopWorkAfterDispatch,
            encode = {
                val budget = decodedRequest.getOrNull()?.diagnosticByteLimit
                    ?.coerceAtMost(capabilities.limits.maxDiagnosticBytes)
                    ?: capabilities.limits.maxDiagnosticBytes
                val boundedMessage = boundedUtf8(message, budget)
                val remaining = budget - boundedMessage.toByteArray(StandardCharsets.UTF_8).size
                var used = 0
                val admitted = diagnostics.takeWhile { diagnostic ->
                    val cost = diagnostic.code.toByteArray(StandardCharsets.UTF_8).size +
                        diagnostic.message.toByteArray(StandardCharsets.UTF_8).size
                    if (cost > remaining - used) false else {
                        used += cost
                        true
                    }
                }
                R8RetraceCodec.encodeError(
                    R8RetraceError(
                        requestId,
                        code,
                        phase,
                        boundedMessage,
                        elapsedMillis(),
                        admitted,
                    ),
                )
            },
            dispatch = { payload ->
                dispatchCallback("retrace-failed") { callback.onFailed(payload) }
            },
        )
    }

    private fun finishCancellation(reason: R8RetraceCancellationReason) {
        terminalController.dispatchTerminal(
            stopWorkAfterDispatch = true,
            encode = {
                R8RetraceCodec.encodeCancellation(
                    R8RetraceCancellation(
                        requestIdHint,
                        reason,
                        currentPhase.get(),
                        elapsedMillis(),
                    ),
                )
            },
            dispatch = { payload ->
                dispatchCallback("retrace-cancelled") { callback.onCancelled(payload) }
            },
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
                unlinkCallbackDeath()
            } finally {
                onFinished(this)
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

    private fun isActive(): Boolean =
        !terminalController.isTerminal && !Thread.currentThread().isInterrupted

    private fun ensureActive() {
        if (!isActive()) throw SessionStopped()
    }

    private fun elapsedMillis(): Long =
        (SystemClock.elapsedRealtime() - createdAtMillis).coerceAtLeast(0L)

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
