package io.github.supermonster003.autojs6.plugin.r8compiler

import org.autojs.plugin.r8compiler.api.R8BundleError
import org.autojs.plugin.r8compiler.api.R8BundleException
import org.autojs.plugin.r8compiler.api.R8CompileRequest
import org.autojs.plugin.r8compiler.api.R8CompilerCapabilities
import org.autojs.plugin.r8compiler.api.R8CompilerValidation
import org.autojs.plugin.r8compiler.api.R8ContractException
import org.autojs.plugin.r8compiler.api.R8ContractViolation
import org.autojs.plugin.r8compiler.api.R8ErrorCode
import org.autojs.plugin.r8compiler.api.R8FailurePhase
import org.autojs.plugin.r8compiler.api.R8InputBundleCodec
import org.autojs.plugin.r8compiler.api.R8InputIdentity
import org.autojs.plugin.r8compiler.api.R8InputRole
import org.autojs.plugin.r8compiler.api.R8RulePolicyException
import org.autojs.plugin.r8compiler.api.R8Sha256
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class MaterializedR8Rule(
    val identity: R8InputIdentity,
    val file: File,
    val content: ByteArray,
)

internal data class MaterializedR8Inputs(
    val programJar: File,
    val classpathJars: List<File>,
    val rules: List<MaterializedR8Rule>,
)

/** Materializes a fully admitted, path-free protocol bundle into one private staging directory. */
internal object R8InputMaterializer {
    fun materialize(
        request: R8CompileRequest,
        capabilities: R8CompilerCapabilities,
        input: InputStream,
        workspace: PrivateSessionWorkspace,
        ensureActive: () -> Unit = {},
    ): MaterializedR8Inputs {
        val classpath = ArrayList<File>()
        val rules = ArrayList<MaterializedR8Rule>()
        val aggregate = AggregateArchiveBudget(capabilities)
        try {
            val summary = R8InputBundleCodec.read(input, request, capabilities) { identity, entry ->
                ensureActive()
                when (identity.role) {
                    R8InputRole.PROGRAM_JAR -> {
                        val validated = BoundedJarValidator.copyAndValidate(
                            entry,
                            workspace.programJar,
                            identity,
                            capabilities.limits,
                            requireClassEntries = true,
                        )
                        if (validated.uncompressedSizeBytes > capabilities.limits.maxUncompressedProgramBytes) {
                            fail(R8ErrorCode.INPUT_TOO_LARGE, "Program JAR exceeds the uncompressed limit")
                        }
                        aggregate.add(validated)
                    }
                    R8InputRole.CLASSPATH_JAR -> {
                        val destination = workspace.classpathJar(identity.ordinal)
                        aggregate.add(
                            BoundedJarValidator.copyAndValidate(
                                entry,
                                destination,
                                identity,
                                capabilities.limits,
                                requireClassEntries = false,
                            ),
                        )
                        classpath += destination
                    }
                    R8InputRole.KEEP_RULES,
                    R8InputRole.CONSUMER_RULES,
                    -> {
                        val content = readExactRule(entry, identity, ensureActive)
                        val destination = when (identity.role) {
                            R8InputRole.KEEP_RULES -> workspace.keepRules(identity.ordinal)
                            R8InputRole.CONSUMER_RULES -> workspace.consumerRules(identity.ordinal)
                            else -> error("unreachable")
                        }
                        destination.writeBytes(content)
                        rules += MaterializedR8Rule(identity, destination, content)
                    }
                }
                ensureActive()
            }
            if (summary.identities != request.inputIdentities ||
                summary.sizeBytes != request.inputBundleSizeBytes ||
                summary.contentSha256 != request.inputBundleSha256
            ) {
                fail(R8ErrorCode.INVALID_BUNDLE, "Input bundle does not match the admitted request")
            }
            R8CompilerValidation.validateExtractedRules(
                rules.map { it.identity.role to it.content },
                capabilities,
            )
            ensureActive()
            return MaterializedR8Inputs(workspace.programJar, classpath.toList(), rules.toList())
        } catch (error: R8CompilerFailure) {
            throw error
        } catch (error: R8BundleException) {
            throw R8CompilerFailure(
                if (error.error == R8BundleError.LIMIT_EXCEEDED) {
                    R8ErrorCode.INPUT_TOO_LARGE
                } else {
                    R8ErrorCode.INVALID_BUNDLE
                },
                R8FailurePhase.INPUT_VALIDATION,
                "Input bundle failed canonical validation",
                error,
            )
        } catch (error: R8RulePolicyException) {
            throw R8CompilerFailure(
                R8ErrorCode.RULE_POLICY_REJECTED,
                R8FailurePhase.RULE_VALIDATION,
                "Explicit R8 rules violate the provider policy",
                error,
            )
        } catch (error: R8ContractException) {
            val code = when (error.violation) {
                R8ContractViolation.PROTOCOL_INCOMPATIBLE -> R8ErrorCode.UNSUPPORTED_PROTOCOL
                R8ContractViolation.CAPABILITY_INCOMPATIBLE -> R8ErrorCode.UNSUPPORTED_CAPABILITY
                else -> R8ErrorCode.INVALID_REQUEST
            }
            val phase = if (error.violation == R8ContractViolation.INVALID_VALUE ||
                error.violation == R8ContractViolation.UNKNOWN_ENUM
            ) {
                R8FailurePhase.INPUT_VALIDATION
            } else {
                R8FailurePhase.NEGOTIATION
            }
            throw R8CompilerFailure(code, phase, "R8 request is incompatible with the provider", error)
        }
    }

    private fun readExactRule(
        input: InputStream,
        identity: R8InputIdentity,
        ensureActive: () -> Unit,
    ): ByteArray {
        val expected = identity.sizeBytes.toInt()
        val output = ByteArray(expected)
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < output.size) {
            ensureActive()
            val read = input.read(output, offset, output.size - offset)
            if (read < 0) fail(R8ErrorCode.INVALID_BUNDLE, "Rule payload is truncated")
            if (read == 0) continue
            digest.update(output, offset, read)
            offset += read
        }
        if (input.read() >= 0 || R8Sha256.fromBytes(digest.digest()) != identity.contentSha256) {
            fail(R8ErrorCode.INVALID_BUNDLE, "Rule payload does not match its admitted identity")
        }
        return output
    }

    private class AggregateArchiveBudget(
        private val capabilities: R8CompilerCapabilities,
    ) {
        private var archiveEntries = 0L
        private var uncompressedBytes = 0L
        private var classBytes = 0L

        fun add(value: ValidatedJar) {
            archiveEntries = addExact(archiveEntries, value.archiveEntryCount.toLong())
            uncompressedBytes = addExact(uncompressedBytes, value.uncompressedSizeBytes)
            classBytes = addExact(classBytes, value.totalClassBytes)
            if (
                archiveEntries > capabilities.limits.maxTotalArchiveEntries ||
                uncompressedBytes > capabilities.limits.maxTotalUncompressedInputBytes ||
                classBytes > capabilities.limits.maxTotalClassBytes
            ) {
                fail(R8ErrorCode.INPUT_TOO_LARGE, "Input archives exceed the aggregate provider budget")
            }
        }

        private fun addExact(left: Long, right: Long): Long = try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw R8CompilerFailure(
                R8ErrorCode.INPUT_TOO_LARGE,
                R8FailurePhase.INPUT_VALIDATION,
                "Input archive aggregate overflows",
                error,
            )
        }
    }

    private fun fail(code: R8ErrorCode, message: String): Nothing = throw R8CompilerFailure(
        code,
        R8FailurePhase.INPUT_VALIDATION,
        message,
    )
}
