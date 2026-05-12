package com.streame.tv.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ErrorLoggingTest {

    @Test
    fun `AppLogger e method accepts tag message and throwable`() {
        // Verify the signature exists and doesn't throw
        val exception = RuntimeException("test error")
        // Should not throw — the method is a no-op in unit tests by default
        AppLogger.e("TestTag", "Test message", exception)
    }

    @Test
    fun `AppLogger e method works with null throwable`() {
        AppLogger.e("TestTag", "Test message", null)
    }

    @Test
    fun `sanitizeEmail does not leak full email`() {
        val email = "sensitive@company.com"
        val sanitized = email.sanitizeEmail()
        assertThat(sanitized).doesNotContain("sensitive")
        assertThat(sanitized).doesNotContain("company")
    }

    @Test
    fun `maskToken does not leak full token`() {
        val token = "sk-1234567890abcdef"
        val masked = token.maskToken()
        assertThat(masked).doesNotContain("567890abcdef")
    }

    @Test
    fun `hash produces consistent output for same input`() {
        val input = "test-user-id"
        val hash1 = input.hash()
        val hash2 = input.hash()
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `hash output is not reversible to original input`() {
        val input = "my-secret-user-id"
        val hash = input.hash()
        assertThat(hash).doesNotContain("secret")
        assertThat(hash).doesNotContain("my-secret-user-id")
    }
}
