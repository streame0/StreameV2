package com.streame.tv.data.sync

import android.util.Log
import com.streame.tv.BuildConfig
import com.streame.tv.data.repository.AuthManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Cloud sync WebSocket connection status for the UI indicator. */
enum class CloudSyncStatus { CONNECTED, RECONNECTING, NOT_SIGNED_IN }

/**
 * Manages a Supabase Realtime WebSocket connection to receive instant
 * notifications when data changes on another device.
 *
 * Two channels are joined on the same socket:
 *
 * 1. `realtime:watch_history` — listens for INSERTs, UPDATEs, and DELETEs
 *    on `watch_progress` so the Home screen's Continue Watching row can
 *    refresh on other devices within seconds of a progress update.
 *
 * 2. `realtime:library` — listens for changes on `library` so watchlist
 *    additions/removals propagate instantly across devices.
 *
 * Features:
 * - Reuses a single OkHttpClient for WebSocket connections
 * - Periodic token refresh: reconnects with a fresh JWT every 30 minutes
 * - Exponential backoff on reconnect (5s → 10s → 20s → 40s cap)
 * - Exposes [syncStatusFlow] for the UI to show a connection indicator
 * - Periodic fallback polling every 45 seconds
 */
@Singleton
class RealtimeSyncManager @Inject constructor(
    private val startupSyncService: StartupSyncService,
    private val authManager: AuthManager
) {
    companion object {
        private const val TAG = "RealtimeSync"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 40_000L
        private const val PERIODIC_SYNC_INTERVAL_MS = 20_000L
        private const val TOKEN_REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private val msgRef = AtomicInteger(1)

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private var tokenRefreshJob: Job? = null

    private var currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS

    @Volatile
    private var lastPushTimestamp = 0L

    private val _syncStatusFlow = MutableStateFlow(CloudSyncStatus.NOT_SIGNED_IN)
    val syncStatusFlow: StateFlow<CloudSyncStatus> = _syncStatusFlow.asStateFlow()

    /** Mark that a push just happened, so incoming realtime echo can be ignored. */
    fun markPush() {
        lastPushTimestamp = System.currentTimeMillis()
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting realtime sync")
        _syncStatusFlow.value = CloudSyncStatus.RECONNECTING
        connectWebSocket()
        startPeriodicSync()
        startTokenRefreshLoop()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping realtime sync")
        webSocket?.close(1000, "App stopping")
        webSocket = null
        heartbeatJob?.cancel()
        periodicSyncJob?.cancel()
        reconnectJob?.cancel()
        tokenRefreshJob?.cancel()
        _syncStatusFlow.value = CloudSyncStatus.NOT_SIGNED_IN
    }

    // ── WebSocket Connection ────────────────────────────────────────

    private fun connectWebSocket() {
        if (!isRunning.get()) return

        if (!authManager.isAuthenticated) {
            Log.w(TAG, "Not logged in, skipping WebSocket connection")
            _syncStatusFlow.value = CloudSyncStatus.NOT_SIGNED_IN
            scheduleReconnect()
            return
        }

        _syncStatusFlow.value = CloudSyncStatus.RECONNECTING
        scope.launch {
            val accessToken = try {
                authManager.currentAccessToken()
            } catch (_: Exception) { null }

            if (accessToken.isNullOrBlank()) {
                Log.w(TAG, "No access token, skipping WebSocket connection")
                _syncStatusFlow.value = CloudSyncStatus.NOT_SIGNED_IN
                scheduleReconnect()
                return@launch
            }
            connectWebSocketWithToken(accessToken)
        }
    }

    private fun connectWebSocketWithToken(accessToken: String) {
        val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val wsUrl = supabaseUrl.replace("https://", "wss://").replace("http://", "ws://")
        val fullUrl = "$wsUrl/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_ANON_KEY}&vsn=1.0.0"

        val request = Request.Builder().url(fullUrl).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
                _syncStatusFlow.value = CloudSyncStatus.CONNECTED
                startHeartbeat()
                joinChannels(accessToken)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                _syncStatusFlow.value = CloudSyncStatus.RECONNECTING
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                if (isRunning.get()) {
                    _syncStatusFlow.value = CloudSyncStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        })
    }

    // ── Channel Subscription ────────────────────────────────────────

    private fun joinChannels(accessToken: String) {
        // Subscribe to watch_progress changes
        sendPhoenixMessage(
            topic = "realtime:watch_progress",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )

        // Subscribe to library changes
        sendPhoenixMessage(
            topic = "realtime:library",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )

        // Subscribe to watched_items changes
        sendPhoenixMessage(
            topic = "realtime:watched_items",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )

        // Subscribe to collections changes
        sendPhoenixMessage(
            topic = "realtime:collections",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )

        // Subscribe to home_catalog_settings changes
        sendPhoenixMessage(
            topic = "realtime:home_catalog_settings",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )

        // Subscribe to addons changes
        sendPhoenixMessage(
            topic = "realtime:addons",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )

        // Subscribe to profile_settings changes
        sendPhoenixMessage(
            topic = "realtime:profile_settings",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("self", true)
                    })
                })
            },
            token = accessToken
        )
    }

    // ── Message Handling ────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")
            val topic = json.optString("topic")

            when {
                event == "phx_reply" -> {
                    // Subscription confirmation — no action needed
                }
                event == "phx_error" -> {
                    Log.w(TAG, "Phoenix error on $topic: $text")
                }
                event == "presence_diff" || event == "presence_state" -> {
                    // Presence events — no action needed
                }
                topic.startsWith("realtime:") -> {
                    // Realtime data change notification
                    val payload = json.optJSONObject("payload")
                    if (payload != null) {
                        handleRealtimeEvent(topic, event, payload)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WebSocket message", e)
        }
    }

    private fun handleRealtimeEvent(topic: String, event: String, payload: JSONObject) {
        // Ignore events that are echoes of our own recent push
        if (System.currentTimeMillis() - lastPushTimestamp < 3_000L) {
            Log.d(TAG, "Ignoring realtime echo on $topic (recent push)")
            return
        }

        Log.d(TAG, "Realtime event on $topic: $event")
        scope.launch {
            when (topic) {
                "realtime:watch_progress" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull watch progress after realtime event", it) }
                }
                "realtime:library" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull library after realtime event", it) }
                }
                "realtime:watched_items" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull watched items after realtime event", it) }
                }
                "realtime:collections" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull collections after realtime event", it) }
                }
                "realtime:home_catalog_settings" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull home catalog settings after realtime event", it) }
                }
                "realtime:addons" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull addons after realtime event", it) }
                }
                "realtime:profile_settings" -> {
                    runCatching { startupSyncService.requestForegroundPull(force = true) }
                        .onFailure { Log.w(TAG, "Failed to pull profile settings after realtime event", it) }
                }
            }
        }
    }

    // ── Heartbeat ────────────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendPhoenixMessage(
                    topic = "phoenix",
                    event = "heartbeat",
                    payload = buildJsonObject {}
                )
            }
        }
    }

    // ── Periodic Sync ────────────────────────────────────────────────

    private fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_SYNC_INTERVAL_MS)
                if (authManager.isAuthenticated) {
                    runCatching { startupSyncService.requestForegroundPull() }
                        .onFailure { Log.w(TAG, "Periodic sync failed", it) }
                }
            }
        }
    }

    // ── Token Refresh ────────────────────────────────────────────────

    private fun startTokenRefreshLoop() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive) {
                delay(TOKEN_REFRESH_INTERVAL_MS)
                if (isRunning.get() && authManager.isAuthenticated) {
                    Log.d(TAG, "Refreshing WebSocket with new token")
                    webSocket?.close(1000, "Token refresh")
                    webSocket = null
                    connectWebSocket()
                }
            }
        }
    }

    // ── Reconnect ────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(currentReconnectDelay)
            currentReconnectDelay = (currentReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            connectWebSocket()
        }
    }

    // ── Phoenix Protocol Helpers ─────────────────────────────────────

    private fun sendPhoenixMessage(
        topic: String,
        event: String,
        payload: JSONObject,
        token: String? = null
    ) {
        val ref = msgRef.getAndIncrement().toString()
        val message = JSONObject().apply {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", ref)
            put("join_ref", ref)
        }
        if (token != null) {
            message.getJSONObject("payload").put("access_token", token)
        }
        webSocket?.send(message.toString())
    }

    private fun buildJsonObject(block: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply(block)
    }
}
