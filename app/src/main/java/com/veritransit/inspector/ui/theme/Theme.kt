package com.veritransit.inspector.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val VTMono = staticCompositionLocalOf { MonoStyles() }
val LocalHapticsEnabled = staticCompositionLocalOf { true }

private val LightScheme = lightColorScheme(
    primary = VT.Primary,
    onPrimary = VT.OnPrimary,
    primaryContainer = VT.PrimaryDeep,
    onPrimaryContainer = VT.OnPrimary,
    secondary = VT.Slate,
    onSecondary = Color.White,
    surface = VT.Surface,
    onSurface = VT.Ink,
    surfaceVariant = VT.Inset,
    onSurfaceVariant = VT.Slate,
    background = VT.Alabaster,
    onBackground = VT.Ink,
    outline = VT.Border,
    outlineVariant = VT.Hairline,
    error = VT.Crimson,
    surfaceContainer = VT.Surface,
    surfaceContainerLow = VT.Surface,
    surfaceContainerHigh = VT.Inset,
)

private val VTShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun VeriTransitTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(VTMono provides MonoStyles()) {
        MaterialTheme(
            colorScheme = LightScheme,
            typography = VTTypography,
            shapes = VTShapes,
            content = content,
        )
    }
}

@Composable
fun mono() = VTMono.current
