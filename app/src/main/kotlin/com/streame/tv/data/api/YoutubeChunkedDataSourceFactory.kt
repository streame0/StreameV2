package com.streame.tv.data.api

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * A DataSource.Factory that wraps a HttpDataSource and appends YouTube's
 * `&range=start-end` query parameter on each request. YouTube throttles (and
 * kills) connections that try to download full adaptive streams in one shot,
 * but honours chunked range-param requests at full speed.
 *
 * Only activates for googlevideo.com URLs; all other URLs pass through untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val upstreamFactory: HttpDataSource.Factory,
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : HttpDataSource.Factory {

    private val defaultRequestProperties = HttpDataSource.RequestProperties()

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
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty()
            isYouTubeStream = host.contains("googlevideo.com") || path.contains("googlevideo.com")

            if (!isYouTubeStream) {
                return upstream.open(dataSpec)
            }

            originalDataSpec = dataSpec
            currentChunkStart = dataSpec.position
            totalContentLength = dataSpec.length

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

            // Append &range=start-end to the URL (YouTube's own range param, not HTTP Range header)
            val rangedUri = spec.uri.buildUpon()
                .appendQueryParameter("range", "$currentChunkStart-$currentChunkEnd")
                .build()

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
                    Log.e(TAG, "416 Requested Range Not Satisfiable at $currentChunkStart-$currentChunkEnd", e)
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
                    return C.RESULT_END_OF_INPUT
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
            upstream.close()
            currentUri = null
            originalDataSpec = null
        }
    }
}
