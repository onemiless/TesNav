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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class NavAssistV2ConnectionStatus {
    UNCONFIGURED,
    SCANNING,
    MULTIPLE_DEVICES,
    DISCOVERED,
    ONLINE,
    ERROR,
}

interface NavAssistV2HttpClient {
    fun post(endpoint: HttpUrl, body: String, signature: String)
    fun close()
}

internal class OkHttpNavAssistV2Client : NavAssistV2HttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun post(endpoint: HttpUrl, body: String, signature: String) {
        val request = Request.Builder()
            .url(endpoint)
            .header(NavAssistV2Protocol.SIGNATURE_HEADER, signature)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Optional NavAssist v2 transport, isolated from the legacy WebSocket. A blank
 * base URL selects authenticated UDP discovery; a valid explicit URL remains
 * available as a test override.
 */
class HttpNavAssistV2Exporter(
    private val config: NavAssistV2ExportConfig,
    private val stateProvider: () -> NavigationState?,
    private val endpointDiscovery: NavAssistV2EndpointDiscovery = UdpNavAssistV2EndpointDiscovery(),
    private val httpClient: NavAssistV2HttpClient = OkHttpNavAssistV2Client(),
    private val discoveryRetryMs: Long = DEFAULT_DISCOVERY_RETRY_MS,
) : NavigationDataExporter {
    private val mutableConnectionState = MutableStateFlow(ExportConnectionState.STOPPED)
    override val connectionState: StateFlow<ExportConnectionState> = mutableConnectionState
    private val mutableLastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = mutableLastError
    private val mutableStatus = MutableStateFlow(NavAssistV2ConnectionStatus.UNCONFIGURED)
    val status: StateFlow<NavAssistV2ConnectionStatus> = mutableStatus
    private val mutableResolvedEndpoint = MutableStateFlow<String?>(null)
    val resolvedEndpoint: StateFlow<String?> = mutableResolvedEndpoint

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session by lazy { NavAssistV2Session(validForMs = config.validForMs) }

    @Volatile private var running = false
    private var publisherJob: Job? = null

    override fun start() {
        if (running) return
        if (!config.isConfigured()) {
            mutableResolvedEndpoint.value = null
            if (config.hasValidTokenAndLifetime() && config.baseUrl.isNotBlank()) {
                mutableLastError.value = "NavAssist v2 URL 必须是有效的 http/https URL"
                mutableConnectionState.value = ExportConnectionState.ERROR
                mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
            } else {
                mutableLastError.value = null
                mutableConnectionState.value = ExportConnectionState.STOPPED
                mutableStatus.value = NavAssistV2ConnectionStatus.UNCONFIGURED
            }
            return
        }

        val explicitEndpoint = if (config.usesDiscovery()) null else snapshotEndpoint(config.baseUrl)
        if (!config.usesDiscovery() && explicitEndpoint == null) {
            mutableLastError.value = "NavAssist v2 URL 必须是有效的 http/https URL"
            mutableConnectionState.value = ExportConnectionState.ERROR
            mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
            return
        }

        running = true
        mutableConnectionState.value = ExportConnectionState.STARTING
        val intervalMs = config.intervalMs.coerceAtLeast(NavAssistV2Protocol.MIN_INTERVAL_MS)
        publisherJob = scope.launch {
            var endpoint = explicitEndpoint
            if (endpoint != null) publishDiscovered(endpoint)
            while (isActive && running) {
                if (endpoint == null) {
                    endpoint = discoverEndpoint()
                    if (!isActive || !running) break
                    if (endpoint == null) {
                        delay(discoveryRetryMs.coerceAtLeast(intervalMs))
                        continue
                    }
                }

                val startedAtNs = System.nanoTime()
                when (postLatest(endpoint)) {
                    PostResult.NO_STATE -> Unit
                    PostResult.SUCCESS -> {
                        mutableLastError.value = null
                        mutableConnectionState.value = ExportConnectionState.CONNECTED
                        mutableStatus.value = NavAssistV2ConnectionStatus.ONLINE
                    }
                    PostResult.FAILURE -> {
                        if (config.usesDiscovery()) {
                            endpoint = null
                            mutableResolvedEndpoint.value = null
                        }
                    }
                }
                val elapsedMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLISECOND
                delay((intervalMs - elapsedMs).coerceAtLeast(0L))
            }
        }
    }

    override fun stop() {
        running = false
        publisherJob?.cancel()
        publisherJob = null
        httpClient.close()
        scope.cancel()
        mutableResolvedEndpoint.value = null
        mutableConnectionState.value = ExportConnectionState.STOPPED
        mutableStatus.value = if (config.hasValidTokenAndLifetime()) {
            NavAssistV2ConnectionStatus.ERROR
        } else {
            NavAssistV2ConnectionStatus.UNCONFIGURED
        }
    }

    private fun discoverEndpoint(): HttpUrl? {
        mutableConnectionState.value = ExportConnectionState.STARTING
        mutableStatus.value = NavAssistV2ConnectionStatus.SCANNING
        return when (val result = endpointDiscovery.discover(config.token)) {
            is NavAssistV2DiscoveryResult.Found -> discoveryEndpoint(result.sourceHost)?.also(::publishDiscovered)
                ?: failDiscovery("C3XL 返回了无效地址")
            NavAssistV2DiscoveryResult.NotFound -> null
            NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts -> {
                mutableLastError.value = "发现多个已认证 C3XL，已拒绝自动选择"
                mutableConnectionState.value = ExportConnectionState.ERROR
                mutableStatus.value = NavAssistV2ConnectionStatus.MULTIPLE_DEVICES
                null
            }
            is NavAssistV2DiscoveryResult.Failed -> failDiscovery(result.reason)
        }
    }

    private fun failDiscovery(reason: String): HttpUrl? {
        mutableLastError.value = reason
        mutableConnectionState.value = ExportConnectionState.ERROR
        mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
        return null
    }

    private fun publishDiscovered(endpoint: HttpUrl) {
        mutableLastError.value = null
        mutableResolvedEndpoint.value = endpoint.toString()
        mutableConnectionState.value = ExportConnectionState.STARTING
        mutableStatus.value = NavAssistV2ConnectionStatus.DISCOVERED
    }

    private fun postLatest(endpoint: HttpUrl): PostResult {
        val state = stateProvider() ?: return PostResult.NO_STATE
        return runCatching {
            val snapshot = session.nextSnapshot(state, System.currentTimeMillis())
            val body = CanonicalJson.encode(snapshot)
            val signature = HmacSha256.signLowerHex(body, config.token)
            httpClient.post(endpoint, body, signature)
        }.fold(
            onSuccess = { PostResult.SUCCESS },
            onFailure = { error ->
                mutableLastError.value = "NavAssist HTTP 发送失败：${error.javaClass.simpleName}"
                mutableConnectionState.value = ExportConnectionState.ERROR
                mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
                PostResult.FAILURE
            },
        )
    }

    internal fun snapshotEndpoint(baseUrl: String): HttpUrl? {
        val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        return parsed.resolve(NavAssistV2Protocol.ENDPOINT_PATH)
    }

    internal fun discoveryEndpoint(sourceHost: String): HttpUrl? = runCatching {
        HttpUrl.Builder()
            .scheme("http")
            .host(sourceHost)
            .port(NavAssistV2Discovery.SNAPSHOT_PORT)
            .addPathSegments(NavAssistV2Protocol.ENDPOINT_PATH.removePrefix("/"))
            .build()
    }.getOrNull()

    private enum class PostResult { NO_STATE, SUCCESS, FAILURE }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_DISCOVERY_RETRY_MS = 1_000L
    }
}
