package com.streame.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.streame.tv.ui.skin.LocalFocusBorderColorOverride
import com.streame.tv.ui.skin.ProvideStreameSkin
import com.streame.tv.ui.skin.StreameSkinTokens
import com.streame.tv.ui.skin.focusBorderColorFromName
import androidx.compose.ui.graphics.Color

/**
 * Streame Color scheme holder - Arctic Fuse 2 inspired
 * Minimal dark theme with light gray (#EDEDED) on pure black (#000000)
 */
data class StreameColors(
    // Arctic Fuse 2 Main Colors
    val arcticWhite: androidx.compose.ui.graphics.Color = ArcticWhite,
    val arcticWhite90: androidx.compose.ui.graphics.Color = ArcticWhite90,
    val arcticWhite70: androidx.compose.ui.graphics.Color = ArcticWhite70,
    val arcticWhite50: androidx.compose.ui.graphics.Color = ArcticWhite50,
    val arcticBlack: androidx.compose.ui.graphics.Color = ArcticBlack,
    val arcticGray: androidx.compose.ui.graphics.Color = ArcticGray,
    
    // Legacy gradient colors (mapped to Arctic style)
    val cyan: androidx.compose.ui.graphics.Color = ArcticWhite,
    val cyanDark: androidx.compose.ui.graphics.Color = ArcticGray,
    val cyanGlow: androidx.compose.ui.graphics.Color = FocusGlow,
    val purple: androidx.compose.ui.graphics.Color = ArcticWhite,
    val purpleDark: androidx.compose.ui.graphics.Color = ArcticGray,
    val purpleGlow: androidx.compose.ui.graphics.Color = FocusGlow,
    val pink: androidx.compose.ui.graphics.Color = AccentWhite,
    val pinkDark: androidx.compose.ui.graphics.Color = ArcticGray,
    val pinkGlow: androidx.compose.ui.graphics.Color = FocusGlow,

    // Background colors
    val backgroundDark: androidx.compose.ui.graphics.Color = BackgroundDark,
    val backgroundCard: androidx.compose.ui.graphics.Color = BackgroundCard,
    val backgroundElevated: androidx.compose.ui.graphics.Color = BackgroundElevated,
    val backgroundGlass: androidx.compose.ui.graphics.Color = BackgroundGlass,

    // Text colors
    val textPrimary: androidx.compose.ui.graphics.Color = TextPrimary,
    val textSecondary: androidx.compose.ui.graphics.Color = TextSecondary,
    val textTertiary: androidx.compose.ui.graphics.Color = TextTertiary,

    // Border colors
    val borderLight: androidx.compose.ui.graphics.Color = BorderLight,
    val borderGradient: androidx.compose.ui.graphics.Color = BorderGradient,

    // Status colors
    val success: androidx.compose.ui.graphics.Color = SuccessGreen,
    val error: androidx.compose.ui.graphics.Color = ErrorRed,
    val warning: androidx.compose.ui.graphics.Color = WarningOrange,
    val info: androidx.compose.ui.graphics.Color = InfoBlue,

    // Special colors
    val imdbYellow: androidx.compose.ui.graphics.Color = ImdbYellow,
    val accentRed: androidx.compose.ui.graphics.Color = AccentRed,

    // Focus states (White for Arctic Fuse 2)
    val focusRing: androidx.compose.ui.graphics.Color = FocusRing,
    val focusGlow: androidx.compose.ui.graphics.Color = FocusGlow,

    // Particle colors (subtle white)
    val particleCyan: androidx.compose.ui.graphics.Color = ParticleCyan,
    val particlePurple: androidx.compose.ui.graphics.Color = ParticlePurple,
    val particlePink: androidx.compose.ui.graphics.Color = ParticlePink
)

val LocalStreameColors = staticCompositionLocalOf { StreameColors() }

/**
 * Theme variant names — persisted in DataStore.
 */
enum class ThemeVariant(val key: String) {
    ARCTIC("arctic"),
    OLED("oled"),
    DIMMER("dimmer");

    fun toSkinTokens(): StreameSkinTokens = when (this) {
        ARCTIC -> StreameSkinTokens.defaults()
        OLED -> StreameSkinTokens.oled()
        DIMMER -> StreameSkinTokens.dimmer()
    }
}

/**
 * Main Streame TV theme - Arctic Fuse 2 inspired
 * Supports theme variants: Arctic (default), OLED (true black), Dimmer (low contrast)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreameTvTheme(
    variant: ThemeVariant = ThemeVariant.ARCTIC,
    focusBorderColorName: String? = null,
    content: @Composable () -> Unit
) {
    val skinTokens = variant.toSkinTokens()
    val focusBorderColor = focusBorderColorName?.let { focusBorderColorFromName(it) }

    // Adjust Material color scheme based on variant
    val colorScheme = when (variant) {
        ThemeVariant.OLED -> darkColorScheme(
            primary = ArcticWhite,
            onPrimary = ArcticBlack,
            primaryContainer = Color(0xFF0A0A0A),
            onPrimaryContainer = ArcticWhite,
            secondary = ArcticWhite70,
            onSecondary = ArcticBlack,
            secondaryContainer = Color(0xFF0A0A0A),
            onSecondaryContainer = ArcticWhite,
            tertiary = AccentWhite,
            onTertiary = ArcticBlack,
            tertiaryContainer = Color(0xFF0A0A0A),
            onTertiaryContainer = ArcticWhite,
            background = Color(0xFF000000),
            onBackground = TextPrimary,
            surface = Color(0xFF000000),
            onSurface = TextPrimary,
            surfaceVariant = Color(0xFF0A0A0A),
            onSurfaceVariant = TextSecondary,
            error = ErrorRed,
            onError = ArcticWhite,
            border = BorderLight
        )
        ThemeVariant.DIMMER -> darkColorScheme(
            primary = Color(0xFFBFBFBF),
            onPrimary = ArcticBlack,
            primaryContainer = Color(0xFF1A1A1A),
            onPrimaryContainer = Color(0xFFBFBFBF),
            secondary = Color(0x80BFBFBF),
            onSecondary = ArcticBlack,
            secondaryContainer = Color(0xFF1A1A1A),
            onSecondaryContainer = Color(0xFFBFBFBF),
            tertiary = Color(0xFFD4D4D4),
            onTertiary = ArcticBlack,
            tertiaryContainer = Color(0xFF1A1A1A),
            onTertiaryContainer = Color(0xFFBFBFBF),
            background = Color(0xFF08090A),
            onBackground = Color(0xFFBFBFBF),
            surface = Color(0xFF0F0F0F),
            onSurface = Color(0xFFBFBFBF),
            surfaceVariant = Color(0xFF0F0F0F),
            onSurfaceVariant = Color(0x80BFBFBF),
            error = ErrorRed,
            onError = Color(0xFFBFBFBF),
            border = BorderLight
        )
        else -> darkColorScheme(
            primary = ArcticWhite,
            onPrimary = ArcticBlack,
            primaryContainer = ArcticGray,
            onPrimaryContainer = ArcticWhite,
            secondary = ArcticWhite70,
            onSecondary = ArcticBlack,
            secondaryContainer = ArcticGray,
            onSecondaryContainer = ArcticWhite,
            tertiary = AccentWhite,
            onTertiary = ArcticBlack,
            tertiaryContainer = ArcticGray,
            onTertiaryContainer = ArcticWhite,
            background = BackgroundDark,
            onBackground = TextPrimary,
            surface = BackgroundCard,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceVariant,
            onSurfaceVariant = TextSecondary,
            error = ErrorRed,
            onError = ArcticWhite,
            border = BorderLight
        )
    }

    val streameColors = StreameColors()

    CompositionLocalProvider(
        LocalStreameColors provides streameColors,
        LocalFocusBorderColorOverride provides focusBorderColor
    ) {
        ProvideStreameSkin(tokens = skinTokens) {
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }
}

/**
 * Access custom Streame colors
 */
object StreameTheme {
    val colors: StreameColors
        @Composable
        get() = LocalStreameColors.current
}

