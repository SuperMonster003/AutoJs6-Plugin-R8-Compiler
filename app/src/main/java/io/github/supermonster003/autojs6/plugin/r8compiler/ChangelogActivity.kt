package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import java.io.IOException

class ChangelogActivity : AppearanceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content=android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.VERTICAL }
        val bar=com.google.android.material.appbar.MaterialToolbar(this).apply {
            title=getString(R.string.changelog_title);setTitleTextColor(settingsPalette.text)
            setNavigationIcon(R.drawable.ic_settings_back);setNavigationIconTint(settingsPalette.text)
            navigationContentDescription=getString(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        content.addView(bar,android.widget.LinearLayout.LayoutParams(-1,(56*resources.displayMetrics.density).toInt()))
        layoutInflater.inflate(R.layout.activity_changelog,content,true)
        setContentView(content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        SettingsUi(this,settingsPalette).tint(content)

        val locale = resources.configuration.locales[0]
        val changelog = ChangelogAssetSelector.candidates(locale).firstNotNullOfOrNull { name ->
            try {
                assets.open("doc/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: IOException) {
                null
            }
        }
        findViewById<TextView>(R.id.changelog_content).text =
            changelog ?: getString(R.string.changelog_load_error)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
