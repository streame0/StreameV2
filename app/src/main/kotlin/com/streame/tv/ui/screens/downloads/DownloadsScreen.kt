package com.streame.tv.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streame.tv.data.local.DownloadEntity
import com.streame.tv.data.repository.DownloadRepository
import com.streame.tv.ui.theme.BackgroundElevated
import com.streame.tv.ui.theme.Pink
import com.streame.tv.ui.theme.TextPrimary
import com.streame.tv.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository
) : ViewModel() {
    val downloads = downloadRepository.completedDownloadsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteDownload(id: Long) {
        viewModelScope.launch {
            downloadRepository.deleteDownload(id)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Downloads",
            style = androidx.compose.ui.text.TextStyle(fontSize = 28.sp),
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No downloads yet",
                        color = TextSecondary,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp)
                    )
                    Text(
                        text = "Download movies and episodes for offline viewing",
                        color = TextSecondary.copy(alpha = 0.6f),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloads, key = { it.id }) { download ->
                    DownloadRow(
                        download = download,
                        onDelete = { viewModel.deleteDownload(download.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    download: DownloadEntity,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        val imageUrl = download.posterPath
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(80.dp)
                    .height(120.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.title,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp)
            )
            if (download.seasonNumber != null && download.episodeNumber != null) {
                Text(
                    text = "S${String.format("%02d", download.seasonNumber)}E${String.format("%02d", download.episodeNumber)}" +
                            if (!download.episodeTitle.isNullOrBlank()) " — ${download.episodeTitle}" else "",
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Status
            when (download.status) {
                "completed" -> {
                    val sizeStr = formatFileSize(download.fileSizeBytes)
                    Text(
                        text = "✓ Completed · $sizeStr",
                        color = Pink,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
                "downloading" -> {
                    LinearProgressIndicator(
                        progress = { download.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Pink,
                        trackColor = BackgroundElevated
                    )
                    Text(
                        text = "${download.progress}%",
                        color = TextSecondary,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
                "failed" -> {
                    Text(
                        text = "Failed: ${download.errorMessage ?: "Unknown error"}",
                        color = androidx.compose.ui.graphics.Color.Red,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
                "queued" -> {
                    Text(
                        text = "Queued",
                        color = TextSecondary,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }

        // Delete button
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete download",
                tint = TextSecondary
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
