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
    val line: Color, val accent: Color, val accentFill: Color, val softAccent: Color, val onSoftAccent: Color,
    val green: Color, val softGreen: Color, val amber: Color, val softAmber: Color,
    val hero: Color, val onHero: Color,
    val mint: Color, val softMint: Color, val onSoftMint: Color,
    val blue: Color, val softBlue: Color, val onSoftBlue: Color,
    val error: Color, val softError: Color, val onSoftError: Color,
    val disabled: Color, val disabledSurface: Color, val focus: Color,
    val overlay: Color, val onAccent: Color,
)

val LightColors = MemoraColors(
    background = Color(0xFFF8F9FC), card = Color(0xFFFFFFFF), ink = Color(0xFF202238), muted = Color(0xFF62677A),
    line = Color(0xFF888EA0), accent = Color(0xFF6F4FE0), accentFill = Color(0xFF8B6EF6),
    softAccent = Color(0xFFE9E1FF), onSoftAccent = Color(0xFF423273),
    green = Color(0xFF28654F), softGreen = Color(0xFFE8F8F0), amber = Color(0xFF805716), softAmber = Color(0xFFFAF0E0),
    hero = Color(0xFFE9E1FF), onHero = Color(0xFF33254F),
    mint = Color(0xFFA6F3D0), softMint = Color(0xFFE8F8F0), onSoftMint = Color(0xFF185843),
    blue = Color(0xFFDCEBFF), softBlue = Color(0xFFEAF2FF), onSoftBlue = Color(0xFF21476B),
    error = Color(0xFFB3261E), softError = Color(0xFFFFE9E6), onSoftError = Color(0xFF7A1712),
    disabled = Color(0xFF73798A), disabledSurface = Color(0xFFE7E9F0), focus = Color(0xFF6F4FE0),
    overlay = Color(0x520F1220), onAccent = Color.White,
)
val DarkColors = MemoraColors(
    background = Color(0xFF12131C), card = Color(0xFF1B1D2A), ink = Color(0xFFECEDF7), muted = Color(0xFFB2B6C8),
    line = Color(0xFF81869A), accent = Color(0xFFB3A0FF), accentFill = Color(0xFFB3A0FF),
    softAccent = Color(0xFF332B4A), onSoftAccent = Color(0xFFE5DAFF),
    green = Color(0xFF8DCBAF), softGreen = Color(0xFF203B32), amber = Color(0xFFE5C080), softAmber = Color(0xFF3C3325),
    hero = Color(0xFF30273F), onHero = Color(0xFFE4D8FC),
    mint = Color(0xFFA6F3D0), softMint = Color(0xFF203B32), onSoftMint = Color(0xFFC2F4DA),
    blue = Color(0xFFDCEBFF), softBlue = Color(0xFF23364D), onSoftBlue = Color(0xFFC9E0FF),
    error = Color(0xFFFFB4AB), softError = Color(0xFF442A2A), onSoftError = Color(0xFFFFDAD5),
    disabled = Color(0xFF858A9B), disabledSurface = Color(0xFF282A36), focus = Color(0xFFB3A0FF),
    overlay = Color(0x99000000), onAccent = Color(0xFF211A38),
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

object MemoraElevation {
    val card = 1.dp
    val floating = 4.dp
}

object MemoraMotion {
    const val quickMs = 140
    const val standardMs = 220
    const val emphasizedMs = 320
    val easing = androidx.compose.animation.core.FastOutSlowInEasing
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
        error = palette.error, onError = Color(0xFF601410),
        errorContainer = palette.softError, onErrorContainer = palette.onSoftError,
    ) else lightColorScheme(
        primary = palette.accent, onPrimary = palette.onAccent,
        secondary = palette.green, onSecondary = Color.White,
        secondaryContainer = palette.softMint, onSecondaryContainer = palette.onSoftMint,
        background = palette.background, surface = palette.card, onSurface = palette.ink,
        onSurfaceVariant = palette.muted, surfaceVariant = palette.softAccent,
        primaryContainer = palette.softAccent, onPrimaryContainer = palette.onSoftAccent,
        outline = palette.line, outlineVariant = palette.line,
        error = palette.error, onError = Color.White,
        errorContainer = palette.softError, onErrorContainer = palette.onSoftError,
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
