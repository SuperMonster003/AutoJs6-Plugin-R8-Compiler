package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8RuntimeLibraryFingerprint
import org.autojs.plugin.r8compiler.api.R8RuntimeLibraryIdentity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal class RuntimeLibrarySet private constructor(
    val files: List<File>,
    val identities: List<R8RuntimeLibraryIdentity>,
) {
    val fingerprint = R8RuntimeLibraryFingerprint.compute(identities)

    companion object {
        fun discover(context: Context): RuntimeLibrarySet {
            val pathGroups = listOfNotNull(
                System.getProperty("java.boot.class.path"),
                System.getenv("BOOTCLASSPATH"),
                System.getenv("DEX2OATBOOTCLASSPATH"),
            )
            val deviceRuntimeFiles = readableFiles(pathGroups)
            require(deviceRuntimeFiles.isNotEmpty()) {
                "No readable device runtime boot-classpath files were found"
            }
            val compilerLibrary = BundledPlatformLibrary.materialize(context)
            return fromIdentityAndCompilerFiles(
                identityFiles = deviceRuntimeFiles + compilerLibrary,
                compilerFiles = listOf(compilerLibrary),
            )
        }

        internal fun fromPathGroups(pathGroups: Collection<String>): RuntimeLibrarySet {
            return fromFiles(readableFiles(pathGroups))
        }

        private fun readableFiles(pathGroups: Collection<String>): List<File> {
            val seen = HashSet<String>()
            return pathGroups.asSequence()
                .flatMap { it.split(File.pathSeparatorChar).asSequence() }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::File)
                .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
                .filter { it.isFile && it.canRead() }
                .filter { seen.add(it.path) }
                .toList()
        }

        internal fun fromFiles(files: Collection<File>): RuntimeLibrarySet {
            return fromIdentityAndCompilerFiles(files, files)
        }

        internal fun fromRuntimeAndCompilerFiles(
            runtimeFiles: Collection<File>,
            compilerFiles: Collection<File>,
        ): RuntimeLibrarySet {
            require(runtimeFiles.isNotEmpty()) { "No readable device runtime boot-classpath files were found" }
            require(compilerFiles.isNotEmpty()) { "No compiler platform library was supplied" }
            val runtimePaths = runtimeFiles.map { it.canonicalFile.path }.toSet()
            val compilerPaths = compilerFiles.map { it.canonicalFile.path }.toSet()
            require(runtimePaths.intersect(compilerPaths).isEmpty()) {
                "Compiler platform libraries must be distinct from device runtime files"
            }
            return fromIdentityAndCompilerFiles(runtimeFiles + compilerFiles, compilerFiles)
        }

        private fun fromIdentityAndCompilerFiles(
            identityFiles: Collection<File>,
            compilerFiles: Collection<File>,
        ): RuntimeLibrarySet {
            require(identityFiles.isNotEmpty()) { "No runtime library identities were supplied" }
            require(identityFiles.size <= R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES) {
                "Device runtime boot classpath has too many entries"
            }
            val canonicalIdentityFiles = identityFiles.map { file ->
                file.canonicalFile.also {
                    require(it.isFile && it.canRead()) { "Runtime library is not a readable file" }
                }
            }
            require(canonicalIdentityFiles.map(File::getPath).toSet().size == canonicalIdentityFiles.size) {
                "Device runtime boot classpath contains duplicate files"
            }
            val identityPaths = canonicalIdentityFiles.map(File::getPath).toSet()
            val canonicalCompilerFiles = compilerFiles.map { file ->
                file.canonicalFile.also {
                    require(it.isFile && it.canRead()) { "Compiler platform library is not a readable file" }
                    require(it.path in identityPaths) { "Compiler platform library has no runtime identity" }
                }
            }
            require(canonicalCompilerFiles.isNotEmpty()) { "No compiler platform library was supplied" }
            require(canonicalCompilerFiles.map(File::getPath).toSet().size == canonicalCompilerFiles.size) {
                "Compiler platform library list contains duplicate files"
            }
            return RuntimeLibrarySet(
                canonicalCompilerFiles,
                canonicalIdentityFiles.map(::readIdentity),
            )
        }

        private fun readIdentity(file: File): R8RuntimeLibraryIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileInputStream(file).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    size = Math.addExact(size, read.toLong())
                    digest.update(buffer, 0, read)
                }
            }
            require(size > 0L) { "Runtime library is empty" }
            return R8RuntimeLibraryIdentity(size, org.autojs.plugin.r8compiler.api.R8Sha256.fromBytes(digest.digest()))
        }
    }
}
