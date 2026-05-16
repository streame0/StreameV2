package com.streame.tv.data.api

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A DataSource.Factory that wraps a HttpDataSource and appends YouTube's
 * `&range=start-end` query parameter on each request. YouTube throttles (and
 * kills) connections that try to download full adaptive streams in one shot,
 * but honours chunked range-param requests at full speed.
 *
 * Only activates for googlevideo.com URLs; all other URLs pass through untouched.
 * Enhanced to handle playback recovery and improved error logging for expired URLs.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val upstreamFactory: HttpDataSource.Factory,
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : HttpDataSource.Factory {

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        upstreamFactory.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }

    companion object {
        private const val TAG = "YTChunkedDS"
        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): HttpDataSource {
        val upstream = upstreamFactory.createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: HttpDataSource,
        private val chunkSize: Long
    ) : HttpDataSource {

        private var currentUri: Uri? = null
        private var isYouTubeStream = false
        private var totalContentLength = C.LENGTH_UNSET.toLong()
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var originalDataSpec: DataSpec? = null

        override fun setRequestProperty(name: String, value: String) {
            upstream.setRequestProperty(name, value)
        }

        override fun clearRequestProperty(name: String) {
            upstream.clearRequestProperty(name)
        }

        override fun clearAllRequestProperties() {
            upstream.clearAllRequestProperties()
        }

        override fun getResponseCode(): Int = upstream.responseCode

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            currentUri = uri
            val host = uri.host.orEmpty().lowercase()
            
            // Expand detection to cover more YouTube media URL patterns
            // Also include any URL that already has a 'range=' parameter, as ExoPlayer's
            // Range header will likely conflict with it, causing HTTP 400.
            val urlString = uri.toString()
            isYouTubeStream = host.contains("googlevideo") || 
                             host.contains("youtube") ||
                             host.contains("hubcloud") ||
                             host.contains("googleusercontent") ||
                             host.contains("pixeldrain") ||
                             host.contains("worker") ||
                             urlString.contains("videoplayback") ||
                             urlString.contains("range=") ||
                             urlString.contains("sig=") ||
                             urlString.contains("lsig=") ||
                             urlString.contains("expire=")

            if (!isYouTubeStream) {
                try {
                    return upstream.open(dataSpec)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // Fallback for hosts that return 400 on range requests (common for debrid/cdn)
                    // We also check for 403 as sometimes range requests are forbidden but full ones aren't
                    if ((e.responseCode == 400 || e.responseCode == 403) && 
                        (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong())) {
                        Log.w(TAG, "HTTP ${e.responseCode} on range request for ${uri.host}, falling back to chunked logic. URL: $urlString")
                        isYouTubeStream = true
                        // Fall through to chunked logic below
                    } else {
                        // Log potential errors for non-YouTube streams too
                        if (e.responseCode >= 400) {
                            Log.e(TAG, "Stream failed with HTTP ${e.responseCode} for host ${uri.host}. URL: $urlString", e)
                        }
                        throw e
                    }
                }
            }

            originalDataSpec = dataSpec
            currentChunkStart = dataSpec.position
            totalContentLength = dataSpec.length

            Log.d(TAG, "Chunked DataSource activated for: ${uri.host}")
            return openNextChunk()
        }

        private fun openNextChunk(): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            
            // Calculate chunk end position
            val end = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, originalDataSpec!!.position + originalDataSpec!!.length - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }

            // Ensure we don't request a range that starts beyond the content length if known
            if (totalContentLength != C.LENGTH_UNSET.toLong() && currentChunkStart >= (originalDataSpec!!.position + originalDataSpec!!.length)) {
                return C.LENGTH_UNSET.toLong()
            }

            currentChunkEnd = end

            // YouTube's 'range' parameter must be the exact start-end bytes.
            // We use string manipulation on the encoded query to avoid re-encoding
            // sensitive parameters like signatures (sig, lsig, n, etc.) which are
            // often present in googlevideo URLs and would be broken by Uri.getQueryParameters().
            val baseUri = spec.uri
            val rangeParam = "range=$currentChunkStart-$currentChunkEnd"
            val encodedQuery = baseUri.encodedQuery
            
            val newEncodedQuery = when {
                encodedQuery == null -> rangeParam
                encodedQuery.contains("range=") -> {
                    // Using a more precise replacement that only targets the range=... parameter
                    // without accidentally matching substrings in signatures.
                    val parts = encodedQuery.split('&').toMutableList()
                    var found = false
                    for (i in parts.indices) {
                        if (parts[i].startsWith("range=")) {
                            parts[i] = rangeParam
                            found = true
                            break
                        }
                    }
                    if (!found) parts.add(rangeParam)
                    parts.joinToString("&")
                }
                else -> "$encodedQuery&$rangeParam"
            }
            
            val rangedUri = baseUri.buildUpon()
                .encodedQuery(newEncodedQuery)
                .build()

            Log.d(TAG, "Opening chunk: $currentChunkStart-$currentChunkEnd")
            Log.v(TAG, "Original URI: $baseUri")
            Log.v(TAG, "Ranged URI: $rangedUri")

            val chunkedSpec = spec.buildUpon()
                .setUri(rangedUri)
                .setPosition(0)           // position within this chunk's response
                .setLength(C.LENGTH_UNSET.toLong()) // let the server decide
                .build()

            bytesReadInChunk = 0
            try {
                upstream.open(chunkedSpec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416) {
                    Log.e(TAG, "416 Requested Range Not Satisfiable at $currentChunkStart-$currentChunkEnd")
                } else if (e.responseCode == 403 || e.responseCode == 404 || e.responseCode == 410) {
                    Log.e(TAG, "HTTP ${e.responseCode} opening YouTube chunk at $currentChunkStart-$currentChunkEnd (URL expired)")
                } else if (e.responseCode >= 400) {
                    Log.e(TAG, "HTTP ${e.responseCode} error opening chunk at $currentChunkStart-$currentChunkEnd for URI: $rangedUri")
                }
                throw e
            }
            return if (totalContentLength != C.LENGTH_UNSET.toLong()) totalContentLength else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) {
                return upstream.read(buffer, offset, length)
            }

            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                // Current chunk exhausted — open the next one
                val chunkBytesReceived = bytesReadInChunk
                upstream.close()

                // If this chunk returned fewer bytes than requested, the stream is done
                if (chunkBytesReceived < (currentChunkEnd - currentChunkStart + 1)) {
                    // Check if we actually reached the end of the total requested content
                    val originalSpec = originalDataSpec
                    if (originalSpec != null && originalSpec.length != C.LENGTH_UNSET.toLong()) {
                        val totalRead = currentChunkStart + chunkBytesReceived - originalSpec.position
                        if (totalRead >= originalSpec.length) {
                            return C.RESULT_END_OF_INPUT
                        }
                    }
                    // If not at end but got fewer bytes, the server might have capped the chunk
                }

                currentChunkStart += chunkBytesReceived
                if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                    totalContentLength -= chunkBytesReceived
                    if (totalContentLength <= 0) {
                        return C.RESULT_END_OF_INPUT
                    }
                }

                return try {
                    openNextChunk()
                    upstream.read(buffer, offset, length)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open next chunk at $currentChunkStart: ${e.message}")
                    C.RESULT_END_OF_INPUT
                }
            }

            bytesReadInChunk += bytesRead
            return bytesRead
        }

        override fun getUri(): Uri? = upstream.uri ?: currentUri

        override fun close() {
            try {
                upstream.close()
            } finally {
                currentUri = null
                originalDataSpec = null
            }
        }
    }
}
