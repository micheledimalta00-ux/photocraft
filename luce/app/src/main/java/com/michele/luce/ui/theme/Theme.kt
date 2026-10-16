package com.michele.luce.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object LuceColors {
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceHigh = Color(0xFF26262A)
    val Panel = Color(0xFF181819)
    val Divider = Color(0xFF2E2E32)
    val Accent = Color(0xFF3D9BFF)
    val AccentDim = Color(0xFF2A5D94)
    val TextPrimary = Color(0xFFEDEDED)
    val TextSecondary = Color(0xFF9A9AA0)
    val TextDisabled = Color(0xFF5C5C62)
    val Danger = Color(0xFFFF5A5A)
    val Warm = Color(0xFFFFB547)
}

private val scheme = darkColorScheme(
    primary = LuceColors.Accent,
    onPrimary = Color.White,
    secondary = LuceColors.Accent,
    onSecondary = Color.White,
    background = LuceColors.Background,
    onBackground = LuceColors.TextPrimary,
    surface = LuceColors.Surface,
    onSurface = LuceColors.TextPrimary,
    surfaceVariant = LuceColors.SurfaceHigh,
    onSurfaceVariant = LuceColors.TextSecondary,
    surfaceContainer = LuceColors.Surface,
    surfaceContainerHigh = LuceColors.SurfaceHigh,
    surfaceContainerHighest = LuceColors.SurfaceHigh,
    outline = LuceColors.Divider,
    error = LuceColors.Danger
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun LuceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
