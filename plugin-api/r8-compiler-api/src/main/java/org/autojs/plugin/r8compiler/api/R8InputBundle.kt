package org.autojs.plugin.r8compiler.api

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class R8BundleError { MALFORMED_HEADER, UNSUPPORTED_VERSION, LIMIT_EXCEEDED, INVALID_LAYOUT, TRUNCATED, CONTENT_MISMATCH, TRAILING_DATA }
class R8BundleException(val error: R8BundleError, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
class R8InputSource(val identity: R8InputIdentity, val openInputStream: () -> InputStream)
class R8InputBundleSummary(
    identities: Collection<R8InputIdentity>,
    val sizeBytes: Long,
    val contentSha256: R8Sha256,
) {
    val identities = immutableList(identities, maximumInputIdentityCount, "Input identity")
    override fun equals(other: Any?): Boolean = other is R8InputBundleSummary &&
        identities == other.identities && sizeBytes == other.sizeBytes && contentSha256 == other.contentSha256
    override fun hashCode(): Int = 31 * (31 * identities.hashCode() + sizeBytes.hashCode()) + contentSha256.hashCode()
}

object R8InputBundleCodec {
    private val magic = "AJ6R8I01".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val headerBytes = 16L
    private const val recordBytes = 84L
    private const val maximumSourceCount = maximumInputIdentityCount

    fun encodedSize(values: Collection<R8InputIdentity>): Long {
        val identities = boundedSnapshot(values, maximumSourceCount, "Input identity")
        R8CompilerValidation.validateInputIdentitiesSnapshot(identities)
        return calculateSize(identities)
    }

    @JvmSynthetic
    internal fun write(output: OutputStream, sources: Collection<R8InputSource>): R8InputBundleSummary {
        val admittedSources = boundedSnapshot(sources, maximumSourceCount, "Input source")
        return writeInternal(output, admittedSources, R8CompilerContract.MAX_INPUT_BUNDLE_BYTES)
    }

    /**
     * Capability-aware producer. All identities and the canonical bundle size are admitted before
     * any payload is opened. [output] must be isolated staging and discarded if this method fails.
     */
    fun write(
        output: OutputStream,
        sources: Collection<R8InputSource>,
        capabilities: R8CompilerCapabilities,
    ): R8InputBundleSummary {
        val admittedSources = boundedSnapshot(sources, maximumSourceCount, "Input source")
        val identities = admittedSources.map { it.identity }
        val expected = calculateSize(identities)
        R8CompilerValidation.validateInputIdentitiesAgainstCapabilities(identities, expected, capabilities)
        return writeInternal(output, admittedSources, capabilities.limits.maxInputBundleBytes)
    }

    private fun writeInternal(
        output: OutputStream,
        sources: List<R8InputSource>,
        maximumBundleBytes: Long,
    ): R8InputBundleSummary {
        val identities = sources.map { it.identity }
        R8CompilerValidation.validateInputIdentitiesSnapshot(identities)
        val expected = calculateSize(identities)
        if (expected > maximumBundleBytes) fail(R8BundleError.LIMIT_EXCEEDED, "Input bundle exceeds admitted limit")
        val digest = MessageDigest.getInstance("SHA-256"); val counted = DigestingOutput(output, digest, maximumBundleBytes)
        val data = DataOutputStream(counted); data.write(magic); data.writeInt(version); data.writeInt(identities.size)
        identities.forEach { writeIdentity(data, it) }
        sources.forEachIndexed { index, source ->
            val contentDigest = MessageDigest.getInstance("SHA-256")
            source.openInputStream().use { input -> copyExact(input, data, source.identity.sizeBytes, contentDigest, index) }
            if (!contentDigest.digest().contentEquals(source.identity.contentSha256.toByteArray())) fail(R8BundleError.CONTENT_MISMATCH, "Input $index digest mismatch")
        }
        data.flush(); if (counted.count != expected) fail(R8BundleError.CONTENT_MISMATCH, "Input bundle size mismatch")
        return R8InputBundleSummary(identities, counted.count, R8Sha256.fromBytes(digest.digest()))
    }

    @JvmSynthetic
    internal fun read(input: InputStream, onEntry: (R8InputIdentity, InputStream) -> Unit): R8InputBundleSummary =
        readInternal(
            input,
            R8CompilerContract.MAX_INPUT_BUNDLE_BYTES,
            beforePayload = { _, _ -> },
            onEntry = onEntry,
        )

    /**
     * Provider-safe reader: request, capability, table, and byte budgets are checked before
     * payload bytes. [onEntry] may write only to isolated staging; no compiler execution or
     * publication may begin until this method returns successfully after digest and EOF checks.
     */
    fun read(
        input: InputStream,
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
        onEntry: (R8InputIdentity, InputStream) -> Unit,
    ): R8InputBundleSummary {
        R8CompilerValidation.validateRequestAgainst(request, capabilities)
        val maximum = minOf(request.inputBundleSizeBytes, capabilities.limits.maxInputBundleBytes)
        return readInternal(input, maximum, beforePayload = { identities, expectedSize ->
            if (identities != request.inputIdentities || expectedSize != request.inputBundleSizeBytes) {
                fail(R8BundleError.CONTENT_MISMATCH, "Input table does not match the admitted request")
            }
        }, onEntry = onEntry).also { summary ->
            try {
                R8CompilerValidation.validateInputBundleSummaryAgainst(summary, request)
            } catch (error: R8ContractException) {
                fail(R8BundleError.CONTENT_MISMATCH, error.message ?: "Input bundle does not match request", error)
            }
        }
    }

    private fun readInternal(
        input: InputStream,
        maximumBundleBytes: Long,
        beforePayload: (List<R8InputIdentity>, Long) -> Unit,
        onEntry: (R8InputIdentity, InputStream) -> Unit,
    ): R8InputBundleSummary {
        val digest = MessageDigest.getInstance("SHA-256"); val counted = DigestingInput(input, digest, maximumBundleBytes)
        val data = DataInputStream(counted)
        if (!readExact(data, 8).contentEquals(magic)) fail(R8BundleError.MALFORMED_HEADER, "Input bundle magic is invalid")
        if (readInt(data) != version) fail(R8BundleError.UNSUPPORTED_VERSION, "Input bundle version is unsupported")
        val count = readInt(data); if (count !in 1..(1 + R8CompilerContract.MAX_CLASSPATH_JARS + R8CompilerContract.MAX_KEEP_RULE_FILES + R8CompilerContract.MAX_CONSUMER_RULE_FILES)) fail(R8BundleError.LIMIT_EXCEEDED, "Input count is invalid")
        val identities = List(count) { readIdentity(data) }
        try { R8CompilerValidation.validateInputIdentities(identities) } catch (e: R8ContractException) { fail(R8BundleError.INVALID_LAYOUT, e.message ?: "Input layout invalid", e) }
        val expected = calculateSize(identities); if (expected > maximumBundleBytes) fail(R8BundleError.LIMIT_EXCEEDED, "Input bundle exceeds admitted limit")
        beforePayload(identities, expected)
        identities.forEachIndexed { index, identity ->
            val entryDigest = MessageDigest.getInstance("SHA-256"); val entry = EntryInput(counted, identity.sizeBytes, entryDigest, index)
            onEntry(identity, entry); entry.drain()
            if (!entryDigest.digest().contentEquals(identity.contentSha256.toByteArray())) fail(R8BundleError.CONTENT_MISMATCH, "Input $index digest mismatch")
        }
        if (counted.read() >= 0) fail(R8BundleError.TRAILING_DATA, "Input bundle has trailing data")
        if (counted.count != expected) fail(R8BundleError.TRUNCATED, "Input bundle size mismatch")
        return R8InputBundleSummary(identities, counted.count, R8Sha256.fromBytes(digest.digest()))
    }

    private fun writeIdentity(out: DataOutputStream, v: R8InputIdentity) {
        out.writeInt(v.role.wireCode); out.writeInt(v.ordinal); out.writeInt(v.ownerClasspathOrdinal); out.writeLong(v.sizeBytes)
        out.write(v.contentSha256.toByteArray()); out.write(v.ownerClasspathSha256.toByteArray())
    }
    private fun readIdentity(input: DataInputStream): R8InputIdentity {
        val roleCode = readInt(input); val role = R8InputRole.values().firstOrNull { it.wireCode == roleCode } ?: fail(R8BundleError.INVALID_LAYOUT, "Unknown input role")
        return R8InputIdentity(role, readInt(input), readInt(input), readLong(input), R8Sha256.fromBytes(readExact(input, 32)), R8Sha256.fromBytes(readExact(input, 32)))
    }
    private fun calculateSize(v: List<R8InputIdentity>): Long = try { v.fold(Math.addExact(headerBytes, Math.multiplyExact(recordBytes, v.size.toLong()))) { n, i -> Math.addExact(n, i.sizeBytes) } } catch (e: ArithmeticException) { fail(R8BundleError.LIMIT_EXCEEDED, "Input size overflows", e) }
}

class R8ArtifactSource(val identity: R8ArtifactIdentity, val openInputStream: () -> InputStream)
class R8ArtifactBundleSummary(
    identities: Collection<R8ArtifactIdentity>,
    val sizeBytes: Long,
    val contentSha256: R8Sha256,
) {
    val identities = immutableList(identities, artifactCount, "Artifact identity")
    override fun equals(other: Any?): Boolean = other is R8ArtifactBundleSummary &&
        identities == other.identities && sizeBytes == other.sizeBytes && contentSha256 == other.contentSha256
    override fun hashCode(): Int = 31 * (31 * identities.hashCode() + sizeBytes.hashCode()) + contentSha256.hashCode()
}

object R8ArtifactBundleCodec {
    private val magic = "AJ6R8O01".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val headerBytes = 16L
    private const val recordBytes = 48L
    private const val artifactCount = 5

    fun encodedSize(values: Collection<R8ArtifactIdentity>): Long {
        val identities = boundedSnapshot(values, artifactCount, "Artifact identity")
        R8CompilerValidation.validateArtifactIdentitiesSnapshot(identities)
        return calculateSize(identities)
    }
    @JvmSynthetic
    internal fun write(output: OutputStream, sources: Collection<R8ArtifactSource>): R8ArtifactBundleSummary {
        val admittedSources = boundedSnapshot(sources, artifactCount, "Artifact source")
        return writeInternal(output, admittedSources, R8CompilerContract.MAX_OUTPUT_BUNDLE_BYTES)
    }

    /**
     * Request- and capability-aware producer. The exact artifact set and all role/bundle budgets
     * are admitted before any payload is opened. [output] must be isolated staging and discarded
     * if this method fails.
     */
    fun write(
        output: OutputStream,
        sources: Collection<R8ArtifactSource>,
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
    ): R8ArtifactBundleSummary {
        val admittedSources = boundedSnapshot(sources, artifactCount, "Artifact source")
        if (admittedSources.size != artifactCount) invalid("Artifact source count must be exactly five")
        R8CompilerValidation.validateRequestAgainst(request, capabilities)
        val identities = admittedSources.map { it.identity }
        R8CompilerValidation.validateArtifactIdentitiesSnapshot(identities)
        identities.zip(request.requestedArtifacts).forEach { (identity, requested) ->
            if (identity.role != requested.role || identity.sizeBytes > requested.maxBytes ||
                identity.sizeBytes > maximumFor(capabilities.limits, identity.role)
            ) fail(R8BundleError.LIMIT_EXCEEDED, "Artifact exceeds its admitted role budget")
        }
        val maximum = minOf(request.maxOutputBundleBytes, capabilities.limits.maxOutputBundleBytes)
        if (calculateSize(identities) > maximum) fail(R8BundleError.LIMIT_EXCEEDED, "Artifact bundle exceeds admitted limit")
        return writeInternal(output, admittedSources, maximum)
    }

    private fun writeInternal(
        output: OutputStream,
        sources: List<R8ArtifactSource>,
        maximumBundleBytes: Long,
    ): R8ArtifactBundleSummary {
        val ids = sources.map { it.identity }; R8CompilerValidation.validateArtifactIdentitiesSnapshot(ids)
        val expected = calculateSize(ids); if (expected > maximumBundleBytes) fail(R8BundleError.LIMIT_EXCEEDED, "Artifact bundle exceeds admitted limit")
        val digest = MessageDigest.getInstance("SHA-256"); val counted = DigestingOutput(output, digest, maximumBundleBytes); val data = DataOutputStream(counted)
        data.write(magic); data.writeInt(version); data.writeInt(ids.size)
        ids.forEach { data.writeInt(it.role.wireCode); data.writeInt(it.ordinal); data.writeLong(it.sizeBytes); data.write(it.contentSha256.toByteArray()) }
        sources.forEachIndexed { index, source -> val d = MessageDigest.getInstance("SHA-256"); source.openInputStream().use { copyExact(it, data, source.identity.sizeBytes, d, index) }; if (!d.digest().contentEquals(source.identity.contentSha256.toByteArray())) fail(R8BundleError.CONTENT_MISMATCH, "Artifact $index digest mismatch") }
        data.flush(); if (counted.count != expected) fail(R8BundleError.CONTENT_MISMATCH, "Artifact bundle size mismatch")
        return R8ArtifactBundleSummary(ids, counted.count, R8Sha256.fromBytes(digest.digest()))
    }
    @JvmSynthetic
    internal fun read(input: InputStream, onEntry: (R8ArtifactIdentity, InputStream) -> Unit): R8ArtifactBundleSummary =
        readInternal(
            input,
            R8CompilerContract.MAX_OUTPUT_BUNDLE_BYTES,
            beforePayload = { _, _ -> },
            onEntry = onEntry,
        )

    /**
     * Consumer-safe reader. The canonical table is bound before payload dispatch; textual report
     * artifacts and retrace metadata are validated automatically before the caller sees them.
     * [onEntry] may write only to isolated staging, and callers must not load, publish, or otherwise
     * consume any staged artifact until this method returns successfully after digest and EOF checks.
     */
    fun read(
        input: InputStream,
        result: R8Result,
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
        onEntry: (R8ArtifactIdentity, InputStream) -> Unit,
    ): R8ArtifactBundleSummary {
        R8CompilerValidation.validateResultAgainst(result, request, capabilities)
        val maximum = minOf(
            result.outputBundleSizeBytes,
            request.maxOutputBundleBytes,
            capabilities.limits.maxOutputBundleBytes,
        )
        var retraceMetadata: R8RetraceMetadata? = null
        return readInternal(input, maximum, beforePayload = { identities, expectedSize ->
            if (identities != result.artifactIdentities || expectedSize != result.outputBundleSizeBytes) {
                fail(R8BundleError.CONTENT_MISMATCH, "Artifact table does not match the admitted result")
            }
            identities.zip(request.requestedArtifacts).forEach { (identity, requested) ->
                if (identity.role != requested.role || identity.sizeBytes > requested.maxBytes ||
                    identity.sizeBytes > maximumFor(capabilities.limits, identity.role)
                ) fail(R8BundleError.LIMIT_EXCEEDED, "Artifact exceeds its admitted role budget")
            }
        }, onEntry = { identity, entry ->
            when (identity.role) {
                R8ArtifactRole.DEX_ZIP -> onEntry(identity, entry)
                R8ArtifactRole.MAPPING_TEXT,
                R8ArtifactRole.SEEDS_TEXT,
                R8ArtifactRole.USAGE_TEXT,
                R8ArtifactRole.RETRACE_METADATA,
                -> {
                    val content = entry.readBytes()
                    when (identity.role) {
                        R8ArtifactRole.MAPPING_TEXT -> R8ArtifactContentValidation.validateText(content, requireContent = true)
                        R8ArtifactRole.SEEDS_TEXT,
                        R8ArtifactRole.USAGE_TEXT,
                        -> R8ArtifactContentValidation.validateText(content, requireContent = false)
                        R8ArtifactRole.RETRACE_METADATA -> retraceMetadata = R8CompilerCodec.decodeRetraceMetadata(content)
                        else -> Unit
                    }
                    onEntry(identity, ByteArrayInputStream(content))
                }
            }
        }).also { summary ->
            try {
                R8CompilerValidation.validateArtifactBundleSummaryAgainst(summary, result, request, capabilities)
                R8CompilerValidation.validateRetraceMetadataAgainst(
                    retraceMetadata ?: invalid("Retrace metadata artifact was not decoded"),
                    result,
                )
            } catch (error: R8ContractException) {
                fail(R8BundleError.CONTENT_MISMATCH, error.message ?: "Artifact bundle does not match result", error)
            }
        }
    }

    private fun readInternal(
        input: InputStream,
        maximumBundleBytes: Long,
        beforePayload: (List<R8ArtifactIdentity>, Long) -> Unit,
        onEntry: (R8ArtifactIdentity, InputStream) -> Unit,
    ): R8ArtifactBundleSummary {
        val digest = MessageDigest.getInstance("SHA-256"); val counted = DigestingInput(input, digest, maximumBundleBytes); val data = DataInputStream(counted)
        if (!readExact(data, 8).contentEquals(magic)) fail(R8BundleError.MALFORMED_HEADER, "Artifact bundle magic is invalid")
        if (readInt(data) != version) fail(R8BundleError.UNSUPPORTED_VERSION, "Artifact bundle version is unsupported")
        val count = readInt(data); if (count != R8ArtifactRole.values().size) fail(R8BundleError.INVALID_LAYOUT, "Artifact count must be five")
        val ids = List(count) { val roleCode = readInt(data); val role = R8ArtifactRole.values().firstOrNull { it.wireCode == roleCode } ?: fail(R8BundleError.INVALID_LAYOUT, "Unknown artifact role"); R8ArtifactIdentity(role, readInt(data), readLong(data), R8Sha256.fromBytes(readExact(data, 32))) }
        try { R8CompilerValidation.validateArtifactIdentities(ids) } catch (e: R8ContractException) { fail(R8BundleError.INVALID_LAYOUT, e.message ?: "Artifact layout invalid", e) }
        val expected = calculateSize(ids); if (expected > maximumBundleBytes) fail(R8BundleError.LIMIT_EXCEEDED, "Artifact bundle exceeds admitted limit")
        beforePayload(ids, expected)
        ids.forEachIndexed { index, id -> val d = MessageDigest.getInstance("SHA-256"); val entry = EntryInput(counted, id.sizeBytes, d, index); onEntry(id, entry); entry.drain(); if (!d.digest().contentEquals(id.contentSha256.toByteArray())) fail(R8BundleError.CONTENT_MISMATCH, "Artifact $index digest mismatch") }
        if (counted.read() >= 0) fail(R8BundleError.TRAILING_DATA, "Artifact bundle has trailing data")
        if (counted.count != expected) fail(R8BundleError.TRUNCATED, "Artifact bundle size mismatch")
        return R8ArtifactBundleSummary(ids, counted.count, R8Sha256.fromBytes(digest.digest()))
    }
    private fun calculateSize(v: List<R8ArtifactIdentity>): Long = try { v.fold(Math.addExact(headerBytes, Math.multiplyExact(recordBytes, v.size.toLong()))) { n, i -> Math.addExact(n, i.sizeBytes) } } catch (e: ArithmeticException) { fail(R8BundleError.LIMIT_EXCEEDED, "Artifact size overflows", e) }
    private fun maximumFor(limits: R8ResourceLimits, role: R8ArtifactRole): Long = when (role) {
        R8ArtifactRole.DEX_ZIP -> limits.maxDexZipBytes
        R8ArtifactRole.MAPPING_TEXT -> limits.maxMappingBytes
        R8ArtifactRole.SEEDS_TEXT -> limits.maxSeedsBytes
        R8ArtifactRole.USAGE_TEXT -> limits.maxUsageBytes
        R8ArtifactRole.RETRACE_METADATA -> limits.maxRetraceMetadataBytes
    }
}

object R8ArtifactContentValidation {
    /** Strict UTF-8, LF-only report text. Empty SEEDS/USAGE are canonical zero-byte artifacts. */
    fun validateText(bytes: ByteArray, requireContent: Boolean) {
        if (bytes.isEmpty()) {
            if (requireContent) invalid("Text artifact must not be empty")
            return
        }
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            invalid("Text artifact must not contain a UTF-8 BOM")
        }
        if (bytes.any { it == 0.toByte() || it == '\r'.code.toByte() }) invalid("Text artifact contains a forbidden byte")
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (error: Exception) {
            invalid("Text artifact is not strict UTF-8", error)
        }
        if (bytes.last() != '\n'.code.toByte()) invalid("Non-empty text artifact must end with LF")
    }
}

private fun copyExact(input: InputStream, output: OutputStream, size: Long, digest: MessageDigest, index: Int) { var remaining = size; val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (remaining > 0) { val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (read <= 0) fail(R8BundleError.TRUNCATED, "Entry $index is truncated"); output.write(buffer, 0, read); digest.update(buffer, 0, read); remaining -= read }; if (input.read() >= 0) fail(R8BundleError.CONTENT_MISMATCH, "Entry $index exceeds declared size") }
private fun readExact(input: DataInputStream, count: Int): ByteArray = ByteArray(count).also { try { input.readFully(it) } catch (e: EOFException) { fail(R8BundleError.TRUNCATED, "Bundle is truncated", e) } }
private fun readInt(input: DataInputStream): Int = try { input.readInt() } catch (e: EOFException) { fail(R8BundleError.TRUNCATED, "Bundle is truncated", e) }
private fun readLong(input: DataInputStream): Long = try { input.readLong() } catch (e: EOFException) { fail(R8BundleError.TRUNCATED, "Bundle is truncated", e) }
private fun fail(error: R8BundleError, message: String, cause: Throwable? = null): Nothing = throw R8BundleException(error, message, cause)

private class DigestingOutput(private val delegate: OutputStream, private val digest: MessageDigest, private val maximum: Long) : OutputStream() { var count = 0L; private set; override fun write(b: Int) { reserve(1); delegate.write(b); digest.update(b.toByte()) }; override fun write(b: ByteArray, off: Int, len: Int) { if (len == 0) return; reserve(len); delegate.write(b, off, len); digest.update(b, off, len) }; override fun flush() = delegate.flush(); private fun reserve(n: Int) { count = try { Math.addExact(count, n.toLong()) } catch (e: ArithmeticException) { fail(R8BundleError.LIMIT_EXCEEDED, "Bundle size overflows", e) }; if (count > maximum) fail(R8BundleError.LIMIT_EXCEEDED, "Bundle exceeds limit") } }
private class DigestingInput(private val delegate: InputStream, private val digest: MessageDigest, private val maximum: Long) : InputStream() { var count = 0L; private set; override fun read(): Int { val v = delegate.read(); if (v >= 0) { reserve(1); digest.update(v.toByte()) }; return v }; override fun read(b: ByteArray, off: Int, len: Int): Int { val n = delegate.read(b, off, len); if (n > 0) { reserve(n); digest.update(b, off, n) }; return n }; private fun reserve(n: Int) { count = try { Math.addExact(count, n.toLong()) } catch (e: ArithmeticException) { fail(R8BundleError.LIMIT_EXCEEDED, "Bundle size overflows", e) }; if (count > maximum) fail(R8BundleError.LIMIT_EXCEEDED, "Bundle exceeds limit") } }
private class EntryInput(private val input: InputStream, size: Long, private val digest: MessageDigest, private val index: Int) : InputStream() {
    private var remaining = size

    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = input.read()
        if (value < 0) fail(R8BundleError.TRUNCATED, "Entry $index is truncated")
        remaining--
        digest.update(value.toByte())
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val read = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (read <= 0) fail(R8BundleError.TRUNCATED, "Entry $index is truncated")
        remaining -= read
        digest.update(bytes, offset, read)
        return read
    }

    override fun close() = Unit

    fun drain() {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) read(buffer)
    }
}

private const val maximumInputIdentityCount = 1 + R8CompilerContract.MAX_CLASSPATH_JARS +
    R8CompilerContract.MAX_KEEP_RULE_FILES + R8CompilerContract.MAX_CONSUMER_RULE_FILES
private const val artifactCount = 5
