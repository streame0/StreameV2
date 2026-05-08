package com.streame.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.streame.tv.data.api.*
import com.streame.tv.data.model.MediaType
import com.streame.tv.util.Constants
import com.streame.tv.util.traktDataStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local watch history record (previously in SupabaseApi, now standalone)
 */
data class WatchHistoryRecord(
    val userId: String? = null,
    val profileId: String? = null,
    val mediaType: String,
    val showTmdbId: Int? = null,
    val showTraktId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val traktEpisodeId: Int? = null,
    val tmdbEpisodeId: Int? = null,
    val progress: Float = 0f,
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val pausedAt: String? = null,
    val updatedAt: String? = null,
    val source: String? = null,
    val title: String? = null,
    val episodeTitle: String? = null,
    val backdropPath: String? = null,
    val posterPath: String? = null,
    val id: String? = null
)

/**
 * Watched episode record (previously in SupabaseApi, now standalone)
 */
data class WatchedEpisodeRecord(
    val userId: String? = null,
    val profileId: String? = null,
    val showTmdbId: Int? = null,
    val showTraktId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val traktEpisodeId: Int? = null,
    val tmdbEpisodeId: Int? = null,
    val watched: Boolean = false,
    val watchedAt: String? = null,
    val updatedAt: String? = null,
    val source: String? = null,
    val title: String? = null,
    val episodeTitle: String? = null
)

/**
 * Watched movie record (previously in SupabaseApi, now standalone)
 */
data class WatchedMovieRecord(
    val userId: String? = null,
    val profileId: String? = null,
    val showTmdbId: Int? = null,
    val showTraktId: Int? = null,
    val watched: Boolean = false,
    val watchedAt: String? = null,
    val updatedAt: String? = null,
    val source: String? = null,
    val title: String? = null
)

/**
 * TraktSyncService - Manages synchronization between Trakt and local storage
 *
 * No Supabase — Trakt is the only cloud service:
 * 1. Full sync: Imports all watched data from Trakt to local cache
 * 2. Incremental sync: Uses Trakt's last_activities to sync only changes
 * 3. Two-way sync: Pushes local changes to Trakt
 */
@Singleton
class TraktSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApi,
    private val authRepository: AuthRepository,
    private val outboxRepository: TraktOutboxRepository,
    private val profileManager: ProfileManager
) {
    private val TAG = "TraktSyncService"
    private val gson = Gson()
    private val clientId = Constants.TRAKT_CLIENT_ID
    private val clientSecret = Constants.TRAKT_CLIENT_SECRET

    // Sync progress state
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _syncEvents = MutableSharedFlow<SyncStatus>(extraBufferCapacity = 1)
    val syncEvents: SharedFlow<SyncStatus> = _syncEvents.asSharedFlow()

    // Profile-scoped DataStore keys (must match TraktRepository for token sharing)
    private fun accessTokenKey() = profileManager.profileStringKey("trakt_access_token")
    private fun refreshTokenKey() = profileManager.profileStringKey("trakt_refresh_token")
    private fun expiresAtKey() = profileManager.profileLongKey("trakt_expires_at")

    // In-memory cache for current session
    private var cachedWatchedMovies: List<WatchedMovieRecord>? = null
    private var cachedWatchedEpisodes: List<WatchedEpisodeRecord>? = null

    private fun profileHistorySource(base: String): String {
        return "profile:${profileManager.getProfileIdSync()}:$base"
    }

    private fun activeProfileId(): String {
        return profileManager.getProfileIdSync().ifBlank { "default" }
    }

    private fun recordBelongsToActiveProfile(profileId: String?): Boolean {
        return if (!profileId.isNullOrBlank()) {
            profileId == activeProfileId()
        } else {
            profileManager.isDefaultProfile()
        }
    }

    /**
     * Perform a full sync from Trakt to local cache
     * This imports ALL watched movies and episodes, overwriting existing data
     */

    suspend fun performFullSync(): SyncResult = withContext(Dispatchers.IO) {
        if (_isSyncing.value) {
            return@withContext SyncResult.Error("Sync already in progress")
        }

        _isSyncing.value = true
        _syncProgress.value = SyncProgress(status = SyncStatus.STARTING, message = "Starting full sync...")

        val completionThreshold = Constants.WATCHED_THRESHOLD / 100f

        try {
            val userId = getUserId()
            val localUserId = userId ?: "local"

            var totalMovies = 0
            var totalEpisodes = 0

            _syncProgress.value = SyncProgress(
                status = SyncStatus.SYNCING_MOVIES,
                message = "Fetching watched movies..."
            )

            val watchedMovies = fetchAllWatchedMovies()
            val (movieRecords, filteredMovies) = buildWatchedMoviesFromWatchedList(localUserId, watchedMovies)

            // Update cache
            cachedWatchedMovies = movieRecords

            totalMovies = movieRecords.size

            _syncProgress.value = SyncProgress(
                status = SyncStatus.SYNCING_EPISODES,
                message = "Fetching watched episodes...",
                moviesProcessed = totalMovies,
                totalMovies = totalMovies
            )

            val watchedShows = fetchAllWatchedShows()
            val totalEpisodeItems = watchedShows.sumOf { show ->
                show.seasons?.sumOf { it.episodes.size } ?: 0
            }
            val totalPlays = watchedShows.sumOf { it.plays }
            val useProgressExpansion = totalEpisodeItems < totalPlays
            val (episodeRecords, filteredEpisodes) = if (useProgressExpansion) {
                // PERFORMANCE: Only expand progress for top 15 most recently watched shows
                // This prevents 30+ API calls on startup while still getting recent watch data
                // Old shows should already have data from previous syncs
                val recentShows = watchedShows.sortedByDescending { it.lastWatchedAt }.take(15)
                buildWatchedEpisodesFromShowProgress(localUserId, recentShows)
            } else {
                buildWatchedEpisodesFromWatchedShows(localUserId, watchedShows)
            }

            // Update cache
            cachedWatchedEpisodes = episodeRecords

            totalEpisodes = episodeRecords.size

            _syncProgress.value = SyncProgress(
                status = SyncStatus.SYNCING_PROGRESS,
                message = "Fetching playback progress...",
                moviesProcessed = totalMovies,
                totalMovies = totalMovies,
                episodesProcessed = totalEpisodes,
                totalEpisodes = totalEpisodes
            )

            val playbackItems = fetchAllPlaybackProgress()
            val progressRecords = buildWatchHistoryFromPlayback(
                localUserId,
                playbackItems,
                completionThreshold,
                profileHistorySource("trakt")
            )

            flushOutbox()

            _syncProgress.value = SyncProgress(
                status = SyncStatus.COMPLETED,
                message = "Sync completed!",
                moviesProcessed = totalMovies,
                totalMovies = totalMovies,
                episodesProcessed = totalEpisodes,
                totalEpisodes = totalEpisodes
            )
            _syncEvents.tryEmit(SyncStatus.COMPLETED)

            SyncResult.Success(totalMovies, totalEpisodes)

        } catch (e: Exception) {
            _syncProgress.value = SyncProgress(
                status = SyncStatus.ERROR,
                message = "Sync failed: ${e.message}"
            )

            SyncResult.Error(e.message ?: "Unknown error")
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Perform incremental sync — without Supabase sync state, this
     * just delegates to full sync. Could be optimized later with local
     * last-activities tracking.
     */
    suspend fun performIncrementalSync(): SyncResult = withContext(Dispatchers.IO) {
        performFullSync()
    }

    /**
     * Sync a single movie as watched to Trakt
     */
    suspend fun markMovieWatched(tmdbId: Int, traktId: Int? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val traktAuth = getAuthHeader()

            // Sync to Trakt (queue on failure or if offline)
            val traktSyncOk = if (traktAuth != null) {
                try {
                    traktApi.addToHistory(
                        traktAuth, clientId, "2",
                        TraktHistoryBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            if (!traktSyncOk && traktAuth != null) {
                outboxRepository.enqueue(
                    TraktOutboxItem(
                        action = TraktOutboxAction.MARK_MOVIE_WATCHED,
                        tmdbId = tmdbId
                    )
                )
            }

            // Remove playback item from Trakt so it disappears from Continue Watching
            removePlaybackForContent(traktAuth, tmdbId, MediaType.MOVIE)

            traktSyncOk
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sync a single episode as watched to Trakt
     */
    suspend fun markEpisodeWatched(
        showTmdbId: Int,
        season: Int,
        episode: Int,
        showTraktId: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val traktAuth = getAuthHeader()

            // Sync to Trakt (queue on failure or if offline)
            val traktSyncOk = if (traktAuth != null) {
                try {
                    traktApi.addToHistory(
                        traktAuth, clientId, "2",
                        TraktHistoryBody(
                            shows = listOf(
                                TraktHistoryShowWithSeasons(
                                    ids = TraktIds(tmdb = showTmdbId),
                                    seasons = listOf(
                                        TraktHistorySeason(
                                            number = season,
                                            episodes = listOf(TraktHistoryEpisodeNumber(number = episode))
                                        )
                                    )
                                )
                            )
                        )
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            if (!traktSyncOk && traktAuth != null) {
                outboxRepository.enqueue(
                    TraktOutboxItem(
                        action = TraktOutboxAction.MARK_EPISODE_WATCHED,
                        tmdbId = showTmdbId,
                        showTraktId = showTraktId,
                        season = season,
                        episode = episode
                    )
                )
            }

            // Remove playback item from Trakt so it disappears from Continue Watching
            removePlaybackForContent(traktAuth, showTmdbId, MediaType.TV)

            traktSyncOk
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Mark movie as unwatched in Trakt
     */
    suspend fun markMovieUnwatched(tmdbId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val traktAuth = getAuthHeader()

            // Remove from Trakt
            if (traktAuth != null) {
                traktApi.removeFromHistory(
                    traktAuth, clientId, "2",
                    TraktHistoryBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
                )
            }

            traktAuth != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Mark episode as unwatched in Trakt
     */
    suspend fun markEpisodeUnwatched(showTmdbId: Int, season: Int, episode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val traktAuth = getAuthHeader()

            // Remove from Trakt
            if (traktAuth != null) {
                traktApi.removeFromHistory(
                    traktAuth, clientId, "2",
                    TraktHistoryBody(
                        shows = listOf(
                            TraktHistoryShowWithSeasons(
                                ids = TraktIds(tmdb = showTmdbId),
                                seasons = listOf(
                                    TraktHistorySeason(
                                        number = season,
                                        episodes = listOf(TraktHistoryEpisodeNumber(number = episode))
                                    )
                                )
                            )
                        )
                    )
                )
            }

            traktAuth != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Save playback progress — no-op, Trakt handles playback progress directly
     */
    suspend fun savePlaybackProgress(
        tmdbId: Int,
        mediaType: String,
        progress: Float,
        positionSeconds: Long,
        durationSeconds: Long,
        season: Int? = null,
        episode: Int? = null,
        showTraktId: Int? = null,
        traktEpisodeId: Int? = null,
        tmdbEpisodeId: Int? = null,
        title: String? = null,
        episodeTitle: String? = null,
        backdropPath: String? = null,
        posterPath: String? = null
    ): Boolean = true

    /**
     * Get all watched movies from local cache
     */
    suspend fun getWatchedMovies(): Set<Int> = withContext(Dispatchers.IO) {
        cachedWatchedMovies
            ?.filter { recordBelongsToActiveProfile(it.profileId) }
            ?.mapNotNull { it.showTmdbId }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Get all watched episodes from local cache
     * Returns set of keys in format "show_tmdb:tmdbId:season:episode" (and trakt variants)
     */
    suspend fun getWatchedEpisodes(): Set<String> = withContext(Dispatchers.IO) {
        val cached = cachedWatchedEpisodes ?: return@withContext emptySet()
        val keys = mutableSetOf<String>()
        cached.filter { recordBelongsToActiveProfile(it.profileId) }.forEach { record ->
            val season = record.season
            val episode = record.episode
            if (season == null || episode == null) return@forEach
            buildEpisodeKey(record.traktEpisodeId, null, null, season, episode)?.let { keys.add(it) }
            buildEpisodeKey(null, record.showTraktId, null, season, episode)?.let { keys.add(it) }
            buildEpisodeKey(null, null, record.showTmdbId, season, episode)?.let { keys.add(it) }
        }
        keys
    }

    /**
     * Get watched episodes for a specific show from local cache
     */
    suspend fun getWatchedEpisodesForShow(showTmdbId: Int): Set<String> = withContext(Dispatchers.IO) {
        val cached = cachedWatchedEpisodes ?: return@withContext emptySet()
        val keys = mutableSetOf<String>()
        cached.filter { recordBelongsToActiveProfile(it.profileId) && it.showTmdbId == showTmdbId }.forEach { record ->
            val season = record.season
            val episode = record.episode
            buildEpisodeKey(record.traktEpisodeId, null, null, season, episode)?.let { keys.add(it) }
            buildEpisodeKey(null, record.showTraktId, null, season, episode)?.let { keys.add(it) }
            buildEpisodeKey(null, null, record.showTmdbId, season, episode)?.let { keys.add(it) }
        }
        keys
    }

    /**
     * Get watched episodes with timestamps from local cache
     */
    suspend fun getWatchedEpisodesDetailed(): List<WatchedEpisodeRecord> = withContext(Dispatchers.IO) {
        cachedWatchedEpisodes?.filter { record -> recordBelongsToActiveProfile(record.profileId) } ?: emptyList()
    }

    /**
     * Get in-progress items — returns empty (Trakt playback API handles this)
     */
    suspend fun getInProgressItems(): List<WatchHistoryRecord> = emptyList()

    /**
     * Get last sync time — no longer tracked without Supabase
     */
    suspend fun getLastSyncTime(): String? = null

    // ========== Private Helpers ==========

    private fun buildEpisodeKey(
        traktEpisodeId: Int?,
        showTraktId: Int?,
        showTmdbId: Int?,
        season: Int?,
        episode: Int?
    ): String? {
        return when {
            traktEpisodeId != null -> "trakt:$traktEpisodeId"
            showTraktId != null && season != null && episode != null -> "show_trakt:$showTraktId:$season:$episode"
            showTmdbId != null && season != null && episode != null -> "show_tmdb:$showTmdbId:$season:$episode"
            else -> null
        }
    }

    private fun buildWatchHistoryKey(record: WatchHistoryRecord): String? {
        return if (record.mediaType == "movie") {
            record.showTmdbId?.let { "movie:$it" }
        } else {
            buildEpisodeKey(record.traktEpisodeId, record.showTraktId, record.showTmdbId, record.season, record.episode)
        }
    }

    private fun isAfter(candidate: String?, existing: String?): Boolean {
        if (candidate == null) return false
        if (existing == null) return true
        return try {
            Instant.parse(candidate).isAfter(Instant.parse(existing))
        } catch (_: Exception) {
            candidate > existing
        }
    }

    private suspend fun fetchAllWatchedMovies(): List<TraktWatchedMovie> {
        return executeTraktCall("watched movies") { auth ->
            traktApi.getWatchedMovies(auth, clientId)
        }
    }

    private suspend fun fetchAllWatchedShows(): List<TraktWatchedShow> {
        return executeTraktCall("watched shows") { auth ->
            traktApi.getWatchedShows(auth, clientId)
        }
    }

    private suspend fun fetchAllHistoryMovies(startAt: String?): List<TraktHistoryItem> {
        val all = mutableListOf<TraktHistoryItem>()
        var page = 1
        val limit = 100
        var consecutiveErrors = 0
        val maxRetries = 5

        while (true) {
            try {
                if (consecutiveErrors > 0) {
                    val backoff = (consecutiveErrors * 1000L).coerceAtMost(10000L)
                    delay(backoff)
                } else {
                    delay(250) // Standard rate limit protection
                }

                val pageItems = executeTraktCall("history movies page $page") { auth ->
                    traktApi.getHistoryMovies(auth, clientId, "2", page, limit, startAt)
                }

                consecutiveErrors = 0 // Reset on success

                if (pageItems.isEmpty()) break
                all.addAll(pageItems)

                if (pageItems.size < limit) break

                page++
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors > maxRetries) {
                    break
                }
            }
        }

        return all
    }

    private suspend fun fetchAllHistoryEpisodes(startAt: String?): List<TraktHistoryItem> {
        val all = mutableListOf<TraktHistoryItem>()
        var page = 1
        val limit = 100
        var consecutiveErrors = 0
        val maxRetries = 5

        while (true) {
            try {
                if (consecutiveErrors > 0) {
                    val backoff = (consecutiveErrors * 1000L).coerceAtMost(10000L)
                    delay(backoff)
                } else {
                    delay(250) // Standard rate limit protection
                }

                val pageItems = executeTraktCall("history episodes page $page") { auth ->
                    traktApi.getHistoryEpisodes(auth, clientId, "2", page, limit, startAt)
                }

                consecutiveErrors = 0 // Reset on success

                if (pageItems.isEmpty()) break
                all.addAll(pageItems)

                if (pageItems.size < limit) break

                page++
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors > maxRetries) {
                    break
                }
            }
        }

        return all
    }

    private suspend fun fetchAllPlaybackProgress(): List<TraktPlaybackItem> {
        val all = mutableListOf<TraktPlaybackItem>()
        var page = 1
        val limit = 100

        while (true) {
            val pageItems = executeTraktCall("playback page $page") { auth ->
                traktApi.getPlaybackProgress(auth, clientId, "2", null, page, limit)
            }
            if (pageItems.isEmpty()) break
            all.addAll(pageItems)
            page++
        }

        return all
    }

    private fun buildWatchedMoviesFromWatchedList(
        userId: String,
        items: List<TraktWatchedMovie>
    ): Pair<List<WatchedMovieRecord>, Int> {
        val byTmdbId = LinkedHashMap<Int, WatchedMovieRecord>()
        var filtered = 0

        for (item in items) {
            val movie = item.movie
            val tmdbId = movie.ids.tmdb ?: continue
            val watchedAt = item.lastWatchedAt ?: item.lastUpdatedAt
            val existing = byTmdbId[tmdbId]
            if (existing == null || isAfter(watchedAt, existing.watchedAt)) {
                byTmdbId[tmdbId] = WatchedMovieRecord(
                    userId = userId,
                    profileId = activeProfileId(),
                    showTmdbId = tmdbId,
                    showTraktId = movie.ids.trakt,
                    watchedAt = watchedAt
                )
            } else {
                filtered++
            }
        }

        return Pair(byTmdbId.values.toList(), filtered)
    }

    private fun buildWatchedMoviesFromHistory(
        userId: String,
        items: List<TraktHistoryItem>
    ): Pair<List<WatchedMovieRecord>, Int> {
        val byTmdbId = LinkedHashMap<Int, WatchedMovieRecord>()
        var filtered = 0

        for (item in items) {
            val movie = item.movie ?: continue
            val tmdbId = movie.ids.tmdb ?: continue
            val watchedAt = item.watchedAt
            val existing = byTmdbId[tmdbId]
            if (existing == null || isAfter(watchedAt, existing.watchedAt)) {
                byTmdbId[tmdbId] = WatchedMovieRecord(
                    userId = userId,
                    profileId = activeProfileId(),
                    showTmdbId = tmdbId,
                    showTraktId = movie.ids.trakt,
                    watchedAt = watchedAt
                )
            } else {
                filtered++
            }
        }

        return Pair(byTmdbId.values.toList(), filtered)
    }

    private fun buildWatchedEpisodesFromWatchedShows(
        userId: String,
        items: List<TraktWatchedShow>
    ): Pair<List<WatchedEpisodeRecord>, Int> {
        val byKey = LinkedHashMap<String, WatchedEpisodeRecord>()
        var filtered = 0
        var skippedShows = 0
        var skippedEpisodes = 0

        for (item in items) {
            val show = item.show
            val showTmdbId = show.ids.tmdb
            if (showTmdbId == null) {
                skippedShows++
                skippedEpisodes += item.seasons?.sumOf { it.episodes.size } ?: 0
                continue
            }
            val showTraktId = show.ids.trakt
            val showWatchedAt = item.lastWatchedAt ?: item.lastUpdatedAt

            item.seasons?.forEach { season ->
                season.episodes.forEach { episode ->
                    val key = buildEpisodeKey(
                        traktEpisodeId = null,
                        showTraktId = showTraktId,
                        showTmdbId = showTmdbId,
                        season = season.number,
                        episode = episode.number
                    ) ?: return@forEach

                    val watchedAt = episode.lastWatchedAt ?: showWatchedAt
                    val existing = byKey[key]
                    if (existing == null || isAfter(watchedAt, existing.watchedAt)) {
                        byKey[key] = WatchedEpisodeRecord(
                            userId = userId,
                            profileId = activeProfileId(),
                            showTmdbId = showTmdbId,
                            season = season.number,
                            episode = episode.number,
                            traktEpisodeId = null,
                            tmdbEpisodeId = null,
                            showTraktId = showTraktId,
                            watched = true,
                            watchedAt = watchedAt,
                            source = "trakt",
                            updatedAt = watchedAt
                        )
                    } else {
                        filtered++
                    }
                }
            }
        }

        return Pair(byKey.values.toList(), filtered)
    }

    private suspend fun buildWatchedEpisodesFromShowProgress(
        userId: String,
        items: List<TraktWatchedShow>
    ): Pair<List<WatchedEpisodeRecord>, Int> = coroutineScope {
        val byKey = LinkedHashMap<String, WatchedEpisodeRecord>()
        val mutex = Mutex()
        var filtered = 0
        val skippedShows = AtomicInteger(0)
        val skippedEpisodes = AtomicInteger(0)

        val semaphore = Semaphore(5)
        suspend fun upsertEpisode(
            showTmdbId: Int,
            showTraktId: Int?,
            seasonNumber: Int,
            episodeNumber: Int,
            watchedAt: String?
        ) {
            val key = buildEpisodeKey(
                traktEpisodeId = null,
                showTraktId = showTraktId,
                showTmdbId = showTmdbId,
                season = seasonNumber,
                episode = episodeNumber
            ) ?: return

            mutex.withLock {
                val existing = byKey[key]
                if (existing == null || isAfter(watchedAt, existing.watchedAt)) {
                    byKey[key] = WatchedEpisodeRecord(
                        userId = userId,
                        profileId = activeProfileId(),
                        showTmdbId = showTmdbId,
                        season = seasonNumber,
                        episode = episodeNumber,
                        traktEpisodeId = null,
                        tmdbEpisodeId = null,
                        showTraktId = showTraktId,
                        watched = true,
                        watchedAt = watchedAt,
                        source = "trakt",
                        updatedAt = watchedAt
                    )
                } else {
                    filtered++
                }
            }
        }

        val tasks = items.map { item ->
            async {
                semaphore.withPermit {
                    val show = item.show
                    val showTmdbId = show.ids.tmdb
                    val showTraktId = show.ids.trakt
                    if (showTmdbId == null) {
                        skippedShows.incrementAndGet()
                        skippedEpisodes.addAndGet(item.seasons?.sumOf { it.episodes.size } ?: 0)
                        return@withPermit
                    }

                    val showWatchedAt = item.lastWatchedAt ?: item.lastUpdatedAt

                    item.seasons?.forEach { season ->
                        season.episodes.forEach { episode ->
                            if (episode.plays <= 0) return@forEach
                            val watchedAt = episode.lastWatchedAt ?: showWatchedAt
                            upsertEpisode(
                                showTmdbId = showTmdbId,
                                showTraktId = showTraktId,
                                seasonNumber = season.number,
                                episodeNumber = episode.number,
                                watchedAt = watchedAt
                            )
                        }
                    }

                    if (showTraktId == null) {
                        return@withPermit
                    }

                    try {
                        val progress = executeTraktCall("show progress $showTraktId") { auth ->
                            traktApi.getShowProgress(
                                auth,
                                clientId,
                                "2",
                                showTraktId.toString(),
                                specials = "false",
                                countSpecials = "false"
                            )
                        }

                        progress.seasons?.forEach { season ->
                            season.episodes?.forEach { episode ->
                                if (!episode.completed) return@forEach
                                val watchedAt = episode.lastWatchedAt ?: showWatchedAt
                                upsertEpisode(
                                    showTmdbId = showTmdbId,
                                    showTraktId = showTraktId,
                                    seasonNumber = season.number,
                                    episodeNumber = episode.number,
                                    watchedAt = watchedAt
                                )
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }

        tasks.awaitAll()

        Pair(byKey.values.toList(), filtered)
    }

    private fun buildWatchedEpisodesFromHistory(
        userId: String,
        items: List<TraktHistoryItem>
    ): Pair<List<WatchedEpisodeRecord>, Int> {
        val byKey = LinkedHashMap<String, WatchedEpisodeRecord>()
        var filtered = 0

        for (item in items) {
            val show = item.show ?: continue
            val episode = item.episode ?: continue
            val showTmdbId = show.ids.tmdb ?: continue
            val key = buildEpisodeKey(
                episode.ids.trakt,
                show.ids.trakt,
                showTmdbId,
                episode.season,
                episode.number
            ) ?: continue

            val watchedAt = item.watchedAt
            val existing = byKey[key]
            if (existing == null || isAfter(watchedAt, existing.watchedAt)) {
                byKey[key] = WatchedEpisodeRecord(
                    userId = userId,
                    profileId = activeProfileId(),
                    showTmdbId = showTmdbId,
                    season = episode.season,
                    episode = episode.number,
                    traktEpisodeId = episode.ids.trakt,
                    tmdbEpisodeId = episode.ids.tmdb,
                    showTraktId = show.ids.trakt,
                    watched = true,
                    watchedAt = watchedAt,
                    source = "trakt",
                    updatedAt = watchedAt
                )
            } else {
                filtered++
            }
        }

        return Pair(byKey.values.toList(), filtered)
    }

    private fun buildWatchHistoryFromPlayback(
        userId: String,
        items: List<TraktPlaybackItem>,
        completionThreshold: Float,
        source: String
    ): List<WatchHistoryRecord> {
        val records = mutableListOf<WatchHistoryRecord>()

        for (item in items) {
            val progress = (item.progress / 100f).coerceIn(0f, 1f)
            if (progress <= 0f || progress >= completionThreshold) continue

            when (item.type) {
                "movie" -> {
                    val tmdbId = item.movie?.ids?.tmdb ?: continue
                    val updatedAt = item.pausedAt ?: Instant.now().toString()
                    records.add(
                        WatchHistoryRecord(
                            userId = userId,
                            profileId = activeProfileId(),
                            mediaType = "movie",
                            showTmdbId = tmdbId,
                            progress = progress,
                            positionSeconds = 0,
                            durationSeconds = 0,
                            pausedAt = item.pausedAt,
                            updatedAt = updatedAt,
                            source = source,
                            title = item.movie?.title
                        )
                    )
                }
                "episode" -> {
                    val showTmdbId = item.show?.ids?.tmdb ?: continue
                    val season = item.episode?.season ?: continue
                    val number = item.episode?.number ?: continue
                    val updatedAt = item.pausedAt ?: Instant.now().toString()
                    records.add(
                        WatchHistoryRecord(
                            userId = userId,
                            profileId = activeProfileId(),
                            mediaType = "tv",
                            showTmdbId = showTmdbId,
                            showTraktId = item.show?.ids?.trakt,
                            season = season,
                            episode = number,
                            traktEpisodeId = item.episode?.ids?.trakt,
                            tmdbEpisodeId = item.episode?.ids?.tmdb,
                            progress = progress,
                            positionSeconds = 0,
                            durationSeconds = 0,
                            pausedAt = item.pausedAt,
                            updatedAt = updatedAt,
                            source = source,
                            title = item.show?.title,
                            episodeTitle = item.episode?.title
                        )
                    )
                }
            }
        }

        return records
    }


    private suspend fun flushOutbox() {
        val items = outboxRepository.loadAll()
        if (items.isEmpty()) return

        val succeeded = mutableSetOf<String>()
        val failed = mutableSetOf<String>()

        items.forEach { item ->
            val ok = try {
                when (item.action) {
                    TraktOutboxAction.MARK_MOVIE_WATCHED -> {
                        val tmdbId = item.tmdbId
                        if (tmdbId == null) {
                            false
                        } else {
                            executeTraktCall("outbox mark movie watched") { auth ->
                                traktApi.addToHistory(
                                    auth, clientId, "2",
                                    TraktHistoryBody(movies = listOf(TraktMovieId(TraktIds(tmdb = tmdbId))))
                                )
                            }
                            true
                        }
                    }
                    TraktOutboxAction.MARK_EPISODE_WATCHED -> {
                        val tmdbId = item.tmdbId
                        val season = item.season
                        val episode = item.episode
                        if (tmdbId == null || season == null || episode == null) {
                            false
                        } else {
                            executeTraktCall("outbox mark episode watched") { auth ->
                                traktApi.addToHistory(
                                    auth, clientId, "2",
                                    TraktHistoryBody(
                                        shows = listOf(
                                            TraktHistoryShowWithSeasons(
                                                ids = TraktIds(tmdb = tmdbId),
                                                seasons = listOf(
                                                    TraktHistorySeason(
                                                        number = season,
                                                        episodes = listOf(TraktHistoryEpisodeNumber(number = episode))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            }
                            true
                        }
                    }
                    TraktOutboxAction.REMOVE_PLAYBACK_ITEM -> {
                        val playbackId = item.playbackId
                        if (playbackId == null) {
                            false
                        } else {
                            executeTraktCall("outbox remove playback") { auth ->
                                traktApi.removePlaybackItem(auth, clientId, "2", playbackId)
                            }
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                false
            }

            if (ok) {
                succeeded.add(item.id)
            } else {
                failed.add(item.id)
            }
        }

        outboxRepository.remove(succeeded)
        outboxRepository.incrementAttempts(failed)
    }

    private suspend fun removePlaybackForContent(traktAuth: String?, tmdbId: Int, mediaType: MediaType) {
        if (traktAuth.isNullOrBlank()) return

        try {
            val playbackItems = fetchAllPlaybackProgress()
            val item = playbackItems.firstOrNull {
                when (mediaType) {
                    MediaType.MOVIE -> it.movie?.ids?.tmdb == tmdbId
                    MediaType.TV -> it.show?.ids?.tmdb == tmdbId
                }
            }

            if (item == null) return

            try {
                executeTraktCall("remove playback item") { auth ->
                    traktApi.removePlaybackItem(auth, clientId, "2", item.id)
                }
            } catch (e: Exception) {
                outboxRepository.enqueue(
                    TraktOutboxItem(
                        action = TraktOutboxAction.REMOVE_PLAYBACK_ITEM,
                        playbackId = item.id
                    )
                )
            }
        } catch (e: Exception) {
        }
    }


    private fun hasChanged(previous: String?, current: String?): Boolean {
        if (previous == null || current == null) return true
        return previous != current
    }

    private suspend fun <T> executeTraktCall(
        operation: String,
        block: suspend (String) -> T
    ): T {
        val auth = getAuthHeader() ?: throw IllegalStateException("Not authenticated with Trakt")
        return try {
            block(auth)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                val refreshed = refreshTokenIfNeeded(force = true) ?: throw e
                block("Bearer $refreshed")
            } else {
                throw e
            }
        }
    }


    private suspend fun getAuthHeader(): String? {
        val token = refreshTokenIfNeeded(force = false) ?: return null
        return "Bearer $token"
    }

    private fun getUserId(): String? {
        // Get user ID from AuthRepository
        return authRepository.getCurrentUserId()
    }


    private suspend fun refreshTokenIfNeeded(force: Boolean): String? {
        val prefs = context.traktDataStore.data.first()
        val accessToken = prefs[accessTokenKey()] ?: return null
        val refreshToken = prefs[refreshTokenKey()]
        val expiresAt = prefs[expiresAtKey()]

        if (refreshToken == null || expiresAt == null) {
            return if (force) null else accessToken
        }

        val now = System.currentTimeMillis() / 1000
        if (!force && now < expiresAt - 3600) {
            return accessToken
        }

        return try {
            val newToken = traktApi.refreshToken(
                RefreshTokenRequest(
                    refreshToken = refreshToken,
                    clientId = clientId,
                    clientSecret = clientSecret
                )
            )
            saveToken(newToken)
            newToken.accessToken
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun saveToken(token: TraktToken) {
        context.traktDataStore.edit { prefs ->
            prefs[accessTokenKey()] = token.accessToken
            prefs[refreshTokenKey()] = token.refreshToken
            prefs[expiresAtKey()] = token.createdAt + token.expiresIn
        }
    }
}

// ========== Data Classes ==========

data class SyncProgress(
    val status: SyncStatus = SyncStatus.IDLE,
    val message: String = "",
    val moviesProcessed: Int = 0,
    val totalMovies: Int = 0,
    val episodesProcessed: Int = 0,
    val totalEpisodes: Int = 0
)

enum class SyncStatus {
    IDLE,
    STARTING,
    SYNCING_MOVIES,
    SYNCING_EPISODES,
    SYNCING_PROGRESS,
    COMPLETED,
    ERROR
}

sealed class SyncResult {
    data class Success(val moviesSynced: Int, val episodesSynced: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

