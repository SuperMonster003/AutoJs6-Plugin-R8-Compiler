package io.github.supermonster003.autojs6.plugin.r8compiler

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ChangelogAssetSelectorTest {
    @Test
    fun mapsSupportedLocalesToBundledAssets() {
        val cases = linkedMapOf(
            Locale.SIMPLIFIED_CHINESE to "CHANGELOG-zh.md",
            Locale.TRADITIONAL_CHINESE to "CHANGELOG-zh-rTW.md",
            Locale.forLanguageTag("zh-HK") to "CHANGELOG-zh-rHK.md",
            Locale.forLanguageTag("zh-Hant") to "CHANGELOG-zh-rTW.md",
            Locale.ENGLISH to "CHANGELOG-en.md",
            Locale.FRENCH to "CHANGELOG-fr.md",
            Locale.forLanguageTag("es") to "CHANGELOG-es.md",
            Locale.JAPANESE to "CHANGELOG-ja.md",
            Locale.KOREAN to "CHANGELOG-ko.md",
            Locale.forLanguageTag("ru") to "CHANGELOG-ru.md",
            Locale.forLanguageTag("ar") to "CHANGELOG-ar.md",
        )

        cases.forEach { (locale, expected) ->
            assertEquals(expected, ChangelogAssetSelector.candidates(locale).first())
        }
    }

    @Test
    fun fallsBackToEnglishAndDoesNotDuplicateIt() {
        assertEquals(listOf("CHANGELOG-en.md"), ChangelogAssetSelector.candidates(Locale.GERMAN))
        assertEquals(listOf("CHANGELOG-en.md"), ChangelogAssetSelector.candidates(Locale.ENGLISH))
    }
}
