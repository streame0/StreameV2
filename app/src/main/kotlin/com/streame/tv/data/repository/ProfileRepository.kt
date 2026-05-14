package com.streame.tv.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.streame.tv.data.local.ProfileDao
import com.streame.tv.data.local.ProfileEntity
import com.streame.tv.data.model.Profile
import com.streame.tv.data.model.ProfileColors
import com.streame.tv.util.profilesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val profileDao: ProfileDao
) {
    private val gson = Gson()
    private val profileListType = object : TypeToken<List<Profile>>() {}.type

    companion object {
        private val PROFILES_KEY = stringPreferencesKey("profiles")
        private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")
        private const val TAG = "ProfileRepository"
        private const val MIGRATION_DONE_KEY = "profiles_room_migration_done"
    }

    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // One-time migration: copy profiles from DataStore JSON to Room.
        // Runs on IO to avoid blocking the main thread / causing ANR.
        migrationScope.launch {
            migrateFromDataStoreIfNeeded()
        }
    }

    /**
     * Flow of all profiles (from Room)
     */
    val profiles: Flow<List<Profile>> = profileDao.getAllFlow().map { entities ->
        entities.map { it.toProfile() }
    }

    /**
     * Flow of the active profile ID (still from DataStore — lightweight key)
     */
    val activeProfileId: Flow<String?> = context.profilesDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_KEY]
    }

    /**
     * Flow of the active profile
     */
    val activeProfile: Flow<Profile?> = combine(
        profileDao.getAllFlow(),
        context.profilesDataStore.data.map { prefs -> prefs[ACTIVE_PROFILE_KEY] }
    ) { entities, activeId ->
        activeId?.let { id -> entities.find { it.id == id }?.toProfile() }
    }

    suspend fun getProfiles(): List<Profile> = profileDao.getAll().map { it.toProfile() }

    suspend fun getActiveProfileId(): String? {
        return context.profilesDataStore.data.first()[ACTIVE_PROFILE_KEY]
    }

    suspend fun getActiveProfile(): Profile? {
        val activeId = getActiveProfileId() ?: return null
        return profileDao.getById(activeId)?.toProfile()
    }

    suspend fun hasProfiles(): Boolean = profileDao.getAll().isNotEmpty()

    suspend fun createProfile(name: String, avatarColor: Long, avatarId: Int = 0, isKidsProfile: Boolean = false): Profile {
        val profile = Profile(
            name = name,
            avatarColor = avatarColor,
            avatarId = avatarId,
            isKidsProfile = isKidsProfile
        )
        profileDao.upsert(profile.toEntity())
        return profile
    }

    suspend fun updateProfile(profile: Profile) {
        profileDao.upsert(profile.toEntity())
    }

    suspend fun deleteProfile(profileId: String) {
        profileDao.delete(profileId)
        // If we deleted the active profile, clear it
        context.profilesDataStore.edit { prefs ->
            if (prefs[ACTIVE_PROFILE_KEY] == profileId) {
                prefs.remove(ACTIVE_PROFILE_KEY)
            }
        }
    }

    suspend fun setActiveProfile(profileId: String) {
        context.profilesDataStore.edit { prefs ->
            prefs[ACTIVE_PROFILE_KEY] = profileId
        }
        // Update lastUsedAt
        val entity = profileDao.getById(profileId)
        if (entity != null) {
            profileDao.upsert(entity.copy(lastUsedAt = System.currentTimeMillis()))
        }
    }

    suspend fun clearActiveProfile() {
        context.profilesDataStore.edit { prefs ->
            prefs.remove(ACTIVE_PROFILE_KEY)
        }
    }

    suspend fun createDefaultProfileIfNeeded(): Profile? {
        if (hasProfiles()) return null
        return createProfile(
            name = "Profile 1",
            avatarColor = ProfileColors.colors[0]
        )
    }

    /**
     * One-time migration: reads the JSON blob from DataStore and inserts
     * all profiles into Room. Runs only once (guarded by a flag in DataStore).
     */
    private suspend fun migrateFromDataStoreIfNeeded() {
        val prefs = context.profilesDataStore.data.first()
        if (prefs[stringPreferencesKey(MIGRATION_DONE_KEY)] == "true") return

        val json = prefs[PROFILES_KEY]
        if (!json.isNullOrBlank()) {
            val oldProfiles = try {
                gson.fromJson<List<Profile>>(json, profileListType) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (oldProfiles.isNotEmpty()) {
                profileDao.upsertAll(oldProfiles.map { it.toEntity() })
                Log.i(TAG, "Migrated ${oldProfiles.size} profiles from DataStore to Room")
            }
        }

        // Mark migration as done
        context.profilesDataStore.edit { prefs ->
            prefs[stringPreferencesKey(MIGRATION_DONE_KEY)] = "true"
        }
    }

    // ── Mappers ──────────────────────────────────────────────

    private fun ProfileEntity.toProfile() = Profile(
        id = id,
        name = name,
        avatarColor = avatarColor,
        avatarId = avatarId,
        isKidsProfile = isKidsProfile,
        pin = pin,
        isLocked = isLocked,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )

    private fun Profile.toEntity() = ProfileEntity(
        id = id,
        name = name,
        avatarColor = avatarColor,
        avatarId = avatarId,
        isKidsProfile = isKidsProfile,
        pin = pin,
        isLocked = isLocked,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )
}
