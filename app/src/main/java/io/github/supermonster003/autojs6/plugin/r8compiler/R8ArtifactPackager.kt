package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8ArtifactBundleCodec
import org.autojs.plugin.r8compiler.api.R8ArtifactBundleSummary
import org.autojs.plugin.r8compiler.api.R8ArtifactContentValidation
import org.autojs.plugin.r8compiler.api.R8ArtifactIdentity
import org.autojs.plugin.r8compiler.api.R8ArtifactRole
import org.autojs.plugin.r8compiler.api.R8ArtifactSource
import org.autojs.plugin.r8compiler.api.R8BundleError
import org.autojs.plugin.r8compiler.api.R8BundleException
import org.autojs.plugin.r8compiler.api.R8CompileRequest
import org.autojs.plugin.r8compiler.api.R8CompilerCapabilities
import org.autojs.plugin.r8compiler.api.R8CompilerCodec
import org.autojs.plugin.r8compiler.api.R8CompilerContract
import org.autojs.plugin.r8compiler.api.R8CompilerValidation
import org.autojs.plugin.r8compiler.api.R8Diagnostic
import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import org.autojs.plugin.r8compiler.api.R8Result
import org.autojs.plugin.r8compiler.api.R8RetraceMetadata
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class ProducedR8Artifact(
    val file: File,
    val summary: R8ArtifactBundleSummary,
    val diagnostics: List<R8Diagnostic>,
)

internal object R8ArtifactPackager {
    fun packageArtifacts(
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
        workspace: PrivateSessionWorkspace,
        diagnostics: List<R8Diagnostic>,
        ensureActive: () -> Unit,
    ): ProducedR8Artifact {
        ensureActive()
        R8DexPackager.packageOutput(
            workspace.r8OutputDirectory,
            workspace.dexZip,
            minOf(
                request.requestedArtifacts.single { it.role == R8ArtifactRole.DEX_ZIP }.maxBytes,
                capabilities.limits.maxDexZipBytes,
            ),
            capabilities.limits.maxDexEntries,
        )
        canonicalizeReport(
            workspace.mapping,
            artifactLimit(request, capabilities, R8ArtifactRole.MAPPING_TEXT),
            requireContent = true,
        )
        canonicalizeReport(
            workspace.seeds,
            artifactLimit(request, capabilities, R8ArtifactRole.SEEDS_TEXT),
            requireContent = false,
        )
        canonicalizeReport(
            workspace.usage,
            artifactLimit(request, capabilities, R8ArtifactRole.USAGE_TEXT),
            requireContent = false,
        )

        val mappingIdentity = identity(R8ArtifactRole.MAPPING_TEXT, workspace.mapping)
        val mappingFormatVersion = parseMappingFormatVersion(workspace.mapping)
        val metadata = R8RetraceMetadata(
            mappingSha256 = mappingIdentity.contentSha256,
            formatId = R8CompilerContract.MAPPING_FORMAT_ID,
            formatVersion = mappingFormatVersion,
            compilerVersion = capabilities.compilerVersion,
            capabilityFingerprint = capabilities.capabilityFingerprint,
            runtimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
            inputSetFingerprint = request.inputSetFingerprint,
            minApi = request.minApi,
            profile = request.profile,
        )
        workspace.retraceMetadata.writeBytes(R8CompilerCodec.encodeRetraceMetadata(metadata))
        if (workspace.retraceMetadata.length() >
            artifactLimit(request, capabilities, R8ArtifactRole.RETRACE_METADATA)
        ) {
            fail(R8ErrorCode.OUTPUT_TOO_LARGE, "Retrace metadata exceeds its admitted limit")
        }

        val identities = listOf(
            identity(R8ArtifactRole.DEX_ZIP, workspace.dexZip),
            mappingIdentity,
            identity(R8ArtifactRole.SEEDS_TEXT, workspace.seeds),
            identity(R8ArtifactRole.USAGE_TEXT, workspace.usage),
            identity(R8ArtifactRole.RETRACE_METADATA, workspace.retraceMetadata),
        )
        val files = listOf(
            workspace.dexZip,
            workspace.mapping,
            workspace.seeds,
            workspace.usage,
            workspace.retraceMetadata,
        )
        val sources = identities.zip(files) { artifactIdentity, file ->
            R8ArtifactSource(artifactIdentity) { file.inputStream().buffered() }
        }

        ensureActive()
        val summary = try {
            workspace.artifactBundleStaging.outputStream().buffered().use { output ->
                R8ArtifactBundleCodec.write(output, sources, request, capabilities)
            }
        } catch (error: R8BundleException) {
            workspace.artifactBundleStaging.delete()
            throw R8CompilerFailure(
                if (error.error == R8BundleError.LIMIT_EXCEEDED) {
                    R8ErrorCode.OUTPUT_TOO_LARGE
                } else {
                    R8ErrorCode.INTERNAL
                },
                R8FailurePhase.OUTPUT_PACKAGING,
                "Could not assemble the admitted artifact bundle",
                error,
            )
        } catch (error: Throwable) {
            workspace.artifactBundleStaging.delete()
            throw error
        }
        ensureActive()
        if (workspace.artifactBundle.exists() || !workspace.artifactBundleStaging.renameTo(workspace.artifactBundle)) {
            workspace.artifactBundleStaging.delete()
            fail(R8ErrorCode.INTERNAL, "Could not atomically finalize the local artifact bundle")
        }
        if (workspace.artifactBundle.length() != summary.sizeBytes ||
            R8Hashes.sha256(workspace.artifactBundle) != summary.contentSha256
        ) {
            fail(R8ErrorCode.INTERNAL, "Finalized artifact bundle changed in private staging")
        }

        val provisionalResult = R8Result(
            requestId = request.requestId,
            compilerFamily = request.compilerFamily,
            compilerVersion = capabilities.compilerVersion,
            capabilityFingerprint = capabilities.capabilityFingerprint,
            runtimeLibraryFingerprint = capabilities.runtimeLibraryFingerprint,
            inputSetFingerprint = request.inputSetFingerprint,
            profile = request.profile,
            minApi = request.minApi,
            outputLayout = request.outputLayout,
            outputBundleSizeBytes = summary.sizeBytes,
            outputBundleSha256 = summary.contentSha256,
            artifactIdentities = summary.identities,
            determinismClaim = capabilities.determinismClaim,
            elapsedMillis = 0,
            diagnostics = diagnostics,
        )
        R8CompilerValidation.validateResultAgainst(provisionalResult, request, capabilities)
        R8CompilerValidation.validateRetraceMetadataAgainst(metadata, provisionalResult)
        return ProducedR8Artifact(workspace.artifactBundle, summary, diagnostics)
    }

    private fun canonicalizeReport(file: File, maximumBytes: Long, requireContent: Boolean) {
        if (!file.exists()) file.writeBytes(byteArrayOf())
        if (!file.isFile || file.length() > maximumBytes) {
            fail(R8ErrorCode.OUTPUT_TOO_LARGE, "R8 report exceeds its provider limit")
        }
        val raw = file.readBytes()
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (error: Exception) {
            throw R8CompilerFailure(
                R8ErrorCode.INTERNAL,
                R8FailurePhase.OUTPUT_PACKAGING,
                "R8 emitted a non-UTF-8 report",
                error,
            )
        }
        var canonical = decoded.replace("\r\n", "\n").replace('\r', '\n')
        if (canonical.isNotEmpty() && !canonical.endsWith('\n')) canonical += '\n'
        val bytes = canonical.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() > maximumBytes) {
            fail(R8ErrorCode.OUTPUT_TOO_LARGE, "Canonical R8 report exceeds its provider limit")
        }
        R8ArtifactContentValidation.validateText(bytes, requireContent)
        file.writeBytes(bytes)
    }

    private fun parseMappingFormatVersion(mapping: File): String {
        val prefixLength = minOf(mapping.length(), MAX_MAPPING_HEADER_BYTES.toLong()).toInt()
        val prefix = mapping.inputStream().use { input ->
            val bytes = ByteArray(prefixLength)
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                if (read > 0) offset += read
            }
            String(bytes, 0, offset, StandardCharsets.UTF_8)
        }
        return MAPPING_VERSION.find(prefix)?.groupValues?.get(1)
            ?: fail(R8ErrorCode.INTERNAL, "R8 mapping format version is missing")
    }

    private fun identity(role: R8ArtifactRole, file: File): R8ArtifactIdentity {
        if (!file.isFile) fail(R8ErrorCode.INTERNAL, "R8 artifact is missing from private staging")
        return R8ArtifactIdentity(role, 0, file.length(), R8Hashes.sha256(file))
    }

    private fun artifactLimit(
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
        role: R8ArtifactRole,
    ): Long {
        val providerMaximum = when (role) {
            R8ArtifactRole.DEX_ZIP -> capabilities.limits.maxDexZipBytes
            R8ArtifactRole.MAPPING_TEXT -> capabilities.limits.maxMappingBytes
            R8ArtifactRole.SEEDS_TEXT -> capabilities.limits.maxSeedsBytes
            R8ArtifactRole.USAGE_TEXT -> capabilities.limits.maxUsageBytes
            R8ArtifactRole.RETRACE_METADATA -> capabilities.limits.maxRetraceMetadataBytes
        }
        return minOf(request.requestedArtifacts.single { it.role == role }.maxBytes, providerMaximum)
    }

    private fun fail(code: R8ErrorCode, message: String): Nothing = throw R8CompilerFailure(
        code,
        R8FailurePhase.OUTPUT_PACKAGING,
        message,
    )

    private val MAPPING_VERSION = Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private const val MAX_MAPPING_HEADER_BYTES = 8 * 1024
}
