package com.streame.tv.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SubtitleTranslationMgr"

class SubtitleTranslationManager(
    private var service: SubtitleTranslationService,
    internal var targetLanguage: String,
    private val scope: CoroutineScope
) {
    companion object {
        const val MOCK_MODE = false
        private const val BATCH_WINDOW_MS = 150L
    }

    var isEnabled: Boolean = false
    var removeHearingImpaired: Boolean = true

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null

    val translatedCount: Int get() = cache.size

    @Volatile var isTranslating: Boolean = false
        private set

    private val cache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var pendingCount = 0
    private var hideTranslatingJob: Job? = null

    private data class PendingItem(val text: String, val deferred: CompletableDeferred<String>)
    private val queue = Channel<PendingItem>(Channel.UNLIMITED)

    init {
        if (!MOCK_MODE) {
            scope.launch { processBatches() }
        }
    }

    fun updateService(apiKey: String, model: SubtitleAiModel) {
        service = SubtitleTranslationService(
            apiKeyProvider = { apiKey },
            modelProvider = { model }
        )
    }

    private suspend fun processBatches() {
        val batch = mutableListOf<PendingItem>()
        while (true) {
            val first = queue.receive()
            batch.add(first)

            val deadline = System.currentTimeMillis() + BATCH_WINDOW_MS
            while (batch.size < 40) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val next = withTimeoutOrNull(remaining) { queue.receive() } ?: break
                batch.add(next)
            }

            val texts = batch.map { it.text }
            val cacheHits = texts.count { cache.containsKey(it) }
            Log.d(TAG, "processBatches: batch=${texts.size} cacheHits=$cacheHits targetLanguage=$targetLanguage cacheSize=${cache.size}")
            val result = service.translateBatch(texts, targetLanguage)
            if (!result.success) {
                Log.w(TAG, "processBatches: translation failed error=${result.errorMessage}")
                onBatchResult?.invoke(false, result.errorMessage)
                batch.forEachIndexed { i, item ->
                    cache[item.text] = item.text
                    inFlight.remove(item.text)
                    item.deferred.complete(item.text)
                }
                batch.clear()
                delay(5_000L)
                continue
            }
            Log.d(TAG, "processBatches: success translated=${result.lines.size}")
            onBatchResult?.invoke(true, null)
            batch.forEachIndexed { i, item ->
                val translated = result.lines.getOrElse(i) { item.text }
                cache[item.text] = translated
                inFlight.remove(item.text)
                item.deferred.complete(translated)
            }
            batch.clear()
        }
    }

    fun getCached(text: String): String? = cache[text]

    suspend fun translate(text: String): String {
        cache[text]?.let { return it }
        inFlight[text]?.let { return it.await() }

        val deferred = CompletableDeferred<String>()
        inFlight[text] = deferred
        val depth = pendingCount++
        if (depth == 0) {
            isTranslating = true
            onTranslatingChanged?.invoke(true)
        }
        queue.send(PendingItem(text, deferred))
        return try {
            deferred.await()
        } finally {
            if (--pendingCount == 0) {
                hideTranslatingJob?.cancel()
                hideTranslatingJob = scope.launch {
                    delay(1500)
                    if (pendingCount == 0) {
                        isTranslating = false
                        onTranslatingChanged?.invoke(false)
                    }
                }
            }
        }
    }

    fun reset() {
        cache.clear()
        inFlight.clear()
        pendingCount = 0
        isTranslating = false
        onTranslatingChanged?.invoke(false)
    }

    suspend fun preTranslateWindow(texts: List<String>) {
        val uncached = texts.filter { !cache.containsKey(it) && !inFlight.containsKey(it) }
        if (uncached.isEmpty()) return
        val translatedEntries = mutableMapOf<String, String>()
        uncached.chunked(40).forEach { chunk ->
            val result = service.translateBatch(chunk, targetLanguage)
            if (result.success) {
                onBatchResult?.invoke(true, null)
                chunk.forEachIndexed { i, text ->
                    translatedEntries[text] = result.lines.getOrElse(i) { text }
                }
            } else {
                onBatchResult?.invoke(false, result.errorMessage)
                delay(5_000L)
                return
            }
        }
        cache.putAll(translatedEntries)
    }
}
