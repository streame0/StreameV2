package com.streame.tv.data.repository

import android.util.Log
import com.streame.tv.data.local.WatchHistoryDao
import com.streame.tv.data.local.WatchHistoryEntity
import com.streame.tv.data.model.MediaType
import com.streame.tv.util.Constants
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Watch history entry (local only)
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
    val poster_path: String? = null,
    // Last-played source info for same-source resume
    val last_addon_id: String? = null,
    val last_source_name: String? = null,
    val last_binge_group: String? = null
)

/**
 * Repository for watch history — backed by Room for persistence + in-memory cache for speed.
 * Local progress is saved here and used as a fallback for Trakt Continue Watching.
 */
@Singleton
class WatchHistoryRepository @Inject constructor(
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val profileManager: ProfileManager,
    private val watchHistoryDao: WatchHistoryDao
) {
    @Volatile
    private var cachedContinueWatching: List<WatchHistoryEntry> = emptyList()
    private val cachedContinueWatchingByProfile = ConcurrentHashMap<String, List<WatchHistoryEntry>>()
    private val cachedWatchHistoryByProfile = ConcurrentHashMap<String, List<WatchHistoryEntry>>()

    /** Emits Unit whenever cloud data is merged into the local cache. Uses replay=1 so late subscribers still receive the event. */
    private val _cloudMergeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1)
    val cloudMergeEvents: SharedFlow<Unit> = _cloudMergeEvents.asSharedFlow()

    /** Emits Unit whenever local watch progress is saved, so observers (e.g. HomeViewModel) can refresh Continue Watching. */
    private val _localUpdateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localUpdateEvents: SharedFlow<Unit> = _localUpdateEvents.asSharedFlow()

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
     * Load cached data from Room for the current profile.
     * Called on cold start so Continue Watching is instantly available.
     */
    suspend fun loadFromRoom() {
        val profileId = currentProfileId()
        if (cachedContinueWatchingByProfile.containsKey(profileId)) return // already loaded
        try {
            val entities = withContext(Dispatchers.IO) {
                watchHistoryDao.getAllForProfile(profileId)
            }
            val entries = entities.map { it.toEntry() }
            cachedContinueWatchingByProfile[profileId] = entries
            cachedWatchHistoryByProfile[profileId] = entries
            Log.d(TAG, "loadFromRoom: loaded ${entries.size} entries for profile=$profileId")
        } catch (e: Exception) {
            Log.e(TAG, "loadFromRoom failed", e)
        }
    }

    /**
     * Save watch progress to local cache + Room.
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
        position: Long,
        lastAddonId: String? = null,
        lastSourceName: String? = null,
        lastBingeGroup: String? = null
    ) {
        val userId = authRepositoryProvider.get().getCurrentUserId() ?: ""
        val profileId = currentProfileId()
        val mediaTypeKey = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val progressKey = if (mediaTypeKey == "tv") "tv:$tmdbId:$season:$episode" else "movie:$tmdbId"
        val videoId = if (mediaTypeKey == "tv") "$tmdbId:$season:$episode" else tmdbId.toString()
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.now().toString()

        val entry = WatchHistoryEntry(
            user_id = userId,
            profile_id = profileId,
            media_type = mediaTypeKey,
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
            source = profileHistorySource("Streame"),
            last_addon_id = lastAddonId,
            last_source_name = lastSourceName,
            last_binge_group = lastBingeGroup
        )

        val cachedEntry = entry.copy(
            paused_at = nowIso,
            updated_at = nowIso
        )

        Log.i(TAG, "saveProgress profileId=$profileId userId=$userId progressKey=$progressKey position=${position}s duration=${duration}s progress=${String.format("%.2f", progress)}")

        // Update in-memory cache
        val profileCache = cachedContinueWatchingByProfile[profileId].orEmpty()
        val isSameEntry: (WatchHistoryEntry) -> Boolean = if (entry.media_type == "tv" && entry.season != null && entry.episode != null) {
            { existing ->
                existing.media_type == entry.media_type &&
                    existing.show_tmdb_id == entry.show_tmdb_id &&
                    existing.season == entry.season &&
                    existing.episode == entry.episode
            }
        } else {
            { existing ->
                existing.media_type == entry.media_type &&
                    existing.show_tmdb_id == entry.show_tmdb_id
            }
        }
        cachedContinueWatching = if (isEntryInProgress(cachedEntry)) {
            listOf(cachedEntry) + profileCache.filterNot { isSameEntry(it) }
        } else {
            profileCache.filterNot { isSameEntry(it) }
        }
        cachedContinueWatchingByProfile[profileId] = cachedContinueWatching

        // Persist to Room (non-blocking, best-effort)
        // We use NonCancellable to ensure the progress is saved even if the scope
        // is cancelled (e.g. when the player is closing and the ViewModel is cleared).
        try {
            val entity = WatchHistoryEntity(
                userId = userId,
                profileId = profileId,
                mediaType = mediaTypeKey,
                tmdbId = tmdbId,
                title = title,
                posterPath = poster,
                backdropPath = backdrop,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                progress = (progress * 100).toInt().coerceIn(0, 100),
                durationSeconds = duration,
                positionSeconds = position,
                updatedAt = nowMs,
                source = profileHistorySource("Streame"),
                videoId = videoId,
                progressKey = progressKey,
                lastAddonId = lastAddonId,
                lastSourceName = lastSourceName,
                lastBingeGroup = lastBingeGroup
            )
            withContext(NonCancellable + Dispatchers.IO) {
                watchHistoryDao.upsert(entity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist watch progress to Room", e)
        }

        // Notify observers that local data changed
        _localUpdateEvents.tryEmit(Unit)
    }

    @Volatile
    private var cachedWatchHistory: List<WatchHistoryEntry> = emptyList()

    /**
     * Get watch history — loads from Room if cache is empty.
     */
    suspend fun getWatchHistory(): List<WatchHistoryEntry> {
        val profileId = currentProfileId()
        if (!cachedWatchHistoryByProfile.containsKey(profileId)) loadFromRoom()
        return cachedWatchHistoryByProfile[profileId].orEmpty()
    }

    /**
     * Get continue watching items — loads from Room if cache is empty.
     */
    suspend fun getContinueWatching(): List<WatchHistoryEntry> {
        val profileId = currentProfileId()
        if (!cachedContinueWatchingByProfile.containsKey(profileId)) loadFromRoom()
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
            .firstOrNull { entry ->
                if (mediaType == MediaType.MOVIE) true
                else entry.season == season && entry.episode == episode
            }
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
     * Remove item from watch history (local cache + Room).
     */
    suspend fun removeFromHistory(
        tmdbId: Int,
        season: Int?,
        episode: Int?
    ) {
        val profileId = currentProfileId()
        // Remove from in-memory cache
        cachedContinueWatchingByProfile[profileId] = cachedContinueWatchingByProfile[profileId]
            .orEmpty()
            .filterNot { it.show_tmdb_id == tmdbId && it.season == season && it.episode == episode }
        cachedWatchHistoryByProfile[profileId] = cachedWatchHistoryByProfile[profileId]
            .orEmpty()
            .filterNot { it.show_tmdb_id == tmdbId && it.season == season && it.episode == episode }
        // Remove from Room
        try {
            val progressKey = if (season != null && episode != null) "tv:$tmdbId:$season:$episode" else "movie:$tmdbId"
            withContext(Dispatchers.IO) { watchHistoryDao.deleteByProgressKey(progressKey) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watch history from Room", e)
        }
    }

    /**
     * Clear all watch history (local cache + Room).
     */
    suspend fun clearHistory() {
        val profileId = currentProfileId()
        cachedContinueWatchingByProfile[profileId] = emptyList()
        cachedWatchHistoryByProfile[profileId] = emptyList()
        try {
            withContext(Dispatchers.IO) { watchHistoryDao.clearForProfile(profileId) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear watch history from Room", e)
        }
    }

    fun clearProfileCaches() {
        cachedContinueWatching = emptyList()
        cachedWatchHistory = emptyList()
        cachedContinueWatchingByProfile.clear()
        cachedWatchHistoryByProfile.clear()
    }

    companion object {
        private const val TAG = "WatchHistoryRepo"
    }

    private fun parseEpoch(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun resolveProfileIdPublic(): Int = resolveProfileId()

    private suspend fun resolveProfileId(): Int {
        val activeId = profileManager.getProfileId()
        if (activeId == "default") return 1
        val profiles = profileManager.getProfileList()
        // Sort by createdAt to produce a stable index order across devices
        val sorted = profiles.sortedBy { it.createdAt }
        val index = sorted.indexOfFirst { it.id == activeId }
        val resolved = if (index >= 0) index + 1 else 1
        Log.d(TAG, "resolveProfileId: activeId=$activeId index=$index resolved=$resolved totalProfiles=${profiles.size}")
        return resolved
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
            normalizedPosition > 0L -> true
            else -> false
        }
    }

    private fun normalizeStoredSeconds(value: Long): Long {
        return if (value > 86_400L) value / 1000L else value
    }

    /** Convert a [WatchHistoryEntity] (Room) to a [WatchHistoryEntry] (domain). */
    private fun WatchHistoryEntity.toEntry(): WatchHistoryEntry {
        val progressFloat = if (progress in 1..100) progress / 100f else 0f
        return WatchHistoryEntry(
            user_id = userId,
            profile_id = profileId,
            media_type = mediaType,
            show_tmdb_id = tmdbId,
            title = title,
            poster_path = posterPath,
            backdrop_path = backdropPath,
            season = season,
            episode = episode,
            episode_title = episodeTitle,
            progress = progressFloat,
            duration_seconds = durationSeconds,
            position_seconds = positionSeconds,
            updated_at = java.time.Instant.ofEpochMilli(updatedAt).toString(),
            paused_at = java.time.Instant.ofEpochMilli(updatedAt).toString(),
            source = this.source,
            last_addon_id = lastAddonId,
            last_source_name = lastSourceName,
            last_binge_group = lastBingeGroup
        )
    }

    /** Convert a [WatchHistoryEntry] (domain) to a [WatchHistoryEntity] (Room). */
    private fun WatchHistoryEntry.toEntity(): WatchHistoryEntity {
        val progressKey = if (media_type == "tv") "tv:$show_tmdb_id:$season:$episode" else "movie:$show_tmdb_id"
        val videoId = if (media_type == "tv") "$show_tmdb_id:$season:$episode" else show_tmdb_id.toString()
        val ts = parseEpoch(updated_at).coerceAtLeast(parseEpoch(paused_at)).let { if (it > 0) it else System.currentTimeMillis() }
        return WatchHistoryEntity(
            userId = user_id,
            profileId = profile_id ?: currentProfileId(),
            mediaType = media_type,
            tmdbId = show_tmdb_id,
            title = title.orEmpty(),
            posterPath = poster_path,
            backdropPath = backdrop_path,
            season = season,
            episode = episode,
            episodeTitle = episode_title,
            progress = (progress * 100).toInt().coerceIn(0, 100),
            durationSeconds = duration_seconds,
            positionSeconds = position_seconds,
            updatedAt = ts,
            source = source,
            videoId = videoId,
            progressKey = progressKey,
            lastAddonId = last_addon_id,
            lastSourceName = last_source_name,
            lastBingeGroup = last_binge_group
        )
    }
}
