package com.streame.tv.network

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.*
import okio.Path.Companion.toPath
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
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
    // Image disk cache — increased to avoid evictions between sessions.
    // A typical home screen touches 30-50MB per session; 48MB was evicting half the cache.
    const val IMAGE_DISK_CACHE_SIZE_TV = 256L * 1024L * 1024L
    const val IMAGE_DISK_CACHE_SIZE_MOBILE = 192L * 1024L * 1024L
    const val IMAGE_DISK_CACHE_SIZE_LOW_RAM = 96L * 1024L * 1024L
    /**
     * Certificate pinning for API endpoints that carry sensitive data
     * (auth tokens, API keys, user data). Pins are SHA-256 hashes of
     * the subjectPublicKeyInfo of the leaf or intermediate certificate.
     *
     * Backup pins allow certificate rotation without breaking the app.
     * To generate a pin: echo | openssl s_client -connect host:443 | openssl x509 -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl enc -base64
     */
    private val certificatePinner = CertificatePinner.Builder()
        // TMDB API — carries API key in query params
        .add("api.themoviedb.org",
            "sha256/G9LNNAql897egYsabashkzUCTEJkWBzgoEtk8X/678c=",  // Amazon RSA 2048 M04 intermediate
            "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI="   // Amazon Root CA 1
        )
        // Trakt API — carries OAuth tokens in headers
        .add("api.trakt.tv",
            "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",  // Let's Encrypt R4 intermediate
            "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="   // backup: Let's Encrypt R3 intermediate
        )
        // Supabase — carries JWT tokens and user data
        .add("*.supabase.co",
            "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",  // WE1 intermediate (Google Trust Services)
            "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c="   // GTS Root R4
        )
        .build()

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
     *  Skips image CDN hosts (image.tmdb.org) to reduce logcat noise — those are
     *  handled by the CDN cache interceptor. Uses Log.d for success to reduce
     *  logcat spam; Log.e for failures (always visible). */
    private val appLoggingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        // Skip verbose logging for image CDN — these are high-volume, low-value logs
        if (host.contains("image.tmdb", ignoreCase = true)) {
            return@Interceptor chain.proceed(request)
        }
        val method = request.method
        try {
            val response = chain.proceed(request)
            Log.d(TAG, "HTTP $method $host -> ${response.code}")
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

        builder
            .addNetworkInterceptor(tmdbCacheInterceptor)
            .certificatePinner(certificatePinner)
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
            // 16 connections with 60s keepalive — home screen fires 20+ parallel image
            // requests; 8 connections caused queuing and slow card loading.
            .connectionPool(ConnectionPool(16, 60, TimeUnit.SECONDS))
            .addNetworkInterceptor(imageCdnCacheInterceptor)
            .dns(dns)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Network interceptor that forces long cache lifetime for image.tmdb.org
     *  responses. The TMDB image CDN sometimes returns short max-age or no-cache
     *  headers, which defeats OkHttp's disk cache and forces a full network
     *  round-trip on every app launch. By forcing max-age=86400 (24h), cached
     *  images are served directly from disk without any network I/O. */
    private val imageCdnCacheInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val host = request.url.host
        if (host.contains("image.tmdb", ignoreCase = true)
            && request.method == "GET" && response.isSuccessful
        ) {
            val existingMaxAge = response.cacheControl.maxAgeSeconds
            if (existingMaxAge < 86400) { // less than 24 hours
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=86400")
                    .build()
            } else {
                response
            }
        } else {
            response
        }
    }

    fun createCoilImageLoader(context: Context): ImageLoader {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = activityManager?.isLowRamDevice == true
        val isTv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val memoryCacheBytes = when {
            isLowRam -> 32 * 1024 * 1024
            isTv -> 80 * 1024 * 1024
            else -> 96 * 1024 * 1024
        }
        val diskCacheBytes = when {
            isLowRam -> IMAGE_DISK_CACHE_SIZE_LOW_RAM
            isTv -> IMAGE_DISK_CACHE_SIZE_TV
            else -> IMAGE_DISK_CACHE_SIZE_MOBILE
        }
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { coilClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes.toLong())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(diskCacheBytes)
                    .build()
            }
            .build()
    }
}
