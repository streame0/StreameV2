package com.streame.tv.data.repository

internal data class HttpScraperManifest(
    val name: String = "",
    val version: String = "1.0.0",
    val scrapers: List<HttpScraperEntry> = emptyList()
)

internal data class HttpScraperEntry(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = false,
    val formats: List<String> = emptyList(),
    val logo: String? = null
)

internal data class HttpScraperTmdbDetails(
    val id: String,
    val title: String,
    val year: String?,
    val imdbId: String?,
    val mediaType: String
)

internal data class HttpResolvedStream(
    val provider: String,
    val title: String,
    val url: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap()
)
