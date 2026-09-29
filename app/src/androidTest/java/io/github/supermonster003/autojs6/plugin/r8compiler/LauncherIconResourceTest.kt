package io.github.supermonster003.autojs6.plugin.r8compiler

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LauncherIconResourceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    private fun resources(night: Int): Resources {
        val base = context.resources
        val configuration = Configuration(base.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
        return Resources(base.assets, base.displayMetrics, configuration)
    }

    private fun draw(icon: Drawable): Bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888).also {
        icon.setBounds(0, 0, it.width, it.height)
        icon.draw(Canvas(it))
    }

    @Test fun transparentModeHasThemedGlyphAndNoBackground() {
        for ((mode, color) in listOf(Configuration.UI_MODE_NIGHT_NO to 0xff272727.toInt(), Configuration.UI_MODE_NIGHT_YES to 0xffd8d8d8.toInt())) {
            val icon = resources(mode).getDrawable(R.mipmap.ic_launcher_transparent, null)
            assertTrue(icon is VectorDrawable)
            val bitmap = draw(icon)
            try {
                var opaque = 0
                var clear = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) == 255) { assertEquals(color, pixel); opaque++ }
                    if (Color.alpha(pixel) == 0) clear++
                }
                assertTrue(opaque > bitmap.width)
                assertTrue(clear > bitmap.width * bitmap.height / 2)
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun fixedModesIgnoreThemeAndAutomaticUsesDarkWhenThemeIsUnknown() {
        for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_UNDEFINED)) {
            for (id in listOf(R.mipmap.ic_launcher_system, R.mipmap.ic_launcher_system_light, R.mipmap.ic_launcher_system_auto)) {
                val light = id == R.mipmap.ic_launcher_system_light || (id == R.mipmap.ic_launcher_system_auto && mode == Configuration.UI_MODE_NIGHT_NO)
                val background = if (light) 0xfffafafa.toInt() else 0xff212121.toInt()
                val foreground = if (light) 0xff272727.toInt() else 0xffd8d8d8.toInt()
                val icon = resources(mode).getDrawable(id, null)
                if (Build.VERSION.SDK_INT >= 26) {
                    assertTrue(icon is AdaptiveIconDrawable)
                    val adaptive = icon as AdaptiveIconDrawable
                    assertEquals(background, (adaptive.background as ColorDrawable).color)
                    val bitmap = draw(adaptive.foreground)
                    try {
                        val opaque = (0 until bitmap.height).asSequence().flatMap { y -> (0 until bitmap.width).asSequence().map { x -> bitmap.getPixel(x, y) } }.first { Color.alpha(it) == 255 }
                        assertEquals(foreground, opaque)
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                                val distance = kotlin.math.hypot(x + 0.5 - bitmap.width / 2.0, y + 0.5 - bitmap.height / 2.0)
                                assertTrue("Glyph must fit the 66 dp safe circle", distance <= bitmap.width * 33.0 / 108)
                            }
                        }
                    } finally { bitmap.recycle() }
                    if (Build.VERSION.SDK_INT >= 33) assertNotNull(adaptive.monochrome)
                } else {
                    assertTrue(icon is VectorDrawable)
                    val bitmap = draw(icon)
                    try {
                        assertEquals(background, bitmap.getPixel(bitmap.width / 2, bitmap.height / 12))
                        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
