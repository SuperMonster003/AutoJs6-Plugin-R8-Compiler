package org.autojs.plugin.r8compiler.api

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object R8CompilerContract {
    const val SERVICE_ACTION = "org.autojs.plugin.R8_COMPILER"
    const val PLUGIN_PERMISSION = "org.autojs.permission.PLUGIN"
    const val ENGINE_ID = "r8-compiler"
    const val PROVIDER_ID = "autojs6-r8"
    const val MAPPING_FORMAT_ID = "com.android.tools.r8.mapping"

    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 0
    const val SCHEMA_MAJOR = 1
    const val SCHEMA_MINOR = 0

    const val SCHEMA_COMPILER_INFO = 0x5238_0001
    const val SCHEMA_CAPABILITIES = 0x5238_0002
    const val SCHEMA_RUNTIME_LIBRARY_IDENTITY = 0x5238_0003
    const val SCHEMA_DIAGNOSTIC = 0x5238_0004
    const val SCHEMA_INPUT_IDENTITY = 0x5238_0005
    const val SCHEMA_REQUESTED_ARTIFACT = 0x5238_0006
    const val SCHEMA_ARTIFACT_IDENTITY = 0x5238_0007
    const val SCHEMA_RETRACE_METADATA = 0x5238_0008
    const val SCHEMA_RESOURCE_LIMITS = 0x5238_0009
    const val SCHEMA_COMPILE_REQUEST = 0x5238_0010
    const val SCHEMA_STARTED = 0x5238_0011
    const val SCHEMA_PROGRESS = 0x5238_0012
    const val SCHEMA_RESULT = 0x5238_0013
    const val SCHEMA_ERROR = 0x5238_0014
    const val SCHEMA_CANCELLATION = 0x5238_0015

    const val CANONICALIZATION_POLICY_VERSION = 1
    const val RULE_POLICY_VERSION = 1
    const val MAX_METADATA_BYTES = 256 * 1024
    const val MAX_PROGRAM_BYTES = 128L * 1024 * 1024
    const val MAX_CLASSPATH_JARS = 32
    const val MAX_CLASSPATH_JAR_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_CLASSPATH_BYTES = 128L * 1024 * 1024
    const val MAX_KEEP_RULE_FILES = 16
    const val MAX_CONSUMER_RULE_FILES = 32
    const val MAX_RULE_FILE_BYTES = 256L * 1024
    const val MAX_TOTAL_RULE_BYTES = 2L * 1024 * 1024
    const val MAX_RULE_LINES_PER_FILE = 4_096
    const val MAX_TOTAL_RULE_LINES = 16_384
    const val MAX_RULE_LINE_BYTES = 16 * 1024
    const val MIN_INPUT_BUNDLE_OVERHEAD_WITH_KEEP = 185L
    const val MAX_INPUT_BUNDLE_BYTES = 260L * 1024 * 1024
    const val MAX_ARCHIVE_ENTRIES = 20_000
    const val MAX_TOTAL_ARCHIVE_ENTRIES = 60_000
    const val MAX_UNCOMPRESSED_PROGRAM_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_INPUT_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_CLASS_BYTES = 256L * 1024 * 1024
    const val MAX_SINGLE_CLASS_BYTES = 8L * 1024 * 1024
    const val MIN_OUTPUT_BUNDLE_BYTES = 259L
    const val MAX_OUTPUT_BUNDLE_BYTES = 256L * 1024 * 1024
    const val MAX_DEX_ZIP_BYTES = 192L * 1024 * 1024
    const val MAX_MAPPING_BYTES = 32L * 1024 * 1024
    const val MAX_SEEDS_BYTES = 16L * 1024 * 1024
    const val MAX_USAGE_BYTES = 16L * 1024 * 1024
    const val MAX_RETRACE_METADATA_BYTES = 256L * 1024
    const val MAX_DEX_ENTRIES = 64
    const val MAX_DIAGNOSTIC_BYTES = 64 * 1024
    const val MAX_RUNTIME_LIBRARY_IDENTITIES = 512
    const val MAX_TIMEOUT_MILLIS = 300_000L
    const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    const val MAX_CONCURRENT_SESSIONS = 1
    const val MAX_VERSION_TEXT_BYTES = 128
    const val MAX_PROVIDER_ID_BYTES = 128
    const val MIN_SUPPORTED_API = 24
    const val MAX_SUPPORTED_API = 36

    val PROTOCOL_V1 = R8ProtocolVersion(PROTOCOL_MAJOR, PROTOCOL_MINOR)
}

data class R8ProtocolVersion(val major: Int, val minor: Int) : Comparable<R8ProtocolVersion> {
    override fun compareTo(other: R8ProtocolVersion): Int =
        compareValuesBy(this, other, R8ProtocolVersion::major, R8ProtocolVersion::minor)
}

interface R8WireCode { val wireCode: Int }
enum class R8CompilerFamily(override val wireCode: Int) : R8WireCode { R8(1) }
enum class R8CompilerIntent(override val wireCode: Int) : R8WireCode { R8_EXPLICIT(1) }
enum class R8FallbackPolicy(override val wireCode: Int) : R8WireCode { NONE(1) }
enum class R8CompilerProfile(
    override val wireCode: Int,
    val shrink: Boolean,
    val optimize: Boolean,
    val obfuscate: Boolean,
) : R8WireCode { FULL_RELEASE(1, true, true, true) }
enum class R8InputLayout(override val wireCode: Int) : R8WireCode { PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1(1) }
enum class R8InputRole(override val wireCode: Int) : R8WireCode {
    PROGRAM_JAR(1), CLASSPATH_JAR(2), KEEP_RULES(3), CONSUMER_RULES(4),
}
enum class R8OutputLayout(override val wireCode: Int) : R8WireCode { DEX_AND_REPORTS_BUNDLE_V1(1) }
enum class R8ArtifactRole(override val wireCode: Int) : R8WireCode {
    DEX_ZIP(1), MAPPING_TEXT(2), SEEDS_TEXT(3), USAGE_TEXT(4), RETRACE_METADATA(5),
}
enum class R8ImplicitRulePolicy(override val wireCode: Int) : R8WireCode { NONE(1) }
enum class R8DeterminismClaim(override val wireCode: Int) : R8WireCode { NOT_CLAIMED(1) }
enum class R8RuntimeLibraryModel(override val wireCode: Int) : R8WireCode { DEVICE_RUNTIME_BOOTCLASSPATH_V1(1) }
enum class R8ProgressStage(override val wireCode: Int) : R8WireCode {
    QUEUED(1), VALIDATING(2), COMPILING(3), PACKAGING(4), WRITING(5),
}
enum class R8DiagnosticSeverity(override val wireCode: Int) : R8WireCode { INFO(1), WARNING(2), ERROR(3) }
enum class R8ErrorCode(override val wireCode: Int) : R8WireCode {
    INVALID_REQUEST(1), UNSUPPORTED_PROTOCOL(2), UNSUPPORTED_CAPABILITY(3),
    INPUT_TOO_LARGE(4), INVALID_BUNDLE(5), RULE_POLICY_REJECTED(6), BUSY(7),
    COMPILATION_FAILED(8), OUTPUT_TOO_LARGE(9), INTERNAL(10), TIMEOUT(11),
}
enum class R8FailurePhase(override val wireCode: Int) : R8WireCode {
    NEGOTIATION(1), INPUT_VALIDATION(2), RULE_VALIDATION(3), COMPILATION(4),
    OUTPUT_PACKAGING(5), OUTPUT_WRITE(6), CLEANUP(7),
}
enum class R8CancellationReason(override val wireCode: Int) : R8WireCode { REQUESTED(1), SESSION_CLOSED(2) }

enum class R8ContractViolation {
    INVALID_VALUE, UNKNOWN_ENUM, PROTOCOL_INCOMPATIBLE, CAPABILITY_INCOMPATIBLE,
}

class R8ContractException(
    val violation: R8ContractViolation,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class R8Sha256 private constructor(private val value: ByteArray) {
    fun toByteArray(): ByteArray = value.copyOf()
    fun toHexString(): String = value.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    override fun equals(other: Any?): Boolean = other is R8Sha256 && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = toHexString()

    companion object {
        const val BYTE_COUNT = 32
        val ZERO: R8Sha256 = fromBytes(ByteArray(BYTE_COUNT))
        fun fromBytes(value: ByteArray): R8Sha256 {
            if (value.size != BYTE_COUNT) invalid("SHA-256 must contain exactly $BYTE_COUNT bytes")
            return R8Sha256(value.copyOf())
        }
        fun digest(value: ByteArray): R8Sha256 = fromBytes(MessageDigest.getInstance("SHA-256").digest(value))
    }
}

class R8RequestId private constructor(private val value: ByteArray) {
    fun toByteArray(): ByteArray = value.copyOf()
    fun toUuid(): UUID = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).let { UUID(it.long, it.long) }
    override fun equals(other: Any?): Boolean = other is R8RequestId && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = toUuid().toString()

    companion object {
        const val BYTE_COUNT = 16
        fun fromBytes(value: ByteArray): R8RequestId {
            if (value.size != BYTE_COUNT) invalid("Request ID must contain exactly $BYTE_COUNT bytes")
            return R8RequestId(value.copyOf())
        }
        fun fromUuid(value: UUID): R8RequestId = fromBytes(
            ByteBuffer.allocate(BYTE_COUNT).order(ByteOrder.BIG_ENDIAN)
                .putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
        )
    }
}

object R8RuntimeLibraryFingerprint {
    private val domain = "AutoJs6:R8RuntimeLibraryFingerprint:v1\u0000".toByteArray(StandardCharsets.UTF_8)
    fun compute(values: Collection<R8RuntimeLibraryIdentity>): R8Sha256 {
        val identities = boundedSnapshot(
            values,
            R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES,
            "Runtime library identity",
        )
        return hashStructured(domain) { out ->
            out.writeInt(identities.size)
            identities.forEach { out.writeLong(it.sizeBytes); out.write(it.contentSha256.toByteArray()) }
        }
    }
}

object R8InputSetFingerprint {
    private val domain = "AutoJs6:R8CompilerInputSetFingerprint:v1\u0000".toByteArray(StandardCharsets.UTF_8)
    fun compute(values: Collection<R8InputIdentity>): R8Sha256 {
        val ordered = boundedSnapshot(values, maximumInputIdentityCount, "Input identity")
        R8CompilerValidation.validateInputIdentitiesSnapshot(ordered)
        return hashStructured(domain) { out ->
            out.writeInt(ordered.size)
            ordered.forEach {
                out.writeInt(it.role.wireCode); out.writeInt(it.ordinal); out.writeInt(it.ownerClasspathOrdinal)
                out.writeLong(it.sizeBytes); out.write(it.contentSha256.toByteArray())
                out.write(it.ownerClasspathSha256.toByteArray())
            }
        }
    }
}

@JvmSynthetic
internal fun hashStructured(domain: ByteArray, block: (DataOutputStream) -> Unit): R8Sha256 {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out -> out.write(domain); block(out) }
    return R8Sha256.digest(bytes.toByteArray())
}

@JvmSynthetic
internal fun invalid(message: String, cause: Throwable? = null): Nothing =
    throw R8ContractException(R8ContractViolation.INVALID_VALUE, message, cause)

@JvmSynthetic
internal fun <T> boundedSnapshot(values: Collection<T>, maximumSize: Int, label: String): List<T> {
    if (maximumSize < 0) invalid("$label limit is invalid")
    val snapshot = ArrayList<T>(minOf(maximumSize, 16))
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        val value = iterator.next()
        if (snapshot.size == maximumSize) invalid("$label count exceeds limit")
        snapshot += value
    }
    return snapshot
}

private const val maximumInputIdentityCount = 1 + R8CompilerContract.MAX_CLASSPATH_JARS +
    R8CompilerContract.MAX_KEEP_RULE_FILES + R8CompilerContract.MAX_CONSUMER_RULE_FILES
