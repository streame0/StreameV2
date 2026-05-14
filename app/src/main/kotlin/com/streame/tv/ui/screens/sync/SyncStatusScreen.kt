package com.streame.tv.ui.screens.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.streame.tv.data.sync.CloudSyncStatus
import com.streame.tv.data.sync.RealtimeSyncManager
import com.streame.tv.data.sync.StartupSyncService
import com.streame.tv.data.local.SyncQueueDao
import com.streame.tv.data.local.SyncQueueEntity
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.ui.theme.BackgroundElevated
import com.streame.tv.ui.theme.Pink
import com.streame.tv.ui.theme.TextPrimary
import com.streame.tv.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val realtimeSyncManager: RealtimeSyncManager,
    private val startupSyncService: StartupSyncService,
    private val syncQueueDao: SyncQueueDao,
    private val authManager: AuthManager
) : ViewModel() {
    val syncStatus: StateFlow<CloudSyncStatus> = realtimeSyncManager.syncStatusFlow

    val pendingQueueCount: StateFlow<Int> = syncQueueDao.getAllFlow()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pendingQueue: StateFlow<List<SyncQueueEntity>> = syncQueueDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isAuthenticated: Boolean
        get() = authManager.isAuthenticated

    fun forceFullSync() {
        viewModelScope.launch {
            runCatching { startupSyncService.requestForegroundPull(force = true) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    viewModel: SyncStatusViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val syncStatus by viewModel.syncStatus.collectAsState()
    val pendingCount by viewModel.pendingQueueCount.collectAsState()
    val pendingQueue by viewModel.pendingQueue.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.padding(end = 16.dp).clickable { onBack() }
            )
            Text(
                text = "Cloud Sync Status",
                style = TextStyle(fontSize = 28.sp),
                color = TextPrimary
            )
        }

        // Connection status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            val (icon, tint, label) = when (syncStatus) {
                CloudSyncStatus.CONNECTED -> Triple(Icons.Default.CloudDone, Color(0xFF4CAF50), "Connected")
                CloudSyncStatus.RECONNECTING -> Triple(Icons.Default.CloudSync, Color(0xFFFFC107), "Reconnecting...")
                CloudSyncStatus.NOT_SIGNED_IN -> Triple(Icons.Default.CloudOff, TextSecondary, "Not signed in")
            }
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = label, style = TextStyle(fontSize = 20.sp), color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pending queue
        Text(
            text = "Pending operations: $pendingCount",
            style = TextStyle(fontSize = 16.sp),
            color = TextSecondary
        )

        if (pendingQueue.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            pendingQueue.take(5).forEach { entry ->
                Text(
                    text = "  · ${entry.scope} (retry #${entry.retryCount})${entry.lastError?.let { " — $it" } ?: ""}",
                    style = TextStyle(fontSize = 13.sp),
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }
            if (pendingQueue.size > 5) {
                Text(
                    text = "  ... and ${pendingQueue.size - 5} more",
                    style = TextStyle(fontSize = 13.sp),
                    color = TextSecondary.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Force full sync button
        if (viewModel.isAuthenticated) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Pink,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Force Full Sync",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    color = Pink,
                    modifier = Modifier.clickable { viewModel.forceFullSync() }
                )
            }
            Text(
                text = "Re-downloads all data from the cloud. Use if sync appears stuck.",
                style = TextStyle(fontSize = 13.sp),
                color = TextSecondary.copy(alpha = 0.6f)
            )
        }
    }
}
