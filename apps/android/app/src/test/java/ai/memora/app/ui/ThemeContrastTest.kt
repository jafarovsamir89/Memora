package ai.memora.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun bodyTextMeetsWcagAaInLightAndDarkSurfaces() {
        listOf(LightColors, DarkColors).forEach { palette ->
            assertRatioAtLeast(palette.ink, palette.background, 4.5, "primary text/background")
            assertRatioAtLeast(palette.ink, palette.card, 4.5, "primary text/card")
            assertRatioAtLeast(palette.muted, palette.background, 4.5, "secondary text/background")
            assertRatioAtLeast(palette.muted, palette.card, 4.5, "secondary text/card")
        }
    }

    @Test fun interactiveColorsAndDecorativeBoundariesRemainDistinguishable() {
        listOf(LightColors, DarkColors).forEach { palette ->
            assertRatioAtLeast(palette.onSoftAccent, palette.softAccent, 4.5, "selected purple label")
            assertRatioAtLeast(palette.onSoftMint, palette.softMint, 4.5, "mint label")
            assertRatioAtLeast(palette.accent, palette.card, 4.5, "purple action on card")
            assertRatioAtLeast(palette.line, palette.background, 3.0, "outline/background")
            assertRatioAtLeast(palette.line, palette.card, 3.0, "outline/card")
        }
        assertRatioAtLeast(LightColors.onAccent, LightColors.accent, 4.5, "light primary button")
        assertRatioAtLeast(DarkColors.onAccent, DarkColors.accent, 4.5, "dark primary button")
        assertRatioAtLeast(LightColors.onSoftMint, LightColors.softMint, 4.5, "light mint surface text")
        assertRatioAtLeast(DarkColors.onSoftMint, DarkColors.softMint, 4.5, "dark mint surface text")
        assertRatioAtLeast(LightColors.onSoftBlue, LightColors.softBlue, 4.5, "light blue surface text")
        assertRatioAtLeast(DarkColors.onSoftBlue, DarkColors.softBlue, 4.5, "dark blue surface text")
        assertRatioAtLeast(LightColors.onSoftError, LightColors.softError, 4.5, "light error surface text")
        assertRatioAtLeast(DarkColors.onSoftError, DarkColors.softError, 4.5, "dark error surface text")
        assertRatioAtLeast(Color.White, LightColors.error, 4.5, "light error button label")
        assertRatioAtLeast(DarkColors.onAccent, DarkColors.accentFill, 4.5, "dark purple fill label")
    }

    private fun assertRatioAtLeast(foreground: Color, background: Color, minimum: Double, label: String) {
        val ratio = contrast(foreground, background)
        assertTrue("$label contrast was ${"%.2f".format(ratio)}:1, expected at least $minimum:1", ratio >= minimum)
    }

    private fun contrast(first: Color, second: Color): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) / (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
