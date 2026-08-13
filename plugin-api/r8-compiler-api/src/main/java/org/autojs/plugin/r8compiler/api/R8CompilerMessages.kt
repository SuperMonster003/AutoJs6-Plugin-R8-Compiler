package org.autojs.plugin.r8compiler.api

import java.nio.charset.StandardCharsets
import java.util.Collections

data class R8CompilerInfo(
    val protocolMin: R8ProtocolVersion,
    val protocolMax: R8ProtocolVersion,
    val providerId: String,
    val providerVersionName: String,
    val providerVersionCode: Long,
    val minHostVersionCode: Long? = null,
    val maxHostVersionCode: Long? = null,
)

data class R8RuntimeLibraryIdentity(val sizeBytes: Long, val contentSha256: R8Sha256)

data class R8ResourceLimits(
    val maxProgramBytes: Long,
    val maxClasspathCount: Int,
    val maxClasspathJarBytes: Long,
    val maxTotalClasspathBytes: Long,
    val maxKeepRuleFiles: Int,
    val maxConsumerRuleFiles: Int,
    val maxRuleFileBytes: Long,
    val maxTotalRuleBytes: Long,
    val maxRuleLinesPerFile: Int,
    val maxTotalRuleLines: Int,
    val maxRuleLineBytes: Int,
    val maxInputBundleBytes: Long,
    val maxArchiveEntries: Int,
    val maxTotalArchiveEntries: Int,
    val maxUncompressedProgramBytes: Long,
    val maxTotalUncompressedInputBytes: Long,
    val maxTotalClassBytes: Long,
    val maxSingleClassBytes: Long,
    val maxOutputBundleBytes: Long,
    val maxDexZipBytes: Long,
    val maxMappingBytes: Long,
    val maxSeedsBytes: Long,
    val maxUsageBytes: Long,
    val maxRetraceMetadataBytes: Long,
    val maxDexEntries: Int,
    val maxDiagnosticBytes: Int,
    val maxConcurrentSessions: Int,
    val defaultTimeoutMillis: Long,
    val maxTimeoutMillis: Long,
)

class R8CompilerCapabilities(
    val compilerFamily: R8CompilerFamily,
    val compilerVersion: String,
    profiles: Collection<R8CompilerProfile>,
    val minApi: Int,
    val maxApi: Int,
    val inputLayout: R8InputLayout,
    val outputLayout: R8OutputLayout,
    artifactRoles: Collection<R8ArtifactRole>,
    val determinismClaim: R8DeterminismClaim,
    val runtimeLibraryModel: R8RuntimeLibraryModel,
    val rulePolicyVersion: Int,
    val canonicalizationPolicyVersion: Int,
    val implicitRulePolicy: R8ImplicitRulePolicy,
    val limits: R8ResourceLimits,
    runtimeLibraryIdentities: Collection<R8RuntimeLibraryIdentity>,
    val runtimeLibraryFingerprint: R8Sha256,
    val capabilityFingerprint: R8Sha256,
    val supportsMultiDex: Boolean,
) {
    val profiles = immutableList(profiles, 1, "Compiler profile")
    val artifactRoles = immutableList(artifactRoles, R8ArtifactRole.values().size, "Artifact role")
    val runtimeLibraryIdentities = immutableList(
        runtimeLibraryIdentities,
        R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES,
        "Runtime library identity",
    )
}

data class R8InputIdentity(
    val role: R8InputRole,
    val ordinal: Int,
    val ownerClasspathOrdinal: Int,
    val sizeBytes: Long,
    val contentSha256: R8Sha256,
    val ownerClasspathSha256: R8Sha256,
)

data class R8RequestedArtifact(val role: R8ArtifactRole, val maxBytes: Long)

data class R8ArtifactIdentity(
    val role: R8ArtifactRole,
    val ordinal: Int,
    val sizeBytes: Long,
    val contentSha256: R8Sha256,
)

class R8CompileRequest(
    val requestId: R8RequestId,
    val protocolVersion: R8ProtocolVersion,
    val compilerFamily: R8CompilerFamily,
    val compilerIntent: R8CompilerIntent,
    val fallbackPolicy: R8FallbackPolicy,
    val profile: R8CompilerProfile,
    val minApi: Int,
    val inputLayout: R8InputLayout,
    inputIdentities: Collection<R8InputIdentity>,
    val inputSetFingerprint: R8Sha256,
    val inputBundleSizeBytes: Long,
    val inputBundleSha256: R8Sha256,
    val runtimeLibraryModel: R8RuntimeLibraryModel,
    val expectedRuntimeLibraryFingerprint: R8Sha256,
    val expectedCapabilityFingerprint: R8Sha256,
    val canonicalizationPolicyVersion: Int,
    val rulePolicyVersion: Int,
    val outputLayout: R8OutputLayout,
    requestedArtifacts: Collection<R8RequestedArtifact>,
    val maxOutputBundleBytes: Long,
    val diagnosticByteLimit: Int,
    val timeoutMillis: Long,
) {
    val inputIdentities = immutableList(inputIdentities, maximumInputIdentityCount, "Input identity")
    val requestedArtifacts = immutableList(requestedArtifacts, R8ArtifactRole.values().size, "Requested artifact")
}

data class R8RetraceMetadata(
    val mappingSha256: R8Sha256,
    val formatId: String,
    val formatVersion: String,
    val compilerVersion: String,
    val capabilityFingerprint: R8Sha256,
    val runtimeLibraryFingerprint: R8Sha256,
    val inputSetFingerprint: R8Sha256,
    val minApi: Int,
    val profile: R8CompilerProfile,
)

data class R8Started(
    val requestId: R8RequestId,
    val sequence: Long,
    val protocolVersion: R8ProtocolVersion,
    val compilerFamily: R8CompilerFamily,
    val compilerVersion: String,
    val capabilityFingerprint: R8Sha256,
    val runtimeLibraryFingerprint: R8Sha256,
    val profile: R8CompilerProfile,
    val queueElapsedMillis: Long,
)

data class R8Progress(
    val requestId: R8RequestId,
    val sequence: Long,
    val stage: R8ProgressStage,
    val current: Long? = null,
    val total: Long? = null,
)

data class R8Diagnostic(
    val severity: R8DiagnosticSeverity,
    val code: String,
    val message: String,
)

class R8Result(
    val requestId: R8RequestId,
    val compilerFamily: R8CompilerFamily,
    val compilerVersion: String,
    val capabilityFingerprint: R8Sha256,
    val runtimeLibraryFingerprint: R8Sha256,
    val inputSetFingerprint: R8Sha256,
    val profile: R8CompilerProfile,
    val minApi: Int,
    val outputLayout: R8OutputLayout,
    val outputBundleSizeBytes: Long,
    val outputBundleSha256: R8Sha256,
    artifactIdentities: Collection<R8ArtifactIdentity>,
    val determinismClaim: R8DeterminismClaim,
    val elapsedMillis: Long,
    diagnostics: Collection<R8Diagnostic> = emptyList(),
) {
    val artifactIdentities = immutableList(artifactIdentities, R8ArtifactRole.values().size, "Artifact identity")
    val diagnostics = immutableList(diagnostics, maximumDiagnosticCount, "Diagnostic")
}

class R8Error(
    val requestId: R8RequestId,
    val code: R8ErrorCode,
    val phase: R8FailurePhase,
    val message: String,
    val elapsedMillis: Long,
    diagnostics: Collection<R8Diagnostic> = emptyList(),
) {
    val diagnostics = immutableList(diagnostics, maximumDiagnosticCount, "Diagnostic")
}

data class R8Cancellation(
    val requestId: R8RequestId,
    val reason: R8CancellationReason,
    val phase: R8FailurePhase,
    val elapsedMillis: Long,
)

/**
 * Session law: zero or one started event precedes zero or more strictly increasing progress
 * events; when started exists, every progress sequence is greater than its sequence. Exactly one
 * terminal event follows. No callback is legal after terminal. cancel/close are
 * idempotent, descriptors are owned by the callee after dispatch, and dispatch is never retried
 * or redirected to a D8 provider.
 */
object R8SessionLaw

object R8CapabilityFingerprint {
    private val domain = "AutoJs6:R8CompilerCapabilityFingerprint:v1\u0000".toByteArray(StandardCharsets.UTF_8)

    fun compute(value: R8CompilerCapabilities): R8Sha256 = hashStructured(domain) { out ->
        out.writeInt(value.compilerFamily.wireCode)
        val compilerVersion = value.compilerVersion.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(compilerVersion.size); out.write(compilerVersion)
        out.writeInt(value.profiles.size); value.profiles.forEach { out.writeInt(it.wireCode) }
        out.writeInt(value.minApi); out.writeInt(value.maxApi)
        out.writeInt(value.inputLayout.wireCode); out.writeInt(value.outputLayout.wireCode)
        out.writeInt(value.artifactRoles.size); value.artifactRoles.forEach { out.writeInt(it.wireCode) }
        out.writeInt(value.determinismClaim.wireCode); out.writeInt(value.runtimeLibraryModel.wireCode)
        out.writeInt(value.rulePolicyVersion); out.writeInt(value.canonicalizationPolicyVersion)
        out.writeInt(value.implicitRulePolicy.wireCode)
        value.limits.run {
            out.writeLong(maxProgramBytes); out.writeInt(maxClasspathCount); out.writeLong(maxClasspathJarBytes)
            out.writeLong(maxTotalClasspathBytes); out.writeInt(maxKeepRuleFiles); out.writeInt(maxConsumerRuleFiles)
            out.writeLong(maxRuleFileBytes); out.writeLong(maxTotalRuleBytes); out.writeInt(maxRuleLinesPerFile)
            out.writeInt(maxTotalRuleLines); out.writeInt(maxRuleLineBytes); out.writeLong(maxInputBundleBytes)
            out.writeInt(maxArchiveEntries); out.writeInt(maxTotalArchiveEntries); out.writeLong(maxUncompressedProgramBytes)
            out.writeLong(maxTotalUncompressedInputBytes); out.writeLong(maxTotalClassBytes); out.writeLong(maxSingleClassBytes)
            out.writeLong(maxOutputBundleBytes)
            out.writeLong(maxDexZipBytes); out.writeLong(maxMappingBytes); out.writeLong(maxSeedsBytes)
            out.writeLong(maxUsageBytes); out.writeLong(maxRetraceMetadataBytes); out.writeInt(maxDexEntries)
            out.writeInt(maxDiagnosticBytes); out.writeInt(maxConcurrentSessions)
            out.writeLong(defaultTimeoutMillis); out.writeLong(maxTimeoutMillis)
        }
        out.writeInt(value.runtimeLibraryIdentities.size)
        value.runtimeLibraryIdentities.forEach { out.writeLong(it.sizeBytes); out.write(it.contentSha256.toByteArray()) }
        out.write(value.runtimeLibraryFingerprint.toByteArray())
        out.writeBoolean(value.supportsMultiDex)
    }
}

@JvmSynthetic
internal fun <T> immutableList(values: Collection<T>, maximumSize: Int, label: String): List<T> =
    Collections.unmodifiableList(boundedSnapshot(values, maximumSize, label))

private const val maximumInputIdentityCount = 1 + R8CompilerContract.MAX_CLASSPATH_JARS +
    R8CompilerContract.MAX_KEEP_RULE_FILES + R8CompilerContract.MAX_CONSUMER_RULE_FILES
private const val maximumDiagnosticCount = 512
