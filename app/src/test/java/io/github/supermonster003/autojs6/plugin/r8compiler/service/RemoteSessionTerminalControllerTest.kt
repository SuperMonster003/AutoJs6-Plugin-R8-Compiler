package io.github.supermonster003.autojs6.plugin.r8compiler.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionTerminalControllerTest {
    @Test
    fun exactlyOneTerminalOwnsDispatchAndCleanup() {
        var stops = 0
        var cleanups = 0
        val dispatched = ArrayList<String>()
        val controller = RemoteSessionTerminalController({ stops++ }, { cleanups++ })
        assertTrue(controller.dispatchTerminal(encode = { "complete" }, dispatch = dispatched::add))
        assertFalse(controller.dispatchTerminal(encode = { "failed" }, dispatch = dispatched::add))
        assertEquals(listOf("complete"), dispatched)
        assertEquals(0, stops)
        assertTrue(controller.cleanupOnce())
        assertFalse(controller.cleanupOnce())
        assertEquals(1, cleanups)
    }

    @Test
    fun cancellationStopsAtMostOnceAndWorkerRetainsCleanupOwnership() {
        var stops = 0
        var cleanups = 0
        val controller = RemoteSessionTerminalController({ stops++ }, { cleanups++ })
        assertTrue(controller.dispatchTerminal(
            stopWorkAfterDispatch = true,
            encode = { "cancel" },
            dispatch = {},
        ))
        assertFalse(controller.abort())
        assertEquals(1, stops)
        assertEquals(0, cleanups)
        controller.cleanupOnce()
        assertEquals(1, cleanups)
    }

    @Test
    fun preWorkerFailureStillStopsAndCleans() {
        var stops = 0
        var cleanups = 0
        val controller = RemoteSessionTerminalController({ stops++ }, { cleanups++ })
        try {
            controller.runBeforeWorker<Unit> { throw IllegalStateException("link failed") }
        } catch (_: IllegalStateException) {
            Unit
        }
        assertTrue(controller.isTerminal)
        assertEquals(1, stops)
        assertEquals(1, cleanups)
    }
}
