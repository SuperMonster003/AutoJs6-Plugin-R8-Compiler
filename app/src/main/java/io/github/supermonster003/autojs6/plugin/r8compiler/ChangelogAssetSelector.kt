package io.github.supermonster003.autojs6.plugin.r8compiler

import java.util.Locale

internal object ChangelogAssetSelector {
    private val directlySupportedLanguages = setOf("ar", "en", "es", "fr", "ja", "ko", "ru")

    fun candidates(locale: Locale): List<String> {
        val language = locale.language.lowercase(Locale.ROOT)
        val localized = when {
            language == "zh" -> chineseAsset(locale)
            language in directlySupportedLanguages -> "CHANGELOG-$language.md"
            else -> "CHANGELOG-en.md"
        }
        return listOf(localized, "CHANGELOG-en.md").distinct()
    }

    private fun chineseAsset(locale: Locale): String {
        val country = locale.country.uppercase(Locale.ROOT)
        val script = locale.script.lowercase(Locale.ROOT)
        return when {
            country == "HK" || country == "MO" -> "CHANGELOG-zh-rHK.md"
            country == "TW" || script == "hant" -> "CHANGELOG-zh-rTW.md"
            else -> "CHANGELOG-zh.md"
        }
    }
}
