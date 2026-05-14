package com.streame.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.streame.tv.util.AppLogger
import com.streame.tv.util.authDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * User profile data (local only)
 */
data class UserProfile(
    val id: String = "",
    val email: String = "",
    val trakt_username: String? = null,
    val addons: String? = null,
    val default_subtitle: String? = null,
    val auto_play_next: Boolean? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

/**
 * Authentication state for Trakt-based identity.
 */
sealed class AuthState {
    object Loading : AuthState()
    object NotAuthenticated : AuthState()
    data class Authenticated(
        val userId: String,
        val email: String,
        val profile: UserProfile?
    ) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * Repository for Trakt-based authentication and local user profile management.
 *
 * Auth state is derived from Trakt connection status.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktRepositoryProvider: Provider<TraktRepository>
) {

    // DataStore keys
    private object PrefsKeys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_EMAIL = stringPreferencesKey("user_email")
    }

    // Auth state
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // User profile
    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    /**
     * Check if user is logged in on app start.
     * Auth state is derived from Trakt connection.
     */
    suspend fun checkAuthState() {
        try {
            val prefs = context.authDataStore.data.first()
            val cachedUserId = prefs[PrefsKeys.USER_ID]
            val cachedEmail = prefs[PrefsKeys.USER_EMAIL]

            val isTraktConnected = traktRepositoryProvider.get().isAuthenticated.first()

            if (isTraktConnected && !cachedUserId.isNullOrBlank()) {
                val email = cachedEmail ?: cachedUserId
                val profile = UserProfile(id = cachedUserId, email = email)
                _userProfile.value = profile
                _authState.value = AuthState.Authenticated(cachedUserId, email, profile)
            } else if (isTraktConnected) {
                // Trakt connected but no cached user ID — generate one
                val userId = "trakt_user_${System.currentTimeMillis()}"
                val email = userId
                context.authDataStore.edit { prefs ->
                    prefs[PrefsKeys.USER_ID] = userId
                    prefs[PrefsKeys.USER_EMAIL] = email
                }
                val profile = UserProfile(id = userId, email = email)
                _userProfile.value = profile
                _authState.value = AuthState.Authenticated(userId, email, profile)
            } else if (!cachedUserId.isNullOrBlank()) {
                // Has cached identity but Trakt not connected — still treat as authenticated
                // (Trakt token may have expired but user identity persists)
                val email = cachedEmail ?: cachedUserId
                val profile = UserProfile(id = cachedUserId, email = email)
                _userProfile.value = profile
                _authState.value = AuthState.Authenticated(cachedUserId, email, profile)
            } else {
                _authState.value = AuthState.NotAuthenticated
            }
        } catch (e: Exception) {
            AppLogger.e("AuthRepository", "checkAuthState failed", e)
            _authState.value = AuthState.NotAuthenticated
        }
    }

    /**
     * Called when Trakt OAuth succeeds — marks user as authenticated.
     */
    suspend fun onTraktAuthenticated(accessToken: String, refreshToken: String?, username: String?) {
        val userId = username ?: "trakt_user_${System.currentTimeMillis()}"
        val email = username ?: userId

        context.authDataStore.edit { prefs ->
            prefs[PrefsKeys.USER_ID] = userId
            prefs[PrefsKeys.USER_EMAIL] = email
        }

        val profile = UserProfile(id = userId, email = email, trakt_username = username)
        _userProfile.value = profile
        _authState.value = AuthState.Authenticated(userId, email, profile)
    }

    /**
     * Sign out — disconnects Trakt and clears auth data.
     * Note: Only clears auth-specific DataStore; does NOT wipe user settings.
     */
    suspend fun signOut() {
        try {
            traktRepositoryProvider.get().logout()
        } catch (e: Exception) {
            AppLogger.e("AuthRepository", "Trakt logout failed", e)
        }

        context.authDataStore.edit { prefs -> prefs.clear() }

        _userProfile.value = null
        _authState.value = AuthState.NotAuthenticated
    }

    /**
     * Get current user ID
     */
    fun getCurrentUserId(): String? {
        return when (val state = _authState.value) {
            is AuthState.Authenticated -> state.userId
            else -> null
        }
    }


    /**
     * Get Trakt access token (delegates to TraktRepository)
     */
    suspend fun getAccessToken(): String? {
        return traktRepositoryProvider.get().refreshTokenIfNeeded()
    }

    /**
     * Get addons JSON from profile
     */
    fun getAddonsFromProfile(): String? {
        return _userProfile.value?.addons
    }

    /**
     * Get default subtitle from profile
     */
    fun getDefaultSubtitleFromProfile(): String? {
        return _userProfile.value?.default_subtitle
    }

    /**
     * Save default subtitle to local profile
     */
    fun saveDefaultSubtitleToProfile(subtitle: String?) {
        _userProfile.value = _userProfile.value?.copy(default_subtitle = subtitle)
    }

    /**
     * Get auto play next from profile
     */
    fun getAutoPlayNextFromProfile(): Boolean? {
        return _userProfile.value?.auto_play_next
    }

    /**
     * Save auto play next to local profile
     */
    fun saveAutoPlayNextToProfile(autoPlayNext: Boolean) {
        _userProfile.value = _userProfile.value?.copy(auto_play_next = autoPlayNext)
    }

    /**
     * Check if user has Trakt linked
     */
    suspend fun isTraktLinked(): Boolean {
        return runCatching { traktRepositoryProvider.get().isAuthenticated.first() }.getOrNull() == true
    }
}
