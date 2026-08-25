import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

abstract class PrepareR8PlatformLibraryAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Input
    abstract val expectedSizeBytes: Property<Long>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val source = sourceFile.get().asFile
        check(source.isFile && source.length() == expectedSizeBytes.get()) {
            "Pinned Android 36 platform library size does not match the installed SDK"
        }
        check(sha256(source) == expectedSha256.get()) {
            "Pinned Android 36 platform library digest does not match the installed SDK"
        }
        val destination = outputDirectory.get().asFile.resolve("r8-library/android-36.jar")
        check(destination.parentFile.mkdirs() || destination.parentFile.isDirectory)
        val temporary = destination.parentFile.resolve(".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
            check(destination.length() == expectedSizeBytes.get() && sha256(destination) == expectedSha256.get()) {
                "Generated Android 36 platform library asset failed verification"
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

plugins {
    id("com.android.application")
}

val pinnedR8Version = libs.versions.r8.get()
val pinnedAndroidPlatformLibrarySha256 = "d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a"
val pinnedAndroidPlatformLibrarySize = 27_768_026L

android {
    namespace = "io.github.supermonster003.autojs6.plugin.r8compiler"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.supermonster003.autojs6.plugin.r8compiler"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-provider-dev"

        buildConfigField("String", "R8_COMPILER_VERSION", "\"$pinnedR8Version\"")
        buildConfigField(
            "String",
            "R8_PLATFORM_LIBRARY_SHA256",
            "\"$pinnedAndroidPlatformLibrarySha256\"",
        )
        buildConfigField("long", "R8_PLATFORM_LIBRARY_SIZE_BYTES", "${pinnedAndroidPlatformLibrarySize}L")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        // G2 pins the provider engine independently from the Android build toolchain.
        disable += "GradleDependency"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
        )
    }
}

val generatedR8PlatformAssets = layout.buildDirectory.dir("generated/r8-platform-assets")
val androidPlatformLibrary = androidComponents.sdkComponents.sdkDirectory.map { sdkDirectory ->
    sdkDirectory.file("platforms/android-36/android.jar")
}
val prepareR8PlatformLibraryAsset = tasks.register<PrepareR8PlatformLibraryAsset>(
    "prepareR8PlatformLibraryAsset",
) {
    sourceFile.set(androidPlatformLibrary)
    expectedSha256.set(pinnedAndroidPlatformLibrarySha256)
    expectedSizeBytes.set(pinnedAndroidPlatformLibrarySize)
    outputDirectory.set(generatedR8PlatformAssets)
}

androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        prepareR8PlatformLibraryAsset,
        PrepareR8PlatformLibraryAsset::outputDirectory,
    )
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(
        files(
            "../plugin-api/r8-compiler-api/releases/0.1.0/protocol-wire-api-0.1.0.aar",
            "../plugin-api/r8-compiler-api/releases/0.1.0/r8-compiler-api-0.1.0.aar",
        ),
    )
    implementation(libs.r8)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("r8.provider.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty(
        "r8.compatibility.report.dir",
        rootProject.layout.buildDirectory.dir("reports/r42-g4/corpus-cases").get().asFile.absolutePath,
    )
    val androidHome = System.getenv("ANDROID_HOME")
    if (!androidHome.isNullOrBlank()) {
        systemProperty("r8.android.jar", file("$androidHome/platforms/android-36/android.jar").absolutePath)
    }
}
