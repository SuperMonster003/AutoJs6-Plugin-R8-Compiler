package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8CompilerCodec
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerFamily
import org.autojs.plugin.r8compiler.api.R8CompilerProfile
import org.autojs.plugin.r8compiler.api.R8MappingProvenanceId
import org.autojs.plugin.r8compiler.api.R8RequestId
import org.autojs.plugin.r8compiler.api.R8RetraceCapabilities
import org.autojs.plugin.r8compiler.api.R8RetraceCapabilityFingerprint
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundle
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundleCodec
import org.autojs.plugin.r8compiler.api.R8RetraceInputLayout
import org.autojs.plugin.r8compiler.api.R8RetraceInputRole
import org.autojs.plugin.r8compiler.api.R8RetraceMetadata
import org.autojs.plugin.r8compiler.api.R8RetraceOutputLayout
import org.autojs.plugin.r8compiler.api.R8RetraceRequest
import org.autojs.plugin.r8compiler.api.R8RetraceResourceLimits
import org.autojs.plugin.r8compiler.api.R8Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class R8RetraceEngineTest {
    @Test
    fun embeddedR8RetracesARealObfuscatedFrame() {
        val fixture = fixture()
        val produced = R8RetraceEngine().retrace(
            fixture.request,
            fixture.capabilities,
            fixture.input,
        ) { Unit }
        val text = produced.bytes.decodeToString()
        assertTrue(text, text.contains("sample.Main.crash"))
        assertTrue(text, text.contains("Main.java:42"))
        assertTrue(text.endsWith('\n'))
        assertEquals(R8Sha256.digest(produced.bytes), produced.sha256)
    }

    @Test
    fun runnerOutputIsCanonicalizedToUtf8Lf() {
        val fixture = fixture()
        val engine = R8RetraceEngine { _, _, _ -> listOf("first\r", "second") }
        val produced = engine.retrace(
            fixture.request,
            fixture.capabilities,
            fixture.input,
        ) { Unit }
        assertEquals("first\nsecond\n", produced.bytes.decodeToString())
    }

    @Test
    fun outputLimitAndRunnerFailureAreTypedAndFailClosed() {
        val fixture = fixture(maxOutputBytes = 8)
        val oversized = R8RetraceEngine { _, _, _ -> listOf("0123456789") }
        val limitFailure = assertThrows(R8RetraceProviderFailure::class.java) {
            oversized.retrace(
                fixture.request,
                fixture.capabilities,
                fixture.input,
            ) { Unit }
        }
        assertEquals(
            org.autojs.plugin.r8compiler.api.R8RetraceErrorCode.OUTPUT_TOO_LARGE,
            limitFailure.code,
        )

        val failed = R8RetraceEngine { _, _, _ -> error("engine failure") }
        val engineFailure = assertThrows(R8RetraceProviderFailure::class.java) {
            failed.retrace(fixture.request, fixture.capabilities, fixture.input) { Unit }
        }
        assertEquals(
            org.autojs.plugin.r8compiler.api.R8RetraceErrorCode.RETRACE_FAILED,
            engineFailure.code,
        )

        val empty = R8RetraceEngine { _, _, _ -> emptyList() }
        val emptyFailure = assertThrows(R8RetraceProviderFailure::class.java) {
            empty.retrace(fixture.request, fixture.capabilities, fixture.input) { Unit }
        }
        assertEquals(
            org.autojs.plugin.r8compiler.api.R8RetraceFailurePhase.OUTPUT_VALIDATION,
            emptyFailure.phase,
        )
    }

    private fun fixture(maxOutputBytes: Long = 4L * 1024 * 1024): Fixture {
        val mapping = (
            "sample.Main -> a:\n" +
                "    7:7:void crash():42:42 -> a\n"
            ).toByteArray()
        val stack = (
            "java.lang.IllegalStateException: broken\n" +
                "    at a.a(SourceFile:7)\n"
            ).toByteArray()
        val capabilities = capabilities(maxOutputBytes)
        val metadata = R8CompilerCodec.encodeRetraceMetadata(
            R8RetraceMetadata(
                mappingSha256 = R8Sha256.digest(mapping),
                formatId = capabilities.mappingFormatId,
                formatVersion = capabilities.mappingFormatVersion,
                compilerVersion = capabilities.compilerVersion,
                capabilityFingerprint = R8Sha256.digest("compile-capability".toByteArray()),
                runtimeLibraryFingerprint = R8Sha256.digest("runtime".toByteArray()),
                inputSetFingerprint = R8Sha256.digest("inputs".toByteArray()),
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
        val mappingIdentity = summary.identities.single {
            it.role == R8RetraceInputRole.MAPPING_TEXT
        }
        val metadataIdentity = summary.identities.single {
            it.role == R8RetraceInputRole.RETRACE_METADATA
        }
        val request = R8RetraceRequest(
            R8RequestId.fromUuid(UUID(0L, 3L)),
            R8CompilerContract.PROTOCOL_V1_1,
            capabilities.inputLayout,
            summary.identities,
            summary.sizeBytes,
            summary.contentSha256,
            R8MappingProvenanceId.compute(
                mappingIdentity.contentSha256,
                metadataIdentity.contentSha256,
            ),
            capabilities.compilerVersion,
            capabilities.capabilityFingerprint,
            capabilities.outputLayout,
            maxOutputBytes,
            capabilities.limits.maxDiagnosticBytes,
            capabilities.limits.defaultTimeoutMillis,
        )
        val input = R8RetraceInputBundleCodec.read(
            ByteArrayInputStream(encoded.toByteArray()),
            request,
            capabilities,
        )
        return Fixture(request, capabilities, input)
    }

    private fun capabilities(maxOutputBytes: Long): R8RetraceCapabilities {
        val limits = R8RetraceResourceLimits(
            maxMappingBytes = 16L * 1024 * 1024,
            maxRetraceMetadataBytes = 256L * 1024,
            maxObfuscatedStackTraceBytes = 1024L * 1024,
            maxInputBundleBytes = 16L * 1024 * 1024 + 256L * 1024 + 1024L * 1024 + 160L,
            maxRetracedStackTraceBytes = maxOutputBytes,
            maxDiagnosticBytes = 64 * 1024,
            maxConcurrentSessions = 1,
            defaultTimeoutMillis = 30_000,
            maxTimeoutMillis = 120_000,
        )
        fun create(fingerprint: R8Sha256) = R8RetraceCapabilities(
            R8CompilerFamily.R8,
            R8CompilerRuntime.compilerVersion,
            R8CompilerContract.MAPPING_FORMAT_ID,
            "1",
            R8RetraceInputLayout.MAPPING_METADATA_AND_STACK_BUNDLE_V1,
            R8RetraceOutputLayout.UTF8_LF_STACK_TRACE_V1,
            limits,
            fingerprint,
        )
        val provisional = create(R8Sha256.ZERO)
        return create(R8RetraceCapabilityFingerprint.compute(provisional))
    }

    private data class Fixture(
        val request: R8RetraceRequest,
        val capabilities: R8RetraceCapabilities,
        val input: R8RetraceInputBundle,
    )
}
