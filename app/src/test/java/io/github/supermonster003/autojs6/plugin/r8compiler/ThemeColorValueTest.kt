package io.github.supermonster003.autojs6.plugin.r8compiler

import org.junit.Assert.*
import org.junit.Test

class ThemeColorValueTest {
    @Test fun hexSupportsOptionalPrefixAndCaseWithoutAlpha() {
        assertEquals(0xffabcdef.toInt(), ThemeColorValue.parse(" #AbCdEf "))
        assertEquals(0xffabcdef.toInt(), ThemeColorValue.parse("abcdef"))
        assertEquals("#ABCDEF", ThemeColorValue.hex(0xffabcdef.toInt()))
    }

    @Test fun rgbComponentsAreDecimalAndBounded() {
        assertEquals(0xff00ff7f.toInt(), ThemeColorValue.parse("RGB(0, 255, 127)"))
        assertEquals(0xff010203.toInt(), ThemeColorValue.parse("rgb( 1 , 2 , 3 )"))
        assertEquals("rgb(1, 2, 3)", ThemeColorValue.rgb(0xff010203.toInt()))
    }

    @Test fun malformedAndAlphaColorsAreRejected() {
        listOf("", "#fff", "#FF112233", "##112233", "12 3456", "xyzxyz", "rgb(256,0,0)",
            "rgb(-1,0,0)", "rgb(1,2)", "rgb(1.0,2,3)", "rgba(1,2,3,1)", "rgb(1,2,3) garbage").forEach {
            assertNull(it, ThemeColorValue.parse(it))
        }
    }

    @Test fun previewChoosesReadableOpaqueForeground() {
        assertEquals(0xff000000.toInt(), ThemeColorValue.onColor(0xffffffff.toInt()))
        assertEquals(0xffffffff.toInt(), ThemeColorValue.onColor(0xff000000.toInt()))
        assertEquals(0xff000000.toInt(), ThemeColorValue.onColor(0xffffdead.toInt()))
    }

    @Test fun agreedPresetPaletteHasNoDuplicatesAndContainsHostDefault() {
        assertEquals(16, ThemeColorValue.presets.size)
        assertEquals(16, ThemeColorValue.presets.distinct().size)
        assertEquals(0xffffdead.toInt(), ThemeColorValue.presets.first())
        assertTrue(ThemeColorValue.presets.all { it ushr 24 == 255 })
    }
}
