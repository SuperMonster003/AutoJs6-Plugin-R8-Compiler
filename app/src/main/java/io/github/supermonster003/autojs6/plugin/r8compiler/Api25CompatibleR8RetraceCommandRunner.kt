package io.github.supermonster003.autojs6.plugin.r8compiler

import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.references.ClassReference
import com.android.tools.r8.references.Reference
import com.android.tools.r8.retrace.ProguardMapProducer
import com.android.tools.r8.retrace.ProguardMappingSupplier
import com.android.tools.r8.retrace.Retrace
import com.android.tools.r8.retrace.RetraceStackTraceContext
import com.android.tools.r8.retrace.RetraceStackTraceElementProxy
import com.android.tools.r8.retrace.StackTraceElementProxy
import com.android.tools.r8.retrace.StackTraceLineParser
import java.util.regex.Pattern

/**
 * R8 8.13.17's string parser calls Matcher.start(String), which Android added in API 26.
 * This parser uses only numbered matcher groups while retaining R8's public proxy retracer.
 */
internal object Api25CompatibleR8RetraceCommandRunner : R8RetraceCommandRunner {
    override fun run(
        mappingBytes: ByteArray,
        obfuscatedStackTrace: List<String>,
        diagnostics: DiagnosticsHandler,
    ): List<String> {
        val mappingSupplier = ProguardMappingSupplier.builder()
            .setProguardMapProducer(ProguardMapProducer.fromBytes(mappingBytes))
            .setLoadAllDefinitions(true)
            .build()
        val retrace = Retrace.builder<String, Api25StackTraceElementProxy>()
            .setStackTraceLineParser(Api25StackTraceLineParser)
            .setMappingSupplier(mappingSupplier)
            .setDiagnosticsHandler(diagnostics)
            .setVerbose(false)
            .build()
        val result = retrace.retraceStackTrace(
            obfuscatedStackTrace,
            RetraceStackTraceContext.empty(),
        )
        return joinAmbiguousLines(result.result)
    }

    private fun joinAmbiguousLines(
        retraced: List<com.android.tools.r8.retrace.RetraceStackFrameAmbiguousResult<String>>,
    ): List<String> {
        val result = ArrayList<String>()
        retraced.forEach { ambiguousResult ->
            var lineIndex = 0
            while (true) {
                var addedLine = false
                val reportedFrames = HashSet<String>()
                val alternatives = ambiguousResult.ambiguousResult
                val first = alternatives.firstOrNull()
                alternatives.forEach { inlineFrames ->
                    if (lineIndex < inlineFrames.size()) {
                        addedLine = true
                        val frame = inlineFrames.get(lineIndex)
                        if (reportedFrames.add(frame)) {
                            result += if (inlineFrames === first) frame else insertOr(frame)
                        }
                    }
                }
                if (!addedLine) break
                lineIndex += 1
            }
        }
        return result
    }

    private fun insertOr(stackTraceLine: String): String {
        var index = stackTraceLine.indexOf("at ")
        if (index < 0) {
            index = stackTraceLine.indexOfFirst { character -> !character.isWhitespace() }
                .coerceAtLeast(0)
        }
        return stackTraceLine.substring(0, index) + "<OR> " + stackTraceLine.substring(index)
    }
}

private object Api25StackTraceLineParser :
    StackTraceLineParser<String, Api25StackTraceElementProxy> {
    private val framePattern = Pattern.compile(
        "^(\\s*at\\s+)((?:[^\\s/]+/)*)([^\\s/()]+)\\.([^\\s.(]+)\\(([^():]*)(?::(\\d+))?\\)(.*)$",
    )

    override fun parse(line: String): Api25StackTraceElementProxy {
        val matcher = framePattern.matcher(line)
        if (!matcher.matches()) return Api25StackTraceElementProxy.unparsed(line)
        val lineNumberStart = matcher.start(6).takeIf { it >= 0 }?.let { it - 1 } ?: -1
        return Api25StackTraceElementProxy(
            line = line,
            classStart = matcher.start(3),
            classEnd = matcher.end(3),
            methodStart = matcher.start(4),
            methodEnd = matcher.end(4),
            sourceStart = matcher.start(5),
            sourceEnd = matcher.end(5),
            lineNumberStart = lineNumberStart,
            lineNumberEnd = matcher.end(6),
        )
    }
}

private class Api25StackTraceElementProxy(
    private val line: String,
    private val classStart: Int,
    private val classEnd: Int,
    private val methodStart: Int,
    private val methodEnd: Int,
    private val sourceStart: Int,
    private val sourceEnd: Int,
    private val lineNumberStart: Int,
    private val lineNumberEnd: Int,
) : StackTraceElementProxy<String, Api25StackTraceElementProxy>() {
    private val parsed: Boolean
        get() = classStart >= 0

    override fun hasClassName(): Boolean = parsed

    override fun hasMethodName(): Boolean = parsed

    override fun hasSourceFile(): Boolean = parsed

    override fun hasLineNumber(): Boolean = lineNumberStart >= 0

    override fun hasFieldName(): Boolean = false

    override fun hasFieldOrReturnType(): Boolean = false

    override fun hasMethodArguments(): Boolean = false

    override fun getClassReference(): ClassReference =
        Reference.classFromTypeName(line.substring(classStart, classEnd))

    override fun getMethodName(): String = line.substring(methodStart, methodEnd)

    override fun getSourceFile(): String = line.substring(sourceStart, sourceEnd)

    override fun getLineNumber(): Int = if (hasLineNumber()) {
        line.substring(lineNumberStart + 1, lineNumberEnd).toIntOrNull() ?: -1
    } else {
        -1
    }

    override fun getFieldName(): String = error("A Java stack frame has no field name")

    override fun getFieldOrReturnType(): String = error("A Java stack frame has no return type")

    override fun getMethodArguments(): String = error("A Java stack frame has no method arguments")

    override fun toRetracedItem(
        retracedProxy: RetraceStackTraceElementProxy<String, Api25StackTraceElementProxy>,
        verbose: Boolean,
    ): String {
        if (!parsed) return line
        val replacements = ArrayList<Replacement>(4)
        if (retracedProxy.hasRetracedClass()) {
            replacements += Replacement(
                classStart,
                classEnd,
                retracedProxy.retracedClass.typeName,
            )
        }
        if (retracedProxy.hasRetracedMethod()) {
            replacements += Replacement(
                methodStart,
                methodEnd,
                retracedProxy.retracedMethod.methodName,
            )
        }
        if (retracedProxy.hasSourceFile()) {
            replacements += Replacement(
                sourceStart,
                sourceEnd,
                retracedProxy.sourceFile,
            )
        }
        if (hasLineNumber() && retracedProxy.hasLineNumber()) {
            val retracedLine = retracedProxy.lineNumber
            replacements += Replacement(
                lineNumberStart,
                lineNumberEnd,
                if (retracedLine > 0) ":$retracedLine" else "",
            )
        }
        val output = StringBuilder(line.length + 32)
        var cursor = 0
        replacements.sortedBy(Replacement::start).forEach { replacement ->
            output.append(line, cursor, replacement.start)
            output.append(replacement.value)
            cursor = replacement.end
        }
        output.append(line, cursor, line.length)
        return output.toString()
    }

    private data class Replacement(
        val start: Int,
        val end: Int,
        val value: String,
    )

    companion object {
        fun unparsed(line: String) = Api25StackTraceElementProxy(
            line = line,
            classStart = -1,
            classEnd = -1,
            methodStart = -1,
            methodEnd = -1,
            sourceStart = -1,
            sourceEnd = -1,
            lineNumberStart = -1,
            lineNumberEnd = -1,
        )
    }
}
