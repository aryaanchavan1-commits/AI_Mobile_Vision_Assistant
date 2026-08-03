package com.arynox.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0F1117)
val Surface1 = Color(0xFF1B1F2A)
val Surface2 = Color(0xFF232839)
val Accent = Color(0xFF4F8CFF)
val Accent2 = Color(0xFF8AB4F8)
val Green = Color(0xFF34D399)
val Red = Color(0xFFF87171)
val Amber = Color(0xFFFBBF24)
val TextPrimary = Color(0xFFF2F5FA)
val TextDim = Color(0xFF9CA3AF)
val TextFaint = Color(0xFF6B7280)
val Edge = Color(0xFF39415A)
val Scrim = Color(0xCC000000)

val ArynoxDarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3F6B),
    onPrimaryContainer = Accent2,
    secondary = Accent2,
    onSecondary = Color(0xFF10141D),
    secondaryContainer = Color(0xFF253049),
    onSecondaryContainer = Color(0xFFC9D8FF),
    tertiary = Green,
    onTertiary = Color(0xFF0B1A13),
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    outline = Edge,
    outlineVariant = Surface2,
    error = Red,
    onError = Color.White,
    errorContainer = Color(0xFF4A2020),
    onErrorContainer = Color(0xFFFFD3D3),
    inverseSurface = Surface2,
    inverseOnSurface = TextPrimary,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Color(0xFF20263A),
    surfaceContainerHighest = Color(0xFF2A3040),
)
