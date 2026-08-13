package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException

class R8InputBundleTest {
    @Test fun canonicalBundleRoundTripsAllFourInputRoles() {
        val fixture = R8Fixtures.inputBundle()
        val decoded = mutableListOf<Pair<R8InputIdentity, ByteArray>>()
        val summary = R8InputBundleCodec.read(ByteArrayInputStream(fixture.bytes)) { identity, stream ->
            decoded += identity to stream.readBytes()
        }
        assertEquals(fixture.summary, summary)
        assertEquals(fixture.summary.identities, decoded.map { it.first })
        fixture.payloads.forEachIndexed { index, bytes -> assertArrayEquals(bytes, decoded[index].second) }
    }

    @Test fun providerSafeReaderBindsRequestCapabilitiesAndDigest() {
        val caps = R8Fixtures.capabilities()
        val fixture = R8Fixtures.inputBundle(caps)
        val summary = R8InputBundleCodec.read(
            ByteArrayInputStream(fixture.bytes),
            fixture.request,
            caps,
        ) { _, _ -> }
        assertEquals(fixture.summary, summary)
    }

    @Test fun entryInputHonorsZeroLengthAndBoundsBeforePayloadReads() {
        val fixture = R8Fixtures.inputBundle()
        val decoded = mutableListOf<ByteArray>()
        R8InputBundleCodec.read(ByteArrayInputStream(fixture.bytes)) { _, stream ->
            assertEquals(0, stream.read(ByteArray(1), 0, 0))
            try {
                inputStreamReadMethod.invoke(stream, null, 0, 0)
                throw AssertionError("Expected null buffer rejection")
            } catch (error: InvocationTargetException) {
                assertTrue(error.cause is NullPointerException)
            }
            try {
                stream.read(ByteArray(1), -1, 0)
                throw AssertionError("Expected bounds rejection")
            } catch (_: IndexOutOfBoundsException) { }
            decoded += stream.readBytes()
        }
        fixture.payloads.forEachIndexed { index, payload -> assertArrayEquals(payload, decoded[index]) }
    }

    @Test fun capabilityAwareWriterRejectsBeforeOpeningPayload() {
        val fixture = R8Fixtures.inputBundle()
        val capabilities = R8Fixtures.capabilities(
            limits = R8Fixtures.limits(maxProgramBytes = 4),
        )
        var opened = false
        val sources = fixture.summary.identities.mapIndexed { index, identity ->
            R8InputSource(identity) { opened = true; ByteArrayInputStream(fixture.payloads[index]) }
        }
        expectContractFailure {
            R8InputBundleCodec.write(ByteArrayOutputStream(), sources, capabilities)
        }
        assertFalse(opened)
    }

    @Test fun capabilityAwareWriterSnapshotsCallerCollectionExactlyOnce() {
        val capabilities = R8Fixtures.capabilities()
        val fixture = R8Fixtures.inputBundle(capabilities)
        val sources = fixture.summary.identities.mapIndexed { index, identity ->
            R8InputSource(identity) { ByteArrayInputStream(fixture.payloads[index]) }
        }
        var iterations = 0
        val singleUse = object : AbstractCollection<R8InputSource>() {
            override val size: Int = sources.size
            override fun iterator(): Iterator<R8InputSource> {
                check(++iterations == 1) { "Caller collection was iterated more than once" }
                return sources.iterator()
            }
        }
        R8InputBundleCodec.write(ByteArrayOutputStream(), singleUse, capabilities)
        assertEquals(1, iterations)
    }

    @Test fun capabilityAwareWriterBoundsHostileCollectionBeforeOpeningPayload() {
        val fixture = R8Fixtures.inputBundle()
        var nextCalls = 0
        var opened = false
        val source = R8InputSource(fixture.summary.identities.first()) {
            opened = true
            ByteArrayInputStream(fixture.payloads.first())
        }
        val endless = object : AbstractCollection<R8InputSource>() {
            override val size: Int get() = throw AssertionError("Collection size must not be trusted")
            override fun iterator(): Iterator<R8InputSource> = object : Iterator<R8InputSource> {
                override fun hasNext() = true
                override fun next(): R8InputSource {
                    nextCalls++
                    if (nextCalls > 82) throw AssertionError("Writer iterated past the protocol limit plus one")
                    return source
                }
            }
        }

        val failure = expectContractFailure {
            R8InputBundleCodec.write(ByteArrayOutputStream(), endless, R8Fixtures.capabilities())
        }

        assertTrue(failure is R8ContractException)
        assertEquals(R8ContractViolation.INVALID_VALUE, (failure as R8ContractException).violation)
        assertEquals(82, nextCalls)
        assertFalse(opened)
    }

    @Test fun trailingDataIsRejected() {
        val bytes = R8Fixtures.inputBundle().bytes + byteArrayOf(0)
        expectContractFailure { R8InputBundleCodec.read(ByteArrayInputStream(bytes)) { _, _ -> } }
    }

    @Test fun payloadDigestDriftIsRejected() {
        val bytes = R8Fixtures.inputBundle().bytes.copyOf().also { it[it.lastIndex] = (it.last() xor 1) }
        expectContractFailure { R8InputBundleCodec.read(ByteArrayInputStream(bytes)) { _, _ -> } }
    }

    @Test fun admittedTableMismatchIsRejectedBeforePayloadCallback() {
        val caps = R8Fixtures.capabilities()
        val fixture = R8Fixtures.inputBundle(caps)
        val bytes = fixture.bytes.copyOf().also { it[36] = (it[36].toInt() xor 1).toByte() }
        var callbackReached = false
        expectContractFailure {
            R8InputBundleCodec.read(ByteArrayInputStream(bytes), fixture.request, caps) { _, _ -> callbackReached = true }
        }
        assertFalse(callbackReached)
    }

    @Test fun providerSmallerBundleBudgetRejectsBeforePayloadCallback() {
        val baseline = R8Fixtures.inputBundle()
        val admittedMaximum = baseline.summary.sizeBytes - 1
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
            inputBundleSize = baseline.summary.sizeBytes,
            inputBundleSha256 = baseline.summary.contentSha256,
        )
        var callbackReached = false
        expectContractFailure {
            R8InputBundleCodec.read(ByteArrayInputStream(baseline.bytes), request, caps) { _, _ -> callbackReached = true }
        }
        assertFalse(callbackReached)
    }

    @Test fun consumerRulesBindExactClasspathOrdinalAndDigest() {
        val consumer = R8Fixtures.inputIdentities.single { it.role == R8InputRole.CONSUMER_RULES }
        val classpath = R8Fixtures.inputIdentities.single { it.role == R8InputRole.CLASSPATH_JAR }
        assertEquals(classpath.ordinal, consumer.ownerClasspathOrdinal)
        assertEquals(classpath.contentSha256, consumer.ownerClasspathSha256)
        R8CompilerValidation.validateInputIdentities(R8Fixtures.inputIdentities)
    }

    @Test fun bundleSummaryIdentitiesAreImmutableSnapshots() {
        val mutable = R8Fixtures.inputIdentities.toMutableList()
        val summary = R8InputBundleSummary(mutable, 1, R8Fixtures.sha("summary"))
        mutable.clear()
        assertEquals(R8Fixtures.inputIdentities, summary.identities)
        @Suppress("UNCHECKED_CAST")
        expectUnsupported { (summary.identities as MutableList<R8InputIdentity>).clear() }
    }

    @Test fun invalidRoleOrderAndOwnerDigestAreRejected() {
        val reordered = R8Fixtures.inputIdentities.toMutableList().also { values ->
            val keep = values.removeAt(2)
            values.add(1, keep)
        }
        expectContractFailure { R8CompilerValidation.validateInputIdentities(reordered) }
        val wrongOwner = R8Fixtures.inputIdentities.map {
            if (it.role == R8InputRole.CONSUMER_RULES) it.copy(ownerClasspathSha256 = R8Fixtures.sha("wrong")) else it
        }
        expectContractFailure { R8CompilerValidation.validateInputIdentities(wrongOwner) }
        assertTrue(wrongOwner.isNotEmpty())
    }

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()

    private companion object {
        val inputStreamReadMethod = java.io.InputStream::class.java.getMethod(
            "read",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    }

    private fun expectUnsupported(block: () -> Unit) {
        try { block(); throw AssertionError("Expected immutable collection") }
        catch (_: UnsupportedOperationException) { }
    }
}
