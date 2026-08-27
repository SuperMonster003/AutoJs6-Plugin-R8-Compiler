package org.autojs.plugin.r8compiler.api

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class R8RetraceInputBundleSummary(
    identities: Collection<R8RetraceInputIdentity>,
    val sizeBytes: Long,
    val contentSha256: R8Sha256,
) {
    val identities = immutableList(
        identities,
        R8RetraceInputRole.values().size,
        "Retrace input identity",
    )

    override fun equals(other: Any?): Boolean = other is R8RetraceInputBundleSummary &&
        identities == other.identities && sizeBytes == other.sizeBytes &&
        contentSha256 == other.contentSha256

    override fun hashCode(): Int =
        31 * (31 * identities.hashCode() + sizeBytes.hashCode()) + contentSha256.hashCode()
}

class R8RetraceInputBundle(
    mappingBytes: ByteArray,
    retraceMetadataBytes: ByteArray,
    obfuscatedStackTraceBytes: ByteArray,
    val summary: R8RetraceInputBundleSummary,
) {
    private val mapping = mappingBytes.copyOf()
    private val metadata = retraceMetadataBytes.copyOf()
    private val stackTrace = obfuscatedStackTraceBytes.copyOf()

    val mappingBytes: ByteArray get() = mapping.copyOf()
    val retraceMetadataBytes: ByteArray get() = metadata.copyOf()
    val obfuscatedStackTraceBytes: ByteArray get() = stackTrace.copyOf()
}

object R8RetraceInputBundleCodec {
    private val magic = "AJ6R8T01".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val headerBytes = 16L
    private const val recordBytes = 48L
    private const val inputCount = 3

    fun encodedSize(values: Collection<R8RetraceInputIdentity>): Long {
        val identities = boundedSnapshot(values, inputCount, "Retrace input identity")
        R8RetraceValidation.validateInputIdentities(identities)
        return calculateSize(identities)
    }

    fun write(
        output: OutputStream,
        mappingBytes: ByteArray,
        retraceMetadataBytes: ByteArray,
        obfuscatedStackTraceBytes: ByteArray,
        capabilities: R8RetraceCapabilities,
    ): R8RetraceInputBundleSummary {
        R8RetraceValidation.validateCapabilities(capabilities)
        R8ArtifactContentValidation.validateText(mappingBytes, requireContent = true)
        R8ArtifactContentValidation.validateText(obfuscatedStackTraceBytes, requireContent = true)
        if (mappingBytes.toString(Charsets.UTF_8).all(Char::isWhitespace)) {
            fail(R8BundleError.CONTENT_MISMATCH, "Retrace mapping must contain non-whitespace text")
        }
        if (obfuscatedStackTraceBytes.toString(Charsets.UTF_8).all(Char::isWhitespace)) {
            fail(R8BundleError.CONTENT_MISMATCH, "Obfuscated stack trace must contain non-whitespace text")
        }
        val metadata = R8CompilerCodec.decodeRetraceMetadata(retraceMetadataBytes)
        val identities = listOf(
            identity(R8RetraceInputRole.MAPPING_TEXT, mappingBytes),
            identity(R8RetraceInputRole.RETRACE_METADATA, retraceMetadataBytes),
            identity(R8RetraceInputRole.OBFUSCATED_STACK_TRACE, obfuscatedStackTraceBytes),
        )
        if (metadata.mappingSha256 != identities[0].contentSha256) {
            fail(R8BundleError.CONTENT_MISMATCH, "Retrace metadata does not identify the supplied mapping")
        }
        if (metadata.compilerVersion != capabilities.compilerVersion ||
            metadata.formatId != capabilities.mappingFormatId ||
            metadata.formatVersion != capabilities.mappingFormatVersion
        ) {
            fail(R8BundleError.CONTENT_MISMATCH, "Retrace metadata is incompatible with the provider")
        }
        val payloads = listOf(mappingBytes, retraceMetadataBytes, obfuscatedStackTraceBytes)
        val expected = calculateSize(identities)
        if (expected > capabilities.limits.maxInputBundleBytes) {
            fail(R8BundleError.LIMIT_EXCEEDED, "Retrace input bundle exceeds provider")
        }
        identities.forEach { value ->
            val maximum = when (value.role) {
                R8RetraceInputRole.MAPPING_TEXT -> capabilities.limits.maxMappingBytes
                R8RetraceInputRole.RETRACE_METADATA -> capabilities.limits.maxRetraceMetadataBytes
                R8RetraceInputRole.OBFUSCATED_STACK_TRACE ->
                    capabilities.limits.maxObfuscatedStackTraceBytes
            }
            if (value.sizeBytes > maximum) {
                fail(R8BundleError.LIMIT_EXCEEDED, "Retrace input exceeds provider")
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val counted = DigestingRetraceOutput(output, digest, capabilities.limits.maxInputBundleBytes)
        val data = DataOutputStream(counted)
        data.write(magic)
        data.writeInt(version)
        data.writeInt(inputCount)
        identities.forEach { value ->
            data.writeInt(value.role.wireCode)
            data.writeInt(value.ordinal)
            data.writeLong(value.sizeBytes)
            data.write(value.contentSha256.toByteArray())
        }
        payloads.forEach(data::write)
        data.flush()
        if (counted.count != expected) {
            fail(R8BundleError.CONTENT_MISMATCH, "Retrace input bundle size mismatch")
        }
        return R8RetraceInputBundleSummary(
            identities,
            counted.count,
            R8Sha256.fromBytes(digest.digest()),
        )
    }

    fun read(
        input: InputStream,
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
    ): R8RetraceInputBundle {
        R8RetraceValidation.validateRequestAgainst(request, capabilities)
        val maximum = minOf(request.inputBundleSizeBytes, capabilities.limits.maxInputBundleBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = DigestingRetraceInput(input, digest, maximum)
        val data = DataInputStream(counted)
        if (!readExact(data, magic.size).contentEquals(magic)) {
            fail(R8BundleError.MALFORMED_HEADER, "Retrace input bundle magic is invalid")
        }
        if (readInt(data) != version) {
            fail(R8BundleError.UNSUPPORTED_VERSION, "Retrace input bundle version is unsupported")
        }
        if (readInt(data) != inputCount) {
            fail(R8BundleError.INVALID_LAYOUT, "Retrace input count must be three")
        }
        val identities = List(inputCount) {
            val roleCode = readInt(data)
            val role = R8RetraceInputRole.values().firstOrNull { value ->
                value.wireCode == roleCode
            } ?: fail(R8BundleError.INVALID_LAYOUT, "Retrace input role is unknown")
            R8RetraceInputIdentity(
                role,
                readInt(data),
                readLong(data),
                R8Sha256.fromBytes(readExact(data, R8Sha256.BYTE_COUNT)),
            )
        }
        try {
            R8RetraceValidation.validateInputIdentities(identities)
        } catch (error: R8ContractException) {
            fail(R8BundleError.INVALID_LAYOUT, error.message ?: "Retrace input layout is invalid", error)
        }
        val expected = calculateSize(identities)
        if (expected != request.inputBundleSizeBytes || expected > maximum || identities != request.inputIdentities) {
            fail(R8BundleError.CONTENT_MISMATCH, "Retrace input table differs from the admitted request")
        }
        val payloads = identities.mapIndexed { index, identity ->
            val bytes = readExactPayload(data, identity.sizeBytes, index)
            if (R8Sha256.digest(bytes) != identity.contentSha256) {
                fail(R8BundleError.CONTENT_MISMATCH, "Retrace input $index digest mismatch")
            }
            bytes
        }
        if (counted.read() >= 0) {
            fail(R8BundleError.TRAILING_DATA, "Retrace input bundle has trailing data")
        }
        if (counted.count != expected) {
            fail(R8BundleError.TRUNCATED, "Retrace input bundle size mismatch")
        }
        val summary = R8RetraceInputBundleSummary(
            identities,
            counted.count,
            R8Sha256.fromBytes(digest.digest()),
        )
        val bundle = R8RetraceInputBundle(payloads[0], payloads[1], payloads[2], summary)
        R8RetraceValidation.validateInputBundleAgainst(bundle, request, capabilities)
        return bundle
    }

    private fun identity(role: R8RetraceInputRole, bytes: ByteArray) =
        R8RetraceInputIdentity(role, 0, bytes.size.toLong(), R8Sha256.digest(bytes))

    private fun calculateSize(values: List<R8RetraceInputIdentity>): Long = try {
        values.fold(
            Math.addExact(headerBytes, Math.multiplyExact(recordBytes, values.size.toLong())),
        ) { total, value -> Math.addExact(total, value.sizeBytes) }
    } catch (error: ArithmeticException) {
        fail(R8BundleError.LIMIT_EXCEEDED, "Retrace input size overflows", error)
    }

    private fun readExactPayload(input: DataInputStream, size: Long, index: Int): ByteArray {
        if (size > Int.MAX_VALUE) {
            fail(R8BundleError.LIMIT_EXCEEDED, "Retrace input $index cannot be materialized")
        }
        return readExact(input, size.toInt())
    }

    private fun readExact(input: DataInputStream, count: Int): ByteArray = ByteArray(count).also {
        try {
            input.readFully(it)
        } catch (error: EOFException) {
            fail(R8BundleError.TRUNCATED, "Retrace input bundle is truncated", error)
        }
    }

    private fun readInt(input: DataInputStream): Int = try {
        input.readInt()
    } catch (error: EOFException) {
        fail(R8BundleError.TRUNCATED, "Retrace input bundle is truncated", error)
    }

    private fun readLong(input: DataInputStream): Long = try {
        input.readLong()
    } catch (error: EOFException) {
        fail(R8BundleError.TRUNCATED, "Retrace input bundle is truncated", error)
    }

    private fun fail(error: R8BundleError, message: String, cause: Throwable? = null): Nothing =
        throw R8BundleException(error, message, cause)
}

private class DigestingRetraceOutput(
    private val delegate: OutputStream,
    private val digest: MessageDigest,
    private val maximum: Long,
) : OutputStream() {
    var count = 0L
        private set

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
        digest.update(value.toByte())
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        reserve(length)
        delegate.write(bytes, offset, length)
        digest.update(bytes, offset, length)
    }

    override fun flush() = delegate.flush()

    private fun reserve(length: Int) {
        count = try {
            Math.addExact(count, length.toLong())
        } catch (error: ArithmeticException) {
            throw R8BundleException(R8BundleError.LIMIT_EXCEEDED, "Retrace bundle size overflows", error)
        }
        if (count > maximum) {
            throw R8BundleException(R8BundleError.LIMIT_EXCEEDED, "Retrace bundle exceeds limit")
        }
    }
}

private class DigestingRetraceInput(
    private val delegate: InputStream,
    private val digest: MessageDigest,
    private val maximum: Long,
) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            reserve(1)
            digest.update(value.toByte())
        }
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(bytes, offset, length)
        if (read > 0) {
            reserve(read)
            digest.update(bytes, offset, read)
        }
        return read
    }

    private fun reserve(length: Int) {
        count = try {
            Math.addExact(count, length.toLong())
        } catch (error: ArithmeticException) {
            throw R8BundleException(R8BundleError.LIMIT_EXCEEDED, "Retrace bundle size overflows", error)
        }
        if (count > maximum) {
            throw R8BundleException(R8BundleError.LIMIT_EXCEEDED, "Retrace bundle exceeds limit")
        }
    }
}
