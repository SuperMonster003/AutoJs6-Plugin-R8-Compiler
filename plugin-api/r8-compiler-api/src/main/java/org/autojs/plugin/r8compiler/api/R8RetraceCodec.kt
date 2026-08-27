package org.autojs.plugin.r8compiler.api

import org.autojs.plugin.protocol.wire.TaggedWireDocument
import org.autojs.plugin.protocol.wire.TaggedWireLimits
import org.autojs.plugin.protocol.wire.TaggedWireWriter

object R8RetraceCodec {
    private val limits = TaggedWireLimits(
        R8CompilerContract.MAX_METADATA_BYTES,
        R8CompilerContract.MAX_METADATA_BYTES,
        1_024,
    )

    fun encodeCapabilities(value: R8RetraceCapabilities): ByteArray {
        R8RetraceValidation.validateCapabilities(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_CAPABILITIES)
            .int32(1, value.compilerFamily.wireCode, true)
            .string(2, value.compilerVersion, true)
            .string(3, value.mappingFormatId, true)
            .string(4, value.mappingFormatVersion, true)
            .int32(5, value.inputLayout.wireCode, true)
            .int32(6, value.outputLayout.wireCode, true)
            .document(7, encodeLimits(value.limits), true)
            .bytes(8, value.capabilityFingerprint.toByteArray(), true)
            .encode()
    }

    fun decodeCapabilities(bytes: ByteArray): R8RetraceCapabilities {
        val document = doc(bytes, R8CompilerContract.SCHEMA_RETRACE_CAPABILITIES, (1..8).toSet())
        return R8RetraceCapabilities(
            enumCode(document.requireInt32(1)),
            document.requireString(2),
            document.requireString(3),
            document.requireString(4),
            enumCode(document.requireInt32(5)),
            enumCode(document.requireInt32(6)),
            decodeLimits(document.requireDocument(7)),
            R8Sha256.fromBytes(document.requireBytes(8)),
        ).also(R8RetraceValidation::validateCapabilities)
    }

    fun encodeRequest(value: R8RetraceRequest): ByteArray {
        R8RetraceValidation.validateRequest(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_REQUEST)
            .bytes(1, value.requestId.toByteArray(), true)
            .int32(2, value.protocolVersion.major, true)
            .int32(3, value.protocolVersion.minor, true)
            .int32(4, value.inputLayout.wireCode, true)
            .apply {
                value.inputIdentities.forEach { identity ->
                    document(5, encodeInputIdentity(identity), true)
                }
            }
            .int64(6, value.inputBundleSizeBytes, true)
            .bytes(7, value.inputBundleSha256.toByteArray(), true)
            .bytes(8, value.mappingProvenanceId.toByteArray(), true)
            .string(9, value.expectedCompilerVersion, true)
            .bytes(10, value.expectedCapabilityFingerprint.toByteArray(), true)
            .int32(11, value.outputLayout.wireCode, true)
            .int64(12, value.maxOutputBytes, true)
            .int32(13, value.diagnosticByteLimit, true)
            .int64(14, value.timeoutMillis, true)
            .encode()
    }

    fun decodeRequest(bytes: ByteArray): R8RetraceRequest {
        val document = doc(
            bytes,
            R8CompilerContract.SCHEMA_RETRACE_REQUEST,
            (1..14).toSet(),
            setOf(5),
        )
        return R8RetraceRequest(
            R8RequestId.fromBytes(document.requireBytes(1)),
            R8ProtocolVersion(document.requireInt32(2), document.requireInt32(3)),
            enumCode(document.requireInt32(4)),
            document.documents(5).map(::decodeInputIdentity),
            document.requireInt64(6),
            R8Sha256.fromBytes(document.requireBytes(7)),
            R8Sha256.fromBytes(document.requireBytes(8)),
            document.requireString(9),
            R8Sha256.fromBytes(document.requireBytes(10)),
            enumCode(document.requireInt32(11)),
            document.requireInt64(12),
            document.requireInt32(13),
            document.requireInt64(14),
        ).also(R8RetraceValidation::validateRequest)
    }

    fun encodeStarted(value: R8RetraceStarted): ByteArray {
        validateStarted(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_STARTED)
            .bytes(1, value.requestId.toByteArray(), true)
            .int64(2, value.sequence, true)
            .int32(3, value.protocolVersion.major, true)
            .int32(4, value.protocolVersion.minor, true)
            .string(5, value.compilerVersion, true)
            .bytes(6, value.capabilityFingerprint.toByteArray(), true)
            .bytes(7, value.mappingProvenanceId.toByteArray(), true)
            .int64(8, value.queueElapsedMillis, true)
            .encode()
    }

    fun decodeStarted(bytes: ByteArray): R8RetraceStarted {
        val document = doc(bytes, R8CompilerContract.SCHEMA_RETRACE_STARTED, (1..8).toSet())
        return R8RetraceStarted(
            R8RequestId.fromBytes(document.requireBytes(1)),
            document.requireInt64(2),
            R8ProtocolVersion(document.requireInt32(3), document.requireInt32(4)),
            document.requireString(5),
            R8Sha256.fromBytes(document.requireBytes(6)),
            R8Sha256.fromBytes(document.requireBytes(7)),
            document.requireInt64(8),
        ).also(::validateStarted)
    }

    fun encodeProgress(value: R8RetraceProgress): ByteArray {
        validateProgress(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_PROGRESS)
            .bytes(1, value.requestId.toByteArray(), true)
            .int64(2, value.sequence, true)
            .int32(3, value.stage.wireCode, true)
            .encode()
    }

    fun decodeProgress(bytes: ByteArray): R8RetraceProgress {
        val document = doc(bytes, R8CompilerContract.SCHEMA_RETRACE_PROGRESS, (1..3).toSet())
        return R8RetraceProgress(
            R8RequestId.fromBytes(document.requireBytes(1)),
            document.requireInt64(2),
            enumCode(document.requireInt32(3)),
        ).also(::validateProgress)
    }

    fun encodeResult(value: R8RetraceResult): ByteArray {
        R8RetraceValidation.validateResult(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_RESULT)
            .bytes(1, value.requestId.toByteArray(), true)
            .int32(2, value.protocolVersion.major, true)
            .int32(3, value.protocolVersion.minor, true)
            .string(4, value.compilerVersion, true)
            .bytes(5, value.capabilityFingerprint.toByteArray(), true)
            .bytes(6, value.mappingProvenanceId.toByteArray(), true)
            .bytes(7, value.obfuscatedStackTraceSha256.toByteArray(), true)
            .int32(8, value.outputLayout.wireCode, true)
            .int64(9, value.outputSizeBytes, true)
            .bytes(10, value.outputSha256.toByteArray(), true)
            .int64(11, value.elapsedMillis, true)
            .apply {
                value.diagnostics.forEach { diagnostic ->
                    document(12, encodeDiagnostic(diagnostic), true)
                }
            }
            .encode()
    }

    fun decodeResult(bytes: ByteArray): R8RetraceResult {
        val document = doc(
            bytes,
            R8CompilerContract.SCHEMA_RETRACE_RESULT,
            (1..12).toSet(),
            setOf(12),
        )
        return R8RetraceResult(
            R8RequestId.fromBytes(document.requireBytes(1)),
            R8ProtocolVersion(document.requireInt32(2), document.requireInt32(3)),
            document.requireString(4),
            R8Sha256.fromBytes(document.requireBytes(5)),
            R8Sha256.fromBytes(document.requireBytes(6)),
            R8Sha256.fromBytes(document.requireBytes(7)),
            enumCode(document.requireInt32(8)),
            document.requireInt64(9),
            R8Sha256.fromBytes(document.requireBytes(10)),
            document.requireInt64(11),
            document.documents(12).map(::decodeDiagnostic),
        ).also(R8RetraceValidation::validateResult)
    }

    fun encodeError(value: R8RetraceError): ByteArray {
        R8RetraceValidation.validateError(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_ERROR)
            .bytes(1, value.requestId.toByteArray(), true)
            .int32(2, value.code.wireCode, true)
            .int32(3, value.phase.wireCode, true)
            .string(4, value.message, true)
            .int64(5, value.elapsedMillis, true)
            .apply {
                value.diagnostics.forEach { diagnostic ->
                    document(6, encodeDiagnostic(diagnostic), true)
                }
            }
            .encode()
    }

    fun decodeError(bytes: ByteArray): R8RetraceError {
        val document = doc(
            bytes,
            R8CompilerContract.SCHEMA_RETRACE_ERROR,
            (1..6).toSet(),
            setOf(6),
        )
        return R8RetraceError(
            R8RequestId.fromBytes(document.requireBytes(1)),
            enumCode(document.requireInt32(2)),
            enumCode(document.requireInt32(3)),
            document.requireString(4),
            document.requireInt64(5),
            document.documents(6).map(::decodeDiagnostic),
        ).also(R8RetraceValidation::validateError)
    }

    fun encodeCancellation(value: R8RetraceCancellation): ByteArray {
        validateCancellation(value)
        return writer(R8CompilerContract.SCHEMA_RETRACE_CANCELLATION)
            .bytes(1, value.requestId.toByteArray(), true)
            .int32(2, value.reason.wireCode, true)
            .int32(3, value.phase.wireCode, true)
            .int64(4, value.elapsedMillis, true)
            .encode()
    }

    fun decodeCancellation(bytes: ByteArray): R8RetraceCancellation {
        val document = doc(bytes, R8CompilerContract.SCHEMA_RETRACE_CANCELLATION, (1..4).toSet())
        return R8RetraceCancellation(
            R8RequestId.fromBytes(document.requireBytes(1)),
            enumCode(document.requireInt32(2)),
            enumCode(document.requireInt32(3)),
            document.requireInt64(4),
        ).also(::validateCancellation)
    }

    private fun encodeLimits(value: R8RetraceResourceLimits): ByteArray =
        writer(R8CompilerContract.SCHEMA_RETRACE_RESOURCE_LIMITS)
            .int64(1, value.maxMappingBytes, true)
            .int64(2, value.maxRetraceMetadataBytes, true)
            .int64(3, value.maxObfuscatedStackTraceBytes, true)
            .int64(4, value.maxInputBundleBytes, true)
            .int64(5, value.maxRetracedStackTraceBytes, true)
            .int32(6, value.maxDiagnosticBytes, true)
            .int32(7, value.maxConcurrentSessions, true)
            .int64(8, value.defaultTimeoutMillis, true)
            .int64(9, value.maxTimeoutMillis, true)
            .encode()

    private fun decodeLimits(bytes: ByteArray): R8RetraceResourceLimits {
        val document = doc(bytes, R8CompilerContract.SCHEMA_RETRACE_RESOURCE_LIMITS, (1..9).toSet())
        return R8RetraceResourceLimits(
            document.requireInt64(1),
            document.requireInt64(2),
            document.requireInt64(3),
            document.requireInt64(4),
            document.requireInt64(5),
            document.requireInt32(6),
            document.requireInt32(7),
            document.requireInt64(8),
            document.requireInt64(9),
        )
    }

    private fun encodeInputIdentity(value: R8RetraceInputIdentity): ByteArray =
        writer(R8CompilerContract.SCHEMA_RETRACE_INPUT_IDENTITY)
            .int32(1, value.role.wireCode, true)
            .int32(2, value.ordinal, true)
            .int64(3, value.sizeBytes, true)
            .bytes(4, value.contentSha256.toByteArray(), true)
            .encode()

    private fun decodeInputIdentity(bytes: ByteArray): R8RetraceInputIdentity {
        val document = doc(bytes, R8CompilerContract.SCHEMA_RETRACE_INPUT_IDENTITY, (1..4).toSet())
        return R8RetraceInputIdentity(
            enumCode(document.requireInt32(1)),
            document.requireInt32(2),
            document.requireInt64(3),
            R8Sha256.fromBytes(document.requireBytes(4)),
        )
    }

    private fun encodeDiagnostic(value: R8Diagnostic): ByteArray =
        writer(R8CompilerContract.SCHEMA_DIAGNOSTIC)
            .int32(1, value.severity.wireCode, true)
            .string(2, value.code, true)
            .string(3, value.message, true)
            .encode()

    private fun decodeDiagnostic(bytes: ByteArray): R8Diagnostic {
        val document = doc(bytes, R8CompilerContract.SCHEMA_DIAGNOSTIC, (1..3).toSet())
        return R8Diagnostic(
            enumCode(document.requireInt32(1)),
            document.requireString(2),
            document.requireString(3),
        )
    }

    private fun validateStarted(value: R8RetraceStarted) {
        if (value.sequence < 0L || value.queueElapsedMillis < 0L ||
            value.protocolVersion != R8CompilerContract.PROTOCOL_V1_1 || value.compilerVersion.isBlank()
        ) {
            invalid("Retrace started payload is invalid")
        }
    }

    private fun validateProgress(value: R8RetraceProgress) {
        if (value.sequence < 0L) invalid("Retrace progress payload is invalid")
    }

    private fun validateCancellation(value: R8RetraceCancellation) {
        if (value.elapsedMillis < 0L) invalid("Retrace cancellation payload is invalid")
    }

    private fun writer(schema: Int) = TaggedWireWriter(
        schema,
        R8CompilerContract.SCHEMA_MAJOR,
        R8CompilerContract.SCHEMA_MINOR,
        limits,
    )

    private fun doc(
        bytes: ByteArray,
        schema: Int,
        knownTags: Set<Int>,
        repeatedTags: Set<Int> = emptySet(),
    ): TaggedWireDocument = TaggedWireDocument.decode(bytes, limits)
        .requireSchema(schema, R8CompilerContract.SCHEMA_MAJOR)
        .rejectUnknownRequiredFields(knownTags)
        .validateKnownCardinality(knownTags, repeatedTags)

    private inline fun <reified E> enumCode(code: Int): E where E : Enum<E>, E : R8WireCode =
        enumValues<E>().firstOrNull { value -> value.wireCode == code }
            ?: throw R8ContractException(
                R8ContractViolation.UNKNOWN_ENUM,
                "Unknown retrace enum code $code",
            )
}
