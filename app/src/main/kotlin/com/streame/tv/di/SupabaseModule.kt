package com.streame.tv.di

import com.streame.tv.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import com.russhwolf.settings.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseSettings(@ApplicationContext context: Context): Settings =
        com.russhwolf.settings.SharedPreferencesSettings(context.getSharedPreferences("supabase-session", Context.MODE_PRIVATE))

    @Provides
    @Singleton
    fun provideSupabaseClient(settings: Settings): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
                enableLifecycleCallbacks = false
                sessionManager = io.github.jan.supabase.auth.SettingsSessionManager(settings)
                codeVerifierCache = io.github.jan.supabase.auth.SettingsCodeVerifierCache(settings)
            }
            install(Postgrest)
        }
    }

    @Provides
    @Singleton
    fun provideAuth(client: SupabaseClient): Auth = client.auth

    @Provides
    @Singleton
    fun providePostgrest(client: SupabaseClient): Postgrest = client.postgrest
}
