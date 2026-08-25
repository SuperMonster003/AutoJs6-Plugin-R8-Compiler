package io.github.supermonster003.autojs6.plugin.r8compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class PrivateSessionWorkspaceTest {
    @Test
    fun closeDeletesOnlyTheAllocatedSessionTree() {
        val root = Files.createTempDirectory("r8-workspace-").toFile()
        try {
            val sessions = root.resolve("sessions")
            val sentinel = root.resolve("sentinel").apply { writeText("keep") }
            val workspace = PrivateSessionWorkspace.createUnder(sessions)
            workspace.programJar.writeText("private")
            assertTrue(sessions.listFiles().orEmpty().single().isDirectory)
            workspace.close()
            assertTrue(sentinel.isFile)
            assertTrue(sessions.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun startupRecoveryDeletesCanonicalStaleSessionsButNotUnknownEntries() {
        val root = Files.createTempDirectory("r8-recovery-").toFile()
        try {
            val sessions = root.resolve("sessions").apply { mkdirs() }
            val stale = sessions.resolve("session-00000000-0000-0000-0000-000000000000").apply { mkdir() }
            stale.resolve("artifact.bundle").writeText("partial")
            val unknown = sessions.resolve("do-not-delete").apply { mkdir() }
            PrivateSessionWorkspace.recoverStaleUnder(sessions)
            assertFalse(stale.exists())
            assertTrue(unknown.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun processRecoveryRunsOnlyAfterACompleteSuccessfulAttempt() {
        val recovery = ProcessWorkspaceRecovery()
        var calls = 0
        try {
            recovery.ensureRecovered {
                calls++
                throw IOException("first attempt")
            }
        } catch (_: IOException) {
            Unit
        }
        recovery.ensureRecovered { calls++ }
        recovery.ensureRecovered { calls++ }
        assertEquals(2, calls)
    }

    @Test
    fun sessionGateUsesExactOwnerIdentity() {
        val gate = SingleActiveSessionGate<Any>()
        val first = Any()
        val equalButDifferent = Any()
        assertTrue(gate.tryAcquire(first))
        assertFalse(gate.release(equalButDifferent))
        assertFalse(gate.tryAcquire(equalButDifferent))
        assertTrue(gate.release(first))
        assertTrue(gate.tryAcquire(equalButDifferent))
    }
}
