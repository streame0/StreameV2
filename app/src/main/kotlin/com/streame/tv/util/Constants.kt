package com.streame.tv.util

/**
 * Application constants
 *
 * TMDB and Trakt API keys are sourced from secrets.properties via BuildConfig.
 * No Supabase — Trakt is the only cloud service.
 */
object Constants {
    // API Base URLs - all calls go direct
    const val TMDB_BASE_URL = "https://api.themoviedb.org/3/"
    const val TRAKT_API_URL = "https://api.trakt.tv/"

    // API keys — sourced from secrets.properties via BuildConfig at compile time.
    // TMDB is public/rate-limited per IP; Trakt Client ID is public.
    // Trakt Client Secret must NEVER be hardcoded in source control.
    val TMDB_API_KEY: String get() = com.streame.tv.BuildConfig.TMDB_API_KEY
    val TRAKT_CLIENT_ID: String get() = com.streame.tv.BuildConfig.TRAKT_CLIENT_ID
    val TRAKT_CLIENT_SECRET: String get() = com.streame.tv.BuildConfig.TRAKT_CLIENT_SECRET
    // Image URLs - tuned for fast loading with smooth scrolling/perf.
    // Card posters use w500 (sufficient for ~140dp cards, ~40% smaller than w780).
    // Card backdrops use w780 (sufficient for ~210dp landscape cards).
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    const val IMAGE_BASE_LARGE = "https://image.tmdb.org/t/p/w1280"
    const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
    // Full quality for hero and detail backdrops — restored to "original" so
    // 4K TV users get the sharpest image. The loading speed issue is addressed
    // by aggressive preloading + disk caching (not by resolution downgrade).
    const val BACKDROP_BASE_LARGE = "https://image.tmdb.org/t/p/original"
    const val LOGO_BASE = "https://image.tmdb.org/t/p/w500"
    const val LOGO_BASE_LARGE = "https://image.tmdb.org/t/p/original"

    // Progress thresholds
    const val WATCHED_THRESHOLD = 90 // Percentage at which content is considered watched
    const val MIN_PROGRESS_THRESHOLD = 3 // Minimum % progress to appear in Continue Watching (filters accidental plays)
    const val MAX_PROGRESS_ENTRIES = 50  // Max playback progress entries to process
    const val MAX_CONTINUE_WATCHING = 50 // Max items in Continue Watching row

    // Preferences keys
    const val PREFS_NAME = "Streame_prefs"
    const val PREF_DEFAULT_SUBTITLE = "default_subtitle"
    const val PREF_AUTO_PLAY_NEXT = "auto_play_next"
    const val PREF_TRAKT_TOKEN = "trakt_token"
}

/**
 * Language code mappings
 */
object LanguageMap {
    private val ISO_LANG_MAP = mapOf(
        "en" to "English", "eng" to "English",
        "fr" to "French", "fre" to "French", "fra" to "French",
        "es" to "Spanish", "spa" to "Spanish",
        "de" to "German", "ger" to "German", "deu" to "German",
        "it" to "Italian", "ita" to "Italian",
        "pt" to "Portuguese", "por" to "Portuguese",
        "nl" to "Dutch", "nld" to "Dutch", "dut" to "Dutch",
        "ru" to "Russian", "rus" to "Russian",
        "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
        "ja" to "Japanese", "jpn" to "Japanese",
        "ko" to "Korean", "kor" to "Korean",
        "ar" to "Arabic", "ara" to "Arabic",
        "hi" to "Hindi", "hin" to "Hindi"
    )
    
    fun getLanguageName(code: String): String {
        return ISO_LANG_MAP[code.lowercase()] ?: code.uppercase()
    }
}


