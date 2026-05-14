package com.streame.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.*
import coil3.size.Precision
import androidx.compose.ui.platform.LocalContext
import com.streame.tv.data.model.MediaItem
import com.streame.tv.data.model.MediaType
import com.streame.tv.ui.skin.StreameFocusableSurface
import com.streame.tv.ui.skin.StreameSkin
import com.streame.tv.ui.skin.rememberStreameCardShape

/**
 * Continue Watching card with progress bar.
 *
 * Notes:
 * - Focus visuals are handled by `StreameFocusableSurface` (no layout scaling).
 * - The `isFocused` param is preserved for compatibility with any external focus tracking.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingCard(
    item: MediaItem,
    progress: Float = 0f, // 0.0 to 1.0
    episodeInfo: String? = null,
    timeRemaining: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = 340.dp,
    isFocused: Boolean = false,
    onClick: () -> Unit = {},
) {
    val shape = rememberStreameCardShape(StreameSkin.radius.md)

    Column(modifier = modifier.width(width)) {
        StreameFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = shape,
            backgroundColor = StreameSkin.colors.surface,
            onClick = onClick,
        ) { surfaceFocused ->
            val focused = isFocused || surfaceFocused

            Box(modifier = Modifier.fillMaxSize()) {
                // Use a sized ImageRequest so Coil decodes at the card's actual
                // pixel dimensions instead of the source image's full resolution.
                // Without this, "original"-sized TMDB backdrops (2-10 MB) were
                // being decoded at full size, causing slow loads and memory waste.
                val imageUrl = (item.backdrop ?: item.image).takeIf { !it.isNullOrBlank() }
                if (imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .size(640, 360)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    StreameSkin.colors.background.copy(alpha = 0.85f),
                                ),
                                startY = 120f,
                            )
                        )
                )

                if (focused) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(StreameSkin.colors.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = StreameSkin.colors.textPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = StreameSkin.spacing.x2, vertical = StreameSkin.spacing.x2),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(StreameSkin.colors.focusOutline.copy(alpha = 0.20f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(StreameSkin.colors.accent),
                    )
                }

                if (timeRemaining != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(StreameSkin.spacing.x2)
                            .background(
                                color = StreameSkin.colors.surfaceRaised.copy(alpha = 0.85f),
                                shape = rememberStreameCardShape(StreameSkin.radius.sm),
                            )
                            .padding(horizontal = StreameSkin.spacing.x2, vertical = StreameSkin.spacing.x1),
                    ) {
                        Text(
                            text = timeRemaining,
                            style = StreameSkin.typography.badge,
                            color = StreameSkin.colors.textPrimary,
                        )
                    }
                }

                val typeLabel = if (item.mediaType == MediaType.TV) "TV" else "MOVIE"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(StreameSkin.spacing.x2)
                        .background(
                            color = StreameSkin.colors.surfaceRaised.copy(alpha = 0.85f),
                            shape = rememberStreameCardShape(StreameSkin.radius.sm),
                        )
                        .padding(horizontal = StreameSkin.spacing.x2, vertical = StreameSkin.spacing.x1),
                ) {
                    Text(
                        text = typeLabel,
                        style = StreameSkin.typography.badge,
                        color = StreameSkin.colors.textPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(StreameSkin.spacing.x2))

        Text(
            text = item.title,
            style = StreameSkin.typography.cardTitle,
            color = if (isFocused) StreameSkin.colors.textPrimary else StreameSkin.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val meta = episodeInfo ?: item.year.takeIf { it.isNotBlank() }
        if (meta != null) {
            Text(
                text = meta,
                style = StreameSkin.typography.caption,
                color = StreameSkin.colors.textMuted.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact Continue Watching card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingCardCompact(
    item: MediaItem,
    progress: Float = 0f,
    episodeInfo: String? = null,
    isFocused: Boolean = false,
    onClick: () -> Unit = {},
) {
    val shape = rememberStreameCardShape(StreameSkin.radius.md)

    StreameFocusableSurface(
        modifier = Modifier.width(380.dp),
        shape = shape,
        backgroundColor = StreameSkin.colors.surface,
        onClick = onClick,
    ) { surfaceFocused ->
        val focused = isFocused || surfaceFocused

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(StreameSkin.spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .aspectRatio(16f / 9f)
                    .background(StreameSkin.colors.surfaceRaised, rememberStreameCardShape(StreameSkin.radius.sm)),
            ) {
                val compactUrl = (item.backdrop ?: item.image).takeIf { !it.isNullOrBlank() }
                if (compactUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(compactUrl)
                            .size(200, 112)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(StreameSkin.colors.focusOutline.copy(alpha = 0.20f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(StreameSkin.colors.accent),
                    )
                }
            }

            Spacer(modifier = Modifier.width(StreameSkin.spacing.x3))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = StreameSkin.typography.cardTitle,
                    color = StreameSkin.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episodeInfo != null) {
                    Text(
                        text = episodeInfo,
                        style = StreameSkin.typography.caption,
                        color = StreameSkin.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (focused) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(StreameSkin.colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = StreameSkin.colors.textPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

