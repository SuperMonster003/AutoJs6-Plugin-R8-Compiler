package io.github.supermonster003.autojs6.plugin.r8compiler.service

import android.util.Log
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class SerialCallbackLane : Closeable {
    private val executor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_CALLBACKS),
        { runnable -> Thread(runnable, "r8-compiler-callback").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun dispatch(label: String, callback: () -> Unit, onFailure: (Throwable) -> Unit) {
        try {
            executor.execute {
                try {
                    callback()
                } catch (error: VirtualMachineError) {
                    rethrowCallbackVirtualMachineError(error, onFailure)
                } catch (error: Throwable) {
                    onFailure(error)
                    try {
                        Log.w(TAG, "R8 compiler callback failed: $label", error)
                    } catch (logFailure: VirtualMachineError) {
                        throw logFailure
                    } catch (_: Throwable) {
                        Unit
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            onFailure(error)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "R8CompilerCallbackLane"
        const val MAX_PENDING_CALLBACKS = 64
    }
}

internal fun rethrowCallbackVirtualMachineError(
    error: VirtualMachineError,
    onFailure: (Throwable) -> Unit,
): Nothing {
    try {
        onFailure(error)
    } catch (failure: Throwable) {
        if (failure !== error) {
            try {
                error.addSuppressed(failure)
            } catch (_: Throwable) {
                Unit
            }
        }
    }
    throw error
}
