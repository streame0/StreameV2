package com.streame.tv.network

import com.streame.tv.util.Constants
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Intercepts Trakt API calls and routes them through Supabase Edge Functions.
 * TMDB calls go directly — the API key is passed as a query parameter by Retrofit.
 */
class ApiProxyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val host = originalRequest.url.host

        return when {
            host.contains("trakt.tv") -> {
                val proxyRequest = rewriteForTraktProxy(originalRequest)
                chain.proceed(proxyRequest)
            }
            else -> chain.proceed(originalRequest)
        }
    }

    private fun rewriteForTraktProxy(originalRequest: Request): Request {
        val originalUrl = originalRequest.url

        // Extract the path
        val path = originalUrl.encodedPath

        // Build proxy URL with path and method parameters
        val proxyUrlBuilder = Constants.TRAKT_PROXY_URL.toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("method", originalRequest.method)

        // Forward all original query parameters
        for (i in 0 until originalUrl.querySize) {
            val name = originalUrl.queryParameterName(i)
            val value = originalUrl.queryParameterValue(i)
            if (value != null) {
                proxyUrlBuilder.addQueryParameter(name, value)
            }
        }

        // Get the user's auth token from original request if present
        val authHeader = originalRequest.header("Authorization")
        val userToken = authHeader?.removePrefix("Bearer ")?.trim()

        val requestBuilder = originalRequest.newBuilder()
            .url(proxyUrlBuilder.build())
            .header("apikey", Constants.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${Constants.SUPABASE_ANON_KEY}")

        // Forward user token in custom header
        if (userToken != null && userToken.isNotEmpty()) {
            requestBuilder.header("x-user-token", userToken)
        }

        // For POST/DELETE, keep the body but remove trakt-specific headers (proxy adds them)
        requestBuilder.removeHeader("trakt-api-key")
        requestBuilder.removeHeader("trakt-api-version")

        return requestBuilder.build()
    }
}
