package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class R8ValidationTest {
    @Test fun retraceMetadataPinsTheR8MappingFormatDomain() {
        val fixture = R8Fixtures.artifactBundle()
        expectContractFailure {
            R8CompilerValidation.validateRetraceMetadata(
                fixture.metadata.copy(formatId = "foreign.mapping"),
            )
        }
        R8CompilerValidation.validateRetraceMetadata(fixture.metadata)
    }

    @Test fun requestDiagnosticBudgetMustPermitAProtocolErrorMessage() {
        val capabilities = R8Fixtures.capabilities()
        val request = R8Fixtures.request(capabilities)
        expectContractFailure {
            R8CompilerValidation.validateRequest(copyRequest(request, diagnosticByteLimit = 0))
        }
        R8CompilerValidation.validateRequest(copyRequest(request, diagnosticByteLimit = 1))
    }

    @Test fun validRequestMatchesCapabilities() {
        val caps = R8Fixtures.capabilities()
        val request = R8Fixtures.request(caps)
        R8CompilerValidation.validateRequestAgainst(request, caps)
        assertEquals(R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1, request.runtimeLibraryModel)
        assertEquals(caps.runtimeLibraryModel, request.runtimeLibraryModel)
    }

    @Test fun requestRejectsRuntimeAndCapabilityFingerprintDrift() {
        val caps = R8Fixtures.capabilities()
        val request = R8Fixtures.request(caps)
        expectContractFailure { R8CompilerValidation.validateRequestAgainst(copyRequest(request, runtime = R8Fixtures.sha("wrong")), caps) }
        expectContractFailure { R8CompilerValidation.validateRequestAgainst(copyRequest(request, capability = R8Fixtures.sha("wrong")), caps) }
    }

    @Test fun requestRejectsProviderSpecificInputAndArtifactLimits() {
        val caps = R8Fixtures.capabilities(
            limits = R8Fixtures.limits(
                maxProgramBytes = 4,
                maxClasspathJarBytes = 64,
                maxTotalClasspathBytes = 64,
                maxRuleFileBytes = 128,
                maxTotalRuleBytes = 256,
            ),
        )
        expectContractFailure { R8CompilerValidation.validateRequestAgainst(R8Fixtures.request(caps), caps) }
    }

    @Test fun capabilitiesFitMaximumProgramAndMandatoryKeepFraming() {
        val maximumProgram = R8CompilerContract.MAX_PROGRAM_BYTES
        val exact = R8Fixtures.limits(
            maxProgramBytes = maximumProgram,
            maxInputBundleBytes = maximumProgram + R8CompilerContract.MIN_INPUT_BUNDLE_OVERHEAD_WITH_KEEP,
        )
        R8CompilerValidation.validateCapabilities(R8Fixtures.capabilities(limits = exact))
        expectContractFailure {
            R8CompilerValidation.validateCapabilities(
                R8Fixtures.capabilities(
                    limits = R8Fixtures.limits(
                        maxProgramBytes = maximumProgram,
                        maxInputBundleBytes = maximumProgram +
                            R8CompilerContract.MIN_INPUT_BUNDLE_OVERHEAD_WITH_KEEP - 1,
                    ),
                ),
            )
        }
    }

    @Test fun requestRejectsMinApiOutsideCapabilityRange() {
        val caps = R8Fixtures.capabilities(minApi = 26, maxApi = 36)
        expectContractFailure { R8CompilerValidation.validateRequestAgainst(R8Fixtures.request(caps, minApi = 24), caps) }
    }

    @Test fun requestOutputBudgetMustFitCanonicalFiveArtifactFraming() {
        val caps = R8Fixtures.capabilities()
        R8CompilerValidation.validateRequest(
            R8Fixtures.request(caps, maxOutputBundleBytes = R8CompilerContract.MIN_OUTPUT_BUNDLE_BYTES),
        )
        expectContractFailure {
            R8CompilerValidation.validateRequest(
                R8Fixtures.request(caps, maxOutputBundleBytes = R8CompilerContract.MIN_OUTPUT_BUNDLE_BYTES - 1),
            )
        }
    }

    @Test fun startedBindsRequestCompilerRuntimeAndCapability() {
        val caps = R8Fixtures.capabilities()
        val request = R8Fixtures.request(caps)
        R8CompilerValidation.validateStartedAgainst(R8Fixtures.started(request, caps), request, caps)
        expectContractFailure {
            R8CompilerValidation.validateStartedAgainst(
                R8Fixtures.started(request, caps).copy(compilerVersion = "other"),
                request,
                caps,
            )
        }
    }

    @Test fun progressBindsRequestId() {
        val request = R8Fixtures.request()
        R8CompilerValidation.validateProgressAgainst(R8Progress(request.requestId, 1, R8ProgressStage.COMPILING), request)
        expectContractFailure {
            R8CompilerValidation.validateProgressAgainst(
                R8Progress(R8RequestId.fromUuid(UUID(1, 2)), 1, R8ProgressStage.COMPILING),
                request,
            )
        }
    }

    @Test fun progressSequenceFollowsStartedSequence() {
        val caps = R8Fixtures.capabilities()
        val request = R8Fixtures.request(caps)
        val started = R8Fixtures.started(request, caps).copy(sequence = 7)
        R8CompilerValidation.validateProgressAfterStarted(
            R8Progress(request.requestId, 8, R8ProgressStage.COMPILING),
            started,
            request,
            caps,
        )
        expectContractFailure {
            R8CompilerValidation.validateProgressAfterStarted(
                R8Progress(request.requestId, 7, R8ProgressStage.COMPILING),
                started,
                request,
                caps,
            )
        }
    }

    @Test fun resultBindsRequestCapabilitiesAndFiveArtifactBudgets() {
        val caps = R8Fixtures.capabilities()
        val request = R8Fixtures.inputBundle(caps).request
        val result = R8Fixtures.artifactBundle(caps, request).result
        R8CompilerValidation.validateResultAgainst(result, request, caps)
        val wrong = R8Result(
            result.requestId,
            result.compilerFamily,
            result.compilerVersion,
            R8Fixtures.sha("wrong"),
            result.runtimeLibraryFingerprint,
            result.inputSetFingerprint,
            result.profile,
            result.minApi,
            result.outputLayout,
            result.outputBundleSizeBytes,
            result.outputBundleSha256,
            result.artifactIdentities,
            result.determinismClaim,
            result.elapsedMillis,
            result.diagnostics,
        )
        expectContractFailure { R8CompilerValidation.validateResultAgainst(wrong, request, caps) }
    }

    @Test fun resultDiagnosticsBindZeroAndAggregateRequestBudgets() {
        val caps = R8Fixtures.capabilities()
        val request = R8Fixtures.inputBundle(caps).request
        val fixture = R8Fixtures.artifactBundle(caps, request)
        val diagnostic = R8Diagnostic(R8DiagnosticSeverity.ERROR, "R8_ERROR", "failed")
        val result = resultWithDiagnostics(fixture.result, listOf(diagnostic))

        R8CompilerValidation.validateResultAgainst(result, request, caps)
        expectContractFailure {
            R8CompilerValidation.validateResultAgainst(
                result,
                copyRequest(request, diagnosticByteLimit = 0),
                caps,
            )
        }
        val exactSingleBudget = "R8_ERROR".toByteArray(Charsets.UTF_8).size +
            "failed".toByteArray(Charsets.UTF_8).size
        expectContractFailure {
            R8CompilerValidation.validateResultAgainst(
                resultWithDiagnostics(fixture.result, listOf(diagnostic, diagnostic)),
                copyRequest(request, diagnosticByteLimit = exactSingleBudget),
                caps,
            )
        }
    }

    @Test fun terminalDiagnosticsUseUtf8BytesAndProviderBudget() {
        val caps = R8Fixtures.capabilities(limits = R8Fixtures.limits(maxDiagnosticBytes = 8))
        val request = R8Fixtures.request(caps, diagnosticByteLimit = 8)
        val exact = R8Diagnostic(R8DiagnosticSeverity.ERROR, "E", "界")
        val accepted = R8Error(
            request.requestId,
            R8ErrorCode.INTERNAL,
            R8FailurePhase.CLEANUP,
            "fail",
            1,
            listOf(exact),
        )
        R8CompilerValidation.validateErrorAgainst(accepted, request, caps)
        expectContractFailure {
            R8CompilerValidation.validateErrorAgainst(
                accepted,
                R8Fixtures.request(caps, diagnosticByteLimit = 7),
                caps,
            )
        }
        expectContractFailure {
            R8CompilerValidation.validateErrorAgainst(
                R8Error(
                    request.requestId,
                    R8ErrorCode.INTERNAL,
                    R8FailurePhase.CLEANUP,
                    "fail",
                    1,
                    listOf(exact, exact),
                ),
                request,
                caps,
            )
        }
        expectContractFailure {
            R8CompilerValidation.validateErrorAgainst(
                R8Error(request.requestId, R8ErrorCode.INTERNAL, R8FailurePhase.CLEANUP, "x", 1),
                R8Fixtures.request(caps, diagnosticByteLimit = 0),
                caps,
            )
        }
    }

    @Test fun terminalErrorsAndCancellationBindRequestId() {
        val request = R8Fixtures.request()
        val caps = R8Fixtures.capabilities()
        val error = R8Error(request.requestId, R8ErrorCode.INTERNAL, R8FailurePhase.CLEANUP, "failed", 1)
        val cancellation = R8Cancellation(request.requestId, R8CancellationReason.REQUESTED, R8FailurePhase.CLEANUP, 1)
        R8CompilerValidation.validateErrorAgainst(error, request, caps)
        R8CompilerValidation.validateCancellationAgainst(cancellation, request)
        val other = R8RequestId.fromUuid(UUID(2, 3))
        expectContractFailure {
            R8CompilerValidation.validateErrorAgainst(
                R8Error(other, R8ErrorCode.INTERNAL, R8FailurePhase.CLEANUP, "failed", 1),
                request,
                caps,
            )
        }
    }

    private fun copyRequest(
        value: R8CompileRequest,
        runtime: R8Sha256 = value.expectedRuntimeLibraryFingerprint,
        capability: R8Sha256 = value.expectedCapabilityFingerprint,
        diagnosticByteLimit: Int = value.diagnosticByteLimit,
    ) = R8CompileRequest(
        value.requestId,
        value.protocolVersion,
        value.compilerFamily,
        value.compilerIntent,
        value.fallbackPolicy,
        value.profile,
        value.minApi,
        value.inputLayout,
        value.inputIdentities,
        value.inputSetFingerprint,
        value.inputBundleSizeBytes,
        value.inputBundleSha256,
        value.runtimeLibraryModel,
        runtime,
        capability,
        value.canonicalizationPolicyVersion,
        value.rulePolicyVersion,
        value.outputLayout,
        value.requestedArtifacts,
        value.maxOutputBundleBytes,
        diagnosticByteLimit,
        value.timeoutMillis,
    )

    private fun resultWithDiagnostics(value: R8Result, diagnostics: List<R8Diagnostic>) = R8Result(
        value.requestId,
        value.compilerFamily,
        value.compilerVersion,
        value.capabilityFingerprint,
        value.runtimeLibraryFingerprint,
        value.inputSetFingerprint,
        value.profile,
        value.minApi,
        value.outputLayout,
        value.outputBundleSizeBytes,
        value.outputBundleSha256,
        value.artifactIdentities,
        value.determinismClaim,
        value.elapsedMillis,
        diagnostics,
    )
}
