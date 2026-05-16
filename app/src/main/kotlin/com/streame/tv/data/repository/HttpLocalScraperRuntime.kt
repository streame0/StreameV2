package com.streame.tv.data.repository

import com.streame.tv.data.api.TmdbApi
import com.streame.tv.data.model.Addon
import com.streame.tv.data.model.AddonBehaviorHints
import com.streame.tv.data.model.AddonManifest
import com.streame.tv.data.model.AddonResource
import com.streame.tv.data.model.ProxyHeaders
import com.streame.tv.data.model.StreamBehaviorHints
import com.streame.tv.data.model.StreamSource
import com.streame.tv.util.Constants
import com.google.gson.Gson
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class HttpLocalScraperInstallCandidate(
    val name: String,
    val version: String,
    val description: String,
    val logo: String?,
    val manifest: AddonManifest,
    val transportUrl: String
)

@Singleton
class HttpLocalScraperRuntime @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tmdbApi: TmdbApi
) {
    private val gson = Gson()
    private val networkClient = HttpLocalScraperNetworkClient(okHttpClient, gson)
    private val providerResolvers = HttpLocalScraperProviderResolvers(networkClient, gson)
    private val manifestCache = mutableMapOf<String, HttpScraperManifest>()
    private val tmdbIdCache = mutableMapOf<String, Int?>()

    suspend fun fetchInstallCandidate(
        url: String,
        customName: String?
    ): HttpLocalScraperInstallCandidate? = withContext(Dispatchers.IO) {
        val manifestUrl = manifestUrlFor(url)
        val manifest = fetchManifest(manifestUrl) ?: return@withContext null
        val httpScrapers = manifest.scrapers.filter { it.isHttpOnlyEnabled() }
        if (httpScrapers.isEmpty()) return@withContext null

        val stableId = "http.local.${shortHash(manifestUrl)}"
        val addonManifest = AddonManifest(
            id = stableId,
            name = sanitizeProviderLabel(customName?.trim()?.takeIf { it.isNotBlank() } ?: manifest.name),
            version = manifest.version,
            description = "HTTP local scraper bundle (${httpScrapers.size} HTTP providers)",
            types = listOf("movie", "series"),
            resources = listOf(
                AddonResource(
                    name = "stream",
                    types = listOf("movie", "series"),
                    idPrefixes = listOf("tt")
                )
            ),
            behaviorHints = AddonBehaviorHints()
        )
        HttpLocalScraperInstallCandidate(
            name = addonManifest.name,
            version = manifest.version,
            description = addonManifest.description,
            logo = httpScrapers.firstNotNullOfOrNull { it.logo?.takeIf(String::isNotBlank) },
            manifest = addonManifest,
            transportUrl = manifestUrl.substringBeforeLast('/', missingDelimiterValue = manifestUrl)
        )
    }

    fun canHandle(addon: Addon): Boolean {
        val manifestId = addon.manifest?.id ?: return false
        return manifestId.startsWith(HTTP_LOCAL_MANIFEST_PREFIX) ||
            manifestId.startsWith(LEGACY_LOCAL_MANIFEST_PREFIX)
    }

    suspend fun resolveMovieStreams(
        addon: Addon,
        imdbId: String,
        title: String,
        year: Int?
    ): List<StreamSource> {
        val manifest = resolveAddonManifest(addon) ?: return emptyList()
        val tmdbId = resolveTmdbId(imdbId, mediaType = "movie") ?: return emptyList()
        return resolveHttpStreams(
            addon = addon,
            manifest = manifest,
            tmdbId = tmdbId,
            mediaType = "movie",
            season = null,
            episode = null,
            fallbackTitle = title,
            fallbackYear = year
        )
    }

    suspend fun resolveEpisodeStreams(
        addon: Addon,
        imdbId: String,
        season: Int,
        episode: Int,
        tmdbId: Int?,
        title: String
    ): List<StreamSource> {
        val manifest = resolveAddonManifest(addon) ?: return emptyList()
        val resolvedTmdbId = tmdbId ?: resolveTmdbId(imdbId, mediaType = "tv") ?: return emptyList()
        return resolveHttpStreams(
            addon = addon,
            manifest = manifest,
            tmdbId = resolvedTmdbId,
            mediaType = "tv",
            season = season,
            episode = episode,
            fallbackTitle = title,
            fallbackYear = null
        )
    }

    private suspend fun resolveHttpStreams(
        addon: Addon,
        manifest: HttpScraperManifest,
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?,
        fallbackTitle: String,
        fallbackYear: Int?
    ): List<StreamSource> = coroutineScope {
        val providers = enabledProviderIds(manifest)

        val jobs = buildList {
            if ("multivid" in providers || "videasy" in providers) {
                add(async(Dispatchers.IO) {
                    providerResolvers.resolveVidEasy(tmdbId, mediaType, season, episode, fallbackTitle, fallbackYear)
                })
            }
            if ("multivid" in providers || "vidlink" in providers) {
                add(async(Dispatchers.IO) { providerResolvers.resolveVidLink(tmdbId, mediaType, season, episode) })
            }
            if ("multivid" in providers || "vidsrc" in providers || "vixsrc" in providers) {
                add(async(Dispatchers.IO) { providerResolvers.resolveVidSrc(tmdbId, mediaType, season, episode) })
            }
        }
        jobs.awaitAll()
            .flatten()
            .sanitizeResolvedStreams()
            .distinctBy { it.url }
            .take(50)
            .map { stream -> stream.toStreamSource(addon) }
    }

    private suspend fun resolveTmdbId(imdbId: String, mediaType: String): Int? {
        val clean = imdbId.trim().takeIf { it.matches(Regex("tt\\d{5,}")) } ?: return null
        val key = "$mediaType:$clean"
        synchronized(tmdbIdCache) {
            if (tmdbIdCache.containsKey(key)) return tmdbIdCache[key]
        }
        val resolved = runCatching {
            val find = tmdbApi.findByExternalId(clean)
            if (mediaType == "tv") find.tvResults.firstOrNull()?.id else find.movieResults.firstOrNull()?.id
        }.getOrNull()
        synchronized(tmdbIdCache) { tmdbIdCache[key] = resolved }
        return resolved
    }

    private suspend fun resolveAddonManifest(addon: Addon): HttpScraperManifest? {
        val addonUrl = addon.url ?: return null
        return fetchManifest(manifestUrlFor(addonUrl))
    }

    private fun enabledProviderIds(manifest: HttpScraperManifest): Set<String> {
        return manifest.scrapers
            .filter { it.isHttpOnlyEnabled() }
            .map { it.id.lowercase(Locale.US) }
            .toSet()
    }

    private fun List<HttpResolvedStream>.sanitizeResolvedStreams(): List<HttpResolvedStream> {
        return this.filter { it.url.startsWith("http://", ignoreCase = true) || it.url.startsWith("https://", ignoreCase = true) }
    }

    private suspend fun fetchManifest(manifestUrl: String): HttpScraperManifest? {
        synchronized(manifestCache) {
            manifestCache[manifestUrl]?.let { return it }
        }
        val parsed = runCatching {
            val json = networkClient.getText(manifestUrl)
            gson.fromJson(json, HttpScraperManifest::class.java)
        }.getOrNull()?.takeIf { it.name.isNotBlank() && it.scrapers.isNotEmpty() }
        if (parsed != null) {
            synchronized(manifestCache) { manifestCache[manifestUrl] = parsed }
        }
        return parsed
    }

    private fun manifestUrlFor(url: String): String {
        val clean = url.trim().substringBefore('#').trimEnd('/')
        return if (clean.endsWith("/manifest.json", ignoreCase = true)) clean else "$clean/manifest.json"
    }

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    private fun HttpScraperEntry.isHttpOnlyEnabled(): Boolean {
        if (!enabled) return false
        val normalizedFormats = formats.map { it.lowercase(Locale.US) }.toSet()
        return normalizedFormats.isEmpty() || normalizedFormats.any { it in HTTP_FORMATS }
    }

    private fun HttpResolvedStream.toStreamSource(addon: Addon): StreamSource {
        val cleanHeaders = headers
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        return StreamSource(
            source = title.ifBlank { provider },
            addonName = "${sanitizeProviderLabel(addon.name)} - $provider",
            addonId = addon.id,
            quality = normalizeQuality(quality),
            size = "",
            sizeBytes = null,
            url = url,
            fileIdx = null,
            behaviorHints = cleanHeaders
                .takeIf { it.isNotEmpty() }
                ?.let { StreamBehaviorHints(proxyHeaders = ProxyHeaders(request = it)) },
            subtitles = emptyList()
        )
    }

    private fun normalizeQuality(value: String): String {
        val text = value.lowercase(Locale.US)
        return when {
            "2160" in text || "4k" in text -> "4K"
            "1440" in text -> "1440p"
            "1080" in text -> "1080p"
            "720" in text -> "720p"
            "480" in text -> "480p"
            "360" in text -> "360p"
            else -> "Auto"
        }
    }

    private fun sanitizeProviderLabel(value: String): String {
        return value.replace(Regex("nu" + "vio", RegexOption.IGNORE_CASE), "HTTP").trim()
    }

    companion object {
        private const val HTTP_LOCAL_MANIFEST_PREFIX = "http.local."
        private const val LEGACY_LOCAL_MANIFEST_PREFIX = "nu" + "vio.local."
        private val HTTP_FORMATS = setOf("mp4", "mkv", "m3u8", "hls", "dash")
    }
}
