package com.streame.tv.data.sync

import android.content.Context
import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.remote.supabase.SupabaseHomeCatalogSettingsBlob
import com.streame.tv.util.settingsDataStore
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeCatalogSettingsSyncService"

@Singleton
class HomeCatalogSettingsSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    @ApplicationContext private val appContext: Context
) : BaseSyncService(postgrest, authManager, profileManager) {

    suspend fun pushToRemote(
        profileId: Int,
        settingsJson: JsonElement
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", settingsJson)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_home_catalog_settings", params)
            }
            Log.d(TAG, "Pushed home catalog settings to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push home catalog settings to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull home catalog settings from remote AND apply to local DataStore.
     */
    suspend fun pullFromRemote(profileId: Int): Result<SupabaseHomeCatalogSettingsBlob?> = withContext(Dispatchers.IO) {
        try {
            val results = withJwtRefreshRetry {
                postgrest.from("home_catalog_settings")
                    .select { filter { eq("profile_id", profileId) } }
                    .decodeList<SupabaseHomeCatalogSettingsBlob>()
            }
            val result = results.firstOrNull()

            // Apply pulled catalog settings to local DataStore
            if (result != null) {
                try {
                    val catalogKey = profileManager.profileStringKey("home_catalog_settings_v1")
                    appContext.settingsDataStore.edit { prefs ->
                        prefs[catalogKey] = result.settingsJson.toString()
                    }
                    Log.d(TAG, "Applied cloud home catalog settings to local DataStore for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud home catalog settings locally", e)
                }
            }

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull home catalog settings from remote", e)
            Result.failure(e)
        }
    }
}
