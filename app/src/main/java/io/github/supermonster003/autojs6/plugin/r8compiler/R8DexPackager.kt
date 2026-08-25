package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object R8DexPackager {
    fun packageOutput(
        outputDirectory: File,
        destination: File,
        maximumBytes: Long,
        maximumEntries: Int,
    ) {
        val children = outputDirectory.listFiles()
            ?: fail(R8ErrorCode.INTERNAL, "R8 output directory could not be listed")
        val indexed = children.map { file ->
            val index = dexIndex(file.name)
                ?: fail(R8ErrorCode.INTERNAL, "R8 produced an unexpected output file")
            if (!file.isFile || file.length() <= 0L) {
                fail(R8ErrorCode.COMPILATION_FAILED, "R8 produced an empty DEX file")
            }
            validateDex(file)
            index to file
        }.sortedBy { it.first }

        if (indexed.isEmpty()) fail(R8ErrorCode.COMPILATION_FAILED, "R8 produced no DEX files")
        if (indexed.size > maximumEntries) fail(R8ErrorCode.OUTPUT_TOO_LARGE, "R8 produced too many DEX files")
        indexed.forEachIndexed { index, value ->
            if (value.first != index + 1) fail(R8ErrorCode.INTERNAL, "R8 DEX indexes are not contiguous")
        }

        try {
            val limited = LimitedOutputStream(BufferedOutputStream(FileOutputStream(destination)), maximumBytes)
            ZipOutputStream(limited).use { zip ->
                indexed.forEach { (_, dex) ->
                    val entry = ZipEntry(dex.name).apply {
                        method = ZipEntry.STORED
                        size = dex.length()
                        compressedSize = dex.length()
                        crc = crc32(dex)
                        time = DOS_EPOCH_MILLIS
                        extra = byteArrayOf()
                    }
                    zip.putNextEntry(entry)
                    dex.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (error: OutputLimitExceeded) {
            destination.delete()
            throw R8CompilerFailure(
                R8ErrorCode.OUTPUT_TOO_LARGE,
                R8FailurePhase.OUTPUT_PACKAGING,
                "DEX ZIP exceeds its admitted limit",
                error,
            )
        } catch (error: IOException) {
            destination.delete()
            throw R8CompilerFailure(
                R8ErrorCode.INTERNAL,
                R8FailurePhase.OUTPUT_PACKAGING,
                "Failed to package R8 DEX output",
                error,
            )
        }
        if (destination.length() !in 1..maximumBytes) {
            destination.delete()
            fail(R8ErrorCode.OUTPUT_TOO_LARGE, "DEX ZIP is outside its admitted limit")
        }
    }

    internal fun dexIndex(name: String): Int? {
        if (name == "classes.dex") return 1
        if (!name.startsWith("classes") || !name.endsWith(".dex")) return null
        val digits = name.substring(7, name.length - 4)
        if (digits.isEmpty() || digits.startsWith('0') || digits.any { it !in '0'..'9' }) return null
        return digits.toIntOrNull()?.takeIf { it >= 2 }
    }

    private fun validateDex(file: File) {
        if (file.length() < DEX_HEADER_SIZE) fail(R8ErrorCode.COMPILATION_FAILED, "R8 DEX header is truncated")
        val bytes = file.readBytesBounded(DEX_HEADER_SIZE)
        val validMagic = bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte() &&
            bytes.sliceArray(4..6).all { it in '0'.code.toByte()..'9'.code.toByte() } && bytes[7] == 0.toByte()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (!validMagic || header.getInt(32).toLong() != file.length() ||
            header.getInt(36) != DEX_HEADER_SIZE || header.getInt(40) != DEX_ENDIAN_CONSTANT
        ) {
            fail(R8ErrorCode.COMPILATION_FAILED, "R8 DEX header is invalid")
        }
        val expectedChecksum = header.getInt(8).toLong() and 0xffff_ffffL
        val expectedSignature = bytes.copyOfRange(12, 32)
        val adler = Adler32()
        val sha1 = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            var offset = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val checksumStart = maxOf(12L - offset, 0L).toInt().coerceAtMost(read)
                if (checksumStart < read) adler.update(buffer, checksumStart, read - checksumStart)
                val signatureStart = maxOf(32L - offset, 0L).toInt().coerceAtMost(read)
                if (signatureStart < read) sha1.update(buffer, signatureStart, read - signatureStart)
                offset += read
            }
        }
        if (adler.value != expectedChecksum || !sha1.digest().contentEquals(expectedSignature)) {
            fail(R8ErrorCode.COMPILATION_FAILED, "R8 DEX checksum is invalid")
        }
    }

    private fun File.readBytesBounded(count: Int): ByteArray {
        val bytes = ByteArray(count)
        inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                if (read > 0) offset += read
            }
            if (offset != bytes.size) fail(R8ErrorCode.COMPILATION_FAILED, "R8 DEX header is truncated")
        }
        return bytes
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun fail(code: R8ErrorCode, message: String): Nothing = throw R8CompilerFailure(
        code,
        R8FailurePhase.OUTPUT_PACKAGING,
        message,
    )

    private class LimitedOutputStream(
        delegate: OutputStream,
        private val maximumBytes: Long,
    ) : FilterOutputStream(delegate) {
        private var written = 0L
        override fun write(value: Int) { claim(1); out.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            claim(length)
            out.write(bytes, offset, length)
        }
        private fun claim(count: Int) {
            if (count < 0 || written > maximumBytes - count.toLong()) throw OutputLimitExceeded()
            written += count
        }
    }

    private class OutputLimitExceeded : IOException()

    private const val DEX_HEADER_SIZE = 112
    private const val DEX_ENDIAN_CONSTANT = 0x12345678
    private const val DOS_EPOCH_MILLIS = 315_532_800_000L
}
