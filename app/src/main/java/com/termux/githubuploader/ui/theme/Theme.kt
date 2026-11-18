package com.termux.githubuploader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val GitHubGreen = Color(0xFF2DA44E)
val GitHubGreenDark = Color(0xFF238636)
val GitHubBlue = Color(0xFF58A6FF)
val GitHubRed = Color(0xFFF85149)
val GitHubYellow = Color(0xFFD29922)
val SurfaceDark = Color(0xFF0D1117)
val SurfaceDark2 = Color(0xFF161B22)
val SurfaceDark3 = Color(0xFF21262D)
val BorderDark = Color(0xFF30363D)
val TextMuted = Color(0xFF8B949E)

private val DarkColors = darkColorScheme(
    primary = GitHubGreen,
    onPrimary = Color.White,
    primaryContainer = GitHubGreenDark,
    onPrimaryContainer = Color.White,
    secondary = GitHubBlue,
    onSecondary = Color(0xFF0D1117),
    tertiary = GitHubYellow,
    background = SurfaceDark,
    onBackground = Color(0xFFE6EDF3),
    surface = SurfaceDark2,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = SurfaceDark3,
    onSurfaceVariant = TextMuted,
    outline = BorderDark,
    error = GitHubRed,
    onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = GitHubGreenDark,
    onPrimary = Color.White,
    secondary = Color(0xFF0969DA),
    tertiary = Color(0xFF9A6700),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color.White,
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEEF2),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE),
    error = Color(0xFFCF222E)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp)
)

/** Font monospace untuk panel log, seperti tampilan terminal di Termux. */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp
)

@Composable
fun GitHubUploaderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
