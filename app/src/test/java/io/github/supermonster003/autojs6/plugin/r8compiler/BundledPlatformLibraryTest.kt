package io.github.supermonster003.autojs6.plugin.r8compiler

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest

class BundledPlatformLibraryTest {
    @Test
    fun materializationIsPinnedAtomicAndSelfHealing() {
        val root = Files.createTempDirectory("r8-platform-library-").toFile()
        try {
            val bytes = "fixed-platform-library".toByteArray()
            val digest = bytes.sha256()
            var opens = 0
            val source = {
                opens += 1
                ByteArrayInputStream(bytes)
            }

            val first = BundledPlatformLibrary.materialize(root, bytes.size.toLong(), digest, source)
            assertArrayEquals(bytes, first.readBytes())
            val second = BundledPlatformLibrary.materialize(root, bytes.size.toLong(), digest, source)
            assertEquals(first.canonicalFile, second.canonicalFile)
            assertEquals(1, opens)

            assertTrue(first.setWritable(true, true))
            first.writeText("corrupt")
            val repaired = BundledPlatformLibrary.materialize(root, bytes.size.toLong(), digest, source)
            assertArrayEquals(bytes, repaired.readBytes())
            assertEquals(2, opens)
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun wrongAssetFailsClosedWithoutPublishingStagingBytes() {
        val root = Files.createTempDirectory("r8-platform-library-failure-").toFile()
        try {
            val expected = "expected".toByteArray()
            val wrong = "wrong!!!".toByteArray()
            val failure = runCatching {
                BundledPlatformLibrary.materialize(
                    root,
                    expected.size.toLong(),
                    expected.sha256(),
                ) { ByteArrayInputStream(wrong) }
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deviceIdentitiesIncludeTheCompilerStubButR8ReceivesOnlyTheStub() {
        val root = Files.createTempDirectory("r8-platform-library-set-").toFile()
        try {
            val device = root.resolve("core-oj.jar").apply { writeText("resource-only boot shell") }
            val compiler = root.resolve("android.jar").apply { writeText("class-file platform stubs") }
            val set = RuntimeLibrarySet.fromRuntimeAndCompilerFiles(listOf(device), listOf(compiler))
            val compilerOnly = RuntimeLibrarySet.fromFiles(listOf(compiler))

            assertEquals(listOf(compiler.canonicalFile), set.files)
            assertEquals(2, set.identities.size)
            assertNotEquals(compilerOnly.fingerprint, set.fingerprint)
            assertFalse(set.files.contains(device.canonicalFile))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
