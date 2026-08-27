package org.autojs.plugin.r8compiler.api

import java.io.ByteArrayOutputStream
import java.util.UUID

internal object R8RetraceFixtures {
    val stackBytes = (
        "java.lang.IllegalStateException: broken\n" +
            "    at a.a(SourceFile:1)\n"
        ).toByteArray()

    fun capabilities(
        compilerVersion: String = "8.13.17",
        maxMappingBytes: Long = 16L * 1024 * 1024,
        maxOutputBytes: Long = 4L * 1024 * 1024,
    ): R8RetraceCapabilities {
        val limits = R8RetraceResourceLimits(
            maxMappingBytes = maxMappingBytes,
            maxRetraceMetadataBytes = 256L * 1024,
            maxObfuscatedStackTraceBytes = 1024L * 1024,
            maxInputBundleBytes = maxMappingBytes + 256L * 1024 + 1024L * 1024 + 160L,
            maxRetracedStackTraceBytes = maxOutputBytes,
            maxDiagnosticBytes = 64 * 1024,
            maxConcurrentSessions = 1,
            defaultTimeoutMillis = 30_000,
            maxTimeoutMillis = 120_000,
        )
        fun create(fingerprint: R8Sha256) = R8RetraceCapabilities(
            compilerFamily = R8CompilerFamily.R8,
            compilerVersion = compilerVersion,
            mappingFormatId = R8CompilerContract.MAPPING_FORMAT_ID,
            mappingFormatVersion = "1",
            inputLayout = R8RetraceInputLayout.MAPPING_METADATA_AND_STACK_BUNDLE_V1,
            outputLayout = R8RetraceOutputLayout.UTF8_LF_STACK_TRACE_V1,
            limits = limits,
            capabilityFingerprint = fingerprint,
        )
        val provisional = create(R8Sha256.ZERO)
        return create(R8RetraceCapabilityFingerprint.compute(provisional))
    }

    fun metadataBytes(
        mappingBytes: ByteArray = R8Fixtures.mappingBytes,
        capabilities: R8RetraceCapabilities = capabilities(),
    ): ByteArray = R8CompilerCodec.encodeRetraceMetadata(
        R8RetraceMetadata(
            mappingSha256 = R8Sha256.digest(mappingBytes),
            formatId = capabilities.mappingFormatId,
            formatVersion = capabilities.mappingFormatVersion,
            compilerVersion = capabilities.compilerVersion,
            capabilityFingerprint = R8Fixtures.sha("compile-capability"),
            runtimeLibraryFingerprint = R8Fixtures.sha("runtime-libraries"),
            inputSetFingerprint = R8Fixtures.sha("compile-inputs"),
            minApi = 24,
            profile = R8CompilerProfile.FULL_RELEASE,
        ),
    )

    fun bundle(
        mappingBytes: ByteArray = R8Fixtures.mappingBytes,
        stackBytes: ByteArray = this.stackBytes,
        capabilities: R8RetraceCapabilities = capabilities(),
        metadataBytes: ByteArray = metadataBytes(mappingBytes, capabilities),
    ): Fixture {
        val output = ByteArrayOutputStream()
        val summary = R8RetraceInputBundleCodec.write(
            output,
            mappingBytes,
            metadataBytes,
            stackBytes,
            capabilities,
        )
        val mapping = summary.identities.single { it.role == R8RetraceInputRole.MAPPING_TEXT }
        val metadata = summary.identities.single { it.role == R8RetraceInputRole.RETRACE_METADATA }
        val request = R8RetraceRequest(
            requestId = R8RequestId.fromUuid(UUID(0L, 2L)),
            protocolVersion = R8CompilerContract.PROTOCOL_V1_1,
            inputLayout = capabilities.inputLayout,
            inputIdentities = summary.identities,
            inputBundleSizeBytes = summary.sizeBytes,
            inputBundleSha256 = summary.contentSha256,
            mappingProvenanceId = R8MappingProvenanceId.compute(
                mapping.contentSha256,
                metadata.contentSha256,
            ),
            expectedCompilerVersion = capabilities.compilerVersion,
            expectedCapabilityFingerprint = capabilities.capabilityFingerprint,
            outputLayout = capabilities.outputLayout,
            maxOutputBytes = capabilities.limits.maxRetracedStackTraceBytes,
            diagnosticByteLimit = capabilities.limits.maxDiagnosticBytes,
            timeoutMillis = capabilities.limits.defaultTimeoutMillis,
        )
        return Fixture(
            output.toByteArray(),
            mappingBytes,
            metadataBytes,
            stackBytes,
            summary,
            request,
            capabilities,
        )
    }

    fun started(fixture: Fixture = bundle()) = R8RetraceStarted(
        fixture.request.requestId,
        0,
        fixture.request.protocolVersion,
        fixture.capabilities.compilerVersion,
        fixture.capabilities.capabilityFingerprint,
        fixture.request.mappingProvenanceId,
        0,
    )

    fun result(
        fixture: Fixture = bundle(),
        outputBytes: ByteArray = "java.lang.IllegalStateException: broken\n    at sample.Main.main(Main.java:7)\n".toByteArray(),
    ) = R8RetraceResult(
        requestId = fixture.request.requestId,
        protocolVersion = fixture.request.protocolVersion,
        compilerVersion = fixture.capabilities.compilerVersion,
        capabilityFingerprint = fixture.capabilities.capabilityFingerprint,
        mappingProvenanceId = fixture.request.mappingProvenanceId,
        obfuscatedStackTraceSha256 = fixture.request.inputIdentities.single {
            it.role == R8RetraceInputRole.OBFUSCATED_STACK_TRACE
        }.contentSha256,
        outputLayout = fixture.request.outputLayout,
        outputSizeBytes = outputBytes.size.toLong(),
        outputSha256 = R8Sha256.digest(outputBytes),
        elapsedMillis = 1,
    )

    data class Fixture(
        val bytes: ByteArray,
        val mappingBytes: ByteArray,
        val metadataBytes: ByteArray,
        val stackBytes: ByteArray,
        val summary: R8RetraceInputBundleSummary,
        val request: R8RetraceRequest,
        val capabilities: R8RetraceCapabilities,
    )
}
