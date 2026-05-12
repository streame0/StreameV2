package com.streame.tv.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for watchlist items.
 * Replaces the DataStore JSON-blob storage with proper relational rows,
 * enabling fast lookups, incremental sync, and no ANRs from Gson deserialization.
 */
@Entity(
    tableName = "watchlist",
    indices = [
        Index(value = ["profileId", "mediaType", "tmdbId"], unique = true),
        Index(value = ["profileId", "addedAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class WatchlistEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileId: String,
    val mediaType: String,       // "tv" or "movie"
    val tmdbId: Int,
    val title: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val sourceOrder: Int = Int.MAX_VALUE,
    val updatedAt: Long = System.currentTimeMillis()
)
