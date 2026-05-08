package com.streame.tv.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerSubtitleHelperTest {

    // ── normalizeLanguage ──────────────────────────────────────────────

    @Test
    fun `normalizeLanguage - full English name returns en`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("English")).isEqualTo("en")
    }

    @Test
    fun `normalizeLanguage - full Spanish name returns es`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("Spanish")).isEqualTo("es")
    }

    @Test
    fun `normalizeLanguage - ISO 639-2 eng returns en`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("eng")).isEqualTo("en")
    }

    @Test
    fun `normalizeLanguage - 2-letter code passthrough`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("fr")).isEqualTo("fr")
    }

    @Test
    fun `normalizeLanguage - Brazilian Portuguese variants return pt-br`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("pt-br")).isEqualTo("pt-br")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("ptbr")).isEqualTo("pt-br")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("Portuguese (Brazil)")).isEqualTo("pt-br")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("Brazilian Portuguese")).isEqualTo("pt-br")
    }

    @Test
    fun `normalizeLanguage - case insensitive`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("ENGLISH")).isEqualTo("en")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("Japanese")).isEqualTo("ja")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("KOREAN")).isEqualTo("ko")
    }

    @Test
    fun `normalizeLanguage - trim whitespace`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("  en  ")).isEqualTo("en")
        assertThat(PlayerSubtitleHelper.normalizeLanguage(" English ")).isEqualTo("en")
    }

    @Test
    fun `normalizeLanguage - unknown code returns lowercase`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("XX")).isEqualTo("xx")
    }

    @Test
    fun `normalizeLanguage - jp and jap map to ja`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("jp")).isEqualTo("ja")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("jap")).isEqualTo("ja")
    }

    @Test
    fun `normalizeLanguage - ISO 639-2 three letter codes`() {
        assertThat(PlayerSubtitleHelper.normalizeLanguage("deu")).isEqualTo("de")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("spa")).isEqualTo("es")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("jpn")).isEqualTo("ja")
        assertThat(PlayerSubtitleHelper.normalizeLanguage("zho")).isEqualTo("zh")
    }

    // ── isSubtitleDisabledPreference ───────────────────────────────────

    @Test
    fun `isSubtitleDisabledPreference - off returns true`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("off")).isTrue()
    }

    @Test
    fun `isSubtitleDisabledPreference - none returns true`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("none")).isTrue()
    }

    @Test
    fun `isSubtitleDisabledPreference - disabled returns true`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("disabled")).isTrue()
    }

    @Test
    fun `isSubtitleDisabledPreference - null returns true`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference(null)).isTrue()
    }

    @Test
    fun `isSubtitleDisabledPreference - blank returns true`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("")).isTrue()
    }

    @Test
    fun `isSubtitleDisabledPreference - valid language returns false`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("en")).isFalse()
    }

    @Test
    fun `isSubtitleDisabledPreference - case insensitive`() {
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("OFF")).isTrue()
        assertThat(PlayerSubtitleHelper.isSubtitleDisabledPreference("None")).isTrue()
    }
}
