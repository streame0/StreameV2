package com.streame.tv.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.streame.tv.util.Constants
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class HttpLocalScraperProviderResolvers(
    private val network: HttpLocalScraperNetworkClient,
    private val gson: Gson
) {
    suspend fun resolveVidEasy(
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?,
        fallbackTitle: String,
        fallbackYear: Int?
    ): List<HttpResolvedStream> {
        val details = fetchTmdbDetails(tmdbId, mediaType, fallbackTitle, fallbackYear)
        return coroutineScope {
            VIDEASY_SERVERS.map { (name, endpoint) ->
                async(Dispatchers.IO) {
                    runCatching {
                        val url = buildVidEasyUrl(
                            endpoint = endpoint,
                            serverName = name,
                            details = details,
                            mediaType = mediaType,
                            season = season,
                            episode = episode,
                            tmdbId = tmdbId
                        ) ?: return@runCatching emptyList<HttpResolvedStream>()
                        val encrypted = network.getText(url, VIDEASY_HEADERS).takeIf { it.length > 20 && !it.startsWith("<!") }
                            ?: return@runCatching emptyList()
                        val decrypted = network.postJson(
                            url = "https://enc-dec.app/api/dec-videasy",
                            body = """{"text":${gson.toJson(encrypted)},"id":"$tmdbId"}"""
                        )
                        val result = decrypted?.getObject("result") ?: decrypted
                        (result?.getArray("sources")?.toList().orEmpty()).mapNotNull { source: JsonElement ->
                            val obj = source.asJsonObjectOrNull() ?: return@mapNotNull null
                            val streamUrl = obj.string("url") ?: return@mapNotNull null
                            HttpResolvedStream(
                                provider = "VIDEASY $name",
                                title = "VIDEASY $name ${obj.string("quality").orEmpty()}".trim(),
                                url = streamUrl,
                                quality = obj.string("quality") ?: "Auto",
                                headers = mapOf(
                                    "Referer" to "https://player.videasy.net/",
                                    "Origin" to "https://player.videasy.net",
                                    "User-Agent" to USER_AGENT
                                )
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun resolveVidLink(
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<HttpResolvedStream> = runCatching {
        val encrypted = network.getJson("https://enc-dec.app/api/enc-vidlink?text=${tmdbId.toString().urlEncode()}")
            ?.string("result")
            ?: return@runCatching emptyList()
        val url = if (mediaType == "tv") {
            "https://vidlink.pro/api/b/tv/$encrypted/${season ?: 1}/${episode ?: 1}?multiLang=0"
        } else {
            "https://vidlink.pro/api/b/movie/$encrypted?multiLang=0"
        }
        val payload = network.getJson(url, VIDLINK_HEADERS) ?: return@runCatching emptyList()
        val playlist = payload.getObject("stream")?.string("playlist") ?: return@runCatching emptyList()
        listOf(
            HttpResolvedStream(
                provider = "VidLink",
                title = "VidLink Primary",
                url = playlist,
                quality = "Auto",
                headers = mapOf("Referer" to "https://vidlink.pro/", "Origin" to "https://vidlink.pro")
            )
        )
    }.getOrDefault(emptyList())

    suspend fun resolveVidSrc(
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<HttpResolvedStream> = runCatching {
        val meta = fetchTmdbDetails(tmdbId, mediaType, "", null)
        val imdbId = meta.imdbId ?: return@runCatching emptyList<HttpResolvedStream>()
        val embedUrl = if (mediaType == "tv") {
            "https://vsrc.su/embed/tv?imdb=$imdbId&season=${season ?: 1}&episode=${episode ?: 1}"
        } else {
            "https://vsrc.su/embed/$imdbId"
        }
        val embedHtml = network.getText(embedUrl)
        val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(embedHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?: return@runCatching emptyList<HttpResolvedStream>()
        val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
        val iframeHtml = network.getText(iframeUrl, mapOf("Referer" to "https://vsrc.su/"))
        val prorcpSrc = Regex("""src:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(iframeHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?: return@runCatching emptyList<HttpResolvedStream>()
        val cloudUrl = URL(URL("https://cloudnestra.com/"), prorcpSrc).toString()
        val cloudHtml = network.getText(cloudUrl, mapOf("Referer" to "https://cloudnestra.com/"))
        val divMatch = Regex(
            """<div id="([^"]+)"[^>]*style=["']display\s*:\s*none;?["'][^>]*>([a-zA-Z0-9:/.,{}\-_=+ ]+)</div>""",
            RegexOption.IGNORE_CASE
        ).find(cloudHtml) ?: return@runCatching emptyList<HttpResolvedStream>()
        val decrypted = network.postJson(
            url = "https://enc-dec.app/api/dec-cloudnestra",
            body = """{"text":${gson.toJson(divMatch.groupValues[2])},"div_id":${gson.toJson(divMatch.groupValues[1])}}"""
        )
        (decrypted?.getArray("result")?.toList().orEmpty()).mapIndexedNotNull { index: Int, element: JsonElement ->
            val streamUrl = element.asStringOrNull() ?: return@mapIndexedNotNull null
            HttpResolvedStream(
                provider = "VidSrc",
                title = "VidSrc Server ${index + 1}",
                url = streamUrl,
                quality = "Auto",
                headers = mapOf(
                    "Referer" to "https://cloudnestra.com/",
                    "Origin" to "https://cloudnestra.com"
                )
            )
        }
    }.getOrDefault(emptyList())

    suspend fun fetchTmdbDetails(
        tmdbId: Int,
        mediaType: String,
        fallbackTitle: String,
        fallbackYear: Int?
    ): HttpScraperTmdbDetails {
        return runCatching {
            val type = if (mediaType == "tv") "tv" else "movie"
            val payload = network.getJson(
                "https://api.themoviedb.org/3/$type/$tmdbId?api_key=${Constants.TMDB_API_KEY}&append_to_response=external_ids"
            )
            val title = payload?.string(if (type == "tv") "name" else "title")
                ?: fallbackTitle
            val date = payload?.string(if (type == "tv") "first_air_date" else "release_date")
            val year = date?.take(4)?.takeIf { it.all(Char::isDigit) } ?: fallbackYear?.toString()
            val imdbId = payload?.getObject("external_ids")?.string("imdb_id")
                ?: payload?.string("imdb_id")
            HttpScraperTmdbDetails(tmdbId.toString(), title, year, imdbId, type)
        }.getOrElse {
            HttpScraperTmdbDetails(tmdbId.toString(), fallbackTitle, fallbackYear?.toString(), null, mediaType)
        }
    }

    private fun buildVidEasyUrl(
        endpoint: String,
        serverName: String,
        details: HttpScraperTmdbDetails,
        mediaType: String,
        season: Int?,
        episode: Int?,
        tmdbId: Int
    ): String? {
        if (mediaType == "tv" && serverName == "Yoru") return null
        val base = "$endpoint?title=${details.title.urlEncode()}" +
            "&mediaType=${details.mediaType}&year=${details.year.orEmpty()}" +
            "&tmdbId=$tmdbId&imdbId=${details.imdbId.orEmpty()}"
        if (mediaType != "tv") return base
        return "$base&seasonId=${season ?: 1}&episodeId=${episode ?: 1}"
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        private val VIDEASY_SERVERS = listOf(
            "Neon" to "https://api.videasy.net/myflixerzupcloud/sources-with-title",
            "Yoru" to "https://api.videasy.net/cdn/sources-with-title",
            "Cypher" to "https://api.videasy.net/moviebox/sources-with-title",
            "Reyna" to "https://api.videasy.net/primewire/sources-with-title",
            "Omen" to "https://api.videasy.net/onionplay/sources-with-title",
            "Breach" to "https://api.videasy.net/m4uhd/sources-with-title",
            "Ghost" to "https://api.videasy.net/primesrcme/sources-with-title",
            "Sage" to "https://api.videasy.net/1movies/sources-with-title",
            "Vyse" to "https://api.videasy.net/hdmovie/sources-with-title",
            "Raze" to "https://api.videasy.net/superflix/sources-with-title"
        )
        private val VIDEASY_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://player.videasy.net",
            "Referer" to "https://player.videasy.net/"
        )
        private val VIDLINK_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json,*/*",
            "Referer" to "https://vidlink.pro/",
            "Origin" to "https://vidlink.pro"
        )
    }
}

internal fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
    .replace("+", "%20")
