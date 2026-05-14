package com.streame.tv.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.Test

class SubtitleTranslationServiceTest {

    private val service = SubtitleTranslationService({ "api-key" }, { SubtitleAiModel.GROQ_LLAMA_70B })

    @Test
    fun `extractJsonArray should extract array from various formats`() {
        val cases = listOf(
            // Plain array
            "[\"line 1\", \"line 2\"]" to listOf("line 1", "line 2"),
            // JSON block
            "```json\n[\"line 1\", \"line 2\"]\n```" to listOf("line 1", "line 2"),
            // Markdown block without language
            "```\n[\"line 1\", \"line 2\"]\n```" to listOf("line 1", "line 2"),
            // Text around the array
            "Here is the translation: [\"line 1\", \"line 2\"] Hope it helps!" to listOf("line 1", "line 2"),
            // Multiple blocks, should prefer the last one (often the corrected one if LLM self-corrects)
            "```json\n[\"wrong\"]\n```\n```json\n[\"right\"]\n```" to listOf("right")
        )

        for ((input, expected) in cases) {
            val result = service.extractJsonArray(input)
            assertThat(result).isNotNull()
            val list = mutableListOf<String>()
            for (i in 0 until result!!.length()) {
                list.add(result.getString(i))
            }
            assertThat(list).isEqualTo(expected)
        }
    }

    @Test
    fun `extractJsonArray should handle special characters`() {
        val input = "[\"Line with \\u23CE symbol\", \"Line with \\\"quotes\\\"\"]"
        val result = service.extractJsonArray(input)
        assertThat(result?.getString(0)).isEqualTo("Line with \u23CE symbol")
        assertThat(result?.getString(1)).isEqualTo("Line with \"quotes\"")
    }

    @Test
    fun `extractJsonArray should return null for invalid input`() {
        assertThat(service.extractJsonArray("not a json")).isNull()
        assertThat(service.extractJsonArray("{\"not\": \"an array\"}")).isNull()
    }
}
