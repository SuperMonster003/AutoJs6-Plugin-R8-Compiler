package io.github.supermonster003.autojs6.plugin.r8compiler.service

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class OwnedParcelFileDescriptors private constructor(
    val input: ParcelFileDescriptor,
    val output: ParcelFileDescriptor,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(input)
        closeQuietly(output)
    }

    companion object {
        private const val TAG = "R8CompilerPfdOwner"

        fun duplicateAndValidate(
            incomingInput: ParcelFileDescriptor,
            incomingOutput: ParcelFileDescriptor,
        ): OwnedParcelFileDescriptors {
            var inputCopy: ParcelFileDescriptor? = null
            var outputCopy: ParcelFileDescriptor? = null
            try {
                inputCopy = ParcelFileDescriptor.dup(incomingInput.fileDescriptor)
                outputCopy = ParcelFileDescriptor.dup(incomingOutput.fileDescriptor)
                validateAccessAndAlias(inputCopy, outputCopy)
                return OwnedParcelFileDescriptors(inputCopy, outputCopy)
            } catch (error: Throwable) {
                closeQuietly(inputCopy)
                closeQuietly(outputCopy)
                throw error
            } finally {
                closeQuietly(incomingInput)
                closeQuietly(incomingOutput)
            }
        }

        fun closeIncoming(input: ParcelFileDescriptor?, output: ParcelFileDescriptor?) {
            closeQuietly(input)
            closeQuietly(output)
        }

        private fun validateAccessAndAlias(
            input: ParcelFileDescriptor,
            output: ParcelFileDescriptor,
        ) {
            if (!supportsRead(input) || supportsWrite(input)) {
                throw IllegalArgumentException("R8 input descriptor must be read-only")
            }
            if (!supportsWrite(output) || supportsRead(output)) {
                throw IllegalArgumentException("R8 output descriptor must be write-only")
            }
            val inputStat = Os.fstat(input.fileDescriptor)
            val outputStat = Os.fstat(output.fileDescriptor)
            if (inputStat.st_dev == outputStat.st_dev && inputStat.st_ino == outputStat.st_ino) {
                throw IllegalArgumentException("R8 input and output descriptors must not alias")
            }
        }

        /**
         * `Os.fcntlInt(F_GETFL)` is public only from API 30. A zero-byte read/write still makes the
         * kernel enforce the descriptor's access mode, but cannot consume, emit, or block on
         * payload bytes. This keeps the API 24 floor independent from procfs visibility.
         */
        private fun supportsRead(descriptor: ParcelFileDescriptor): Boolean =
            probeAccess { Os.read(descriptor.fileDescriptor, EMPTY_BYTES, 0, 0) }

        private fun supportsWrite(descriptor: ParcelFileDescriptor): Boolean =
            probeAccess { Os.write(descriptor.fileDescriptor, EMPTY_BYTES, 0, 0) }

        private inline fun probeAccess(operation: () -> Int): Boolean = try {
            val transferred = operation()
            if (transferred != 0) {
                throw IOException("Zero-byte descriptor probe transferred payload bytes")
            }
            true
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EBADF) false else throw error
        }

        private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
            runCatching { descriptor?.close() }.onFailure { error ->
                Log.w(TAG, "Failed to close an R8 compiler descriptor", error)
            }
        }

        private val EMPTY_BYTES = ByteArray(0)
    }
}
