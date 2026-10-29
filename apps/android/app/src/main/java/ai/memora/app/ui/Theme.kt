package ai.memora.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class MemoraColors(
    val background: Color, val card: Color, val ink: Color, val muted: Color,
    val line: Color, val accent: Color, val softAccent: Color, val onSoftAccent: Color,
    val green: Color, val softGreen: Color, val amber: Color, val softAmber: Color,
    val hero: Color, val onHero: Color,
)

val LightColors = MemoraColors(
    Color(0xFFF8F7F4), Color(0xFFFFFFFF), Color(0xFF252432), Color(0xFF6B6875),
    Color(0xFFE8E6EC), Color(0xFF6650B8), Color(0xFFEEEBFA), Color(0xFF57429F),
    Color(0xFF28745B), Color(0xFFEAF3EE), Color(0xFF936426), Color(0xFFFAF0E0),
    Color(0xFFEEEBFA), Color(0xFF33254F),
)
val DarkColors = MemoraColors(
    Color(0xFF17171C), Color(0xFF222229), Color(0xFFF1EFF6), Color(0xFFB1ADB9),
    Color(0xFF35343F), Color(0xFFBCA9FF), Color(0xFF342C4A), Color(0xFFDACDFF),
    Color(0xFF9AD7B9), Color(0xFF233A30), Color(0xFFE5C080), Color(0xFF3C3325),
    Color(0xFF30273F), Color(0xFFE4D8FC),
)
val LocalMemoraColors = staticCompositionLocalOf { LightColors }
val colors: MemoraColors @Composable get() = LocalMemoraColors.current

private val MemoraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

@Composable
fun MemoraTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val palette = if (dark) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = if (dark) darkColorScheme(
        primary = palette.accent, onPrimary = Color(0xFF24183E),
        background = palette.background, surface = palette.card, onSurface = palette.ink,
        onSurfaceVariant = palette.muted, surfaceVariant = palette.softAccent,
        primaryContainer = palette.softAccent, onPrimaryContainer = palette.onSoftAccent,
        outline = palette.line,
    ) else lightColorScheme(
        primary = palette.accent, onPrimary = Color.White,
        background = palette.background, surface = palette.card, onSurface = palette.ink,
        onSurfaceVariant = palette.muted, surfaceVariant = palette.softAccent,
        primaryContainer = palette.softAccent, onPrimaryContainer = palette.onSoftAccent,
        outline = palette.line,
    )
    CompositionLocalProvider(LocalMemoraColors provides palette) {
        MaterialTheme(colorScheme = scheme, typography = MemoraTypography, content = content)
    }
}
