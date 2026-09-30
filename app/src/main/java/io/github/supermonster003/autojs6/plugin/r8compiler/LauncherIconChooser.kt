package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

internal object LauncherIconChooser {
    val labels=intArrayOf(R.string.launcher_icon_light,R.string.launcher_icon_dark,R.string.launcher_icon_auto,R.string.launcher_icon_transparent)
    fun summary(activity: Activity)=activity.getString(R.string.launcher_icon_title)+": "+activity.getString(labels[LauncherIcons.current(activity).ordinal])

    fun show(activity: AppearanceActivity,onChanged: () -> Unit): AlertDialog = SettingsUi(activity,activity.settingsPalette).choice(
        R.string.launcher_icon_title,labels.map(activity::getString),LauncherIcons.current(activity).ordinal,
        mapOf(2 to activity.getString(R.string.launcher_icon_auto_note),3 to activity.getString(R.string.launcher_icon_transparent_note))) { index ->
        LauncherIcons.select(activity,LauncherIconMode.entries[index])
        onChanged()
        Toast.makeText(activity,R.string.launcher_icon_applied_note,Toast.LENGTH_LONG).show()
    }
}
