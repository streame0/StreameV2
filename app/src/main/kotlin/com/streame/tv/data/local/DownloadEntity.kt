package com.streame.tv.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for downloaded content available for offline playback.
 * Tracks download state, local file path, and metadata for the Downloads screen.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index("profileId"),
        Index("tmdbId", "mediaType"),
        Index("status")
    ]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val tmdbId: Int,
    val mediaType: String,         // "movie" or "tv"
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val seasonNumber: Int? = null,  // null for movies
    val episodeNumber: Int? = null, // null for movies
    val episodeTitle: String? = null,
    /** Source URL that was downloaded */
    val sourceUrl: String,
    /** Local file path relative to app's download directory */
    val localPath: String,
    /** File size in bytes */
    val fileSizeBytes: Long = 0,
    /** Download status: "queued", "downloading", "completed", "failed", "paused" */
    val status: String = "queued",
    /** Progress percentage 0-100 */
    val progress: Int = 0,
    /** Error message if status == "failed" */
    val errorMessage: String? = null,
    @ColumnInfo(defaultValue = "0")
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val completedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
)
