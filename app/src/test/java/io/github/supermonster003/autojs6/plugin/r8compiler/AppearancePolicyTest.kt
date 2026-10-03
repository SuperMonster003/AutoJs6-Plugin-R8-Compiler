package io.github.supermonster003.autojs6.plugin.r8compiler

import org.junit.Assert.*
import org.junit.Test

class AppearancePolicyTest {
    @Test fun localHostAndSystemPrecedenceIsIndependentForEachSetting() {
        val host=ResolvedAppearance("ja",true,0xff123456.toInt(),0xff654321.toInt())
        assertEquals(host,AppearanceChoice().resolve(host,"en",false))
        assertEquals(ResolvedAppearance("fr",false,0xffabcdef.toInt()),AppearanceChoice("fr","light",0xffabcdef.toInt()).resolve(host,"en",true))
        val system=AppearanceChoice("system","system").resolve(host,"ar",false)
        assertEquals("ar",system.language);assertFalse(system.night);assertEquals(host.seed,system.seed)
    }
    @Test fun unavailableHostUsesSystemAndTheSameDocumentedSeed() {
        assertEquals(ResolvedAppearance("ru",true,0xffffdead.toInt()),AppearanceChoice().resolve(null,"ru",true))
    }
    @Test fun allPresetsAndExtremeSeedsKeepNeutralSurfacesAndReadableControls() {
        for(dark in listOf(false,true)) for(seed in ThemeColorValue.presets+listOf(0xff000000.toInt(),0xffffffff.toInt(),0xffffff00.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt())) {
            val palette=SettingsPalette(ResolvedAppearance("en",dark,seed))
            assertEquals(if(dark)0xff121212.toInt() else 0xfff3f4f5.toInt(),palette.background)
            assertEquals(if(dark)0xff1e1e1e.toInt() else 0xffffffff.toInt(),palette.surface)
            assertTrue(SettingsColorMath.contrast(palette.accent,palette.background)>=4.5)
            assertTrue(SettingsColorMath.contrast(palette.accent,palette.surface)>=4.5)
            assertTrue(SettingsColorMath.contrast(palette.onPrimary,palette.primary)>=4.5)
        }
    }
}
