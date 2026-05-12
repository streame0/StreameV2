package com.streame.tv.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for recent search queries.
 * Enables "Recent Searches" section above the search input.
 */
@Entity(
    tableName = "search_history",
    indices = [
        Index("profileId"),
        Index("query"),
        Index("searchedAt")
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)
