package com.streame.tv.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for user profiles.
 * Replaces the previous DataStore-based JSON blob storage,
 * enabling reactive queries, type-safe access, and proper migration support.
 */
@Entity(
    tableName = "profiles",
    indices = [
        Index("cloudUserId"),
        Index("lastUsedAt")
    ]
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarColor: Long,
    val avatarId: Int = 0,
    val isKidsProfile: Boolean = false,
    val pin: String? = null,
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val cloudUserId: String? = null,
    val cloudEmail: String? = null
)
