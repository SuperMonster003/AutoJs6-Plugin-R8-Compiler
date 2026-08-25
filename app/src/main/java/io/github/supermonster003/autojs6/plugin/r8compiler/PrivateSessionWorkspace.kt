package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID

internal class PrivateSessionWorkspace private constructor(
    private val root: File,
) : Closeable {
    private val classpathDirectory = File(root, "classpath")
    private val keepRulesDirectory = File(root, "keep-rules")
    private val consumerRulesDirectory = File(root, "consumer-rules")
    val r8OutputDirectory = File(root, "r8-output")
    val programJar = File(root, "program.jar")
    val dexZip = File(root, "dex.zip")
    val mapping = File(root, "mapping.txt")
    val seeds = File(root, "seeds.txt")
    val usage = File(root, "usage.txt")
    val providerControlRules = File(root, "provider-output.pro")
    val retraceMetadata = File(root, "retrace-metadata.bin")
    val artifactBundle = File(root, "artifact.bundle")
    val artifactBundleStaging = File(root, "artifact.bundle.staging")

    init {
        val directories = listOf(
            classpathDirectory,
            keepRulesDirectory,
            consumerRulesDirectory,
            r8OutputDirectory,
        )
        if (directories.any { !it.mkdir() }) {
            close()
            throw IOException("Failed to create the private R8 workspace directories")
        }
    }

    fun classpathJar(ordinal: Int): File = boundedFile(
        classpathDirectory,
        "classpath-${ordinal.toString().padStart(2, '0')}.jar",
        ordinal,
        R8CompilerContract.MAX_CLASSPATH_JARS,
    )

    fun keepRules(ordinal: Int): File = boundedFile(
        keepRulesDirectory,
        "keep-${ordinal.toString().padStart(2, '0')}.pro",
        ordinal,
        R8CompilerContract.MAX_KEEP_RULE_FILES,
    )

    fun consumerRules(ordinal: Int): File = boundedFile(
        consumerRulesDirectory,
        "consumer-${ordinal.toString().padStart(2, '0')}.pro",
        ordinal,
        R8CompilerContract.MAX_CONSUMER_RULE_FILES,
    )

    override fun close() {
        root.deleteRecursively()
    }

    private fun boundedFile(directory: File, name: String, ordinal: Int, maximum: Int): File {
        require(ordinal in 0 until maximum) { "Input ordinal is outside the protocol limit" }
        return File(directory, name)
    }

    companion object {
        fun create(context: Context): PrivateSessionWorkspace = createUnder(
            File(context.cacheDir, WORKSPACE_DIRECTORY),
        )

        fun recoverStale(context: Context) {
            val canonicalCache = try {
                context.cacheDir.canonicalFile
            } catch (error: IOException) {
                throw IOException("Unable to resolve the private cache directory", error)
            }
            recoverStaleUnder(File(canonicalCache, WORKSPACE_DIRECTORY))
        }

        internal fun recoverStaleUnder(baseDirectory: File) {
            val absoluteBase = baseDirectory.absoluteFile
            val canonicalBase = try {
                baseDirectory.canonicalFile
            } catch (error: IOException) {
                throw IOException("Unable to resolve the private R8 workspace root", error)
            }
            if (canonicalBase.path != absoluteBase.path) {
                throw IOException("Private R8 workspace root is not canonical")
            }
            if (!canonicalBase.exists()) return
            if (!canonicalBase.isDirectory) {
                throw IOException("Private R8 workspace root is not a directory")
            }
            val children = canonicalBase.listFiles()
                ?: throw IOException("Unable to enumerate stale R8 workspaces")
            children.filter { SESSION_DIRECTORY.matches(it.name) }.forEach { candidate ->
                val canonicalCandidate = try {
                    candidate.canonicalFile
                } catch (error: IOException) {
                    throw IOException("Unable to resolve a stale R8 workspace", error)
                }
                val expectedPath = canonicalBase.path + File.separator + candidate.name
                if (!candidate.isDirectory || canonicalCandidate.path != expectedPath) {
                    throw IOException("Stale R8 workspace is not a canonical direct directory")
                }
                deleteCanonicalTree(candidate, canonicalBase)
            }
        }

        internal fun createUnder(baseDirectory: File): PrivateSessionWorkspace {
            if (!baseDirectory.exists() && !baseDirectory.mkdirs()) {
                throw IOException("Failed to create the private R8 workspace root")
            }
            val canonicalBase = baseDirectory.canonicalFile
            repeat(MAX_CREATION_ATTEMPTS) {
                val candidate = File(canonicalBase, "session-${UUID.randomUUID()}")
                if (candidate.mkdir()) {
                    val canonicalCandidate = candidate.canonicalFile
                    val expectedPrefix = canonicalBase.path + File.separator
                    if (!canonicalCandidate.path.startsWith(expectedPrefix)) {
                        candidate.deleteRecursively()
                        throw IOException("R8 workspace escaped its private root")
                    }
                    return PrivateSessionWorkspace(canonicalCandidate)
                }
            }
            throw IOException("Failed to allocate a private R8 workspace")
        }

        private fun deleteCanonicalTree(node: File, canonicalBase: File) {
            val absoluteNode = node.absoluteFile
            val canonicalNode = try {
                node.canonicalFile
            } catch (error: IOException) {
                throw IOException("Unable to resolve a stale R8 workspace entry", error)
            }
            val allowedPrefix = canonicalBase.path + File.separator
            if (canonicalNode.path != absoluteNode.path || !canonicalNode.path.startsWith(allowedPrefix)) {
                throw IOException("Stale R8 workspace entry is not a canonical child")
            }
            when {
                node.isDirectory -> (node.listFiles()
                    ?: throw IOException("Unable to enumerate a stale R8 workspace"))
                    .forEach { child -> deleteCanonicalTree(child, canonicalBase) }
                node.isFile -> Unit
                else -> throw IOException("Stale R8 workspace entry has an unsupported file type")
            }
            if (!node.delete() || node.exists()) {
                throw IOException("Unable to remove a stale R8 workspace entry")
            }
        }

        private const val WORKSPACE_DIRECTORY = "r8-compiler-sessions"
        private const val MAX_CREATION_ATTEMPTS = 8
        private val SESSION_DIRECTORY = Regex(
            "^session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

/** Runs a successful stale-workspace recovery once per provider process. */
internal class ProcessWorkspaceRecovery {
    @Volatile
    private var completed = false

    fun ensureRecovered(recover: () -> Unit) {
        if (completed) return
        synchronized(this) {
            if (completed) return
            recover()
            completed = true
        }
    }
}
