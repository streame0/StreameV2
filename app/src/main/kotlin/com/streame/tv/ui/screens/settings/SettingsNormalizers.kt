package com.streame.tv.ui.screens.settings

import com.streame.tv.ui.components.CARD_LAYOUT_MODE_LANDSCAPE
import com.streame.tv.ui.components.normalizeCardLayoutMode

/**
 * Pure normalization and label-mapping functions for settings values.
 *
 * Extracted from [SettingsViewModel] so they can be tested independently
 * without needing the full ViewModel + DI graph.
 */
object SettingsNormalizers {

    fun normalizeFrameRateMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "off" -> "Off"
        "seamless", "seamless only", "only if seamless", "only_if_seamless" -> "Seamless only"
        "always" -> "Always"
        else -> "Off"
    }

    fun normalizeAutoPlayMinQuality(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "any" -> "Any"
        "720p", "hd" -> "720p"
        "1080p", "fullhd", "fhd" -> "1080p"
        "4k", "2160p", "uhd" -> "4K"
        else -> "Any"
    }

    fun normalizeDnsProviderValue(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "system", "system dns", "system_dns" -> "system"
        "cloudflare", "cloudflare dns", "cloudflare_dns" -> "cloudflare"
        "google" -> "google"
        "adguard", "ad guard" -> "adguard"
        else -> "system"
    }

    fun dnsProviderLabel(value: String): String = when (normalizeDnsProviderValue(value)) {
        "system" -> "System DNS"
        "google" -> "Google"
        "adguard" -> "AdGuard"
        else -> "Cloudflare"
    }

    fun dnsProviderValueFromLabel(label: String): String = when (label.trim().lowercase()) {
        "system dns" -> "system"
        "google" -> "google"
        "adguard" -> "adguard"
        else -> "cloudflare"
    }

    fun nextVolumeBoost(current: Int): Int = when {
        current < 3 -> 3
        current < 6 -> 6
        current < 9 -> 9
        current < 12 -> 12
        current < 15 -> 15
        else -> 0
    }

    fun nextSubtitleSize(current: String): String = when (current) {
        "Small" -> "Medium"
        "Medium" -> "Large"
        "Large" -> "Extra Large"
        else -> "Small"
    }

    fun nextSubtitleColor(current: String): String = when (current) {
        "White" -> "Yellow"
        "Yellow" -> "Green"
        "Green" -> "Cyan"
        else -> "White"
    }

    fun nextClockFormat(current: String): String = if (current == "24h") "12h" else "24h"

    fun nextCardLayoutMode(current: String): String =
        if (current.equals("Poster", ignoreCase = true)) CARD_LAYOUT_MODE_LANDSCAPE else "Poster"

    fun nextAutoPlayMinQuality(current: String): String = when (normalizeAutoPlayMinQuality(current)) {
        "Any" -> "720p"
        "720p" -> "1080p"
        "1080p" -> "4K"
        else -> "Any"
    }

    fun nextFrameRateMode(current: String): String = when (normalizeFrameRateMode(current)) {
        "Off" -> "Seamless only"
        "Seamless only" -> "Always"
        else -> "Off"
    }
}
