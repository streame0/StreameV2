package com.streame.tv.navigation

import com.streame.tv.data.model.MediaType

/**
 * Holds all playback parameters for a single player session.
 * Stored in [PlaybackParamsStore] and referenced by a short [playbackId]
 * in the navigation route, avoiding 10+ URL-encoded parameters.
 */
data class PlaybackParams(
    val mediaType: MediaType,
    val mediaId: Int,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val imdbId: String? = null,
    val streamUrl: String? = null,
    val preferredAddonId: String? = null,
    val preferredSourceName: String? = null,
    val preferredBingeGroup: String? = null,
    val startPositionMs: Long? = null
)

/**
 * Temporary in-memory store for playback parameters.
 * Navigation passes only a `playbackId` string; the Player screen
 * reads the full params from this store. Entries are auto-cleaned
 * after retrieval to avoid memory leaks.
 *
 * Thread-safe via synchronized access.
 */
object PlaybackParamsStore {
    private val store = mutableMapOf<String, PlaybackParams>()
    private var counter = 0L

    /**
     * Store params and return a unique playbackId.
     */
    @Synchronized
    fun put(params: PlaybackParams): String {
        val id = "pb_${++counter}"
        store[id] = params
        return id
    }

    /**
     * Retrieve and remove params for the given playbackId.
     * Returns null if the ID doesn't exist (e.g. process death).
     */
    @Synchronized
    fun consume(id: String): PlaybackParams? {
        return store.remove(id)
    }

    /**
     * Peek at params without removing (used for process-death recovery).
     */
    @Synchronized
    fun peek(id: String): PlaybackParams? {
        return store[id]
    }

    /**
     * Clear all stored params.
     */
    @Synchronized
    fun clear() {
        store.clear()
    }
}
