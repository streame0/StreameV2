package com.streame.tv.data.repository

import android.content.Context
import android.util.Log
import com.streame.tv.data.local.DownloadDao
import com.streame.tv.data.local.DownloadEntity
import com.streame.tv.data.model.MediaItem
import com.streame.tv.data.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages downloaded content for offline playback.
 * Handles enqueueing downloads, tracking progress, and file management.
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DownloadRepo"
        /** Subdirectory under app's external files dir for downloads */
        private const val DOWNLOAD_DIR = "downloads"
    }

    /** Directory where downloaded files are stored */
    val downloadDir: File
        get() = File(context.getExternalFilesDir(null), DOWNLOAD_DIR).also { it.mkdirs() }

    /** Observe completed downloads for the current profile */
    fun completedDownloadsFlow(): Flow<List<DownloadEntity>> {
        val profileId = profileManager.currentProfileId.value
        return downloadDao.getCompletedForProfile(profileId)
    }

    /** Get all downloads for the current profile */
    suspend fun getAllDownloads(): List<DownloadEntity> {
        return downloadDao.getAllForProfile(profileManager.currentProfileId.value)
    }

    /** Check if content is already downloaded */
    suspend fun isDownloaded(tmdbId: Int, mediaType: MediaType, seasonNumber: Int? = null, episodeNumber: Int? = null): Boolean {
        val existing = downloadDao.findByContent(
            profileManager.currentProfileId.value,
            tmdbId,
            mediaType.name.lowercase(),
            seasonNumber,
            episodeNumber
        )
        return existing?.status == "completed"
    }

    /**
     * Enqueue a download for a movie or episode.
     * Returns the download ID, or null if already queued/completed.
     */
    suspend fun enqueue(
        item: MediaItem,
        sourceUrl: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null
    ): Long? {
        val profileId = profileManager.currentProfileId.value
        val mediaType = if (item.mediaType == MediaType.MOVIE) "movie" else "tv"

        // Check for existing download
        val existing = downloadDao.findByContent(profileId, item.id, mediaType, seasonNumber, episodeNumber)
        if (existing != null && existing.status in listOf("queued", "downloading", "completed")) {
            Log.w(TAG, "Download already exists for ${item.title} (status=${existing.status})")
            return null
        }

        // Create local path
        val safeName = (item.title ?: "unknown").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = if (seasonNumber != null && episodeNumber != null) {
            "${safeName}_S${String.format("%02d", seasonNumber)}E${String.format("%02d", episodeNumber)}.mp4"
        } else {
            "${safeName}.mp4"
        }
        val localPath = File(downloadDir, fileName).absolutePath

        val entity = DownloadEntity(
            profileId = profileId,
            tmdbId = item.id,
            mediaType = mediaType,
            title = item.title ?: "Unknown",
            posterPath = item.image,
            backdropPath = item.backdrop,
            overview = item.overview,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            sourceUrl = sourceUrl,
            localPath = localPath,
            status = "queued"
        )

        val id = downloadDao.upsert(entity)
        Log.i(TAG, "Enqueued download: ${item.title} (id=$id)")
        return id
    }

    /** Update download progress (called by DownloadWorker) */
    suspend fun updateProgress(id: Long, status: String, progress: Int) {
        downloadDao.updateProgress(id, status, progress)
    }

    /** Mark download as completed */
    suspend fun markCompleted(id: Long, fileSize: Long) {
        downloadDao.markCompleted(id, fileSize)
    }

    /** Mark download as failed */
    suspend fun markFailed(id: Long, error: String) {
        downloadDao.updateStatus(id, "failed", error)
    }

    /** Delete a download and its local file */
    suspend fun deleteDownload(id: Long) {
        val entity = downloadDao.getById(id) ?: return
        val file = File(entity.localPath)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Deleted file: ${entity.localPath}")
        }
        downloadDao.delete(id)
    }

    /** Get the local file for a completed download, or null */
    suspend fun getLocalFile(tmdbId: Int, mediaType: MediaType, seasonNumber: Int? = null, episodeNumber: Int? = null): File? {
        val entity = downloadDao.findByContent(
            profileManager.currentProfileId.value,
            tmdbId,
            mediaType.name.lowercase(),
            seasonNumber,
            episodeNumber
        ) ?: return null
        if (entity.status != "completed") return null
        val file = File(entity.localPath)
        return if (file.exists()) file else null
    }

    /** Get total storage used by downloads for current profile in bytes */
    suspend fun totalStorageBytes(): Long {
        return getAllDownloads()
            .filter { it.status == "completed" }
            .sumOf { it.fileSizeBytes }
    }
}
