package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Process
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LauncherIconSelectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun confirmedChoicesKeepOneEntryAndCanceledChoicesDoNotApply() {
        LauncherIcons.normalize(context)
        val pm=context.packageManager
        val before=LauncherIconMode.entries.associateWith { pm.getComponentEnabledSetting(it.component(context)) }
        val previous=LauncherIcons.current(context)
        val pid=Process.myPid()
        val shortcutId="settings-test-${UUID.randomUUID()}"
        val manager=if(Build.VERSION.SDK_INT>=25) context.getSystemService(ShortcutManager::class.java) else null
        try {
            ActivityScenario.launch<AppSettingsActivity>(Intent(context,AppSettingsActivity::class.java)).use { scenario ->
                manager?.let {
                    assertTrue(it.addDynamicShortcuts(listOf(ShortcutInfo.Builder(context,shortcutId).setShortLabel("Settings test")
                        .setActivity(previous.component(context)).setIntent(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW)).build())))
                }
                for(mode in LauncherIconMode.entries) {
                    val prior=LauncherIcons.current(context)
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("launcher-icon").performClick()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        val dialog=activity.prompt!!
                        for(index in 0..3) assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("choice-$index"))
                        dialog.window!!.decorView.findViewWithTag<View>("choice-${mode.ordinal}").performClick()
                        assertEquals(prior,LauncherIcons.current(context))
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                        assertEquals(prior,LauncherIcons.current(context))
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity -> activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("launcher-icon").performClick() }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        activity.prompt!!.window!!.decorView.findViewWithTag<View>("choice-${mode.ordinal}").performClick()
                        activity.prompt!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertEquals(mode,LauncherIcons.current(context));assertEquals(pid,Process.myPid())
                    val matches=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),0)
                    assertEquals(1,matches.size);assertEquals(mode.component(context).className,matches.single().activityInfo.name)
                    assertEquals(MainActivity::class.java.name,matches.single().activityInfo.targetActivity)
                    val icons=intArrayOf(R.mipmap.ic_launcher_system_light,R.mipmap.ic_launcher_system,R.mipmap.ic_launcher_system_auto,R.mipmap.ic_launcher_transparent)
                    assertEquals(icons[mode.ordinal],matches.single().activityInfo.icon)
                    manager?.dynamicShortcuts?.single { it.id==shortcutId }?.let {
                        assertEquals(mode.component(context),it.activity);assertEquals(MainActivity::class.java.name,it.intent!!.component!!.className)
                    }
                    scenario.recreate()
                    scenario.onActivity { assertEquals(mode,LauncherIcons.current(it)) }
                }
            }
        } finally {
            manager?.removeDynamicShortcuts(listOf(shortcutId))
            LauncherIcons.select(context,previous)
            before.entries.sortedBy { if(it.key==previous)0 else 1 }.forEach { (mode,state) -> pm.setComponentEnabledSetting(mode.component(context),state,PackageManager.DONT_KILL_APP) }
        }
    }

    @Test fun mixedLegacyExplicitDarkIsNormalizedWithoutLosingTheChoice() {
        val pm=context.packageManager
        val before=LauncherIconMode.entries.associateWith { pm.getComponentEnabledSetting(it.component(context)) }
        val previous=LauncherIcons.current(context)
        try {
            pm.setComponentEnabledSetting(LauncherIconMode.DARK.component(context),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
            LauncherIconMode.entries.filter { it!=LauncherIconMode.DARK }.forEach {
                pm.setComponentEnabledSetting(it.component(context),PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,PackageManager.DONT_KILL_APP)
            }
            LauncherIcons.normalize(context);LauncherIcons.normalize(context)
            assertEquals(LauncherIconMode.DARK,LauncherIcons.current(context))
            assertEquals(1,pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),0).size)
        } finally {
            LauncherIcons.select(context,previous)
            before.entries.sortedBy { if(it.key==previous)0 else 1 }.forEach { (mode,state) -> pm.setComponentEnabledSetting(mode.component(context),state,PackageManager.DONT_KILL_APP) }
        }
    }
}

class SettingsAppearanceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun appearanceOrderAndConfirmedThemeAreStableWhileDraftsAreIsolated() {
        val before=AppearanceChoice.read(context)
        val preferences=context.getSharedPreferences(AppearanceChoice.PREFERENCES,android.content.Context.MODE_PRIVATE)
        val snapshot=preferences.all.toMap()
        val source=AppearanceSource.host
        try {
            before.copy(language="en",night="light",color=0xff336699.toInt()).save(context)
            ActivityScenario.launch<AppSettingsActivity>(Intent(context,AppSettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val content=activity.findViewById<View>(android.R.id.content)
                    val tags=listOf("appearance-language","appearance-night","appearance-theme","launcher-icon")
                    val rowViews=tags.map { content.findViewWithTag<View>(it) }
                    val parent=rowViews.first().parent as android.view.ViewGroup
                    assertTrue(rowViews.zipWithNext().all { (first,second) -> parent.indexOfChild(first) < parent.indexOfChild(second) })
                    assertEquals(0xfff3f4f5.toInt(),activity.settingsPalette.background)
                    rowViews[2].performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val dialog=activity.prompt!!
                    val input=dialog.window!!.decorView.findViewWithTag<EditText>("theme-color-input")
                    input.setText("rgb(256, 0, 0)")
                    assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                    input.setText("rgb(255, 128, 0)")
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                    assertEquals(0xff336699.toInt(),AppearanceChoice.read(context).color)
                    assertEquals(activity.settingsPalette.accent,dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    assertEquals(0xff336699.toInt(),AppearanceChoice.read(context).color)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity -> activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("appearance-theme").performClick() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.prompt!!.window!!.decorView.findViewWithTag<EditText>("theme-color-input").setText("#FF8000")
                    activity.prompt!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(0xffff8000.toInt(),AppearanceChoice.read(context).color)
                    assertEquals(0xfff3f4f5.toInt(),activity.settingsPalette.background)
                }
            }
        } finally {
            val editor=preferences.edit().clear()
            snapshot.forEach { (key,value) ->
                when(value) {
                    is String -> editor.putString(key,value)
                    is Int -> editor.putInt(key,value)
                    is Long -> editor.putLong(key,value)
                    is Float -> editor.putFloat(key,value)
                    is Boolean -> editor.putBoolean(key,value)
                    is Set<*> -> editor.putStringSet(key,value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue("Restored preferences must reach disk before instrumentation exits",editor.commit())
            assertEquals(snapshot,preferences.all)
            AppearanceSource.host=source
        }
    }

    @Test fun malformedHostSnapshotsFallBackInsteadOfBecomingAValidTheme() {
        val contract=org.autojs.plugin.common.api.AutoJs6HostSettingsContract
        val valid=android.os.Bundle().apply {
            putInt(contract.KEY_PROTOCOL_VERSION,contract.PROTOCOL_VERSION)
            putString(contract.KEY_HOST_PACKAGE_NAME,contract.HOST_PACKAGE_NAME)
            putString(contract.KEY_RESOLVED_LANGUAGE_TAG,"en")
            putBoolean(contract.KEY_DARK_MODE_ACTIVE,true)
            putInt(contract.KEY_THEME_COLOR_PRIMARY,0xffabcdef.toInt())
            putInt(contract.KEY_THEME_COLOR_ACCENT,0xff123456.toInt())
        }
        assertNotNull(AppearanceSource.decode(valid))
        assertNull(AppearanceSource.decode(android.os.Bundle(valid).apply { putString(contract.KEY_DARK_MODE_ACTIVE,"true") }))
        assertNull(AppearanceSource.decode(android.os.Bundle(valid).apply { putLong(contract.KEY_THEME_COLOR_PRIMARY,0xffabcdef) }))
        assertNull(AppearanceSource.decode(android.os.Bundle(valid).apply { remove(contract.KEY_THEME_COLOR_ACCENT) }))
        assertNull(AppearanceSource.decode(android.os.Bundle(valid).apply { putString(contract.KEY_RESOLVED_LANGUAGE_TAG,"en_US") }))
    }

    @Test fun hostProviderReadHasAValidatedSnapshotOrAnHonestFallback() {
        val snapshot=AppearanceSource.readHost(context)
        val bundle=android.os.Bundle().apply {
            putString("hostAppearance",if(snapshot==null)"unavailable_fallback" else "available")
            snapshot?.let { putString("hostThemeColor",ThemeColorValue.hex(it.seed));putBoolean("hostNight",it.night) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle)
        val effective=AppearanceChoice().resolve(snapshot,"en",false)
        if(snapshot==null) { assertEquals("en",effective.language);assertFalse(effective.night);assertEquals(AppearanceChoice.FALLBACK_COLOR,effective.seed) }
        else assertEquals(snapshot,effective)
    }
}
