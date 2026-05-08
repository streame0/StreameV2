package com.streame.tv.di

import com.streame.tv.data.api.TmdbApi
import com.streame.tv.data.repository.MediaRepository
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.data.repository.ProfileRepository
import com.streame.tv.data.repository.StreamRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositoryAccessEntryPoint {
    fun streamRepository(): StreamRepository
    fun mediaRepository(): MediaRepository
    fun profileRepository(): ProfileRepository
    fun profileManager(): ProfileManager
    fun tmdbApi(): TmdbApi
}
