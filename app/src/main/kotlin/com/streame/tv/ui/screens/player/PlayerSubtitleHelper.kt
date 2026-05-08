package com.streame.tv.ui.screens.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.streame.tv.data.model.Subtitle
import com.streame.tv.data.repository.ProfileManager
import com.streame.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

/**
 * Helper that encapsulates subtitle selection, filtering, and usage tracking logic
 * extracted from [PlayerViewModel] to reduce its size and improve testability.
 */
class PlayerSubtitleHelper(
    private val context: Context,
    private val profileManager: ProfileManager,
    private val gson: Gson
) {
    // Profile-scoped preference keys
    fun defaultSubtitleKey() = profileManager.profileStringKey("default_subtitle")
    fun subtitleUsageKey() = profileManager.profileStringKey("subtitle_usage_v1")
    fun filterSubtitlesByLanguageKey() = profileManager.profileBooleanKey("filter_subtitles_by_lang")
    fun secondarySubtitleKey() = profileManager.profileStringKey("secondary_subtitle")

    /**
     * Returns the user's default subtitle language preference from DataStore.
     */
    suspend fun getDefaultSubtitle(): String {
        return try {
            val prefs = context.settingsDataStore.data.first()
            val raw = prefs[defaultSubtitleKey()]?.trim().orEmpty()
            if (raw.isBlank()) "en" else raw
        } catch (_: Exception) {
            "en"
        }
    }

    /**
     * Returns subs filtered to the preferred language(s) when the setting is enabled.
     * Tries primary language first; if nothing matches, tries secondary; falls back to full list.
     */
    suspend fun filterSubsByPreferredLanguage(subs: List<Subtitle>): List<Subtitle> {
        val prefs = runCatching { context.settingsDataStore.data.first() }.getOrNull() ?: return subs
        val enabled = prefs[filterSubtitlesByLanguageKey()] ?: true
        if (!enabled) return subs
        val preferred = prefs[defaultSubtitleKey()]?.trim().orEmpty()
        if (isSubtitleDisabledPreference(preferred)) return subs
        val normalizedPref = normalizeLanguage(preferred)
        if (normalizedPref.isBlank()) return subs

        fun matchesLang(sub: Subtitle, lang: String): Boolean {
            val tokens = buildSet {
                add(normalizeLanguage(sub.lang))
                add(normalizeLanguage(sub.label))
                Regex("[A-Za-z-]+").findAll("${sub.lang} ${sub.label}")
                    .map { normalizeLanguage(it.value) }
                    .filter { it.isNotBlank() }
                    .forEach { add(it) }
            }
            return tokens.any { it.equals(lang, ignoreCase = true) }
        }

        val primaryFiltered = subs.filter { matchesLang(it, normalizedPref) }
        if (primaryFiltered.isNotEmpty()) return primaryFiltered

        val secondary = prefs[secondarySubtitleKey()]?.trim().orEmpty()
        val secondaryFiltered = if (!isSubtitleDisabledPreference(secondary)) {
            val normalizedSecondary = normalizeLanguage(secondary)
            if (normalizedSecondary.isNotBlank() && normalizedSecondary != normalizedPref) {
                subs.filter { matchesLang(it, normalizedSecondary) }
            } else emptyList()
        } else emptyList()
        if (secondaryFiltered.isNotEmpty()) return secondaryFiltered

        return subs
    }

    /**
     * Applies the preferred subtitle selection to the given list.
     * Returns the selected [Subtitle] or null if disabled.
     */
    fun applyPreferredSubtitle(preference: String, subtitles: List<Subtitle>, fallbackLanguage: String?): Subtitle? {
        if (isSubtitleDisabledPreference(preference)) return null

        val normalizedPref = normalizeLanguage(preference)
        val normalizedFallback = fallbackLanguage
            ?.let { normalizeLanguage(it) }
            ?.takeIf { it.isNotBlank() && it != normalizedPref }

        fun subtitleTokens(sub: Subtitle): Set<String> {
            val rawTokens = Regex("[A-Za-z-]+").findAll("${sub.lang} ${sub.label}")
                .map { it.value }
                .toList()
            val normalized = rawTokens.map { normalizeLanguage(it) }.filter { it.isNotBlank() }
            return buildSet {
                add(normalizeLanguage(sub.lang))
                add(normalizeLanguage(sub.label))
                addAll(normalized)
            }.filter { it.isNotBlank() }.toSet()
        }

        fun findMatch(target: String): Subtitle? {
            val embeddedMatch = subtitles.firstOrNull { sub ->
                sub.isEmbedded && subtitleTokens(sub).contains(target)
            }
            if (embeddedMatch != null) return embeddedMatch

            return subtitles.firstOrNull { sub ->
                subtitleTokens(sub).contains(target)
            }
        }

        findMatch(normalizedPref)?.let { return it }
        normalizedFallback?.let { findMatch(it)?.let { match -> return match } }

        return subtitles.firstOrNull()
    }

    /**
     * Records subtitle language usage in DataStore for smarter defaults.
     */
    suspend fun recordSubtitleUsage(subtitle: Subtitle) {
        val raw = subtitle.lang.ifBlank { subtitle.label }
        if (raw.isBlank()) return
        val key = normalizeLanguage(raw)
        if (key.isBlank()) return

        val prefs = context.settingsDataStore.data.first()
        val json = prefs[subtitleUsageKey()]
        val type = TypeToken.getParameterized(MutableMap::class.java, String::class.java, Int::class.javaObjectType).type
        val map: MutableMap<String, Int> = if (!json.isNullOrBlank()) {
            gson.fromJson(json, type)
        } else {
            mutableMapOf()
        }

        map[key] = (map[key] ?: 0) + 1
        context.settingsDataStore.edit { it[subtitleUsageKey()] = gson.toJson(map) }
    }

    /**
     * Merges player text tracks with external subtitles, applies language filter,
     * and preserves the currently selected subtitle.
     */
    suspend fun mergeAndFilterSubtitles(
        playerTextTracks: List<Subtitle>,
        currentSubtitles: List<Subtitle>,
        selectedSubtitle: Subtitle?
    ): Pair<List<Subtitle>, Subtitle?> {
        val trackBackedIds = playerTextTracks.map { it.id }.toSet()

        val unresolvedExternal = currentSubtitles.filter { subtitle ->
            !subtitle.isEmbedded && subtitle.url.isNotBlank() && subtitle.id !in trackBackedIds
        }

        val merged = (playerTextTracks + unresolvedExternal)
            .distinctBy { subtitle ->
                val normalizedId = subtitle.id.trim()
                if (normalizedId.isNotBlank()) normalizedId
                else "${subtitle.lang}|${subtitle.label}|${subtitle.url}"
            }

        val filtered = filterSubsByPreferredLanguage(merged)

        val finalList = if (selectedSubtitle != null && filtered.none { it.id == selectedSubtitle.id }) {
            (filtered + selectedSubtitle).distinctBy { s ->
                s.id.trim().ifBlank { "${s.lang}|${s.label}|${s.url}" }
            }
        } else {
            filtered
        }

        val resolvedSelected = if (selectedSubtitle != null) {
            finalList.firstOrNull { it.id == selectedSubtitle.id }
                ?: finalList.firstOrNull { selectedSubtitle.url.isNotBlank() && it.url == selectedSubtitle.url }
                ?: selectedSubtitle
        } else {
            null
        }

        return finalList to resolvedSelected
    }

    companion object {
        fun isSubtitleDisabledPreference(value: String?): Boolean {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return normalized.isBlank() ||
                normalized == "off" ||
                normalized == "none" ||
                normalized == "disabled" ||
                normalized == "no" ||
                normalized == "0"
        }

        /**
         * Normalize language codes to a standard format for matching.
         * Maps: "English" -> "en", "eng" -> "en", "Spanish" -> "es", etc.
         */
        fun normalizeLanguage(lang: String): String {
            val lowerLang = lang.lowercase().trim()
            return when {
                // Full names
                lowerLang == "english" || lowerLang.startsWith("english") -> "en"
                lowerLang == "spanish" || lowerLang.startsWith("spanish") || lowerLang == "espanol" -> "es"
                lowerLang == "french" || lowerLang.startsWith("french") || lowerLang == "francais" -> "fr"
                lowerLang == "german" || lowerLang.startsWith("german") || lowerLang == "deutsch" -> "de"
                lowerLang == "italian" || lowerLang.startsWith("italian") -> "it"
                lowerLang == "portuguese" -> "pt"
                lowerLang == "portuguese (brazil)" ||
                    lowerLang == "portuguese-brazil" ||
                    lowerLang == "brazilian portuguese" ||
                    lowerLang == "brazil portuguese" ||
                    lowerLang == "pt-br" ||
                    lowerLang == "ptbr" -> "pt-br"
                lowerLang.startsWith("portuguese") -> "pt"
                lowerLang == "dutch" || lowerLang.startsWith("dutch") -> "nl"
                lowerLang == "russian" || lowerLang.startsWith("russian") -> "ru"
                lowerLang == "chinese" || lowerLang.startsWith("chinese") -> "zh"
                lowerLang == "japanese" || lowerLang.startsWith("japanese") || lowerLang == "jp" || lowerLang == "jap" -> "ja"
                lowerLang == "korean" || lowerLang.startsWith("korean") -> "ko"
                lowerLang == "arabic" || lowerLang.startsWith("arabic") -> "ar"
                lowerLang == "hindi" || lowerLang.startsWith("hindi") -> "hi"
                lowerLang == "turkish" || lowerLang.startsWith("turkish") -> "tr"
                lowerLang == "polish" || lowerLang.startsWith("polish") -> "pl"
                lowerLang == "swedish" || lowerLang.startsWith("swedish") -> "sv"
                lowerLang == "norwegian" || lowerLang.startsWith("norwegian") -> "no"
                lowerLang == "danish" || lowerLang.startsWith("danish") -> "da"
                lowerLang == "finnish" || lowerLang.startsWith("finnish") -> "fi"
                lowerLang == "greek" || lowerLang.startsWith("greek") -> "el"
                lowerLang == "czech" || lowerLang.startsWith("czech") -> "cs"
                lowerLang == "hungarian" || lowerLang.startsWith("hungarian") -> "hu"
                lowerLang == "romanian" || lowerLang.startsWith("romanian") -> "ro"
                lowerLang == "thai" || lowerLang.startsWith("thai") -> "th"
                lowerLang == "vietnamese" || lowerLang.startsWith("vietnamese") -> "vi"
                lowerLang == "indonesian" || lowerLang.startsWith("indonesian") -> "id"
                lowerLang == "hebrew" || lowerLang.startsWith("hebrew") -> "he"
                lowerLang == "persian" || lowerLang.startsWith("persian") || lowerLang == "farsi" -> "fa"
                lowerLang == "ukrainian" || lowerLang.startsWith("ukrainian") -> "uk"
                lowerLang == "bengali" || lowerLang.startsWith("bengali") -> "bn"
                lowerLang == "bulgarian" || lowerLang.startsWith("bulgarian") -> "bg"
                lowerLang == "croatian" || lowerLang.startsWith("croatian") -> "hr"
                lowerLang == "serbian" || lowerLang.startsWith("serbian") -> "sr"
                lowerLang == "slovak" || lowerLang.startsWith("slovak") -> "sk"
                lowerLang == "slovenian" || lowerLang.startsWith("slovenian") -> "sl"
                lowerLang == "lithuanian" || lowerLang.startsWith("lithuanian") -> "lt"
                lowerLang == "estonian" || lowerLang.startsWith("estonian") -> "et"
                // ISO 639-1 codes (2 letter)
                lowerLang.length == 2 -> lowerLang
                // ISO 639-2 codes (3 letter)
                lowerLang == "eng" -> "en"
                lowerLang == "spa" -> "es"
                lowerLang == "fra" || lowerLang == "fre" -> "fr"
                lowerLang == "deu" || lowerLang == "ger" -> "de"
                lowerLang == "ita" -> "it"
                lowerLang == "por" -> "pt"
                lowerLang == "pob" || lowerLang == "pobr" -> "pt-br"
                lowerLang == "nld" || lowerLang == "dut" -> "nl"
                lowerLang == "rus" -> "ru"
                lowerLang == "zho" || lowerLang == "chi" -> "zh"
                lowerLang == "jpn" -> "ja"
                lowerLang == "kor" -> "ko"
                lowerLang == "ara" -> "ar"
                lowerLang == "hin" -> "hi"
                lowerLang == "tur" -> "tr"
                lowerLang == "pol" -> "pl"
                lowerLang == "swe" -> "sv"
                lowerLang == "nor" -> "no"
                lowerLang == "dan" -> "da"
                lowerLang == "fin" -> "fi"
                lowerLang == "ell" || lowerLang == "gre" -> "el"
                lowerLang == "ces" || lowerLang == "cze" -> "cs"
                lowerLang == "hun" -> "hu"
                lowerLang == "ron" || lowerLang == "rum" -> "ro"
                lowerLang == "tha" -> "th"
                lowerLang == "vie" -> "vi"
                lowerLang == "ind" -> "id"
                lowerLang == "heb" -> "he"
                lowerLang == "fas" || lowerLang == "per" -> "fa"
                lowerLang == "ukr" -> "uk"
                lowerLang == "ben" -> "bn"
                lowerLang == "bul" -> "bg"
                lowerLang == "hrv" -> "hr"
                lowerLang == "srp" -> "sr"
                lowerLang == "slk" || lowerLang == "slo" -> "sk"
                lowerLang == "slv" -> "sl"
                lowerLang == "lit" -> "lt"
                lowerLang == "est" -> "et"
                else -> lowerLang
            }
        }
    }
}
