package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudSyncCoordinator"

/**
 * Coordinates automatic cloud pushes in response to local data changes.
 *
 * Listens to [CloudSyncInvalidationBus] events and, after a short debounce,
 * triggers a full or targeted push to Supabase. This ensures that local
 * changes (watchlist add, catalog reorder, settings change, etc.) are
 * automatically reflected in the cloud without requiring manual "Force Sync".
 *
 * The debounce interval varies by scope — watch progress pushes more
 * aggressively (5 s) than catalog changes (15 s) to balance responsiveness
 * with API call volume.
 */
@Singleton
class CloudSyncCoordinator @Inject constructor(
    private val invalidationBus: CloudSyncInvalidationBus,
    private val startupSyncService: StartupSyncService,
    private val authManager: AuthManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null
    private var flushJob: Job? = null

    @Volatile
    private var isPushDirty = false

    @Volatile
    private var started = false

    /** Whether the last push attempt failed and needs retry on next opportunity. */
    val needsRetry: Boolean get() = isPushDirty

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Starting cloud sync coordinator")
        collectorJob = scope.launch {
            invalidationBus.events.collectLatest { invalidation ->
                if (!authManager.isAuthenticated) return@collectLatest
                isPushDirty = true
                scheduleFlush(invalidation)
            }
        }
    }

    fun stop() {
        started = false
        collectorJob?.cancel()
        flushJob?.cancel()
        collectorJob = null
        flushJob = null
        Log.i(TAG, "Stopped cloud sync coordinator")
    }

    /**
     * Retry a previously failed push. Called from lifecycle ON_RESUME
     * or from the RealtimeSyncManager when the connection is restored.
     */
    fun retryIfDirty() {
        if (!isPushDirty || !authManager.isAuthenticated) return
        scope.launch {
            Log.d(TAG, "Retrying dirty push")
            runCatching { startupSyncService.pushAllDataFromRepositories() }
                .onSuccess { isPushDirty = false }
                .onFailure { Log.w(TAG, "Dirty push retry failed: ${it.message}") }
        }
    }

    private fun scheduleFlush(invalidation: CloudSyncInvalidation) {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(debounceMsFor(invalidation.scope))
            if (!authManager.isAuthenticated) return@launch
            runCatching { startupSyncService.pushAllDataFromRepositories() }
                .onSuccess {
                    isPushDirty = false
                    Log.d(TAG, "Auto-push completed for ${invalidation.scope}")
                }
                .onFailure { error ->
                    Log.w(TAG, "Auto-push failed after ${invalidation.scope}: ${error.message}")
                    isPushDirty = true
                }
        }
    }

    private fun debounceMsFor(scope: CloudSyncScope): Long = when (scope) {
        CloudSyncScope.WATCH_PROGRESS -> 5_000L
        CloudSyncScope.WATCHLIST -> 8_000L
        CloudSyncScope.WATCHED_ITEMS -> 8_000L
        CloudSyncScope.ADDONS -> 10_000L
        CloudSyncScope.CATALOGS -> 15_000L
        CloudSyncScope.PROFILE_SETTINGS -> 15_000L
        CloudSyncScope.PROFILES -> 20_000L
        CloudSyncScope.COLLECTIONS -> 15_000L
        CloudSyncScope.HOME_CATALOG_SETTINGS -> 15_000L
        CloudSyncScope.ACCOUNT -> 20_000L
    }
}
