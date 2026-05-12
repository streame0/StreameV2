package com.streame.tv.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streame.tv.data.repository.DownloadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager worker that downloads a video file for offline playback.
 * Input data must contain:
 *   - "download_id": Long — the DownloadEntity row ID
 *
 * Progress is reported as an integer 0-100 via ProgressObserver pattern
 * and the downloads table is updated in real time.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val downloadRepository: DownloadRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val BUFFER_SIZE = 8 * 1024 // 8KB
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1)
        if (downloadId == -1L) {
            Log.e(TAG, "No download_id provided")
            return@withContext Result.failure()
        }

        val entity = downloadRepository.getAllDownloads()
            .firstOrNull { it.id == downloadId }
            ?: run {
                Log.e(TAG, "Download $downloadId not found")
                return@withContext Result.failure()
            }

        if (entity.status == "completed") {
            Log.i(TAG, "Download $downloadId already completed")
            return@withContext Result.success()
        }

        try {
            downloadRepository.updateProgress(downloadId, "downloading", 0)

            val url = URL(entity.sourceUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            // Follow redirects
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode for ${entity.sourceUrl}")
            }

            val contentLength = connection.contentLengthLong
            val targetFile = File(entity.localPath)
            targetFile.parentFile?.mkdirs()

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalRead = 0L
                    var lastProgress = 0

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read

                        // Update progress
                        if (contentLength > 0) {
                            val progress = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                downloadRepository.updateProgress(downloadId, "downloading", progress)
                            }
                        }
                    }
                }
            }

            val fileSize = targetFile.length()
            downloadRepository.markCompleted(downloadId, fileSize)
            Log.i(TAG, "Download completed: ${entity.title} ($fileSize bytes)")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${entity.title}", e)
            downloadRepository.markFailed(downloadId, e.message ?: "Unknown error")
            // Retry up to 3 times with backoff
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
