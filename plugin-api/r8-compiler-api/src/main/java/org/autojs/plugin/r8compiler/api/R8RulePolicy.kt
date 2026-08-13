package org.autojs.plugin.r8compiler.api

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class R8RulePolicyError {
    INVALID_UTF8, INVALID_CONTROL, EMPTY, MISSING_FINAL_NEWLINE, CONTINUATION,
    LIMIT_EXCEEDED, UNBALANCED_SYNTAX, MALFORMED_DIRECTIVE, FORBIDDEN_DIRECTIVE,
    UNKNOWN_DIRECTIVE, INVALID_MODIFIER, MISSING_KEEP_RULE,
}

class R8RulePolicyException(val error: R8RulePolicyError, message: String) : IllegalArgumentException(message)

data class R8RulePolicySummary(val lineCount: Int, val directiveCount: Int, val keepDirectiveCount: Int)
data class R8RuleAdmissionLimits(
    val maxKeepRuleFiles: Int,
    val maxConsumerRuleFiles: Int,
    val maxRuleFileBytes: Long,
    val maxTotalRuleBytes: Long,
    val maxRuleLinesPerFile: Int,
    val maxTotalRuleLines: Int,
    val maxRuleLineBytes: Int,
) {
    init {
        require(maxKeepRuleFiles in 1..R8CompilerContract.MAX_KEEP_RULE_FILES)
        require(maxConsumerRuleFiles in 0..R8CompilerContract.MAX_CONSUMER_RULE_FILES)
        require(maxRuleFileBytes in 1..R8CompilerContract.MAX_RULE_FILE_BYTES)
        require(maxTotalRuleBytes in maxRuleFileBytes..R8CompilerContract.MAX_TOTAL_RULE_BYTES)
        require(maxRuleLinesPerFile in 1..R8CompilerContract.MAX_RULE_LINES_PER_FILE)
        require(maxTotalRuleLines in maxRuleLinesPerFile..R8CompilerContract.MAX_TOTAL_RULE_LINES)
        require(maxRuleLineBytes in 1..R8CompilerContract.MAX_RULE_LINE_BYTES)
    }

    companion object {
        fun protocol() = R8RuleAdmissionLimits(
            R8CompilerContract.MAX_KEEP_RULE_FILES, R8CompilerContract.MAX_CONSUMER_RULE_FILES,
            R8CompilerContract.MAX_RULE_FILE_BYTES, R8CompilerContract.MAX_TOTAL_RULE_BYTES,
            R8CompilerContract.MAX_RULE_LINES_PER_FILE, R8CompilerContract.MAX_TOTAL_RULE_LINES,
            R8CompilerContract.MAX_RULE_LINE_BYTES,
        )
        fun from(value: R8ResourceLimits) = R8RuleAdmissionLimits(
            value.maxKeepRuleFiles, value.maxConsumerRuleFiles, value.maxRuleFileBytes,
            value.maxTotalRuleBytes, value.maxRuleLinesPerFile, value.maxTotalRuleLines,
            value.maxRuleLineBytes,
        )
    }
}

object R8RulePolicy {
    private val keepDirectives = setOf(
        "keep", "keepnames", "keepclassmembers", "keepclassmembernames",
        "keepclasseswithmembers", "keepclasseswithmembernames",
    )
    private val allowed = keepDirectives + setOf("keeppackagenames", "if", "keepattributes", "dontwarn", "dontnote")
    private val modifiers = setOf(
        "allowshrinking", "allowoptimization", "allowobfuscation", "allowaccessmodification",
        "includedescriptorclasses", "includecode",
    )
    private val forbidden = setOf(
        "include", "basedirectory", "injars", "outjars", "libraryjars", "applymapping",
        "obfuscationdictionary", "classobfuscationdictionary", "packageobfuscationdictionary",
        "printmapping", "printseeds", "printusage", "printconfiguration", "dump",
        "dontshrink", "dontoptimize", "dontobfuscate", "forceprocessing", "optimizationpasses",
        "optimizations", "target", "repackageclasses", "flattenpackagehierarchy",
        "allowaccessmodification", "assumenosideeffects", "assumevalues",
    )

    fun validateFile(
        bytes: ByteArray,
        requireKeepDirective: Boolean,
        limits: R8RuleAdmissionLimits = R8RuleAdmissionLimits.protocol(),
    ): R8RulePolicySummary {
        failIf(bytes.isEmpty(), R8RulePolicyError.EMPTY, "Rule file is empty")
        failIf(bytes.size > limits.maxRuleFileBytes, R8RulePolicyError.LIMIT_EXCEEDED, "Rule file exceeds byte limit")
        val text = decode(bytes)
        failIf(text.startsWith('\uFEFF'), R8RulePolicyError.INVALID_CONTROL, "BOM is forbidden")
        failIf(!text.endsWith('\n'), R8RulePolicyError.MISSING_FINAL_NEWLINE, "Final LF is required")
        failIf(text.contains("\\\n"), R8RulePolicyError.CONTINUATION, "Line continuations are forbidden")
        text.forEach { ch ->
            if ((ch.code in 0..31 || ch.code == 127) && ch != '\t' && ch != '\n') {
                fail(R8RulePolicyError.INVALID_CONTROL, "Control character is forbidden")
            }
        }
        val lines = text.dropLast(1).split('\n')
        failIf(lines.size > limits.maxRuleLinesPerFile, R8RulePolicyError.LIMIT_EXCEEDED, "Rule line count exceeds limit")
        var directives = 0
        var keeps = 0
        var depth = 0
        lines.forEachIndexed { index, line ->
            failIf(line.toByteArray(StandardCharsets.UTF_8).size > limits.maxRuleLineBytes, R8RulePolicyError.LIMIT_EXCEEDED, "Rule line ${index + 1} exceeds byte limit")
            val outcome = scanLine(line, index + 1, depth)
            depth = outcome.depth
            outcome.directive?.let { directive -> directives++; if (directive in keepDirectives) keeps++ }
        }
        failIf(depth != 0, R8RulePolicyError.UNBALANCED_SYNTAX, "Rule braces are unbalanced")
        failIf(directives == 0, R8RulePolicyError.EMPTY, "Rule file has no directives")
        if (requireKeepDirective) failIf(keeps == 0, R8RulePolicyError.MISSING_KEEP_RULE, "Keep-rule input has no keep directive")
        return R8RulePolicySummary(lines.size, directives, keeps)
    }

    fun validateAggregate(
        files: Collection<Pair<R8InputRole, ByteArray>>,
        limits: R8RuleAdmissionLimits = R8RuleAdmissionLimits.protocol(),
    ) {
        val maximumFileCount = R8CompilerContract.MAX_KEEP_RULE_FILES + R8CompilerContract.MAX_CONSUMER_RULE_FILES
        val admittedFiles = boundedSnapshot(files, maximumFileCount, "Rule file")
        failIf(admittedFiles.isEmpty(), R8RulePolicyError.EMPTY, "No rule files supplied")
        failIf(admittedFiles.count { it.first == R8InputRole.KEEP_RULES } > limits.maxKeepRuleFiles, R8RulePolicyError.LIMIT_EXCEEDED, "Keep-rule count exceeds limit")
        failIf(admittedFiles.count { it.first == R8InputRole.CONSUMER_RULES } > limits.maxConsumerRuleFiles, R8RulePolicyError.LIMIT_EXCEEDED, "Consumer-rule count exceeds limit")
        var bytes = 0L; var lines = 0; var keepsFromKeepFiles = 0; var keepFileCount = 0
        admittedFiles.forEach { (role, payload) ->
            failIf(role != R8InputRole.KEEP_RULES && role != R8InputRole.CONSUMER_RULES, R8RulePolicyError.MALFORMED_DIRECTIVE, "Non-rule input supplied")
            bytes = try { Math.addExact(bytes, payload.size.toLong()) } catch (_: ArithmeticException) { fail(R8RulePolicyError.LIMIT_EXCEEDED, "Rule bytes overflow") }
            val summary = validateFile(payload, requireKeepDirective = false, limits = limits)
            lines += summary.lineCount
            if (role == R8InputRole.KEEP_RULES) { keepFileCount++; keepsFromKeepFiles += summary.keepDirectiveCount }
        }
        failIf(bytes > limits.maxTotalRuleBytes, R8RulePolicyError.LIMIT_EXCEEDED, "Rule bytes exceed aggregate limit")
        failIf(lines > limits.maxTotalRuleLines, R8RulePolicyError.LIMIT_EXCEEDED, "Rule lines exceed aggregate limit")
        failIf(keepFileCount == 0 || keepsFromKeepFiles == 0, R8RulePolicyError.MISSING_KEEP_RULE, "KEEP_RULES has no keep directive")
    }

    private data class ScanOutcome(val directive: String?, val depth: Int)

    private fun scanLine(line: String, lineNumber: Int, initialDepth: Int): ScanOutcome {
        var i = line.indexOfFirst { !it.isWhitespace() }
        if (i < 0 || line[i] == '#') return ScanOutcome(null, initialDepth)
        if (initialDepth > 0) return ScanOutcome(null, scanBalancedTail(line, i, lineNumber, initialDepth))
        failIf(line[i] == '@', R8RulePolicyError.FORBIDDEN_DIRECTIVE, "@file is forbidden on line $lineNumber")
        failIf(line[i] != '-', R8RulePolicyError.MALFORMED_DIRECTIVE, "Directive must begin at line start on line $lineNumber")
        i++
        val start = i
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        failIf(i == start, R8RulePolicyError.MALFORMED_DIRECTIVE, "Directive is missing on line $lineNumber")
        val directive = line.substring(start, i).lowercase()
        if (directive in forbidden) fail(R8RulePolicyError.FORBIDDEN_DIRECTIVE, "Directive -$directive is forbidden")
        if (directive !in allowed) fail(R8RulePolicyError.UNKNOWN_DIRECTIVE, "Directive -$directive is not allowed")
        if (i < line.length && line[i] == ',') {
            failIf(directive !in keepDirectives, R8RulePolicyError.INVALID_MODIFIER, "Modifiers are not allowed for -$directive")
            val end = line.indexOfFirstFrom(i) { it.isWhitespace() || it == '{' || it == '#' }.let { if (it < 0) line.length else it }
            val values = line.substring(i + 1, end).split(',')
            failIf(values.isEmpty() || values.any { it.isEmpty() || it !in modifiers } || values.size != values.toSet().size, R8RulePolicyError.INVALID_MODIFIER, "Keep modifiers are invalid")
            i = end
        }
        return ScanOutcome(directive, scanBalancedTail(line, i, lineNumber, initialDepth))
    }

    private fun scanBalancedTail(line: String, from: Int, lineNumber: Int, initialDepth: Int): Int {
        var quote: Char? = null; var escaped = false; var depth = initialDepth; var i = from
        while (i < line.length) {
            val ch = line[i]
            if (escaped) { escaped = false; i++; continue }
            if (ch == '\\') { escaped = true; i++; continue }
            if (quote != null) { if (ch == quote) quote = null; i++; continue }
            if (ch == '\'' || ch == '"') { quote = ch; i++; continue }
            if (ch == '#') break
            if (depth == 0 && ch == ';') fail(R8RulePolicyError.MALFORMED_DIRECTIVE, "Directive separator is forbidden on line $lineNumber")
            if (ch == '{') depth++
            if (ch == '}') { depth--; failIf(depth < 0, R8RulePolicyError.UNBALANCED_SYNTAX, "Unbalanced brace on line $lineNumber") }
            if (depth == 0 && (ch == '@' || ch == '-')) {
                fail(R8RulePolicyError.MALFORMED_DIRECTIVE, "Second directive token on line $lineNumber")
            }
            i++
        }
        failIf(escaped || quote != null, R8RulePolicyError.UNBALANCED_SYNTAX, "Unbalanced syntax on line $lineNumber")
        return depth
    }

    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { fail(R8RulePolicyError.INVALID_UTF8, "Rule file is not strict UTF-8") }

    private inline fun failIf(value: Boolean, error: R8RulePolicyError, message: String) { if (value) fail(error, message) }
    private fun fail(error: R8RulePolicyError, message: String): Nothing = throw R8RulePolicyException(error, message)
    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return -1
    }
}
