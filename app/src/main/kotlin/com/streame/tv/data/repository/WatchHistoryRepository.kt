package com.streame.tv.data.repository

import com.streame.tv.data.model.MediaType
import com.streame.tv.util.Constants
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

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
    private val profileManager: ProfileManager
) {
    @Volatile
    private var cachedContinueWatching: List<WatchHistoryEntry> = emptyList()
    private val cachedContinueWatchingByProfile = ConcurrentHashMap<String, List<WatchHistoryEntry>>()
    private val cachedWatchHistoryByProfile = ConcurrentHashMap<String, List<WatchHistoryEntry>>()

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
    }

    /**
     * Clear all watch history (local cache only)
     */
    suspend fun clearHistory() {
        val profileId = currentProfileId()
        cachedContinueWatchingByProfile[profileId] = emptyList()
        cachedWatchHistoryByProfile[profileId] = emptyList()
    }

    fun clearProfileCaches() {
        cachedContinueWatching = emptyList()
        cachedWatchHistory = emptyList()
        cachedContinueWatchingByProfile.clear()
        cachedWatchHistoryByProfile.clear()
    }

    private fun parseEpoch(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
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
