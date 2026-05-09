package com.streame.tv.data.sync

import android.content.Context
import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.remote.supabase.SupabaseProfileSettingsBlob
import com.streame.tv.util.settingsDataStore
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSettingsSyncService"

@Singleton
class ProfileSettingsSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    @ApplicationContext private val appContext: Context
) : BaseSyncService(postgrest, authManager, profileManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private var lastPushedSignature: String? = null
    private var skipNextPushSignature: String? = null

    /** Timestamp of the last local settings edit. Cloud pulls within 60s of a local edit are skipped to avoid reverting user changes. */
    @Volatile
    var lastLocalEditMs: Long = 0L

    companion object {
        private const val DEBOUNCE_DELAY_MS = 1500L
    }

    suspend fun pushToRemote(
        profileId: Int,
        settingsJson: JsonObject
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val signature = computeSignature(settingsJson.toString())

            // Skip if this exact blob was already pushed
            if (signature == lastPushedSignature) {
                Log.d(TAG, "Skipping push — no changes since last push")
                return@withContext Result.success(Unit)
            }

            // Skip if this was just pulled (avoid echo)
            if (signature == skipNextPushSignature) {
                Log.d(TAG, "Skipping push — matches just-pulled remote")
                skipNextPushSignature = null
                return@withContext Result.success(Unit)
            }

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", settingsJson)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_profile_settings", params)
            }
            lastPushedSignature = signature
            lastLocalEditMs = System.currentTimeMillis()
            Log.d(TAG, "Pushed profile settings to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profile settings to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Debounced push — waits 1.5s before pushing, cancelling any previous pending push.
     */
    fun pushToRemoteDebounced(profileId: Int, settingsJson: JsonObject) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_DELAY_MS)
            pushToRemote(profileId, settingsJson)
        }
    }

    /**
     * Pull profile settings from remote AND apply to local DataStore.
     */
    suspend fun pullFromRemote(profileId: Int): Result<SupabaseProfileSettingsBlob?> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(IllegalStateException("Unable to resolve sync owner"))
            val results = withJwtRefreshRetry {
                postgrest.from("profile_settings")
                    .select {
                        filter {
                            eq("profile_id", profileId)
                        }
                    }
                    .decodeList<SupabaseProfileSettingsBlob>()
            }
            val result = results.firstOrNull()
            if (result != null) {
                // Mark the pulled signature so we skip the echo push
                skipNextPushSignature = computeSignature(result.settingsJson.toString())

                // Apply pulled settings to local DataStore
                try {
                    applySettingsToLocal(result.settingsJson, profileId)
                    Log.d(TAG, "Applied cloud profile settings to local DataStore for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud profile settings locally", e)
                }
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile settings from remote", e)
            Result.failure(e)
        }
    }

    /**
     * Write cloud settings JSON into the local settingsDataStore.
     * Maps JSON keys back to DataStore preference keys.
     */
    private suspend fun applySettingsToLocal(settingsJson: JsonObject, profileId: Int) {
        // Skip applying cloud settings if a local edit happened within the last 60 seconds
        // to avoid reverting user changes that haven't been pushed yet.
        val timeSinceLocalEdit = System.currentTimeMillis() - lastLocalEditMs
        if (lastLocalEditMs > 0L && timeSinceLocalEdit < 60_000L) {
            Log.d(TAG, "Skipping cloud settings apply — local edit was ${timeSinceLocalEdit}ms ago (< 60s)")
            return
        }
        val prefs = appContext.settingsDataStore.data.first()
        val currentMap = prefs.asMap().toMutableMap()
        var changed = false
        settingsJson.forEach { (key, value) ->
            val prefKey = currentMap.keys.find { it.name == key }
            if (prefKey != null) {
                when (value) {
                    is JsonPrimitive -> {
                        val existingVal = currentMap[prefKey]
                        when (existingVal) {
                            is String -> {
                                if (existingVal != value.content) { currentMap[prefKey] = value.content; changed = true }
                            }
                            is Boolean -> {
                                val boolVal = value.content.toBooleanStrictOrNull()
                                if (boolVal != null && existingVal != boolVal) { currentMap[prefKey] = boolVal; changed = true }
                            }
                            is Long -> {
                                val longVal = value.content.toLongOrNull()
                                if (longVal != null && existingVal != longVal) { currentMap[prefKey] = longVal; changed = true }
                            }
                            is Int -> {
                                val intVal = value.content.toIntOrNull()
                                if (intVal != null && existingVal != intVal) { currentMap[prefKey] = intVal; changed = true }
                            }
                            is Float -> {
                                val floatVal = value.content.toFloatOrNull()
                                if (floatVal != null && existingVal != floatVal) { currentMap[prefKey] = floatVal; changed = true }
                            }
                            else -> { /* unsupported type, skip */ }
                        }
                    }
                    is JsonArray, is JsonObject -> { /* nested objects/arrays not supported in prefs */ }
                    else -> { /* unsupported JSON type */ }
                }
            }
        }
        if (changed) {
            appContext.settingsDataStore.edit { editor ->
                currentMap.forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is String -> editor[key as androidx.datastore.preferences.core.Preferences.Key<String>] = value
                        is Boolean -> editor[key as androidx.datastore.preferences.core.Preferences.Key<Boolean>] = value
                        is Long -> editor[key as androidx.datastore.preferences.core.Preferences.Key<Long>] = value
                        is Int -> editor[key as androidx.datastore.preferences.core.Preferences.Key<Int>] = value
                        is Float -> editor[key as androidx.datastore.preferences.core.Preferences.Key<Float>] = value
                    }
                }
            }
        }
    }

    private fun computeSignature(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
