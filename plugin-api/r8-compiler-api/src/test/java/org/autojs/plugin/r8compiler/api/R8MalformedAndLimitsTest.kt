package org.autojs.plugin.r8compiler.api

import org.autojs.plugin.protocol.wire.TaggedWireDocument
import org.autojs.plugin.protocol.wire.TaggedWireLimits
import org.autojs.plugin.protocol.wire.TaggedWireProtocol
import org.autojs.plugin.protocol.wire.TaggedWireWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class R8MalformedAndLimitsTest {
    @Test(timeout = 10_000)
    fun taggedWireRejectsMalformedHeadersAndPrimitiveShapes() {
        val encoded = R8CompilerCodec.encodeInfo(R8Fixtures.info())
        expectContractFailure { TaggedWireDocument.decode(encoded.copyOf(27)) }
        expectContractFailure {
            TaggedWireDocument.decode(encoded.copyOf()).also {
                ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putInt(8, encoded.size - 1)
                TaggedWireDocument.decode(encoded)
            }
        }
        val wrongType = encoded.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(32, TaggedWireProtocol.TYPE_INT64)
        }
        expectContractFailure { R8CompilerCodec.decodeInfo(wrongType) }
        val trailing = encoded + byteArrayOf(0)
        expectContractFailure { TaggedWireDocument.decode(trailing) }
    }

    @Test(timeout = 10_000)
    fun taggedWireEnforcesFieldDocumentAndFieldCountLimits() {
        val fieldLimited = TaggedWireLimits(maxDocumentBytes = 64, maxFieldBytes = 4, maxFields = 1)
        expectContractFailure {
            TaggedWireWriter(91, 1, 0, fieldLimited).string(1, "five!")
        }
        expectContractFailure {
            TaggedWireWriter(91, 1, 0, fieldLimited).int32(1, 1).int32(2, 2)
        }
        val documentLimited = TaggedWireLimits(maxDocumentBytes = 44, maxFieldBytes = 64, maxFields = 4)
        expectContractFailure {
            TaggedWireWriter(91, 1, 0, documentLimited).bytes(1, ByteArray(8)).encode()
        }
        assertTrue(fieldLimited.maxFields > 0)
    }

    @Test(timeout = 10_000)
    fun compilerCodecRejectsInvalidEnumAndUnknownRequiredField() {
        val request = R8CompilerCodec.encodeRequest(R8Fixtures.request()).copyOf()
        replaceFieldInt32(request, 4, 99)
        expectContractFailure { R8CompilerCodec.decodeRequest(request) }

        val info = R8Fixtures.info()
        val requiredUnknown = TaggedWireWriter(R8CompilerContract.SCHEMA_COMPILER_INFO, 1, 0)
            .int32(1, info.protocolMin.major, true)
            .int32(2, info.protocolMin.minor, true)
            .int32(3, info.protocolMax.major, true)
            .int32(4, info.protocolMax.minor, true)
            .string(5, info.providerId, true)
            .string(6, info.providerVersionName, true)
            .int64(7, info.providerVersionCode, true)
            .int64(99, 1, true)
            .encode()
        expectContractFailure { R8CompilerCodec.decodeInfo(requiredUnknown) }
    }

    @Test(timeout = 10_000)
    fun inputBundleRejectsTruncationVersionLayoutAndTrailingData() {
        val fixture = R8Fixtures.inputBundle()
        expectContractFailure { R8InputBundleCodec.read(ByteArrayInputStream(fixture.bytes.copyOf(15))) { _, _ -> } }
        val wrongVersion = fixture.bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(8, 2)
        }
        expectContractFailure { R8InputBundleCodec.read(ByteArrayInputStream(wrongVersion)) { _, _ -> } }
        val wrongRole = fixture.bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(16, 99)
        }
        expectContractFailure { R8InputBundleCodec.read(ByteArrayInputStream(wrongRole)) { _, _ -> } }
        expectContractFailure {
            R8InputBundleCodec.read(ByteArrayInputStream(fixture.bytes + byteArrayOf(0))) { _, _ -> }
        }
    }

    @Test(timeout = 10_000)
    fun inputBundleProviderReaderAppliesAdmittedByteBudgetBeforePayload() {
        val fixture = R8Fixtures.inputBundle()
        val admittedMaximum = fixture.summary.sizeBytes - 1
        val caps = R8Fixtures.capabilities(
            limits = R8Fixtures.limits(
                maxProgramBytes = 64,
                maxClasspathJarBytes = 64,
                maxTotalClasspathBytes = 64,
                maxRuleFileBytes = 128,
                maxTotalRuleBytes = 256,
                maxInputBundleBytes = admittedMaximum,
            ),
        )
        val request = R8Fixtures.request(
            capabilities = caps,
            inputBundleSize = fixture.summary.sizeBytes,
            inputBundleSha256 = fixture.summary.contentSha256,
        )
        var callbackReached = false
        expectContractFailure {
            R8InputBundleCodec.read(
                ByteArrayInputStream(fixture.bytes),
                request,
                caps,
            ) { _, _ -> callbackReached = true }
        }
        assertFalse(callbackReached)
    }

    @Test(timeout = 10_000)
    fun artifactBundleRejectsTruncationVersionLayoutAndTrailingData() {
        val fixture = R8Fixtures.artifactBundle()
        expectContractFailure { R8ArtifactBundleCodec.read(ByteArrayInputStream(fixture.bytes.copyOf(15))) { _, _ -> } }
        val wrongVersion = fixture.bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(8, 2)
        }
        expectContractFailure { R8ArtifactBundleCodec.read(ByteArrayInputStream(wrongVersion)) { _, _ -> } }
        val wrongRole = fixture.bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(16, 99)
        }
        expectContractFailure { R8ArtifactBundleCodec.read(ByteArrayInputStream(wrongRole)) { _, _ -> } }
        expectContractFailure {
            R8ArtifactBundleCodec.read(ByteArrayInputStream(fixture.bytes + byteArrayOf(0))) { _, _ -> }
        }
    }

    @Test(timeout = 10_000)
    fun artifactBundleProviderReaderRejectsTableDriftBeforePayload() {
        val caps = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(caps)
        val fixture = R8Fixtures.artifactBundle(caps, input.request)
        val drifted = fixture.bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putLong(24, 1L)
        }
        var callbackReached = false
        expectContractFailure {
            R8ArtifactBundleCodec.read(
                ByteArrayInputStream(drifted),
                fixture.result,
                input.request,
                caps,
            ) { _, _ -> callbackReached = true }
        }
        assertFalse(callbackReached)
    }

    @Test(timeout = 10_000)
    fun compilerCapabilitiesRejectInvalidResourceLimits() {
        val invalid = R8Fixtures.capabilities(
            limits = R8Fixtures.limits(
                maxProgramBytes = 0,
                maxTimeoutMillis = 0,
                defaultTimeoutMillis = 0,
            ),
        )
        expectContractFailure { R8CompilerValidation.validateCapabilities(invalid) }
    }

    @Test(timeout = 10_000)
    fun compilerCapabilitiesRejectTooManyRuntimeLibraryIdentities() {
        val identity = R8RuntimeLibraryIdentity(1, R8Fixtures.sha("runtime"))
        val values = List(R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES + 1) { identity }
        expectContractFailure {
            R8CompilerValidation.validateCapabilities(R8Fixtures.capabilities(runtime = values))
        }
    }

    @Test(timeout = 10_000)
    fun compilerCapabilitiesAndRequestsStayWithinApi24Through36() {
        listOf(1 to 36, 24 to 37).forEach { (minimum, maximum) ->
            expectContractFailure {
                R8CompilerValidation.validateCapabilities(
                    R8Fixtures.capabilities(minApi = minimum, maxApi = maximum),
                )
            }
        }
        val capabilities = R8Fixtures.capabilities()
        listOf(23, 37).forEach { minimum ->
            expectContractFailure {
                R8CompilerValidation.validateRequest(R8Fixtures.request(capabilities, minApi = minimum))
            }
        }
    }

    @Test(timeout = 10_000)
    fun rulePolicyRejectsMalformedTextAndSyntax() {
        listOf(
            byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte()),
            byteArrayOf('x'.code.toByte(), 0, '\n'.code.toByte()),
            "-keep class X {}".toByteArray(),
            ("-keep class X " + "\\\n").toByteArray(),
            "-keep class X {\n".toByteArray(),
            "-keep class X {} ;-dontwarn x\n".toByteArray(),
        ).forEach { bytes -> expectContractFailure { R8RulePolicy.validateFile(bytes, true) } }
    }

    @Test(timeout = 10_000)
    fun rulePolicyEnforcesFileLineAndAggregateLimits() {
        val lineLimited = R8RuleAdmissionLimits(
            maxKeepRuleFiles = 16,
            maxConsumerRuleFiles = 32,
            maxRuleFileBytes = R8Fixtures.keepBytes.size.toLong(),
            maxTotalRuleBytes = R8Fixtures.keepBytes.size.toLong() * 2,
            maxRuleLinesPerFile = 4_096,
            maxTotalRuleLines = 16_384,
            maxRuleLineBytes = 8,
        )
        expectContractFailure { R8RulePolicy.validateFile(R8Fixtures.keepBytes, true, lineLimited) }

        val countLimited = R8RuleAdmissionLimits(
            maxKeepRuleFiles = 1,
            maxConsumerRuleFiles = 32,
            maxRuleFileBytes = 256L * 1024,
            maxTotalRuleBytes = 2L * 1024 * 1024,
            maxRuleLinesPerFile = 4_096,
            maxTotalRuleLines = 16_384,
            maxRuleLineBytes = 16 * 1024,
        )
        expectContractFailure {
            R8RulePolicy.validateAggregate(
                listOf(
                    R8InputRole.KEEP_RULES to R8Fixtures.keepBytes,
                    R8InputRole.KEEP_RULES to R8Fixtures.keepBytes,
                ),
                countLimited,
            )
        }
    }

    private fun replaceFieldInt32(bytes: ByteArray, wantedTag: Int, value: Int) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getInt(24)
        var offset = 28
        repeat(count) {
            val tag = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val length = buffer.getInt(offset + 12)
            if (tag == wantedTag) {
                check(type == TaggedWireProtocol.TYPE_INT32 && length == Int.SIZE_BYTES)
                buffer.putInt(offset + 16, value)
                return
            }
            offset += TaggedWireProtocol.FIELD_HEADER_SIZE_BYTES + length
        }
        error("Missing field $wantedTag")
    }
}
