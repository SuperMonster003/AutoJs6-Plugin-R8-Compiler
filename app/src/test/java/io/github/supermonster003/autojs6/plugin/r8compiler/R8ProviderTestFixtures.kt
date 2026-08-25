package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ArtifactRole
import org.autojs.plugin.r8compiler.api.R8CompileRequest
import org.autojs.plugin.r8compiler.api.R8CompilerCapabilities
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerFamily
import org.autojs.plugin.r8compiler.api.R8CompilerIntent
import org.autojs.plugin.r8compiler.api.R8CompilerProfile
import org.autojs.plugin.r8compiler.api.R8FallbackPolicy
import org.autojs.plugin.r8compiler.api.R8InputBundleCodec
import org.autojs.plugin.r8compiler.api.R8InputIdentity
import org.autojs.plugin.r8compiler.api.R8InputLayout
import org.autojs.plugin.r8compiler.api.R8InputRole
import org.autojs.plugin.r8compiler.api.R8InputSetFingerprint
import org.autojs.plugin.r8compiler.api.R8InputSource
import org.autojs.plugin.r8compiler.api.R8OutputLayout
import org.autojs.plugin.r8compiler.api.R8RequestId
import org.autojs.plugin.r8compiler.api.R8RequestedArtifact
import org.autojs.plugin.r8compiler.api.R8RuntimeLibraryModel
import org.autojs.plugin.r8compiler.api.R8Sha256
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

internal data class R8RequestFixture(
    val request: R8CompileRequest,
    val bundle: File,
    val program: File,
    val rules: File,
)

internal object R8ProviderTestFixtures {
    fun capabilities(): Pair<RuntimeLibrarySet, R8CompilerCapabilities> {
        val androidJar = File(requireNotNull(System.getProperty("r8.android.jar")))
        require(androidJar.isFile) { "Android 36 library was not supplied to the test JVM" }
        val runtime = RuntimeLibrarySet.fromFiles(listOf(androidJar))
        return runtime to R8CompilerRuntime.capabilities(runtime)
    }

    fun requestFixture(
        root: File,
        capabilities: R8CompilerCapabilities,
        ruleText: String = "-keep class sample.Entry { public static int value(); }\n",
        maxOutputBundleBytes: Long = capabilities.limits.maxOutputBundleBytes,
    ): R8RequestFixture {
        val program = compileProgram(root)
        val rules = root.resolve("keep.pro").apply { writeText(ruleText, Charsets.UTF_8) }
        val programIdentity = identity(R8InputRole.PROGRAM_JAR, 0, program)
        val ruleIdentity = identity(R8InputRole.KEEP_RULES, 0, rules)
        val identities = listOf(programIdentity, ruleIdentity)
        val bundle = root.resolve("input.bundle")
        val summary = bundle.outputStream().buffered().use { output ->
            R8InputBundleCodec.write(
                output,
                listOf(
                    R8InputSource(programIdentity) { program.inputStream() },
                    R8InputSource(ruleIdentity) { rules.inputStream() },
                ),
                capabilities,
            )
        }
        val limits = capabilities.limits
        val request = R8CompileRequest(
            requestId = R8RequestId.fromUuid(UUID.randomUUID()),
            protocolVersion = R8CompilerContract.PROTOCOL_V1,
            compilerFamily = R8CompilerFamily.R8,
            compilerIntent = R8CompilerIntent.R8_EXPLICIT,
            fallbackPolicy = R8FallbackPolicy.NONE,
            profile = R8CompilerProfile.FULL_RELEASE,
            minApi = 24,
            inputLayout = R8InputLayout.PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1,
            inputIdentities = identities,
            inputSetFingerprint = R8InputSetFingerprint.compute(identities),
            inputBundleSizeBytes = summary.sizeBytes,
            inputBundleSha256 = summary.contentSha256,
            runtimeLibraryModel = R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1,
            expectedRuntimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
            expectedCapabilityFingerprint = capabilities.capabilityFingerprint,
            canonicalizationPolicyVersion = R8CompilerContract.CANONICALIZATION_POLICY_VERSION,
            rulePolicyVersion = R8CompilerContract.RULE_POLICY_VERSION,
            outputLayout = R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1,
            requestedArtifacts = listOf(
                R8RequestedArtifact(R8ArtifactRole.DEX_ZIP, limits.maxDexZipBytes),
                R8RequestedArtifact(R8ArtifactRole.MAPPING_TEXT, limits.maxMappingBytes),
                R8RequestedArtifact(R8ArtifactRole.SEEDS_TEXT, limits.maxSeedsBytes),
                R8RequestedArtifact(R8ArtifactRole.USAGE_TEXT, limits.maxUsageBytes),
                R8RequestedArtifact(R8ArtifactRole.RETRACE_METADATA, limits.maxRetraceMetadataBytes),
            ),
            maxOutputBundleBytes = maxOutputBundleBytes,
            diagnosticByteLimit = limits.maxDiagnosticBytes,
            timeoutMillis = limits.defaultTimeoutMillis,
        )
        return R8RequestFixture(request, bundle, program, rules)
    }

    private fun compileProgram(root: File): File {
        val sourceRoot = root.resolve("source").apply { mkdirs() }
        val classes = root.resolve("classes").apply { mkdirs() }
        val source = sourceRoot.resolve("Entry.java").apply {
            writeText(
                """
                package sample;
                public class Entry {
                    public static int value() { return 42; }
                }
                class Removed {
                    static String unused() { return "unused"; }
                }
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val exit = compiler.run(null, null, null, "--release", "8", "-d", classes.path, source.path)
        check(exit == 0) { "Fixture javac failed with exit $exit" }
        val jar = root.resolve("program.jar")
        ZipOutputStream(jar.outputStream().buffered()).use { zip ->
            Files.walk(classes.toPath()).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    val name = classes.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    Files.newInputStream(path).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return jar
    }

    private fun identity(role: R8InputRole, ordinal: Int, file: File) = R8InputIdentity(
        role = role,
        ordinal = ordinal,
        ownerClasspathOrdinal = -1,
        sizeBytes = file.length(),
        contentSha256 = R8Sha256.digest(file.readBytes()),
        ownerClasspathSha256 = R8Sha256.ZERO,
    )
}
