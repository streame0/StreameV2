package com.streame.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeRowDao {
    @Query("SELECT * FROM home_rows ORDER BY updatedAt DESC")
    fun getAllRows(): Flow<List<HomeRowEntity>>

    @Query("SELECT * FROM home_rows WHERE id = :id")
    suspend fun getRow(id: String): HomeRowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<HomeRowEntity>)

    @Query("DELETE FROM home_rows")
    suspend fun clearAll()

    @Query("DELETE FROM home_rows WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Query("SELECT * FROM home_rows ORDER BY updatedAt DESC")
    suspend fun getAllRowsSuspend(): List<HomeRowEntity>
}

@Dao
interface CatalogConfigDao {
    @Query("SELECT * FROM catalog_configs WHERE isHidden = 0 ORDER BY sortOrder ASC")
    fun getVisibleCatalogs(): Flow<List<CatalogConfigEntity>>

    @Query("SELECT * FROM catalog_configs ORDER BY sortOrder ASC")
    fun getAllCatalogs(): Flow<List<CatalogConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(configs: List<CatalogConfigEntity>)

    @Query("DELETE FROM catalog_configs")
    suspend fun clearAll()
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND progress >= :minProgress AND progress < 90 ORDER BY updatedAt DESC")
    fun getContinueWatching(profileId: String, minProgress: Int = 3): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun getAllHistory(profileId: String): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY updatedAt DESC")
    suspend fun getAllForProfile(profileId: String): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND progress >= :minProgress AND progress < 90 ORDER BY updatedAt DESC")
    suspend fun getContinueWatchingSuspend(profileId: String, minProgress: Int = 3): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestByTmdbId(profileId: String, mediaType: String, tmdbId: Int): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE progressKey = :key LIMIT 1")
    suspend fun getByProgressKey(key: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WatchHistoryEntity>)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId")
    suspend fun delete(profileId: String, mediaType: String, tmdbId: Int)

    @Query("DELETE FROM watch_history WHERE progressKey = :key")
    suspend fun deleteByProgressKey(key: String)

    @Query("UPDATE watch_history SET progress = :progress, positionSeconds = :position, updatedAt = :updatedAt WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId AND (season IS NULL OR season = :season) AND (episode IS NULL OR episode = :episode)")
    suspend fun updateProgress(profileId: String, mediaType: String, tmdbId: Int, season: Int?, episode: Int?, progress: Int, position: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY sourceOrder ASC, addedAt DESC")
    suspend fun getAllForProfile(profileId: String): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY sourceOrder ASC, addedAt DESC")
    fun getAllForProfileFlow(profileId: String): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId)")
    suspend fun exists(profileId: String, mediaType: String, tmdbId: Int): Boolean

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId LIMIT 1")
    suspend fun getByKey(profileId: String, mediaType: String, tmdbId: Int): WatchlistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchlistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WatchlistEntity>)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId")
    suspend fun delete(profileId: String, mediaType: String, tmdbId: Int)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)

    @Query("DELETE FROM watchlist")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE profileId = :profileId ORDER BY addedAt DESC")
    suspend fun getAllForProfile(profileId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE profileId = :profileId AND status = 'completed' ORDER BY addedAt DESC")
    fun getCompletedForProfile(profileId: String): kotlinx.coroutines.flow.Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType AND seasonNumber IS :seasonNumber AND episodeNumber IS :episodeNumber")
    suspend fun findByContent(profileId: String, tmdbId: Int, mediaType: String, seasonNumber: Int?, episodeNumber: Int?): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, progress = :progress, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progress: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = :status, errorMessage = :error, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'completed', progress = 100, fileSizeBytes = :fileSize, completedAt = :completedAt, updatedAt = :timestamp WHERE id = :id")
    suspend fun markCompleted(id: Long, fileSize: Long, completedAt: Long = System.currentTimeMillis(), timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history WHERE profileId = :profileId ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun getRecent(profileId: String, limit: Int = 20): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE profileId = :profileId ORDER BY searchedAt DESC LIMIT :limit")
    fun getRecentFlow(profileId: String, limit: Int = 20): kotlinx.coroutines.flow.Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntity): Long

    @Query("DELETE FROM search_history WHERE profileId = :profileId AND query = :query")
    suspend fun delete(profileId: String, query: String)

    @Query("DELETE FROM search_history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)

    @Query("DELETE FROM search_history WHERE searchedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY lastUsedAt DESC")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY lastUsedAt DESC")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}
