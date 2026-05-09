package com.streame.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authSessionNoticeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session_notice_store"
)

@Singleton
class AuthSessionNoticeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val hadSupabaseAuthKey = booleanPreferencesKey("had_supabase_auth")
    private val supabaseExplicitLogoutKey = booleanPreferencesKey("supabase_explicit_logout")
    private val pendingSupabaseNoticeKey = booleanPreferencesKey("pending_supabase_notice")

    private val hadTraktAuthKey = booleanPreferencesKey("had_trakt_auth")
    private val traktExplicitLogoutKey = booleanPreferencesKey("trakt_explicit_logout")
    private val pendingTraktNoticeKey = booleanPreferencesKey("pending_trakt_notice")

    enum class StartupAuthNotice {
        SUPABASE,
        TRAKT
    }

    val pendingNotice: Flow<StartupAuthNotice?> = context.authSessionNoticeDataStore.data.map { preferences ->
        when {
            preferences[pendingSupabaseNoticeKey] == true -> StartupAuthNotice.SUPABASE
            preferences[pendingTraktNoticeKey] == true -> StartupAuthNotice.TRAKT
            else -> null
        }
    }

    suspend fun markSupabaseAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadSupabaseAuthKey] = true
            preferences[supabaseExplicitLogoutKey] = false
            preferences[pendingSupabaseNoticeKey] = false
        }
    }

    suspend fun markSupabaseExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadSupabaseAuthKey] = false
            preferences[supabaseExplicitLogoutKey] = true
            preferences[pendingSupabaseNoticeKey] = false
        }
    }

    suspend fun markUnexpectedSupabaseLogoutIfNeeded() {
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadSupabaseAuthKey] == true
            val explicitLogout = preferences[supabaseExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingSupabaseNoticeKey] = true
            }
            preferences[hadSupabaseAuthKey] = false
            preferences[supabaseExplicitLogoutKey] = false
        }
    }

    suspend fun markTraktAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadTraktAuthKey] = true
            preferences[traktExplicitLogoutKey] = false
            preferences[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markTraktExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = true
            preferences[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markUnexpectedTraktLogoutIfNeeded() {
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadTraktAuthKey] == true
            val explicitLogout = preferences[traktExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingTraktNoticeKey] = true
            }
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = false
        }
    }

    suspend fun consumeNotice(notice: StartupAuthNotice) {
        context.authSessionNoticeDataStore.edit { preferences ->
            when (notice) {
                StartupAuthNotice.SUPABASE -> preferences[pendingSupabaseNoticeKey] = false
                StartupAuthNotice.TRAKT -> preferences[pendingTraktNoticeKey] = false
            }
        }
    }
}
