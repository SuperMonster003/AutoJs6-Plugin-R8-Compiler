package io.github.supermonster003.autojs6.plugin.r8compiler

import com.android.tools.r8.Diagnostic
import com.android.tools.r8.origin.Origin
import com.android.tools.r8.position.Position
import org.autojs.plugin.r8compiler.api.R8DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class R8DiagnosticCollectorTest {
    @Test
    fun actionableR8MessageIsPreservedWithoutOriginOrPosition() {
        val collector = R8DiagnosticCollector(maximumBytes = 4 * 1024)
        collector.error(
            diagnostic(
                message = "Missing class java.lang.invoke.StringConcatFactory " +
                    "(referenced from: void sample.Entry.run())",
            ),
        )

        val value = collector.snapshot().single()
        assertEquals(R8DiagnosticSeverity.ERROR, value.severity)
        assertEquals("R8_ERROR", value.code)
        assertEquals(
            "Missing class java.lang.invoke.StringConcatFactory " +
                "(referenced from: void sample.Entry.run())",
            value.message,
        )
        assertFalse(value.message.contains("origin-secret"))
        assertFalse(value.message.contains("position-secret"))
    }

    @Test
    fun absolutePathsAndControlsAreRedactedToOneLine() {
        val collector = R8DiagnosticCollector(maximumBytes = 4 * 1024)
        collector.warning(
            diagnostic(
                "Failed /data/user/0/private/files/program.jar and " +
                    "C:\\private\\rules.pro and file:/storage/emulated/0/input.jar\r\nnext\u0000line",
            ),
        )

        val message = collector.snapshot().single().message
        assertEquals("Failed <path> and <path> and <path> next line", message)
        assertFalse(message.contains('/'))
        assertFalse(message.contains('\\'))
        assertFalse(message.contains('\n'))
        assertFalse(message.contains('\r'))
    }

    @Test
    fun individualMessagesAreUtf8BoundedWithoutSplittingCodePoints() {
        val collector = R8DiagnosticCollector(maximumBytes = 8 * 1024)
        collector.info(diagnostic("界".repeat(4 * 1024)))

        val message = collector.snapshot().single().message
        assertTrue(message.toByteArray(StandardCharsets.UTF_8).size <= R8DiagnosticSanitizer.MAX_MESSAGE_BYTES)
        assertTrue(message.all { it == '界' })
    }

    @Test
    fun aggregateBudgetStillFailsClosed() {
        val collector = R8DiagnosticCollector(maximumBytes = 8)
        collector.error(diagnostic("detail"))

        assertTrue(collector.snapshot().isEmpty())
    }

    @Test
    fun diagnosticMessageFailureUsesTheExistingGenericFallback() {
        val collector = R8DiagnosticCollector(maximumBytes = 4 * 1024)
        collector.error(
            object : Diagnostic {
                override fun getOrigin(): Origin = error("origin must not be queried")
                override fun getPosition(): Position = error("position must not be queried")
                override fun getDiagnosticMessage(): String = error("message unavailable")
            },
        )

        assertEquals("R8 reported a compilation error", collector.snapshot().single().message)
    }

    private fun diagnostic(message: String): Diagnostic = object : Diagnostic {
        override fun getOrigin(): Origin = object : Origin(Origin.unknown()) {
            override fun part(): String = "origin-secret"
        }

        override fun getPosition(): Position = Position { "position-secret" }

        override fun getDiagnosticMessage(): String = message
    }
}
