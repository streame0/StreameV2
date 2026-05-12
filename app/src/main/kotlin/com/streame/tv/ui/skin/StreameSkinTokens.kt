package com.streame.tv.ui.skin

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streame.tv.ui.theme.InterFontFamily

@Immutable
data class StreameColorTokens(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val focusOutline: Color,
    val focusGradientStart: Color,
    val focusGradientEnd: Color,
    val tealAccent: Color,
    val watchedGreen: Color,      // Green checkmark for watched items (Arctic Fuse 2 style)
    val inProgressGrey: Color,    // Grey clock for in-progress items
)

@Immutable
data class StreameSpacingTokens(
    val x1: Dp,
    val x2: Dp,
    val x3: Dp,
    val x4: Dp,
    val x6: Dp,
    val x8: Dp,
    val x12: Dp,
    val x16: Dp,
)

@Immutable
data class StreameRadiusTokens(
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
)

@Immutable
data class StreameTypographyTokens(
    val heroTitle: TextStyle,
    val sectionTitle: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val badge: TextStyle,
    val button: TextStyle,
)

@Immutable
data class StreameMotionTokens(
    val focusDurationMillis: Int,
    val focusEasing: Easing,
    val screenTransitionMillis: Int,
    val heroFadeMillis: Int,
)

@Immutable
data class StreameFocusTokens(
    val scaleFocused: Float,
    val scalePressed: Float,
    val durationMillis: Int,
    val easing: Easing,
    val outlineWidth: Dp,
    val glowWidth: Dp,
    val glowAlpha: Float,
    val translationZFocused: Dp,
)

@Immutable
data class StreameSkinTokens(
    val colors: StreameColorTokens,
    val spacing: StreameSpacingTokens,
    val radius: StreameRadiusTokens,
    val typography: StreameTypographyTokens,
    val motion: StreameMotionTokens,
    val focus: StreameFocusTokens,
) {
    companion object {
        fun defaults(): StreameSkinTokens {
            val easeOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

            return StreameSkinTokens(
                colors = StreameColorTokens(
                    background = Color(0xFF000000),
                    surface = Color(0xFF0D0D0D),
                    surfaceRaised = Color(0xFF1A1A1A),
                    textPrimary = Color(0xFFEDEDED),
                    textMuted = Color(0xB3EDEDED),
                    accent = Color(0xFFEDEDED),
                    focusOutline = Color(0xFFFFFFFF),  // Glowing white focus
                    focusGradientStart = Color(0xFFFFFFFF),  // White
                    focusGradientEnd = Color(0xFFFFFFFF),    // White (no gradient)
                    tealAccent = Color(0xFF00D9B5),  // Teal checkmark color
                    watchedGreen = Color(0xFF4CAF50),  // Green checkmark (Arctic Fuse 2 style)
                    inProgressGrey = Color(0xFF757575),  // Grey clock for in-progress
                ),
                spacing = StreameSpacingTokens(
                    x1 = 4.dp,
                    x2 = 8.dp,
                    x3 = 12.dp,
                    x4 = 16.dp,
                    x6 = 24.dp,
                    x8 = 32.dp,
                    x12 = 48.dp,
                    x16 = 64.dp,
                ),
                radius = StreameRadiusTokens(
                    sm = 8.dp,
                    md = 12.dp,
                    lg = 16.dp,
                ),
                typography = StreameTypographyTokens(
                    heroTitle = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Black,
                        fontSize = 50.sp,
                        letterSpacing = (-0.5).sp,
                        lineHeight = 56.sp,
                    ),
                    sectionTitle = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = 0.4.sp,
                        lineHeight = 26.sp,
                    ),
                    cardTitle = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        letterSpacing = 0.sp,
                        lineHeight = 20.sp,
                    ),
                    body = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        letterSpacing = 0.sp,
                        lineHeight = 20.sp,
                    ),
                    caption = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        letterSpacing = 0.3.sp,
                        lineHeight = 14.sp,
                    ),
                    badge = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 0.4.sp,
                        lineHeight = 12.sp,
                    ),
                    button = TextStyle(
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.4.sp,
                        lineHeight = 20.sp,
                    ),
                ),
                motion = StreameMotionTokens(
                    focusDurationMillis = 120,    // Smooth focus transitions
                    focusEasing = easeOut,
                    screenTransitionMillis = 150, // Smooth screen transitions
                    heroFadeMillis = 200,         // Smooth backdrop dissolve
                ),
                focus = StreameFocusTokens(
                    scaleFocused = 1.05f,  // Noticeable scale for TV viewing distance
                    scalePressed = 0.97f,
                    durationMillis = 120,  // Smooth but responsive animations
                    easing = easeOut,
                    outlineWidth = 3.dp,   // Prominent white border
                    glowWidth = 0.dp,      // No glow for performance
                    glowAlpha = 0f,        // No glow
                    translationZFocused = 8.dp,  // Visible lift effect
                ),
            )
        }

        /**
         * OLED theme variant — true #000000 backgrounds for OLED burn-in prevention.
         * No gradients, no elevated surfaces, pure black everywhere.
         */
        fun oled(): StreameSkinTokens {
            val easeOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
            val defaults = defaults()

            return defaults.copy(
                colors = StreameColorTokens(
                    background = Color(0xFF000000),       // Pure black
                    surface = Color(0xFF000000),          // Pure black — no elevation tint
                    surfaceRaised = Color(0xFF0A0A0A),    // Minimal tint for interactive surfaces
                    textPrimary = Color(0xFFEDEDED),
                    textMuted = Color(0xB3EDEDED),
                    accent = Color(0xFFEDEDED),
                    focusOutline = Color(0xFFFFFFFF),
                    focusGradientStart = Color(0xFFFFFFFF),
                    focusGradientEnd = Color(0xFFFFFFFF),
                    tealAccent = Color(0xFF00D9B5),
                    watchedGreen = Color(0xFF4CAF50),
                    inProgressGrey = Color(0xFF757575),
                ),
                focus = defaults.focus.copy(
                    outlineWidth = 2.dp,  // Thinner border for OLED subtlety
                    glowWidth = 0.dp,
                    glowAlpha = 0f,
                ),
            )
        }

        /**
         * Dimmer theme variant — warmer, lower-contrast palette for late-night viewing.
         * Reduced brightness, warmer tones, softer focus states.
         */
        fun dimmer(): StreameSkinTokens {
            val easeOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
            val defaults = defaults()

            return defaults.copy(
                colors = StreameColorTokens(
                    background = Color(0xFF08090A),
                    surface = Color(0xFF0F0F0F),
                    surfaceRaised = Color(0xFF1A1A1A),
                    textPrimary = Color(0xFFBFBFBF),     // Softer text (75% of full white)
                    textMuted = Color(0x80BFBFBF),        // Even more muted
                    accent = Color(0xFFBFBFBF),
                    focusOutline = Color(0xFFD4D4D4),     // Softer focus ring
                    focusGradientStart = Color(0xFFD4D4D4),
                    focusGradientEnd = Color(0xFFD4D4D4),
                    tealAccent = Color(0xFF00B896),       // Slightly muted teal
                    watchedGreen = Color(0xFF388E3C),     // Darker green
                    inProgressGrey = Color(0xFF616161),
                ),
                focus = defaults.focus.copy(
                    outlineWidth = 2.dp,  // Softer focus border
                    glowWidth = 0.dp,
                    glowAlpha = 0f,
                ),
            )
        }
    }
}

