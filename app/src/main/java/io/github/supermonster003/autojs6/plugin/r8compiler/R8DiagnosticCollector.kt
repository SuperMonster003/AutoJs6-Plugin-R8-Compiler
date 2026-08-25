package io.github.supermonster003.autojs6.plugin.r8compiler

import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import org.autojs.plugin.r8compiler.api.R8Diagnostic
import org.autojs.plugin.r8compiler.api.R8DiagnosticSeverity
import java.nio.charset.StandardCharsets

/** Keeps diagnostics bounded and path-redacted; private workspace origins are never serialized. */
internal class R8DiagnosticCollector(
    private val maximumBytes: Int,
) : DiagnosticsHandler {
    private val lock = Any()
    private val values = ArrayList<R8Diagnostic>()
    private var usedBytes = 0

    override fun error(diagnostic: Diagnostic) = record(R8DiagnosticSeverity.ERROR, "R8_ERROR", diagnostic)

    override fun warning(diagnostic: Diagnostic) = record(R8DiagnosticSeverity.WARNING, "R8_WARNING", diagnostic)

    override fun info(diagnostic: Diagnostic) = record(R8DiagnosticSeverity.INFO, "R8_INFO", diagnostic)

    fun snapshot(): List<R8Diagnostic> = synchronized(lock) { values.toList() }

    private fun record(severity: R8DiagnosticSeverity, code: String, diagnostic: Diagnostic) {
        val fallback = when (severity) {
            R8DiagnosticSeverity.ERROR -> "R8 reported a compilation error"
            R8DiagnosticSeverity.WARNING -> "R8 reported a compilation warning"
            R8DiagnosticSeverity.INFO -> "R8 reported compiler information"
        }
        val message = runCatching { R8DiagnosticSanitizer.sanitize(diagnostic.diagnosticMessage) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: fallback
        val cost = code.toByteArray(StandardCharsets.UTF_8).size +
            message.toByteArray(StandardCharsets.UTF_8).size
        synchronized(lock) {
            if (values.size >= MAX_DIAGNOSTICS || usedBytes > maximumBytes - cost) return
            values += R8Diagnostic(severity, code, message)
            usedBytes += cost
        }
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 512
    }
}

/**
 * Retains actionable compiler text without exposing either R8's origin/position objects or an
 * absolute host/device path that R8 may have embedded in the message itself.
 */
internal object R8DiagnosticSanitizer {
    private const val MAX_RAW_CHARACTERS = 8 * 1024
    internal const val MAX_MESSAGE_BYTES = 2 * 1024
    private const val REDACTED_PATH = "<path>"

    private val fileUri = Regex("""(?i)\bfile:(?:/{1,3}|\\\\)[^\s\"'<>]+""")
    private val windowsAbsolutePath = Regex("""(?i)(?<![A-Za-z0-9_])(?:[A-Z]:[\\/]|\\\\)[^\s\"'<>]+""")
    private val unixAbsolutePath = Regex("""(?<![A-Za-z0-9_:])/(?:[^\s\"'<>])+""")

    fun sanitize(rawMessage: String?): String {
        if (rawMessage == null) return ""
        val redacted = rawMessage
            .take(MAX_RAW_CHARACTERS)
            .replace(fileUri, REDACTED_PATH)
            .replace(windowsAbsolutePath, REDACTED_PATH)
            .replace(unixAbsolutePath, REDACTED_PATH)
        val singleLine = buildString(redacted.length.coerceAtMost(MAX_MESSAGE_BYTES)) {
            var pendingSpace = false
            redacted.forEach { character ->
                when {
                    character.isWhitespace() || Character.isISOControl(character) -> pendingSpace = isNotEmpty()
                    else -> {
                        if (pendingSpace) append(' ')
                        append(character)
                        pendingSpace = false
                    }
                }
            }
        }.trim()
        return truncateUtf8(singleLine, MAX_MESSAGE_BYTES)
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        var index = 0
        var usedBytes = 0
        val result = StringBuilder(value.length.coerceAtMost(maximumBytes))
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(StandardCharsets.UTF_8).size
            if (usedBytes > maximumBytes - bytes) break
            result.append(text)
            usedBytes += bytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
