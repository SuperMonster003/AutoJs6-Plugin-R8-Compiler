package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LauncherEntryRemovalTest {
    @Test fun noLauncherEntryRemainsEvenWhenDisabledComponentsAreIncluded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS).isEmpty())
        assertNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val service = Intent("org.autojs.plugin.R8_COMPILER").setPackage(context.packageName)
        assertEquals(1, context.packageManager.queryIntentServices(service, 0).size)
    }

    @Test fun settingsNoLongerOfferALauncherIconChoice() {
        ActivityScenario.launch(AppSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(activity.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<android.view.View>("launcher-icon"))
            }
        }
    }
}
