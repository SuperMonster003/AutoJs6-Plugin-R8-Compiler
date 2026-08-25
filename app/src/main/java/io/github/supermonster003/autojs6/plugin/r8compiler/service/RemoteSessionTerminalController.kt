package io.github.supermonster003.autojs6.plugin.r8compiler.service

import java.util.concurrent.atomic.AtomicBoolean

/** Owns the one-way transition to an R8 session terminal state and exactly-once cleanup. */
internal class RemoteSessionTerminalController(
    private val stopWork: () -> Unit,
    private val cleanup: () -> Unit,
) {
    private val terminal = AtomicBoolean(false)
    private val stopAttempted = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)

    val isTerminal: Boolean
        get() = terminal.get()

    fun <T> dispatchTerminal(
        stopWorkAfterDispatch: Boolean = false,
        encode: () -> T,
        dispatch: (T) -> Unit,
    ): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        try {
            dispatch(encode())
        } catch (error: Throwable) {
            stopAndRethrow(error)
        }
        if (stopWorkAfterDispatch) requestStop()
        return true
    }

    fun abort(): Boolean {
        val claimed = terminal.compareAndSet(false, true)
        requestStop()
        return claimed
    }

    fun abortWithoutWorker() {
        finishWithoutWorker { abort() }
    }

    fun <T> runBeforeWorker(action: () -> T): T {
        try {
            return action()
        } catch (error: Throwable) {
            failWithoutWorker(error)
        }
    }

    fun finishWithoutWorker(action: () -> Unit) {
        var actionFailure: Throwable? = null
        try {
            action()
        } catch (error: Throwable) {
            actionFailure = error
        }
        var cleanupFailure: Throwable? = null
        try {
            cleanupOnce()
        } catch (error: Throwable) {
            cleanupFailure = error
        }
        when {
            actionFailure != null && cleanupFailure != null -> throw mergeFailures(actionFailure, cleanupFailure)
            actionFailure != null -> throw actionFailure
            cleanupFailure != null -> throw cleanupFailure
        }
    }

    fun cleanupOnce(): Boolean {
        if (!cleaned.compareAndSet(false, true)) return false
        cleanup()
        return true
    }

    private fun requestStop() {
        if (stopAttempted.compareAndSet(false, true)) stopWork()
    }

    private fun failWithoutWorker(error: Throwable): Nothing {
        var failure = error
        try {
            abort()
        } catch (stopFailure: Throwable) {
            failure = mergeFailures(failure, stopFailure)
        }
        try {
            cleanupOnce()
        } catch (cleanupFailure: Throwable) {
            failure = mergeFailures(failure, cleanupFailure)
        }
        throw failure
    }

    private fun stopAndRethrow(error: Throwable): Nothing {
        try {
            requestStop()
        } catch (stopFailure: Throwable) {
            throw mergeFailures(error, stopFailure)
        }
        throw error
    }

    private fun mergeFailures(primary: Throwable, secondary: Throwable): Throwable {
        if (primary === secondary) return primary
        val preferred = when {
            primary is VirtualMachineError -> primary
            secondary is VirtualMachineError -> secondary
            else -> primary
        }
        val suppressed = if (preferred === primary) secondary else primary
        runCatching { preferred.addSuppressed(suppressed) }
        return preferred
    }
}
