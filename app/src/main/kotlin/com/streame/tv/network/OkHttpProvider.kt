package com.streame.tv.network

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.streame.tv.BuildConfig
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Provides a configured OkHttpClient instance.
 *
 * TMDB calls go direct with the hardcoded API key.
 * Trakt calls go direct with the hardcoded Client ID.
 *
 * SSL/TLS validation is handled by NetworkSecurityConfig (res/xml/network_security_config.xml):
 * - Release: System certificates only (secure)
 * - Debug: User + System certificates (allows proxy debugging)
 *
 * DO NOT add custom TrustManager - it defeats certificate validation.
 */
object OkHttpProvider {
    private const val TAG = "AppDns"
    /** 50 MB disk cache for API responses (TMDB metadata, Trakt data, etc.) */
    private const val HTTP_CACHE_SIZE = 50L * 1024L * 1024L
    private const val IMAGE_DISK_CACHE_SIZE = 48L * 1024L * 1024L
    private const val CLOUDFLARE_DOH_HOST = "cloudflare-dns.com"
    private const val CLOUDFLARE_DOH_URL = "https://cloudflare-dns.com/dns-query"
    private const val GOOGLE_DOH_HOST = "dns.google"
    private const val GOOGLE_DOH_URL = "https://dns.google/dns-query"
    private const val ADGUARD_DOH_HOST = "dns.adguard-dns.com"
    private const val ADGUARD_DOH_URL = "https://dns.adguard-dns.com/dns-query"

    const val DNS_PROVIDER_PREF_KEY = "dns_provider_global"

    enum class AppDnsProvider {
        SYSTEM,
        CLOUDFLARE,
        GOOGLE,
        ADGUARD
    }

    /**
     * Must be called once from Application.onCreate() before any network calls.
     * Provides the application context needed for the disk cache directory.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var selectedDnsProvider: AppDnsProvider = AppDnsProvider.SYSTEM

    private val dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
        Log.e("OkHttpProvider", "Unhandled coroutine exception", t)
    })
    private val clientLock = Any()

    private val appConnectionPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    @Volatile
    private var appClient: OkHttpClient? = null

    @Volatile
    private var appHttpCache: Cache? = null

    @Volatile
    private var coilSharedClient: OkHttpClient? = null

    private val systemDns: Dns by lazy {
        preferIpv4ForTmdb(Dns.SYSTEM)
    }

    private val cloudflareDns: Dns by lazy {
        preferIpv4ForTmdb(
            buildDohDns(
                url = CLOUDFLARE_DOH_URL,
                dohHost = CLOUDFLARE_DOH_HOST,
                bootstrapHosts = cloudflareBootstrapHosts
            )
        )
    }

    private val googleDns: Dns by lazy {
        preferIpv4ForTmdb(
            buildDohDns(
                url = GOOGLE_DOH_URL,
                dohHost = GOOGLE_DOH_HOST,
                bootstrapHosts = googleBootstrapHosts
            )
        )
    }

    private val adguardDns: Dns by lazy {
        preferIpv4ForTmdb(
            buildDohDns(
                url = ADGUARD_DOH_URL,
                dohHost = ADGUARD_DOH_HOST,
                bootstrapHosts = adguardBootstrapHosts
            )
        )
    }

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return selectedDns(selectedDnsProvider).lookup(hostname)
        }
    }

    private fun selectedDns(provider: AppDnsProvider): Dns {
        return when (provider) {
            AppDnsProvider.SYSTEM -> systemDns
            AppDnsProvider.CLOUDFLARE -> cloudflareDns
            AppDnsProvider.GOOGLE -> googleDns
            AppDnsProvider.ADGUARD -> adguardDns
        }
    }

    val client: OkHttpClient
        get() = appClient ?: synchronized(clientLock) {
            appClient ?: buildAppClient().also { appClient = it }
        }

    /** Logging interceptor: logs request host + HTTP status (or exception) for every call.
     *  Always active so release builds can be diagnosed via Logcat. */
    private val appLoggingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val method = request.method
        try {
            val response = chain.proceed(request)
            Log.i(TAG, "HTTP $method $host -> ${response.code}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "HTTP $method $host FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** Interceptor that rewrites TMDB API response headers to ensure they are
     *  cacheable for at least 10 minutes. TMDB sometimes returns short or
     *  no-cache headers on certain endpoints, which defeats OkHttp's disk cache
     *  and forces a full network round-trip on every app launch. By forcing a
     *  minimum max-age, repeat visits to the same screen are instant. */
    private val tmdbCacheInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val host = request.url.host
        val isTmdbApi = host.contains("api.themoviedb", ignoreCase = true)
                || host.contains("api.tmdb", ignoreCase = true)
        if (isTmdbApi && request.method == "GET" && response.isSuccessful) {
            val existingMaxAge = response.cacheControl.maxAgeSeconds
            if (existingMaxAge < 600) { // less than 10 minutes
                val newHeaders = response.headers.newBuilder()
                    .set("Cache-Control", "public, max-age=600")
                    .build()
                response.newBuilder()
                    .headers(newHeaders)
                    .build()
            } else {
                response
            }
        } else {
            response
        }
    }

    private fun buildAppClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(appLoggingInterceptor)

        // Only add verbose HTTP logging in debug builds to reduce log spam
        // and main-thread overhead in release builds
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(loggingInterceptor)
        }

        builder
            .addNetworkInterceptor(tmdbCacheInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(appConnectionPool)
            .dns(dns)
            .retryOnConnectionFailure(true)

        appContext?.let { ctx ->
            builder.cache(getOrCreateHttpCache(ctx))
        }

        return builder.build()
    }

    private fun getOrCreateHttpCache(context: Context): Cache {
        return appHttpCache ?: synchronized(clientLock) {
            appHttpCache ?: Cache(File(context.cacheDir, "http_cache"), HTTP_CACHE_SIZE).also {
                appHttpCache = it
            }
        }
    }

    /** Resolve bootstrap IPs safely - IPv6 may fail on some devices/emulators */
    private fun safeResolve(vararg addresses: String): List<InetAddress> {
        return addresses.mapNotNull { addr ->
            try { InetAddress.getByName(addr) } catch (_: Exception) { null }
        }
    }

    private val cloudflareBootstrapHosts: List<InetAddress> by lazy {
        safeResolve("1.1.1.1", "1.0.0.1")
    }

    private val googleBootstrapHosts: List<InetAddress> by lazy {
        safeResolve("8.8.8.8", "8.8.4.4")
    }

    private val adguardBootstrapHosts: List<InetAddress> by lazy {
        safeResolve("94.140.14.14", "94.140.15.15")
    }

    fun parseDnsProvider(raw: String?): AppDnsProvider {
        return when (raw?.trim()?.lowercase()) {
            "system", "system dns", "system_dns" -> AppDnsProvider.SYSTEM
            "cloudflare", "cloudflare dns", "cloudflare_dns" -> AppDnsProvider.CLOUDFLARE
            "google" -> AppDnsProvider.GOOGLE
            "adguard", "ad guard" -> AppDnsProvider.ADGUARD
            else -> AppDnsProvider.SYSTEM
        }
    }

    fun setDnsProvider(provider: AppDnsProvider) {
        selectedDnsProvider = provider
        Log.i(TAG, "Using DNS provider=$provider")
        dnsScope.launch {
            appConnectionPool.evictAll()
            Log.i(TAG, "Evicted pooled app connections after DNS change")
        }
    }

    private fun preferIpv4ForTmdb(delegate: Dns): Dns {
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = delegate.lookup(hostname)
                if (!hostname.contains("tmdb", ignoreCase = true) &&
                    !hostname.contains("themoviedb", ignoreCase = true)
                ) {
                    return resolved
                }

                val ipv4 = resolved.filterIsInstance<Inet4Address>()
                if (ipv4.isEmpty()) {
                    return resolved
                }
                val ipv6 = resolved.filterNot { it is Inet4Address }
                return ipv4 + ipv6
            }
        }
    }

    private fun buildBootstrapDns(
        dohHost: String,
        bootstrapHosts: List<InetAddress>
    ): Dns {
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname.equals(dohHost, ignoreCase = true)) {
                    return bootstrapHosts
                }
                throw UnknownHostException(
                    "Bootstrap DNS is restricted to $dohHost. Requested: $hostname"
                )
            }
        }
    }

    private fun buildDohDns(
        url: String,
        dohHost: String,
        bootstrapHosts: List<InetAddress>
    ): Dns {
        val bootstrapDns = buildBootstrapDns(dohHost, bootstrapHosts)
        val bootstrapClient = OkHttpClient.Builder()
            .dns(bootstrapDns)
            .cache(null)
            .build()

        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(url.toHttpUrl())
            .systemDns(bootstrapDns)
            .bootstrapDnsHosts(*bootstrapHosts.toTypedArray())
            .post(true)
            .resolvePrivateAddresses(false)
            .resolvePublicAddresses(true)
            .build()
    }

    val coilClient: OkHttpClient
        get() = coilSharedClient ?: synchronized(clientLock) {
            coilSharedClient ?: buildCoilClient().also { coilSharedClient = it }
        }

    private fun buildCoilClient(): OkHttpClient {
        // Build a dedicated image client with its OWN connection pool and aggressive
        // timeouts. The previous implementation shared the main API client's connection
        // pool (30s timeouts), which meant:
        // 1. A slow TMDB API call could exhaust the shared pool's max-idle connections,
        //    blocking image loads entirely until an API call finished.
        // 2. If image.tmdb.org DNS failed, Coil waited the full 30s connect timeout
        //    per card — on a home screen with 20+ cards, the entire grid appeared frozen.
        // The image CDN (image.tmdb.org) is a fast static-asset CDN that should respond
        // in <500ms; anything longer is a network issue that retrying later will fix.
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
            .dns(dns)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createCoilImageLoader(context: Context): ImageLoader {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val imageCacheBytes = if (activityManager?.isLowRamDevice == true) {
            32 * 1024 * 1024
        } else {
            48 * 1024 * 1024
        }
        return ImageLoader.Builder(context)
            .okHttpClient(coilClient)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(imageCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_SIZE)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .build()
    }
}
