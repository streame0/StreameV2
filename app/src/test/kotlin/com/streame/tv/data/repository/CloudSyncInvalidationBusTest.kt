package com.streame.tv.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncInvalidationBusTest {
    @Test
    fun `markDirty emits local invalidations`() = runTest {
        val bus = CloudSyncInvalidationBus()

        bus.events.test {
            bus.markDirty(CloudSyncScope.WATCHLIST, "kids", "favorite show")

            val event = awaitItem()
            assertEquals(CloudSyncScope.WATCHLIST, event.scope)
            assertEquals("kids", event.profileId)
            assertEquals("favorite show", event.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `remote apply suppresses invalidations and restores state`() = runTest {
        val bus = CloudSyncInvalidationBus()

        bus.events.test {
            assertFalse(bus.isApplyingRemoteState)

            bus.suppressDuringRemoteApply {
                assertTrue(bus.isApplyingRemoteState)
                bus.markDirty(CloudSyncScope.ADDONS, "main", "remote addon restore")
            }

            assertFalse(bus.isApplyingRemoteState)
            expectNoEvents()

            bus.markDirty(CloudSyncScope.ADDONS, "main", "local addon edit")
            assertEquals("local addon edit", awaitItem().reason)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
