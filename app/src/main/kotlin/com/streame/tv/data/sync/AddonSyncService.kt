package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.remote.supabase.SupabaseAddon
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonSyncService"

@Singleton
class AddonSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager
) : BaseSyncService(postgrest, authManager, profileManager) {

    suspend fun pushToRemote(addonUrls: List<String>, profileId: Int = 1): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    addonUrls.forEachIndexed { index, url ->
                        addJsonObject {
                            put("url", url)
                            put("sort_order", index)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_addons", params)
            }
            Log.d(TAG, "Pushed ${addonUrls.size} addons to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonUrls(profileId: Int = 1): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for addon sync")
                )
            val remoteAddons = withJwtRefreshRetry {
                postgrest.from("addons")
                    .select {
                        filter {
                            eq("user_id", effectiveUserId)
                            eq("profile_id", profileId)
                        }
                    }
                    .decodeList<SupabaseAddon>()
            }
            Result.success(
                remoteAddons.sortedBy { it.sortOrder }.map { it.url }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs", e)
            Result.failure(e)
        }
    }
}
