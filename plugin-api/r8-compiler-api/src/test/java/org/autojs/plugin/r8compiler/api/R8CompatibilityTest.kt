package org.autojs.plugin.r8compiler.api

import org.autojs.plugin.protocol.wire.TaggedWireWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class R8CompatibilityTest {
    @Test fun unknownOptionalFieldIsSkipped() {
        val bytes = infoWriter().int32(99, 7, requiredForReader = false).encode()
        assertEquals(R8Fixtures.info(), R8CompilerCodec.decodeInfo(bytes))
    }

    @Test fun unknownRequiredFieldIsRejected() {
        val bytes = infoWriter().int32(99, 7, requiredForReader = true).encode()
        expectContractFailure { R8CompilerCodec.decodeInfo(bytes) }
    }

    @Test fun newerSchemaMinorIsReadableByTheV1Reader() {
        assertEquals(R8Fixtures.info(), R8CompilerCodec.decodeInfo(infoWriter(schemaMinor = 1).encode()))
    }

    @Test fun newerSchemaMajorIsRejected() {
        expectContractFailure { R8CompilerCodec.decodeInfo(infoWriter(schemaMajor = 2).encode()) }
    }

    @Test fun duplicateScalarFieldIsRejected() {
        val bytes = infoWriter().int32(1, 1, requiredForReader = true).encode()
        expectContractFailure { R8CompilerCodec.decodeInfo(bytes) }
    }

    @Test fun unknownEnumCodeIsRejectedWithoutReflection() {
        val bytes = R8CompilerCodec.encodeRequest(R8Fixtures.request()).copyOf()
        replaceInt32Field(bytes, 4, 99)
        expectContractFailure { R8CompilerCodec.decodeRequest(bytes) }
    }

    @Test fun trailingBytesAreRejected() {
        val bytes = R8CompilerCodec.encodeInfo(R8Fixtures.info()) + byteArrayOf(0)
        expectContractFailure { R8CompilerCodec.decodeInfo(bytes) }
    }

    @Test fun nonCanonicalFieldTagOrderIsRejected() {
        val bytes = R8CompilerCodec.encodeInfo(R8Fixtures.info()).copyOf()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(28, 2).putInt(48, 1)
        expectContractFailure { R8CompilerCodec.decodeInfo(bytes) }
    }

    private fun infoWriter(schemaMajor: Int = 1, schemaMinor: Int = 0): TaggedWireWriter {
        val info = R8Fixtures.info()
        return TaggedWireWriter(R8CompilerContract.SCHEMA_COMPILER_INFO, schemaMajor, schemaMinor)
            .int32(1, info.protocolMin.major, true)
            .int32(2, info.protocolMin.minor, true)
            .int32(3, info.protocolMax.major, true)
            .int32(4, info.protocolMax.minor, true)
            .string(5, info.providerId, true)
            .string(6, info.providerVersionName, true)
            .int64(7, info.providerVersionCode, true)
            .int64(8, checkNotNull(info.minHostVersionCode))
            .int64(9, checkNotNull(info.maxHostVersionCode))
    }

    private fun replaceInt32Field(bytes: ByteArray, wantedTag: Int, value: Int) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getInt(24)
        var offset = 28
        repeat(count) {
            val tag = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val length = buffer.getInt(offset + 12)
            if (tag == wantedTag) {
                check(type == 1 && length == 4)
                buffer.putInt(offset + 16, value)
                return
            }
            offset += 16 + length
        }
        error("Missing tag $wantedTag")
    }
}
