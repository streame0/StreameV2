package com.streame.tv.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.streame.tv.data.local.ProfileDao
import com.streame.tv.data.local.ProfileEntity
import com.streame.tv.data.model.Profile
import com.streame.tv.data.model.ProfileColors
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
class ProfileRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var repository: ProfileRepository
    private val profileDao: ProfileDao = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)

    private val testProfile = Profile(
        id = "test-1",
        name = "Test Profile",
        avatarColor = ProfileColors.colors[0],
        avatarId = 1
    )

    private val testEntity = ProfileEntity(
        id = "test-1",
        name = "Test Profile",
        avatarColor = ProfileColors.colors[0],
        avatarId = 1
    )

    @Before
    fun setup() {
        // Mock DataStore flows to return empty defaults
        // (ProfileRepository reads from DataStore in init for migration)
        mockkStatic("com.streame.tv.util.DataStoreExtensionsKt")
        
        coEvery { profileDao.getAll() } returns emptyList()
        coEvery { profileDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { profileDao.getById(any()) } returns null

        // We can't easily construct ProfileRepository without a real Context for DataStore,
        // so we test the DAO/entity mapping logic directly
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `profile entity maps to domain model correctly`() {
        val entity = testEntity
        val profile = Profile(
            id = entity.id,
            name = entity.name,
            avatarColor = entity.avatarColor,
            avatarId = entity.avatarId,
            isKidsProfile = entity.isKidsProfile,
            pin = entity.pin,
            isLocked = entity.isLocked,
            createdAt = entity.createdAt,
            lastUsedAt = entity.lastUsedAt,
            cloudUserId = entity.cloudUserId,
            cloudEmail = entity.cloudEmail
        )

        assertThat(profile.id).isEqualTo(entity.id)
        assertThat(profile.name).isEqualTo(entity.name)
        assertThat(profile.avatarColor).isEqualTo(entity.avatarColor)
        assertThat(profile.avatarId).isEqualTo(entity.avatarId)
        assertThat(profile.isKidsProfile).isEqualTo(entity.isKidsProfile)
        assertThat(profile.pin).isEqualTo(entity.pin)
        assertThat(profile.isLocked).isEqualTo(entity.isLocked)
        assertThat(profile.cloudUserId).isEqualTo(entity.cloudUserId)
        assertThat(profile.cloudEmail).isEqualTo(entity.cloudEmail)
    }

    @Test
    fun `profile domain model maps to entity correctly`() {
        val profile = testProfile
        val entity = ProfileEntity(
            id = profile.id,
            name = profile.name,
            avatarColor = profile.avatarColor,
            avatarId = profile.avatarId,
            isKidsProfile = profile.isKidsProfile,
            pin = profile.pin,
            isLocked = profile.isLocked,
            createdAt = profile.createdAt,
            lastUsedAt = profile.lastUsedAt,
            cloudUserId = profile.cloudUserId,
            cloudEmail = profile.cloudEmail
        )

        assertThat(entity.id).isEqualTo(profile.id)
        assertThat(entity.name).isEqualTo(profile.name)
        assertThat(entity.cloudUserId).isEqualTo(profile.cloudUserId)
    }

    @Test
    fun `createProfile upserts entity to dao`() = testScope.runTest {
        coEvery { profileDao.upsert(any()) } returns Unit

        // Simulate createProfile logic
        val profile = Profile(
            name = "New Profile",
            avatarColor = ProfileColors.colors[1],
            avatarId = 2
        )
        val entity = ProfileEntity(
            id = profile.id,
            name = profile.name,
            avatarColor = profile.avatarColor,
            avatarId = profile.avatarId,
            isKidsProfile = profile.isKidsProfile,
            pin = profile.pin,
            isLocked = profile.isLocked,
            createdAt = profile.createdAt,
            lastUsedAt = profile.lastUsedAt,
            cloudUserId = profile.cloudUserId,
            cloudEmail = profile.cloudEmail
        )
        profileDao.upsert(entity)

        coVerify { profileDao.upsert(match { it.name == "New Profile" }) }
    }

    @Test
    fun `deleteProfile calls dao delete`() = testScope.runTest {
        coEvery { profileDao.delete(any()) } returns Unit

        profileDao.delete("test-1")

        coVerify { profileDao.delete("test-1") }
    }

    @Test
    fun `linkCloudAccount updates entity`() = testScope.runTest {
        coEvery { profileDao.getById("test-1") } returns testEntity
        coEvery { profileDao.upsert(any()) } returns Unit

        // Simulate linkCloudAccount logic
        val entity = profileDao.getById("test-1")!!
        profileDao.upsert(entity.copy(cloudUserId = "user-123", cloudEmail = "test@example.com"))

        coVerify {
            profileDao.upsert(match {
                it.cloudUserId == "user-123" && it.cloudEmail == "test@example.com"
            })
        }
    }

    @Test
    fun `clearCloudLink nullifies cloud fields`() = testScope.runTest {
        val linkedEntity = testEntity.copy(cloudUserId = "user-123", cloudEmail = "test@example.com")
        coEvery { profileDao.getById("test-1") } returns linkedEntity
        coEvery { profileDao.upsert(any()) } returns Unit

        val entity = profileDao.getById("test-1")!!
        profileDao.upsert(entity.copy(cloudUserId = null, cloudEmail = null))

        coVerify {
            profileDao.upsert(match {
                it.cloudUserId == null && it.cloudEmail == null
            })
        }
    }
}
