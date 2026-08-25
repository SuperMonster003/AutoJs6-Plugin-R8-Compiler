package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class R8InputMaterializerTest {
    @Test
    fun forbiddenOutputDirectiveIsRejectedBeforeR8() {
        withFixture("-keep class sample.Entry { *; }\n-printmapping leaked.txt\n") { fixture, capabilities, workspace ->
            val failure = expectFailure {
                fixture.bundle.inputStream().use { input ->
                    R8InputMaterializer.materialize(fixture.request, capabilities, input, workspace)
                }
            }
            assertEquals(R8ErrorCode.RULE_POLICY_REJECTED, failure.code)
            assertEquals(R8FailurePhase.RULE_VALIDATION, failure.phase)
            assertFalse(workspace.r8OutputDirectory.listFiles().orEmpty().isNotEmpty())
        }
    }

    @Test
    fun jarPayloadDigestMutationIsRejectedBeforeR8() {
        withFixture { fixture, capabilities, workspace ->
            val bytes = fixture.bundle.readBytes()
            val programOffset = 16 + fixture.request.inputIdentities.size * 84
            // Keep the framing and admitted identity untouched while mutating payload bytes. The
            // frozen bundle codec must reject the digest mismatch before archive or R8 processing.
            bytes.fill(0x41, programOffset + fixture.request.inputIdentities[0].sizeBytes.toInt() - 4,
                programOffset + fixture.request.inputIdentities[0].sizeBytes.toInt())
            fixture.bundle.writeBytes(bytes)
            val failure = expectFailure {
                fixture.bundle.inputStream().use { input ->
                    R8InputMaterializer.materialize(fixture.request, capabilities, input, workspace)
                }
            }
            assertEquals(R8ErrorCode.INVALID_BUNDLE, failure.code)
            assertEquals(R8FailurePhase.INPUT_VALIDATION, failure.phase)
        }
    }

    private fun withFixture(
        ruleText: String = "-keep class sample.Entry { public static int value(); }\n",
        block: (R8RequestFixture, org.autojs.plugin.r8compiler.api.R8CompilerCapabilities, PrivateSessionWorkspace) -> Unit,
    ) {
        val root = Files.createTempDirectory("r8-input-test-").toFile()
        try {
            val (_, capabilities) = R8ProviderTestFixtures.capabilities()
            val fixture = R8ProviderTestFixtures.requestFixture(root, capabilities, ruleText)
            PrivateSessionWorkspace.createUnder(root.resolve("sessions")).use { workspace ->
                block(fixture, capabilities, workspace)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun expectFailure(block: () -> Unit): R8CompilerFailure = try {
        block()
        fail("Expected R8CompilerFailure")
        error("unreachable")
    } catch (error: R8CompilerFailure) {
        error
    }
}
