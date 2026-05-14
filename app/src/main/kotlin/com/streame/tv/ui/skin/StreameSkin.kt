package com.streame.tv.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalStreameSkinTokens = staticCompositionLocalOf { StreameSkinTokens.defaults() }

/**
 * Optional override for the focus border colour, driven by the user's
 * "Focus border colour" setting. When non-null every [StreameFocusable]
 * composable uses this colour instead of [StreameColorTokens.focusOutline].
 */
val LocalFocusBorderColorOverride = staticCompositionLocalOf<Color?> { null }

/**
 * Maps a user-facing colour name to its [Color] value.
 * Used by the focus border colour setting and the colour picker.
 */
fun focusBorderColorFromName(name: String): Color = when (name) {
    "Red" -> Color(0xFFFF4444)
    "Orange" -> Color(0xFFFF8800)
    "Yellow" -> Color(0xFFFFDD44)
    "Green" -> Color(0xFF44CC44)
    "Blue" -> Color(0xFF4488FF)
    "Indigo" -> Color(0xFF6644CC)
    "Violet" -> Color(0xFFBB44CC)
    else -> Color(0xFFFFFFFF) // White (default)
}

@Composable
fun ProvideStreameSkin(
    tokens: StreameSkinTokens = StreameSkinTokens.defaults(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStreameSkinTokens provides tokens,
        content = content,
    )
}

object StreameSkin {
    val tokens: StreameSkinTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalStreameSkinTokens.current

    val colors: StreameColorTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.colors

    val spacing: StreameSpacingTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.spacing

    val radius: StreameRadiusTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.radius

    val typography: StreameTypographyTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.typography

    val motion: StreameMotionTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.motion

    val focus: StreameFocusTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.focus
}

