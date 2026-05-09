package com.streame.tv.data.sync

import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.ProfileRepository
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Inject

/**
 * Base class for all sync services providing:
 * - JWT refresh retry logic (shared across all services)
 * - Profile ID resolution (maps local UUID profile → integer profile_id for Supabase)
 */
abstract class BaseSyncService(
    protected val postgrest: Postgrest,
    protected val authManager: AuthManager,
    protected val profileManager: ProfileManager
) {
    protected suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Resolve the current profile's integer ID (1-based) for Supabase.
     * Supabase stores profiles with profile_id 1..N matching the local profile list order.
     */
    protected suspend fun resolveProfileId(): Int {
        val activeId = profileManager.getProfileId()
        if (activeId == "default") return 1
        val profiles = profileManager.getProfileList()
        val index = profiles.indexOfFirst { it.id == activeId }
        return if (index >= 0) index + 1 else 1
    }

    /**
     * Get all local profiles for profile-level sync operations.
     */
    protected suspend fun getLocalProfiles() = profileManager.getProfileList()
}
