package io.github.supermonster003.autojs6.plugin.r8compiler

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale

class AppSettingsActivity : AppearanceActivity() {
    internal var prompt: AlertDialog? = null
        private set
    override val hasUnconfirmedDialog: Boolean get() = prompt?.isShowing == true
    private lateinit var ui: SettingsUi
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onDestroy() { prompt?.dismiss(); super.onDestroy() }

    private fun render() {
        ui=SettingsUi(this,settingsPalette)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(settingsPalette.background) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val toolbar=MaterialToolbar(this).apply {
            title=getString(R.string.settings_title); setTitleTextColor(settingsPalette.text)
            setBackgroundColor(settingsPalette.background)
            navigationIcon=androidx.appcompat.content.res.AppCompatResources.getDrawable(this@AppSettingsActivity,R.drawable.ic_settings_back)
            setNavigationIconTint(settingsPalette.text)
            navigationContentDescription=getString(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar,LinearLayout.LayoutParams(-1,ui.dp(56)))
        rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,0,0,ui.dp(24)) }
        root.addView(ScrollView(this).apply { addView(rows); isFillViewport=true },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        ui.section(rows,R.string.app_settings_appearance)
        val preferences=AppearanceChoice.read(this)
        val languageSummary=when(preferences.language) {
            "host" -> getString(R.string.app_settings_follow_autojs6_summary,Locale.forLanguageTag(resolvedAppearance.language).getDisplayName(resources.configuration.locales[0]))
            "system" -> getString(R.string.app_settings_follow_system)
            else -> Locale.forLanguageTag(preferences.language).getDisplayName(resources.configuration.locales[0])
        }
        rows.addView(ui.row(R.string.app_settings_language,languageSummary,R.drawable.ic_settings_language,"appearance-language",::language))
        ui.divider(rows)
        val nightLabels=nightLabels()
        rows.addView(ui.row(R.string.app_settings_dark_mode,nightLabels[AppearanceChoice.nights.indexOf(preferences.night)],R.drawable.ic_settings_night,"appearance-night",::night))
        ui.divider(rows)
        val colorSummary=preferences.color?.let(ThemeColorValue::hex) ?: getString(R.string.app_settings_follow_autojs6_summary,ThemeColorValue.hex(resolvedAppearance.seed))
        rows.addView(ui.row(R.string.app_settings_theme_color,colorSummary,R.drawable.ic_settings_theme,"appearance-theme",::color))

        ui.section(rows,R.string.app_settings_updates)
        rows.addView(ui.row(R.string.changelog_title,packageManager.getPackageInfo(packageName,0).versionName.orEmpty(),R.drawable.ic_settings_history,"release-history") {
            startActivity(android.content.Intent(this,ChangelogActivity::class.java))
        })
    }

    private fun language() {
        val current=AppearanceChoice.read(this)
        val labels=AppearanceChoice.languages.map {
            when(it) {
                "host" -> getString(R.string.app_settings_follow_autojs6)
                "system" -> getString(R.string.app_settings_follow_system)
                else -> Locale.forLanguageTag(it).getDisplayName(Locale.forLanguageTag(it))
            }
        }
        prompt=ui.choice(R.string.app_settings_language,labels,AppearanceChoice.languages.indexOf(current.language)) {
            if (current.language!=AppearanceChoice.languages[it]) { current.copy(language=AppearanceChoice.languages[it]).save(this); recreate() }
        }
    }

    private fun nightLabels() = listOf(R.string.app_settings_follow_autojs6,R.string.app_settings_follow_system,
        R.string.app_settings_always_light,R.string.app_settings_always_dark).map(::getString)

    private fun night() {
        val current=AppearanceChoice.read(this)
        prompt=ui.choice(R.string.app_settings_dark_mode,nightLabels(),AppearanceChoice.nights.indexOf(current.night)) {
            if(current.night!=AppearanceChoice.nights[it]) { current.copy(night=AppearanceChoice.nights[it]).save(this); recreate() }
        }
    }

    private fun color() {
        val current=AppearanceChoice.read(this)
        prompt=ThemeColorChooser.show(this,current.color,AppearanceSource.host?.seed ?: AppearanceChoice.FALLBACK_COLOR,
            ThemeColorChooser.Palette(settingsPalette.accent,settingsPalette.surface,settingsPalette.text,settingsPalette.muted,settingsPalette.outline),
            ThemeColorChooser.Labels(getString(R.string.app_settings_theme_color),getString(R.string.app_settings_follow_autojs6),
                getString(R.string.theme_picker_presets),getString(R.string.theme_picker_custom),getString(R.string.theme_picker_input),
                getString(R.string.theme_picker_invalid),getString(R.string.theme_picker_preview))) {
            if(current.color!=it) { current.copy(color=it).save(this); recreate() }
        }
    }



}
