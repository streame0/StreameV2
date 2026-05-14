# ============================================
# STREAME ProGuard/R8 Rules
# Production-ready optimization rules
# ============================================

# ============================================
# General Android optimizations
# ============================================
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# Keep source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================
# Log stripping for release builds
# Remove ALL logs for maximum performance
# ============================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Strip verbose/debug/info AppLogger methods in release.
# Keep w() and e() — they forward to the crash reporter (Sentry/Crashlytics).
-assumenosideeffects class com.streame.tv.util.AppLogger {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}

# Strip Kotlin debug assertions in release
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
}

# ============================================
# Kotlin specific rules
# ============================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ============================================
# Retrofit / OkHttp
# ============================================
-keep,allowobfuscation,allowoptimization interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp platform used only on JVM and when Conscrypt dependency is available
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================
# Gson serialization
# ============================================
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.streame.tv.data.model.** { *; }
-keep class com.streame.tv.data.api.** { *; }

# Keep generic type information for Gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Prevent R8 from removing fields used by Gson reflection
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep enum field names for Gson (used in Trakt outbox persistence)
-keepclassmembers enum com.streame.tv.data.repository.TraktOutboxAction { *; }

# ============================================
# ExoPlayer / Media3
# ============================================
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# FFmpeg decoder extension
-keep class org.jellyfin.media3.** { *; }
-dontwarn org.jellyfin.media3.**

# ============================================
# Hilt / Dagger
# ============================================
# Keep Hilt entry points and injected classes
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.internal.** { *; }
-keep class dagger.hilt.android.** { *; }
-keep class dagger.hilt.android.internal.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep interface hilt_aggregated_deps.** { *; }

# Keep classes with @Inject constructors (Hilt needs these at runtime)
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# Keep Hilt modules and entry points
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.EntryPoint class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep Hilt Worker subclasses
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep @dagger.hilt.android.lifecycle.HiltWorker class * { *; }

# Suppress warnings for Hilt generated classes
-dontwarn com.streame.tv.**_GeneratedInjector
-dontwarn dagger.hilt.internal.aggregatedroot.codegen.**
-dontwarn hilt_aggregated_deps.**

# ============================================
# Jetpack Compose
# ============================================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep composable functions for proper rendering
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ============================================
# AndroidX / Lifecycle
# ============================================
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ============================================
# Supabase / Kotlin Serialization
# ============================================
# Keep Supabase models (used by Kotlin Serialization reflection)
-keep class com.streame.tv.data.remote.supabase.** { *; }
-keepclassmembers class com.streame.tv.data.remote.supabase.** {
    <fields>;
}

# ============================================
# Room entities — keep fields for reflection-based mapping
# ============================================
-keep class com.streame.tv.data.local.HomeRowEntity { *; }
-keep class com.streame.tv.data.local.CatalogConfigEntity { *; }
-keep class com.streame.tv.data.local.WatchHistoryEntity { *; }
-keep class com.streame.tv.data.local.SyncQueueEntity { *; }
-keep class com.streame.tv.data.local.WatchlistEntity { *; }
-keep class com.streame.tv.data.local.DownloadEntity { *; }
-keep class com.streame.tv.data.local.SearchHistoryEntity { *; }
-keep class com.streame.tv.data.local.ProfileEntity { *; }

# ============================================
# App sealed classes and state classes
# ============================================
-keep class com.streame.tv.data.repository.AuthState { *; }
-keep class com.streame.tv.data.repository.AuthState$* { *; }
-keep class com.streame.tv.data.repository.SupabaseAuthState { *; }
-keep class com.streame.tv.data.repository.SupabaseAuthState$* { *; }
-keep class com.streame.tv.data.sync.CloudSyncScope { *; }
-keep class com.streame.tv.data.sync.CloudSyncStatus { *; }
-keep class com.streame.tv.data.model.MediaType { *; }
-keep class com.streame.tv.util.DeviceType { *; }

# ============================================
# Firebase Crashlytics
# ============================================
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }

# ============================================
# Coil image loading
# ============================================
-keep class coil.** { *; }
-dontwarn coil.**

# ============================================
# Warnings to suppress
# ============================================
-dontwarn org.slf4j.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**

# Retrofit needs generic signatures on service methods such as
# Response<List<TraktWatchlistItem>>. Keep these after all other attribute
# rules so release minification cannot strip them and break live Trakt sync.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable

# ============================================
# App-specific keeps
# ============================================
# Keep app exception classes for crash reporting
-keep class com.streame.tv.util.AppException { *; }
-keep class com.streame.tv.util.AppException$* { *; }

# Keep sealed classes for proper when() handling
-keep class com.streame.tv.util.Result { *; }
-keep class com.streame.tv.util.Result$* { *; }
-keep class com.streame.tv.util.UiState { *; }
-keep class com.streame.tv.util.UiState$* { *; }
