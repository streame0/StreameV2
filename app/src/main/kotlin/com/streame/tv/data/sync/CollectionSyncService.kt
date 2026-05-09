package com.streame.tv.data.sync

import android.content.Context
import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.remote.supabase.SupabaseCollectionBlob
import com.streame.tv.util.settingsDataStore
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionSyncService"

@Singleton
class CollectionSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    @ApplicationContext private val appContext: Context
) : BaseSyncService(postgrest, authManager, profileManager) {

    private val gson = Gson()

    suspend fun pushToRemote(
        profileId: Int,
        collectionsJson: JsonElement
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", collectionsJson)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_collections", params)
            }
            Log.d(TAG, "Pushed collections to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push collections to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull collections from remote AND apply to local DataStore.
     */
    suspend fun pullFromRemote(profileId: Int): Result<SupabaseCollectionBlob?> = withContext(Dispatchers.IO) {
        try {
            val results = withJwtRefreshRetry {
                postgrest.from("collections")
                    .select { filter { eq("profile_id", profileId) } }
                    .decodeList<SupabaseCollectionBlob>()
            }
            val result = results.firstOrNull()

            // Apply pulled collections to local DataStore
            if (result != null) {
                try {
                    val collectionsKey = profileManager.profileStringKey("collections_v1")
                    appContext.settingsDataStore.edit { prefs ->
                        prefs[collectionsKey] = result.collectionsJson.toString()
                    }
                    Log.d(TAG, "Applied cloud collections to local DataStore for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud collections locally", e)
                }
            }

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull collections from remote", e)
            Result.failure(e)
        }
    }
}
