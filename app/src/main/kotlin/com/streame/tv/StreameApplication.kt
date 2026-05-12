package com.streame.tv

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Build
import java.security.Security
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toPath
import coil3.request.*
import com.streame.tv.network.OkHttpProvider
import com.streame.tv.data.repository.AuthRepository
import com.streame.tv.data.repository.AuthState
import com.streame.tv.data.repository.WatchlistRepository
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.util.AppLogger
import com.streame.tv.util.CrashlyticsProvider
import com.streame.tv.util.DeviceType
import com.streame.tv.util.SentryCrashReporter
import com.streame.tv.util.detectDeviceType
import com.streame.tv.worker.TraktSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.streame.tv.util.settingsDataStore
import java.util.concurrent.TimeUnit
import org.conscrypt.Conscrypt
import javax.inject.Inject

/**
 * Streame TV Application class
 */
@HiltAndroidApp
class StreameApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("AppScope", "Unhandled coroutine exception", throwable)
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)
    @Volatile
    private var appImageLoader: ImageLoader? = null

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    @Inject
    lateinit var profileManager: ProfileManager
    @Inject
    lateinit var authRepository: AuthRepository
    @Inject
    lateinit var watchlistRepository: WatchlistRepository
    @Inject
    lateinit var watchHistoryRepository: com.streame.tv.data.repository.WatchHistoryRepository
    @Inject
    lateinit var startupSyncService: com.streame.tv.data.sync.StartupSyncService
    @Inject
    lateinit var realtimeSyncManager: com.streame.tv.data.sync.RealtimeSyncManager
    @Inject
    lateinit var cloudSyncCoordinator: com.streame.tv.data.sync.CloudSyncCoordinator

    override fun onCreate() {
        super.onCreate()
        instance = this

        runCatching {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        }

        // StrictMode in debug builds - disabled for now as it generates too many
        // logs from existing code (SharedPreferences reads during startup).
        // Enable if needed for debugging specific issues.
        // if (BuildConfig.DEBUG) {
        //     StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
        //         .detectDiskReads()
        //         .detectDiskWrites()
        //         .detectNetwork()
        //         .penaltyLog()
        //         .build())
        //     StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
        //         .detectLeakedSqlLiteObjects()
        //         .detectLeakedClosableObjects()
        //         .penaltyLog()
        //         .build())
        // }

        // Global safety net: catch any unhandled exception from coroutines or
        // Compose that would otherwise crash the process.
        //
        // Strategy:
        // 1. Always log and report to the crash reporter (Sentry/Crashlytics).
        // 2. For truly fatal errors (OOM, StackOverflow, native crashes),
        //    forward to the default handler — these indicate the app is in a
        //    broken state and continuing would be worse than restarting.
        // 3. For non-fatal errors (Compose rendering glitches, coroutine
        //    cancellations), swallow them — a momentary UI glitch is better
        //    than a hard crash on TV (no easy way to restart).
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("Uncaught", "Unhandled exception on ${thread.name}", throwable)

            val cause = throwable.cause
            val isFatal = throwable is OutOfMemoryError
                    || throwable is StackOverflowError
                    || throwable is ThreadDeath
                    || throwable is VirtualMachineError
                    || (cause != null && isFatalError(cause))

            if (isFatal) {
                // Fatal errors — the process is in an unrecoverable state.
                // Forward to the default handler so the system can restart the app.
                defaultHandler?.uncaughtException(thread, throwable)
            }
            // Non-fatal errors are swallowed intentionally on TV:
            // a brief glitch is preferable to a full app restart.
        }

        // OkHttpProvider.init(context) just stashes the app context; it does
        // not build the OkHttpClient. Safe to keep on the main thread — it's
        // a single volatile assignment.
        OkHttpProvider.init(this)

        // Defer both the OkHttpClient lazy build AND the CloudStream HTTP
        // bridge to IO. Accessing OkHttpProvider.client triggers a ~tens-of-ms
        // disk-cache probe (File(cacheDir, "http_cache")); keeping that off
        // the main thread trims cold-start jank on first frame. Plugins loaded
        // later acquire the client through the same lazy path, so ordering is
        // preserved without blocking here.
        //
        // Initialize global DNS provider from DataStore before network calls
        appScope.launch(Dispatchers.IO) {
            val prefs = settingsDataStore.data.first()
            val dnsKey = androidx.datastore.preferences.core.stringPreferencesKey(OkHttpProvider.DNS_PROVIDER_PREF_KEY)
            val dnsPref = prefs[dnsKey]
            val provider = OkHttpProvider.parseDnsProvider(dnsPref)
            OkHttpProvider.setDnsProvider(provider)

            runCatching {
                com.streame.tv.cloudstream.initCloudstream(OkHttpProvider.client)
            }
            runCatching { OkHttpProvider.dns.lookup("image.tmdb.org") }
        }

        // Initialize crash reporting. Sentry is preferred when SENTRY_DSN is configured;
        // Crashlytics remains as a fallback for builds with Firebase configuration.
        if (!SentryCrashReporter.initialize(this)) {
            CrashlyticsProvider.initialize()
        }
        // Initialize active profile asynchronously to avoid blocking cold start.
        // All independent startup tasks run in parallel for faster cold start.
        appScope.launch {
            // Profile init must complete first — other tasks depend on it
            runCatching { profileManager.initialize() }

            // After profile is ready, launch all independent tasks in parallel
            kotlinx.coroutines.coroutineScope {
                launch { runCatching { watchlistRepository.getWatchlistItems() } }
                launch { runCatching { watchHistoryRepository.loadFromRoom() } }
                launch { runCatching { startupSyncService.pullAllData() } }
                launch {
                    runCatching {
                        cloudSyncCoordinator.start()
                        realtimeSyncManager.start()
                    }
                }
            }
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val isTvDevice = detectDeviceType(this) == DeviceType.TV
        val isLowRamDevice = isLowRamDevice()
        return ImageLoader.Builder(this)
            // Use the dedicated Coil HTTP client instead of the main API client.
            // Avoids logging interceptor overhead and connection pool contention.
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpProvider.coilClient }))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    // Increased memory budgets: more in-memory bitmaps = fewer
                    // disk reads and network fetches during scrolling.
                    // Low-RAM TVs stay conservative to avoid zram pressure.
                    .maxSizeBytes(
                        when {
                            isTvDevice && isLowRamDevice -> 32 * 1024 * 1024
                            isTvDevice -> 80 * 1024 * 1024
                            else -> 96 * 1024 * 1024
                        }
                    )
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").absolutePath.toPath())
                    // Increased disk cache to avoid evictions between sessions.
                    // A typical home screen touches 30-50MB per session.
                    .maxSizeBytes(
                        when {
                            isTvDevice && isLowRamDevice -> OkHttpProvider.IMAGE_DISK_CACHE_SIZE_LOW_RAM
                            isTvDevice -> OkHttpProvider.IMAGE_DISK_CACHE_SIZE_TV
                            else -> OkHttpProvider.IMAGE_DISK_CACHE_SIZE_MOBILE
                        }
                    )
                    .build()
            }
            // No global placeholder — card composables use their own surface
            // background color as the visual placeholder. A global placeholder
            // causes a dark-rectangle flash behind transparent clearlogo PNGs
            // on the home hero. Error = transparent so failed loads are invisible
            // (the card surface background is the fallback visual).
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
            .also { appImageLoader = it }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            appImageLoader?.memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        appImageLoader?.memoryCache?.clear()
    }

    private fun isLowRamDevice(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.isLowRamDevice == true
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.ASSERT)
            .build()

    /**
     * Schedule periodic Trakt data sync
     */
    fun scheduleTraktSyncIfNeeded() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Use INCREMENTAL sync on startup for fast app launch
        // Full sync only happens on periodic schedule or explicit user action
        val oneTimeRequest = OneTimeWorkRequestBuilder<TraktSyncWorker>()
            .setConstraints(constraints)
            // Defer startup sync to keep first-run navigation and scrolling smooth.
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(TraktSyncWorker.INPUT_SYNC_MODE to TraktSyncWorker.SYNC_MODE_INCREMENTAL)
            )
            .addTag(TraktSyncWorker.TAG)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<TraktSyncWorker>(
            TraktSyncWorker.SYNC_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .addTag(TraktSyncWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            TraktSyncWorker.WORK_NAME_ON_OPEN,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TraktSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    companion object {
        lateinit var instance: StreameApplication
            private set

        /** Recursively checks if a throwable chain contains a fatal error type. */
        private tailrec fun isFatalError(throwable: Throwable, depth: Int = 0): Boolean {
            if (depth > 5) return false // guard against infinite cause loops
            return when (throwable) {
                is OutOfMemoryError, is StackOverflowError, is ThreadDeath, is VirtualMachineError -> true
                else -> {
                    val cause = throwable.cause
                    cause != null && cause !== throwable && isFatalError(cause, depth + 1)
                }
            }
        }
    }
}




