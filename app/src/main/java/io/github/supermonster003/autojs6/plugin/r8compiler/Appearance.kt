package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import org.autojs.plugin.common.api.AutoJs6HostSettingsContract as HostContract
import java.util.Locale
import java.util.concurrent.Executors

/** Local choices are independent of host availability; no host setting is ever written. */
internal data class AppearanceChoice(val language: String = "host", val night: String = "host", val color: Int? = null) {
    fun resolve(host: ResolvedAppearance?, systemLanguage: String, systemNight: Boolean) = ResolvedAppearance(
        when (language) { "host" -> host?.language ?: systemLanguage; "system" -> systemLanguage; else -> language },
        when (night) { "host" -> host?.night ?: systemNight; "light" -> false; "dark" -> true; else -> systemNight },
        (color ?: host?.seed ?: FALLBACK_COLOR) or 0xff000000.toInt(),
        (color ?: host?.accentSeed ?: FALLBACK_COLOR) or 0xff000000.toInt(),
    )

    fun save(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("language", language).putString("night", night).apply {
                if (color == null) remove("color") else putInt("color", color)
            }.apply()
    }

    companion object {
        const val PREFERENCES = "standalone-appearance"
        const val FALLBACK_COLOR = 0xffffdead.toInt()
        val languages = listOf("host", "system", "zh-Hans", "zh-Hant-HK", "zh-Hant-TW", "en", "fr", "es", "ja", "ko", "ru", "ar")
        val nights = listOf("host", "system", "light", "dark")
        val presets = intArrayOf(0xffffdead.toInt(),0xfff44336.toInt(),0xffe91e63.toInt(),0xff9c27b0.toInt(),
            0xff673ab7.toInt(),0xff3f51b5.toInt(),0xff2196f3.toInt(),0xff03a9f4.toInt(),
            0xff00bcd4.toInt(),0xff009688.toInt(),0xff4caf50.toInt(),0xff8bc34a.toInt(),
            0xffff9800.toInt(),0xffff5722.toInt(),0xff795548.toInt(),0xff607d8b.toInt())

        fun read(context: Context): AppearanceChoice {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return AppearanceChoice(
                preferences.getString("language", "host").takeIf { it in languages } ?: "host",
                preferences.getString("night", "host").takeIf { it in nights } ?: "host",
                if (preferences.contains("color")) preferences.getInt("color", FALLBACK_COLOR) or 0xff000000.toInt() else null,
            )
        }

        fun parseColor(value: String): Int? {
            val input = value.trim()
            if (input.matches(Regex("#?[0-9a-fA-F]{6}"))) return input.removePrefix("#").toLong(16).toInt() or 0xff000000.toInt()
            val match = Regex("rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)", RegexOption.IGNORE_CASE).matchEntire(input) ?: return null
            val rgb = match.groupValues.drop(1).map { it.toInt() }
            if (rgb.any { it !in 0..255 }) return null
            return 0xff000000.toInt() or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }

        fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xffffff)
    }
}

internal data class ResolvedAppearance(val language: String, val night: Boolean, val seed: Int, val accentSeed: Int = seed) {
    fun wrap(context: Context): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        val locale = Locale.forLanguageTag(language)
        setLocales(LocaleList(locale)); setLayoutDirection(locale)
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })
}

internal object AppearanceSource {
    @Volatile var host: ResolvedAppearance? = null
    val worker = Executors.newSingleThreadExecutor()

    fun resolve(context: Context, snapshot: ResolvedAppearance? = host): ResolvedAppearance {
        // Application resources retain system configuration; Activity resources may already be overridden.
        val system = context.applicationContext.resources.configuration
        return AppearanceChoice.read(context).resolve(snapshot, system.locales[0].toLanguageTag(),
            system.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }

    fun readHost(context: Context): ResolvedAppearance? = runCatching {
        context.contentResolver.acquireUnstableContentProviderClient(Uri.parse(HostContract.CONTENT_URI))?.use { client ->
            client.call(HostContract.METHOD_GET_SETTINGS, null, null)?.let(::decode)
        }
    }.getOrNull()

    fun decode(bundle: Bundle): ResolvedAppearance? = runCatching {
        require(!bundle.hasFileDescriptors())
        require(bundle.getInt(HostContract.KEY_PROTOCOL_VERSION) == HostContract.PROTOCOL_VERSION)
        require(bundle.getString(HostContract.KEY_HOST_PACKAGE_NAME) == HostContract.HOST_PACKAGE_NAME)
        @Suppress("DEPRECATION")
        require(bundle.get(HostContract.KEY_DARK_MODE_ACTIVE) is Boolean && bundle.get(HostContract.KEY_THEME_COLOR_PRIMARY) is Int && bundle.get(HostContract.KEY_THEME_COLOR_ACCENT) is Int)
        val tag = requireNotNull(bundle.getString(HostContract.KEY_RESOLVED_LANGUAGE_TAG))
        require(tag.length in 2..80 && tag.matches(Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")) && Locale.forLanguageTag(tag).language.isNotEmpty())
        ResolvedAppearance(tag, bundle.getBoolean(HostContract.KEY_DARK_MODE_ACTIVE), bundle.getInt(HostContract.KEY_THEME_COLOR_PRIMARY) or 0xff000000.toInt(), bundle.getInt(HostContract.KEY_THEME_COLOR_ACCENT) or 0xff000000.toInt())
    }.getOrNull()
}

/** Surface colors remain neutral; arbitrary seeds only affect interactive emphasis. */
internal class SettingsPalette(val appearance: ResolvedAppearance) {
    private val roles = ThemeAccentRoles.fromSeed(appearance.seed,appearance.night)
    val primary = roles.primary
    val background = if (appearance.night) 0xff121212.toInt() else 0xfff3f4f5.toInt()
    val surface = if (appearance.night) 0xff1e1e1e.toInt() else 0xffffffff.toInt()
    val text = if (appearance.night) 0xffe6e1e5.toInt() else 0xff1d1b20.toInt()
    val muted = if (appearance.night) 0xffb9bac0.toInt() else 0xff5f6368.toInt()
    val outline = if (appearance.night) 0xff777a82.toInt() else 0xffc5c8ce.toInt()
    val divider = if (appearance.night) 0xff34363a.toInt() else 0xffe0e3e7.toInt()
    val danger = if (appearance.night) 0xffffb4ab.toInt() else 0xffb3261e.toInt()
    val disabled = SettingsColorMath.blend(surface, text, 0.38f)
    val onPrimary = roles.onPrimary
    private val accentRoles = ThemeAccentRoles.fromSeed(appearance.accentSeed,appearance.night)
    val accent = accessible(accentRoles.primary, surface, background)
    val onAccent = accentRoles.onPrimary
    val ripple = SettingsColorMath.alpha(accent, 0x24)

    companion object {
        fun accessible(seed: Int, vararg backgrounds: Int): Int {
            fun passes(color: Int) = backgrounds.all { SettingsColorMath.contrast(color, it) >= 4.5 }
            if (passes(seed)) return seed
            val target = if (backgrounds.sumOf { SettingsColorMath.luminance(it) } / backgrounds.size > 0.5) 0xff000000.toInt() else 0xffffffff.toInt()
            var low = 0f; var high = 1f
            repeat(20) {
                val middle = (low + high) / 2
                if (passes(SettingsColorMath.blend(seed, target, middle))) high = middle else low = middle
            }
            return SettingsColorMath.blend(seed, target, high)
        }
    }
}

internal object SettingsColorMath {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double = ((color ushr shift and 255)/255.0).let { if(it<=0.04045) it/12.92 else Math.pow((it+0.055)/1.055,2.4) }
        return 0.2126*channel(16)+0.7152*channel(8)+0.0722*channel(0)
    }
    fun contrast(first: Int,second: Int): Double {
        val a=luminance(first)+0.05;val b=luminance(second)+0.05
        return maxOf(a,b)/minOf(a,b)
    }
    fun blend(first: Int,second: Int,amount: Float): Int {
        fun channel(shift: Int): Int = (((first ushr shift and 255)*(1-amount))+(second ushr shift and 255)*amount).toInt().coerceIn(0,255)
        return 0xff000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    fun alpha(color: Int,value: Int) = (color and 0xffffff) or (value shl 24)
}
