package com.streame.tv.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streame.tv.data.model.Category
import com.streame.tv.data.model.MediaItem

/**
 * Room entity for a home screen row (category).
 * Items are stored as JSON to keep the schema simple — the home screen
 * only needs to read/write whole rows, not query individual items.
 */
@Entity(tableName = "home_rows")
data class HomeRowEntity(
    @PrimaryKey val id: String,
    val title: String,
    val itemsJson: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toCategory(gson: Gson): Category {
        val type = object : TypeToken<List<MediaItem>>() {}.type
        val items: List<MediaItem> = gson.fromJson(itemsJson, type) ?: emptyList()
        return Category(id = id, title = title, items = items)
    }

    companion object {
        fun fromCategory(category: Category, gson: Gson): HomeRowEntity {
            return HomeRowEntity(
                id = category.id,
                title = category.title,
                itemsJson = gson.toJson(category.items),
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}

/**
 * Room entity for cached catalog configs.
 * Stores the user's catalog configuration so the home screen can
 * be rebuilt from local data without waiting for DataStore.
 */
@Entity(tableName = "catalog_configs")
data class CatalogConfigEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isPreinstalled: Boolean,
    val sourceUrl: String?,
    val sortOrder: Int,
    val isHidden: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Room entity for watch history / continue watching.
 * Replaces the complex Supabase-query-per-screen pattern with a local table.
 */
@Entity(
    tableName = "watch_history",
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["tmdbId", "mediaType"]),
        Index(value = ["updatedAt"]),
        Index(value = ["profileId", "progressKey"], unique = true)
    ]
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val userId: String = "",
    val profileId: String,
    val mediaType: String, // "movie" or "tv"
    val tmdbId: Int,
    val title: String = "",
    val posterPath: String?,
    val backdropPath: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val progress: Int, // 0-100
    val durationSeconds: Long,
    val positionSeconds: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String? = null,
    val videoId: String? = null,
    val progressKey: String? = null,
    // Last-played source info for same-source resume
    val lastAddonId: String? = null,
    val lastSourceName: String? = null,
    val lastBingeGroup: String? = null
)
