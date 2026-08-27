package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ArtifactBundleCodec
import org.autojs.plugin.r8compiler.api.R8ArtifactRole
import org.autojs.plugin.r8compiler.api.R8CompilerCodec
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerValidation
import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import org.autojs.plugin.r8compiler.api.R8RetraceInputBundleCodec
import org.autojs.plugin.r8compiler.api.R8Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

class R8CompilerEngineIntegrationTest {
    @Test
    fun realR8ProducesAndContractConsumesAllFiveArtifacts() {
        val root = Files.createTempDirectory("r8-provider-engine-").toFile()
        try {
            val (runtime, capabilities) = R8ProviderTestFixtures.capabilities()
            val fixture = R8ProviderTestFixtures.requestFixture(root, capabilities)
            PrivateSessionWorkspace.createUnder(root.resolve("sessions")).use { workspace ->
                val materialized = fixture.bundle.inputStream().buffered().use { input ->
                    R8InputMaterializer.materialize(fixture.request, capabilities, input, workspace)
                }
                var packagingCalled = false
                val artifact = R8CompilerEngine(runtime, sdkInt = { 26 }).compile(
                    fixture.request,
                    capabilities,
                    materialized,
                    workspace,
                    isActive = { true },
                    ensureActive = {},
                    beforePackaging = { packagingCalled = true },
                )
                assertTrue(packagingCalled)
                assertEquals(R8ArtifactRole.values().toList(), artifact.summary.identities.map { it.role })
                assertEquals(artifact.file.length(), artifact.summary.sizeBytes)

                val result = R8Result(
                    fixture.request.requestId,
                    fixture.request.compilerFamily,
                    capabilities.compilerVersion,
                    capabilities.capabilityFingerprint,
                    capabilities.runtimeLibraryFingerprint,
                    fixture.request.inputSetFingerprint,
                    fixture.request.profile,
                    fixture.request.minApi,
                    fixture.request.outputLayout,
                    artifact.summary.sizeBytes,
                    artifact.summary.contentSha256,
                    artifact.summary.identities,
                    capabilities.determinismClaim,
                    1L,
                    artifact.diagnostics,
                )
                R8CompilerValidation.validateResultAgainst(result, fixture.request, capabilities)
                val payloads = linkedMapOf<R8ArtifactRole, ByteArray>()
                artifact.file.inputStream().buffered().use { input ->
                    R8ArtifactBundleCodec.read(input, result, fixture.request, capabilities) { identity, entry ->
                        payloads[identity.role] = entry.readBytes()
                    }
                }
                assertEquals(R8ArtifactRole.values().toSet(), payloads.keys)
                val mapping = payloads.getValue(R8ArtifactRole.MAPPING_TEXT)
                val retraceMetadata = payloads.getValue(R8ArtifactRole.RETRACE_METADATA)
                assertTrue(mapping.isNotEmpty())
                assertTrue(payloads.getValue(R8ArtifactRole.USAGE_TEXT).isNotEmpty())
                assertTrue(retraceMetadata.isNotEmpty())
                val decodedRetraceMetadata = R8CompilerCodec.decodeRetraceMetadata(retraceMetadata)
                val retraceCapabilities = R8CompilerRuntime.retraceCapabilities()
                assertEquals(retraceCapabilities.mappingFormatId, decodedRetraceMetadata.formatId)
                assertEquals(retraceCapabilities.mappingFormatVersion, decodedRetraceMetadata.formatVersion)
                R8RetraceInputBundleCodec.write(
                    ByteArrayOutputStream(),
                    mapping,
                    retraceMetadata,
                    "java.lang.IllegalStateException\n    at a.a(SourceFile:1)\n".toByteArray(),
                    retraceCapabilities,
                )

                val dexNames = ArrayList<String>()
                ZipInputStream(payloads.getValue(R8ArtifactRole.DEX_ZIP).inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        dexNames += entry.name
                    }
                }
                assertFalse(dexNames.isEmpty())
                assertEquals("classes.dex", dexNames.first())
                assertTrue(dexNames.withIndex().all { (index, name) ->
                    name == if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aggregateOutputLimitIsReportedAsOutputTooLarge() {
        val root = Files.createTempDirectory("r8-provider-output-limit-").toFile()
        try {
            val (runtime, capabilities) = R8ProviderTestFixtures.capabilities()
            val fixture = R8ProviderTestFixtures.requestFixture(
                root,
                capabilities,
                maxOutputBundleBytes = R8CompilerContract.MIN_OUTPUT_BUNDLE_BYTES,
            )
            PrivateSessionWorkspace.createUnder(root.resolve("sessions")).use { workspace ->
                val materialized = fixture.bundle.inputStream().buffered().use { input ->
                    R8InputMaterializer.materialize(fixture.request, capabilities, input, workspace)
                }
                val failure = try {
                    R8CompilerEngine(runtime, sdkInt = { 26 }).compile(
                        fixture.request,
                        capabilities,
                        materialized,
                        workspace,
                        isActive = { true },
                        ensureActive = {},
                        beforePackaging = {},
                    )
                    error("Aggregate bundle budget should reject the output")
                } catch (error: R8CompilerFailure) {
                    error
                }
                assertEquals(R8ErrorCode.OUTPUT_TOO_LARGE, failure.code)
                assertEquals(R8FailurePhase.OUTPUT_PACKAGING, failure.phase)
                assertFalse(workspace.artifactBundle.exists())
                assertFalse(workspace.artifactBundleStaging.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun api24CliIsReleaseOnlyAndCarriesExplicitRulesWithoutFallback() {
        val root = Files.createTempDirectory("r8-provider-cli-").toFile()
        try {
            val (runtime, capabilities) = R8ProviderTestFixtures.capabilities()
            val fixture = R8ProviderTestFixtures.requestFixture(root, capabilities)
            PrivateSessionWorkspace.createUnder(root.resolve("sessions")).use { workspace ->
                val materialized = fixture.bundle.inputStream().use { input ->
                    R8InputMaterializer.materialize(fixture.request, capabilities, input, workspace)
                }
                val captured = AtomicReference<List<String>>()
                val failure = try {
                    R8CompilerEngine(
                        runtime,
                        sdkInt = { 24 },
                        cliRunner = R8CliRunner { arguments ->
                            captured.set(arguments.toList())
                            throw IllegalStateException("stop after capture")
                        },
                    ).compile(
                        fixture.request,
                        capabilities,
                        materialized,
                        workspace,
                        isActive = { true },
                        ensureActive = {},
                        beforePackaging = {},
                    )
                    error("CLI capture should stop compilation")
                } catch (error: R8CompilerFailure) {
                    error
                }
                assertEquals(org.autojs.plugin.r8compiler.api.R8ErrorCode.COMPILATION_FAILED, failure.code)
                val arguments = captured.get()
                assertTrue("--release" in arguments)
                assertTrue("--pg-conf" in arguments)
                assertTrue("--output" in arguments)
                assertFalse("--debug" in arguments)
                assertFalse(arguments.any { it.contains("d8", ignoreCase = true) || it.equals("dx", true) })
                assertTrue(workspace.providerControlRules.readText().contains("-printmapping"))
                assertTrue(workspace.providerControlRules.readText().contains("-printseeds"))
                assertTrue(workspace.providerControlRules.readText().contains("-printusage"))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
