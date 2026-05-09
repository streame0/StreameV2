package com.streame.tv.domain.repository

import com.streame.tv.data.remote.supabase.ClaimSyncResult
import com.streame.tv.data.remote.supabase.SupabaseLinkedDevice

interface SyncRepository {
    suspend fun generateSyncCode(pin: String): Result<String>
    suspend fun getSyncCode(pin: String): Result<String>
    suspend fun claimSyncCode(code: String, pin: String, deviceName: String?): Result<ClaimSyncResult>
    suspend fun unlinkDevice(deviceUserId: String): Result<Unit>
    suspend fun getLinkedDevices(): Result<List<SupabaseLinkedDevice>>

    // Profile PIN
    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<Pair<Boolean, String>>
    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String?): Result<Pair<Boolean, String>>
    suspend fun clearProfilePin(profileId: Int, currentPin: String?): Result<Pair<Boolean, String>>
}
