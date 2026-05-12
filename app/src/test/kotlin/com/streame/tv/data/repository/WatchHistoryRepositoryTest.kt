package com.streame.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.streame.tv.data.local.WatchHistoryDao
import com.streame.tv.data.local.WatchHistoryEntity
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchHistoryRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val watchHistoryDao: WatchHistoryDao = mockk(relaxed = true)

    private val testEntity = WatchHistoryEntity(
        profileId = "profile-1",
        mediaType = "movie",
        tmdbId = 12345,
        title = "Test Movie",
        posterPath = "/poster.jpg",
        backdropPath = "/backdrop.jpg",
        progress = 50,
        durationSeconds = 9000L,
        positionSeconds = 4500L,
        updatedAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        coEvery { watchHistoryDao.getContinueWatching(any()) } returns flowOf(listOf(testEntity))
        coEvery { watchHistoryDao.getAllHistory(any()) } returns flowOf(listOf(testEntity))
        coEvery { watchHistoryDao.getLatestByTmdbId(any(), any(), any()) } returns testEntity
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `continue watching returns items with progress`() = testScope.runTest {
        val result = watchHistoryDao.getContinueWatching("profile-1").first()

        assertThat(result).hasSize(1)
        assertThat(result[0].progress).isEqualTo(50)
        assertThat(result[0].title).isEqualTo("Test Movie")
    }

    @Test
    fun `upsert updates existing entry`() = testScope.runTest {
        coEvery { watchHistoryDao.upsert(any()) } returns Unit

        val updated = testEntity.copy(progress = 75, positionSeconds = 6750L)
        watchHistoryDao.upsert(updated)

        coVerify { watchHistoryDao.upsert(match { it.progress == 75 }) }
    }

    @Test
    fun `delete removes entry by content key`() = testScope.runTest {
        coEvery { watchHistoryDao.delete(any(), any(), any()) } returns Unit

        watchHistoryDao.delete("profile-1", "movie", 12345)

        coVerify { watchHistoryDao.delete("profile-1", "movie", 12345) }
    }

    @Test
    fun `clearForProfile removes all entries for profile`() = testScope.runTest {
        coEvery { watchHistoryDao.clearForProfile(any()) } returns Unit

        watchHistoryDao.clearForProfile("profile-1")

        coVerify { watchHistoryDao.clearForProfile("profile-1") }
    }

    @Test
    fun `entity fields map correctly`() {
        assertThat(testEntity.profileId).isEqualTo("profile-1")
        assertThat(testEntity.mediaType).isEqualTo("movie")
        assertThat(testEntity.tmdbId).isEqualTo(12345)
        assertThat(testEntity.progress).isEqualTo(50)
        assertThat(testEntity.positionSeconds).isEqualTo(4500L)
        assertThat(testEntity.durationSeconds).isEqualTo(9000L)
    }
}
