package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationState
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String)
    fun close()
}

internal class OkHttpNavAssistV2Client : NavAssistV2HttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
        val request = Request.Builder()
            .url(endpoint)
            .header(NavAssistV3Auth.KEY_ID_HEADER, appKeyId)
            .header(NavAssistV3Auth.SIGNATURE_HEADER, signature)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = response.body?.string()
                    ?.let(HTTP_REASON_PATTERN::find)
                    ?.groupValues
                    ?.getOrNull(1)
                error("HTTP ${response.code}${reason?.let { " ($it)" } ?: ""}")
            }
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val HTTP_REASON_PATTERN = Regex("\\\"reason\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}

/**
 * Optional NavAssist v2 transport, isolated from the legacy WebSocket. A blank
 * base URL selects authenticated UDP discovery; a valid explicit URL remains
 * available as a test override.
 */
internal class HttpNavAssistV2Exporter(
    private val config: NavAssistV2ExportConfig,
    private val stateProvider: () -> NavigationState?,
    private val identity: NavAssistSigningIdentity,
    private val endpointDiscovery: NavAssistV2EndpointDiscovery,
    private val pinnedDeviceProvider: () -> PinnedNavAssistDevice?,
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
            if (config.hasValidLifetime() && config.baseUrl.isNotBlank()) {
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

        val explicitEndpoint = if (config.usesDiscovery()) null else {
            val pinned = pinnedDeviceProvider()
            snapshotEndpoint(config.baseUrl)?.let { url -> pinned?.let { ResolvedNavAssistEndpoint(url, it.deviceId) } }
        }
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
            var consecutivePostFailures = 0
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
                        consecutivePostFailures = 0
                        mutableLastError.value = null
                        mutableConnectionState.value = ExportConnectionState.CONNECTED
                        mutableStatus.value = NavAssistV2ConnectionStatus.ONLINE
                    }
                    PostResult.FAILURE -> {
                        consecutivePostFailures += 1
                        // A single missed response is common on phone hotspots. Keep the authenticated
                        // endpoint for one direct retry; rediscover after repeated failures so a Wi-Fi
                        // change can still move the session to the C3XL's new address.
                        if (config.usesDiscovery() && consecutivePostFailures >= POST_FAILURES_BEFORE_REDISCOVERY) {
                            endpoint = null
                            consecutivePostFailures = 0
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
        mutableStatus.value = if (config.hasValidLifetime()) {
            NavAssistV2ConnectionStatus.ERROR
        } else {
            NavAssistV2ConnectionStatus.UNCONFIGURED
        }
    }

    private fun discoverEndpoint(): ResolvedNavAssistEndpoint? {
        mutableConnectionState.value = ExportConnectionState.STARTING
        mutableStatus.value = NavAssistV2ConnectionStatus.SCANNING
        return when (val result = endpointDiscovery.discover()) {
            is NavAssistV2DiscoveryResult.Found -> discoveryEndpoint(result.sourceHost)
                ?.let { ResolvedNavAssistEndpoint(it, result.deviceId) }
                ?.also(::publishDiscovered)
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

    private fun failDiscovery(reason: String): ResolvedNavAssistEndpoint? {
        mutableLastError.value = reason
        mutableConnectionState.value = ExportConnectionState.ERROR
        mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
        return null
    }

    private fun publishDiscovered(endpoint: ResolvedNavAssistEndpoint) {
        mutableLastError.value = null
        mutableResolvedEndpoint.value = endpoint.url.toString()
        mutableConnectionState.value = ExportConnectionState.STARTING
        mutableStatus.value = NavAssistV2ConnectionStatus.DISCOVERED
    }

    private fun postLatest(endpoint: ResolvedNavAssistEndpoint): PostResult {
        val state = stateProvider() ?: return PostResult.NO_STATE
        return runCatching {
            val snapshot = session.nextSnapshot(state, System.currentTimeMillis())
            val body = CanonicalJson.encode(snapshot)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val signature = identity.sign(
                NavAssistV3Auth.snapshotSignatureMaterial(
                    endpoint.deviceId, identity.keyId, NavAssistV2Protocol.ENDPOINT_PATH, bodyBytes,
                ),
            )
            httpClient.post(endpoint.url, body, identity.keyId, signature)
        }.fold(
            onSuccess = { PostResult.SUCCESS },
            onFailure = { error ->
                mutableLastError.value = describeHttpFailure(endpoint.url, error)
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

    private fun describeHttpFailure(endpoint: HttpUrl, error: Throwable): String {
        val target = "${endpoint.host}:${endpoint.port}"
        return when (error) {
            is SocketTimeoutException -> "NavAssist HTTP 超时：$target（热点延迟或 C3XL 繁忙）"
            is ConnectException -> "NavAssist 无法连接：$target"
            is UnknownHostException -> "NavAssist 地址无效：${endpoint.host}"
            else -> "NavAssist HTTP 发送失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private enum class PostResult { NO_STATE, SUCCESS, FAILURE }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_DISCOVERY_RETRY_MS = 1_000L
        const val POST_FAILURES_BEFORE_REDISCOVERY = 2
    }
}

internal data class ResolvedNavAssistEndpoint(
    val url: HttpUrl,
    val deviceId: String,
)
