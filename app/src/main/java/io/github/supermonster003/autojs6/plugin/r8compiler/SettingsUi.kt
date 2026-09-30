package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class SettingsUi(val context: Context, val palette: SettingsPalette) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun text(value: CharSequence, size: Float = 16f, secondary: Boolean = false) = TextView(context).apply {
        text = value; textSize = size
        setTextColor(if (secondary) palette.muted else palette.text)
        setLinkTextColor(palette.accent); highlightColor = palette.ripple
    }
    fun states(active: Int, normal: Int = palette.muted, disabled: Int = palette.disabled) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf(android.R.attr.state_selected),intArrayOf(android.R.attr.state_focused),intArrayOf()),
        intArrayOf(disabled,active,active,active,normal))
    fun fill(color: Int, radius: Int = 0, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1),palette.outline)
    }
    fun ripple() = RippleDrawable(ColorStateList.valueOf(palette.ripple), null, ColorDrawable(Color.WHITE))
    fun icon(resource: Int) = ImageView(context).apply {
        setImageResource(resource); imageTintList = states(palette.accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun section(container: LinearLayout, label: Int) {
        container.addView(text(context.getText(label),14f,true).apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); androidx.core.view.ViewCompat.setAccessibilityHeading(this,true); setPadding(dp(24),dp(24),dp(24),dp(8))
        })
    }
    fun divider(container: LinearLayout) {
        container.addView(View(context).apply { setBackgroundColor(palette.divider) },LinearLayout.LayoutParams(-1,dp(1)).apply { marginStart=dp(64) })
    }
    fun row(title: Int, summary: CharSequence, icon: Int, tag: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            this.tag=tag; orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            minimumHeight=dp(72); setPadding(dp(24),dp(12),dp(24),dp(12)); background=ripple()
            addView(this@SettingsUi.icon(icon),LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(16) })
            addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(context.getText(title)),LinearLayout.LayoutParams(-1,-2))
                addView(text(summary,14f,true).apply { this.tag="row-summary" },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(this@SettingsUi.icon(R.drawable.ic_settings_chevron),LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginStart=dp(16) })
            contentDescription=context.getString(title)+", "+summary
            isFocusable=true
            accessibilityDelegate=object: View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View,info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info);info.className=android.widget.Button::class.java.name
                }
            }
            setOnClickListener { onClick() }
        }
    }
    fun switchRow(title: Int, selected: Boolean, tag: String, onChanged: (Boolean) -> Unit): View {
        val row=LinearLayout(context).apply {
            this.tag=tag; orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            minimumHeight=dp(72); setPadding(dp(24),dp(12),dp(24),dp(12)); background=ripple()
        }
        row.addView(icon(R.drawable.ic_settings_tune),LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(16) })
        row.addView(text(context.getText(title)).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(0,-2,1f))
        val control=MaterialSwitch(context).apply {
            isChecked=selected; isClickable=false; isFocusable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnCheckedChangeListener { _, value -> onChanged(value); row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
        }
        tint(control)
        row.addView(control,LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(16) })
        row.contentDescription=context.getString(title); row.isFocusable=true
        row.accessibilityDelegate=object: View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className=android.widget.Switch::class.java.name; info.isCheckable=true; info.isChecked=control.isChecked
            }
        }
        row.setOnClickListener { control.isChecked=!control.isChecked }
        return row
    }

    /** No unconfirmed callback runs. A failing commit leaves the picker open and selection recoverable. */
    fun choice(title: Int, labels: List<CharSequence>, selected: Int, notes: Map<Int,String> = emptyMap(), commit: (Int) -> Unit): AlertDialog {
        var pending=selected
        val rows=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val radios=mutableListOf<com.google.android.material.radiobutton.MaterialRadioButton>()
        labels.forEachIndexed { index, label ->
            val row=LinearLayout(context).apply {
                tag="choice-$index"; orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(if (notes.containsKey(index)) 72 else 56)
                setPadding(dp(24),dp(8),dp(24),dp(8)); background=ripple(); isFocusable=true
            }
            val radio=com.google.android.material.radiobutton.MaterialRadioButton(context).apply {
                isChecked=index==selected; isClickable=false; isFocusable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList=states(palette.accent)
            }
            radios.add(radio)
            row.addView(radio,LinearLayout.LayoutParams(dp(40),dp(48)))
            row.addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(label),LinearLayout.LayoutParams(-1,-2))
                notes[index]?.let { addView(text(it,14f,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) }) }
            },LinearLayout.LayoutParams(0,-2,1f))
            row.contentDescription=listOfNotNull(label,notes[index]).joinToString(", ")
            row.accessibilityDelegate=object: View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info)
                    info.className=android.widget.RadioButton::class.java.name; info.isCheckable=true; info.isChecked=radio.isChecked
                }
            }
            row.setOnClickListener {
                pending=index
                radios.forEachIndexed { number, button -> button.isChecked=number==index }
                row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            rows.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        val scroll=ConstrainedScrollView(context).apply { addView(rows); isFillViewport=false }
        val dialog=MaterialAlertDialogBuilder(context).setTitle(title).setView(scroll)
            .setBackgroundInsetStart(0).setBackgroundInsetEnd(0).setBackgroundInsetTop(0).setBackgroundInsetBottom(0)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { commit(pending); dialog.dismiss() }
                catch (_: Exception) { Toast.makeText(context,R.string.settings_apply_failed,Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
        return dialog
    }

    fun styleDialog(dialog: AlertDialog) {
        dialog.window?.apply {
            val ownerWindow=(context as? android.app.Activity)?.window
            @Suppress("DEPRECATION")
            if(ownerWindow!=null) {
                if(Build.VERSION.SDK_INT>=30) {
                    val flags=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    insetsController?.setSystemBarsAppearance(ownerWindow.insetsController?.systemBarsAppearance ?: 0,flags)
                } else {
                    val flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if(Build.VERSION.SDK_INT>=26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
                    decorView.systemUiVisibility=(decorView.systemUiVisibility and flags.inv()) or (ownerWindow.decorView.systemUiVisibility and flags)
                }
            }
            setBackgroundDrawable(fill(palette.surface,24))
            val width=context.resources.configuration.screenWidthDp
            setLayout(dp(minOf(560,(width-48).coerceAtLeast(1))),ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply { textSize=20f; setTextColor(palette.text) }
        for (id in intArrayOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(id)?.apply {
                isAllCaps=false; minHeight=dp(48); setTextColor(states(palette.accent,palette.accent)); background=ripple()
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply { setTextColor(palette.text); setLinkTextColor(palette.accent) }
    }

    fun tint(view: View) {
        when (view) {
            is MaterialSwitch -> {
                view.thumbTintList=states(palette.accent,palette.muted)
                view.trackTintList=states(ColorUtils.blendARGB(palette.surface,palette.accent,0.15f),palette.divider,ColorUtils.blendARGB(palette.surface,palette.text,0.12f))
                view.trackDecorationTintList=states(palette.accent,palette.muted)
                view.thumbIconTintList=states(palette.onAccent,palette.muted)
            }
            is SwitchCompat -> { view.thumbTintList=states(palette.primary); view.trackTintList=states(palette.accent,palette.outline) }
            is CompoundButton -> view.buttonTintList=states(palette.accent)
            is MaterialButton -> {
                view.backgroundTintList=states(palette.primary,palette.primary,ColorUtils.blendARGB(palette.surface,palette.text,0.12f)); view.setTextColor(states(palette.onPrimary,palette.onPrimary))
                view.iconTint=states(palette.onPrimary,palette.onPrimary); view.rippleColor=ColorStateList.valueOf(palette.ripple)
            }
            is Button -> {
                view.backgroundTintList=states(palette.primary,palette.primary,ColorUtils.blendARGB(palette.surface,palette.text,0.12f)); view.setTextColor(states(palette.onPrimary,palette.onPrimary)); view.isAllCaps=false
            }
            is Slider -> {
                view.thumbTintList=states(palette.accent,palette.accent); view.trackActiveTintList=states(palette.accent,palette.accent)
                view.trackInactiveTintList=ColorStateList.valueOf(palette.divider); view.haloTintList=ColorStateList.valueOf(palette.ripple)
            }
            is SeekBar -> { view.thumbTintList=states(palette.accent,palette.accent); view.progressTintList=states(palette.accent,palette.accent) }
            is ProgressBar -> { view.progressTintList=states(palette.accent,palette.accent); view.indeterminateTintList=states(palette.accent,palette.accent) }
            is EditText -> {
                view.backgroundTintList=states(palette.accent); view.setTextColor(palette.text); view.setHintTextColor(palette.muted)
                if (Build.VERSION.SDK_INT>=29) view.textCursorDrawable?.mutate()?.setTint(palette.accent)
            }
            is TextInputLayout -> {
                view.boxStrokeColor=palette.accent; view.defaultHintTextColor=states(palette.accent,palette.muted)
                view.setErrorTextColor(ColorStateList.valueOf(palette.danger)); view.setBoxStrokeErrorColor(ColorStateList.valueOf(palette.danger))
            }
        }
        if (view is TextView) { view.setLinkTextColor(palette.accent); view.highlightColor=palette.ripple }
        if (view is ViewGroup) for(index in 0 until view.childCount) tint(view.getChildAt(index))
    }

    private class ConstrainedScrollView(context: Context): ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
            val maximum=((resources.configuration.screenHeightDp*0.85f-144)*resources.displayMetrics.density).toInt().coerceAtLeast((48*resources.displayMetrics.density).toInt())
            super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(maximum,MeasureSpec.AT_MOST))
        }
    }
}
