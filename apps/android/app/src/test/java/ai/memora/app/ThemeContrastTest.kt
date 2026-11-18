package ai.memora.app

import ai.memora.app.ui.DarkColors
import ai.memora.app.ui.LightColors
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun textPairsMeetAaContrastForLightAndDarkSurfaces() {
        listOf("light" to LightColors, "dark" to DarkColors).forEach { (theme, palette) ->
            assertTextContrast(theme, "primary text/background", palette.ink, palette.background)
            assertTextContrast(theme, "primary text/card", palette.ink, palette.card)
            assertTextContrast(theme, "muted text/background", palette.muted, palette.background)
            assertTextContrast(theme, "muted text/card", palette.muted, palette.card)
            assertTextContrast(theme, "accent text/background", palette.accent, palette.background)
            assertTextContrast(theme, "accent text/card", palette.accent, palette.card)
            assertTextContrast(theme, "primary button", palette.onAccent, palette.accent)
            assertTextContrast(theme, "accent badge", palette.onSoftAccent, palette.softAccent)
            assertTextContrast(theme, "mint badge", palette.onSoftMint, palette.softMint)
            assertTextContrast(theme, "blue badge", palette.onSoftBlue, palette.softBlue)
            assertTextContrast(theme, "error badge", palette.onSoftError, palette.softError)
            assertTextContrast(theme, "green status", palette.green, palette.softGreen)
            assertTextContrast(theme, "warning status", palette.amber, palette.softAmber)
            assertTextContrast(theme, "error text/background", palette.error, palette.background)
        }
    }

    @Test fun nonTextBoundariesMeetThreeToOneContrast() {
        listOf("light" to LightColors, "dark" to DarkColors).forEach { (theme, palette) ->
            assertNonTextContrast(theme, "outline/card", palette.line, palette.card)
            assertNonTextContrast(theme, "outline/background", palette.line, palette.background)
            assertNonTextContrast(theme, "focus/card", palette.focus, palette.card)
            assertNonTextContrast(theme, "focus/background", palette.focus, palette.background)
        }
    }

    private fun assertTextContrast(theme: String, pair: String, foreground: androidx.compose.ui.graphics.Color,
        background: androidx.compose.ui.graphics.Color) {
        assertTrue("$theme $pair contrast was ${contrast(foreground, background)}:1", contrast(foreground, background) >= 4.5)
    }

    private fun assertNonTextContrast(theme: String, pair: String, foreground: androidx.compose.ui.graphics.Color,
        background: androidx.compose.ui.graphics.Color) {
        assertTrue("$theme $pair contrast was ${contrast(foreground, background)}:1", contrast(foreground, background) >= 3.0)
    }

    private fun contrast(first: androidx.compose.ui.graphics.Color, second: androidx.compose.ui.graphics.Color): Double {
        val high = maxOf(luminance(first), luminance(second))
        val low = minOf(luminance(first), luminance(second))
        return (high + 0.05) / (low + 0.05)
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
