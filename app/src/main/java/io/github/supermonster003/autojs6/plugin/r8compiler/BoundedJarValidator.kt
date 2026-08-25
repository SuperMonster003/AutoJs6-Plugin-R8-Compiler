package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import org.autojs.plugin.r8compiler.api.R8InputIdentity
import org.autojs.plugin.r8compiler.api.R8ResourceLimits
import org.autojs.plugin.r8compiler.api.R8Sha256
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal data class ValidatedJar(
    val compressedSizeBytes: Long,
    val archiveEntryCount: Int,
    val classEntryCount: Int,
    val uncompressedSizeBytes: Long,
    val totalClassBytes: Long,
)

/**
 * Copies one admitted bundle entry and validates the JAR before R8 can observe it.
 *
 * Validation is intentionally performed through both the local-entry stream and central-directory
 * view. A zero-comment EOCD must end at the final byte, role-independent names are canonical and
 * unique, class data is bounded and starts with CAFEBABE, and local/central entry order agrees.
 */
internal object BoundedJarValidator {
    fun copyAndValidate(
        input: InputStream,
        destination: File,
        identity: R8InputIdentity,
        limits: R8ResourceLimits,
        requireClassEntries: Boolean,
    ): ValidatedJar {
        val copied = copyExact(input, destination, identity.sizeBytes)
        if (copied.sizeBytes != identity.sizeBytes || copied.sha256 != identity.contentSha256) {
            destination.delete()
            fail(R8ErrorCode.INVALID_BUNDLE, "JAR payload does not match its admitted identity")
        }
        return try {
            validateArchive(destination, limits, requireClassEntries)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun copyExact(input: InputStream, destination: File, expectedBytes: Long): CopiedInput {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        try {
            destination.outputStream().buffered().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = checkedAdd(total, read.toLong(), "compressed JAR size")
                    if (total > expectedBytes) {
                        fail(R8ErrorCode.INVALID_BUNDLE, "JAR payload exceeds its admitted size")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        if (total != expectedBytes || total == 0L) {
            destination.delete()
            fail(R8ErrorCode.INVALID_BUNDLE, "JAR payload is truncated or empty")
        }
        return CopiedInput(total, R8Sha256.fromBytes(digest.digest()))
    }

    private fun validateArchive(
        file: File,
        limits: R8ResourceLimits,
        requireClassEntries: Boolean,
    ): ValidatedJar {
        val archiveSize = file.length()
        val declaredEntryCount = validateEnvelope(file, limits.maxArchiveEntries)
        val localNames = ArrayList<String>(declaredEntryCount)
        val seenNames = HashSet<String>()
        var entryCount = 0
        var classCount = 0
        var uncompressedBytes = 0L
        var totalClassBytes = 0L
        var totalCompressedBytes = 0L

        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > limits.maxArchiveEntries) {
                        fail(R8ErrorCode.INPUT_TOO_LARGE, "JAR contains too many entries")
                    }
                    val canonicalName = canonicalEntryName(entry.name, entry.isDirectory)
                    if (!seenNames.add(canonicalName)) {
                        fail(R8ErrorCode.INVALID_BUNDLE, "JAR contains a duplicate entry")
                    }
                    localNames += canonicalName
                    val isClass = !entry.isDirectory && canonicalName.endsWith(".class")
                    val content = readEntry(zip, isClass, limits.maxSingleClassBytes) { count ->
                        uncompressedBytes = checkedAdd(uncompressedBytes, count.toLong(), "uncompressed JAR size")
                        if (uncompressedBytes > limits.maxTotalUncompressedInputBytes) {
                            fail(R8ErrorCode.INPUT_TOO_LARGE, "JAR uncompressed data exceeds the provider limit")
                        }
                    }
                    if (entry.isDirectory && content.sizeBytes != 0L) {
                        fail(R8ErrorCode.INVALID_BUNDLE, "JAR directory entry contains data")
                    }
                    val compressedSize = entry.compressedSize
                    if (compressedSize < 0L) {
                        fail(R8ErrorCode.INVALID_BUNDLE, "JAR entry has no measurable compressed size")
                    }
                    enforceCompressionRatio(content.sizeBytes, compressedSize, MAX_ENTRY_COMPRESSION_RATIO)
                    totalCompressedBytes = checkedAdd(totalCompressedBytes, compressedSize, "compressed JAR size")
                    if (isClass) {
                        classCount++
                        totalClassBytes = checkedAdd(totalClassBytes, content.sizeBytes, "class data size")
                        if (totalClassBytes > limits.maxTotalClassBytes) {
                            fail(R8ErrorCode.INPUT_TOO_LARGE, "JAR class data exceeds the provider limit")
                        }
                        if (!content.prefix.contentEquals(CLASS_MAGIC)) {
                            fail(R8ErrorCode.INVALID_BUNDLE, "Class entry has invalid magic")
                        }
                    }
                    zip.closeEntry()
                }
            }
            validateCentralDirectory(file, localNames)
        } catch (error: R8CompilerFailure) {
            throw error
        } catch (error: IOException) {
            throw R8CompilerFailure(
                R8ErrorCode.INVALID_BUNDLE,
                R8FailurePhase.INPUT_VALIDATION,
                "JAR is malformed",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw R8CompilerFailure(
                R8ErrorCode.INVALID_BUNDLE,
                R8FailurePhase.INPUT_VALIDATION,
                "JAR metadata is malformed",
                error,
            )
        }

        if (entryCount == 0 || entryCount != declaredEntryCount) {
            fail(R8ErrorCode.INVALID_BUNDLE, "JAR local and central entry counts disagree")
        }
        if (requireClassEntries && classCount == 0) {
            fail(R8ErrorCode.INVALID_BUNDLE, "Program JAR contains no class files")
        }
        if (file.length() != archiveSize) {
            fail(R8ErrorCode.INVALID_BUNDLE, "JAR changed during validation")
        }
        enforceCompressionRatio(uncompressedBytes, totalCompressedBytes, MAX_AGGREGATE_COMPRESSION_RATIO)
        enforceCompressionRatio(uncompressedBytes, archiveSize, MAX_AGGREGATE_COMPRESSION_RATIO)
        return ValidatedJar(archiveSize, entryCount, classCount, uncompressedBytes, totalClassBytes)
    }

    private fun validateEnvelope(file: File, maximumEntries: Int): Int {
        if (file.length() < EOCD_SIZE) fail(R8ErrorCode.INVALID_BUNDLE, "JAR EOCD is missing")
        RandomAccessFile(file, "r").use { archive ->
            val first = ByteArray(4)
            archive.readFully(first)
            if (leInt(first, 0) != LOCAL_FILE_SIGNATURE) {
                fail(R8ErrorCode.INVALID_BUNDLE, "JAR does not start with a local ZIP entry")
            }
            val eocdOffset = archive.length() - EOCD_SIZE
            archive.seek(eocdOffset)
            val eocd = ByteArray(EOCD_SIZE.toInt())
            archive.readFully(eocd)
            if (leInt(eocd, 0) != EOCD_SIGNATURE || leU16(eocd, 20) != 0) {
                fail(R8ErrorCode.INVALID_BUNDLE, "JAR EOCD is missing, commented, or not final")
            }
            if (leU16(eocd, 4) != 0 || leU16(eocd, 6) != 0) {
                fail(R8ErrorCode.INVALID_BUNDLE, "Multi-disk JARs are forbidden")
            }
            val diskEntries = leU16(eocd, 8)
            val totalEntries = leU16(eocd, 10)
            if (totalEntries == ZIP64_U16 || totalEntries == 0 || totalEntries != diskEntries) {
                fail(R8ErrorCode.INVALID_BUNDLE, "JAR entry count is invalid")
            }
            if (totalEntries > maximumEntries) {
                fail(R8ErrorCode.INPUT_TOO_LARGE, "JAR entry count exceeds the provider limit")
            }
            val centralSize = leU32(eocd, 12)
            val centralOffset = leU32(eocd, 16)
            if (centralSize == ZIP64_U32 || centralOffset == ZIP64_U32 ||
                checkedAdd(centralOffset, centralSize, "central directory") != eocdOffset
            ) {
                fail(R8ErrorCode.INVALID_BUNDLE, "JAR central-directory bounds are inconsistent")
            }
            return totalEntries
        }
    }

    private fun validateCentralDirectory(file: File, localNames: List<String>) {
        ZipFile(file).use { zip ->
            val centralNames = ArrayList<String>(localNames.size)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.comment.isNullOrEmpty()) {
                    fail(R8ErrorCode.INVALID_BUNDLE, "JAR entry comments are forbidden")
                }
                rejectZip64Extra(entry.extra)
                centralNames += canonicalEntryName(entry.name, entry.isDirectory)
            }
            if (centralNames != localNames) {
                fail(R8ErrorCode.INVALID_BUNDLE, "JAR local and central entry order differs")
            }
        }
    }

    private fun rejectZip64Extra(extra: ByteArray?) {
        if (extra == null) return
        var offset = 0
        while (offset < extra.size) {
            if (offset > extra.size - 4) fail(R8ErrorCode.INVALID_BUNDLE, "JAR extra field is truncated")
            val id = leU16(extra, offset)
            val size = leU16(extra, offset + 2)
            offset += 4
            if (offset > extra.size - size) fail(R8ErrorCode.INVALID_BUNDLE, "JAR extra field is truncated")
            if (id == ZIP64_EXTRA_ID) fail(R8ErrorCode.INVALID_BUNDLE, "ZIP64 JAR entries are forbidden")
            offset += size
        }
    }

    private fun readEntry(
        input: InputStream,
        retainClassPrefix: Boolean,
        maxSingleClassBytes: Long,
        onBytes: (Int) -> Unit,
    ): EntryContent {
        val prefix = ByteArray(CLASS_MAGIC.size)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        var prefixBytes = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (retainClassPrefix && prefixBytes < prefix.size) {
                val retained = minOf(read, prefix.size - prefixBytes)
                buffer.copyInto(prefix, prefixBytes, 0, retained)
                prefixBytes += retained
            }
            entryBytes = checkedAdd(entryBytes, read.toLong(), "JAR entry size")
            onBytes(read)
            if (retainClassPrefix && entryBytes > maxSingleClassBytes) {
                fail(R8ErrorCode.INPUT_TOO_LARGE, "A class entry exceeds the provider limit")
            }
        }
        return EntryContent(entryBytes, prefix.copyOf(prefixBytes))
    }

    private fun canonicalEntryName(name: String, isDirectory: Boolean): String {
        val expectedBody = if (isDirectory) name.removeSuffix("/") else name
        val canonicalDirectorySuffix = !isDirectory || (name.endsWith('/') && !name.endsWith("//"))
        val segments = expectedBody.split('/')
        if (
            name.isEmpty() || name.startsWith('/') || name.contains('\u0000') || name.contains('\\') ||
            name.contains(':') || !canonicalDirectorySuffix ||
            Normalizer.normalize(expectedBody, Normalizer.Form.NFC) != expectedBody ||
            segments.any { it.isEmpty() || it == "." || it == ".." }
        ) {
            fail(R8ErrorCode.INVALID_BUNDLE, "JAR entry name is unsafe")
        }
        return segments.joinToString("/")
    }

    private fun enforceCompressionRatio(uncompressed: Long, compressed: Long, maximumRatio: Long) {
        val exceeds = when {
            uncompressed == 0L -> false
            compressed <= 0L -> true
            compressed > Long.MAX_VALUE / maximumRatio -> false
            else -> uncompressed > compressed * maximumRatio
        }
        if (exceeds) fail(R8ErrorCode.INPUT_TOO_LARGE, "JAR compression ratio exceeds the provider limit")
    }

    private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw R8CompilerFailure(
            R8ErrorCode.INPUT_TOO_LARGE,
            R8FailurePhase.INPUT_VALIDATION,
            "$label overflows",
            error,
        )
    }

    private fun leU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        leU16(bytes, offset) or (leU16(bytes, offset + 2) shl 16)

    private fun leU32(bytes: ByteArray, offset: Int): Long = leInt(bytes, offset).toLong() and 0xffff_ffffL

    private fun fail(code: R8ErrorCode, message: String): Nothing = throw R8CompilerFailure(
        code,
        R8FailurePhase.INPUT_VALIDATION,
        message,
    )

    private data class CopiedInput(val sizeBytes: Long, val sha256: R8Sha256)
    private data class EntryContent(val sizeBytes: Long, val prefix: ByteArray)

    private val CLASS_MAGIC = byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())
    private const val LOCAL_FILE_SIGNATURE = 0x04034b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_SIZE = 22L
    private const val ZIP64_U16 = 0xffff
    private const val ZIP64_U32 = 0xffff_ffffL
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val MAX_ENTRY_COMPRESSION_RATIO = 100L
    private const val MAX_AGGREGATE_COMPRESSION_RATIO = 100L
}
