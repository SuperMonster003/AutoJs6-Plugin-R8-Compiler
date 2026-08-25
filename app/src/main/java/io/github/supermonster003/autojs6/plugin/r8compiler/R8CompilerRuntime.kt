package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import android.os.Build
import com.android.tools.r8.Version
import org.autojs.plugin.r8compiler.api.R8ArtifactRole
import org.autojs.plugin.r8compiler.api.R8CapabilityFingerprint
import org.autojs.plugin.r8compiler.api.R8CompilerCapabilities
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerFamily
import org.autojs.plugin.r8compiler.api.R8CompilerInfo
import org.autojs.plugin.r8compiler.api.R8CompilerProfile
import org.autojs.plugin.r8compiler.api.R8DeterminismClaim
import org.autojs.plugin.r8compiler.api.R8ImplicitRulePolicy
import org.autojs.plugin.r8compiler.api.R8InputLayout
import org.autojs.plugin.r8compiler.api.R8OutputLayout
import org.autojs.plugin.r8compiler.api.R8ResourceLimits
import org.autojs.plugin.r8compiler.api.R8RuntimeLibraryModel
import org.autojs.plugin.r8compiler.api.R8Sha256

internal object R8CompilerRuntime {
    const val HOST_PACKAGE_NAME = "org.autojs.autojs6"
    const val APPLICATION_ID = "io.github.supermonster003.autojs6.plugin.r8compiler"
    const val PLUGIN_ID = "r8-compiler"
    const val PLUGIN_VARIANT = "r8"
    const val PROVIDER_ID = R8CompilerContract.PROVIDER_ID
    const val REQUIRED_HOST_VERSION = 5_270L

    val compilerVersion: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val pinned = BuildConfig.R8_COMPILER_VERSION
        val parts = pinned.split('.').map(String::toInt)
        val actual = Version.getVersionString()
        check(
            parts.size == 3 &&
                Version.getMajorVersion() == parts[0] &&
                Version.getMinorVersion() == parts[1] &&
                Version.getPatchVersion() == parts[2] &&
                (actual == pinned || actual.startsWith("$pinned (build "))
        ) {
            "Packaged R8 version does not match the provider build identity"
        }
        pinned
    }

    private val limits = R8ResourceLimits(
        maxProgramBytes = 64L * 1024 * 1024,
        maxClasspathCount = 16,
        maxClasspathJarBytes = 64L * 1024 * 1024,
        maxTotalClasspathBytes = 128L * 1024 * 1024,
        maxKeepRuleFiles = 16,
        maxConsumerRuleFiles = 32,
        maxRuleFileBytes = 256L * 1024,
        maxTotalRuleBytes = 2L * 1024 * 1024,
        maxRuleLinesPerFile = 4_096,
        maxTotalRuleLines = 16_384,
        maxRuleLineBytes = 16 * 1024,
        maxInputBundleBytes = 196L * 1024 * 1024,
        maxArchiveEntries = 20_000,
        maxTotalArchiveEntries = 60_000,
        maxUncompressedProgramBytes = 256L * 1024 * 1024,
        maxTotalUncompressedInputBytes = 512L * 1024 * 1024,
        maxTotalClassBytes = 256L * 1024 * 1024,
        maxSingleClassBytes = 8L * 1024 * 1024,
        maxOutputBundleBytes = 128L * 1024 * 1024,
        maxDexZipBytes = 96L * 1024 * 1024,
        maxMappingBytes = 16L * 1024 * 1024,
        maxSeedsBytes = 8L * 1024 * 1024,
        maxUsageBytes = 8L * 1024 * 1024,
        maxRetraceMetadataBytes = 256L * 1024,
        maxDexEntries = 64,
        maxDiagnosticBytes = 64 * 1024,
        maxConcurrentSessions = 1,
        defaultTimeoutMillis = 120_000L,
        maxTimeoutMillis = 300_000L,
    )

    fun info(context: Context): R8CompilerInfo {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return R8CompilerInfo(
            protocolMin = R8CompilerContract.PROTOCOL_V1,
            protocolMax = R8CompilerContract.PROTOCOL_V1,
            providerId = PROVIDER_ID,
            providerVersionName = packageInfo.versionName.orEmpty(),
            providerVersionCode = versionCode,
            minHostVersionCode = REQUIRED_HOST_VERSION,
        )
    }

    fun capabilities(runtimeLibraries: RuntimeLibrarySet): R8CompilerCapabilities {
        fun create(fingerprint: R8Sha256) = R8CompilerCapabilities(
            compilerFamily = R8CompilerFamily.R8,
            compilerVersion = compilerVersion,
            profiles = listOf(R8CompilerProfile.FULL_RELEASE),
            minApi = R8CompilerContract.MIN_SUPPORTED_API,
            maxApi = R8CompilerContract.MAX_SUPPORTED_API,
            inputLayout = R8InputLayout.PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1,
            outputLayout = R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1,
            artifactRoles = R8ArtifactRole.values().toList(),
            determinismClaim = R8DeterminismClaim.NOT_CLAIMED,
            runtimeLibraryModel = R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1,
            rulePolicyVersion = R8CompilerContract.RULE_POLICY_VERSION,
            canonicalizationPolicyVersion = R8CompilerContract.CANONICALIZATION_POLICY_VERSION,
            implicitRulePolicy = R8ImplicitRulePolicy.NONE,
            limits = limits,
            runtimeLibraryIdentities = runtimeLibraries.identities,
            runtimeLibraryFingerprint = runtimeLibraries.fingerprint,
            capabilityFingerprint = fingerprint,
            supportsMultiDex = true,
        )

        val provisional = create(R8Sha256.ZERO)
        return create(R8CapabilityFingerprint.compute(provisional))
    }
}
