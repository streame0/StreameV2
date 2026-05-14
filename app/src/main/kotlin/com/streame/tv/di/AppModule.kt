package com.streame.tv.di

import android.content.Context
import com.streame.tv.data.api.AniSkipApi
import com.streame.tv.data.local.AppDatabase
import com.streame.tv.data.local.DownloadDao
import com.streame.tv.data.local.HomeRowDao
import com.streame.tv.data.local.ProfileDao
import com.streame.tv.data.local.SearchHistoryDao
import com.streame.tv.data.local.WatchHistoryDao
import com.streame.tv.data.local.WatchlistDao
import com.streame.tv.data.local.LocalHomeRepository
import com.streame.tv.data.api.ArmApi
import com.streame.tv.data.api.IntroDbApi
import com.streame.tv.data.api.StreamApi
import com.streame.tv.data.api.TmdbApi
import com.streame.tv.data.api.TraktApi
import com.streame.tv.network.OkHttpProvider
import com.streame.tv.util.Constants
import com.streame.tv.di.DispatcherProvider
import com.streame.tv.di.AppDispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpProvider.client
    }

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider {
        return AppDispatcherProvider()
    }

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbOkHttpClient(): OkHttpClient {
        return OkHttpProvider.client.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.newBuilder()
                    .addQueryParameter("api_key", Constants.TMDB_API_KEY)
                    .build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            })
            .build()
    }

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktOkHttpClient(): OkHttpClient {
        return OkHttpProvider.client.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("trakt-api-key", Constants.TRAKT_CLIENT_ID)
                    .header("trakt-api-version", "2")
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideTmdbApi(@Named("tmdb") okHttpClient: OkHttpClient): TmdbApi {
        return Retrofit.Builder()
            .baseUrl(Constants.TMDB_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideTraktApi(@Named("trakt") okHttpClient: OkHttpClient): TraktApi {
        return Retrofit.Builder()
            .baseUrl(Constants.TRAKT_API_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TraktApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideStreamApi(okHttpClient: OkHttpClient): StreamApi {
        // Base URL doesn't matter for dynamic URLs
        return Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StreamApi::class.java)
    }

    // Skip intro providers (IntroDB + AniSkip + ARM).

    @Provides
    @Singleton
    @Named("introDb")
    fun provideIntroDbRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.introdb.app/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideIntroDbApi(@Named("introDb") retrofit: Retrofit): IntroDbApi {
        return retrofit.create(IntroDbApi::class.java)
    }

    @Provides
    @Singleton
    @Named("aniSkip")
    fun provideAniSkipRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.aniskip.com/v2/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAniSkipApi(@Named("aniSkip") retrofit: Retrofit): AniSkipApi {
        return retrofit.create(AniSkipApi::class.java)
    }

    @Provides
    @Singleton
    @Named("arm")
    fun provideArmRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://arm.haglund.dev/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideArmApi(@Named("arm") retrofit: Retrofit): ArmApi {
        return retrofit.create(ArmApi::class.java)
    }

    @Provides
    @Singleton
    @Named("jikan")
    fun provideJikanRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.jikan.moe/v4/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideJikanApi(@Named("jikan") retrofit: Retrofit): com.streame.tv.data.api.JikanApi {
        return retrofit.create(com.streame.tv.data.api.JikanApi::class.java)
    }

    // CloudstreamProviderRuntime is @Singleton @Inject — Hilt constructs it
    // directly, no @Provides needed.

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideHomeRowDao(database: AppDatabase): HomeRowDao {
        return database.homeRowDao()
    }

    @Provides
    @Singleton
    fun provideWatchHistoryDao(database: AppDatabase): WatchHistoryDao {
        return database.watchHistoryDao()
    }

    @Provides
    @Singleton
    fun provideWatchlistDao(database: AppDatabase): WatchlistDao {
        return database.watchlistDao()
    }

    @Provides
    @Singleton
    fun provideDownloadDao(database: AppDatabase): DownloadDao {
        return database.downloadDao()
    }

    @Provides
    @Singleton
    fun provideSearchHistoryDao(database: AppDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }

    @Provides
    @Singleton
    fun provideProfileDao(database: AppDatabase): ProfileDao {
        return database.profileDao()
    }

    @Provides
    @Singleton
    fun provideLocalHomeRepository(
        homeRowDao: HomeRowDao,
        tmdbApi: TmdbApi
    ): LocalHomeRepository {
        return LocalHomeRepository(homeRowDao, tmdbApi)
    }

}
