package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertEquals
import org.junit.Test

class R8CollectionBoundaryTest {
    @Test fun publicFingerprintAndEncodedSizeInputsStopAtProtocolMaximumPlusOne() {
        val runtime = R8Fixtures.capabilities().runtimeLibraryIdentities.first()
        assertBoundedCollection(513, runtime) { R8RuntimeLibraryFingerprint.compute(it) }

        val input = R8Fixtures.inputIdentities.first()
        assertBoundedCollection(82, input) { R8InputSetFingerprint.compute(it) }
        assertBoundedCollection(82, input) { R8InputBundleCodec.encodedSize(it) }

        val artifact = R8Fixtures.artifactBundle().summary.identities.first()
        assertBoundedCollection(6, artifact) { R8ArtifactBundleCodec.encodedSize(it) }
    }

    @Test fun publicListValidatorsStopAtProtocolMaximumPlusOneWithoutTrustingSize() {
        val input = R8Fixtures.inputIdentities.first()
        assertBoundedList(82, input) { R8CompilerValidation.validateInputIdentities(it) }

        val requested = R8Fixtures.inputBundle().request.requestedArtifacts.first()
        assertBoundedList(6, requested) { R8CompilerValidation.validateRequestedArtifacts(it) }

        val artifact = R8Fixtures.artifactBundle().summary.identities.first()
        assertBoundedList(6, artifact) { R8CompilerValidation.validateArtifactIdentities(it) }

        val diagnostic = R8Diagnostic(R8DiagnosticSeverity.INFO, "BOUNDED", "bounded")
        assertBoundedList(513, diagnostic) { R8CompilerValidation.validateDiagnostics(it) }
    }

    @Test fun publicDtoSnapshotsAreBoundedAndDoNotTrustCollectionSize() {
        val input = R8Fixtures.inputIdentities.first()
        assertBoundedCollection(82, input) {
            R8InputBundleSummary(it, 1, R8Fixtures.sha("input-summary"))
        }

        val artifact = R8Fixtures.artifactBundle().summary.identities.first()
        assertBoundedCollection(6, artifact) {
            R8ArtifactBundleSummary(it, 1, R8Fixtures.sha("artifact-summary"))
        }

        val diagnostic = R8Diagnostic(R8DiagnosticSeverity.INFO, "BOUNDED", "bounded")
        assertBoundedCollection(513, diagnostic) {
            R8Error(
                R8Fixtures.inputBundle().request.requestId,
                R8ErrorCode.INTERNAL,
                R8FailurePhase.CLEANUP,
                "bounded",
                0,
                it,
            )
        }

        val caps = R8Fixtures.capabilities()
        assertBoundedCollection(2, R8CompilerProfile.FULL_RELEASE) { profiles ->
            R8CompilerCapabilities(
                caps.compilerFamily,
                caps.compilerVersion,
                profiles,
                caps.minApi,
                caps.maxApi,
                caps.inputLayout,
                caps.outputLayout,
                caps.artifactRoles,
                caps.determinismClaim,
                caps.runtimeLibraryModel,
                caps.rulePolicyVersion,
                caps.canonicalizationPolicyVersion,
                caps.implicitRulePolicy,
                caps.limits,
                caps.runtimeLibraryIdentities,
                caps.runtimeLibraryFingerprint,
                caps.capabilityFingerprint,
                caps.supportsMultiDex,
            )
        }
    }

    @Test fun publicCollectionInputsUseOneStableSnapshot() {
        val expected = R8Fixtures.inputIdentities
        var iterations = 0
        val singleUse = object : AbstractCollection<R8InputIdentity>() {
            override val size: Int get() = throw AssertionError("Collection size must not be trusted")
            override fun iterator(): Iterator<R8InputIdentity> {
                check(++iterations == 1) { "Caller collection was iterated more than once" }
                return expected.iterator()
            }
        }

        assertEquals(R8InputSetFingerprint.compute(expected), R8InputSetFingerprint.compute(singleUse))
        assertEquals(1, iterations)

        val mutable = expected.toMutableList()
        val summary = R8InputBundleSummary(mutable, 1, R8Fixtures.sha("mutable-snapshot"))
        mutable.clear()
        assertEquals(expected, summary.identities)
    }

    private fun <T> assertBoundedCollection(expectedNextCalls: Int, value: T, block: (Collection<T>) -> Unit) {
        var nextCalls = 0
        val endless = object : AbstractCollection<T>() {
            override val size: Int get() = throw AssertionError("Collection size must not be trusted")
            override fun iterator(): Iterator<T> = object : Iterator<T> {
                override fun hasNext() = true
                override fun next(): T {
                    nextCalls++
                    if (nextCalls > expectedNextCalls) {
                        throw AssertionError("Collection was iterated past the protocol maximum plus one")
                    }
                    return value
                }
            }
        }
        expectInvalid { block(endless) }
        assertEquals(expectedNextCalls, nextCalls)
    }

    private fun <T> assertBoundedList(expectedNextCalls: Int, value: T, block: (List<T>) -> Unit) {
        var nextCalls = 0
        val endless = object : AbstractList<T>() {
            override val size: Int get() = throw AssertionError("List size must not be trusted")
            override fun get(index: Int): T = throw AssertionError("List indexing must not be trusted")
            override fun iterator(): Iterator<T> = object : Iterator<T> {
                override fun hasNext() = true
                override fun next(): T {
                    nextCalls++
                    if (nextCalls > expectedNextCalls) {
                        throw AssertionError("List was iterated past the protocol maximum plus one")
                    }
                    return value
                }
            }
        }
        expectInvalid { block(endless) }
        assertEquals(expectedNextCalls, nextCalls)
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected bounded collection rejection")
        } catch (error: R8ContractException) {
            assertEquals(R8ContractViolation.INVALID_VALUE, error.violation)
        }
    }
}
