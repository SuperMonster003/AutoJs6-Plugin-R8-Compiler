package org.autojs.plugin.r8compiler.api

import java.nio.charset.StandardCharsets

enum class R8RetraceInputLayout(override val wireCode: Int) : R8WireCode {
    MAPPING_METADATA_AND_STACK_BUNDLE_V1(1),
}

enum class R8RetraceOutputLayout(override val wireCode: Int) : R8WireCode {
    UTF8_LF_STACK_TRACE_V1(1),
}

enum class R8RetraceInputRole(override val wireCode: Int) : R8WireCode {
    MAPPING_TEXT(1),
    RETRACE_METADATA(2),
    OBFUSCATED_STACK_TRACE(3),
}

enum class R8RetraceProgressStage(override val wireCode: Int) : R8WireCode {
    VALIDATING(1),
    RETRACING(2),
    WRITING(3),
}

enum class R8RetraceErrorCode(override val wireCode: Int) : R8WireCode {
    INVALID_REQUEST(1),
    UNSUPPORTED_PROTOCOL(2),
    UNSUPPORTED_CAPABILITY(3),
    INPUT_TOO_LARGE(4),
    INVALID_BUNDLE(5),
    MAPPING_MISMATCH(6),
    METADATA_MISMATCH(7),
    INVALID_STACK_TRACE(8),
    BUSY(9),
    RETRACE_FAILED(10),
    OUTPUT_TOO_LARGE(11),
    INTERNAL(12),
    TIMEOUT(13),
}

enum class R8RetraceFailurePhase(override val wireCode: Int) : R8WireCode {
    NEGOTIATION(1),
    INPUT_VALIDATION(2),
    PROVENANCE_VALIDATION(3),
    RETRACING(4),
    OUTPUT_VALIDATION(5),
    OUTPUT_WRITE(6),
    CLEANUP(7),
}

enum class R8RetraceCancellationReason(override val wireCode: Int) : R8WireCode {
    REQUESTED(1),
    SESSION_CLOSED(2),
}

data class R8RetraceResourceLimits(
    val maxMappingBytes: Long,
    val maxRetraceMetadataBytes: Long,
    val maxObfuscatedStackTraceBytes: Long,
    val maxInputBundleBytes: Long,
    val maxRetracedStackTraceBytes: Long,
    val maxDiagnosticBytes: Int,
    val maxConcurrentSessions: Int,
    val defaultTimeoutMillis: Long,
    val maxTimeoutMillis: Long,
)

data class R8RetraceCapabilities(
    val compilerFamily: R8CompilerFamily,
    val compilerVersion: String,
    val mappingFormatId: String,
    val mappingFormatVersion: String,
    val inputLayout: R8RetraceInputLayout,
    val outputLayout: R8RetraceOutputLayout,
    val limits: R8RetraceResourceLimits,
    val capabilityFingerprint: R8Sha256,
)

data class R8RetraceInputIdentity(
    val role: R8RetraceInputRole,
    val ordinal: Int,
    val sizeBytes: Long,
    val contentSha256: R8Sha256,
)

class R8RetraceRequest(
    val requestId: R8RequestId,
    val protocolVersion: R8ProtocolVersion,
    val inputLayout: R8RetraceInputLayout,
    inputIdentities: Collection<R8RetraceInputIdentity>,
    val inputBundleSizeBytes: Long,
    val inputBundleSha256: R8Sha256,
    val mappingProvenanceId: R8Sha256,
    val expectedCompilerVersion: String,
    val expectedCapabilityFingerprint: R8Sha256,
    val outputLayout: R8RetraceOutputLayout,
    val maxOutputBytes: Long,
    val diagnosticByteLimit: Int,
    val timeoutMillis: Long,
) {
    val inputIdentities = immutableList(
        inputIdentities,
        R8RetraceInputRole.values().size,
        "Retrace input identity",
    )
}

data class R8RetraceStarted(
    val requestId: R8RequestId,
    val sequence: Long,
    val protocolVersion: R8ProtocolVersion,
    val compilerVersion: String,
    val capabilityFingerprint: R8Sha256,
    val mappingProvenanceId: R8Sha256,
    val queueElapsedMillis: Long,
)

data class R8RetraceProgress(
    val requestId: R8RequestId,
    val sequence: Long,
    val stage: R8RetraceProgressStage,
)

class R8RetraceResult(
    val requestId: R8RequestId,
    val protocolVersion: R8ProtocolVersion,
    val compilerVersion: String,
    val capabilityFingerprint: R8Sha256,
    val mappingProvenanceId: R8Sha256,
    val obfuscatedStackTraceSha256: R8Sha256,
    val outputLayout: R8RetraceOutputLayout,
    val outputSizeBytes: Long,
    val outputSha256: R8Sha256,
    val elapsedMillis: Long,
    diagnostics: Collection<R8Diagnostic> = emptyList(),
) {
    val diagnostics = immutableList(diagnostics, 512, "Retrace diagnostic")
}

class R8RetraceError(
    val requestId: R8RequestId,
    val code: R8RetraceErrorCode,
    val phase: R8RetraceFailurePhase,
    val message: String,
    val elapsedMillis: Long,
    diagnostics: Collection<R8Diagnostic> = emptyList(),
) {
    val diagnostics = immutableList(diagnostics, 512, "Retrace diagnostic")
}

data class R8RetraceCancellation(
    val requestId: R8RequestId,
    val reason: R8RetraceCancellationReason,
    val phase: R8RetraceFailurePhase,
    val elapsedMillis: Long,
)

/**
 * A retrace session follows the same exactly-one-terminal callback law as a compile session.
 * Every callback payload uses a retrace-specific schema. cancel/close remain idempotent, input and
 * output descriptors transfer exactly once, and no local or remote fallback is permitted.
 */
object R8RetraceSessionLaw

object R8RetraceCapabilityFingerprint {
    private val domain =
        "AutoJs6:R8RetraceCapabilityFingerprint:v1\u0000".toByteArray(StandardCharsets.UTF_8)

    fun compute(value: R8RetraceCapabilities): R8Sha256 = hashStructured(domain) { output ->
        output.writeInt(value.compilerFamily.wireCode)
        writeText(output, value.compilerVersion)
        writeText(output, value.mappingFormatId)
        writeText(output, value.mappingFormatVersion)
        output.writeInt(value.inputLayout.wireCode)
        output.writeInt(value.outputLayout.wireCode)
        value.limits.run {
            output.writeLong(maxMappingBytes)
            output.writeLong(maxRetraceMetadataBytes)
            output.writeLong(maxObfuscatedStackTraceBytes)
            output.writeLong(maxInputBundleBytes)
            output.writeLong(maxRetracedStackTraceBytes)
            output.writeInt(maxDiagnosticBytes)
            output.writeInt(maxConcurrentSessions)
            output.writeLong(defaultTimeoutMillis)
            output.writeLong(maxTimeoutMillis)
        }
    }
}

object R8MappingProvenanceId {
    private val domain =
        "AutoJs6:R8MappingProvenanceId:v1\u0000".toByteArray(StandardCharsets.UTF_8)

    fun compute(mappingSha256: R8Sha256, retraceMetadataSha256: R8Sha256): R8Sha256 =
        hashStructured(domain) { output ->
            output.write(mappingSha256.toByteArray())
            output.write(retraceMetadataSha256.toByteArray())
        }
}

private fun writeText(output: java.io.DataOutputStream, value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    output.writeInt(bytes.size)
    output.write(bytes)
}
