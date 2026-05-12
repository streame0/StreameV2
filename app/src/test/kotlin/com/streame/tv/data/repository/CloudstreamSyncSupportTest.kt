package com.streame.tv.data.repository

import org.junit.Ignore
import org.junit.Test

class CloudstreamSyncSupportTest {

    @Ignore("Addon.installedArtifactPath removed; test needs rewrite")
    @Test
    fun `sanitizeAddonsForCloudSync strips local paths from cloudstream addons`() {
        // Needs rewrite — Addon data class no longer has installedArtifactPath field
    }

    @Ignore("mergeCloudstreamRepositoriesFromAddons removed; test needs rewrite")
    @Test
    fun `mergeCloudstreamRepositoriesFromAddons recovers missing repo records from synced addons`() {
        // Needs rewrite — mergeCloudstreamRepositoriesFromAddons function removed
    }
}
