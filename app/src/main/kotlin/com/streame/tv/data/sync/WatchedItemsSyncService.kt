package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.TraktRepository
import com.streame.tv.data.remote.supabase.SupabaseWatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchedItemsSyncService"

@Singleton
class WatchedItemsSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    private val traktRepository: TraktRepository
) : BaseSyncService(postgrest, authManager, profileManager) {

    suspend fun pushToRemote(
        items: List<SupabaseWatchedItem>,
        profileId: Int = 1
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_items", buildJsonArray {
                    items.forEach { item ->
                        addJsonObject {
                            put("content_id", item.contentId)
                            put("content_type", item.contentType)
                            put("title", item.title)
                            item.season?.let { put("season", it) }
                            item.episode?.let { put("episode", it) }
                            put("watched_at", item.watchedAt)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry { postgrest.rpc("sync_push_watched_items", params) }
            Log.d(TAG, "Pushed ${items.size} watched items to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched items to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull watched items from remote AND apply to local Trakt cache.
     */
    suspend fun pullFromRemote(profileId: Int = 1): Result<List<SupabaseWatchedItem>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(IllegalStateException("Unable to resolve sync owner"))
            val items = withJwtRefreshRetry {
                postgrest.from("watched_items")
                    .select {
                        filter {
                            eq("user_id", effectiveUserId)
                            eq("profile_id", profileId)
                        }
                    }
                    .decodeList<SupabaseWatchedItem>()
            }
            Log.d(TAG, "Pulled ${items.size} watched items from remote")

            // Apply pulled items to local watched cache
            if (items.isNotEmpty()) {
                try {
                    traktRepository.mergeWatchedFromCloud(items)
                    Log.d(TAG, "Applied ${items.size} cloud watched items to local cache")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud watched items locally", e)
                }
            }

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items from remote", e)
            Result.failure(e)
        }
    }
}
