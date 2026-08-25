package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Materializes the byte-pinned SDK class stubs that R8 uses instead of stripped boot JAR shells. */
internal object BundledPlatformLibrary {
    private const val ASSET_PATH = "r8-library/android-36.jar"
    private const val DIRECTORY_NAME = "r8-platform-library-v1"
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun materialize(context: Context): File = materialize(
        directory = File(context.noBackupFilesDir, DIRECTORY_NAME),
        expectedSizeBytes = BuildConfig.R8_PLATFORM_LIBRARY_SIZE_BYTES,
        expectedSha256 = BuildConfig.R8_PLATFORM_LIBRARY_SHA256,
        source = { context.assets.open(ASSET_PATH, AssetManager.ACCESS_STREAMING) },
    )

    @Synchronized
    internal fun materialize(
        directory: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        source: () -> InputStream,
    ): File {
        require(expectedSizeBytes > 0L) { "Pinned platform library size is invalid" }
        require(SHA256.matches(expectedSha256)) { "Pinned platform library digest is invalid" }
        val canonicalDirectory = directory.canonicalFile
        require(canonicalDirectory.mkdirs() || canonicalDirectory.isDirectory) {
            "Unable to create the private platform-library directory"
        }
        val destination = File(canonicalDirectory, "android-36-$expectedSha256.jar")
        if (matches(destination, expectedSizeBytes, expectedSha256)) return destination

        val staging = File(canonicalDirectory, ".android-36-${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source().buffered().use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        size = Math.addExact(size, read.toLong())
                        require(size <= expectedSizeBytes) { "Bundled platform library exceeds its pinned size" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            require(size == expectedSizeBytes) { "Bundled platform library size does not match its build identity" }
            val actualSha256 = digest.digest().toHexString()
            require(actualSha256 == expectedSha256) {
                "Bundled platform library digest does not match its build identity"
            }
            try {
                Files.move(
                    staging.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(matches(destination, expectedSizeBytes, expectedSha256)) {
                "Materialized platform library failed post-write verification"
            }
            destination.setWritable(false, false)
            destination.setReadable(false, false)
            check(destination.setReadable(true, true)) { "Unable to restrict the platform library to its owner" }
            return destination
        } finally {
            Files.deleteIfExists(staging.toPath())
        }
    }

    private fun matches(file: File, expectedSizeBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || !file.canRead() || file.length() != expectedSizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString() == expectedSha256
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
