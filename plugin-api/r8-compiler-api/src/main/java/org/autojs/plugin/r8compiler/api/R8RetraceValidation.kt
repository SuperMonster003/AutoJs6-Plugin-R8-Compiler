package org.autojs.plugin.r8compiler.api

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object R8RetraceValidation {
    private val canonicalInputRoles = R8RetraceInputRole.values().toList()

    fun validateCapabilities(value: R8RetraceCapabilities) {
        requireValue(value.compilerFamily == R8CompilerFamily.R8, "Retrace compiler family must be R8")
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Retrace compiler version")
        requireValue(
            value.mappingFormatId == R8CompilerContract.MAPPING_FORMAT_ID,
            "Retrace mapping format ID is unsupported",
        )
        requireText(value.mappingFormatVersion, 64, "Retrace mapping format version")
        requireValue(
            value.inputLayout == R8RetraceInputLayout.MAPPING_METADATA_AND_STACK_BUNDLE_V1,
            "Retrace input layout is unsupported",
        )
        requireValue(
            value.outputLayout == R8RetraceOutputLayout.UTF8_LF_STACK_TRACE_V1,
            "Retrace output layout is unsupported",
        )
        validateLimits(value.limits)
        requireValue(
            value.capabilityFingerprint == R8RetraceCapabilityFingerprint.compute(value),
            "Retrace capability fingerprint mismatch",
        )
    }

    fun validateInputIdentities(values: List<R8RetraceInputIdentity>) {
        val identities = boundedSnapshot(
            values,
            R8RetraceInputRole.values().size,
            "Retrace input identity",
        )
        requireValue(
            identities.map(R8RetraceInputIdentity::role) == canonicalInputRoles,
            "Retrace inputs must contain the canonical three roles",
        )
        identities.forEach { identity ->
            requireValue(identity.ordinal == 0, "Retrace input ordinal must be zero")
            val maximum = when (identity.role) {
                R8RetraceInputRole.MAPPING_TEXT -> R8CompilerContract.MAX_MAPPING_BYTES
                R8RetraceInputRole.RETRACE_METADATA -> R8CompilerContract.MAX_RETRACE_METADATA_BYTES
                R8RetraceInputRole.OBFUSCATED_STACK_TRACE ->
                    R8CompilerContract.MAX_OBFUSCATED_STACK_TRACE_BYTES
            }
            requireValue(identity.sizeBytes in 1..maximum, "Retrace input size is invalid")
        }
    }

    fun validateRequest(value: R8RetraceRequest) {
        requireValue(
            value.protocolVersion == R8CompilerContract.PROTOCOL_V1_1,
            "Retrace protocol 1.1 is required",
            R8ContractViolation.PROTOCOL_INCOMPATIBLE,
        )
        requireValue(
            value.inputLayout == R8RetraceInputLayout.MAPPING_METADATA_AND_STACK_BUNDLE_V1,
            "Retrace input layout is unsupported",
        )
        validateInputIdentities(value.inputIdentities)
        requireValue(
            value.inputBundleSizeBytes in
                R8CompilerContract.MIN_RETRACE_INPUT_BUNDLE_BYTES..R8CompilerContract.MAX_RETRACE_INPUT_BUNDLE_BYTES,
            "Retrace input bundle size is invalid",
        )
        requireValue(
            value.inputBundleSizeBytes == R8RetraceInputBundleCodec.encodedSize(value.inputIdentities),
            "Retrace input bundle size is not canonical",
        )
        val mapping = value.inputIdentities.single { it.role == R8RetraceInputRole.MAPPING_TEXT }
        val metadata = value.inputIdentities.single { it.role == R8RetraceInputRole.RETRACE_METADATA }
        requireValue(
            value.mappingProvenanceId ==
                R8MappingProvenanceId.compute(mapping.contentSha256, metadata.contentSha256),
            "Retrace mapping provenance ID mismatch",
        )
        requireText(
            value.expectedCompilerVersion,
            R8CompilerContract.MAX_VERSION_TEXT_BYTES,
            "Expected retrace compiler version",
        )
        requireValue(
            value.outputLayout == R8RetraceOutputLayout.UTF8_LF_STACK_TRACE_V1,
            "Retrace output layout is unsupported",
        )
        requireValue(
            value.maxOutputBytes in 1..R8CompilerContract.MAX_RETRACED_STACK_TRACE_BYTES,
            "Retrace output limit is invalid",
        )
        requireValue(
            value.diagnosticByteLimit in 1..R8CompilerContract.MAX_DIAGNOSTIC_BYTES,
            "Retrace diagnostic limit is invalid",
        )
        requireValue(
            value.timeoutMillis in 1..R8CompilerContract.MAX_RETRACE_TIMEOUT_MILLIS,
            "Retrace timeout is invalid",
        )
    }

    fun validateRequestAgainst(value: R8RetraceRequest, capabilities: R8RetraceCapabilities) {
        validateRequest(value)
        validateCapabilities(capabilities)
        capability(
            value.expectedCompilerVersion == capabilities.compilerVersion,
            "Retrace compiler version is incompatible",
        )
        capability(
            value.expectedCapabilityFingerprint == capabilities.capabilityFingerprint,
            "Retrace capability fingerprint is incompatible",
        )
        capability(value.inputLayout == capabilities.inputLayout, "Retrace input layout is incompatible")
        capability(value.outputLayout == capabilities.outputLayout, "Retrace output layout is incompatible")
        capability(value.maxOutputBytes <= capabilities.limits.maxRetracedStackTraceBytes, "Retrace output limit exceeds provider")
        capability(value.diagnosticByteLimit <= capabilities.limits.maxDiagnosticBytes, "Retrace diagnostic limit exceeds provider")
        capability(value.timeoutMillis <= capabilities.limits.maxTimeoutMillis, "Retrace timeout exceeds provider")
        capability(value.inputBundleSizeBytes <= capabilities.limits.maxInputBundleBytes, "Retrace input bundle exceeds provider")
        value.inputIdentities.forEach { identity ->
            val maximum = when (identity.role) {
                R8RetraceInputRole.MAPPING_TEXT -> capabilities.limits.maxMappingBytes
                R8RetraceInputRole.RETRACE_METADATA -> capabilities.limits.maxRetraceMetadataBytes
                R8RetraceInputRole.OBFUSCATED_STACK_TRACE ->
                    capabilities.limits.maxObfuscatedStackTraceBytes
            }
            capability(identity.sizeBytes <= maximum, "Retrace input exceeds provider")
        }
    }

    fun validateInputBundleAgainst(
        value: R8RetraceInputBundle,
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
    ) {
        validateRequestAgainst(request, capabilities)
        capability(value.summary.identities == request.inputIdentities, "Retrace bundle identities mismatch")
        capability(value.summary.sizeBytes == request.inputBundleSizeBytes, "Retrace bundle size mismatch")
        capability(value.summary.contentSha256 == request.inputBundleSha256, "Retrace bundle digest mismatch")
        R8ArtifactContentValidation.validateText(value.mappingBytes, requireContent = true)
        R8ArtifactContentValidation.validateText(value.obfuscatedStackTraceBytes, requireContent = true)
        requireNonWhitespace(value.mappingBytes, "Retrace mapping")
        requireNonWhitespace(value.obfuscatedStackTraceBytes, "Obfuscated stack trace")
        val metadata = R8CompilerCodec.decodeRetraceMetadata(value.retraceMetadataBytes)
        val mapping = request.inputIdentities.single { it.role == R8RetraceInputRole.MAPPING_TEXT }
        capability(metadata.mappingSha256 == mapping.contentSha256, "Retrace metadata mapping digest mismatch")
        capability(metadata.compilerVersion == capabilities.compilerVersion, "Retrace metadata compiler version mismatch")
        capability(metadata.formatId == capabilities.mappingFormatId, "Retrace metadata format ID mismatch")
        capability(metadata.formatVersion == capabilities.mappingFormatVersion, "Retrace metadata format version mismatch")
    }

    fun validateStartedAgainst(
        value: R8RetraceStarted,
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
    ) {
        requireValue(value.sequence >= 0L, "Retrace started sequence is invalid")
        requireValue(value.queueElapsedMillis >= 0L, "Retrace queue elapsed time is invalid")
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Retrace compiler version")
        validateRequestAgainst(request, capabilities)
        capability(value.requestId == request.requestId, "Retrace started request ID mismatch")
        capability(value.protocolVersion == request.protocolVersion, "Retrace started protocol mismatch")
        capability(value.compilerVersion == capabilities.compilerVersion, "Retrace started compiler mismatch")
        capability(value.capabilityFingerprint == capabilities.capabilityFingerprint, "Retrace started capability mismatch")
        capability(value.mappingProvenanceId == request.mappingProvenanceId, "Retrace started provenance mismatch")
    }

    fun validateProgressAgainst(value: R8RetraceProgress, request: R8RetraceRequest) {
        validateRequest(request)
        requireValue(value.sequence >= 0L, "Retrace progress sequence is invalid")
        capability(value.requestId == request.requestId, "Retrace progress request ID mismatch")
    }

    fun validateResultAgainst(
        value: R8RetraceResult,
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
    ) {
        validateResult(value)
        validateRequestAgainst(request, capabilities)
        capability(value.requestId == request.requestId, "Retrace result request ID mismatch")
        capability(value.protocolVersion == request.protocolVersion, "Retrace result protocol mismatch")
        capability(value.compilerVersion == capabilities.compilerVersion, "Retrace result compiler mismatch")
        capability(value.capabilityFingerprint == capabilities.capabilityFingerprint, "Retrace result capability mismatch")
        capability(value.mappingProvenanceId == request.mappingProvenanceId, "Retrace result provenance mismatch")
        val stack = request.inputIdentities.single {
            it.role == R8RetraceInputRole.OBFUSCATED_STACK_TRACE
        }
        capability(value.obfuscatedStackTraceSha256 == stack.contentSha256, "Retrace result input stack mismatch")
        capability(value.outputLayout == request.outputLayout, "Retrace result output layout mismatch")
        capability(value.outputSizeBytes <= request.maxOutputBytes, "Retrace result exceeds request")
        capability(value.outputSizeBytes <= capabilities.limits.maxRetracedStackTraceBytes, "Retrace result exceeds provider")
        validateDiagnosticBudget(value.diagnostics, request, capabilities, "Retrace result diagnostics")
    }

    fun validateOutputAgainst(bytes: ByteArray, result: R8RetraceResult) {
        validateResult(result)
        requireValue(bytes.size.toLong() == result.outputSizeBytes, "Retrace output size mismatch")
        requireValue(R8Sha256.digest(bytes) == result.outputSha256, "Retrace output digest mismatch")
        R8ArtifactContentValidation.validateText(bytes, requireContent = true)
        requireNonWhitespace(bytes, "Retraced stack trace")
    }

    fun validateResult(value: R8RetraceResult) {
        requireValue(
            value.protocolVersion == R8CompilerContract.PROTOCOL_V1_1,
            "Retrace result protocol is unsupported",
            R8ContractViolation.PROTOCOL_INCOMPATIBLE,
        )
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Retrace compiler version")
        requireValue(
            value.outputLayout == R8RetraceOutputLayout.UTF8_LF_STACK_TRACE_V1,
            "Retrace result layout is unsupported",
        )
        requireValue(
            value.outputSizeBytes in 1..R8CompilerContract.MAX_RETRACED_STACK_TRACE_BYTES,
            "Retrace result size is invalid",
        )
        requireValue(value.elapsedMillis >= 0L, "Retrace result elapsed time is invalid")
        R8CompilerValidation.validateDiagnostics(value.diagnostics)
    }

    fun validateErrorAgainst(
        value: R8RetraceError,
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
    ) {
        validateError(value)
        validateRequestAgainst(request, capabilities)
        capability(value.requestId == request.requestId, "Retrace error request ID mismatch")
        validateDiagnosticBudget(
            value.diagnostics,
            request,
            capabilities,
            "Retrace error diagnostics",
            utf8Size(value.message).toLong(),
        )
    }

    fun validateError(value: R8RetraceError) {
        requireText(value.message, R8CompilerContract.MAX_DIAGNOSTIC_BYTES, "Retrace error message")
        requireValue(value.elapsedMillis >= 0L, "Retrace error elapsed time is invalid")
        R8CompilerValidation.validateDiagnostics(value.diagnostics)
        requireValue(
            Math.addExact(utf8Size(value.message).toLong(), diagnosticBytes(value.diagnostics)) <=
                R8CompilerContract.MAX_DIAGNOSTIC_BYTES,
            "Retrace error diagnostics exceed limit",
        )
    }

    fun validateCancellationAgainst(value: R8RetraceCancellation, request: R8RetraceRequest) {
        validateRequest(request)
        requireValue(value.elapsedMillis >= 0L, "Retrace cancellation elapsed time is invalid")
        capability(value.requestId == request.requestId, "Retrace cancellation request ID mismatch")
    }

    private fun validateLimits(value: R8RetraceResourceLimits) {
        requireValue(value.maxMappingBytes in 1..R8CompilerContract.MAX_MAPPING_BYTES, "Retrace mapping limit is invalid")
        requireValue(
            value.maxRetraceMetadataBytes in 1..R8CompilerContract.MAX_RETRACE_METADATA_BYTES,
            "Retrace metadata limit is invalid",
        )
        requireValue(
            value.maxObfuscatedStackTraceBytes in 1..R8CompilerContract.MAX_OBFUSCATED_STACK_TRACE_BYTES,
            "Obfuscated stack limit is invalid",
        )
        requireValue(
            value.maxInputBundleBytes in
                R8CompilerContract.MIN_RETRACE_INPUT_BUNDLE_BYTES..R8CompilerContract.MAX_RETRACE_INPUT_BUNDLE_BYTES,
            "Retrace input bundle limit is invalid",
        )
        val minimumBundle = Math.addExact(
            Math.addExact(value.maxMappingBytes, value.maxRetraceMetadataBytes),
            Math.addExact(value.maxObfuscatedStackTraceBytes, 160L),
        )
        requireValue(minimumBundle <= value.maxInputBundleBytes, "Retrace input limits exceed bundle limit")
        requireValue(
            value.maxRetracedStackTraceBytes in 1..R8CompilerContract.MAX_RETRACED_STACK_TRACE_BYTES,
            "Retraced stack limit is invalid",
        )
        requireValue(
            value.maxDiagnosticBytes in 1..R8CompilerContract.MAX_DIAGNOSTIC_BYTES,
            "Retrace diagnostic limit is invalid",
        )
        requireValue(
            value.maxConcurrentSessions == R8CompilerContract.MAX_CONCURRENT_SESSIONS,
            "Retrace concurrency limit is invalid",
        )
        requireValue(
            value.defaultTimeoutMillis in 1..value.maxTimeoutMillis &&
                value.maxTimeoutMillis <= R8CompilerContract.MAX_RETRACE_TIMEOUT_MILLIS,
            "Retrace timeout limits are invalid",
        )
    }

    private fun validateDiagnosticBudget(
        diagnostics: List<R8Diagnostic>,
        request: R8RetraceRequest,
        capabilities: R8RetraceCapabilities,
        label: String,
        additionalBytes: Long = 0L,
    ) {
        val bytes = Math.addExact(additionalBytes, diagnosticBytes(diagnostics))
        capability(bytes <= request.diagnosticByteLimit, "$label exceed request")
        capability(bytes <= capabilities.limits.maxDiagnosticBytes, "$label exceed provider")
    }

    private fun diagnosticBytes(values: List<R8Diagnostic>): Long = values.fold(0L) { total, value ->
        Math.addExact(
            total,
            Math.addExact(utf8Size(value.code).toLong(), utf8Size(value.message).toLong()),
        )
    }

    private fun requireText(value: String, maximumBytes: Int, label: String) {
        requireValue(value.isNotBlank() && utf8Size(value) <= maximumBytes, "$label is invalid")
    }

    private fun utf8Size(value: String): Int = try {
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
            .remaining()
    } catch (error: Exception) {
        invalid("Retrace text is invalid Unicode", error)
    }

    private fun requireNonWhitespace(bytes: ByteArray, label: String) {
        requireValue(
            bytes.toString(StandardCharsets.UTF_8).any { !it.isWhitespace() },
            "$label must contain non-whitespace text",
        )
    }

    private fun requireValue(
        condition: Boolean,
        message: String,
        violation: R8ContractViolation = R8ContractViolation.INVALID_VALUE,
    ) {
        if (!condition) throw R8ContractException(violation, message)
    }

    private fun capability(condition: Boolean, message: String) =
        requireValue(condition, message, R8ContractViolation.CAPABILITY_INCOMPATIBLE)
}
