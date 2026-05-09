package com.streame.tv.data.repository

import com.streame.tv.data.model.MediaType
import com.streame.tv.util.Constants
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Watch history entry (local only — no Supabase)
 */
data class WatchHistoryEntry(
    val id: String? = null,
    val user_id: String,
    val profile_id: String? = null,
    val media_type: String, // "movie" or "tv"
    val show_tmdb_id: Int,
    val show_trakt_id: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val trakt_episode_id: Int? = null,
    val tmdb_episode_id: Int? = null,
    val title: String? = null,
    val episode_title: String? = null,
    val progress: Float = 0f, // 0.0-1.0
    val duration_seconds: Long = 0,
    val position_seconds: Long = 0,
    val paused_at: String? = null,
    val updated_at: String? = null,
    val source: String? = null,
    val backdrop_path: String? = null,
    val poster_path: String? = null
)

/**
 * Repository for watch history — local in-memory cache only.
 * Trakt playback API provides continue-watching data.
 */
@Singleton
class WatchHistoryRepository @Inject constructor(
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val profileManager: ProfileManager,
    private val watchProgressSyncService: com.streame.tv.data.sync.WatchProgressSyncService,
    private val authManager: com.streame.tv.data.repository.AuthManager,
    private val invalidationBus: com.streame.tv.data.sync.CloudSyncInvalidationBus
) {
    @Volatile
    private var cachedContinueWatching: List<WatchHistoryEntry> = emptyList()
    private val cachedContinueWatchingByProfile = ConcurrentHashMap<String, List<WatchHistoryEntry>>()
    private val cachedWatchHistoryByProfile = ConcurrentHashMap<String, List<WatchHistoryEntry>>()

    /** Emits Unit whenever cloud data is merged into the local cache. Uses replay=1 so late subscribers still receive the event. */
    private val _cloudMergeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1)
    val cloudMergeEvents: SharedFlow<Unit> = _cloudMergeEvents.asSharedFlow()

    private fun currentProfileId(): String = profileManager.getProfileIdSync().ifBlank { "default" }

    private fun profileHistorySource(base: String): String {
        val profileId = currentProfileId()
        return "profile:$profileId:$base"
    }

    private fun filterByProfile(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> {
        val profileId = currentProfileId()
        val isDefault = profileManager.isDefaultProfile()
        return entries.filter { entry ->
            if (!entry.profile_id.isNullOrBlank()) {
                entry.profile_id == profileId
            } else {
                isDefault
            }
        }
    }

    /**
     * Save watch progress to local cache
     */
    suspend fun saveProgress(
        mediaType: MediaType,
        tmdbId: Int,
        title: String,
        poster: String?,
        backdrop: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        progress: Float,
        duration: Long,
        position: Long
    ) {
        val userId = authRepositoryProvider.get().getCurrentUserId() ?: return

        val entry = WatchHistoryEntry(
            user_id = userId,
            profile_id = currentProfileId(),
            media_type = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            show_tmdb_id = tmdbId,
            title = title,
            poster_path = poster,
            backdrop_path = backdrop,
            season = season,
            episode = episode,
            episode_title = episodeTitle,
            progress = progress,
            duration_seconds = duration,
            position_seconds = position,
            source = profileHistorySource("Streame")
        )

        val profileId = currentProfileId()
        val nowIso = Instant.now().toString()
        val cachedEntry = entry.copy(
            paused_at = nowIso,
            updated_at = nowIso
        )
        val profileCache = cachedContinueWatchingByProfile[profileId].orEmpty()
        cachedContinueWatching = if (isEntryInProgress(cachedEntry)) {
            listOf(cachedEntry) + profileCache.filterNot { existing ->
                existing.media_type == cachedEntry.media_type &&
                    existing.show_tmdb_id == cachedEntry.show_tmdb_id
            }
        } else {
            profileCache.filterNot { existing ->
                existing.media_type == cachedEntry.media_type &&
                    existing.show_tmdb_id == cachedEntry.show_tmdb_id
            }
        }
        cachedContinueWatchingByProfile[profileId] = cachedContinueWatching

        // Push to Supabase if authenticated
        pushWatchProgressToRemote()
        invalidationBus.markDirty(
            com.streame.tv.data.sync.CloudSyncScope.WATCH_PROGRESS,
            reason = "updateContinueWatching"
        )
    }

    @Volatile
    private var cachedWatchHistory: List<WatchHistoryEntry> = emptyList()

    /**
     * Get watch history from local cache
     */
    suspend fun getWatchHistory(): List<WatchHistoryEntry> {
        val profileId = currentProfileId()
        return cachedWatchHistoryByProfile[profileId].orEmpty()
    }

    /**
     * Get continue watching items from local cache
     */
    suspend fun getContinueWatching(): List<WatchHistoryEntry> {
        val profileId = currentProfileId()
        return cachedContinueWatchingByProfile[profileId].orEmpty()
    }

    /**
     * Get progress for a specific item from local cache
     */
    suspend fun getProgress(
        mediaType: MediaType,
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ): WatchHistoryEntry? {
        val profileId = currentProfileId()
        val mediaTypeKey = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        return cachedContinueWatchingByProfile[profileId]
            .orEmpty()
            .filter { it.media_type == mediaTypeKey && it.show_tmdb_id == tmdbId }
            .firstOrNull()
    }

    /**
     * Get the latest in-progress entry for a show/movie from local cache
     */
    suspend fun getLatestProgress(
        mediaType: MediaType,
        tmdbId: Int
    ): WatchHistoryEntry? {
        val profileId = currentProfileId()
        val mediaTypeKey = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        return cachedContinueWatchingByProfile[profileId]
            .orEmpty()
            .filter { it.media_type == mediaTypeKey && it.show_tmdb_id == tmdbId && isEntryInProgress(it) }
            .maxByOrNull { entry ->
                parseEpoch(entry.updated_at).coerceAtLeast(parseEpoch(entry.paused_at))
            }
    }

    /**
     * Remove item from watch history (local cache only)
     */
    suspend fun removeFromHistory(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ) {
        val profileId = currentProfileId()
        cachedContinueWatchingByProfile[profileId] = cachedContinueWatchingByProfile[profileId]
            .orEmpty()
            .filterNot { it.show_tmdb_id == tmdbId }
        cachedWatchHistoryByProfile[profileId] = cachedWatchHistoryByProfile[profileId]
            .orEmpty()
            .filterNot { it.show_tmdb_id == tmdbId }
        invalidationBus.markDirty(
            com.streame.tv.data.sync.CloudSyncScope.WATCH_PROGRESS,
            reason = "removeFromHistory"
        )
    }

    /**
     * Clear all watch history (local cache only)
     */
    suspend fun clearHistory() {
        val profileId = currentProfileId()
        cachedContinueWatchingByProfile[profileId] = emptyList()
        cachedWatchHistoryByProfile[profileId] = emptyList()
        invalidationBus.markDirty(
            com.streame.tv.data.sync.CloudSyncScope.WATCH_PROGRESS,
            reason = "clearHistory"
        )
    }

    fun clearProfileCaches() {
        cachedContinueWatching = emptyList()
        cachedWatchHistory = emptyList()
        cachedContinueWatchingByProfile.clear()
        cachedWatchHistoryByProfile.clear()
    }

    /**
     * Merge cloud watch progress items into local cache.
     * For each progress key, keeps the entry with the higher position.
     */
    suspend fun mergeFromCloud(cloudItems: List<com.streame.tv.data.remote.supabase.SupabaseWatchProgress>) {
        if (cloudItems.isEmpty()) return
        val profileId = currentProfileId()
        val existing = cachedContinueWatchingByProfile[profileId].orEmpty().toMutableList()
        android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: ${cloudItems.size} cloud items, ${existing.size} existing cache entries for profile=$profileId")
        val existingKeys = existing.associateBy { entry ->
            if (entry.media_type == "tv") "tv:${entry.show_tmdb_id}:${entry.season}:${entry.episode}" else "movie:${entry.show_tmdb_id}"
        }
        var merged = 0
        cloudItems.forEach { cloud ->
            val tmdbId = cloud.contentId.toIntOrNull()
            if (tmdbId == null) {
                android.util.Log.w("WatchHistoryRepo", "mergeFromCloud: skipping item with non-numeric contentId=${cloud.contentId}, key=${cloud.progressKey}")
                return@forEach
            }
            val userId = authManager.currentSupabaseUserId
            if (userId == null) {
                android.util.Log.w("WatchHistoryRepo", "mergeFromCloud: skipping item because currentSupabaseUserId=null, contentId=${cloud.contentId}")
                return@forEach
            }
            val key = cloud.progressKey
            android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: processing contentId=${cloud.contentId} tmdbId=$tmdbId key=$key contentType=${cloud.contentType} position=${cloud.position} duration=${cloud.duration}")
            val cloudEntry = WatchHistoryEntry(
                user_id = userId,
                profile_id = profileId,
                media_type = cloud.contentType,
                show_tmdb_id = tmdbId,
                season = cloud.season,
                episode = cloud.episode,
                progress = if (cloud.duration > 0) (cloud.position.toFloat() / cloud.duration.toFloat()).coerceIn(0f, 1f) else 0f,
                duration_seconds = cloud.duration,
                position_seconds = cloud.position,
                updated_at = java.time.Instant.ofEpochMilli(cloud.lastWatched).toString(),
                paused_at = java.time.Instant.ofEpochMilli(cloud.lastWatched).toString(),
                source = profileHistorySource("CloudSync")
            )
            val existingEntry = existingKeys[key]
            if (existingEntry == null) {
                existing.add(cloudEntry)
                merged++
                android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: added new entry for key=$key")
            } else if (cloud.position > (existingEntry.position_seconds ?: 0L)) {
                existing.removeAll { it.show_tmdb_id == tmdbId && it.media_type == cloud.contentType && it.season == cloud.season && it.episode == cloud.episode }
                existing.add(cloudEntry)
                merged++
                android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: updated existing entry for key=$key, cloud.position=${cloud.position} > existing=${existingEntry.position_seconds}")
            } else {
                android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: skipped key=$key, cloud.position=${cloud.position} <= existing=${existingEntry.position_seconds}")
            }
        }
        if (merged > 0) {
            cachedContinueWatchingByProfile[profileId] = existing
            _cloudMergeEvents.tryEmit(Unit)
            android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: merged $merged items, emitting cloudMergeEvent")
        } else {
            android.util.Log.d("WatchHistoryRepo", "mergeFromCloud: 0 items merged (all existing or no new data)")
        }
    }

    private fun parseEpoch(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Push current watch progress to Supabase if authenticated.
     * Fire-and-forget — failures are logged but don't block the user.
     */
    private suspend fun pushWatchProgressToRemote() {
        if (!authManager.isAuthenticated) return
        try {
            val profileId = currentProfileId()
            val entries = cachedContinueWatchingByProfile[profileId].orEmpty()
            val items = entries.map { entry ->
                com.streame.tv.data.remote.supabase.SupabaseWatchProgress(
                    userId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = true) ?: "",
                    contentId = entry.show_tmdb_id.toString(),
                    contentType = entry.media_type,
                    videoId = if (entry.media_type == "tv") "${entry.show_tmdb_id}:${entry.season}:${entry.episode}" else entry.show_tmdb_id.toString(),
                    season = entry.season,
                    episode = entry.episode,
                    position = entry.position_seconds ?: 0L,
                    duration = entry.duration_seconds ?: 0L,
                    lastWatched = System.currentTimeMillis(),
                    progressKey = if (entry.media_type == "tv") "tv:${entry.show_tmdb_id}:${entry.season}:${entry.episode}" else "movie:${entry.show_tmdb_id}",
                    profileId = resolveProfileId()
                )
            }
            watchProgressSyncService.pushToRemote(items, resolveProfileId())
        } catch (_: Exception) { }
    }

    private suspend fun resolveProfileId(): Int {
        val activeId = profileManager.getProfileId()
        if (activeId == "default") return 1
        val profiles = profileManager.getProfileList()
        val index = profiles.indexOfFirst { it.id == activeId }
        return if (index >= 0) index + 1 else 1
    }

    private fun isEntryInProgress(entry: WatchHistoryEntry): Boolean {
        val threshold = Constants.WATCHED_THRESHOLD / 100f
        val normalizedProgress = entry.progress.coerceIn(0f, 1f)
        val normalizedDuration = normalizeStoredSeconds(entry.duration_seconds)
        val normalizedPosition = normalizeStoredSeconds(entry.position_seconds)
        val derivedProgress = when {
            normalizedProgress > 0f -> normalizedProgress
            normalizedDuration > 0L && normalizedPosition > 0L ->
                (normalizedPosition.toFloat() / normalizedDuration.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

        return when {
            derivedProgress > 0f -> derivedProgress < threshold
            else -> normalizedPosition > 0L
        }
    }

    private fun normalizeStoredSeconds(value: Long): Long {
        return if (value > 86_400L) value / 1000L else value
    }
}
