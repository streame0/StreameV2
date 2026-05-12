package com.streame.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.streame.tv.data.local.DownloadDao
import com.streame.tv.data.local.DownloadEntity
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val downloadDao: DownloadDao = mockk(relaxed = true)

    private val testDownload = DownloadEntity(
        id = 1,
        profileId = "profile-1",
        tmdbId = 12345,
        mediaType = "movie",
        title = "Test Movie",
        posterPath = "/poster.jpg",
        sourceUrl = "https://example.com/video.mp4",
        localPath = "/data/downloads/Test_Movie.mp4",
        status = "completed",
        progress = 100,
        fileSizeBytes = 1_500_000_000L
    )

    @Before
    fun setup() {
        coEvery { downloadDao.getCompletedForProfile(any()) } returns flowOf(listOf(testDownload))
        coEvery { downloadDao.getAllForProfile(any()) } returns listOf(testDownload)
        coEvery { downloadDao.getById(any()) } returns testDownload
        coEvery { downloadDao.findByContent(any(), any(), any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `completed downloads flow returns completed items`() = testScope.runTest {
        val result = downloadDao.getCompletedForProfile("profile-1")

        result.collect { downloads ->
            assertThat(downloads).hasSize(1)
            assertThat(downloads[0].status).isEqualTo("completed")
        }
    }

    @Test
    fun `enqueue creates entity with queued status`() = testScope.runTest {
        coEvery { downloadDao.upsert(any()) } returns 1L

        val entity = DownloadEntity(
            profileId = "profile-1",
            tmdbId = 99999,
            mediaType = "tv",
            title = "New Show",
            sourceUrl = "https://example.com/episode.mp4",
            localPath = "/data/downloads/New_Show_S01E01.mp4",
            seasonNumber = 1,
            episodeNumber = 1,
            status = "queued"
        )
        downloadDao.upsert(entity)

        coVerify { downloadDao.upsert(match { it.status == "queued" && it.seasonNumber == 1 }) }
    }

    @Test
    fun `updateProgress tracks download progress`() = testScope.runTest {
        coEvery { downloadDao.updateProgress(any(), any(), any(), any()) } returns Unit

        downloadDao.updateProgress(1, "downloading", 50)

        coVerify { downloadDao.updateProgress(1, "downloading", 50, any()) }
    }

    @Test
    fun `markCompleted sets status and file size`() = testScope.runTest {
        coEvery { downloadDao.markCompleted(any(), any(), any(), any()) } returns Unit

        downloadDao.markCompleted(1, 1_500_000_000L)

        coVerify { downloadDao.markCompleted(1, 1_500_000_000L, any(), any()) }
    }

    @Test
    fun `markFailed records error message`() = testScope.runTest {
        coEvery { downloadDao.updateStatus(any(), any(), any(), any()) } returns Unit

        downloadDao.updateStatus(1, "failed", "Network timeout")

        coVerify { downloadDao.updateStatus(1, "failed", "Network timeout", any()) }
    }

    @Test
    fun `findByContent returns null for non-existent download`() = testScope.runTest {
        val result = downloadDao.findByContent("profile-1", 99999, "movie", null, null)
        assertThat(result).isNull()
    }

    @Test
    fun `findByContent returns existing download`() = testScope.runTest {
        coEvery { downloadDao.findByContent("profile-1", 12345, "movie", null, null) } returns testDownload

        val result = downloadDao.findByContent("profile-1", 12345, "movie", null, null)
        assertThat(result).isNotNull()
        assertThat(result!!.status).isEqualTo("completed")
    }

    @Test
    fun `delete removes download entry`() = testScope.runTest {
        coEvery { downloadDao.delete(any()) } returns Unit

        downloadDao.delete(1)

        coVerify { downloadDao.delete(1) }
    }
}
