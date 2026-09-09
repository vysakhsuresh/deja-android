package com.layerbit.deja.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.deja.R

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
    val Danger = Color(0xFFE0705F)
}

/** Space Grotesk, the same face LayerLink and layerbit.co.in use. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

private val DejaTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.8).sp),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
        typography = DejaTypography
    ) {
        // Most screens style text inline, which merges with whatever LocalTextStyle carries -
        // setting the family here is what makes Space Grotesk the default everywhere rather than
        // something each call site has to remember.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                fontFamily = SpaceGrotesk,
                color = DejaColors.Text
            ),
            content = content
        )
    }
}
