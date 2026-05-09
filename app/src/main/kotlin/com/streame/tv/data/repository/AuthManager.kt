package com.streame.tv.data.repository

import android.util.Log
import com.streame.tv.BuildConfig
import com.streame.tv.data.local.AuthSessionNoticeDataStore
import com.streame.tv.data.remote.supabase.TvLoginExchangeResult
import com.streame.tv.data.remote.supabase.TvLoginPollResult
import com.streame.tv.data.remote.supabase.TvLoginStartResult
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthManager"

sealed class SupabaseAuthState {
    object Loading : SupabaseAuthState()
    object SignedOut : SupabaseAuthState()
    data class FullAccount(val userId: String, val email: String) : SupabaseAuthState()
}

@Singleton
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val httpClient: OkHttpClient,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    private val _authState = MutableStateFlow<SupabaseAuthState>(SupabaseAuthState.Loading)
    val authState: StateFlow<SupabaseAuthState> = _authState.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null

    init {
        observeSessionStatus()
    }

    private fun observeSessionStatus() {
        scope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = auth.currentUserOrNull()
                        if (user != null) {
                            if (cachedEffectiveUserSourceUserId != user.id) {
                                cachedEffectiveUserId = null
                                cachedEffectiveUserSourceUserId = null
                            }
                            if (user.email.isNullOrBlank()) {
                                _authState.value = SupabaseAuthState.SignedOut
                                authSessionNoticeDataStore.markUnexpectedSupabaseLogoutIfNeeded()
                            } else {
                                _authState.value = SupabaseAuthState.FullAccount(
                                    userId = user.id,
                                    email = user.email!!
                                )
                                authSessionNoticeDataStore.markSupabaseAuthenticated()
                            }
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val session = auth.currentSessionOrNull()
                        val refreshToken = session?.refreshToken?.takeIf { it.isNotBlank() }
                        if (refreshToken != null) {
                            scope.launch {
                                if (!refreshCurrentSessionSerialized(
                                        observedRefreshToken = refreshToken,
                                        reason = "Session became unauthenticated"
                                    )
                                ) {
                                    cachedEffectiveUserId = null
                                    cachedEffectiveUserSourceUserId = null
                                    _authState.value = SupabaseAuthState.SignedOut
                                    authSessionNoticeDataStore.markUnexpectedSupabaseLogoutIfNeeded()
                                }
                            }
                        } else {
                            cachedEffectiveUserId = null
                            cachedEffectiveUserSourceUserId = null
                            _authState.value = SupabaseAuthState.SignedOut
                            authSessionNoticeDataStore.markUnexpectedSupabaseLogoutIfNeeded()
                        }
                    }
                    is SessionStatus.Initializing -> {
                        _authState.value = SupabaseAuthState.Loading
                    }
                    else -> { /* NetworkError etc. — keep current state */ }
                }
            }
        }
    }

    val isAuthenticated: Boolean
        get() = _authState.value is SupabaseAuthState.FullAccount

    val currentUserId: String?
        get() = when (val state = _authState.value) {
            is SupabaseAuthState.FullAccount -> state.userId
            else -> null
        }

    val currentUserEmail: String?
        get() = when (val state = _authState.value) {
            is SupabaseAuthState.FullAccount -> state.email
            else -> null
        }

    val currentSupabaseUserId: String?
        get() = auth.currentUserOrNull()?.id

    /** Expose the current access token for WebSocket auth. */
    suspend fun currentAccessToken(): String? = auth.currentAccessTokenOrNull()

    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String? {
        val userId = currentUserId ?: return null
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        cachedEffectiveUserId?.let { return it }

        suspend fun resolveAndCache(): String {
            val result = postgrest.rpc("get_sync_owner")
            val effectiveId = result.decodeAs<String>()
            cachedEffectiveUserId = effectiveId
            cachedEffectiveUserSourceUserId = userId
            return effectiveId
        }

        return try {
            resolveAndCache()
        } catch (e: Exception) {
            if (refreshSessionIfJwtExpired(e)) {
                return try {
                    resolveAndCache()
                } catch (retryError: Exception) {
                    if (fallbackToOwnIdOnFailure) {
                        Log.e(TAG, "Failed to get effective user ID after refresh; falling back to own ID", retryError)
                        userId
                    } else null
                }
            }
            if (fallbackToOwnIdOnFailure) {
                Log.e(TAG, "Failed to get effective user ID, falling back to own ID", e)
                userId
            } else null
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun ensureQrSessionAuthenticated(): Result<Unit> {
        val user = auth.currentUserOrNull()
        val hasToken = auth.currentAccessTokenOrNull() != null
        if (user != null && hasToken) {
            return Result.success(Unit)
        }
        return try {
            auth.signInAnonymously()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "QR anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        authSessionNoticeDataStore.markSupabaseExplicitLogout()
        try {
            auth.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
        }
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }

    // ── Account Management ────────────────────────────────────

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            val token = auth.currentAccessTokenOrNull()
                ?: return Result.failure(Exception("Not authenticated"))

            // Soft-delete user data via RPC
            try {
                postgrest.rpc("soft_delete_user_data")
            } catch (e: Exception) {
                Log.e(TAG, "soft_delete_user_data RPC failed, continuing with auth deletion", e)
            }

            // Call delete-account Edge Function to remove auth user
            val payload = buildJsonObject {}.toString()
            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/delete-account")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val (success, code, body) = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { resp ->
                    Triple(resp.isSuccessful, resp.code, resp.body?.string().orEmpty())
                }
            }
            if (!success) {
                return Result.failure(Exception("Delete account failed ($code): $body"))
            }

            // Clear local state
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
            _authState.value = SupabaseAuthState.SignedOut
            authSessionNoticeDataStore.markSupabaseExplicitLogout()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account", e)
            Result.failure(e)
        }
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            auth.resetPasswordForEmail(email)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset email failed", e)
            Result.failure(e)
        }
    }


    fun clearEffectiveUserIdCache() {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean {
        if (!error.isJwtExpiredError()) return false
        val refreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
            ?: return false
        return refreshCurrentSessionSerialized(
            observedRefreshToken = refreshToken,
            reason = "JWT expired"
        )
    }

    private suspend fun refreshCurrentSessionSerialized(
        observedRefreshToken: String?,
        reason: String
    ): Boolean = refreshMutex.withLock {
        val currentRefreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
        if (currentRefreshToken == null) {
            Log.w(TAG, "$reason but no refresh token available; cannot refresh session")
            return@withLock false
        }
        if (observedRefreshToken != null && currentRefreshToken != observedRefreshToken) {
            Log.d(TAG, "$reason; session was already refreshed by another request")
            return@withLock true
        }
        return@withLock try {
            Log.w(TAG, "$reason; refreshing Supabase session")
            auth.refreshCurrentSession()
            true
        } catch (refreshError: Exception) {
            Log.e(TAG, "Failed to refresh Supabase session", refreshError)
            false
        }
    }

    // ── TV Login (QR) RPCs ──────────────────────────────────────

    suspend fun startTvLoginSession(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String
    ): Result<TvLoginStartResult> {
        return try {
            Result.success(startTvLoginSessionRpc(deviceNonce, deviceName, redirectBaseUrl))
        } catch (e: Exception) {
            val message = e.message.orEmpty().lowercase()
            val shouldRetryLegacy = !deviceName.isNullOrBlank() &&
                message.contains("could not find the function") &&
                message.contains("start_tv_login_session") &&
                message.contains("p_device_name")
            if (shouldRetryLegacy) {
                return try {
                    Log.w(TAG, "start_tv_login_session legacy signature; retrying without p_device_name")
                    Result.success(startTvLoginSessionRpc(deviceNonce, null, redirectBaseUrl))
                } catch (retryError: Exception) {
                    Log.e(TAG, "Failed to start TV login session after legacy retry", retryError)
                    Result.failure(retryError)
                }
            }
            Log.e(TAG, "Failed to start TV login session", e)
            Result.failure(e)
        }
    }

    private suspend fun startTvLoginSessionRpc(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String
    ): TvLoginStartResult {
        val params = buildJsonObject {
            put("p_device_nonce", deviceNonce)
            put("p_redirect_base_url", redirectBaseUrl)
            if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
        }
        val response = postgrest.rpc("start_tv_login_session", params)
        return response.decodeList<TvLoginStartResult>().firstOrNull()
            ?: throw Exception("Empty response from start_tv_login_session")
    }

    suspend fun pollTvLoginSession(code: String, deviceNonce: String): Result<TvLoginPollResult> {
        return try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_device_nonce", deviceNonce)
            }
            val response = postgrest.rpc("poll_tv_login_session", params)
            val result = response.decodeList<TvLoginPollResult>().firstOrNull()
                ?: return Result.failure(Exception("Empty response from poll_tv_login_session"))
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll TV login session", e)
            Result.failure(e)
        }
    }

    suspend fun exchangeTvLoginSession(code: String, deviceNonce: String): Result<Unit> {
        return try {
            val token = auth.currentAccessTokenOrNull()
                ?: return Result.failure(Exception("Not authenticated"))
            val payload = buildJsonObject {
                put("code", code)
                put("device_nonce", deviceNonce)
            }.toString()
            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/tv-logins-exchange")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val body = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("TV login exchange failed (${response.code}): $responseBody")
                    }
                    responseBody
                }
            }
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            auth.importAuthToken(result.accessToken, result.refreshToken)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exchange TV login session", e)
            Result.failure(e)
        }
    }
}

private fun Throwable.isJwtExpiredError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("jwt expired", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}
