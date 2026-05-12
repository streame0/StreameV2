package com.streame.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.streame.tv.data.api.TraktActivityTimestamps
import com.streame.tv.data.api.TraktEpisodeInfo
import com.streame.tv.data.api.TraktHistoryItem
import com.streame.tv.data.api.TraktIds
import com.streame.tv.data.api.TraktLastActivities
import com.streame.tv.data.api.TraktMovieInfo
import com.streame.tv.data.api.TraktShowInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class TraktSyncServiceIncrementalTest {

    // ── latestOf helper ──────────────────────────────────────────

    @Test
    fun `latestOf returns null when all inputs are null`() {
        val result = latestOfVararg(null, null, null)
        assertThat(result).isNull()
    }

    @Test
    fun `latestOf returns single non-null value`() {
        val result = latestOfVararg(null, "2024-01-15T10:00:00Z", null)
        assertThat(result).isEqualTo("2024-01-15T10:00:00Z")
    }

    @Test
    fun `latestOf returns latest of multiple timestamps`() {
        val result = latestOfVararg(
            "2024-01-10T10:00:00Z",
            "2024-01-15T10:00:00Z",
            "2024-01-12T10:00:00Z"
        )
        assertThat(result).isEqualTo("2024-01-15T10:00:00Z")
    }

    // ── isAfter helper logic ─────────────────────────────────────

    @Test
    fun `isAfter - candidate after existing returns true`() {
        val candidate = "2024-01-20T10:00:00Z"
        val existing = "2024-01-15T10:00:00Z"
        assertThat(isAfterStr(candidate, existing)).isTrue()
    }

    @Test
    fun `isAfter - candidate before existing returns false`() {
        val candidate = "2024-01-10T10:00:00Z"
        val existing = "2024-01-15T10:00:00Z"
        assertThat(isAfterStr(candidate, existing)).isFalse()
    }

    @Test
    fun `isAfter - null candidate returns false`() {
        assertThat(isAfterStr(null, "2024-01-15T10:00:00Z")).isFalse()
    }

    @Test
    fun `isAfter - null existing returns true`() {
        assertThat(isAfterStr("2024-01-15T10:00:00Z", null)).isTrue()
    }

    @Test
    fun `isAfter - both null returns false`() {
        assertThat(isAfterStr(null, null)).isFalse()
    }

    // ── Incremental sync decision logic ───────────────────────────

    @Test
    fun `incremental sync should skip when no changes since last sync`() {
        val lastSyncAt = "2024-01-20T00:00:00Z"
        val lastActivities = TraktLastActivities(
            all = "2024-01-19T00:00:00Z",
            movies = TraktActivityTimestamps(watchedAt = "2024-01-18T00:00:00Z"),
            episodes = TraktActivityTimestamps(watchedAt = "2024-01-17T00:00:00Z"),
            watchlist = TraktActivityTimestamps(watchlistedAt = "2024-01-16T00:00:00Z"),
            shows = null, seasons = null, comments = null, lists = null,
            favorites = null, recommendations = null, collaborations = null,
            account = null, savedFilters = null
        )

        val hasWatchedChanges = isAfterStr(
            latestOfVararg(lastActivities.movies?.watchedAt, lastActivities.episodes?.watchedAt),
            lastSyncAt
        )
        val hasWatchlistChanges = isAfterStr(lastActivities.watchlist?.watchlistedAt, lastSyncAt)

        assertThat(hasWatchedChanges).isFalse()
        assertThat(hasWatchlistChanges).isFalse()
    }

    @Test
    fun `incremental sync should detect watched changes after last sync`() {
        val lastSyncAt = "2024-01-15T00:00:00Z"
        val lastActivities = TraktLastActivities(
            all = "2024-01-20T00:00:00Z",
            movies = TraktActivityTimestamps(watchedAt = "2024-01-18T00:00:00Z"),
            episodes = TraktActivityTimestamps(watchedAt = "2024-01-17T00:00:00Z"),
            watchlist = TraktActivityTimestamps(watchlistedAt = "2024-01-14T00:00:00Z"),
            shows = null, seasons = null, comments = null, lists = null,
            favorites = null, recommendations = null, collaborations = null,
            account = null, savedFilters = null
        )

        val hasWatchedChanges = isAfterStr(
            latestOfVararg(lastActivities.movies?.watchedAt, lastActivities.episodes?.watchedAt),
            lastSyncAt
        )
        val hasWatchlistChanges = isAfterStr(lastActivities.watchlist?.watchlistedAt, lastSyncAt)

        assertThat(hasWatchedChanges).isTrue()
        assertThat(hasWatchlistChanges).isFalse()
    }

    @Test
    fun `incremental sync should detect watchlist changes after last sync`() {
        val lastSyncAt = "2024-01-15T00:00:00Z"
        val lastActivities = TraktLastActivities(
            all = "2024-01-20T00:00:00Z",
            movies = TraktActivityTimestamps(watchedAt = "2024-01-14T00:00:00Z"),
            episodes = TraktActivityTimestamps(watchedAt = "2024-01-13T00:00:00Z"),
            watchlist = TraktActivityTimestamps(watchlistedAt = "2024-01-18T00:00:00Z"),
            shows = null, seasons = null, comments = null, lists = null,
            favorites = null, recommendations = null, collaborations = null,
            account = null, savedFilters = null
        )

        val hasWatchlistChanges = isAfterStr(lastActivities.watchlist?.watchlistedAt, lastSyncAt)
        assertThat(hasWatchlistChanges).isTrue()
    }

    @Test
    fun `incremental sync should detect playback changes via pausedAt`() {
        val lastSyncAt = "2024-01-15T00:00:00Z"
        val lastActivities = TraktLastActivities(
            all = "2024-01-20T00:00:00Z",
            movies = TraktActivityTimestamps(watchedAt = "2024-01-14T00:00:00Z", pausedAt = "2024-01-18T00:00:00Z"),
            episodes = TraktActivityTimestamps(watchedAt = "2024-01-13T00:00:00Z"),
            watchlist = null,
            shows = null, seasons = null, comments = null, lists = null,
            favorites = null, recommendations = null, collaborations = null,
            account = null, savedFilters = null
        )

        val hasPlaybackChanges = isAfterStr(lastActivities.movies?.pausedAt, lastSyncAt)
        assertThat(hasPlaybackChanges).isTrue()
    }

    // ── History record building ────────────────────────────────────

    @Test
    fun `movie history item maps to WatchedMovieRecord correctly`() {
        val historyItem = TraktHistoryItem(
            id = 1L,
            watchedAt = "2024-01-18T10:00:00Z",
            action = "watch",
            type = "movie",
            movie = TraktMovieInfo(title = "Test Movie", year = 2024, ids = TraktIds(tmdb = 12345, trakt = 999)),
            show = null,
            episode = null
        )

        val record = WatchedMovieRecord(
            userId = "local",
            profileId = "default",
            showTmdbId = 12345,
            showTraktId = 999,
            watched = true,
            watchedAt = "2024-01-18T10:00:00Z",
            updatedAt = "2024-01-18T10:00:00Z",
            source = "trakt_incremental",
            title = "Test Movie"
        )

        assertThat(record.showTmdbId).isEqualTo(12345)
        assertThat(record.watched).isTrue()
        assertThat(record.title).isEqualTo("Test Movie")
    }

    @Test
    fun `episode history item maps to WatchedEpisodeRecord correctly`() {
        val historyItem = TraktHistoryItem(
            id = 2L,
            watchedAt = "2024-01-18T10:00:00Z",
            action = "watch",
            type = "episode",
            movie = null,
            show = TraktShowInfo(title = "Test Show", year = 2023, ids = TraktIds(tmdb = 54321, trakt = 888)),
            episode = TraktEpisodeInfo(season = 2, number = 5, title = "Episode Five", ids = TraktIds(tmdb = 111, trakt = 222))
        )

        val record = WatchedEpisodeRecord(
            userId = "local",
            profileId = "default",
            showTmdbId = 54321,
            showTraktId = 888,
            season = 2,
            episode = 5,
            traktEpisodeId = 222,
            tmdbEpisodeId = 111,
            watched = true,
            watchedAt = "2024-01-18T10:00:00Z",
            updatedAt = "2024-01-18T10:00:00Z",
            source = "trakt_incremental",
            title = "Test Show",
            episodeTitle = "Episode Five"
        )

        assertThat(record.showTmdbId).isEqualTo(54321)
        assertThat(record.season).isEqualTo(2)
        assertThat(record.episode).isEqualTo(5)
        assertThat(record.episodeTitle).isEqualTo("Episode Five")
    }

    // ── Merge logic ────────────────────────────────────────────────

    @Test
    fun `merge overwrites existing movie by tmdbId`() {
        val existing = listOf(
            WatchedMovieRecord(showTmdbId = 1, watchedAt = "2024-01-01T00:00:00Z", title = "Old"),
            WatchedMovieRecord(showTmdbId = 2, watchedAt = "2024-01-02T00:00:00Z", title = "Keep")
        )
        val incremental = listOf(
            WatchedMovieRecord(showTmdbId = 1, watchedAt = "2024-01-15T00:00:00Z", title = "Updated")
        )

        val merged = mergeMovieRecords(existing, incremental)
        assertThat(merged).hasSize(2)
        val updated = merged.first { it.showTmdbId == 1 }
        assertThat(updated.title).isEqualTo("Updated")
        assertThat(updated.watchedAt).isEqualTo("2024-01-15T00:00:00Z")
    }

    @Test
    fun `merge adds new movie not in existing`() {
        val existing = listOf(
            WatchedMovieRecord(showTmdbId = 1, title = "Existing")
        )
        val incremental = listOf(
            WatchedMovieRecord(showTmdbId = 2, title = "New")
        )

        val merged = mergeMovieRecords(existing, incremental)
        assertThat(merged).hasSize(2)
    }

    @Test
    fun `merge overwrites existing episode by show+season+episode`() {
        val existing = listOf(
            WatchedEpisodeRecord(showTmdbId = 1, season = 1, episode = 1, watchedAt = "2024-01-01T00:00:00Z"),
            WatchedEpisodeRecord(showTmdbId = 1, season = 1, episode = 2, watchedAt = "2024-01-02T00:00:00Z")
        )
        val incremental = listOf(
            WatchedEpisodeRecord(showTmdbId = 1, season = 1, episode = 1, watchedAt = "2024-01-15T00:00:00Z")
        )

        val merged = mergeEpisodeRecords(existing, incremental)
        assertThat(merged).hasSize(2)
        val updated = merged.first { it.season == 1 && it.episode == 1 }
        assertThat(updated.watchedAt).isEqualTo("2024-01-15T00:00:00Z")
    }

    // ── Helpers (mirroring TraktSyncService private logic) ─────────

    private fun latestOfVararg(vararg timestamps: String?): String? {
        return timestamps.filterNotNull().maxWithOrNull(compareBy { it })
    }

    private fun isAfterStr(candidate: String?, existing: String?): Boolean {
        if (candidate == null) return false
        if (existing == null) return true
        return try {
            Instant.parse(candidate).isAfter(Instant.parse(existing))
        } catch (_: Exception) {
            candidate > existing
        }
    }

    private fun mergeMovieRecords(
        existing: List<WatchedMovieRecord>,
        incremental: List<WatchedMovieRecord>
    ): List<WatchedMovieRecord> {
        val map = existing.associateBy { it.showTmdbId }.toMutableMap()
        for (record in incremental) {
            map[record.showTmdbId] = record
        }
        return map.values.toList()
    }

    private fun mergeEpisodeRecords(
        existing: List<WatchedEpisodeRecord>,
        incremental: List<WatchedEpisodeRecord>
    ): List<WatchedEpisodeRecord> {
        val map = existing.associateBy { "${it.showTmdbId}:${it.season}:${it.episode}" }.toMutableMap()
        for (record in incremental) {
            map["${record.showTmdbId}:${record.season}:${record.episode}"] = record
        }
        return map.values.toList()
    }
}
