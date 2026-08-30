package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationState
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
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Optional NavAssist v2 transport. It is separate from the legacy WebSocket and
 * cannot start unless both the destination and HMAC secret are configured.
 */
class HttpNavAssistV2Exporter(
    private val config: NavAssistV2ExportConfig,
    private val stateProvider: () -> NavigationState?,
) : NavigationDataExporter {
    private val mutableConnectionState = MutableStateFlow(ExportConnectionState.STOPPED)
    override val connectionState: StateFlow<ExportConnectionState> = mutableConnectionState
    private val mutableLastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = mutableLastError

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()
    private val session by lazy { NavAssistV2Session(validForMs = config.validForMs) }

    @Volatile private var running = false
    @Volatile private var startedOnce = false
    private var publisherJob: Job? = null

    override fun start() {
        if (!config.isConfigured() || running) return
        val endpoint = snapshotEndpoint(config.baseUrl)
        if (endpoint == null) {
            mutableLastError.value = "NavAssist v2 URL 必须是有效的 http/https URL"
            mutableConnectionState.value = ExportConnectionState.ERROR
            return
        }

        running = true
        startedOnce = true
        mutableConnectionState.value = ExportConnectionState.STARTING
        val intervalMs = config.intervalMs.coerceAtLeast(NavAssistV2Protocol.MIN_INTERVAL_MS)
        publisherJob = scope.launch {
            while (isActive && running) {
                val startedAtNs = System.nanoTime()
                postLatest(endpoint)
                val elapsedMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLISECOND
                delay((intervalMs - elapsedMs).coerceAtLeast(0L))
            }
        }
    }

    override fun stop() {
        running = false
        publisherJob?.cancel()
        publisherJob = null
        if (startedOnce) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        scope.cancel()
        mutableConnectionState.value = ExportConnectionState.STOPPED
    }

    private fun postLatest(endpoint: HttpUrl) {
        val state = stateProvider() ?: return
        runCatching {
            val snapshot = session.nextSnapshot(state, System.currentTimeMillis())
            val body = CanonicalJson.encode(snapshot)
            val signature = HmacSha256.signLowerHex(body, config.token)
            val request = Request.Builder()
                .url(endpoint)
                .header(NavAssistV2Protocol.SIGNATURE_HEADER, signature)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
            }
        }.onSuccess {
            mutableLastError.value = null
            mutableConnectionState.value = ExportConnectionState.CONNECTED
        }.onFailure { error ->
            mutableLastError.value = error.message ?: error.javaClass.simpleName
            mutableConnectionState.value = ExportConnectionState.ERROR
        }
    }

    internal fun snapshotEndpoint(baseUrl: String): HttpUrl? {
        val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        return parsed.resolve(NavAssistV2Protocol.ENDPOINT_PATH)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
