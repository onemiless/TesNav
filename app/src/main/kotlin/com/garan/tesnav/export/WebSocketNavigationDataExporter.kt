package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationState
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class NavigationWireEnvelope(
    val schemaVersion: Int = 1,
    val state: NavigationState,
)

/** Persistent WebSocket transport that publishes the newest snapshot every 200 ms. */
class WebSocketNavigationDataExporter(
    private val config: ExportConfig,
    private val stateProvider: () -> NavigationState?,
) : NavigationDataExporter {
    private val mutableConnectionState = MutableStateFlow(ExportConnectionState.STOPPED)
    override val connectionState: StateFlow<ExportConnectionState> = mutableConnectionState
    private val mutableLastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = mutableLastError

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var publisherJob: Job? = null

    override fun start() {
        if (!config.enabled || config.webSocketUrl.isBlank() || running) return
        running = true
        connect()
    }

    override fun stop() {
        running = false
        reconnectJob?.cancel()
        reconnectJob = null
        publisherJob?.cancel()
        publisherJob = null
        socket?.close(1000, "service stopped")
        socket = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        scope.cancel()
        mutableConnectionState.value = ExportConnectionState.STOPPED
    }

    private fun connect() {
        if (!running) return
        mutableConnectionState.value = ExportConnectionState.STARTING
        val request = Request.Builder().url(config.webSocketUrl).apply {
            if (config.apiToken.isNotBlank()) header("Authorization", "Bearer ${config.apiToken}")
        }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                mutableConnectionState.value = ExportConnectionState.CONNECTED
                mutableLastError.value = null
                sendLatestSnapshot()
                startFixedRatePublisher()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasCurrent = socket === webSocket
                if (wasCurrent) socket = null
                if (running && wasCurrent) scheduleReconnect("连接已关闭：$code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val wasCurrent = socket === webSocket
                if (wasCurrent) socket = null
                if (running && wasCurrent) scheduleReconnect(t.message ?: t.javaClass.simpleName)
            }
        })
    }

    @Synchronized
    private fun sendLatestSnapshot() {
        val state = stateProvider() ?: return
        val sent = socket?.send(gson.toJson(NavigationWireEnvelope(state = state))) == true
        if (sent) mutableLastError.value = null
        else if (running) scheduleReconnect("WebSocket 发送失败")
    }

    @Synchronized
    private fun startFixedRatePublisher() {
        publisherJob?.cancel()
        publisherJob = scope.launch {
            val interval = config.intervalMs.coerceAtLeast(200L)
            while (isActive && running) {
                delay(interval)
                if (mutableConnectionState.value == ExportConnectionState.CONNECTED) sendLatestSnapshot()
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect(message: String) {
        publisherJob?.cancel()
        publisherJob = null
        mutableLastError.value = message
        mutableConnectionState.value = ExportConnectionState.ERROR
        if (reconnectJob?.isActive == true || !running) return
        reconnectJob = scope.launch {
            var waitMs = 1_000L
            while (isActive && running && mutableConnectionState.value != ExportConnectionState.CONNECTED) {
                delay(waitMs)
                connect()
                delay(1_000L)
                waitMs = (waitMs * 2).coerceAtMost(10_000L)
            }
        }
    }
}
