package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.WatchHistoryRepository
import com.streame.tv.data.remote.supabase.SupabaseWatchProgress
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "WatchProgressSyncService"

@Singleton
class WatchProgressSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    private val watchHistoryRepositoryProvider: Provider<WatchHistoryRepository>
) : BaseSyncService(postgrest, authManager, profileManager) {

    suspend fun pushToRemote(
        items: List<SupabaseWatchProgress>,
        profileId: Int = 1
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_items", buildJsonArray {
                    items.forEach { item ->
                        addJsonObject {
                            put("content_id", item.contentId)
                            put("content_type", item.contentType)
                            put("video_id", item.videoId)
                            item.season?.let { put("season", it) }
                            item.episode?.let { put("episode", it) }
                            put("position", item.position)
                            put("duration", item.duration)
                            put("last_watched", item.lastWatched)
                            put("progress_key", item.progressKey)
                            item.lastAddonId?.let { put("last_addon_id", it) }
                            item.lastSourceName?.let { put("last_source_name", it) }
                            item.lastBingeGroup?.let { put("last_binge_group", it) }
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watch_progress", params)
            }
            Log.d(TAG, "Pushed ${items.size} watch progress items to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watch progress to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull watch progress from remote AND apply to local cache.
     */
    suspend fun pullFromRemote(profileId: Int = 1): Result<List<SupabaseWatchProgress>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(IllegalStateException("Unable to resolve sync owner"))
            val items = withJwtRefreshRetry {
                postgrest.from("watch_progress")
                    .select {
                        filter {
                            eq("user_id", effectiveUserId)
                            eq("profile_id", profileId)
                        }
                    }
                    .decodeList<SupabaseWatchProgress>()
            }
            Log.d(TAG, "Pulled ${items.size} watch progress items from remote")

            // Apply pulled items to local watch history cache
            if (items.isNotEmpty()) {
                try {
                    watchHistoryRepositoryProvider.get().mergeFromCloud(items)
                    Log.d(TAG, "Applied ${items.size} cloud watch progress items to local cache")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud watch progress locally", e)
                }
            }

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watch progress from remote", e)
            Result.failure(e)
        }
    }

    suspend fun deleteFromRemote(progressKey: String, profileId: Int = 1): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_progress_key", progressKey)
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_watch_progress", params)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watch progress from remote", e)
            Result.failure(e)
        }
    }
}
