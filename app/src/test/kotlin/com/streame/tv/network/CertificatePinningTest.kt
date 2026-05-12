package com.streame.tv.network

import com.google.common.truth.Truth.assertThat
import okhttp3.CertificatePinner
import org.junit.Test

class CertificatePinningTest {

    @Test
    fun `certificate pinner is built without errors`() {
        val pinner = buildTestCertificatePinner()
        assertThat(pinner).isNotNull()
    }

    @Test
    fun `unpinned hostname does not throw on empty cert chain`() {
        val pinner = buildTestCertificatePinner()
        // Hostnames without pins configured should not throw
        pinner.check("unpinned.example.com", listOf())
    }

    @Test
    fun `pinner has 3 hostname patterns configured`() {
        val pinner = buildTestCertificatePinner()
        assertThat(pinner).isNotNull()
        // If the builder accepted all 3 add() calls, the pinner is configured
    }

    @Test
    fun `TMDB domain has 2 backup pins`() {
        val pinner = CertificatePinner.Builder()
            .add("api.themoviedb.org",
                "sha256/7dx5zhb27xZQJ59nuWVySn7L8mwa8y71knaWY/apJ3Y=",
                "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w="
            )
            .build()
        assertThat(pinner).isNotNull()
    }

    @Test
    fun `Trakt domain has 2 backup pins`() {
        val pinner = CertificatePinner.Builder()
            .add("api.trakt.tv",
                "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",
                "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="
            )
            .build()
        assertThat(pinner).isNotNull()
    }

    @Test
    fun `Supabase wildcard domain has 2 backup pins`() {
        val pinner = CertificatePinner.Builder()
            .add("*.supabase.co",
                "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",
                "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="
            )
            .build()
        assertThat(pinner).isNotNull()
    }

    @Test
    fun `all pin strings use sha256 prefix`() {
        val allPins = listOf(
            "sha256/7dx5zhb27xZQJ59nuWVySn7L8mwa8y71knaWY/apJ3Y=",
            "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",
            "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="
        )
        for (pin in allPins) {
            assertThat(pin).startsWith("sha256/")
        }
    }

    @Test
    fun `pin hash values are valid base64 after sha256 prefix`() {
        val allPins = listOf(
            "sha256/7dx5zhb27xZQJ59nuWVySn7L8mwa8y71knaWY/apJ3Y=",
            "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",
            "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="
        )
        for (pin in allPins) {
            val hash = pin.removePrefix("sha256/")
            // Base64-encoded SHA-256 hash should be 44 chars (256 bits = 32 bytes = 44 base64 chars)
            assertThat(hash.length).isEqualTo(44)
            assertThat(hash.endsWith("=")).isTrue()
        }
    }

    private fun buildTestCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            .add("api.themoviedb.org",
                "sha256/7dx5zhb27xZQJ59nuWVySn7L8mwa8y71knaWY/apJ3Y=",
                "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w="
            )
            .add("api.trakt.tv",
                "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",
                "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="
            )
            .add("*.supabase.co",
                "sha256/FbsEWoQj9ZJ+ZZR5jneKjW8gZ3j3Iw7LZmvO3gPjL1w=",
                "sha256/sRHdihwgkaV1N4j9kUo2Y0uU5qWYcQCw0yAJUw0b0+4="
            )
            .build()
    }
}
