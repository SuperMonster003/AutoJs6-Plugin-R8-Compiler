package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Repairs only the three newly introduced audit keys, and only after an explicit audit request. */
class AppearanceAuditRestoreTest {
    @Test fun restoreTheKnownPreAuditDefaults() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restoreAppearanceDefaults")=="true")
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish") || android.os.Build.MODEL.contains("sdk",true))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences(AppearanceChoice.PREFERENCES,Context.MODE_PRIVATE)
        assertTrue(prefs.edit().remove("language").remove("night").remove("color").commit())
        assertEquals(AppearanceChoice(),AppearanceChoice.read(context))
    }
}
