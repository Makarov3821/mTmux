package dev.mtmux

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class PaletteTest {
    @Test fun everyPaletteHasDistinctLightAndDarkSurfacesAndReadableText() {
        assertEquals(6,AppPalette.entries.size)
        for(palette in AppPalette.entries) {
            val dark=appColors(true,palette);val light=appColors(false,palette)
            assertTrue(palette.name,dark.background.luminance()<.2f)
            assertTrue(palette.name,light.background.luminance()>.7f)
            assertNotEquals(dark.background,dark.surface)
            assertNotEquals(light.background,light.surface)
            for(colors in listOf(dark,light)) {
                val a=colors.onSurface.luminance();val b=colors.surface.luminance()
                assertTrue(palette.name,(maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f)
                val muted=colors.onSurfaceVariant.luminance();val raised=colors.surfaceVariant.luminance()
                assertTrue(palette.name,(maxOf(muted,raised)+.05f)/(minOf(muted,raised)+.05f)>=4.5f)
            }
        }
    }
}
