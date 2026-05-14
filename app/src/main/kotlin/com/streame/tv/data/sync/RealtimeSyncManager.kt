package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.PostgresChangeFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Cloud sync WebSocket connection status for the UI indicator. */
enum class CloudSyncStatus { CONNECTED, RECONNECTING, NOT_SIGNED_IN }

/**
 * Manages a Supabase Realtime connection to receive instant notifications
 * when data changes on another device.
 *
 * Subscribes to Postgres Change events on these tables:
 * - `watch_progress` → [CloudSyncScope.WATCH_PROGRESS]
 * - `library` → [CloudSyncScope.WATCHLIST]
 * - `watched_items` → [CloudSyncScope.WATCHED_ITEMS]
 * - `collections` → [CloudSyncScope.COLLECTIONS]
 * - `home_catalog_settings` → [CloudSyncScope.HOME_CATALOG_SETTINGS]
 * - `addons` → [CloudSyncScope.ADDONS]
 * - `profile_settings` → [CloudSyncScope.PROFILE_SETTINGS]
 *
 * Uses the official Supabase Realtime SDK which handles:
 * - Heartbeats and reconnection
 * - Token refresh
 * - Message parsing
 * - Exponential backoff
 *
 * Also runs a periodic fallback sync every 20 seconds.
 */
@Singleton
class RealtimeSyncManager @Inject constructor(
    private val startupSyncService: StartupSyncService,
    private val authManager: AuthManager,
    private val realtime: Realtime
) {
    companion object {
        private const val TAG = "RealtimeSync"
        private const val PERIODIC_SYNC_INTERVAL_MS = 20_000L
        private const val CHANNEL_NAME = "streame-sync"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var lastPushTimestamp = 0L

    private var periodicSyncJob: Job? = null
    private var channelJob: Job? = null
    @Volatile
    private var activeChannel: RealtimeChannel? = null

    private val _syncStatusFlow = MutableStateFlow(CloudSyncStatus.NOT_SIGNED_IN)
    val syncStatusFlow: StateFlow<CloudSyncStatus> = _syncStatusFlow.asStateFlow()

    /** Mark that a push just happened, so incoming realtime echo can be ignored. */
    fun markPush() {
        lastPushTimestamp = System.currentTimeMillis()
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting realtime sync")
        _syncStatusFlow.value = CloudSyncStatus.RECONNECTING
        connectChannels()
        startPeriodicSync()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping realtime sync")
        channelJob?.cancel()
        periodicSyncJob?.cancel()
        scope.launch {
            activeChannel?.let { runCatching { realtime.removeChannel(it) } }
            activeChannel = null
        }
        _syncStatusFlow.value = CloudSyncStatus.NOT_SIGNED_IN
    }

    // ── Channel Connection ────────────────────────────────────────

    private fun connectChannels() {
        channelJob?.cancel()
        channelJob = scope.launch {
            if (!authManager.isAuthenticated) {
                Log.w(TAG, "Not logged in, skipping realtime connection")
                _syncStatusFlow.value = CloudSyncStatus.NOT_SIGNED_IN
                return@launch
            }

            try {
                val channel = realtime.channel(CHANNEL_NAME) {
                    // Broadcast config — receive own events for echo detection
                    broadcast { }
                    // Presence config
                    presence { }
                }
                activeChannel = channel

                // Subscribe to postgres changes for each table
                val tableScopes = mapOf(
                    "watch_progress" to CloudSyncScope.WATCH_PROGRESS,
                    "library" to CloudSyncScope.WATCHLIST,
                    "watched_items" to CloudSyncScope.WATCHED_ITEMS,
                    "collections" to CloudSyncScope.COLLECTIONS,
                    "home_catalog_settings" to CloudSyncScope.HOME_CATALOG_SETTINGS,
                    "addons" to CloudSyncScope.ADDONS,
                    "profile_settings" to CloudSyncScope.PROFILE_SETTINGS,
                )

                for ((table, syncScope) in tableScopes) {
                    channel.postgresChangeFlow<PostgresAction>("public") {
                        this.table = table
                    }.collect { action ->
                        handleRealtimeEvent(syncScope, action)
                    }
                }

                // Subscribe and wait for connection
                channel.subscribe()
                _syncStatusFlow.value = CloudSyncStatus.CONNECTED
                Log.i(TAG, "Realtime channel connected")

                // Keep the channel alive while running
                while (isActive && isRunning.get()) {
                    delay(30_000L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Realtime connection failed: ${e.message}")
                _syncStatusFlow.value = CloudSyncStatus.RECONNECTING
                // Retry after delay
                if (isRunning.get()) {
                    delay(5_000L)
                    connectChannels()
                }
            }
        }
    }

    // ── Event Handling ────────────────────────────────────────────

    private fun handleRealtimeEvent(scope: CloudSyncScope, action: Any) {
        // Ignore events that are echoes of our own recent push
        if (System.currentTimeMillis() - lastPushTimestamp < 3_000L) {
            Log.d(TAG, "Ignoring realtime echo for $scope (recent push)")
            return
        }

        Log.d(TAG, "Realtime event for $scope")
        this@RealtimeSyncManager.scope.launch {
            runCatching { startupSyncService.pullScope(scope) }
                .onFailure { Log.w(TAG, "Targeted pull failed for $scope after realtime event", it) }
        }
    }

    // ── Periodic Sync ────────────────────────────────────────────────

    private fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_SYNC_INTERVAL_MS)
                if (authManager.isAuthenticated) {
                    runCatching { startupSyncService.requestForegroundPull() }
                        .onFailure { Log.w(TAG, "Periodic sync failed", it) }
                }
            }
        }
    }
}
