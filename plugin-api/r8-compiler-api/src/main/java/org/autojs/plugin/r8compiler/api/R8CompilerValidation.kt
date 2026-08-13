package org.autojs.plugin.r8compiler.api

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object R8CompilerValidation {
    private val codePattern = Regex("[A-Z][A-Z0-9_]{0,127}")
    private val canonicalArtifacts = R8ArtifactRole.values().toList()

    fun validateInfo(value: R8CompilerInfo) {
        validateProtocol(value.protocolMin); validateProtocol(value.protocolMax)
        requireValue(value.protocolMin <= value.protocolMax, "Protocol range is reversed")
        requireText(value.providerId, R8CompilerContract.MAX_PROVIDER_ID_BYTES, "Provider ID")
        requireText(value.providerVersionName, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Provider version")
        requireValue(value.providerVersionCode > 0, "Provider version code must be positive")
        value.minHostVersionCode?.let { requireValue(it > 0, "Minimum host version is invalid") }
        value.maxHostVersionCode?.let { requireValue(it > 0, "Maximum host version is invalid") }
        if (value.minHostVersionCode != null && value.maxHostVersionCode != null) {
            requireValue(value.minHostVersionCode <= value.maxHostVersionCode, "Host version range is reversed")
        }
    }

    fun validateCapabilities(value: R8CompilerCapabilities) {
        requireValue(value.compilerFamily == R8CompilerFamily.R8, "Compiler family must be R8")
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Compiler version")
        requireValue(value.profiles == listOf(R8CompilerProfile.FULL_RELEASE), "Only FULL_RELEASE is allowed")
        requireValue(
            value.minApi in R8CompilerContract.MIN_SUPPORTED_API..R8CompilerContract.MAX_SUPPORTED_API &&
                value.maxApi in value.minApi..R8CompilerContract.MAX_SUPPORTED_API,
            "API range is invalid",
        )
        requireValue(value.inputLayout == R8InputLayout.PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1, "Input layout is invalid")
        requireValue(value.outputLayout == R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1, "Output layout is invalid")
        requireValue(value.artifactRoles == canonicalArtifacts, "Artifact roles must be exact and canonical")
        requireValue(value.determinismClaim == R8DeterminismClaim.NOT_CLAIMED, "Determinism claim is invalid")
        requireValue(value.runtimeLibraryModel == R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1, "Runtime model is invalid")
        requireValue(value.rulePolicyVersion == R8CompilerContract.RULE_POLICY_VERSION, "Rule policy is unsupported")
        requireValue(value.canonicalizationPolicyVersion == R8CompilerContract.CANONICALIZATION_POLICY_VERSION, "Canonicalization policy is unsupported")
        requireValue(value.implicitRulePolicy == R8ImplicitRulePolicy.NONE, "Implicit rules are forbidden")
        validateLimits(value.limits)
        requireValue(
            value.runtimeLibraryIdentities.size in 1..R8CompilerContract.MAX_RUNTIME_LIBRARY_IDENTITIES,
            "Runtime identity count is invalid",
        )
        value.runtimeLibraryIdentities.forEach { requireValue(it.sizeBytes > 0, "Runtime identity size is invalid") }
        requireValue(value.runtimeLibraryFingerprint == R8RuntimeLibraryFingerprint.compute(value.runtimeLibraryIdentities), "Runtime fingerprint mismatch")
        requireValue(value.capabilityFingerprint == R8CapabilityFingerprint.compute(value), "Capability fingerprint mismatch")
        requireValue(value.supportsMultiDex, "R8 protocol requires multi-dex")
    }

    fun validateRequest(value: R8CompileRequest) {
        validateProtocol(value.protocolVersion)
        requireValue(value.protocolVersion == R8CompilerContract.PROTOCOL_V1, "Protocol is unsupported", R8ContractViolation.PROTOCOL_INCOMPATIBLE)
        requireValue(value.compilerFamily == R8CompilerFamily.R8, "Compiler family must be R8")
        requireValue(value.compilerIntent == R8CompilerIntent.R8_EXPLICIT, "R8 must be selected explicitly")
        requireValue(value.fallbackPolicy == R8FallbackPolicy.NONE, "Fallback is forbidden")
        requireValue(value.profile == R8CompilerProfile.FULL_RELEASE, "Only FULL_RELEASE is allowed")
        requireValue(value.minApi in R8CompilerContract.MIN_SUPPORTED_API..R8CompilerContract.MAX_SUPPORTED_API, "Minimum API is invalid")
        requireValue(value.inputLayout == R8InputLayout.PROGRAM_CLASSPATH_AND_RULES_BUNDLE_V1, "Input layout is invalid")
        validateInputIdentitiesSnapshot(value.inputIdentities)
        requireValue(value.inputSetFingerprint == R8InputSetFingerprint.compute(value.inputIdentities), "Input-set fingerprint mismatch")
        requireValue(value.inputBundleSizeBytes in 1..R8CompilerContract.MAX_INPUT_BUNDLE_BYTES, "Input bundle size is invalid")
        requireValue(value.inputBundleSizeBytes == R8InputBundleCodec.encodedSize(value.inputIdentities), "Input bundle size is not canonical")
        requireValue(
            value.runtimeLibraryModel == R8RuntimeLibraryModel.DEVICE_RUNTIME_BOOTCLASSPATH_V1,
            "Runtime library model is unsupported",
        )
        requireValue(value.canonicalizationPolicyVersion == R8CompilerContract.CANONICALIZATION_POLICY_VERSION, "Canonicalization policy is unsupported")
        requireValue(value.rulePolicyVersion == R8CompilerContract.RULE_POLICY_VERSION, "Rule policy is unsupported")
        requireValue(value.outputLayout == R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1, "Output layout is invalid")
        validateRequestedArtifactsSnapshot(value.requestedArtifacts)
        requireValue(value.maxOutputBundleBytes in R8CompilerContract.MIN_OUTPUT_BUNDLE_BYTES..R8CompilerContract.MAX_OUTPUT_BUNDLE_BYTES, "Output bundle limit is invalid")
        requireValue(value.diagnosticByteLimit in 1..R8CompilerContract.MAX_DIAGNOSTIC_BYTES, "Diagnostic limit is invalid")
        requireValue(value.timeoutMillis in 1..R8CompilerContract.MAX_TIMEOUT_MILLIS, "Timeout is invalid")
    }

    fun validateRequestAgainst(value: R8CompileRequest, caps: R8CompilerCapabilities) {
        validateRequest(value); validateCapabilities(caps)
        capability(value.minApi in caps.minApi..caps.maxApi, "Minimum API is unsupported")
        capability(value.runtimeLibraryModel == caps.runtimeLibraryModel, "Runtime library model is incompatible")
        capability(value.expectedRuntimeLibraryFingerprint == caps.runtimeLibraryFingerprint, "Runtime fingerprint is incompatible")
        capability(value.expectedCapabilityFingerprint == caps.capabilityFingerprint, "Capability fingerprint is incompatible")
        capability(value.maxOutputBundleBytes <= caps.limits.maxOutputBundleBytes, "Output limit exceeds provider")
        capability(value.diagnosticByteLimit <= caps.limits.maxDiagnosticBytes, "Diagnostic limit exceeds provider")
        capability(value.timeoutMillis <= caps.limits.maxTimeoutMillis, "Timeout exceeds provider")
        validateInputIdentitiesAgainstCapabilities(value.inputIdentities, value.inputBundleSizeBytes, caps)
        value.requestedArtifacts.forEach { requested -> capability(requested.maxBytes <= maximumFor(caps.limits, requested.role), "Artifact limit exceeds provider") }
    }

    @JvmSynthetic
    internal fun validateInputIdentitiesAgainstCapabilities(
        values: List<R8InputIdentity>,
        inputBundleSizeBytes: Long,
        caps: R8CompilerCapabilities,
    ) {
        val identities = boundedSnapshot(values, maximumInputIdentityCount, "Input identity")
        validateInputIdentitiesSnapshot(identities); validateCapabilities(caps)
        val program = identities.single { it.role == R8InputRole.PROGRAM_JAR }
        val classpath = identities.filter { it.role == R8InputRole.CLASSPATH_JAR }
        val keeps = identities.filter { it.role == R8InputRole.KEEP_RULES }
        val consumers = identities.filter { it.role == R8InputRole.CONSUMER_RULES }
        capability(program.sizeBytes <= caps.limits.maxProgramBytes, "Program exceeds provider")
        capability(classpath.size <= caps.limits.maxClasspathCount, "Classpath count exceeds provider")
        capability(classpath.all { it.sizeBytes <= caps.limits.maxClasspathJarBytes }, "Classpath JAR exceeds provider")
        capability(sum(classpath.map { it.sizeBytes }) <= caps.limits.maxTotalClasspathBytes, "Classpath bytes exceed provider")
        capability(keeps.size <= caps.limits.maxKeepRuleFiles, "Keep-rule count exceeds provider")
        capability(consumers.size <= caps.limits.maxConsumerRuleFiles, "Consumer-rule count exceeds provider")
        capability((keeps + consumers).all { it.sizeBytes <= caps.limits.maxRuleFileBytes }, "Rule file exceeds provider")
        capability(sum((keeps + consumers).map { it.sizeBytes }) <= caps.limits.maxTotalRuleBytes, "Rule bytes exceed provider")
        capability(inputBundleSizeBytes == R8InputBundleCodec.encodedSize(identities), "Input bundle size is not canonical")
        capability(inputBundleSizeBytes <= caps.limits.maxInputBundleBytes, "Input bundle exceeds provider")
    }

    /** Provider admission hook after bounded bundle extraction; rules are never accepted by metadata alone. */
    fun validateExtractedRules(files: Collection<Pair<R8InputRole, ByteArray>>, caps: R8CompilerCapabilities) {
        validateCapabilities(caps)
        R8RulePolicy.validateAggregate(files, R8RuleAdmissionLimits.from(caps.limits))
    }

    fun validateStartedAgainst(value: R8Started, request: R8CompileRequest, caps: R8CompilerCapabilities) {
        validateStarted(value); validateRequestAgainst(request, caps)
        capability(value.requestId == request.requestId, "Started request ID mismatch")
        capability(value.protocolVersion == request.protocolVersion, "Started protocol mismatch")
        capability(value.compilerFamily == request.compilerFamily, "Started compiler family mismatch")
        capability(value.compilerVersion == caps.compilerVersion, "Started compiler version mismatch")
        capability(value.capabilityFingerprint == caps.capabilityFingerprint, "Started capability fingerprint mismatch")
        capability(value.runtimeLibraryFingerprint == caps.runtimeLibraryFingerprint, "Started runtime fingerprint mismatch")
        capability(value.profile == request.profile, "Started profile mismatch")
    }

    fun validateResultAgainst(value: R8Result, request: R8CompileRequest, caps: R8CompilerCapabilities) {
        validateResult(value); validateRequestAgainst(request, caps)
        capability(value.requestId == request.requestId, "Result request ID mismatch")
        capability(value.compilerFamily == request.compilerFamily, "Result compiler family mismatch")
        capability(value.compilerVersion == caps.compilerVersion, "Result compiler version mismatch")
        capability(value.capabilityFingerprint == caps.capabilityFingerprint, "Result capability fingerprint mismatch")
        capability(value.runtimeLibraryFingerprint == caps.runtimeLibraryFingerprint, "Result runtime fingerprint mismatch")
        capability(value.inputSetFingerprint == request.inputSetFingerprint, "Result input fingerprint mismatch")
        capability(value.profile == request.profile && value.minApi == request.minApi, "Result compile options mismatch")
        capability(value.outputLayout == request.outputLayout, "Result output layout mismatch")
        capability(value.outputBundleSizeBytes <= request.maxOutputBundleBytes, "Result bundle exceeds request")
        capability(value.outputBundleSizeBytes <= caps.limits.maxOutputBundleBytes, "Result bundle exceeds provider")
        value.artifactIdentities.zip(request.requestedArtifacts).forEach { (artifact, requested) ->
            capability(artifact.role == requested.role && artifact.sizeBytes <= requested.maxBytes, "Result artifact exceeds request")
            capability(artifact.sizeBytes <= maximumFor(caps.limits, artifact.role), "Result artifact exceeds provider")
        }
        validateDiagnosticBudgetAgainst(value.diagnostics, request, caps, "Result diagnostics")
    }

    fun validateRetraceMetadataAgainst(value: R8RetraceMetadata, result: R8Result) {
        validateRetraceMetadata(value); validateResult(result)
        val mapping = result.artifactIdentities.single { it.role == R8ArtifactRole.MAPPING_TEXT }
        capability(value.mappingSha256 == mapping.contentSha256, "Retrace metadata mapping digest mismatch")
        capability(value.compilerVersion == result.compilerVersion, "Retrace metadata compiler mismatch")
        capability(value.capabilityFingerprint == result.capabilityFingerprint, "Retrace metadata capability mismatch")
        capability(value.runtimeLibraryFingerprint == result.runtimeLibraryFingerprint, "Retrace metadata runtime mismatch")
        capability(value.inputSetFingerprint == result.inputSetFingerprint, "Retrace metadata input mismatch")
        capability(value.minApi == result.minApi && value.profile == result.profile, "Retrace metadata options mismatch")
    }

    fun validateInputBundleSummaryAgainst(summary: R8InputBundleSummary, request: R8CompileRequest) {
        validateRequest(request)
        capability(summary.identities == request.inputIdentities, "Input bundle identities mismatch")
        capability(summary.sizeBytes == request.inputBundleSizeBytes, "Input bundle size mismatch")
        capability(summary.contentSha256 == request.inputBundleSha256, "Input bundle digest mismatch")
        capability(R8InputSetFingerprint.compute(summary.identities) == request.inputSetFingerprint, "Input-set fingerprint mismatch")
    }

    fun validateArtifactBundleSummaryAgainst(summary: R8ArtifactBundleSummary, result: R8Result, request: R8CompileRequest, caps: R8CompilerCapabilities) {
        validateResultAgainst(result, request, caps)
        capability(summary.identities == result.artifactIdentities, "Artifact bundle identities mismatch")
        capability(summary.sizeBytes == result.outputBundleSizeBytes, "Artifact bundle size mismatch")
        capability(summary.contentSha256 == result.outputBundleSha256, "Artifact bundle digest mismatch")
    }

    fun validateErrorAgainst(value: R8Error, request: R8CompileRequest, caps: R8CompilerCapabilities) {
        validateError(value); validateRequestAgainst(request, caps)
        capability(value.requestId == request.requestId, "Error request ID mismatch")
        validateDiagnosticBudgetAgainst(
            Math.addExact(utf8Size(value.message).toLong(), diagnosticBytes(value.diagnostics)),
            request,
            caps,
            "Error diagnostics",
        )
    }
    fun validateCancellationAgainst(value: R8Cancellation, request: R8CompileRequest) { validateCancellation(value); capability(value.requestId == request.requestId, "Cancellation request ID mismatch") }

    fun validateInputIdentities(values: List<R8InputIdentity>) {
        val identities = boundedSnapshot(values, maximumInputIdentityCount, "Input identity")
        validateInputIdentitiesSnapshot(identities)
    }

    @JvmSynthetic
    internal fun validateInputIdentitiesSnapshot(values: List<R8InputIdentity>) {
        requireValue(values.isNotEmpty(), "Input identities must not be empty")
        requireValue(values.first().role == R8InputRole.PROGRAM_JAR && values.first().ordinal == 0, "Exactly one program must be first")
        val programs = values.filter { it.role == R8InputRole.PROGRAM_JAR }
        requireValue(programs.size == 1, "Exactly one program is required")
        val classpath = values.filter { it.role == R8InputRole.CLASSPATH_JAR }
        val keepRules = values.filter { it.role == R8InputRole.KEEP_RULES }
        val consumerRules = values.filter { it.role == R8InputRole.CONSUMER_RULES }
        requireValue(classpath.size <= R8CompilerContract.MAX_CLASSPATH_JARS, "Classpath count exceeds limit")
        requireValue(keepRules.isNotEmpty(), "At least one explicit keep-rule file is required")
        requireValue(keepRules.size <= R8CompilerContract.MAX_KEEP_RULE_FILES, "Keep-rule count exceeds limit")
        requireValue(consumerRules.size <= R8CompilerContract.MAX_CONSUMER_RULE_FILES, "Consumer-rule count exceeds limit")
        requireValue(values == programs + classpath + keepRules + consumerRules, "Input roles are not canonically grouped")
        listOf(programs, classpath, keepRules, consumerRules).forEach { group ->
            group.forEachIndexed { index, identity -> requireValue(identity.ordinal == index, "Input ordinals are not contiguous") }
        }
        values.forEachIndexed { index, identity ->
            val maximum = when (identity.role) {
                R8InputRole.PROGRAM_JAR -> R8CompilerContract.MAX_PROGRAM_BYTES
                R8InputRole.CLASSPATH_JAR -> R8CompilerContract.MAX_CLASSPATH_JAR_BYTES
                R8InputRole.KEEP_RULES, R8InputRole.CONSUMER_RULES -> R8CompilerContract.MAX_RULE_FILE_BYTES
            }
            requireValue(identity.sizeBytes in 1..maximum, "Input $index size is invalid")
            if (identity.role == R8InputRole.CONSUMER_RULES) {
                requireValue(identity.ownerClasspathOrdinal in classpath.indices, "Consumer rule owner is invalid")
                requireValue(identity.ownerClasspathSha256 == classpath[identity.ownerClasspathOrdinal].contentSha256, "Consumer rule owner digest mismatch")
            } else {
                requireValue(identity.ownerClasspathOrdinal == -1 && identity.ownerClasspathSha256 == R8Sha256.ZERO, "Non-consumer input declares an owner")
            }
        }
        requireValue(sum(classpath.map { it.sizeBytes }) <= R8CompilerContract.MAX_TOTAL_CLASSPATH_BYTES, "Classpath bytes exceed limit")
        requireValue(sum((keepRules + consumerRules).map { it.sizeBytes }) <= R8CompilerContract.MAX_TOTAL_RULE_BYTES, "Rule bytes exceed limit")
    }

    fun validateRequestedArtifacts(values: List<R8RequestedArtifact>) {
        val artifacts = boundedSnapshot(values, artifactCount, "Requested artifact")
        validateRequestedArtifactsSnapshot(artifacts)
    }

    private fun validateRequestedArtifactsSnapshot(values: List<R8RequestedArtifact>) {
        requireValue(values.map { it.role } == canonicalArtifacts, "All five artifacts must be requested canonically")
        values.forEach { requireValue(it.maxBytes in 1..maximumFor(it.role), "Artifact limit is invalid") }
    }

    fun validateArtifactIdentities(values: List<R8ArtifactIdentity>) {
        val identities = boundedSnapshot(values, artifactCount, "Artifact identity")
        validateArtifactIdentitiesSnapshot(identities)
    }

    @JvmSynthetic
    internal fun validateArtifactIdentitiesSnapshot(values: List<R8ArtifactIdentity>) {
        requireValue(values.map { it.role } == canonicalArtifacts, "All five artifacts must be present canonically")
        values.forEachIndexed { index, value ->
            requireValue(value.ordinal == 0, "Artifact $index ordinal must be zero")
            val minimum = if (value.role == R8ArtifactRole.DEX_ZIP || value.role == R8ArtifactRole.MAPPING_TEXT || value.role == R8ArtifactRole.RETRACE_METADATA) 1L else 0L
            requireValue(value.sizeBytes in minimum..maximumFor(value.role), "Artifact $index size is invalid")
        }
    }

    fun validateRetraceMetadata(value: R8RetraceMetadata) {
        requireValue(value.formatId == R8CompilerContract.MAPPING_FORMAT_ID, "Mapping format ID is unsupported")
        requireText(value.formatVersion, 64, "Mapping format version")
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Compiler version")
        requireValue(value.minApi in R8CompilerContract.MIN_SUPPORTED_API..R8CompilerContract.MAX_SUPPORTED_API, "Minimum API is invalid")
        requireValue(value.profile == R8CompilerProfile.FULL_RELEASE, "Profile is invalid")
    }

    fun validateStarted(value: R8Started) {
        requireValue(value.sequence >= 0, "Sequence is invalid"); validateProtocol(value.protocolVersion)
        requireValue(value.compilerFamily == R8CompilerFamily.R8, "Compiler family must be R8")
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Compiler version")
        requireValue(value.profile == R8CompilerProfile.FULL_RELEASE, "Profile is invalid")
        requireValue(value.queueElapsedMillis >= 0, "Queue elapsed is invalid")
    }

    fun validateProgress(value: R8Progress) {
        requireValue(value.sequence >= 0, "Sequence is invalid")
        requireValue((value.current == null) == (value.total == null), "Progress range must be paired")
        if (value.current != null) requireValue(value.total!! >= 0 && value.current in 0..value.total, "Progress range is invalid")
    }
    fun validateProgressAgainst(value: R8Progress, request: R8CompileRequest) {
        validateProgress(value); validateRequest(request)
        capability(value.requestId == request.requestId, "Progress request ID mismatch")
    }

    /** Stateless cross-event check; a G2 callback state machine must also compare adjacent progress events. */
    fun validateProgressAfterStarted(
        value: R8Progress,
        started: R8Started,
        request: R8CompileRequest,
        caps: R8CompilerCapabilities,
    ) {
        validateProgressAgainst(value, request)
        validateStartedAgainst(started, request, caps)
        capability(value.sequence > started.sequence, "Progress sequence must follow started sequence")
    }

    fun validateResult(value: R8Result) {
        requireValue(value.compilerFamily == R8CompilerFamily.R8, "Compiler family must be R8")
        requireText(value.compilerVersion, R8CompilerContract.MAX_VERSION_TEXT_BYTES, "Compiler version")
        requireValue(value.profile == R8CompilerProfile.FULL_RELEASE, "Profile is invalid")
        requireValue(value.minApi in R8CompilerContract.MIN_SUPPORTED_API..R8CompilerContract.MAX_SUPPORTED_API, "Minimum API is invalid")
        requireValue(value.outputLayout == R8OutputLayout.DEX_AND_REPORTS_BUNDLE_V1, "Output layout is invalid")
        requireValue(value.outputBundleSizeBytes in 1..R8CompilerContract.MAX_OUTPUT_BUNDLE_BYTES, "Output bundle size is invalid")
        validateArtifactIdentitiesSnapshot(value.artifactIdentities)
        requireValue(value.outputBundleSizeBytes == R8ArtifactBundleCodec.encodedSize(value.artifactIdentities), "Output bundle size is not canonical")
        requireValue(value.determinismClaim == R8DeterminismClaim.NOT_CLAIMED, "Determinism claim is invalid")
        requireValue(value.elapsedMillis >= 0, "Elapsed time is invalid"); validateDiagnosticsSnapshot(value.diagnostics)
    }

    fun validateError(value: R8Error) {
        requireText(value.message, R8CompilerContract.MAX_DIAGNOSTIC_BYTES, "Error message")
        requireValue(value.elapsedMillis >= 0, "Elapsed time is invalid"); validateDiagnosticsSnapshot(value.diagnostics)
        requireValue(
            Math.addExact(utf8Size(value.message).toLong(), diagnosticBytes(value.diagnostics)) <=
                R8CompilerContract.MAX_DIAGNOSTIC_BYTES,
            "Error diagnostics exceed byte limit",
        )
    }

    fun validateCancellation(value: R8Cancellation) = requireValue(value.elapsedMillis >= 0, "Elapsed time is invalid")

    fun validateDiagnostics(values: List<R8Diagnostic>) {
        val diagnostics = boundedSnapshot(values, maximumDiagnosticCount, "Diagnostic")
        validateDiagnosticsSnapshot(diagnostics)
    }

    private fun validateDiagnosticsSnapshot(values: List<R8Diagnostic>) {
        requireValue(diagnosticBytes(values) <= R8CompilerContract.MAX_DIAGNOSTIC_BYTES, "Diagnostics exceed byte limit")
        values.forEach { requireValue(codePattern.matches(it.code), "Diagnostic code is invalid"); requireValue(it.message.isNotBlank(), "Diagnostic message is blank") }
    }

    private fun validateDiagnosticBudgetAgainst(
        values: List<R8Diagnostic>,
        request: R8CompileRequest,
        caps: R8CompilerCapabilities,
        label: String,
    ) = validateDiagnosticBudgetAgainst(diagnosticBytes(values), request, caps, label)

    private fun validateDiagnosticBudgetAgainst(
        bytes: Long,
        request: R8CompileRequest,
        caps: R8CompilerCapabilities,
        label: String,
    ) {
        capability(bytes <= request.diagnosticByteLimit.toLong(), "$label exceed request")
        capability(bytes <= caps.limits.maxDiagnosticBytes.toLong(), "$label exceed provider")
    }

    private fun diagnosticBytes(values: List<R8Diagnostic>): Long =
        sum(values.map { diagnostic ->
            Math.addExact(utf8Size(diagnostic.code).toLong(), utf8Size(diagnostic.message).toLong())
        })

    private fun validateLimits(v: R8ResourceLimits) {
        requireValue(v.maxProgramBytes in 1..R8CompilerContract.MAX_PROGRAM_BYTES, "Program limit is invalid")
        requireValue(v.maxClasspathCount in 0..R8CompilerContract.MAX_CLASSPATH_JARS, "Classpath count limit is invalid")
        requireValue(v.maxClasspathJarBytes in 1..R8CompilerContract.MAX_CLASSPATH_JAR_BYTES, "Classpath JAR limit is invalid")
        requireValue(v.maxTotalClasspathBytes in v.maxClasspathJarBytes..R8CompilerContract.MAX_TOTAL_CLASSPATH_BYTES, "Classpath total limit is invalid")
        requireValue(v.maxKeepRuleFiles in 1..R8CompilerContract.MAX_KEEP_RULE_FILES, "Keep-rule count limit is invalid")
        requireValue(v.maxConsumerRuleFiles in 0..R8CompilerContract.MAX_CONSUMER_RULE_FILES, "Consumer-rule count limit is invalid")
        requireValue(v.maxRuleFileBytes in 1..R8CompilerContract.MAX_RULE_FILE_BYTES, "Rule file limit is invalid")
        requireValue(v.maxTotalRuleBytes in v.maxRuleFileBytes..R8CompilerContract.MAX_TOTAL_RULE_BYTES, "Rule total limit is invalid")
        requireValue(v.maxRuleLinesPerFile in 1..R8CompilerContract.MAX_RULE_LINES_PER_FILE, "Rule line limit is invalid")
        requireValue(v.maxTotalRuleLines in v.maxRuleLinesPerFile..R8CompilerContract.MAX_TOTAL_RULE_LINES, "Rule total-line limit is invalid")
        requireValue(v.maxRuleLineBytes in 1..R8CompilerContract.MAX_RULE_LINE_BYTES, "Rule line byte limit is invalid")
        requireValue(v.maxInputBundleBytes in 186L..R8CompilerContract.MAX_INPUT_BUNDLE_BYTES, "Input bundle limit is invalid")
        requireValue(v.maxArchiveEntries in 1..R8CompilerContract.MAX_ARCHIVE_ENTRIES, "Archive limit is invalid")
        requireValue(v.maxTotalArchiveEntries in v.maxArchiveEntries..R8CompilerContract.MAX_TOTAL_ARCHIVE_ENTRIES, "Archive total limit is invalid")
        requireValue(v.maxUncompressedProgramBytes in 1..R8CompilerContract.MAX_UNCOMPRESSED_PROGRAM_BYTES, "Uncompressed program limit is invalid")
        requireValue(v.maxTotalUncompressedInputBytes in v.maxUncompressedProgramBytes..R8CompilerContract.MAX_TOTAL_UNCOMPRESSED_INPUT_BYTES, "Uncompressed total limit is invalid")
        requireValue(v.maxTotalClassBytes in 1..R8CompilerContract.MAX_TOTAL_CLASS_BYTES, "Class total limit is invalid")
        requireValue(v.maxSingleClassBytes in 1..minOf(v.maxTotalClassBytes, R8CompilerContract.MAX_SINGLE_CLASS_BYTES), "Single class limit is invalid")
        requireValue(v.maxOutputBundleBytes in R8CompilerContract.MIN_OUTPUT_BUNDLE_BYTES..R8CompilerContract.MAX_OUTPUT_BUNDLE_BYTES, "Output bundle limit is invalid")
        requireValue(v.maxDexZipBytes in 1..R8CompilerContract.MAX_DEX_ZIP_BYTES, "DEX ZIP limit is invalid")
        requireValue(v.maxMappingBytes in 1..R8CompilerContract.MAX_MAPPING_BYTES, "Mapping limit is invalid")
        requireValue(v.maxSeedsBytes in 1..R8CompilerContract.MAX_SEEDS_BYTES, "Seeds limit is invalid")
        requireValue(v.maxUsageBytes in 1..R8CompilerContract.MAX_USAGE_BYTES, "Usage limit is invalid")
        requireValue(v.maxRetraceMetadataBytes in 1..R8CompilerContract.MAX_RETRACE_METADATA_BYTES, "Metadata limit is invalid")
        requireValue(v.maxDexEntries in 1..R8CompilerContract.MAX_DEX_ENTRIES, "DEX entry limit is invalid")
        requireValue(v.maxDiagnosticBytes in 1..R8CompilerContract.MAX_DIAGNOSTIC_BYTES, "Diagnostic limit is invalid")
        requireValue(v.maxConcurrentSessions == R8CompilerContract.MAX_CONCURRENT_SESSIONS, "Session limit is invalid")
        requireValue(v.defaultTimeoutMillis in 1..v.maxTimeoutMillis && v.maxTimeoutMillis <= R8CompilerContract.MAX_TIMEOUT_MILLIS, "Timeout limits are invalid")
        requireValue(
            checkedAdd(v.maxProgramBytes, R8CompilerContract.MIN_INPUT_BUNDLE_OVERHEAD_WITH_KEEP) <= v.maxInputBundleBytes,
            "Program plus mandatory bundle framing exceeds input bundle",
        )
        requireValue(v.maxClasspathJarBytes <= v.maxTotalClasspathBytes && v.maxTotalClasspathBytes <= v.maxInputBundleBytes, "Classpath limits are inconsistent")
        requireValue(v.maxRuleFileBytes <= v.maxTotalRuleBytes && v.maxTotalRuleBytes <= v.maxInputBundleBytes, "Rule limits are inconsistent")
        requireValue(v.maxUncompressedProgramBytes <= v.maxTotalUncompressedInputBytes, "Uncompressed limits are inconsistent")
        requireValue(v.maxSingleClassBytes <= v.maxTotalClassBytes && v.maxTotalClassBytes <= v.maxTotalUncompressedInputBytes, "Class limits are inconsistent")
        requireValue(v.maxDexZipBytes <= v.maxOutputBundleBytes && v.maxMappingBytes <= v.maxOutputBundleBytes && v.maxSeedsBytes <= v.maxOutputBundleBytes && v.maxUsageBytes <= v.maxOutputBundleBytes && v.maxRetraceMetadataBytes <= v.maxOutputBundleBytes, "Artifact limits exceed output bundle")
    }

    private fun maximumFor(role: R8ArtifactRole): Long = when (role) {
        R8ArtifactRole.DEX_ZIP -> R8CompilerContract.MAX_DEX_ZIP_BYTES
        R8ArtifactRole.MAPPING_TEXT -> R8CompilerContract.MAX_MAPPING_BYTES
        R8ArtifactRole.SEEDS_TEXT -> R8CompilerContract.MAX_SEEDS_BYTES
        R8ArtifactRole.USAGE_TEXT -> R8CompilerContract.MAX_USAGE_BYTES
        R8ArtifactRole.RETRACE_METADATA -> R8CompilerContract.MAX_RETRACE_METADATA_BYTES
    }
    private fun maximumFor(limits: R8ResourceLimits, role: R8ArtifactRole): Long = when (role) {
        R8ArtifactRole.DEX_ZIP -> limits.maxDexZipBytes
        R8ArtifactRole.MAPPING_TEXT -> limits.maxMappingBytes
        R8ArtifactRole.SEEDS_TEXT -> limits.maxSeedsBytes
        R8ArtifactRole.USAGE_TEXT -> limits.maxUsageBytes
        R8ArtifactRole.RETRACE_METADATA -> limits.maxRetraceMetadataBytes
    }

    private fun validateProtocol(v: R8ProtocolVersion) = requireValue(v.major > 0 && v.minor >= 0, "Protocol version is invalid")
    private fun requireText(v: String, max: Int, label: String) { requireValue(v.isNotBlank() && utf8Size(v) <= max, "$label is invalid") }
    private fun utf8Size(v: String): Int = try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(v)).remaining() } catch (e: Exception) { invalid("Text is invalid Unicode", e) }
    private fun sum(values: Iterable<Long>): Long = try { values.fold(0L, Math::addExact) } catch (e: ArithmeticException) { invalid("Size total overflows", e) }
    private fun checkedAdd(left: Long, right: Long): Long = try { Math.addExact(left, right) } catch (e: ArithmeticException) { invalid("Size total overflows", e) }
    private fun requireValue(ok: Boolean, message: String, violation: R8ContractViolation = R8ContractViolation.INVALID_VALUE) { if (!ok) throw R8ContractException(violation, message) }
    private fun capability(ok: Boolean, message: String) = requireValue(ok, message, R8ContractViolation.CAPABILITY_INCOMPATIBLE)

    private const val maximumInputIdentityCount = 1 + R8CompilerContract.MAX_CLASSPATH_JARS +
        R8CompilerContract.MAX_KEEP_RULE_FILES + R8CompilerContract.MAX_CONSUMER_RULE_FILES
    private const val artifactCount = 5
    private const val maximumDiagnosticCount = 512
}
