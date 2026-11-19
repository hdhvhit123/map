package app.terratrace.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Brand palette: Forest Green, Terra Cotta and stone greys. */
object Brand {
    val Forest = Color(0xFF2F5D3A)
    val ForestDark = Color(0xFF1F3F27)
    val ForestLight = Color(0xFF8BC49A)
    val Terra = Color(0xFFC8643B)
    val TerraLight = Color(0xFFF09A72)
    val GuideRed = Color(0xFFD93A2B)
    val Stone50 = Color(0xFFF5F3EF)
    val Stone100 = Color(0xFFECE8E1)
    val Stone200 = Color(0xFFDAD5CC)
    val Stone600 = Color(0xFF6B675F)
    val Stone800 = Color(0xFF2E2C28)
    val Night = Color(0xFF151816)
    val NightSurface = Color(0xFF1F2320)
    val NightSurface2 = Color(0xFF2A2F2B)
    val Glass = Color(0x8C0F1210)
    val GlassBorder = Color(0x1FFFFFFF)
}

/** ARGB ints for MapLibre layers. */
object TrailColors {
    val WHITE = 0xFFFFFFFF.toInt()
    val TERRA = Brand.Terra.toArgb()
    val FOREST = Brand.Forest.toArgb()
    val GUIDE_RED = Brand.GuideRed.toArgb()
    val STONE = Brand.Stone600.toArgb()
    val LOCATION_BLUE = 0xFF2B7BE4.toInt()
}

private val LightColors = lightColorScheme(
    primary = Brand.Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6D3),
    onPrimaryContainer = Brand.ForestDark,
    secondary = Brand.Terra,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8DCCD),
    onSecondaryContainer = Color(0xFF5A2410),
    tertiary = Brand.GuideRed,
    background = Brand.Stone50,
    onBackground = Brand.Stone800,
    surface = Color.White,
    onSurface = Brand.Stone800,
    surfaceVariant = Brand.Stone100,
    onSurfaceVariant = Brand.Stone600,
    surfaceContainer = Brand.Stone100,
    surfaceContainerHigh = Brand.Stone200,
    surfaceContainerLow = Color(0xFFF9F8F5),
    outline = Color(0xFFB8B2A7),
    outlineVariant = Brand.Stone200,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Brand.ForestLight,
    onPrimary = Color(0xFF0D2A16),
    primaryContainer = Color(0xFF2B4A32),
    onPrimaryContainer = Color(0xFFCFE6D3),
    secondary = Brand.TerraLight,
    onSecondary = Color(0xFF4A1A06),
    secondaryContainer = Color(0xFF6B3018),
    onSecondaryContainer = Color(0xFFF8DCCD),
    tertiary = Color(0xFFFF6B5B),
    background = Brand.Night,
    onBackground = Color(0xFFE8E4DC),
    surface = Brand.NightSurface,
    onSurface = Color(0xFFE8E4DC),
    surfaceVariant = Brand.NightSurface2,
    onSurfaceVariant = Color(0xFFA8A39A),
    surfaceContainer = Brand.NightSurface,
    surfaceContainerHigh = Brand.NightSurface2,
    surfaceContainerLow = Color(0xFF191C1A),
    outline = Color(0xFF5E5A53),
    outlineVariant = Color(0xFF3A3D3A),
)

private val Display = FontFamily.SansSerif

val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

/** Tabular figures so live numbers don't jitter. */
val NumberStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TerraTraceTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
