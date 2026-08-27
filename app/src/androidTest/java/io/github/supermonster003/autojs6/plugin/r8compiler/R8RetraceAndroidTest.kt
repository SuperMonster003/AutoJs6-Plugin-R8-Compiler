package io.github.supermonster003.autojs6.plugin.r8compiler

import junit.framework.TestCase
import org.autojs.plugin.r8compiler.api.R8CompilerCodec
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerProfile
import org.autojs.plugin.r8compiler.api.R8MappingProvenanceId
import org.autojs.plugin.r8compiler.api.R8RequestId
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundleCodec
import org.autojs.plugin.r8compiler.api.R8RetraceInputRole
import org.autojs.plugin.r8compiler.api.R8RetraceMetadata
import org.autojs.plugin.r8compiler.api.R8RetraceRequest
import org.autojs.plugin.r8compiler.api.R8Sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

class R8RetraceAndroidTest : TestCase() {
    fun testEmbeddedR8RetracesRealObfuscatedFrameOnDevice() {
        val capabilities = R8CompilerRuntime.retraceCapabilities()
        val mapping = (
            "sample.Main -> a:\n" +
                "    7:7:void crash():42:42 -> a\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val stack = (
            "java.lang.IllegalStateException: broken\n" +
                "    at a.a(SourceFile:7)\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val metadata = R8CompilerCodec.encodeRetraceMetadata(
            R8RetraceMetadata(
                mappingSha256 = R8Sha256.digest(mapping),
                formatId = capabilities.mappingFormatId,
                formatVersion = capabilities.mappingFormatVersion,
                compilerVersion = capabilities.compilerVersion,
                capabilityFingerprint = R8Sha256.ZERO,
                runtimeLibraryFingerprint = R8Sha256.digest("runtime".toByteArray(StandardCharsets.UTF_8)),
                inputSetFingerprint = R8Sha256.digest("inputs".toByteArray(StandardCharsets.UTF_8)),
                minApi = 24,
                profile = R8CompilerProfile.FULL_RELEASE,
            ),
        )
        val encoded = ByteArrayOutputStream()
        val summary = R8RetraceInputBundleCodec.write(
            encoded,
            mapping,
            metadata,
            stack,
            capabilities,
        )
        val mappingIdentity = summary.identities.single { it.role == R8RetraceInputRole.MAPPING_TEXT }
        val metadataIdentity = summary.identities.single { it.role == R8RetraceInputRole.RETRACE_METADATA }
        val request = R8RetraceRequest(
            requestId = R8RequestId.fromUuid(UUID(0L, 25L)),
            protocolVersion = R8CompilerContract.PROTOCOL_V1_1,
            inputLayout = capabilities.inputLayout,
            inputIdentities = summary.identities,
            inputBundleSizeBytes = summary.sizeBytes,
            inputBundleSha256 = summary.contentSha256,
            mappingProvenanceId = R8MappingProvenanceId.compute(
                mappingIdentity.contentSha256,
                metadataIdentity.contentSha256,
            ),
            expectedCompilerVersion = capabilities.compilerVersion,
            expectedCapabilityFingerprint = capabilities.capabilityFingerprint,
            outputLayout = capabilities.outputLayout,
            maxOutputBytes = capabilities.limits.maxRetracedStackTraceBytes,
            diagnosticByteLimit = capabilities.limits.maxDiagnosticBytes,
            timeoutMillis = capabilities.limits.defaultTimeoutMillis,
        )
        val input = R8RetraceInputBundleCodec.read(
            ByteArrayInputStream(encoded.toByteArray()),
            request,
            capabilities,
        )

        val produced = R8RetraceEngine().retrace(request, capabilities, input) { Unit }
        val retraced = produced.bytes.toString(StandardCharsets.UTF_8)
        assertTrue(retraced, retraced.contains("sample.Main.crash"))
        assertTrue(retraced, retraced.contains("Main.java:42"))
    }
}
