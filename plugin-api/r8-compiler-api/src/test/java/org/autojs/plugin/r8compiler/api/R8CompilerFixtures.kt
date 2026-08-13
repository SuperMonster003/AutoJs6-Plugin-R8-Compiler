package org.autojs.plugin.r8compiler.api

import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object R8Fixtures {
    val programBytes = "program-jar".toByteArray()
    val classpathBytes = "classpath-jar".toByteArray()
    val keepBytes = "-keep class sample.Main {\n  public static void main(...);\n}\n".toByteArray()
    val consumerBytes = "-dontwarn sample.optional.**\n".toByteArray()
    val mappingBytes = "sample.Main -> a:\n    void main() -> a\n".toByteArray()
    val dexBytes = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0)

    val runtimeIdentities = listOf(R8RuntimeLibraryIdentity(7, sha("runtime")))
    val runtimeFingerprint = R8RuntimeLibraryFingerprint.compute(runtimeIdentities)

    val inputIdentities: List<R8InputIdentity> by lazy {
        val classpath = input(R8InputRole.CLASSPATH_JAR, 0, classpathBytes)
        listOf(
            input(R8InputRole.PROGRAM_JAR, 0, programBytes),
            classpath,
            input(R8InputRole.KEEP_RULES, 0, keepBytes),
            input(
                R8InputRole.CONSUMER_RULES,
                0,
                consumerBytes,
                ownerOrdinal = 0,
                ownerDigest = classpath.contentSha256,
            ),
        )
    }

    fun info() = R8CompilerInfo(
        protocolMin = R8CompilerContract.PROTOCOL_V1,
        protocolMax = R8CompilerContract.PROTOCOL_V1,
        providerId = R8CompilerContract.PROVIDER_ID,
        providerVersionName = "0.1.0",
        providerVersionCode = 1,
        minHostVersionCode = 100,
        maxHostVersionCode = 200,
    )

    fun limits(
        maxProgramBytes: Long = 128.mib,
        maxClasspathCount: Int = 32,
        maxClasspathJarBytes: Long = 64.mib,
        maxTotalClasspathBytes: Long = 128.mib,
        maxKeepRuleFiles: Int = 16,
        maxConsumerRuleFiles: Int = 32,
        maxRuleFileBytes: Long = 256.kib,
        maxTotalRuleBytes: Long = 2.mib,
        maxRuleLinesPerFile: Int = 4_096,
        maxTotalRuleLines: Int = 16_384,
        maxRuleLineBytes: Int = 16.kib.toInt(),
        maxInputBundleBytes: Long = 260.mib,
        maxArchiveEntries: Int = 20_000,
        maxTotalArchiveEntries: Int = 60_000,
        maxUncompressedProgramBytes: Long = 256.mib,
        maxTotalUncompressedInputBytes: Long = 512.mib,
        maxTotalClassBytes: Long = 256.mib,
        maxSingleClassBytes: Long = 8.mib,
        maxOutputBundleBytes: Long = 256.mib,
        maxDexZipBytes: Long = 192.mib,
        maxMappingBytes: Long = 32.mib,
        maxSeedsBytes: Long = 16.mib,
        maxUsageBytes: Long = 16.mib,
        maxRetraceMetadataBytes: Long = 256.kib,
        maxDexEntries: Int = 64,
        maxDiagnosticBytes: Int = 64.kib.toInt(),
        maxConcurrentSessions: Int = 1,
        defaultTimeoutMillis: Long = 120_000,
        maxTimeoutMillis: Long = 300_000,
    ) = R8ResourceLimits(
        maxProgramBytes, maxClasspathCount, maxClasspathJarBytes, maxTotalClasspathBytes,
        maxKeepRuleFiles, maxConsumerRuleFiles, maxRuleFileBytes, maxTotalRuleBytes,
        maxRuleLinesPerFile, maxTotalRuleLines, maxRuleLineBytes, maxInputBundleBytes,
        maxArchiveEntries, maxTotalArchiveEntries, maxUncompressedProgramBytes,
        maxTotalUncompressedInputBytes, maxTotalClassBytes, maxSingleClassBytes,
        maxOutputBundleBytes, maxDexZipBytes, maxMappingBytes, maxSeedsBytes, maxUsageBytes,
        maxRetraceMetadataBytes, maxDexEntries, maxDiagnosticBytes, maxConcurrentSessions,
        defaultTimeoutMillis, maxTimeoutMillis,
    )

    fun capabilities(
        limits: R8ResourceLimits = limits(),
        compilerVersion: String = "8.13.17",
        minApi: Int = 24,
        maxApi: Int = 36,
        runtime: List<R8RuntimeLibraryIdentity> = runtimeIdentities,
        runtimeFingerprint: R8Sha256 = R8RuntimeLibraryFingerprint.compute(runtime),
        supportsMultiDex: Boolean = true,
    ): R8CompilerCapabilities {
        val provisional = R8CompilerCapabilities(
            R8CompilerFamily.R8,
            compilerVersion,
            listOf(R8CompilerProfile.FULL_RELEASE),
            minApi,
            maxApi,
            R8InputLayout.PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1,
            R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1,
            R8ArtifactRole.values().toList(),
            R8DeterminismClaim.NOT_CLAIMED,
            R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1,
            R8CompilerContract.RULE_POLICY_VERSION,
            R8CompilerContract.CANONICALIZATION_POLICY_VERSION,
            R8ImplicitRulePolicy.NONE,
            limits,
            runtime,
            runtimeFingerprint,
            R8Sha256.ZERO,
            supportsMultiDex,
        )
        return R8CompilerCapabilities(
            provisional.compilerFamily,
            provisional.compilerVersion,
            provisional.profiles,
            provisional.minApi,
            provisional.maxApi,
            provisional.inputLayout,
            provisional.outputLayout,
            provisional.artifactRoles,
            provisional.determinismClaim,
            provisional.runtimeLibraryModel,
            provisional.rulePolicyVersion,
            provisional.canonicalizationPolicyVersion,
            provisional.implicitRulePolicy,
            provisional.limits,
            provisional.runtimeLibraryIdentities,
            provisional.runtimeLibraryFingerprint,
            R8CapabilityFingerprint.compute(provisional),
            provisional.supportsMultiDex,
        )
    }

    fun request(
        capabilities: R8CompilerCapabilities = capabilities(),
        inputs: List<R8InputIdentity> = inputIdentities,
        inputBundleSize: Long = R8InputBundleCodec.encodedSize(inputs),
        inputBundleSha256: R8Sha256 = sha("admitted-input-bundle"),
        minApi: Int = 24,
        requestedArtifacts: List<R8RequestedArtifact> = R8ArtifactRole.values().map {
            R8RequestedArtifact(it, maximumFor(capabilities.limits, it))
        },
        maxOutputBundleBytes: Long = capabilities.limits.maxOutputBundleBytes,
        diagnosticByteLimit: Int = capabilities.limits.maxDiagnosticBytes,
        timeoutMillis: Long = capabilities.limits.defaultTimeoutMillis,
    ) = R8CompileRequest(
        R8RequestId.fromUuid(UUID(0, 1)),
        R8CompilerContract.PROTOCOL_V1,
        R8CompilerFamily.R8,
        R8CompilerIntent.R8_EXPLICIT,
        R8FallbackPolicy.NONE,
        R8CompilerProfile.FULL_RELEASE,
        minApi,
        R8InputLayout.PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1,
        inputs,
        R8InputSetFingerprint.compute(inputs),
        inputBundleSize,
        inputBundleSha256,
        R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1,
        capabilities.runtimeLibraryFingerprint,
        capabilities.capabilityFingerprint,
        R8CompilerContract.CANONICALIZATION_POLICY_VERSION,
        R8CompilerContract.RULE_POLICY_VERSION,
        R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1,
        requestedArtifacts,
        maxOutputBundleBytes,
        diagnosticByteLimit,
        timeoutMillis,
    )

    fun inputBundle(capabilities: R8CompilerCapabilities = capabilities()): InputFixture {
        val bytes = ByteArrayOutputStream()
        val payloads = listOf(programBytes, classpathBytes, keepBytes, consumerBytes)
        val summary = R8InputBundleCodec.write(
            bytes,
            inputIdentities.mapIndexed { index, identity ->
                R8InputSource(identity) { ByteArrayInputStream(payloads[index]) }
            },
        )
        val request = request(
            capabilities = capabilities,
            inputBundleSize = summary.sizeBytes,
            inputBundleSha256 = summary.contentSha256,
        )
        return InputFixture(bytes.toByteArray(), payloads, summary, request)
    }

    fun artifactBundle(
        capabilities: R8CompilerCapabilities = capabilities(),
        request: R8CompileRequest = inputBundle(capabilities).request,
        mapping: ByteArray = mappingBytes,
        seeds: ByteArray = ByteArray(0),
        usage: ByteArray = ByteArray(0),
        metadataTransform: (R8RetraceMetadata) -> R8RetraceMetadata = { it },
    ): ArtifactFixture {
        val metadata = metadataTransform(
            R8RetraceMetadata(
                mappingSha256 = R8Sha256.digest(mapping),
                formatId = R8CompilerContract.MAPPING_FORMAT_ID,
                formatVersion = "1",
                compilerVersion = capabilities.compilerVersion,
                capabilityFingerprint = capabilities.capabilityFingerprint,
                runtimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
                inputSetFingerprint = request.inputSetFingerprint,
                minApi = request.minApi,
                profile = request.profile,
            ),
        )
        val metadataBytes = R8CompilerCodec.encodeRetraceMetadata(metadata)
        val payloads = listOf(dexBytes, mapping, seeds, usage, metadataBytes)
        val identities = R8ArtifactRole.values().mapIndexed { index, role ->
            R8ArtifactIdentity(role, 0, payloads[index].size.toLong(), R8Sha256.digest(payloads[index]))
        }
        val output = ByteArrayOutputStream()
        val summary = R8ArtifactBundleCodec.write(
            output,
            identities.mapIndexed { index, identity ->
                R8ArtifactSource(identity) { ByteArrayInputStream(payloads[index]) }
            },
        )
        val result = result(summary, request, capabilities)
        return ArtifactFixture(output.toByteArray(), payloads, summary, result, metadata)
    }

    fun result(
        summary: R8ArtifactBundleSummary,
        request: R8CompileRequest = request(),
        capabilities: R8CompilerCapabilities = capabilities(),
    ) = R8Result(
        request.requestId,
        R8CompilerFamily.R8,
        capabilities.compilerVersion,
        capabilities.capabilityFingerprint,
        capabilities.runtimeLibraryFingerprint,
        request.inputSetFingerprint,
        request.profile,
        request.minApi,
        request.outputLayout,
        summary.sizeBytes,
        summary.contentSha256,
        summary.identities,
        R8DeterminismClaim.NOT_CLAIMED,
        1,
    )

    fun started(request: R8CompileRequest = request(), capabilities: R8CompilerCapabilities = capabilities()) =
        R8Started(
            request.requestId,
            0,
            request.protocolVersion,
            request.compilerFamily,
            capabilities.compilerVersion,
            capabilities.capabilityFingerprint,
            capabilities.runtimeLibraryFingerprint,
            request.profile,
            0,
        )

    fun input(
        role: R8InputRole,
        ordinal: Int,
        bytes: ByteArray,
        ownerOrdinal: Int = -1,
        ownerDigest: R8Sha256 = R8Sha256.ZERO,
    ) = R8InputIdentity(
        role,
        ordinal,
        ownerOrdinal,
        bytes.size.toLong(),
        R8Sha256.digest(bytes),
        ownerDigest,
    )

    fun sha(text: String) = R8Sha256.digest(text.toByteArray(StandardCharsets.UTF_8))
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun maximumFor(limits: R8ResourceLimits, role: R8ArtifactRole): Long = when (role) {
        R8ArtifactRole.DEX_ZIP -> limits.maxDexZipBytes
        R8ArtifactRole.MAPPING_TEXT -> limits.maxMappingBytes
        R8ArtifactRole.SEEDS_TEXT -> limits.maxSeedsBytes
        R8ArtifactRole.USAGE_TEXT -> limits.maxUsageBytes
        R8ArtifactRole.RETRACE_METADATA -> limits.maxRetraceMetadataBytes
    }

    data class InputFixture(
        val bytes: ByteArray,
        val payloads: List<ByteArray>,
        val summary: R8InputBundleSummary,
        val request: R8CompileRequest,
    )

    data class ArtifactFixture(
        val bytes: ByteArray,
        val payloads: List<ByteArray>,
        val summary: R8ArtifactBundleSummary,
        val result: R8Result,
        val metadata: R8RetraceMetadata,
    )

    val Int.kib: Long get() = toLong() * 1024
    val Int.mib: Long get() = kib * 1024
}

internal fun expectContractFailure(block: () -> Unit): IllegalArgumentException {
    try {
        block()
        fail("Expected fail-closed rejection")
    } catch (error: IllegalArgumentException) {
        return error
    }
    throw AssertionError("unreachable")
}
