package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class R8AidlGoldenTest {
    @Test fun exactAidlSetNormalizedHashesAndLifecycleLawAreFrozen() {
        val root = File(checkNotNull(System.getProperty("r8.aidl.root")))
        val files = root.walkTopDown().filter { it.isFile && it.extension == "aidl" }.toList().sortedBy { it.name }
        val expected = linkedMapOf(
            "IR8CompilerCallback.aidl" to "ee1749ec69fda76d0b71d8ee726eb84fe871bb7bbdff53031a787bc3d9da2529",
            "IR8CompilerProvider.aidl" to "f501348baec7fe4fc6858ef0496a61c64c6e75f6e585718ee9572d4a6f3ceff4",
            "IR8CompilerSession.aidl" to "cb98259298650c0c1789017da3aa1e077484f7b76332d3a5d16cca5c45708882",
        )
        assertEquals(expected.keys.toList(), files.map { it.name })
        files.forEach { file ->
            val normalized = file.readText().replace("\r\n", "\n").replace('\r', '\n')
            assertEquals(expected.getValue(file.name), R8Fixtures.sha256Hex(normalized.toByteArray(StandardCharsets.UTF_8)))
        }
        val text = files.joinToString("\n") { it.readText() }
        assertTrue(text.contains("IR8CompilerSession openSession"))
        assertFalse(text.contains("openRetraceSession"))
        assertTrue(text.contains("At most one started event"))
        assertTrue(text.contains("Progress sequence values are strictly increasing"))
        assertTrue(text.contains("greater than its sequence"))
        assertTrue(text.contains("Exactly one terminal"))
        assertTrue(text.contains("idempotent"))
        assertTrue(text.contains("caller retains and closes its local PFD instances"))
        assertTrue(text.contains("provider exclusively owns its received duplicates"))
        assertTrue(text.contains("must not alias the same endpoint"))
        assertTrue(text.contains("never redirected to D8"))
    }
}
