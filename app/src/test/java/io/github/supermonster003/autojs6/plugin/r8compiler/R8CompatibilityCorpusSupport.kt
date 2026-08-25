package io.github.supermonster003.autojs6.plugin.r8compiler

import compat.corpus.kotlin.ReflectionEntry as KotlinReflectionEntry
import org.autojs.plugin.r8compiler.api.R8ArtifactBundleCodec
import org.autojs.plugin.r8compiler.api.R8ArtifactIdentity
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
import org.autojs.plugin.r8compiler.api.R8Result
import org.autojs.plugin.r8compiler.api.R8RuntimeLibraryModel
import org.autojs.plugin.r8compiler.api.R8Sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

enum class R8CorpusLanguage(
    val wireName: String,
    val packageName: String,
) {
    JAVA("JAVA", "compat.corpus.java"),
    KOTLIN("KOTLIN", "compat.corpus.kotlin"),
}

internal data class R8CompatibilityReceipt(
    val caseId: String,
    val language: R8CorpusLanguage,
    val minApi: Int,
    val compilerVersion: String,
    val programSha256: String,
    val ruleSha256: String,
    val inputBundleSha256: String,
    val outputBundleSha256: String,
    val artifactIdentities: List<R8ArtifactIdentity>,
    val dexEntries: List<String>,
    val observedClassDescriptors: List<String>,
)

/**
 * Real-R8 compatibility corpus support. The DEX observations are structural: executing the
 * optimized DEX on ART, linking JNI, and loading it from a device script remain later gates.
 */
internal object R8CompatibilityCorpusSupport {
    private const val ACCESS_NATIVE = 0x100
    private val requiredSimpleClasses = listOf(
        "ReflectionEntry",
        "ReflectiveTarget",
        "DynamicEntry",
        "DynamicTarget",
        "NativeBridge",
        "SerializableState",
        "ScriptApi",
    )

    fun execute(language: R8CorpusLanguage, minApi: Int): R8CompatibilityReceipt {
        require(minApi in 24..36)
        val root = Files.createTempDirectory("r8-compat-${language.wireName.lowercase()}-$minApi-").toFile()
        try {
            val (runtime, capabilities) = R8ProviderTestFixtures.capabilities()
            val program = when (language) {
                R8CorpusLanguage.JAVA -> compileJavaProgram(root)
                R8CorpusLanguage.KOTLIN -> packageKotlinProgram(root)
            }
            val classpath = when (language) {
                R8CorpusLanguage.JAVA -> emptyList()
                R8CorpusLanguage.KOTLIN -> kotlinClasspath()
            }
            verifyOriginalJvmControl(language, program, classpath)
            val rules = root.resolve("compatibility.pro").apply {
                writeText(rulesFor(language.packageName), Charsets.UTF_8)
            }
            val fixture = createRequest(root, capabilities, program, classpath, rules, minApi)
            val compilation = PrivateSessionWorkspace.createUnder(root.resolve("sessions")).use { workspace ->
                val materialized = fixture.bundle.inputStream().buffered().use { input ->
                    R8InputMaterializer.materialize(fixture.request, capabilities, input, workspace)
                }
                val artifact = R8CompilerEngine(runtime, sdkInt = { 26 }).compile(
                    fixture.request,
                    capabilities,
                    materialized,
                    workspace,
                    isActive = { true },
                    ensureActive = {},
                    beforePackaging = {},
                )
                val result = R8Result(
                    requestId = fixture.request.requestId,
                    compilerFamily = fixture.request.compilerFamily,
                    compilerVersion = capabilities.compilerVersion,
                    capabilityFingerprint = capabilities.capabilityFingerprint,
                    runtimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
                    inputSetFingerprint = fixture.request.inputSetFingerprint,
                    profile = fixture.request.profile,
                    minApi = fixture.request.minApi,
                    outputLayout = fixture.request.outputLayout,
                    outputBundleSizeBytes = artifact.summary.sizeBytes,
                    outputBundleSha256 = artifact.summary.contentSha256,
                    artifactIdentities = artifact.summary.identities,
                    determinismClaim = capabilities.determinismClaim,
                    elapsedMillis = 1L,
                    diagnostics = artifact.diagnostics,
                )
                val extracted = linkedMapOf<R8ArtifactRole, ByteArray>()
                artifact.file.inputStream().buffered().use { input ->
                    R8ArtifactBundleCodec.read(input, result, fixture.request, capabilities) { identity, entry ->
                        extracted[identity.role] = entry.readBytes()
                    }
                }
                Triple(
                    extracted.toMap(),
                    artifact.summary.identities,
                    artifact.summary.contentSha256.toHexString(),
                )
            }
            val (payloads, artifactIdentities, outputBundleSha256) = compilation

            check(payloads.keys == R8ArtifactRole.values().toSet()) {
                "Compatibility cell did not consume the exact five-artifact set"
            }
            val dexPayloads = extractDexPayloads(payloads.getValue(R8ArtifactRole.DEX_ZIP))
            val dexEntries = dexPayloads.keys.toList()
            check(dexEntries.isNotEmpty() && dexEntries.withIndex().all { (index, name) ->
                name == if (index == 0) "classes.dex" else "classes${index + 1}.dex"
            }) { "Compatibility cell emitted a non-canonical DEX topology" }
            val classes = linkedMapOf<String, DexClassObservation>()
            dexPayloads.values.forEach { dex ->
                parseDex(dex).forEach { (descriptor, observed) ->
                    check(classes.put(descriptor, observed) == null) {
                        "Compatibility class was defined in more than one DEX: $descriptor"
                    }
                }
            }
            verifyDexObservations(language, classes)
            verifyTextArtifacts(language, payloads)

            return R8CompatibilityReceipt(
                caseId = "${language.wireName.lowercase()}-minapi-$minApi",
                language = language,
                minApi = minApi,
                compilerVersion = capabilities.compilerVersion,
                programSha256 = R8Sha256.digest(program.readBytes()).toHexString(),
                ruleSha256 = R8Sha256.digest(rules.readBytes()).toHexString(),
                inputBundleSha256 = fixture.request.inputBundleSha256.toHexString(),
                outputBundleSha256 = outputBundleSha256,
                artifactIdentities = artifactIdentities,
                dexEntries = dexEntries,
                observedClassDescriptors = requiredSimpleClasses.map { descriptor(language, it) },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    fun writeReceipt(receipt: R8CompatibilityReceipt) {
        val reportDirectory = File(requireNotNull(System.getProperty("r8.compatibility.report.dir")))
        check(reportDirectory.mkdirs() || reportDirectory.isDirectory)
        val destination = reportDirectory.resolve("${receipt.caseId}.json")
        val temporary = reportDirectory.resolve(".${receipt.caseId}.${UUID.randomUUID()}.tmp")
        val artifacts = receipt.artifactIdentities.joinToString(",") { identity ->
            "{" +
                "\"role\":${json(identity.role.name)}," +
                "\"sizeBytes\":${identity.sizeBytes}," +
                "\"sha256\":${json(identity.contentSha256.toHexString())}" +
                "}"
        }
        val json = buildString {
            append('{')
            append("\"schemaVersion\":\"autojs6.r8.compatibility-case/v1\",")
            append("\"caseId\":").append(json(receipt.caseId)).append(',')
            append("\"language\":").append(json(receipt.language.wireName)).append(',')
            append("\"minApi\":").append(receipt.minApi).append(',')
            append("\"minApiIsCompilerParameter\":true,")
            append("\"compilerFamily\":\"R8\",")
            append("\"compilerVersion\":").append(json(receipt.compilerVersion)).append(',')
            append("\"profile\":\"FULL_RELEASE\",")
            append("\"fallbackPolicy\":\"NONE\",")
            append("\"programSha256\":").append(json(receipt.programSha256)).append(',')
            append("\"ruleSha256\":").append(json(receipt.ruleSha256)).append(',')
            append("\"inputBundleSha256\":").append(json(receipt.inputBundleSha256)).append(',')
            append("\"outputBundleSha256\":").append(json(receipt.outputBundleSha256)).append(',')
            append("\"artifacts\":[").append(artifacts).append("],")
            append("\"dexEntries\":").append(jsonArray(receipt.dexEntries)).append(',')
            append("\"observedClassDescriptors\":")
                .append(jsonArray(receipt.observedClassDescriptors)).append(',')
            append("\"observations\":{")
            append("\"reflectionClassAndMembersRetained\":true,")
            append("\"dynamicClassNameTargetRetained\":true,")
            append("\"jniClassAndNativeNamesRetained\":true,")
            append("\"serializationClassNameAndHooksRetained\":true,")
            append("\"autoJs6ScriptApiNamesRetained\":true,")
            append("\"unusedDecoyRemoved\":true,")
            append("\"originalJvmControlPassed\":true")
            append("},")
            append("\"claims\":{")
            append("\"realR8Executed\":true,")
            append("\"canonicalFiveArtifactBundleConsumed\":true,")
            append("\"postR8DexRuntimeExecuted\":false,")
            append("\"jniLinked\":false,")
            append("\"deviceVerified\":false")
            append("}")
            append('}')
            append('\n')
        }
        try {
            temporary.writeText(json, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private data class RequestFixture(
        val request: R8CompileRequest,
        val bundle: File,
    )

    private fun createRequest(
        root: File,
        capabilities: R8CompilerCapabilities,
        program: File,
        classpath: List<File>,
        rules: File,
        minApi: Int,
    ): RequestFixture {
        val programIdentity = identity(R8InputRole.PROGRAM_JAR, 0, program)
        val classpathIdentities = classpath.mapIndexed { index, file ->
            identity(R8InputRole.CLASSPATH_JAR, index, file)
        }
        val ruleIdentity = identity(R8InputRole.KEEP_RULES, 0, rules)
        val identities = listOf(programIdentity) + classpathIdentities + ruleIdentity
        val sources = buildList {
            add(R8InputSource(programIdentity) { program.inputStream().buffered() })
            classpathIdentities.zip(classpath).forEach { (classpathIdentity, file) ->
                add(R8InputSource(classpathIdentity) { file.inputStream().buffered() })
            }
            add(R8InputSource(ruleIdentity) { rules.inputStream().buffered() })
        }
        val bundle = root.resolve("input.bundle")
        val summary = bundle.outputStream().buffered().use { output ->
            R8InputBundleCodec.write(output, sources, capabilities)
        }
        val limits = capabilities.limits
        val request = R8CompileRequest(
            requestId = R8RequestId.fromUuid(UUID.randomUUID()),
            protocolVersion = R8CompilerContract.PROTOCOL_V1,
            compilerFamily = R8CompilerFamily.R8,
            compilerIntent = R8CompilerIntent.R8_EXPLICIT,
            fallbackPolicy = R8FallbackPolicy.NONE,
            profile = R8CompilerProfile.FULL_RELEASE,
            minApi = minApi,
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
            maxOutputBundleBytes = limits.maxOutputBundleBytes,
            diagnosticByteLimit = limits.maxDiagnosticBytes,
            timeoutMillis = limits.defaultTimeoutMillis,
        )
        return RequestFixture(request, bundle)
    }

    private fun identity(role: R8InputRole, ordinal: Int, file: File) = R8InputIdentity(
        role = role,
        ordinal = ordinal,
        ownerClasspathOrdinal = -1,
        sizeBytes = file.length(),
        contentSha256 = R8Sha256.digest(file.readBytes()),
        ownerClasspathSha256 = R8Sha256.ZERO,
    )

    private fun compileJavaProgram(root: File): File {
        val packageName = R8CorpusLanguage.JAVA.packageName
        val sourceRoot = root.resolve("java-source").apply { mkdirs() }
        val classes = root.resolve("java-classes").apply { mkdirs() }
        val sources = linkedMapOf(
            "ReflectionEntry.java" to """
                package $packageName;
                public final class ReflectionEntry {
                    public static String run() throws Exception {
                        Class<?> type = Class.forName("$packageName.ReflectiveTarget");
                        Object target = type.getDeclaredConstructor().newInstance();
                        return (String) type.getDeclaredMethod("message").invoke(target);
                    }
                }
            """,
            "ReflectiveTarget.java" to """
                package $packageName;
                public final class ReflectiveTarget {
                    public String message() { return "java-reflection-ok"; }
                }
            """,
            "DynamicEntry.java" to """
                package $packageName;
                public final class DynamicEntry {
                    public static String run(String simpleName) throws Exception {
                        Class<?> type = Class.forName("$packageName." + simpleName);
                        Object target = type.getDeclaredConstructor().newInstance();
                        return (String) type.getDeclaredMethod("value").invoke(target);
                    }
                }
            """,
            "DynamicTarget.java" to """
                package $packageName;
                public final class DynamicTarget {
                    public String value() { return "java-dynamic-ok"; }
                }
            """,
            "NativeBridge.java" to """
                package $packageName;
                public final class NativeBridge {
                    public native long nativeRoundTrip(long value);
                    public static native String nativeStatic(String value);
                }
            """,
            "SerializableState.java" to """
                package $packageName;
                import java.io.IOException;
                import java.io.ObjectInputStream;
                import java.io.ObjectOutputStream;
                import java.io.Serializable;
                public final class SerializableState implements Serializable {
                    private static final long serialVersionUID = 18364086432839756L;
                    private String label;
                    private int count;
                    public SerializableState(String label, int count) { this.label = label; this.count = count; }
                    public String getLabel() { return label; }
                    public int getCount() { return count; }
                    private void writeObject(ObjectOutputStream output) throws IOException { output.defaultWriteObject(); }
                    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException { input.defaultReadObject(); }
                    private Object readResolve() { return this; }
                }
            """,
            "ScriptApi.java" to """
                package $packageName;
                public final class ScriptApi {
                    public String greet(String name) { return "hello," + name; }
                    public Object echo(Object value) { return value; }
                    public static int apiVersion() { return 1; }
                }
            """,
            "RemovedDecoy.java" to """
                package $packageName;
                public final class RemovedDecoy {
                    public String marker() { return "remove-java-decoy"; }
                }
            """,
        ).map { (name, source) ->
            sourceRoot.resolve(name).apply { writeText(source.trimIndent() + "\n", Charsets.UTF_8) }
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val arguments = listOf("--release", "8", "-d", classes.path) + sources.map(File::getPath)
        val exit = compiler.run(null, null, null, *arguments.toTypedArray())
        check(exit == 0) { "Java compatibility fixture javac failed with exit $exit" }
        return zipDirectory(classes, root.resolve("java-program.jar"))
    }

    private fun packageKotlinProgram(root: File): File {
        val protectionDomain = requireNotNull(KotlinReflectionEntry::class.java.protectionDomain)
        val codeSource = File(requireNotNull(protectionDomain.codeSource).location.toURI())
        val prefix = "compat/corpus/kotlin/"
        val destination = root.resolve("kotlin-program.jar")
        ZipOutputStream(destination.outputStream().buffered()).use { output ->
            when {
                codeSource.isDirectory -> {
                    val packageRoot = codeSource.resolve(prefix.replace('/', File.separatorChar))
                    check(packageRoot.isDirectory) { "Kotlin compatibility fixture package is missing" }
                    Files.walk(packageRoot.toPath()).use { paths ->
                        paths.filter(Files::isRegularFile).sorted().forEach { path ->
                            val name = codeSource.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                            if (name.endsWith(".class")) writeZipEntry(output, name, Files.readAllBytes(path))
                        }
                    }
                }
                codeSource.isFile -> ZipFile(codeSource).use { input ->
                    input.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".class") }
                        .sortedBy { it.name }
                        .forEach { entry -> writeZipEntry(output, entry.name, input.getInputStream(entry).readBytes()) }
                }
                else -> error("Kotlin compatibility fixture code source is not readable")
            }
        }
        check(ZipFile(destination).use { zip -> zip.entries().asSequence().count() >= requiredSimpleClasses.size }) {
            "Kotlin compatibility program JAR is incomplete"
        }
        return destination
    }

    private fun kotlinClasspath(): List<File> {
        val classes = buildList {
            add(kotlin.Unit::class.java)
            add(kotlin.jvm.internal.Intrinsics::class.java)
            runCatching { Class.forName("org.jetbrains.annotations.NotNull") }.getOrNull()?.let(::add)
        }
        return classes.map { type ->
            val protectionDomain = requireNotNull(type.protectionDomain)
            File(requireNotNull(protectionDomain.codeSource).location.toURI()).also { file ->
                check(file.isFile && file.extension.equals("jar", ignoreCase = true)) {
                    "Kotlin compatibility classpath is not a bounded JAR: ${type.name}"
                }
            }
        }.distinctBy { it.canonicalFile }
    }

    private fun zipDirectory(directory: File, destination: File): File {
        ZipOutputStream(destination.outputStream().buffered()).use { output ->
            Files.walk(directory.toPath()).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    val name = directory.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    writeZipEntry(output, name, Files.readAllBytes(path))
                }
            }
        }
        return destination
    }

    private fun writeZipEntry(output: ZipOutputStream, name: String, bytes: ByteArray) {
        output.putNextEntry(ZipEntry(name).apply { time = 0L })
        output.write(bytes)
        output.closeEntry()
    }

    private fun rulesFor(packageName: String): String = """
        -keep class $packageName.ReflectionEntry { *; }
        -keep class $packageName.ReflectiveTarget { *; }
        -keep class $packageName.DynamicEntry { *; }
        -keep class $packageName.DynamicTarget { *; }
        -keep class $packageName.NativeBridge
        -keepclassmembers,includedescriptorclasses class * {
            native <methods>;
        }
        -keepnames class * implements java.io.Serializable
        -keepclassmembers class * implements java.io.Serializable {
            static final long serialVersionUID;
            private void writeObject(java.io.ObjectOutputStream);
            private void readObject(java.io.ObjectInputStream);
            java.lang.Object readResolve();
        }
        -keep class $packageName.SerializableState { *; }
        -keep class $packageName.ScriptApi { *; }
    """.trimIndent() + "\n"

    private fun verifyOriginalJvmControl(language: R8CorpusLanguage, program: File, classpath: List<File>) {
        val urls = (listOf(program) + classpath).map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getSystemClassLoader().parent).use { loader ->
            val prefix = language.packageName
            check(invokeStatic(loader, "$prefix.ReflectionEntry", "run") ==
                if (language == R8CorpusLanguage.JAVA) "java-reflection-ok" else "kotlin-reflection-ok")
            check(invokeStatic(loader, "$prefix.DynamicEntry", "run", "DynamicTarget") ==
                if (language == R8CorpusLanguage.JAVA) "java-dynamic-ok" else "kotlin-dynamic-ok")

            val nativeMethods = loader.loadClass("$prefix.NativeBridge").declaredMethods
                .filter { Modifier.isNative(it.modifiers) }
                .map { it.name }
                .toSet()
            check(nativeMethods.containsAll(setOf("nativeRoundTrip", "nativeStatic")))

            val serializable = loader.loadClass("$prefix.SerializableState")
            val original = serializable.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType!!)
                .newInstance("state", 7)
            val encoded = ByteArrayOutputStream().use { bytes ->
                ObjectOutputStream(bytes).use { it.writeObject(original) }
                bytes.toByteArray()
            }
            val restored = object : ObjectInputStream(ByteArrayInputStream(encoded)) {
                override fun resolveClass(descriptor: java.io.ObjectStreamClass): Class<*> =
                    runCatching { loader.loadClass(descriptor.name) }.getOrElse { super.resolveClass(descriptor) }
            }.use { it.readObject() }
            check(serializable.getDeclaredMethod("getLabel").invoke(restored) == "state")
            check(serializable.getDeclaredMethod("getCount").invoke(restored) == 7)

            val scriptApi = loader.loadClass("$prefix.ScriptApi")
            val scriptObject = scriptApi.getDeclaredConstructor().newInstance()
            check(scriptApi.getDeclaredMethod("greet", String::class.java).invoke(scriptObject, "autojs6") ==
                "hello,autojs6")
            check(scriptApi.getDeclaredMethod("echo", Object::class.java).invoke(scriptObject, "echo") == "echo")
            check(scriptApi.getDeclaredMethod("apiVersion").invoke(null) == 1)
        }
    }

    private fun invokeStatic(loader: ClassLoader, className: String, methodName: String, vararg values: Any): Any? {
        val type = loader.loadClass(className)
        val method = type.declaredMethods.single { method ->
            method.name == methodName && method.parameterCount == values.size
        }.apply { isAccessible = true }
        return method.invoke(null, *values)
    }

    private fun extractDexPayloads(zipBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val payloads = linkedMapOf<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory && payloads.put(entry.name, zip.readBytes()) == null)
            }
        }
        return payloads
    }

    private data class DexClassObservation(
        val fields: Set<String>,
        val methods: Map<String, List<Int>>,
    )

    private fun parseDex(bytes: ByteArray): Map<String, DexClassObservation> {
        check(bytes.size >= 0x70 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())))
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = data.getInt(0x38)
        val stringOffset = data.getInt(0x3c)
        val typeCount = data.getInt(0x40)
        val typeOffset = data.getInt(0x44)
        val fieldCount = data.getInt(0x50)
        val fieldOffset = data.getInt(0x54)
        val methodCount = data.getInt(0x58)
        val methodOffset = data.getInt(0x5c)
        val classCount = data.getInt(0x60)
        val classOffset = data.getInt(0x64)
        requireTable(bytes, stringOffset, stringCount, 4)
        requireTable(bytes, typeOffset, typeCount, 4)
        requireTable(bytes, fieldOffset, fieldCount, 8)
        requireTable(bytes, methodOffset, methodCount, 8)
        requireTable(bytes, classOffset, classCount, 32)

        val strings = List(stringCount) { index ->
            val offset = data.getInt(stringOffset + index * 4)
            readDexString(bytes, offset)
        }
        val types = IntArray(typeCount) { index -> data.getInt(typeOffset + index * 4) }
        val fieldNames = List(fieldCount) { index ->
            strings[data.getInt(fieldOffset + index * 8 + 4)]
        }
        val methodNames = List(methodCount) { index ->
            strings[data.getInt(methodOffset + index * 8 + 4)]
        }
        return buildMap {
            repeat(classCount) { classIndex ->
                val item = classOffset + classIndex * 32
                val classTypeIndex = data.getInt(item)
                val descriptor = strings[types[classTypeIndex]]
                val classDataOffset = data.getInt(item + 24)
                val fields = linkedSetOf<String>()
                val methods = linkedMapOf<String, MutableList<Int>>()
                if (classDataOffset != 0) {
                    val cursor = DexCursor(bytes, classDataOffset)
                    val staticFields = cursor.readUleb128()
                    val instanceFields = cursor.readUleb128()
                    val directMethods = cursor.readUleb128()
                    val virtualMethods = cursor.readUleb128()
                    readEncodedFields(cursor, staticFields, fieldNames, fields)
                    readEncodedFields(cursor, instanceFields, fieldNames, fields)
                    readEncodedMethods(cursor, directMethods, methodNames, methods)
                    readEncodedMethods(cursor, virtualMethods, methodNames, methods)
                }
                put(descriptor, DexClassObservation(fields, methods.mapValues { it.value.toList() }))
            }
        }
    }

    private fun readEncodedFields(
        cursor: DexCursor,
        count: Int,
        names: List<String>,
        output: MutableSet<String>,
    ) {
        var index = 0
        repeat(count) {
            index += cursor.readUleb128()
            check(index in names.indices)
            cursor.readUleb128()
            output += names[index]
        }
    }

    private fun readEncodedMethods(
        cursor: DexCursor,
        count: Int,
        names: List<String>,
        output: MutableMap<String, MutableList<Int>>,
    ) {
        var index = 0
        repeat(count) {
            index += cursor.readUleb128()
            check(index in names.indices)
            val access = cursor.readUleb128()
            cursor.readUleb128()
            output.getOrPut(names[index]) { arrayListOf() } += access
        }
    }

    private class DexCursor(private val bytes: ByteArray, private var offset: Int) {
        fun readUleb128(): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                check(offset in bytes.indices)
                val next = bytes[offset++].toInt() and 0xff
                value = value or ((next and 0x7f) shl shift)
                if ((next and 0x80) == 0) return value
                shift += 7
            }
            error("DEX ULEB128 exceeds five bytes")
        }
    }

    private fun readDexString(bytes: ByteArray, offset: Int): String {
        val cursor = DexCursor(bytes, offset)
        cursor.readUleb128()
        var dataOffset = offset
        while ((bytes[dataOffset].toInt() and 0x80) != 0) dataOffset++
        dataOffset++
        val end = (dataOffset until bytes.size).first { bytes[it].toInt() == 0 }
        return String(bytes, dataOffset, end - dataOffset, StandardCharsets.UTF_8)
    }

    private fun requireTable(bytes: ByteArray, offset: Int, count: Int, itemSize: Int) {
        check(offset >= 0 && count >= 0)
        val end = offset.toLong() + count.toLong() * itemSize
        check(end <= bytes.size.toLong())
    }

    private fun verifyDexObservations(
        language: R8CorpusLanguage,
        classes: Map<String, DexClassObservation>,
    ) {
        val required = requiredSimpleClasses.associateWith { simpleName ->
            classes[descriptor(language, simpleName)] ?: error("R8 removed compatibility class $simpleName")
        }
        check(descriptor(language, "RemovedDecoy") !in classes)
        check(required.getValue("ReflectiveTarget").methods.containsKey("message"))
        check(required.getValue("DynamicTarget").methods.containsKey("value"))
        val nativeMethods = required.getValue("NativeBridge").methods
        for (name in listOf("nativeRoundTrip", "nativeStatic")) {
            check(nativeMethods.getValue(name).any { flags -> flags and ACCESS_NATIVE != 0 }) {
                "JNI member was renamed or lost its native flag: $name"
            }
        }
        val serializable = required.getValue("SerializableState")
        check("serialVersionUID" in serializable.fields)
        check(serializable.methods.keys.containsAll(setOf("writeObject", "readObject", "readResolve")))
        val script = required.getValue("ScriptApi")
        check(script.methods.keys.containsAll(setOf("greet", "echo", "apiVersion")))
    }

    private fun verifyTextArtifacts(
        language: R8CorpusLanguage,
        payloads: Map<R8ArtifactRole, ByteArray>,
    ) {
        val mapping = payloads.getValue(R8ArtifactRole.MAPPING_TEXT).toString(Charsets.UTF_8)
        val seeds = payloads.getValue(R8ArtifactRole.SEEDS_TEXT).toString(Charsets.UTF_8)
        val usage = payloads.getValue(R8ArtifactRole.USAGE_TEXT).toString(Charsets.UTF_8)
        requiredSimpleClasses.forEach { simpleName ->
            val binaryName = "${language.packageName}.$simpleName"
            check(mapping.contains(binaryName)) { "Mapping omits compatibility class $binaryName" }
        }
        for (simpleName in listOf("ReflectiveTarget", "DynamicTarget", "NativeBridge", "SerializableState", "ScriptApi")) {
            check(seeds.contains("${language.packageName}.$simpleName")) {
                "Seeds omit compatibility class ${language.packageName}.$simpleName"
            }
        }
        check(usage.contains("${language.packageName}.RemovedDecoy")) {
            "Usage report does not record the removed compatibility decoy"
        }
    }

    private fun descriptor(language: R8CorpusLanguage, simpleName: String): String =
        "L${language.packageName.replace('.', '/')}/$simpleName;"

    private fun jsonArray(values: List<String>): String = values.joinToString(",", "[", "]", transform = ::json)

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
