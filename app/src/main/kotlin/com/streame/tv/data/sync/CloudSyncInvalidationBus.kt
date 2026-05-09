package com.streame.tv.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class CloudSyncScope {
    PROFILE_SETTINGS,
    PROFILES,
    ADDONS,
    CATALOGS,
    WATCHLIST,
    WATCH_PROGRESS,
    WATCHED_ITEMS,
    COLLECTIONS,
    HOME_CATALOG_SETTINGS,
    ACCOUNT
}

data class CloudSyncInvalidation(
    val scope: CloudSyncScope,
    val profileId: String? = null,
    val reason: String = "",
    val changedAt: Long = System.currentTimeMillis()
)

/**
 * Event bus for cloud sync invalidation events.
 *
 * When local data changes (e.g. user adds a watchlist item), the repository
 * calls [markDirty] to signal that the cloud state is now out of date.
 * [CloudSyncCoordinator] collects these events and triggers a debounced push.
 *
 * During a remote-apply (pull), [suppressDuringRemoteApply] prevents
 * the pull from triggering a redundant push back to the cloud — a common
 * echo problem when local DataStore writes from a cloud pull would otherwise
 * be interpreted as user-initiated changes.
 */
@Singleton
class CloudSyncInvalidationBus @Inject constructor() {
    private val _events = MutableSharedFlow<CloudSyncInvalidation>(
        extraBufferCapacity = 64
    )
    val events: SharedFlow<CloudSyncInvalidation> = _events.asSharedFlow()

    private val restoreDepth = AtomicInteger(0)

    /** True while a cloud pull is being applied to local storage. */
    val isApplyingRemoteState: Boolean
        get() = restoreDepth.get() > 0

    /**
     * Mark a sync scope as dirty. If a remote-apply is in progress
     * (i.e. we're currently pulling from cloud), the event is silently
     * dropped to avoid echo.
     */
    fun markDirty(scope: CloudSyncScope, profileId: String? = null, reason: String = "") {
        if (isApplyingRemoteState) return
        _events.tryEmit(
            CloudSyncInvalidation(
                scope = scope,
                profileId = profileId?.trim()?.takeIf { it.isNotBlank() },
                reason = reason
            )
        )
    }

    /**
     * Run [block] while suppressing any [markDirty] calls.
     * Use this when applying pulled cloud data to local storage so that
     * the local writes don't trigger a redundant push.
     */
    suspend fun <T> suppressDuringRemoteApply(block: suspend () -> T): T {
        restoreDepth.incrementAndGet()
        return try {
            block()
        } finally {
            restoreDepth.updateAndGet { depth -> (depth - 1).coerceAtLeast(0) }
        }
    }
}
