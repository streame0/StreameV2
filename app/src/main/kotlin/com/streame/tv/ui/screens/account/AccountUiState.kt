package com.streame.tv.ui.screens.account

import com.streame.tv.data.remote.supabase.SupabaseLinkedDevice
import com.streame.tv.data.repository.SupabaseAuthState

data class AccountUiState(
    val authState: SupabaseAuthState = SupabaseAuthState.Loading,
    val isLoading: Boolean = false,
    val error: String? = null,
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,

    // Account management
    val showDeleteConfirm: Boolean = false,
    val showSignOutConfirm: Boolean = false,
    val showPasswordResetSent: Boolean = false,

    // QR Login
    val qrLoginCode: String? = null,
    val qrLoginWebUrl: String? = null,
    val qrLoginDeviceNonce: String? = null,
    val qrLoginStatus: String? = null,
    val qrLoginExpiresAtMillis: Long? = null,
    val qrLoginPollIntervalSeconds: Int = 3,

    // Sync code
    val syncCode: String? = null,
    val syncCodePin: String = "",
    val claimCode: String = "",
    val claimPin: String = "",

    // Linked devices
    val linkedDevices: List<SupabaseLinkedDevice> = emptyList(),

    // Connected stats
    val connectedStats: ConnectedStats? = null,
    val isStatsLoading: Boolean = false,

    // Profile PIN
    val profilePin: String = "",
    val profilePinCurrent: String = "",
    val showPinDialog: Boolean = false,
    val pinAction: PinAction = PinAction.SET,
    val pinError: String? = null,

    // Avatar catalog
    val avatarCatalog: List<com.streame.tv.data.remote.supabase.AvatarCatalogItem> = emptyList()
)

enum class PinAction { SET, VERIFY, CLEAR }

data class ConnectedStats(
    val addonCount: Int = 0,
    val libraryCount: Int = 0,
    val watchProgressCount: Int = 0,
    val watchedItemsCount: Int = 0
)
