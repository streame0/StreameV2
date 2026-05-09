package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.StreamRepository
import com.streame.tv.data.repository.SupabaseAuthState
import com.streame.tv.data.repository.WatchHistoryRepository
import com.streame.tv.data.repository.WatchlistRepository
import com.streame.tv.data.repository.TraktRepository
import com.streame.tv.data.sync.CloudSyncInvalidationBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val addonSyncService: AddonSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val collectionSyncService: CollectionSyncService,
    private val profileSyncService: ProfileSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val streamRepository: StreamRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val traktRepository: TraktRepository,
    private val invalidationBus: CloudSyncInvalidationBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var foregroundPullJob: Job? = null
    private var lastForegroundPullAtMs: Long = 0L

    companion object {
        private const val FOREGROUND_PULL_DELAY_MS = 1_000L
        private const val FOREGROUND_PULL_MIN_INTERVAL_MS = 15_000L
    }

    /**
     * Resolve the current profile's integer ID (1-based) for Supabase.
     */
    private suspend fun resolveProfileIdFromManager(): Int {
        val activeId = profileManager.getProfileId()
        if (activeId == "default") return 1
        val profiles = profileManager.getProfileList()
        val index = profiles.indexOfFirst { it.id == activeId }
        return if (index >= 0) index + 1 else 1
    }

    fun observeAndSync(authStateFlow: StateFlow<SupabaseAuthState>) {
        scope.launch {
            authStateFlow.collect { state ->
                if (state is SupabaseAuthState.FullAccount) {
                    pullAllData()
                }
            }
        }
    }

    suspend fun pullAllData() {
        if (!authManager.isAuthenticated) return
        try {
            Log.d(TAG, "Starting full data pull from remote")

            val profileId = resolveProfileIdFromManager()

            // Suppress invalidation events while applying remote state
            invalidationBus.suppressDuringRemoteApply {
                // Parallel pull for independent data
                kotlinx.coroutines.coroutineScope {
                    launch { applyRemoteAddons(profileId) }
                    launch { librarySyncService.pullFromRemote(profileId) }
                    launch { collectionSyncService.pullFromRemote(profileId) }
                    launch { profileSettingsSyncService.pullFromRemote(profileId) }
                    launch { profileSyncService.pullFromRemote() }
                    launch { homeCatalogSettingsSyncService.pullFromRemote(profileId) }
                }

                // Sequential pull for watch progress (large table, pull last)
                watchProgressSyncService.pullFromRemote(profileId)
                watchedItemsSyncService.pullFromRemote(profileId)
            }

            Log.d(TAG, "Full data pull completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed during full data pull", e)
        }
    }

    /**
     * Request a foreground sync — throttled to once per 60 seconds with a 2.5s delay.
     * Syncs all data types for full cross-device consistency.
     */
    fun requestForegroundPull(force: Boolean = false) {
        if (!authManager.isAuthenticated) return

        val now = System.currentTimeMillis()
        if (!force && foregroundPullJob?.isActive == true) return
        if (!force && now - lastForegroundPullAtMs < FOREGROUND_PULL_MIN_INTERVAL_MS) return

        foregroundPullJob = scope.launch {
            if (!force) {
                delay(FOREGROUND_PULL_DELAY_MS)
            }
            if (!authManager.isAuthenticated) return@launch

            val profileId = resolveProfileIdFromManager()
            lastForegroundPullAtMs = System.currentTimeMillis()
            Log.d(TAG, "Foreground pull: all data for profile $profileId")

            invalidationBus.suppressDuringRemoteApply {
                kotlinx.coroutines.coroutineScope {
                    launch {
                        runCatching { watchProgressSyncService.pullFromRemote(profileId) }
                            .onFailure { Log.e(TAG, "Foreground watch progress pull failed", it) }
                    }
                    launch {
                        runCatching { librarySyncService.pullFromRemote(profileId) }
                            .onFailure { Log.e(TAG, "Foreground library pull failed", it) }
                    }
                    launch {
                        runCatching { watchedItemsSyncService.pullFromRemote(profileId) }
                            .onFailure { Log.e(TAG, "Foreground watched items pull failed", it) }
                    }
                    launch {
                        runCatching { collectionSyncService.pullFromRemote(profileId) }
                            .onFailure { Log.e(TAG, "Foreground collections pull failed", it) }
                    }
                    launch {
                        runCatching { applyRemoteAddons(profileId) }
                            .onFailure { Log.e(TAG, "Foreground addons pull failed", it) }
                    }
                    launch {
                        runCatching { profileSettingsSyncService.pullFromRemote(profileId) }
                            .onFailure { Log.e(TAG, "Foreground profile settings pull failed", it) }
                    }
                    launch {
                        runCatching { homeCatalogSettingsSyncService.pullFromRemote(profileId) }
                            .onFailure { Log.e(TAG, "Foreground home catalog settings pull failed", it) }
                    }
                }
            }
        }
    }

    /**
     * Fetch remote addon URLs and install any that are missing locally.
     */
    private suspend fun applyRemoteAddons(profileId: Int) {
        val result = addonSyncService.getRemoteAddonUrls(profileId)
        val remoteUrls = result.getOrNull() ?: return
        if (remoteUrls.isEmpty()) return
        val localUrls = streamRepository.installedAddons.first()
            .mapNotNull { it.url }
            .toSet()
        val missingUrls = remoteUrls.filter { it !in localUrls }
        if (missingUrls.isNotEmpty()) {
            Log.d(TAG, "Installing ${missingUrls.size} missing addons from cloud")
            streamRepository.ensureCustomAddons(missingUrls)
        }
    }

    suspend fun pushAllData(
        addonUrls: List<String>,
        watchProgressItems: List<com.streame.tv.data.remote.supabase.SupabaseWatchProgress>,
        libraryItems: List<com.streame.tv.data.remote.supabase.SupabaseLibraryItem>,
        watchedItems: List<com.streame.tv.data.remote.supabase.SupabaseWatchedItem>,
        profileId: Int? = null
    ) {
        if (!authManager.isAuthenticated) return
        try {
            val effectiveProfileId = profileId ?: resolveProfileIdFromManager()
            Log.d(TAG, "Starting full data push to remote")
            addonSyncService.pushToRemote(addonUrls, effectiveProfileId)
            watchProgressSyncService.pushToRemote(watchProgressItems, effectiveProfileId)
            librarySyncService.pushToRemote(libraryItems, effectiveProfileId)
            watchedItemsSyncService.pushToRemote(watchedItems, effectiveProfileId)
            Log.d(TAG, "Full data push completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed during full data push", e)
        }
    }

    /**
     * Gather current data from all repositories and push to remote.
     * Called by [CloudSyncCoordinator] when local data changes.
     */
    suspend fun pushAllDataFromRepositories() {
        if (!authManager.isAuthenticated) return
        try {
            val profileId = resolveProfileIdFromManager()
            val userId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = true) ?: return

            // Gather addon URLs
            val addons = streamRepository.installedAddons.first()
            val addonUrls = addons.mapNotNull { it.url }

            // Gather watch progress from cache
            val progressEntries = watchHistoryRepository.getContinueWatching()
            val progressItems = progressEntries.map { entry ->
                com.streame.tv.data.remote.supabase.SupabaseWatchProgress(
                    userId = userId,
                    contentId = entry.show_tmdb_id.toString(),
                    contentType = entry.media_type,
                    videoId = if (entry.media_type == "tv") "${entry.show_tmdb_id}:${entry.season}:${entry.episode}" else entry.show_tmdb_id.toString(),
                    season = entry.season,
                    episode = entry.episode,
                    position = entry.position_seconds ?: 0L,
                    duration = entry.duration_seconds ?: 0L,
                    lastWatched = System.currentTimeMillis(),
                    progressKey = if (entry.media_type == "tv") "tv:${entry.show_tmdb_id}:${entry.season}:${entry.episode}" else "movie:${entry.show_tmdb_id}",
                    profileId = profileId,
                    lastAddonId = entry.last_addon_id,
                    lastSourceName = entry.last_source_name,
                    lastBingeGroup = entry.last_binge_group
                )
            }

            // Gather library (watchlist) items
            val watchlistItems = watchlistRepository.exportWatchlistForProfile(
                profileManager.getProfileId().ifBlank { "default" }
            )
            val libraryItems = watchlistItems.map { local ->
                com.streame.tv.data.remote.supabase.SupabaseLibraryItem(
                    contentId = local.tmdbId.toString(),
                    contentType = local.mediaType,
                    name = local.title,
                    poster = local.posterPath,
                    posterShape = "POSTER",
                    background = local.backdropPath,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    profileId = profileId
                )
            }

            // Gather watched items from Trakt
            val watchedMovies = traktRepository.exportLocalWatchedMoviesForProfiles(
                listOf(profileManager.getProfileId().ifBlank { "default" })
            ).values.flatten()
            val watchedEpisodes = traktRepository.exportLocalWatchedEpisodesForProfiles(
                listOf(profileManager.getProfileId().ifBlank { "default" })
            ).values.flatten()
            val watchedItems = watchedMovies.map { tmdbId ->
                com.streame.tv.data.remote.supabase.SupabaseWatchedItem(
                    userId = userId,
                    contentId = tmdbId.toString(),
                    contentType = "movie",
                    watchedAt = System.currentTimeMillis(),
                    profileId = profileId
                )
            } + watchedEpisodes.map { key ->
                com.streame.tv.data.remote.supabase.SupabaseWatchedItem(
                    userId = userId,
                    contentId = key,
                    contentType = "tv",
                    watchedAt = System.currentTimeMillis(),
                    profileId = profileId
                )
            }

            pushAllData(
                addonUrls = addonUrls,
                watchProgressItems = progressItems,
                libraryItems = libraryItems,
                watchedItems = watchedItems,
                profileId = profileId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to gather and push data from repositories", e)
        }
    }
}
