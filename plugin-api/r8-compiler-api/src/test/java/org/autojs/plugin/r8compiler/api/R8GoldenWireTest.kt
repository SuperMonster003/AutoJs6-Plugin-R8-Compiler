package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class R8GoldenWireTest {
    @Test fun compilerInfoWireHeaderAndDigestAreGolden() {
        val bytes = R8CompilerCodec.encodeInfo(R8Fixtures.info())
        assertHeader(bytes, R8CompilerContract.SCHEMA_COMPILER_INFO, 9)
        assertEquals("1277fba64e3644ca4fea12c909b4c69fc29ce8a5883685132ba3db59d1eaedec", R8Fixtures.sha256Hex(bytes))
    }

    @Test fun capabilitiesWireHeaderAndDigestAreGolden() {
        val bytes = R8CompilerCodec.encodeCapabilities(R8Fixtures.capabilities())
        assertHeader(bytes, R8CompilerContract.SCHEMA_CAPABILITIES, 22)
        assertEquals("561d1228726bf6885a73a8221514f9541380ee66aa5ab763c41a39413be5affb", R8Fixtures.sha256Hex(bytes))
    }

    @Test fun requestWireHeaderAndDigestAreGolden() {
        val bytes = R8CompilerCodec.encodeRequest(R8Fixtures.request())
        assertHeader(bytes, R8CompilerContract.SCHEMA_COMPILE_REQUEST, 30)
        assertEquals("ec7f2b79416ad98ba9d05d887ce26d6e2a4eb21eec7b1ae076940405ee251612", R8Fixtures.sha256Hex(bytes))
    }

    @Test fun resultWireHeaderAndDigestAreGolden() {
        val bytes = R8CompilerCodec.encodeResult(R8Fixtures.artifactBundle().result)
        assertHeader(bytes, R8CompilerContract.SCHEMA_RESULT, 18)
        assertEquals("94162ee177cec386eaeb7b03ac7d66f63f43596642bd3474bcfb67bc6548c0dc", R8Fixtures.sha256Hex(bytes))
    }

    @Test fun inputBundleFramingAndDigestAreGolden() {
        val fixture = R8Fixtures.inputBundle()
        assertArrayEquals("AJ6R8I01".toByteArray(), fixture.bytes.copyOfRange(0, 8))
        val header = ByteBuffer.wrap(fixture.bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, header.getInt(8))
        assertEquals(4, header.getInt(12))
        assertEquals("c8eb2531ce4513a3de1da43a45f52259073c34ba1f257366fd519e9d2548cc18", R8Fixtures.sha256Hex(fixture.bytes))
    }

    @Test fun outputBundleFramingAndDigestAreGolden() {
        val fixture = R8Fixtures.artifactBundle()
        assertArrayEquals("AJ6R8O01".toByteArray(), fixture.bytes.copyOfRange(0, 8))
        val header = ByteBuffer.wrap(fixture.bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, header.getInt(8))
        assertEquals(5, header.getInt(12))
        assertEquals("ee5c23c500784952cee3a2c824b94aa256a1d4a175d0643f238e84b2e1c946d4", R8Fixtures.sha256Hex(fixture.bytes))
    }

    private fun assertHeader(bytes: ByteArray, schema: Int, fields: Int) {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x414A3657, header.int)
        assertEquals(1, header.int)
        assertEquals(bytes.size, header.int)
        assertEquals(schema, header.int)
        assertEquals(1, header.int)
        assertEquals(0, header.int)
        assertEquals(fields, header.int)
    }
}
