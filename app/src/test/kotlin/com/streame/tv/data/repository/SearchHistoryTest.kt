package com.streame.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.streame.tv.data.local.SearchHistoryDao
import com.streame.tv.data.local.SearchHistoryEntity
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
class SearchHistoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val searchHistoryDao: SearchHistoryDao = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { searchHistoryDao.getRecent(any(), any()) } returns listOf(
            SearchHistoryEntity(id = 1, profileId = "p1", query = "Breaking Bad", searchedAt = 1000),
            SearchHistoryEntity(id = 2, profileId = "p1", query = "Stranger Things", searchedAt = 2000)
        )
        coEvery { searchHistoryDao.getRecentFlow(any(), any()) } returns flowOf(
            listOf(
                SearchHistoryEntity(id = 1, profileId = "p1", query = "Breaking Bad", searchedAt = 1000),
                SearchHistoryEntity(id = 2, profileId = "p1", query = "Stranger Things", searchedAt = 2000)
            )
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getRecent returns queries ordered by time`() = testScope.runTest {
        val results = searchHistoryDao.getRecent("p1", 20)

        assertThat(results).hasSize(2)
        assertThat(results[0].query).isEqualTo("Breaking Bad")
        assertThat(results[1].query).isEqualTo("Stranger Things")
    }

    @Test
    fun `getRecentFlow emits search history`() = testScope.runTest {
        val flow = searchHistoryDao.getRecentFlow("p1", 20)

        flow.collect { results ->
            assertThat(results).hasSize(2)
        }
    }

    @Test
    fun `insert adds new search entry`() = testScope.runTest {
        coEvery { searchHistoryDao.insert(any()) } returns 3L

        val entry = SearchHistoryEntity(profileId = "p1", query = "The Office", searchedAt = System.currentTimeMillis())
        val id = searchHistoryDao.insert(entry)

        coVerify { searchHistoryDao.insert(match { it.query == "The Office" }) }
        assertThat(id).isEqualTo(3L)
    }

    @Test
    fun `delete removes specific query`() = testScope.runTest {
        coEvery { searchHistoryDao.delete(any(), any()) } returns Unit

        searchHistoryDao.delete("p1", "Breaking Bad")

        coVerify { searchHistoryDao.delete("p1", "Breaking Bad") }
    }

    @Test
    fun `clearForProfile removes all entries`() = testScope.runTest {
        coEvery { searchHistoryDao.clearForProfile(any()) } returns Unit

        searchHistoryDao.clearForProfile("p1")

        coVerify { searchHistoryDao.clearForProfile("p1") }
    }

    @Test
    fun `deleteOlderThan prunes stale entries`() = testScope.runTest {
        coEvery { searchHistoryDao.deleteOlderThan(any()) } returns Unit

        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 // 30 days
        searchHistoryDao.deleteOlderThan(cutoff)

        coVerify { searchHistoryDao.deleteOlderThan(cutoff) }
    }
}
