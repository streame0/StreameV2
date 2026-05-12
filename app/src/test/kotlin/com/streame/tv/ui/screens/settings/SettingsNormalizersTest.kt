package com.streame.tv.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsNormalizersTest {

    @Test
    fun `normalizeFrameRateMode handles all variants`() {
        assertThat(SettingsNormalizers.normalizeFrameRateMode("off")).isEqualTo("Off")
        assertThat(SettingsNormalizers.normalizeFrameRateMode("Seamless Only")).isEqualTo("Seamless only")
        assertThat(SettingsNormalizers.normalizeFrameRateMode("always")).isEqualTo("Always")
        assertThat(SettingsNormalizers.normalizeFrameRateMode(null)).isEqualTo("Off")
        assertThat(SettingsNormalizers.normalizeFrameRateMode("")).isEqualTo("Off")
    }

    @Test
    fun `normalizeAutoPlayMinQuality maps quality labels`() {
        assertThat(SettingsNormalizers.normalizeAutoPlayMinQuality("any")).isEqualTo("Any")
        assertThat(SettingsNormalizers.normalizeAutoPlayMinQuality("720p")).isEqualTo("720p")
        assertThat(SettingsNormalizers.normalizeAutoPlayMinQuality("1080p")).isEqualTo("1080p")
        assertThat(SettingsNormalizers.normalizeAutoPlayMinQuality("4k")).isEqualTo("4K")
        assertThat(SettingsNormalizers.normalizeAutoPlayMinQuality(null)).isEqualTo("Any")
    }

    @Test
    fun `normalizeDnsProviderValue handles all variants`() {
        assertThat(SettingsNormalizers.normalizeDnsProviderValue("System DNS")).isEqualTo("system")
        assertThat(SettingsNormalizers.normalizeDnsProviderValue("cloudflare")).isEqualTo("cloudflare")
        assertThat(SettingsNormalizers.normalizeDnsProviderValue("Google")).isEqualTo("google")
        assertThat(SettingsNormalizers.normalizeDnsProviderValue("AdGuard")).isEqualTo("adguard")
        assertThat(SettingsNormalizers.normalizeDnsProviderValue(null)).isEqualTo("system")
    }

    @Test
    fun `dnsProviderLabel maps values to display labels`() {
        assertThat(SettingsNormalizers.dnsProviderLabel("system")).isEqualTo("System DNS")
        assertThat(SettingsNormalizers.dnsProviderLabel("cloudflare")).isEqualTo("Cloudflare")
        assertThat(SettingsNormalizers.dnsProviderLabel("google")).isEqualTo("Google")
        assertThat(SettingsNormalizers.dnsProviderLabel("adguard")).isEqualTo("AdGuard")
    }

    @Test
    fun `dnsProviderValueFromLabel is inverse of dnsProviderLabel`() {
        for (label in listOf("System DNS", "Google", "AdGuard", "Cloudflare")) {
            val value = SettingsNormalizers.dnsProviderValueFromLabel(label)
            assertThat(SettingsNormalizers.dnsProviderLabel(value)).isEqualTo(label)
        }
    }

    @Test
    fun `nextVolumeBoost cycles through discrete steps`() {
        assertThat(SettingsNormalizers.nextVolumeBoost(0)).isEqualTo(3)
        assertThat(SettingsNormalizers.nextVolumeBoost(3)).isEqualTo(6)
        assertThat(SettingsNormalizers.nextVolumeBoost(6)).isEqualTo(9)
        assertThat(SettingsNormalizers.nextVolumeBoost(9)).isEqualTo(12)
        assertThat(SettingsNormalizers.nextVolumeBoost(12)).isEqualTo(15)
        assertThat(SettingsNormalizers.nextVolumeBoost(15)).isEqualTo(0)
    }

    @Test
    fun `nextSubtitleSize cycles correctly`() {
        assertThat(SettingsNormalizers.nextSubtitleSize("Small")).isEqualTo("Medium")
        assertThat(SettingsNormalizers.nextSubtitleSize("Medium")).isEqualTo("Large")
        assertThat(SettingsNormalizers.nextSubtitleSize("Large")).isEqualTo("Extra Large")
        assertThat(SettingsNormalizers.nextSubtitleSize("Extra Large")).isEqualTo("Small")
    }

    @Test
    fun `nextSubtitleColor cycles correctly`() {
        assertThat(SettingsNormalizers.nextSubtitleColor("White")).isEqualTo("Yellow")
        assertThat(SettingsNormalizers.nextSubtitleColor("Yellow")).isEqualTo("Green")
        assertThat(SettingsNormalizers.nextSubtitleColor("Green")).isEqualTo("Cyan")
        assertThat(SettingsNormalizers.nextSubtitleColor("Cyan")).isEqualTo("White")
    }

    @Test
    fun `nextClockFormat toggles between 24h and 12h`() {
        assertThat(SettingsNormalizers.nextClockFormat("24h")).isEqualTo("12h")
        assertThat(SettingsNormalizers.nextClockFormat("12h")).isEqualTo("24h")
    }

    @Test
    fun `nextFrameRateMode cycles correctly`() {
        assertThat(SettingsNormalizers.nextFrameRateMode("Off")).isEqualTo("Seamless only")
        assertThat(SettingsNormalizers.nextFrameRateMode("Seamless only")).isEqualTo("Always")
        assertThat(SettingsNormalizers.nextFrameRateMode("Always")).isEqualTo("Off")
    }

    @Test
    fun `nextAutoPlayMinQuality cycles correctly`() {
        assertThat(SettingsNormalizers.nextAutoPlayMinQuality("Any")).isEqualTo("720p")
        assertThat(SettingsNormalizers.nextAutoPlayMinQuality("720p")).isEqualTo("1080p")
        assertThat(SettingsNormalizers.nextAutoPlayMinQuality("1080p")).isEqualTo("4K")
        assertThat(SettingsNormalizers.nextAutoPlayMinQuality("4K")).isEqualTo("Any")
    }
}
