package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ArtifactRole
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerFamily
import org.autojs.plugin.r8compiler.api.R8CompilerProfile
import org.autojs.plugin.r8compiler.api.R8DeterminismClaim
import org.autojs.plugin.r8compiler.api.R8ImplicitRulePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class R8ProviderBoundaryTest {
    @Test
    fun runtimeIdentityAndCapabilitiesRemainIndependentAndR8Only() {
        val (_, capabilities) = R8ProviderTestFixtures.capabilities()
        assertEquals("io.github.supermonster003.autojs6.plugin.r8compiler", R8CompilerRuntime.APPLICATION_ID)
        assertEquals("r8-compiler", R8CompilerRuntime.PLUGIN_ID)
        assertEquals("r8", R8CompilerRuntime.PLUGIN_VARIANT)
        assertEquals(R8CompilerContract.PROVIDER_ID, R8CompilerRuntime.PROVIDER_ID)
        assertEquals(R8CompilerFamily.R8, capabilities.compilerFamily)
        assertEquals(listOf(R8CompilerProfile.FULL_RELEASE), capabilities.profiles)
        assertEquals(R8ArtifactRole.values().toList(), capabilities.artifactRoles)
        assertEquals(R8ImplicitRulePolicy.NONE, capabilities.implicitRulePolicy)
        assertEquals(R8DeterminismClaim.NOT_CLAIMED, capabilities.determinismClaim)
        assertEquals(1, capabilities.limits.maxConcurrentSessions)
        assertTrue(capabilities.capabilityFingerprint != org.autojs.plugin.r8compiler.api.R8Sha256.ZERO)
    }

    @Test
    fun manifestExposesOnlyProtectedCompilerAndMetadataServices() {
        val root = File(requireNotNull(System.getProperty("r8.provider.root")))
        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(root.resolve("app/src/main/AndroidManifest.xml"))
        val nodes = document.getElementsByTagName("service")
        assertEquals(2, nodes.length)
        val services = (0 until nodes.length).map { nodes.item(it) as Element }
            .associateBy { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
        assertEquals(setOf(".R8CompilerService", ".R8PluginInfoService"), services.keys)
        services.values.forEach { service ->
            assertEquals("true", service.getAttributeNS(ANDROID_NAMESPACE, "exported"))
            assertEquals(R8CompilerContract.PLUGIN_PERMISSION, service.getAttributeNS(ANDROID_NAMESPACE, "permission"))
        }
        val compiler = services.getValue(".R8CompilerService")
        assertEquals(":r8", compiler.getAttributeNS(ANDROID_NAMESPACE, "process"))
        fun actionNames(service: Element): Set<String> {
            val actions = service.getElementsByTagName("action")
            return (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ANDROID_NAMESPACE, "name") }.toSet()
        }
        assertEquals(setOf(R8CompilerContract.SERVICE_ACTION), actionNames(compiler))
        assertEquals(setOf("org.autojs.plugin.INFO"), actionNames(services.getValue(".R8PluginInfoService")))
        assertFalse(manifest.contains("DEX_COMPILER"))
    }

    @Test
    fun providerConsumesTheFrozenContractArtifactsByExactBytes() {
        val root = File(requireNotNull(System.getProperty("r8.provider.root")))
        val release = root.resolve("plugin-api/r8-compiler-api/releases/0.2.0")
        assertEquals(
            "1d97a5b44b2c20e85aa12b263fca604a32d6d89275d47a19076861cd20c29a36",
            sha256(release.resolve("protocol-wire-api-0.1.0.aar")),
        )
        assertEquals(
            "ea1416913db1a93328c2fc8017f36a790e9e2ca04b8ad1c234d763da2d367424",
            sha256(release.resolve("r8-compiler-api-0.2.0.aar")),
        )
        val build = root.resolve("app/build.gradle.kts").readText()
        assertTrue(build.contains("protocol-wire-api-0.1.0.aar"))
        assertTrue(build.contains("r8-compiler-api-0.2.0.aar"))
        assertFalse(build.contains("project(\":plugin-api:r8-compiler-api\")"))
    }

    @Test
    fun pinnedR8IsCoreLibraryDesugaredForTheApi24ProviderFloor() {
        val root = File(requireNotNull(System.getProperty("r8.provider.root")))
        val build = root.resolve("app/build.gradle.kts").readText()
        val catalog = root.resolve("gradle/libs.versions.toml").readText()
        val descriptorOwner = root.resolve(
            "app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/service/" +
                "OwnedParcelFileDescriptors.kt",
        ).readText()
        assertEquals("24", System.getProperty("r8.provider.minSdk"))
        assertTrue(build.contains("isCoreLibraryDesugaringEnabled = true"))
        assertTrue(build.contains("coreLibraryDesugaring(libs.desugar)"))
        assertTrue(catalog.contains("desugar = \"2.1.5\""))
        assertTrue(catalog.contains("com.android.tools:desugar_jdk_libs_nio"))
        assertFalse(descriptorOwner.contains("/proc/self/fdinfo"))
        assertTrue(descriptorOwner.contains("Os.read(descriptor.fileDescriptor, EMPTY_BYTES, 0, 0)"))
        assertTrue(descriptorOwner.contains("Os.write(descriptor.fileDescriptor, EMPTY_BYTES, 0, 0)"))
        assertTrue(descriptorOwner.contains("error.errno == OsConstants.EBADF"))
        assertTrue(descriptorOwner.contains("Os.fstat(input.fileDescriptor)"))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
