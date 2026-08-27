package io.github.supermonster003.autojs6.plugin.r8compiler

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import java.io.IOException

class ChangelogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_changelog)
        title = getString(R.string.changelog_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)

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
