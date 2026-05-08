package com.streame.tv.data.local

import android.util.Log
import com.google.gson.Gson
import com.streame.tv.data.model.Category
import com.streame.tv.data.model.MediaItem
import com.streame.tv.data.model.MediaType
import com.streame.tv.data.api.TmdbApi
import com.streame.tv.util.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Local-first home content repository.
 *
 * Pipeline:
 * 1. ViewModel reads from Room (Flow<List<Category>>) — instant, always available.
 * 2. refreshHome() fetches from TMDB, writes to Room — Room Flow triggers UI update.
 * 3. If TMDB fails, Room still has last session's data — no empty screen.
 */
class LocalHomeRepository(
    private val homeRowDao: HomeRowDao,
    private val tmdbApi: TmdbApi,
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val TAG = "LocalHomeRepo"

    /** Observe home rows from Room — always available, even offline. */
    val homeRows: Flow<List<Category>> = homeRowDao.getAllRows().map { rows ->
        rows.map { it.toCategory(gson) }
    }

    /**
     * Fetch fresh data from TMDB and write to Room.
     * Room Flow will automatically push the update to any observing ViewModel.
     */
    suspend fun refreshHome() = withContext(ioDispatcher) {
        try {
            val categories = fetchTmdbCategories()
            if (categories.isNotEmpty()) {
                val entities = categories.map { HomeRowEntity.fromCategory(it, gson) }
                homeRowDao.upsertAll(entities)
                Log.i(TAG, "Refreshed ${categories.size} home rows from TMDB")
            } else {
                Log.w(TAG, "TMDB returned 0 categories — keeping Room data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TMDB refresh failed — keeping Room data: ${e.message}")
            // Don't throw — Room still has last session's data
        }
    }

    private suspend fun fetchTmdbCategories(): List<Category> {
        val results = mutableListOf<Category>()

        // Trending movies
        runCatching {
            val response = tmdbApi.getTrendingMovies(page = 1)
            if (response.results.isNotEmpty()) {
                results.add(Category(
                    id = "trending_movies",
                    title = "Trending Movies",
                    items = response.results.map { it.toMediaItem() }
                ))
            }
        }.onFailure { Log.e(TAG, "Trending movies failed: ${it.message}") }

        // Trending TV
        runCatching {
            val response = tmdbApi.getTrendingTv(page = 1)
            if (response.results.isNotEmpty()) {
                results.add(Category(
                    id = "trending_tv",
                    title = "Trending TV Shows",
                    items = response.results.map { it.toMediaItem() }
                ))
            }
        }.onFailure { Log.e(TAG, "Trending TV failed: ${it.message}") }

        // Trending anime
        runCatching {
            val response = tmdbApi.discoverTv(
                watchRegion = "US",
                sortBy = "popularity.desc",
                genres = "16",
                minVoteCount = 10,
                keywords = "210024",
                airDateGte = getAnimeAirDateGte(),
                page = 1
            )
            if (response.results.isNotEmpty()) {
                results.add(Category(
                    id = "trending_anime",
                    title = "Trending Anime",
                    items = response.results.map { it.toMediaItem(mediaType = MediaType.TV) }
                ))
            }
        }.onFailure { Log.e(TAG, "Trending anime failed: ${it.message}") }

        return results
    }

    private fun getAnimeAirDateGte(): String {
        // 6 months ago
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, -6)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }

    /** Clear all cached home rows (e.g. on profile switch). */
    suspend fun clearHome() {
        homeRowDao.clearAll()
    }

    /**
     * Write resolved categories to Room (called after loadHomeData succeeds).
     * This ensures Room always has the latest data for the local-first pipeline.
     */
    suspend fun cacheCategories(categories: List<Category>) = withContext(ioDispatcher) {
        val entities = categories.map { HomeRowEntity.fromCategory(it, gson) }
        homeRowDao.upsertAll(entities)
    }

    /**
     * Read cached categories from Room (fallback when network fails).
     */
    suspend fun getCachedCategories(): List<Category> = withContext(ioDispatcher) {
        val rows = homeRowDao.getAllRowsSuspend()
        rows.map { it.toCategory(gson) }
    }
}

// Extension: convert TMDB API response item to app MediaItem
private fun com.streame.tv.data.api.TmdbMediaItem.toMediaItem(
    mediaType: MediaType = if (this.mediaType == "tv") MediaType.TV else MediaType.MOVIE
): MediaItem {
    return MediaItem(
        id = id,
        title = title ?: name ?: "",
        subtitle = subtitleText(),
        overview = overview ?: "",
        year = (releaseDate ?: firstAirDate ?: "").take(4),
        releaseDate = releaseDate ?: firstAirDate,
        rating = voteAverage?.let { String.format("%.1f", it) } ?: "",
        tmdbRating = voteAverage?.let { String.format("%.1f", it) } ?: "",
        mediaType = mediaType,
        image = posterPath?.let { Constants.IMAGE_BASE + it } ?: "",
        backdrop = backdropPath?.let { Constants.BACKDROP_BASE + it },
        genreIds = genreIds ?: emptyList(),
        originalLanguage = originalLanguage,
        popularity = popularity ?: 0f
    )
}

private fun com.streame.tv.data.api.TmdbMediaItem.subtitleText(): String {
    return when {
        !releaseDate.isNullOrBlank() -> releaseDate.take(4)
        !firstAirDate.isNullOrBlank() -> firstAirDate.take(4)
        else -> ""
    }
}
