package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R8ContractIdentityTest {
    @Test fun independentIdentityConstantsAreExact() {
        assertEquals("org.autojs.plugin.R8_COMPILER", R8CompilerContract.SERVICE_ACTION)
        assertEquals("org.autojs.permission.PLUGIN", R8CompilerContract.PLUGIN_PERMISSION)
        assertEquals("r8-compiler", R8CompilerContract.ENGINE_ID)
        assertEquals("autojs6-r8", R8CompilerContract.PROVIDER_ID)
        assertEquals("com.android.tools.r8.mapping", R8CompilerContract.MAPPING_FORMAT_ID)
        assertFalse(R8CompilerContract.SERVICE_ACTION.contains("DEX"))
    }

    @Test fun wireEnumsExposeOnlyExplicitR8Semantics() {
        assertEquals(listOf(R8CompilerFamily.R8), R8CompilerFamily.values().toList())
        assertEquals(listOf(R8CompilerIntent.R8_EXPLICIT), R8CompilerIntent.values().toList())
        assertEquals(listOf(R8FallbackPolicy.NONE), R8FallbackPolicy.values().toList())
        assertEquals(listOf(R8CompilerProfile.FULL_RELEASE), R8CompilerProfile.values().toList())
        assertTrue(R8CompilerProfile.FULL_RELEASE.run { shrink && optimize && obfuscate })
        assertEquals(11, R8ErrorCode.TIMEOUT.wireCode)
        assertEquals(
            listOf(R8CancellationReason.REQUESTED, R8CancellationReason.SESSION_CLOSED),
            R8CancellationReason.values().toList(),
        )
    }

    @Test fun schemaIdsAreIndependentContiguousAndFrozen() {
        val schemas = listOf(
            R8CompilerContract.SCHEMA_COMPILER_INFO,
            R8CompilerContract.SCHEMA_CAPABILITIES,
            R8CompilerContract.SCHEMA_RUNTIME_LIBRARY_IDENTITY,
            R8CompilerContract.SCHEMA_DIAGNOSTIC,
            R8CompilerContract.SCHEMA_INPUT_IDENTITY,
            R8CompilerContract.SCHEMA_REQUESTED_ARTIFACT,
            R8CompilerContract.SCHEMA_ARTIFACT_IDENTITY,
            R8CompilerContract.SCHEMA_RETRACE_METADATA,
            R8CompilerContract.SCHEMA_RESOURCE_LIMITS,
            R8CompilerContract.SCHEMA_COMPILE_REQUEST,
            R8CompilerContract.SCHEMA_STARTED,
            R8CompilerContract.SCHEMA_PROGRESS,
            R8CompilerContract.SCHEMA_RESULT,
            R8CompilerContract.SCHEMA_ERROR,
            R8CompilerContract.SCHEMA_CANCELLATION,
        )
        assertEquals((1..9).map { 0x5238_0000 + it } + (0x10..0x15).map { 0x5238_0000 + it }, schemas)
        assertEquals(R8ProtocolVersion(1, 0), R8CompilerContract.PROTOCOL_V1)
    }

    @Test fun protocolMaximumsMatchTheFrozenThreatModel() {
        assertEquals(32, R8CompilerContract.MAX_CLASSPATH_JARS)
        assertEquals(185L, R8CompilerContract.MIN_INPUT_BUNDLE_OVERHEAD_WITH_KEEP)
        assertEquals(260L * 1024 * 1024, R8CompilerContract.MAX_INPUT_BUNDLE_BYTES)
        assertEquals(259L, R8CompilerContract.MIN_OUTPUT_BUNDLE_BYTES)
        assertEquals(256L * 1024 * 1024, R8CompilerContract.MAX_OUTPUT_BUNDLE_BYTES)
        assertEquals(64 * 1024, R8CompilerContract.MAX_DIAGNOSTIC_BYTES)
        assertEquals(512, R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES)
        assertEquals(24, R8CompilerContract.MIN_SUPPORTED_API)
        assertEquals(36, R8CompilerContract.MAX_SUPPORTED_API)
        assertEquals(1, R8CompilerContract.MAX_CONCURRENT_SESSIONS)
        assertEquals(300_000L, R8CompilerContract.MAX_TIMEOUT_MILLIS)
    }

    @Test fun inputSetFingerprintBindsOrderRoleOwnerAndDigest() {
        val canonical = R8Fixtures.inputIdentities
        val fingerprint = R8InputSetFingerprint.compute(canonical)
        assertEquals(fingerprint, R8InputSetFingerprint.compute(canonical.toList()))
        val changedKeep = canonical.toMutableList().also {
            it[2] = R8Fixtures.input(R8InputRole.KEEP_RULES, 0, "-keep class Other {}\n".toByteArray())
        }
        assertNotEquals(fingerprint, R8InputSetFingerprint.compute(changedKeep))
    }
}
