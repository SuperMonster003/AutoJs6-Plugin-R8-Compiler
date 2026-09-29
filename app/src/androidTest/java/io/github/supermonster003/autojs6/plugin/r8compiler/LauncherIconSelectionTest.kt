package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Process
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Exercise the actual chooser; restore aliases and remove only this test's shortcut. */
class LauncherIconSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun chooserKeepsOneLauncherAndPreservesExplicitEntryAndShortcuts() {
        val pm = context.packageManager
        val before = LauncherIconMode.entries.associateWith { pm.getComponentEnabledSetting(it.component(context)) }
        val previous = LauncherIcons.current(context)
        val pid = Process.myPid()
        val shortcutId = "icon-test-${UUID.randomUUID()}"
        val shortcuts = if (Build.VERSION.SDK_INT >= 25) context.getSystemService(ShortcutManager::class.java) else null
        var activity: MainActivity? = null
        fun launch(): MainActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            activity = launch()
            if (shortcuts != null) {
                assertTrue(shortcuts.addDynamicShortcuts(listOf(ShortcutInfo.Builder(context, shortcutId)
                    .setShortLabel("Icon test").setActivity(previous.component(context))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)).build())))
            }
            for (mode in LauncherIconMode.entries) {
                instrumentation.runOnMainSync {
                    val current = activity!!
                    current.findViewById<android.view.View>(android.R.id.content).findViewWithTag<Button>("launcher-icon").performClick()
                    val dialog = current.launcherIconDialog!!
                    assertEquals(4, dialog.listView.adapter.count)
                    assertTrue(dialog.listView.adapter.getItem(2).toString().contains(current.getString(R.string.launcher_icon_auto_note)))
                    assertTrue(dialog.listView.adapter.getItem(3).toString().contains(current.getString(R.string.launcher_icon_transparent_note)))
                    dialog.listView.performItemClick(null, mode.ordinal, dialog.listView.adapter.getItemId(mode.ordinal))
                }
                instrumentation.waitForIdleSync()
                assertEquals(mode, LauncherIcons.current(context))
                assertEquals(pid, Process.myPid())
                val matches = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
                assertEquals(1, matches.size)
                assertEquals(mode.component(context).className, matches.single().activityInfo.name)
                assertEquals(MainActivity::class.java.name, matches.single().activityInfo.targetActivity)
                val iconIds = intArrayOf(R.mipmap.ic_launcher_system_light, R.mipmap.ic_launcher_system,
                    R.mipmap.ic_launcher_system_auto, R.mipmap.ic_launcher_transparent)
                assertEquals("Manifest must retain each independent icon ID", iconIds[mode.ordinal], matches.single().activityInfo.icon)
                assertNull(matches.single().activityInfo.permission)
                assertNotNull(pm.resolveActivity(Intent(context, MainActivity::class.java), 0))
                shortcuts?.dynamicShortcuts?.single { it.id == shortcutId }?.let { shortcut ->
                    assertEquals(mode.component(context), shortcut.activity)
                    assertEquals(MainActivity::class.java.name, shortcut.intent!!.component!!.className)
                }
                instrumentation.runOnMainSync { activity!!.finish() }
                instrumentation.waitForIdleSync()
                activity = launch()
                instrumentation.runOnMainSync {
                    val current = requireNotNull(activity)
                    val button = current.findViewById<android.view.View>(android.R.id.content).findViewWithTag<Button>("launcher-icon")
                    assertTrue(button.text.contains(current.getString(LauncherIconChooser.labels[mode.ordinal])))
                }
            }
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            shortcuts?.removeDynamicShortcuts(listOf(shortcutId))
            LauncherIcons.select(context, previous)
            before.entries.sortedBy { if (it.key == previous) 0 else 1 }.forEach { (mode, state) ->
                pm.setComponentEnabledSetting(mode.component(context), state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
