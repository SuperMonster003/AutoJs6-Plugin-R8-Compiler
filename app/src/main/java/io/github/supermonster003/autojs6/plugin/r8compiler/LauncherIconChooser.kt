package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Activity
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.app.AlertDialog
import android.widget.Toast

/** A single settings row exposes all modes and explains launcher-dependent rendering. */
internal object LauncherIconChooser {
    val labels = intArrayOf(R.string.launcher_icon_light, R.string.launcher_icon_dark,
        R.string.launcher_icon_auto, R.string.launcher_icon_transparent)

    fun summary(activity: Activity): String = activity.getString(R.string.launcher_icon_title) +
        ": " + activity.getString(labels[LauncherIcons.current(activity).ordinal])

    fun show(activity: Activity, onChanged: () -> Unit): AlertDialog {
        val choices = labels.mapIndexed { index, label ->
            val note = when (LauncherIconMode.entries[index]) {
                LauncherIconMode.AUTO -> R.string.launcher_icon_auto_note
                LauncherIconMode.TRANSPARENT -> R.string.launcher_icon_transparent_note
                else -> null
            }
            val title = activity.getString(label)
            SpannableString(title + (note?.let { "\n" + activity.getString(it) } ?: "")).apply {
                if (note != null) setSpan(RelativeSizeSpan(0.8f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.toTypedArray()
        return AlertDialog.Builder(activity)
            .setTitle(R.string.launcher_icon_title)
            .setSingleChoiceItems(choices, LauncherIcons.current(activity).ordinal) { dialog, selected ->
                try {
                    LauncherIcons.select(activity, LauncherIconMode.entries[selected])
                    onChanged()
                    dialog.dismiss()
                    Toast.makeText(activity, R.string.launcher_icon_applied_note, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    (dialog as AlertDialog).listView.setItemChecked(LauncherIcons.current(activity).ordinal, true)
                    Toast.makeText(activity, R.string.launcher_icon_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show().also { dialog -> dialog.listView.post { dialog.listView.setSelection(0) } }
    }
}
