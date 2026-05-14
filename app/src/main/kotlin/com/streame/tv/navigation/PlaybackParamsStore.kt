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
     * Retrieve params for the given playbackId.
     * Does NOT remove the entry — cleanup happens via [removeOnDispose].
     * Returns null if the ID doesn't exist (e.g. process death).
     */
    @Synchronized
    fun get(id: String): PlaybackParams? {
        return store[id]
    }

    /**
     * Remove a specific playbackId entry (called when destination is disposed).
     */
    @Synchronized
    fun removeOnDispose(id: String) {
        store.remove(id)
    }

    /**
     * Clear all stored params.
     */
    @Synchronized
    fun clear() {
        store.clear()
    }
}
