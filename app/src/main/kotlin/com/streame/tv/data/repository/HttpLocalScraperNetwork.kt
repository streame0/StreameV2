package com.streame.tv.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class HttpLocalScraperNetworkClient(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            response.body?.string().orEmpty()
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject? {
        return runCatching { gson.fromJson(getText(url, headers), JsonObject::class.java) }.getOrNull()
    }

    suspend fun postJson(url: String, body: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            runCatching { gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java) }.getOrNull()
        }
    }
}

internal fun JsonObject.string(name: String): String? = get(name)?.asStringOrNull()
internal fun JsonObject.getObject(name: String): JsonObject? = get(name)?.asJsonObjectOrNull()
internal fun JsonObject.getArray(name: String): JsonArray? = get(name)?.asJsonArrayOrNull()
internal fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
internal fun JsonElement.asJsonArrayOrNull(): JsonArray? = if (isJsonArray) asJsonArray else null
internal fun JsonElement.asStringOrNull(): String? = runCatching {
    if (isJsonNull) null else asString
}.getOrNull()?.takeIf { it.isNotBlank() }
