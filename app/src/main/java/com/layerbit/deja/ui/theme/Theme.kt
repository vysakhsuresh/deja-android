package com.layerbit.deja.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.deja.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class DejaPalette(
    val background: Color,
    val surface: Color,
    val surfaceDim: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val amber: Color,
    val amberBright: Color,
    val amberDim: Color,
    val onAmber: Color,
    val green: Color,
    val greenDim: Color,
    val danger: Color,
    val isDark: Boolean
)

/**
 * The palette from the design canvas. Warm charcoal rather than the blue-black LayerLink uses -
 * Deja is a memory, not a tool.
 */
val DarkPalette = DejaPalette(
    background = Color(0xFF0E0D0C),
    surface = Color(0xFF1A1817),
    surfaceDim = Color(0xFF151312),
    border = Color(0xFF262220),
    borderStrong = Color(0xFF2C2825),
    text = Color(0xFFF5F1EC),
    muted = Color(0xFFA79E94),
    dim = Color(0xFF6E655C),
    amber = Color(0xFFE8A33D),
    amberBright = Color(0xFFF2B75C),
    amberDim = Color(0xFF3A2A14),
    onAmber = Color(0xFF16120C),
    green = Color(0xFF58C08C),
    greenDim = Color(0xFF16301F),
    danger = Color(0xFFE0705F),
    isDark = true
)

/**
 * The same identity in daylight: warm paper instead of charcoal, and the amber taken down a few
 * steps so it still reads as text on a light ground (the dark theme's amber is about 2:1 on
 * white). Never a neutral grey-white - that would be any app.
 */
val LightPalette = DejaPalette(
    background = Color(0xFFF6F1EA),
    surface = Color(0xFFFFFBF6),
    surfaceDim = Color(0xFFEFE8DE),
    border = Color(0xFFE2D8CB),
    borderStrong = Color(0xFFD5C9BA),
    text = Color(0xFF1C1814),
    muted = Color(0xFF5E554B),
    dim = Color(0xFF786D61),
    amber = Color(0xFFA0650B),
    amberBright = Color(0xFFB8770F),
    amberDim = Color(0xFFF5E2C3),
    onAmber = Color(0xFFFFFFFF),
    green = Color(0xFF2B7D54),
    greenDim = Color(0xFFDCEFE3),
    danger = Color(0xFFB8432F),
    isDark = false
)

val LocalDejaPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Read through the current palette, so every existing `DejaColors.Amber` call site follows the
 * theme without being rewritten.
 */
object DejaColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.surface
    val SurfaceDim: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.surfaceDim
    val Border: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.border
    val BorderStrong: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.borderStrong
    val Text: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.muted
    val Dim: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.dim
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.amber
    val AmberBright: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.amberBright
    val AmberDim: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.amberDim
    val OnAmber: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.onAmber
    val Green: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.green
    val GreenDim: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.greenDim
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalDejaPalette.current.danger
}

enum class ThemeChoice(val id: String) {
    SYSTEM("system"), DARK("dark"), LIGHT("light");

    companion object {
        fun fromId(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** The manual override from the Privacy screen. Follows the system unless told otherwise. */
object ThemeSettings {
    private const val PREFS = "deja_settings"
    private const val KEY = "theme"

    private val _choice = MutableStateFlow(ThemeChoice.SYSTEM)
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _choice.value = ThemeChoice.fromId(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
        )
    }

    fun set(context: Context, choice: ThemeChoice) {
        _choice.value = choice
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, choice.id).apply()
    }

    @Composable
    fun isDark(): Boolean {
        val choice by choice.collectAsState()
        return when (choice) {
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            ThemeChoice.DARK -> true
            ThemeChoice.LIGHT -> false
        }
    }
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

private fun DejaPalette.scheme() = if (isDark) {
    darkColorScheme(
        primary = amber,
        onPrimary = onAmber,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        outline = border
    )
} else {
    lightColorScheme(
        primary = amber,
        onPrimary = onAmber,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        outline = border
    )
}

@Composable
fun DejaTheme(dark: Boolean = ThemeSettings.isDark(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalDejaPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.scheme(),
            typography = DejaTypography
        ) {
            // Most screens style text inline, which merges with whatever LocalTextStyle carries -
            // setting the family here is what makes Space Grotesk the default everywhere rather
            // than something each call site has to remember.
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(
                    fontFamily = SpaceGrotesk,
                    color = palette.text
                ),
                content = content
            )
        }
    }
}
