package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.WatchlistRepository
import com.streame.tv.data.remote.supabase.SupabaseLibraryItem
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

private const val TAG = "LibrarySyncService"

@Singleton
class LibrarySyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    private val watchlistRepositoryProvider: Provider<WatchlistRepository>
) : BaseSyncService(postgrest, authManager, profileManager) {

    suspend fun pushToRemote(
        items: List<SupabaseLibraryItem>,
        profileId: Int = 1
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_items", buildJsonArray {
                    items.forEach { item ->
                        addJsonObject {
                            put("content_id", item.contentId)
                            put("content_type", item.contentType)
                            put("name", item.name)
                            item.poster?.let { put("poster", it) }
                            item.posterShape.let { put("poster_shape", it) }
                            item.background?.let { put("background", it) }
                            item.description?.let { put("description", it) }
                            item.releaseInfo?.let { put("release_info", it) }
                            item.imdbRating?.let { put("imdb_rating", it) }
                            item.addonBaseUrl?.let { put("addon_base_url", it) }
                            put("added_at", item.addedAt)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_library", params)
            }
            Log.d(TAG, "Pushed ${items.size} library items to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push library to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull library items from remote AND apply them to local watchlist.
     */
    suspend fun pullFromRemote(profileId: Int = 1): Result<List<SupabaseLibraryItem>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(IllegalStateException("Unable to resolve sync owner"))
            val items = withJwtRefreshRetry {
                postgrest.from("library")
                    .select {
                        filter {
                            eq("user_id", effectiveUserId)
                            eq("profile_id", profileId)
                        }
                    }
                    .decodeList<SupabaseLibraryItem>()
            }
            Log.d(TAG, "Pulled ${items.size} library items from remote")

            // Apply pulled items to local watchlist
            if (items.isNotEmpty()) {
                try {
                    watchlistRepositoryProvider.get().mergeFromCloud(items)
                    Log.d(TAG, "Applied ${items.size} cloud library items to local watchlist")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud library items locally", e)
                }
            }

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull library from remote", e)
            Result.failure(e)
        }
    }
}
