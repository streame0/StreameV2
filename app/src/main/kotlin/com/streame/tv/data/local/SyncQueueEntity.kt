package com.streame.tv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting failed sync operations.
 * When a push fails (network error, auth error, etc.), the operation
 * is stored here and retried when connectivity is restored.
 */
@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,       // CloudSyncScope name
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null
)
