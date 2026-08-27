package org.autojs.plugin.r8compiler.api

import org.autojs.plugin.protocol.wire.TaggedWireProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class R8RetraceContractTest {
    @Test
    fun capabilityRequestAndCallbacksRoundTripCanonically() {
        val fixture = R8RetraceFixtures.bundle()
        val capabilities = R8RetraceCodec.encodeCapabilities(fixture.capabilities)
        assertArrayEquals(
            capabilities,
            R8RetraceCodec.encodeCapabilities(R8RetraceCodec.decodeCapabilities(capabilities)),
        )
        val request = R8RetraceCodec.encodeRequest(fixture.request)
        assertArrayEquals(request, R8RetraceCodec.encodeRequest(R8RetraceCodec.decodeRequest(request)))

        val started = R8RetraceCodec.encodeStarted(R8RetraceFixtures.started(fixture))
        assertArrayEquals(started, R8RetraceCodec.encodeStarted(R8RetraceCodec.decodeStarted(started)))
        val progress = R8RetraceCodec.encodeProgress(
            R8RetraceProgress(fixture.request.requestId, 1, R8RetraceProgressStage.RETRACING),
        )
        assertArrayEquals(progress, R8RetraceCodec.encodeProgress(R8RetraceCodec.decodeProgress(progress)))
        val result = R8RetraceCodec.encodeResult(R8RetraceFixtures.result(fixture))
        assertArrayEquals(result, R8RetraceCodec.encodeResult(R8RetraceCodec.decodeResult(result)))
        val error = R8RetraceCodec.encodeError(
            R8RetraceError(
                fixture.request.requestId,
                R8RetraceErrorCode.RETRACE_FAILED,
                R8RetraceFailurePhase.RETRACING,
                "R8 retrace failed",
                1,
            ),
        )
        assertArrayEquals(error, R8RetraceCodec.encodeError(R8RetraceCodec.decodeError(error)))
        val cancellation = R8RetraceCodec.encodeCancellation(
            R8RetraceCancellation(
                fixture.request.requestId,
                R8RetraceCancellationReason.REQUESTED,
                R8RetraceFailurePhase.CLEANUP,
                1,
            ),
        )
        assertArrayEquals(
            cancellation,
            R8RetraceCodec.encodeCancellation(R8RetraceCodec.decodeCancellation(cancellation)),
        )
    }

    @Test
    fun canonicalBundleRoundTripsAndBindsEveryPayload() {
        val fixture = R8RetraceFixtures.bundle()
        val decoded = R8RetraceInputBundleCodec.read(
            ByteArrayInputStream(fixture.bytes),
            fixture.request,
            fixture.capabilities,
        )
        assertEquals(fixture.summary, decoded.summary)
        assertArrayEquals(fixture.mappingBytes, decoded.mappingBytes)
        assertArrayEquals(fixture.metadataBytes, decoded.retraceMetadataBytes)
        assertArrayEquals(fixture.stackBytes, decoded.obfuscatedStackTraceBytes)
        decoded.mappingBytes.fill(0)
        decoded.retraceMetadataBytes.fill(0)
        decoded.obfuscatedStackTraceBytes.fill(0)
        assertArrayEquals(fixture.mappingBytes, decoded.mappingBytes)
        assertArrayEquals(fixture.metadataBytes, decoded.retraceMetadataBytes)
        assertArrayEquals(fixture.stackBytes, decoded.obfuscatedStackTraceBytes)
    }

    @Test
    fun goldenWireAndBundleDigestsAreFrozen() {
        val fixture = R8RetraceFixtures.bundle()
        assertHeader(
            R8RetraceCodec.encodeCapabilities(fixture.capabilities),
            R8CompilerContract.SCHEMA_RETRACE_CAPABILITIES,
            8,
        )
        assertHeader(
            R8RetraceCodec.encodeRequest(fixture.request),
            R8CompilerContract.SCHEMA_RETRACE_REQUEST,
            16,
        )
        assertEquals(
            "96c34cd4b1dbb0228c1edc09dc8a24260b144f9ef3788eafceef3fcf32a00e60",
            R8Fixtures.sha256Hex(R8RetraceCodec.encodeCapabilities(fixture.capabilities)),
        )
        assertEquals(
            "472df75f6d1c70d9949eb7c6ecaa3875082139648f9b36dbf3cae55b6832813a",
            R8Fixtures.sha256Hex(R8RetraceCodec.encodeRequest(fixture.request)),
        )
        assertEquals(
            "a60652baad0e637a0910e3305d236fd69803caa0c1dd8fbea899ffbe0bdfd1b1",
            R8Fixtures.sha256Hex(fixture.bytes),
        )
    }

    @Test
    fun unknownOptionalFieldIsAcceptedAndUnknownRequiredFieldIsRejected() {
        val baseline = R8RetraceCodec.encodeCapabilities(R8RetraceFixtures.capabilities())
        val optional = appendInt32Field(baseline, 99, 7, required = false)
        val required = appendInt32Field(baseline, 99, 7, required = true)
        assertEquals(
            R8RetraceFixtures.capabilities(),
            R8RetraceCodec.decodeCapabilities(optional),
        )
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceCodec.decodeCapabilities(required)
        }
    }

    @Test
    fun protocolCapabilityAndProvenanceDriftFailClosed() {
        val fixture = R8RetraceFixtures.bundle()
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceValidation.validateRequestAgainst(
                copyRequest(fixture, protocolVersion = R8CompilerContract.PROTOCOL_V1),
                fixture.capabilities,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceValidation.validateRequestAgainst(
                copyRequest(fixture, expectedCapabilityFingerprint = R8Sha256.ZERO),
                fixture.capabilities,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceValidation.validateRequest(
                copyRequest(fixture, mappingProvenanceId = R8Sha256.ZERO),
            )
        }
    }

    @Test
    fun mappingMetadataCompilerAndFormatMustMatch() {
        val capabilities = R8RetraceFixtures.capabilities()
        val mapping = R8Fixtures.mappingBytes
        val wrongCompiler = R8RetraceFixtures.metadataBytes(
            mapping,
            R8RetraceFixtures.capabilities(compilerVersion = "8.13.16"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceFixtures.bundle(
                mappingBytes = mapping,
                capabilities = capabilities,
                metadataBytes = wrongCompiler,
            )
        }
        val wrongMapping = mapping + "# changed\n".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceFixtures.bundle(
                mappingBytes = wrongMapping,
                capabilities = capabilities,
                metadataBytes = R8RetraceFixtures.metadataBytes(mapping, capabilities),
            )
        }
    }

    @Test
    fun bundleTruncationTrailingDataAndPayloadMutationFailClosed() {
        val fixture = R8RetraceFixtures.bundle()
        listOf(
            fixture.bytes.copyOf(fixture.bytes.size - 1),
            fixture.bytes + byteArrayOf(0),
            fixture.bytes.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() },
        ).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) {
                R8RetraceInputBundleCodec.read(
                    ByteArrayInputStream(bytes),
                    fixture.request,
                    fixture.capabilities,
                )
            }
        }
    }

    @Test
    fun textCanonicalizationAndProviderLimitsFailClosed() {
        val capabilities = R8RetraceFixtures.capabilities(maxMappingBytes = 64)
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceFixtures.bundle(
                mappingBytes = "sample.Main -> a:\r\n".toByteArray(),
                capabilities = capabilities,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceFixtures.bundle(
                mappingBytes = ByteArray(65) { 'x'.code.toByte() }.also {
                    it[it.lastIndex] = '\n'.code.toByte()
                },
                capabilities = capabilities,
            )
        }
        assertThrows(R8BundleException::class.java) {
            R8RetraceFixtures.bundle(
                stackBytes = "   \n".toByteArray(),
                capabilities = capabilities,
            )
        }
    }

    @Test
    fun resultBindsInputOutputAndDiagnosticBudgets() {
        val fixture = R8RetraceFixtures.bundle()
        val output = "sample.Main.main(Main.java:7)\n".toByteArray()
        val result = R8RetraceFixtures.result(fixture, output)
        R8RetraceValidation.validateResultAgainst(result, fixture.request, fixture.capabilities)
        R8RetraceValidation.validateOutputAgainst(output, result)
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceValidation.validateOutputAgainst(output + byteArrayOf(0), result)
        }
        val wrongResult = R8RetraceResult(
            result.requestId,
            result.protocolVersion,
            result.compilerVersion,
            result.capabilityFingerprint,
            result.mappingProvenanceId,
            R8Sha256.ZERO,
            result.outputLayout,
            result.outputSizeBytes,
            result.outputSha256,
            result.elapsedMillis,
        )
        assertThrows(IllegalArgumentException::class.java) {
            R8RetraceValidation.validateResultAgainst(
                wrongResult,
                fixture.request,
                fixture.capabilities,
            )
        }
    }

    private fun copyRequest(
        fixture: R8RetraceFixtures.Fixture,
        protocolVersion: R8ProtocolVersion = fixture.request.protocolVersion,
        mappingProvenanceId: R8Sha256 = fixture.request.mappingProvenanceId,
        expectedCapabilityFingerprint: R8Sha256 = fixture.request.expectedCapabilityFingerprint,
    ) = R8RetraceRequest(
        fixture.request.requestId,
        protocolVersion,
        fixture.request.inputLayout,
        fixture.request.inputIdentities,
        fixture.request.inputBundleSizeBytes,
        fixture.request.inputBundleSha256,
        mappingProvenanceId,
        fixture.request.expectedCompilerVersion,
        expectedCapabilityFingerprint,
        fixture.request.outputLayout,
        fixture.request.maxOutputBytes,
        fixture.request.diagnosticByteLimit,
        fixture.request.timeoutMillis,
    )

    private fun appendInt32Field(
        baseline: ByteArray,
        tag: Int,
        value: Int,
        required: Boolean,
    ): ByteArray {
        val result = baseline.copyOf(
            baseline.size + TaggedWireProtocol.FIELD_HEADER_SIZE_BYTES + Int.SIZE_BYTES,
        )
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(8, result.size)
        buffer.putInt(24, buffer.getInt(24) + 1)
        buffer.position(baseline.size)
        buffer.putInt(tag)
        buffer.putInt(TaggedWireProtocol.TYPE_INT32)
        buffer.putInt(if (required) TaggedWireProtocol.FLAG_REQUIRED_FOR_READER else 0)
        buffer.putInt(Int.SIZE_BYTES)
        buffer.putInt(value)
        return result
    }

    private fun assertHeader(bytes: ByteArray, schema: Int, fields: Int) {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(TaggedWireProtocol.MAGIC, header.int)
        assertEquals(TaggedWireProtocol.FORMAT_VERSION, header.int)
        assertEquals(bytes.size, header.int)
        assertEquals(schema, header.int)
        assertEquals(1, header.int)
        assertEquals(0, header.int)
        assertEquals(fields, header.int)
        assertTrue(bytes.isNotEmpty())
    }
}
