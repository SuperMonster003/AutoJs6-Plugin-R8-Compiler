package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.autojs.plugin.protocol.wire.TaggedWireProtocol
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class R8FixedSeedMutationTest {
    @Test(timeout = 10_000)
    fun taggedWireFixedSeedMutationsAreCanonicalOrTyped() {
        val baseline = R8CompilerCodec.encodeInfo(R8Fixtures.info())
        val payloadOffsets = taggedPayloadOffsets(baseline)
        mutate256(0x52_38_11L, payloadOffsets.size) { random, variant ->
            val mutated = baseline.copyOf()
            val offset = payloadOffsets[random.nextInt(payloadOffsets.size)]
            mutated[offset] = (mutated[offset].toInt() xor (1 shl ((variant + random.nextInt(8)) and 7))).toByte()
            typedOrCanonical("TaggedWire", variant) {
                val decoded = R8CompilerCodec.decodeInfo(mutated)
                assertArrayEquals(mutated, R8CompilerCodec.encodeInfo(decoded))
            }
        }
    }

    @Test(timeout = 10_000)
    fun inputBundleFixedSeedMutationsAreCanonicalOrTyped() {
        val fixture = R8Fixtures.inputBundle()
        val payloadOffsets = inputPayloadOffsets(fixture.bytes, fixture.payloads)
        mutate256(0x71_19_2DL, payloadOffsets.size) { random, variant ->
            val mutated = fixture.bytes.copyOf()
            val offset = payloadOffsets[random.nextInt(payloadOffsets.size)]
            mutated[offset] = (mutated[offset].toInt() xor (1 shl ((variant + random.nextInt(8)) and 7))).toByte()
            typedOrCanonical("InputBundle", variant) {
                val summary = R8InputBundleCodec.read(ByteArrayInputStream(mutated)) { _, _ -> }
                assertEquals(fixture.summary, summary)
            }
        }
    }

    @Test(timeout = 10_000)
    fun artifactBundleFixedSeedMutationsAreCanonicalOrTyped() {
        val caps = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(caps)
        val fixture = R8Fixtures.artifactBundle(caps, input.request)
        val payloadOffsets = artifactPayloadOffsets(fixture.bytes, fixture.payloads)
        mutate256(0x3A_77_04L, payloadOffsets.size) { random, variant ->
            val mutated = fixture.bytes.copyOf()
            val offset = payloadOffsets[random.nextInt(payloadOffsets.size)]
            mutated[offset] = (mutated[offset].toInt() xor (1 shl ((variant + random.nextInt(8)) and 7))).toByte()
            typedOrCanonical("ArtifactBundle", variant) {
                val summary = R8ArtifactBundleCodec.read(ByteArrayInputStream(mutated)) { _, _ -> }
                assertEquals(fixture.summary, summary)
            }
        }
    }

    @Test(timeout = 10_000)
    fun rulePolicyFixedSeedMutationsAreCanonicalOrTyped() {
        val baseline = R8Fixtures.keepBytes
        mutate256(0x6D_02_91L, baseline.size) { random, variant ->
            val mutated = baseline.copyOf()
            val offset = random.nextInt(mutated.size)
            mutated[offset] = (mutated[offset].toInt() xor (1 shl ((variant + random.nextInt(8)) and 7))).toByte()
            typedOrCanonical("RulePolicy", variant) {
                val summary = R8RulePolicy.validateFile(mutated, requireKeepDirective = true)
                assertTrue(summary.lineCount > 0)
                assertTrue(summary.directiveCount > 0)
                assertTrue(summary.keepDirectiveCount > 0)
            }
        }
    }

    private fun mutate256(seed: Long, bound: Int, mutation: (Random, Int) -> Unit) {
        require(bound > 0)
        val random = Random(seed)
        repeat(256) { variant -> mutation(random, variant) }
    }

    private fun typedOrCanonical(label: String, variant: Int, block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            // Rejection is valid only through the contract's typed boundary.
        } catch (error: Throwable) {
            throw AssertionError("$label mutation $variant escaped the typed boundary: ${error::class.java.name}", error)
        }
    }

    private fun taggedPayloadOffsets(bytes: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getInt(24)
        val offsets = ArrayList<Int>()
        var cursor = 28
        repeat(count) {
            val length = buffer.getInt(cursor + 12)
            for (index in 0 until length) offsets += cursor + 16 + index
            cursor += TaggedWireProtocol.FIELD_HEADER_SIZE_BYTES + length
        }
        return offsets.toIntArray()
    }

    private fun inputPayloadOffsets(bytes: ByteArray, payloads: List<ByteArray>): IntArray {
        val headerAndRecords = 16 + 84 * payloads.size
        return payloadOffsets(headerAndRecords, payloads)
    }

    private fun artifactPayloadOffsets(bytes: ByteArray, payloads: List<ByteArray>): IntArray {
        val headerAndRecords = 16 + 48 * payloads.size
        return payloadOffsets(headerAndRecords, payloads)
    }

    private fun payloadOffsets(start: Int, payloads: List<ByteArray>): IntArray {
        val offsets = ArrayList<Int>()
        var cursor = start
        payloads.forEach { payload ->
            for (index in payload.indices) offsets += cursor + index
            cursor += payload.size
        }
        return offsets.toIntArray()
    }
}
