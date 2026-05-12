package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.local.SyncQueueDao
import com.streame.tv.data.local.SyncQueueEntity
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.WatchHistoryRepository
import com.streame.tv.data.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "CloudSyncCoordinator"

/**
 * Coordinates automatic cloud pushes in response to local data changes.
 *
 * Listens to [CloudSyncInvalidationBus] events and, after a short debounce,
 * triggers a **targeted** push to Supabase for only the changed scope.
 * This avoids the double-push problem (repo pushes + coordinator pushes)
 * and reduces API call volume by only pushing what actually changed.
 *
 * For the most frequent scopes (WATCH_PROGRESS, WATCHLIST), we push only
 * the changed data. For less frequent scopes, we fall back to
 * [StartupSyncService.pushAllDataFromRepositories] which pushes everything.
 */
@Singleton
class CloudSyncCoordinator @Inject constructor(
    private val invalidationBus: CloudSyncInvalidationBus,
    private val startupSyncService: StartupSyncService,
    private val authManager: AuthManager,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val syncQueueDao: SyncQueueDao,
    private val watchHistoryRepositoryProvider: Provider<WatchHistoryRepository>,
    private val watchlistRepositoryProvider: Provider<WatchlistRepository>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null
    private val pendingScopes = mutableSetOf<CloudSyncScope>()
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
                synchronized(pendingScopes) { pendingScopes.add(invalidation.scope) }
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
     * Drains the offline queue and retries each scope.
     */
    fun retryIfDirty() {
        if (!authManager.isAuthenticated) return
        scope.launch {
            // First drain the offline queue
            val queued = runCatching { syncQueueDao.getAll() }.getOrDefault(emptyList())
            if (queued.isNotEmpty()) {
                Log.d(TAG, "Retrying ${queued.size} queued sync operations")
                for (entry in queued) {
                    val syncScope = runCatching { CloudSyncScope.valueOf(entry.scope) }.getOrNull()
                    if (syncScope == null) {
                        syncQueueDao.delete(entry.id)
                        continue
                    }
                    val result = runCatching { pushScope(syncScope) }
                    if (result.isSuccess) {
                        syncQueueDao.delete(entry.id)
                        Log.d(TAG, "Queued push succeeded for $syncScope")
                    } else {
                        val newRetryCount = entry.retryCount + 1
                        if (newRetryCount >= MAX_RETRY_COUNT) {
                            Log.w(TAG, "Dropping queued push for $syncScope after $newRetryCount retries")
                            syncQueueDao.delete(entry.id)
                        } else {
                            syncQueueDao.updateRetry(entry.id, newRetryCount, result.exceptionOrNull()?.message, System.currentTimeMillis())
                        }
                    }
                }
            }
            // Also retry in-memory dirty flag
            if (isPushDirty) {
                Log.d(TAG, "Retrying in-memory dirty push")
                runCatching { startupSyncService.pushAllDataFromRepositories() }
                    .onSuccess { isPushDirty = false }
                    .onFailure { Log.w(TAG, "Dirty push retry failed: ${it.message}") }
            }
        }
    }

    private fun scheduleFlush(invalidation: CloudSyncInvalidation) {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(debounceMsFor(invalidation.scope))
            if (!authManager.isAuthenticated) return@launch
            val scopesToFlush: Set<CloudSyncScope>
            synchronized(pendingScopes) {
                scopesToFlush = pendingScopes.toSet()
                pendingScopes.clear()
            }
            var allSuccess = true
            for (s in scopesToFlush) {
                val result = runCatching { pushScope(s) }
                if (result.isFailure) {
                    Log.w(TAG, "Targeted push failed for $s: ${result.exceptionOrNull()?.message}")
                    allSuccess = false
                    // Persist to offline queue for later retry
                    runCatching {
                        syncQueueDao.insert(SyncQueueEntity(scope = s.name))
                    }
                } else {
                    // On success, clear any previous queue entries for this scope
                    runCatching { syncQueueDao.deleteByScope(s.name) }
                }
            }
            isPushDirty = !allSuccess
            if (allSuccess) Log.d(TAG, "Targeted push completed for scopes: $scopesToFlush")
        }
    }

    /**
     * Push only the data for the given scope — targeted sync.
     * WATCH_PROGRESS and WATCHLIST use targeted push (most frequent, biggest impact).
     * Other scopes fall back to full push via [StartupSyncService].
     */
    private suspend fun pushScope(scope: CloudSyncScope) {
        when (scope) {
            CloudSyncScope.WATCH_PROGRESS -> {
                val repo = watchHistoryRepositoryProvider.get()
                val items = repo.getAllForPush()
                val profileId = repo.resolveProfileIdPublic()
                watchProgressSyncService.pushToRemote(items, profileId)
            }
            CloudSyncScope.WATCHLIST -> {
                val items = watchlistRepositoryProvider.get().getAllForPush()
                val profileId = items.firstOrNull()?.profileId ?: 1
                librarySyncService.pushToRemote(items, profileId)
            }
            else -> {
                // Less frequent scopes — full push is acceptable
                startupSyncService.pushAllDataFromRepositories()
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

    companion object {
        private const val MAX_RETRY_COUNT = 5
    }
}
