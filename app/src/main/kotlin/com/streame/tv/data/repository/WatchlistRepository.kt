package com.streame.tv.data.repository

import android.content.Context
import com.streame.tv.data.api.TmdbApi
import com.streame.tv.data.local.WatchlistDao
import com.streame.tv.data.local.WatchlistEntity
import com.streame.tv.data.model.MediaItem
import com.streame.tv.data.model.MediaType
import com.streame.tv.util.AppLogger
import com.streame.tv.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.streame.tv.data.sync.CloudSyncInvalidationBus
import com.streame.tv.data.sync.CloudSyncScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local watchlist item (legacy data class kept for cloud sync compatibility)
 */
data class LocalWatchlistItem(
    val tmdbId: Int,
    val mediaType: String,  // "tv" or "movie"
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val sourceOrder: Int = Int.MAX_VALUE
)

/**
 * Profile-scoped local watchlist repository.
 * Each profile has its own separate watchlist stored in Room.
 * No authentication required - works completely offline.
 */
@Singleton
class WatchlistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val tmdbApi: TmdbApi,
    private val authManager: com.streame.tv.data.repository.AuthManager,
    private val invalidationBus: com.streame.tv.data.sync.CloudSyncInvalidationBus,
    private val watchlistDao: WatchlistDao
) {
    // In-memory cache for quick lookups
    private val keyCache = mutableSetOf<String>()
    private val itemsCache = mutableListOf<MediaItem>()
    private val _watchlistItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val watchlistItems: StateFlow<List<MediaItem>> = _watchlistItems.asStateFlow()

    private var cacheLoaded = false
    private val cacheMutex = Mutex()

    // Limit parallel TMDB requests
    private val tmdbSemaphore = Semaphore(5)

    private fun cacheKey(mediaType: MediaType, tmdbId: Int): String {
        return "${mediaType.name.lowercase()}:$tmdbId"
    }

    /**
     * Get cached watchlist items instantly
     */
    fun getCachedItems(): List<MediaItem> = itemsCache.toList()

    /**
     * Check if an item is in watchlist
     */
    suspend fun isInWatchlist(mediaType: MediaType, tmdbId: Int): Boolean {
        if (!cacheLoaded) {
            loadKeyCacheQuick()
        }
        return keyCache.contains(cacheKey(mediaType, tmdbId))
    }

    private fun currentProfileId(): String = profileManager.getProfileIdSync().ifBlank { "default" }

    /**
     * Quick cache load - just loads keys for fast lookup
     */
    private suspend fun loadKeyCacheQuick() {
        try {
            val profileId = currentProfileId()
            val items = watchlistDao.getAllForProfile(profileId)
            cacheMutex.withLock {
                keyCache.clear()
                items.forEach { entity ->
                    val type = if (entity.mediaType == "tv") MediaType.TV else MediaType.MOVIE
                    keyCache.add(cacheKey(type, entity.tmdbId))
                }
                cacheLoaded = true
            }
        } catch (e: Exception) {
            AppLogger.e("WatchlistRepo", "Failed to load watchlist key cache", e)
        }
    }

    /**
     * Add item to watchlist
     */
    suspend fun addToWatchlist(mediaType: MediaType, tmdbId: Int, mediaItem: MediaItem? = null) {
        val key = cacheKey(mediaType, tmdbId)
        val typeStr = if (mediaType == MediaType.TV) "tv" else "movie"
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()

        val entity = WatchlistEntity(
            profileId = profileId,
            mediaType = typeStr,
            tmdbId = tmdbId,
            title = mediaItem?.title ?: "",
            posterPath = mediaItem?.image,
            backdropPath = mediaItem?.backdrop,
            addedAt = now,
            sourceOrder = 0,
            updatedAt = now
        )

        watchlistDao.upsert(entity)

        // Update in-memory cache
        cacheMutex.withLock {
            keyCache.add(key)
            itemsCache.removeAll { it.id == tmdbId && it.mediaType == mediaType }
            if (mediaItem != null) {
                itemsCache.add(0, mediaItem)
                _watchlistItems.value = itemsCache.toList()
            }
            cacheLoaded = true
        }

        // Signal CloudSyncCoordinator to push (no direct push — avoids double-push)
        invalidationBus.markDirty(CloudSyncScope.WATCHLIST, reason = "addToWatchlist")
    }

    /**
     * Remove item from watchlist
     */
    suspend fun removeFromWatchlist(mediaType: MediaType, tmdbId: Int) {
        val key = cacheKey(mediaType, tmdbId)
        val typeStr = if (mediaType == MediaType.TV) "tv" else "movie"
        val profileId = currentProfileId()

        watchlistDao.delete(profileId, typeStr, tmdbId)

        // Update in-memory cache
        cacheMutex.withLock {
            keyCache.remove(key)
            itemsCache.removeAll { it.id == tmdbId && it.mediaType == mediaType }
            _watchlistItems.value = itemsCache.toList()
        }

        // Signal CloudSyncCoordinator to push (no direct push — avoids double-push)
        invalidationBus.markDirty(CloudSyncScope.WATCHLIST, reason = "removeFromWatchlist")
    }

    /**
     * Get all watchlist items enriched with TMDB data
     */
    suspend fun getWatchlistItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        // Return cached items if available
        if (itemsCache.isNotEmpty()) {
            return@withContext itemsCache.toList()
        }

        // Load and enrich items
        val profileId = currentProfileId()
        val entities = watchlistDao.getAllForProfile(profileId)
        if (entities.isEmpty()) {
            cacheMutex.withLock {
                itemsCache.clear()
                keyCache.clear()
                _watchlistItems.value = emptyList()
                cacheLoaded = true
            }
            return@withContext emptyList()
        }

        val instantItems = entities.map { it.toBasicMediaItem() }
        cacheMutex.withLock {
            keyCache.clear()
            instantItems.forEach { item ->
                keyCache.add(cacheKey(item.mediaType, item.id))
            }
            _watchlistItems.value = instantItems
            cacheLoaded = true
        }

        // Enrich items with TMDB data in parallel
        val enrichedItems = coroutineScope {
            entities.map { entity ->
                async {
                    tmdbSemaphore.withPermit {
                        enrichWatchlistItem(entity)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Update cache
        cacheMutex.withLock {
            itemsCache.clear()
            itemsCache.addAll(enrichedItems)
            keyCache.clear()
            enrichedItems.forEach { item ->
                keyCache.add(cacheKey(item.mediaType, item.id))
            }
            _watchlistItems.value = enrichedItems
            cacheLoaded = true
        }

        enrichedItems
    }

    /**
     * Force refresh watchlist items
     */
    suspend fun refreshWatchlistItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        // Clear cache to force reload
        cacheMutex.withLock {
            itemsCache.clear()
        }
        getWatchlistItems()
    }

    /**
     * Reorder the local watchlist to match Trakt's newest-first list.
     * Mirrors Trakt's newest-first order and drops stale local entries. Keeping
     * local-only items here lets old bad title-search matches survive forever
     * after Trakt has the correct IDs.
     */
    suspend fun syncFromTraktOrder(traktItems: List<MediaItem>) = withContext(Dispatchers.IO) {
        val profileId = currentProfileId()
        val existing = watchlistDao.getAllForProfile(profileId)
        val existingByKey = existing.associateBy { "${it.mediaType}:${it.tmdbId}" }

        val ordered = mutableListOf<WatchlistEntity>()

        // Trakt items are already newest-first by listed_at.
        val orderedTraktItems = traktItems.toTraktOrder()
        val now = System.currentTimeMillis()
        for ((index, item) in orderedTraktItems.withIndex()) {
            val typeStr = if (item.mediaType == MediaType.TV) "tv" else "movie"
            val key = "$typeStr:${item.id}"
            val local = existingByKey[key]
            val traktOrderAddedAt = item.addedAt.takeIf { it > 0L } ?: (now - index)
            ordered.add(
                local?.copy(
                    title = item.title.ifBlank { local.title },
                    posterPath = item.image.ifBlank { local.posterPath },
                    backdropPath = item.backdrop ?: local.backdropPath,
                    addedAt = traktOrderAddedAt,
                    sourceOrder = index,
                    updatedAt = now
                ) ?: WatchlistEntity(
                    profileId = profileId,
                    mediaType = typeStr,
                    tmdbId = item.id,
                    title = item.title,
                    posterPath = item.image,
                    backdropPath = item.backdrop,
                    addedAt = traktOrderAddedAt,
                    sourceOrder = index,
                    updatedAt = now
                )
            )
        }

        // Replace all items for this profile with the reordered list
        watchlistDao.clearForProfile(profileId)
        watchlistDao.upsertAll(ordered)

        // Invalidate enriched cache so the UI picks up the new order on next refresh.
        cacheMutex.withLock {
            itemsCache.clear()
            keyCache.clear()
            ordered.forEach { entity ->
                val type = if (entity.mediaType == "tv") MediaType.TV else MediaType.MOVIE
                keyCache.add(cacheKey(type, entity.tmdbId))
            }
            _watchlistItems.value = ordered.map { it.toBasicMediaItem() }
            cacheLoaded = true
        }
    }

    /**
     * Clear all caches (call on profile switch)
     */
    fun clearWatchlistCache() {
        keyCache.clear()
        itemsCache.clear()
        _watchlistItems.value = emptyList()
        cacheLoaded = false
    }

    suspend fun exportWatchlistForProfile(profileId: String): List<LocalWatchlistItem> {
        val safeProfileId = profileId.trim().ifBlank { "default" }
        return try {
            watchlistDao.getAllForProfile(safeProfileId).map { it.toLocalWatchlistItem() }
        } catch (e: Exception) {
            AppLogger.e("WatchlistRepo", "Failed to export watchlist for profile", e)
            emptyList()
        }
    }

    suspend fun importWatchlistForProfile(profileId: String, items: List<LocalWatchlistItem>) {
        val safeProfileId = profileId.trim().ifBlank { "default" }
        val now = System.currentTimeMillis()
        val entities = items.map { item ->
            WatchlistEntity(
                profileId = safeProfileId,
                mediaType = item.mediaType,
                tmdbId = item.tmdbId,
                title = item.title,
                posterPath = item.posterPath,
                backdropPath = item.backdropPath,
                addedAt = item.addedAt,
                sourceOrder = item.sourceOrder,
                updatedAt = now
            )
        }
        watchlistDao.clearForProfile(safeProfileId)
        watchlistDao.upsertAll(entities)
        if (profileManager.getProfileIdSync() == safeProfileId) {
            clearWatchlistCache()
        }
    }

    /**
     * Load raw watchlist items from Room
     */
    private suspend fun loadWatchlistRaw(): List<LocalWatchlistItem> {
        val profileId = currentProfileId()
        return watchlistDao.getAllForProfile(profileId).map { it.toLocalWatchlistItem() }
    }

    /**
     * Enrich a watchlist item with TMDB data
     */
    private suspend fun enrichWatchlistItem(entity: WatchlistEntity): MediaItem? {
        return try {
            if (entity.mediaType == "tv") {
                val details = tmdbApi.getTvDetails(entity.tmdbId)
                MediaItem(
                    id = entity.tmdbId,
                    title = details.name,
                    subtitle = "TV Series",
                    overview = details.overview ?: "",
                    year = details.firstAirDate?.take(4) ?: "",
                    releaseDate = details.firstAirDate ?: "",
                    imdbRating = details.voteAverage?.let { String.format("%.1f", it) } ?: "",
                    duration = details.episodeRunTime?.firstOrNull()?.let { "${it}m" } ?: "",
                    mediaType = MediaType.TV,
                    image = details.posterPath?.let { "${Constants.IMAGE_BASE}$it" } ?: "",
                    backdrop = details.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                    addedAt = entity.addedAt,
                    sourceOrder = entity.sourceOrder
                )
            } else {
                val details = tmdbApi.getMovieDetails(entity.tmdbId)
                MediaItem(
                    id = entity.tmdbId,
                    title = details.title,
                    subtitle = "Movie",
                    overview = details.overview ?: "",
                    year = details.releaseDate?.take(4) ?: "",
                    releaseDate = details.releaseDate ?: "",
                    imdbRating = details.voteAverage?.let { String.format("%.1f", it) } ?: "",
                    duration = details.runtime?.let { formatRuntime(it) } ?: "",
                    mediaType = MediaType.MOVIE,
                    image = details.posterPath?.let { "${Constants.IMAGE_BASE}$it" } ?: "",
                    backdrop = details.backdropPath?.let { "${Constants.BACKDROP_BASE_LARGE}$it" },
                    addedAt = entity.addedAt,
                    sourceOrder = entity.sourceOrder
                )
            }
        } catch (_: Exception) {
            // Fallback to basic item from stored data
            entity.toBasicMediaItem()
        }
    }

    private fun formatRuntime(runtime: Int): String {
        val hours = runtime / 60
        val mins = runtime % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    private fun WatchlistEntity.toBasicMediaItem(): MediaItem {
        val type = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE
        return MediaItem(
            id = tmdbId,
            title = title,
            subtitle = if (type == MediaType.TV) "TV Series" else "Movie",
            overview = "",
            year = "",
            mediaType = type,
            image = posterPath.orEmpty(),
            backdrop = backdropPath,
            addedAt = addedAt,
            sourceOrder = sourceOrder
        )
    }

    private fun WatchlistEntity.toLocalWatchlistItem(): LocalWatchlistItem {
        return LocalWatchlistItem(
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = title,
            posterPath = posterPath,
            backdropPath = backdropPath,
            addedAt = addedAt,
            sourceOrder = sourceOrder
        )
    }

    private fun List<MediaItem>.toTraktOrder(): List<MediaItem> {
        return sortedWith(
            compareBy<MediaItem> { it.sourceOrder }
                .thenByDescending { it.addedAt }
        )
    }

    /**
     * Merge cloud library items into local watchlist.
     * Uses updated_at conflict resolution: cloud items not present locally are added;
     * existing items are only overwritten if the cloud version is newer (higher addedAt).
     * After merge, the in-memory cache and StateFlow are refreshed.
     */
    suspend fun mergeFromCloud(cloudItems: List<com.streame.tv.data.remote.supabase.SupabaseLibraryItem>) {
        if (cloudItems.isEmpty()) return
        val profileId = currentProfileId()
        val localEntities = watchlistDao.getAllForProfile(profileId)
        val localByKey = localEntities.associateBy { "${it.mediaType}:${it.tmdbId}" }
        val now = System.currentTimeMillis()
        val toUpsert = mutableListOf<WatchlistEntity>()
        var merged = 0

        cloudItems.forEach { cloud ->
            val tmdbId = cloud.contentId.toIntOrNull() ?: return@forEach
            val mediaType = cloud.contentType // "tv" or "movie"
            val key = "$mediaType:$tmdbId"
            val cloudAddedAt = if (cloud.addedAt > 0) cloud.addedAt else now
            val local = localByKey[key]

            val shouldTakeCloud = when {
                local == null -> true  // new item — always add
                cloudAddedAt > local.addedAt -> true  // cloud is newer
                else -> false  // local is newer or same — keep local
            }

            if (shouldTakeCloud) {
                toUpsert.add(
                    WatchlistEntity(
                        profileId = profileId,
                        mediaType = mediaType,
                        tmdbId = tmdbId,
                        title = cloud.name.ifBlank { local?.title ?: "" },
                        posterPath = cloud.poster ?: local?.posterPath,
                        backdropPath = cloud.background ?: local?.backdropPath,
                        addedAt = cloudAddedAt,
                        sourceOrder = local?.sourceOrder ?: Int.MAX_VALUE,
                        updatedAt = now
                    )
                )
                merged++
            }
        }
        if (merged > 0) {
            watchlistDao.upsertAll(toUpsert)
            // Refresh in-memory cache
            cacheMutex.withLock {
                keyCache.clear()
                val allEntities = watchlistDao.getAllForProfile(profileId)
                allEntities.forEach { entity ->
                    val type = if (entity.mediaType == "tv") MediaType.TV else MediaType.MOVIE
                    keyCache.add(cacheKey(type, entity.tmdbId))
                }
                cacheLoaded = true
                // Rebuild itemsCache from basic data
                val enriched = allEntities.map { it.toBasicMediaItem() }
                itemsCache.clear()
                itemsCache.addAll(enriched)
                _watchlistItems.value = itemsCache.toList()
            }
        }
    }

    /**
     * Get all watchlist items formatted for cloud push.
     * Called by CloudSyncCoordinator when it's time to push.
     */
    suspend fun getAllForPush(): List<com.streame.tv.data.remote.supabase.SupabaseLibraryItem> {
        if (!authManager.isAuthenticated) return emptyList()
        val profileId = resolveProfileId()
        val profileIdStr = currentProfileId()
        return watchlistDao.getAllForProfile(profileIdStr).map { entity ->
            com.streame.tv.data.remote.supabase.SupabaseLibraryItem(
                contentId = entity.tmdbId.toString(),
                contentType = entity.mediaType,
                name = entity.title,
                poster = entity.posterPath,
                posterShape = "POSTER",
                background = entity.backdropPath,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                profileId = profileId
            )
        }
    }

    private suspend fun resolveProfileId(): Int {
        val activeId = profileManager.getProfileId()
        if (activeId == "default") return 1
        val profiles = profileManager.getProfileList()
        val index = profiles.indexOfFirst { it.id == activeId }
        return if (index >= 0) index + 1 else 1
    }

}
