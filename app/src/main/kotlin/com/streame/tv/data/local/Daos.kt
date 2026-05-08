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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId")
    suspend fun delete(profileId: String, mediaType: String, tmdbId: Int)

    @Query("UPDATE watch_history SET progress = :progress, positionSeconds = :position, updatedAt = :updatedAt WHERE profileId = :profileId AND mediaType = :mediaType AND tmdbId = :tmdbId AND (season IS NULL OR season = :season) AND (episode IS NULL OR episode = :episode)")
    suspend fun updateProgress(profileId: String, mediaType: String, tmdbId: Int, season: Int?, episode: Int?, progress: Int, position: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)
}
