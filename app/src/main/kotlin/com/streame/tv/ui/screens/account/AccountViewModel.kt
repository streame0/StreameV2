package com.streame.tv.ui.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streame.tv.BuildConfig
import com.streame.tv.data.remote.supabase.SupabaseLinkedDevice
import com.streame.tv.data.repository.AuthManager
import com.streame.tv.data.repository.SupabaseAuthState
import com.streame.tv.data.sync.StartupSyncService
import com.streame.tv.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val TAG = "AccountViewModel"

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncRepository: SyncRepository,
    private val startupSyncService: StartupSyncService,
    private val avatarRepository: com.streame.tv.data.remote.supabase.AvatarRepository,
    private val profileRepository: com.streame.tv.data.repository.ProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private var qrLoginPollJob: Job? = null

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                _uiState.update {
                    it.copy(
                        authState = state,
                        connectedStats = if (state is SupabaseAuthState.FullAccount) it.connectedStats else null,
                        isStatsLoading = if (state is SupabaseAuthState.FullAccount) it.isStatsLoading else false
                    )
                }
                if (state is SupabaseAuthState.FullAccount) {
                    loadLinkedDevices()
                    // Link this cloud account to the current local profile so
                    // each profile can have its own independent cloud user.
                    linkCloudToCurrentProfile(state.userId, state.email)
                }
            }
        }
    }

    private fun linkCloudToCurrentProfile(cloudUserId: String, cloudEmail: String) {
        viewModelScope.launch {
            try {
                val activeProfile = profileRepository.getActiveProfile()
                if (activeProfile != null && activeProfile.cloudUserId != cloudUserId) {
                    profileRepository.linkCloudAccount(activeProfile.id, cloudUserId, cloudEmail)
                    Log.d(TAG, "Linked cloud account $cloudEmail to profile ${activeProfile.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to link cloud account to profile", e)
            }
        }
    }

    fun onEmailChange(email: String) = _uiState.update { it.copy(email = email) }
    fun onPasswordChange(password: String) = _uiState.update { it.copy(password = password) }
    fun onToggleSignUp() = _uiState.update { it.copy(isSignUp = !it.isSignUp) }
    fun onSyncCodePinChange(pin: String) = _uiState.update { it.copy(syncCodePin = pin) }
    fun onClaimCodeChange(code: String) = _uiState.update { it.copy(claimCode = code) }
    fun onClaimPinChange(pin: String) = _uiState.update { it.copy(claimPin = pin) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun signInWithEmail() {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = "Email and password are required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authManager.signInWithEmail(email, password)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isFailure) {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Sign in failed") }
            }
        }
    }

    fun signUpWithEmail() {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = "Email and password are required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authManager.signUpWithEmail(email, password)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isFailure) {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Sign up failed") }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _uiState.update { it.copy(
                email = "",
                password = "",
                syncCode = null,
                linkedDevices = emptyList(),
                connectedStats = null
            ) }
        }
    }

    // ── QR Login ──────────────────────────────────────────────

    fun startQrLogin() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, qrLoginStatus = null) }
            val sessionResult = authManager.ensureQrSessionAuthenticated()
            if (sessionResult.isFailure) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to initialize QR session") }
                return@launch
            }
            val deviceNonce = UUID.randomUUID().toString()
            val result = authManager.startTvLoginSession(
                deviceNonce = deviceNonce,
                deviceName = "Streame TV",
                redirectBaseUrl = BuildConfig.TV_LOGIN_WEB_BASE_URL
            )
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                val data = result.getOrThrow()
                _uiState.update { it.copy(
                    qrLoginCode = data.code,
                    qrLoginWebUrl = data.webUrl,
                    qrLoginDeviceNonce = deviceNonce,
                    qrLoginExpiresAtMillis = parseExpiryToMillis(data.expiresAt),
                    qrLoginPollIntervalSeconds = data.pollIntervalSeconds,
                    qrLoginStatus = "Waiting for scan..."
                ) }
                startQrPolling()
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Failed to start QR login") }
            }
        }
    }

    private fun startQrPolling() {
        qrLoginPollJob?.cancel()
        qrLoginPollJob = viewModelScope.launch {
            val interval = _uiState.value.qrLoginPollIntervalSeconds * 1000L
            while (true) {
                delay(interval)
                val code = _uiState.value.qrLoginCode ?: break
                val nonce = _uiState.value.qrLoginDeviceNonce ?: break
                val result = authManager.pollTvLoginSession(code, nonce)
                if (result.isSuccess) {
                    val pollData = result.getOrThrow()
                    val status = pollData.status.lowercase()
                    _uiState.update { it.copy(qrLoginStatus = pollData.status) }
                    when {
                        status.contains("approved") -> {
                            exchangeQrLogin()
                            break
                        }
                        status.contains("expired") -> {
                            _uiState.update { it.copy(qrLoginStatus = "Expired", qrLoginCode = null) }
                            break
                        }
                        status.contains("denied") -> {
                            _uiState.update { it.copy(qrLoginStatus = "Denied", qrLoginCode = null) }
                            break
                        }
                    }
                }
            }
        }
    }

    fun exchangeQrLogin() {
        viewModelScope.launch {
            val code = _uiState.value.qrLoginCode ?: return@launch
            val nonce = _uiState.value.qrLoginDeviceNonce ?: return@launch
            _uiState.update { it.copy(isLoading = true, qrLoginStatus = "Signing in...") }
            val result = authManager.exchangeTvLoginSession(code, nonce)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                clearQrLoginSession()
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "QR login exchange failed") }
            }
        }
    }

    fun clearQrLoginSession() {
        qrLoginPollJob?.cancel()
        qrLoginPollJob = null
        _uiState.update { it.copy(
            qrLoginCode = null,
            qrLoginWebUrl = null,
            qrLoginDeviceNonce = null,
            qrLoginStatus = null,
            qrLoginExpiresAtMillis = null
        ) }
    }

    // ── Sync Code ─────────────────────────────────────────────

    fun generateSyncCode() {
        val pin = _uiState.value.syncCodePin
        if (pin.isBlank()) {
            _uiState.update { it.copy(error = "PIN is required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = syncRepository.generateSyncCode(pin)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _uiState.update { it.copy(syncCode = result.getOrThrow()) }
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Failed to generate sync code") }
            }
        }
    }

    fun claimSyncCode() {
        val code = _uiState.value.claimCode.trim()
        val pin = _uiState.value.claimPin
        if (code.isBlank() || pin.isBlank()) {
            _uiState.update { it.copy(error = "Code and PIN are required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = syncRepository.claimSyncCode(code, pin, "Streame TV")
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                val claimResult = result.getOrThrow()
                if (claimResult.success) {
                    _uiState.update { it.copy(claimCode = "", claimPin = "", error = null) }
                    loadLinkedDevices()
                } else {
                    _uiState.update { it.copy(error = claimResult.message) }
                }
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Failed to claim sync code") }
            }
        }
    }

    fun unlinkDevice(deviceUserId: String) {
        viewModelScope.launch {
            val result = syncRepository.unlinkDevice(deviceUserId)
            if (result.isSuccess) {
                loadLinkedDevices()
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Failed to unlink device") }
            }
        }
    }

    private fun loadLinkedDevices() {
        viewModelScope.launch {
            val result = syncRepository.getLinkedDevices()
            if (result.isSuccess) {
                _uiState.update { it.copy(linkedDevices = result.getOrThrow()) }
            }
        }
    }

    fun requestSyncNow() {
        viewModelScope.launch {
            startupSyncService.pullAllData()
        }
    }

    // ── Account Management ────────────────────────────────────

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, showDeleteConfirm = false) }
            val result = authManager.deleteAccount()
            _uiState.update { it.copy(isLoading = false) }
            if (result.isFailure) {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Failed to delete account") }
            }
        }
    }

    fun sendPasswordResetEmail() {
        val email = _uiState.value.email.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(error = "Enter your email above first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authManager.sendPasswordResetEmail(email)
            _uiState.update { it.copy(isLoading = false) }
            if (result.isSuccess) {
                _uiState.update { it.copy(showPasswordResetSent = true) }
            } else {
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message ?: "Failed to send reset email") }
            }
        }
    }

    fun onShowDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = true) }
    fun onDismissDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = false) }
    fun onShowSignOutConfirm() = _uiState.update { it.copy(showSignOutConfirm = true) }
    fun onDismissSignOutConfirm() = _uiState.update { it.copy(showSignOutConfirm = false) }
    fun onDismissPasswordResetSent() = _uiState.update { it.copy(showPasswordResetSent = false) }

    // ── Profile PIN ─────────────────────────────────────────────

    fun onProfilePinChange(pin: String) = _uiState.update { it.copy(profilePin = pin) }
    fun onProfilePinCurrentChange(pin: String) = _uiState.update { it.copy(profilePinCurrent = pin) }

    fun onShowPinDialog(action: PinAction) = _uiState.update { it.copy(showPinDialog = true, pinAction = action, profilePin = "", profilePinCurrent = "", pinError = null) }
    fun onDismissPinDialog() = _uiState.update { it.copy(showPinDialog = false, pinError = null) }

    fun executePinAction(profileId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(pinError = null) }
            val result = when (_uiState.value.pinAction) {
                PinAction.SET -> syncRepository.setProfilePin(profileId, _uiState.value.profilePin, _uiState.value.profilePinCurrent.ifBlank { null })
                PinAction.VERIFY -> syncRepository.verifyProfilePin(profileId, _uiState.value.profilePin)
                PinAction.CLEAR -> syncRepository.clearProfilePin(profileId, _uiState.value.profilePinCurrent.ifBlank { null })
            }
            if (result.isSuccess) {
                val pinResult = result.getOrThrow()
                if (pinResult.first) {
                    _uiState.update { it.copy(showPinDialog = false) }
                } else {
                    _uiState.update { it.copy(pinError = pinResult.second) }
                }
            } else {
                _uiState.update { it.copy(pinError = result.exceptionOrNull()?.message ?: "PIN operation failed") }
            }
        }
    }

    // ── Avatar Catalog ──────────────────────────────────────────

    fun loadAvatarCatalog() {
        viewModelScope.launch {
            runCatching {
                val catalog = avatarRepository.getAvatarCatalog()
                _uiState.update { it.copy(avatarCatalog = catalog) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        qrLoginPollJob?.cancel()
    }

    private fun parseExpiryToMillis(isoString: String): Long {
        return try {
            val instant = java.time.Instant.parse(isoString)
            instant.toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis() + 300_000L // 5 min fallback
        }
    }

    private fun userFriendlyMessage(error: Throwable): String {
        val msg = error.message.orEmpty().lowercase()
        return when {
            msg.contains("invalid login credentials") -> "Invalid email or password"
            msg.contains("user already registered") -> "An account with this email already exists"
            msg.contains("network") -> "Network error — check your connection"
            else -> error.message ?: "An unexpected error occurred"
        }
    }
}
