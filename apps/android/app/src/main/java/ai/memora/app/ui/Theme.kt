package ai.memora.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

data class MemoraColors(
    val background: Color, val card: Color, val ink: Color, val muted: Color,
    val line: Color, val accent: Color, val softAccent: Color, val onSoftAccent: Color,
    val green: Color, val softGreen: Color, val amber: Color, val softAmber: Color,
    val hero: Color, val onHero: Color,
    val mint: Color, val softMint: Color, val onSoftMint: Color, val onAccent: Color,
)

val LightColors = MemoraColors(
    background = Color(0xFFF8F7F4), card = Color(0xFFFFFFFF), ink = Color(0xFF252432), muted = Color(0xFF6B6875),
    line = Color(0xFF8C8695), accent = Color(0xFF6650B8), softAccent = Color(0xFFEEEBFA), onSoftAccent = Color(0xFF57429F),
    green = Color(0xFF28654F), softGreen = Color(0xFFE8F5EF), amber = Color(0xFF805716), softAmber = Color(0xFFFAF0E0),
    hero = Color(0xFFEEEBFA), onHero = Color(0xFF33254F),
    mint = Color(0xFF28654F), softMint = Color(0xFFE8F5EF), onSoftMint = Color(0xFF205B47), onAccent = Color.White,
)
val DarkColors = MemoraColors(
    background = Color(0xFF17171C), card = Color(0xFF222229), ink = Color(0xFFF1EFF6), muted = Color(0xFFB1ADB9),
    line = Color(0xFF726F7B), accent = Color(0xFFBCA9FF), softAccent = Color(0xFF342C4A), onSoftAccent = Color(0xFFDACDFF),
    green = Color(0xFF9AD7B9), softGreen = Color(0xFF233A30), amber = Color(0xFFE5C080), softAmber = Color(0xFF3C3325),
    hero = Color(0xFF30273F), onHero = Color(0xFFE4D8FC),
    mint = Color(0xFF9AD7B9), softMint = Color(0xFF233A30), onSoftMint = Color(0xFFC5EBD7), onAccent = Color(0xFF24183E),
)

/** Shared 4 dp spacing rhythm and shapes for the Memora Compose surface. */
object MemoraSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val screenTop = 18.dp
    val screenBottom = 28.dp
}

object MemoraShapes {
    val card = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    val control = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val field = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
}
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
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.card.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = if (dark) darkColorScheme(
        primary = palette.accent, onPrimary = palette.onAccent,
        secondary = palette.mint, onSecondary = Color(0xFF13251E),
        secondaryContainer = palette.softMint, onSecondaryContainer = palette.onSoftMint,
        background = palette.background, surface = palette.card, onSurface = palette.ink,
        onSurfaceVariant = palette.muted, surfaceVariant = palette.softAccent,
        primaryContainer = palette.softAccent, onPrimaryContainer = palette.onSoftAccent,
        outline = palette.line, outlineVariant = palette.line,
    ) else lightColorScheme(
        primary = palette.accent, onPrimary = palette.onAccent,
        secondary = palette.mint, onSecondary = Color.White,
        secondaryContainer = palette.softMint, onSecondaryContainer = palette.onSoftMint,
        background = palette.background, surface = palette.card, onSurface = palette.ink,
        onSurfaceVariant = palette.muted, surfaceVariant = palette.softAccent,
        primaryContainer = palette.softAccent, onPrimaryContainer = palette.onSoftAccent,
        outline = palette.line, outlineVariant = palette.line,
    )
    CompositionLocalProvider(LocalMemoraColors provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MemoraTypography,
            shapes = Shapes(
                extraSmall = MemoraShapes.control,
                small = MemoraShapes.field,
                medium = MemoraShapes.control,
                large = MemoraShapes.card,
                extraLarge = MemoraShapes.card,
            ),
            content = content,
        )
    }
}
