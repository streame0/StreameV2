package com.streame.tv.data.repository

import org.junit.Ignore
import org.junit.Test

class TraktSyncServiceBenchmarkTest {

    @Ignore("Benchmark test needs rewrite — SupabaseApi methods removed in Trakt-only migration")
    @Test
    fun benchmarkStalePlaybackDeletion() {
        // This test previously benchmarked SupabaseApi.deleteWatchHistoryByIds
        // which was removed when the app migrated to Trakt-only sync.
        // A new benchmark should be written against TraktSyncService if needed.
    }
}
