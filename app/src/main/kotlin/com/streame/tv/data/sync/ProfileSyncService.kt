package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.ProfileRepository
import com.streame.tv.data.model.Profile
import com.streame.tv.data.remote.supabase.SupabaseProfile
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"

@Singleton
class ProfileSyncService @Inject constructor(
    postgrest: Postgrest,
    authManager: AuthManager,
    profileManager: ProfileManager,
    private val profileRepository: ProfileRepository
) : BaseSyncService(postgrest, authManager, profileManager) {

    suspend fun pullFromRemote(): Result<List<SupabaseProfile>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(IllegalStateException("Unable to resolve sync owner"))
            val profiles = withJwtRefreshRetry {
                postgrest.from("profiles")
                    .select {
                        filter { eq("user_id", effectiveUserId) }
                    }
                    .decodeList<SupabaseProfile>()
            }
            Log.d(TAG, "Pulled ${profiles.size} profiles from remote")

            // Apply pulled profiles to local storage
            if (profiles.isNotEmpty()) {
                try {
                    val localProfiles = profiles.map { cloud ->
                        Profile(
                            id = cloud.profileIndex.toString(),
                            name = cloud.name,
                            avatarColor = parseHexColor(cloud.avatarColorHex),
                            avatarId = cloud.avatarId?.toIntOrNull() ?: 0,
                            isLocked = false,
                            createdAt = parseEpochMilli(cloud.createdAt),
                            lastUsedAt = parseEpochMilli(cloud.updatedAt)
                        )
                    }
                    profileRepository.replaceProfilesFromCloud(localProfiles, activeProfileId = null)
                    Log.d(TAG, "Applied ${localProfiles.size} cloud profiles to local storage")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply cloud profiles locally", e)
                }
            }

            Result.success(profiles)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profiles from remote", e)
            Result.failure(e)
        }
    }

    suspend fun pushToRemote(profiles: List<SupabaseProfile>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(IllegalStateException("Unable to resolve sync owner"))
            withJwtRefreshRetry {
                postgrest.from("profiles").delete {
                    filter { eq("user_id", effectiveUserId) }
                }
                profiles.forEach { profile ->
                    postgrest.from("profiles").insert(profile.copy(userId = effectiveUserId))
                }
            }
            Log.d(TAG, "Pushed ${profiles.size} profiles to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e)
            Result.failure(e)
        }
    }

    private fun parseHexColor(hex: String): Long {
        return try {
            val clean = hex.removePrefix("#")
            java.lang.Long.parseLong(clean, 16) or 0xFF000000L
        } catch (_: Exception) {
            0xFF1E88E5L
        }
    }

    private fun parseEpochMilli(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(isoString).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
