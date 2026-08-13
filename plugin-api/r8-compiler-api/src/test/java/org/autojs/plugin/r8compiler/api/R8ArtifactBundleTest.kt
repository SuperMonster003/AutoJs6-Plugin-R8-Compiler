package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class R8ArtifactBundleTest {
    @Test fun canonicalBundleRoundTripsAllFiveArtifacts() {
        val fixture = R8Fixtures.artifactBundle()
        val decoded = mutableListOf<Pair<R8ArtifactIdentity, ByteArray>>()
        val summary = R8ArtifactBundleCodec.read(ByteArrayInputStream(fixture.bytes)) { identity, stream ->
            decoded += identity to stream.readBytes()
        }
        assertEquals(fixture.summary, summary)
        fixture.payloads.forEachIndexed { index, bytes -> assertArrayEquals(bytes, decoded[index].second) }
    }

    @Test fun consumerSafeReaderBindsResultAndValidatesTextAndMetadata() {
        val caps = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(caps)
        val fixture = R8Fixtures.artifactBundle(caps, input.request)
        val summary = R8ArtifactBundleCodec.read(
            ByteArrayInputStream(fixture.bytes),
            fixture.result,
            input.request,
            caps,
        ) { _, _ -> }
        assertEquals(fixture.summary, summary)
    }

    @Test fun entryInputReturnsZeroForZeroLengthReadsIncludingEmptyArtifacts() {
        val fixture = R8Fixtures.artifactBundle()
        val decoded = mutableListOf<ByteArray>()
        R8ArtifactBundleCodec.read(ByteArrayInputStream(fixture.bytes)) { _, stream ->
            assertEquals(0, stream.read(ByteArray(0), 0, 0))
            decoded += stream.readBytes()
            assertEquals(0, stream.read(ByteArray(1), 0, 0))
        }
        fixture.payloads.forEachIndexed { index, payload -> assertArrayEquals(payload, decoded[index]) }
    }

    @Test fun requestAwareWriterRejectsBeforeOpeningPayload() {
        val capabilities = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(capabilities)
        val fixture = R8Fixtures.artifactBundle(capabilities, input.request)
        val request = R8Fixtures.request(
            capabilities = capabilities,
            requestedArtifacts = input.request.requestedArtifacts.map {
                if (it.role == R8ArtifactRole.DEX_ZIP) it.copy(maxBytes = 1) else it
            },
        )
        var opened = false
        val sources = fixture.summary.identities.mapIndexed { index, identity ->
            R8ArtifactSource(identity) { opened = true; ByteArrayInputStream(fixture.payloads[index]) }
        }
        expectContractFailure {
            R8ArtifactBundleCodec.write(ByteArrayOutputStream(), sources, request, capabilities)
        }
        assertFalse(opened)
    }

    @Test fun requestAwareWriterSnapshotsCallerCollectionExactlyOnce() {
        val capabilities = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(capabilities)
        val fixture = R8Fixtures.artifactBundle(capabilities, input.request)
        val sources = fixture.summary.identities.mapIndexed { index, identity ->
            R8ArtifactSource(identity) { ByteArrayInputStream(fixture.payloads[index]) }
        }
        var iterations = 0
        val singleUse = object : AbstractCollection<R8ArtifactSource>() {
            override val size: Int = sources.size
            override fun iterator(): Iterator<R8ArtifactSource> {
                check(++iterations == 1) { "Caller collection was iterated more than once" }
                return sources.iterator()
            }
        }
        R8ArtifactBundleCodec.write(ByteArrayOutputStream(), singleUse, input.request, capabilities)
        assertEquals(1, iterations)
    }

    @Test fun requestAwareWriterBoundsHostileCollectionBeforeValidationOrPayloadOpen() {
        val fixture = R8Fixtures.artifactBundle()
        var nextCalls = 0
        var opened = false
        val source = R8ArtifactSource(fixture.summary.identities.first()) {
            opened = true
            ByteArrayInputStream(fixture.payloads.first())
        }
        val endless = object : AbstractCollection<R8ArtifactSource>() {
            override val size: Int get() = throw AssertionError("Collection size must not be trusted")
            override fun iterator(): Iterator<R8ArtifactSource> = object : Iterator<R8ArtifactSource> {
                override fun hasNext() = true
                override fun next(): R8ArtifactSource {
                    nextCalls++
                    if (nextCalls > 6) throw AssertionError("Writer iterated past the exact artifact count plus one")
                    return source
                }
            }
        }

        val failure = expectContractFailure {
            R8ArtifactBundleCodec.write(
                ByteArrayOutputStream(),
                endless,
                R8Fixtures.inputBundle().request,
                R8Fixtures.capabilities(),
            )
        }

        assertTrue(failure is R8ContractException)
        assertEquals(R8ContractViolation.INVALID_VALUE, (failure as R8ContractException).violation)
        assertEquals(6, nextCalls)
        assertFalse(opened)
    }

    @Test fun mappingMustBeNonEmpty() {
        expectContractFailure { R8Fixtures.artifactBundle(mapping = ByteArray(0)) }
    }

    @Test fun reportTextRejectsBomCrNulInvalidUtf8AndMissingFinalLf() {
        listOf(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 'x'.code.toByte(), '\n'.code.toByte()),
            "x\r\n".toByteArray(),
            byteArrayOf('x'.code.toByte(), 0, '\n'.code.toByte()),
            byteArrayOf(0xc3.toByte(), 0x28),
            "missing-final-lf".toByteArray(),
        ).forEach { hostile ->
            expectContractFailure { R8ArtifactContentValidation.validateText(hostile, requireContent = true) }
        }
    }

    @Test fun emptySeedsAndUsageAreCanonical() {
        R8ArtifactContentValidation.validateText(ByteArray(0), requireContent = false)
    }

    @Test fun metadataMappingDigestDriftIsRejected() {
        val caps = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(caps)
        val fixture = R8Fixtures.artifactBundle(
            caps,
            input.request,
            metadataTransform = { it.copy(mappingSha256 = R8Fixtures.sha("wrong")) },
        )
        expectContractFailure {
            R8ArtifactBundleCodec.read(
                ByteArrayInputStream(fixture.bytes),
                fixture.result,
                input.request,
                caps,
            ) { _, _ -> }
        }
    }

    @Test fun admittedArtifactTableMismatchIsRejectedBeforePayloadCallback() {
        val caps = R8Fixtures.capabilities()
        val input = R8Fixtures.inputBundle(caps)
        val fixture = R8Fixtures.artifactBundle(caps, input.request)
        val bytes = fixture.bytes.copyOf().also { it[28] = (it[28].toInt() xor 1).toByte() }
        var callbackReached = false
        expectContractFailure {
            R8ArtifactBundleCodec.read(ByteArrayInputStream(bytes), fixture.result, input.request, caps) { _, _ ->
                callbackReached = true
            }
        }
        assertFalse(callbackReached)
    }

    @Test fun outputBundleRejectsTrailingAndDigestDrift() {
        val fixture = R8Fixtures.artifactBundle()
        expectContractFailure {
            R8ArtifactBundleCodec.read(ByteArrayInputStream(fixture.bytes + byteArrayOf(0))) { _, _ -> }
        }
        val mutated = fixture.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        expectContractFailure { R8ArtifactBundleCodec.read(ByteArrayInputStream(mutated)) { _, _ -> } }
    }

    @Test fun exactArtifactRoleOrderAndOrdinalAreRequired() {
        val fixture = R8Fixtures.artifactBundle()
        val reversed = fixture.summary.identities.reversed()
        expectContractFailure { R8CompilerValidation.validateArtifactIdentities(reversed) }
        val wrongOrdinal = fixture.summary.identities.mapIndexed { index, identity ->
            if (index == 1) identity.copy(ordinal = 1) else identity
        }
        expectContractFailure { R8CompilerValidation.validateArtifactIdentities(wrongOrdinal) }
    }

    @Test fun artifactSummaryIdentitiesAreImmutableSnapshots() {
        val fixture = R8Fixtures.artifactBundle()
        val mutable = fixture.summary.identities.toMutableList()
        val summary = R8ArtifactBundleSummary(mutable, 1, R8Fixtures.sha("summary"))
        mutable.clear()
        assertEquals(fixture.summary.identities, summary.identities)
        @Suppress("UNCHECKED_CAST")
        try {
            (summary.identities as MutableList<R8ArtifactIdentity>).clear()
            throw AssertionError("Expected immutable collection")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun writerRejectsPayloadThatExceedsDeclaredSize() {
        val fixture = R8Fixtures.artifactBundle()
        val identities = fixture.summary.identities
        val output = ByteArrayOutputStream()
        expectContractFailure {
            R8ArtifactBundleCodec.write(output, identities.mapIndexed { index, identity ->
                val payload = fixture.payloads[index] + byteArrayOf(0)
                R8ArtifactSource(identity) { ByteArrayInputStream(payload) }
            })
        }
    }
}
