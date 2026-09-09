package com.layerbit.deja.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The palette from the design canvas. Warm charcoal rather than the blue-black LayerLink uses -
 * Deja is a memory, not a tool.
 */
object DejaColors {
    val Background = Color(0xFF0E0D0C)
    val Surface = Color(0xFF1A1817)
    val SurfaceDim = Color(0xFF151312)
    val Border = Color(0xFF262220)
    val BorderStrong = Color(0xFF2C2825)
    val Text = Color(0xFFF5F1EC)
    val Muted = Color(0xFFA79E94)
    val Dim = Color(0xFF6E655C)
    val Amber = Color(0xFFE8A33D)
    val AmberBright = Color(0xFFF2B75C)
    val AmberDim = Color(0xFF3A2A14)
    val OnAmber = Color(0xFF16120C)
    val Green = Color(0xFF58C08C)
    val GreenDim = Color(0xFF16301F)
}

// The canvas pairs Bricolage Grotesque with Instrument Sans. Neither ships here yet, so this is
// the system face at the same weights and sizes; dropping the real fonts into res/font later is
// the only change needed.
private val DejaTypography = Typography(
    displayLarge = TextStyle(fontSize = 50.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val DejaColorScheme = darkColorScheme(
    primary = DejaColors.Amber,
    onPrimary = DejaColors.OnAmber,
    background = DejaColors.Background,
    onBackground = DejaColors.Text,
    surface = DejaColors.Surface,
    onSurface = DejaColors.Text,
    outline = DejaColors.Border
)

@Composable
fun DejaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DejaColorScheme,
        typography = DejaTypography,
        content = content
    )
}
