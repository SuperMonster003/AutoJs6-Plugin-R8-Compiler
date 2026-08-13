package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class R8CodecRoundTripTest {
    @Test fun compilerInfoRoundTrips() {
        val value = R8Fixtures.info()
        assertEquals(value, R8CompilerCodec.decodeInfo(R8CompilerCodec.encodeInfo(value)))
    }

    @Test fun capabilitiesRoundTripRepeatedEnumsRuntimeAndAllLimits() {
        val value = R8Fixtures.capabilities()
        val decoded = R8CompilerCodec.decodeCapabilities(R8CompilerCodec.encodeCapabilities(value))
        assertEquals(value.compilerVersion, decoded.compilerVersion)
        assertEquals(value.profiles, decoded.profiles)
        assertEquals(value.artifactRoles, decoded.artifactRoles)
        assertEquals(value.runtimeLibraryIdentities, decoded.runtimeLibraryIdentities)
        assertEquals(value.limits, decoded.limits)
        assertEquals(value.capabilityFingerprint, decoded.capabilityFingerprint)
    }

    @Test fun capabilitiesRoundTripTheMaximumRuntimeIdentityCount() {
        val identity = R8RuntimeLibraryIdentity(1, R8Fixtures.sha("runtime-max"))
        val value = R8Fixtures.capabilities(
            runtime = List(R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES) { identity },
        )
        val decoded = R8CompilerCodec.decodeCapabilities(R8CompilerCodec.encodeCapabilities(value))
        assertEquals(R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES, decoded.runtimeLibraryIdentities.size)
        assertEquals(value.runtimeLibraryFingerprint, decoded.runtimeLibraryFingerprint)
    }

    @Test fun requestRoundTripsEveryPinnedIdentity() {
        val value = R8Fixtures.request()
        val decoded = R8CompilerCodec.decodeRequest(R8CompilerCodec.encodeRequest(value))
        assertEquals(value.requestId, decoded.requestId)
        assertEquals(R8CompilerIntent.R8_EXPLICIT, decoded.compilerIntent)
        assertEquals(R8FallbackPolicy.NONE, decoded.fallbackPolicy)
        assertEquals(value.inputIdentities, decoded.inputIdentities)
        assertEquals(value.inputSetFingerprint, decoded.inputSetFingerprint)
        assertEquals(value.expectedCapabilityFingerprint, decoded.expectedCapabilityFingerprint)
        assertEquals(value.requestedArtifacts, decoded.requestedArtifacts)
    }

    @Test fun retraceMetadataRoundTripsMappingProvenanceOnly() {
        val fixture = R8Fixtures.artifactBundle()
        val decoded = R8CompilerCodec.decodeRetraceMetadata(R8CompilerCodec.encodeRetraceMetadata(fixture.metadata))
        assertEquals(fixture.metadata, decoded)
        R8CompilerValidation.validateRetraceMetadataAgainst(decoded, fixture.result)
    }

    @Test fun startedRoundTripsPinnedCompilerRuntimeAndProfile() {
        val value = R8Fixtures.started()
        assertEquals(value, R8CompilerCodec.decodeStarted(R8CompilerCodec.encodeStarted(value)))
    }

    @Test fun progressWithRangeRoundTrips() {
        val value = R8Progress(R8Fixtures.request().requestId, 1, R8ProgressStage.COMPILING, 3, 9)
        assertEquals(value, R8CompilerCodec.decodeProgress(R8CompilerCodec.encodeProgress(value)))
    }

    @Test fun progressWithoutRangeRoundTripsAsAbsentFields() {
        val value = R8Progress(R8Fixtures.request().requestId, 1, R8ProgressStage.QUEUED)
        val decoded = R8CompilerCodec.decodeProgress(R8CompilerCodec.encodeProgress(value))
        assertNull(decoded.current)
        assertNull(decoded.total)
    }

    @Test fun resultRoundTripsAllFiveArtifactIdentities() {
        val value = R8Fixtures.artifactBundle().result
        val decoded = R8CompilerCodec.decodeResult(R8CompilerCodec.encodeResult(value))
        assertEquals(value.requestId, decoded.requestId)
        assertEquals(value.artifactIdentities, decoded.artifactIdentities)
        assertEquals(value.outputBundleSha256, decoded.outputBundleSha256)
        assertEquals(R8DeterminismClaim.NOT_CLAIMED, decoded.determinismClaim)
    }

    @Test fun errorRoundTripsWithoutRetryabilitySemantics() {
        val value = R8Error(
            R8Fixtures.request().requestId,
            R8ErrorCode.COMPILATION_FAILED,
            R8FailurePhase.COMPILATION,
            "compiler rejected input",
            8,
            listOf(R8Diagnostic(R8DiagnosticSeverity.ERROR, "R8_FAILURE", "bounded diagnostic")),
        )
        val decoded = R8CompilerCodec.decodeError(R8CompilerCodec.encodeError(value))
        assertEquals(value.code, decoded.code)
        assertEquals(value.phase, decoded.phase)
        assertEquals(value.message, decoded.message)
        assertEquals(value.diagnostics, decoded.diagnostics)
    }

    @Test fun providerDeadlineUsesExplicitTimeoutFailureWithoutCancellationSemantics() {
        val value = R8Error(
            R8Fixtures.request().requestId,
            R8ErrorCode.TIMEOUT,
            R8FailurePhase.COMPILATION,
            "request deadline exceeded",
            120_000,
        )
        val decoded = R8CompilerCodec.decodeError(R8CompilerCodec.encodeError(value))
        assertEquals(R8ErrorCode.TIMEOUT, decoded.code)
        assertEquals(R8FailurePhase.COMPILATION, decoded.phase)
        assertFalse(R8CancellationReason.values().any { it.name == "TIMEOUT" })
    }

    @Test fun cancellationRoundTripsWithoutFallbackSemantics() {
        val value = R8Cancellation(
            R8Fixtures.request().requestId,
            R8CancellationReason.REQUESTED,
            R8FailurePhase.COMPILATION,
            9,
        )
        assertEquals(value, R8CompilerCodec.decodeCancellation(R8CompilerCodec.encodeCancellation(value)))
    }

    @Test fun capabilitiesCollectionsAreImmutable() {
        val values = R8Fixtures.capabilities().artifactRoles as MutableList<R8ArtifactRole>
        expectUnsupported { values.clear() }
    }

    @Test fun requestCollectionsAreImmutable() {
        val values = R8Fixtures.request().inputIdentities as MutableList<R8InputIdentity>
        expectUnsupported { values.clear() }
    }

    @Test fun resultCollectionsAreImmutable() {
        val values = R8Fixtures.artifactBundle().result.artifactIdentities as MutableList<R8ArtifactIdentity>
        expectUnsupported { values.clear() }
    }

    @Test fun capabilityFingerprintIsStableAndFieldSensitive() {
        val first = R8Fixtures.capabilities()
        val same = R8Fixtures.capabilities()
        val changed = R8Fixtures.capabilities(compilerVersion = "8.13.18")
        assertEquals(first.capabilityFingerprint, same.capabilityFingerprint)
        assertNotEquals(first.capabilityFingerprint, changed.capabilityFingerprint)
    }

    private fun expectUnsupported(block: () -> Unit) {
        try {
            block()
            fail("Expected immutable collection")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
    }
}
