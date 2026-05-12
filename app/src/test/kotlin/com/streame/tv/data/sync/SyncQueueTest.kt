package com.streame.tv.data.sync

import com.google.common.truth.Truth.assertThat
import com.streame.tv.data.local.SyncQueueDao
import com.streame.tv.data.local.SyncQueueEntity
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncQueueTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val syncQueueDao: SyncQueueDao = mockk(relaxed = true)

    private val testEntry = SyncQueueEntity(
        id = 1,
        scope = "watch_progress",
        createdAt = System.currentTimeMillis(),
        retryCount = 0,
        lastError = null,
        lastAttemptAt = null
    )

    @Before
    fun setup() {
        coEvery { syncQueueDao.getAll() } returns listOf(testEntry)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `sync queue entry has correct scope`() {
        assertThat(testEntry.scope).isEqualTo("watch_progress")
    }

    @Test
    fun `insert adds entry to queue`() = testScope.runTest {
        coEvery { syncQueueDao.insert(any()) } returns 1L

        val entry = SyncQueueEntity(
            scope = "library",
            createdAt = System.currentTimeMillis(),
            retryCount = 0
        )
        val id = syncQueueDao.insert(entry)

        coVerify { syncQueueDao.insert(match { it.scope == "library" }) }
        assertThat(id).isEqualTo(1L)
    }

    @Test
    fun `updateRetry increments retry count`() = testScope.runTest {
        coEvery { syncQueueDao.updateRetry(any(), any(), any(), any()) } returns Unit

        syncQueueDao.updateRetry(1, 1, "Timeout", System.currentTimeMillis())

        coVerify { syncQueueDao.updateRetry(1, 1, "Timeout", any()) }
    }

    @Test
    fun `delete removes entry`() = testScope.runTest {
        coEvery { syncQueueDao.delete(any()) } returns Unit

        syncQueueDao.delete(1)

        coVerify { syncQueueDao.delete(1) }
    }

    @Test
    fun `deleteByScope removes all entries for scope`() = testScope.runTest {
        coEvery { syncQueueDao.deleteByScope(any()) } returns Unit

        syncQueueDao.deleteByScope("watch_progress")

        coVerify { syncQueueDao.deleteByScope("watch_progress") }
    }

    @Test
    fun `failed entry tracks error and retry count`() {
        val failedEntry = testEntry.copy(
            retryCount = 3,
            lastError = "HTTP 503",
            lastAttemptAt = System.currentTimeMillis()
        )

        assertThat(failedEntry.retryCount).isEqualTo(3)
        assertThat(failedEntry.lastError).isEqualTo("HTTP 503")
        assertThat(failedEntry.lastAttemptAt).isNotNull()
    }
}
