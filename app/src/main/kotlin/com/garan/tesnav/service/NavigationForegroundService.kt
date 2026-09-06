package com.garan.tesnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.model.AMapNaviPath
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.MainActivity
import com.garan.tesnav.config.AmapConfiguration
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.data.CommaStateStore
import com.garan.tesnav.data.NavigationRepository
import com.garan.tesnav.data.NavigationStateStore
import com.garan.tesnav.export.ExportConfig
import com.garan.tesnav.export.ExportConnectionState
import com.garan.tesnav.export.AndroidKeystoreNavAssistIdentity
import com.garan.tesnav.export.HttpNavAssistV2Exporter
import com.garan.tesnav.export.NavAssistPairingStore
import com.garan.tesnav.export.NavAssistV2ConnectionStatus
import com.garan.tesnav.export.NavAssistV2ExportConfig
import com.garan.tesnav.export.UdpNavAssistV2EndpointDiscovery
import com.garan.tesnav.export.WebSocketNavigationDataExporter
import com.garan.tesnav.homeassistant.HomeAssistantConnectionState
import com.garan.tesnav.homeassistant.HomeAssistantNavigationClient
import com.garan.tesnav.homeassistant.TeslaNavigationDestination
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.model.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Keeps the navigation engine, state callbacks, and WebSocket alive in background. */
class NavigationForegroundService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var appliedApiKey: String? = null

    lateinit var stateStore: NavigationStateStore
        private set
    lateinit var commaStateStore: CommaStateStore
        private set
    lateinit var exporter: WebSocketNavigationDataExporter
        private set
    private lateinit var navAssistV2Exporter: HttpNavAssistV2Exporter
        private set
    lateinit var homeAssistantClient: HomeAssistantNavigationClient
        private set
    private lateinit var repository: NavigationRepository
    private val exporterObservationJobs = mutableListOf<Job>()
    private var legacyExportEnabled = false
    private lateinit var navAssistIdentity: AndroidKeystoreNavAssistIdentity
    private lateinit var navAssistPairingStore: NavAssistPairingStore

    private val mutableCommaConnectionState = MutableStateFlow(ExportConnectionState.STOPPED)
    val commaConnectionState: StateFlow<ExportConnectionState> = mutableCommaConnectionState.asStateFlow()
    private val mutableCommaLastError = MutableStateFlow<String?>(null)
    val commaLastError: StateFlow<String?> = mutableCommaLastError.asStateFlow()
    private val mutableNavAssistV2Status = MutableStateFlow(NavAssistV2ConnectionStatus.UNCONFIGURED)
    val navAssistV2Status: StateFlow<NavAssistV2ConnectionStatus> = mutableNavAssistV2Status.asStateFlow()
    private val mutableNavAssistV2ResolvedEndpoint = MutableStateFlow<String?>(null)
    val navAssistV2ResolvedEndpoint: StateFlow<String?> = mutableNavAssistV2ResolvedEndpoint.asStateFlow()
    private val mutableNavAssistV2LastError = MutableStateFlow<String?>(null)
    val navAssistV2LastError: StateFlow<String?> = mutableNavAssistV2LastError.asStateFlow()

    private val mutableTeslaSyncEnabled = MutableStateFlow(false)
    val teslaSyncEnabled: StateFlow<Boolean> = mutableTeslaSyncEnabled.asStateFlow()

    private var commaStateReady = false
    private var lastTeslaNavActive: Boolean? = null
    private var routeRequestDestination: TeslaNavigationDestination? = null
    private var activeTeslaDestination: TeslaNavigationDestination? = null
    private var failedTeslaDestination: TeslaNavigationDestination? = null
    private var lastNotificationContent: String? = null
    private var lastNavAssistDiagnosticKey: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): NavigationForegroundService = this@NavigationForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground("正在启动导航服务")
        if (!AmapConfiguration.prepare(applicationContext)) {
            stopSelf()
            return
        }
        appliedApiKey = AmapConfiguration.effectiveKey(applicationContext)
        acquireWakeLock()

        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)

        stateStore = NavigationStateStore()
        commaStateStore = CommaStateStore()
        homeAssistantClient = HomeAssistantNavigationClient()
        mutableTeslaSyncEnabled.value = preferences().getBoolean(HA_SYNC_ENABLED, false)
        // v3 uses an Android Keystore identity; remove the obsolete shared-secret preference on upgrade.
        getSharedPreferences("navassist_v2", MODE_PRIVATE).edit().clear().apply()
        navAssistIdentity = AndroidKeystoreNavAssistIdentity.loadOrCreate(applicationContext)
        navAssistPairingStore = NavAssistPairingStore(applicationContext)
        repository = NavigationRepository(applicationContext, stateStore) { path ->
            if (!legacyExportEnabled || !::exporter.isInitialized) return@NavigationRepository
            if (path == null) {
                exporter.clearRoute()
            } else {
                exporter.publishRoute(
                    pathId = path.pathid,
                    totalDistanceMeters = path.allLength,
                    points = path.coordList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                )
            }
        }
        repository.initialize()
        rebuildDataExporters()
        observeRuntime()
        if (mutableTeslaSyncEnabled.value) startHomeAssistant()
    }

    override fun onBind(intent: Intent?): IBinder? = if (::repository.isInitialized) binder else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::repository.isInitialized) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification()
        return START_STICKY
    }

    fun planRoute(latitude: Double, longitude: Double): Boolean = repository.planRoute(latitude, longitude)
    fun selectRoute(routeId: Int): Boolean = repository.selectRoute(routeId)
    fun startRealtime(): Boolean = repository.startRealtime().also { accepted ->
        if (accepted && ::navAssistV2Exporter.isInitialized) navAssistV2Exporter.requestRediscovery()
    }
    fun startSimulation(): Boolean = repository.startSimulation().also { accepted ->
        if (accepted && ::navAssistV2Exporter.isInitialized) navAssistV2Exporter.requestRediscovery()
    }
    fun pauseSimulation(): Boolean = repository.pauseSimulation()
    fun resumeSimulation(): Boolean = repository.resumeSimulation()
    fun setSpeechEnabled(enabled: Boolean): Boolean = repository.setSpeechEnabled(enabled)
    fun stopNavigation() = repository.stopNavigation()
    fun refreshAMapConfiguration() {
        val key = AmapConfiguration.effectiveKey(applicationContext) ?: return
        if (key == appliedApiKey || stateStore.state.value.navigationMode != NavigationMode.IDLE) return
        repository.release()
        AmapConfiguration.prepare(applicationContext)
        appliedApiKey = key
        repository.initialize()
    }
    fun currentPath(): AMapNaviPath? = repository.currentPath()

    fun navAssistPairedDeviceId(): String? = navAssistPairingStore.pinnedDevice()?.deviceId

    fun clearNavAssistPairing(): String? {
        if (!navAssistPairingStore.clear()) return "清除自动配对失败"
        rebuildDataExporters()
        return null
    }

    fun setTeslaSyncEnabled(enabled: Boolean) {
        if (mutableTeslaSyncEnabled.value == enabled) return
        mutableTeslaSyncEnabled.value = enabled
        preferences().edit().putBoolean(HA_SYNC_ENABLED, enabled).apply()
        resetTeslaSyncTracking()
        if (enabled) startHomeAssistant() else homeAssistantClient.stop()
    }

    override fun onDestroy() {
        exporterObservationJobs.forEach(Job::cancel)
        exporterObservationJobs.clear()
        if (::homeAssistantClient.isInitialized) homeAssistantClient.release()
        if (::navAssistV2Exporter.isInitialized) navAssistV2Exporter.stop()
        if (::exporter.isInitialized) exporter.stop()
        if (::repository.isInitialized) repository.release()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Rebuilds both exporters so a token change takes effect without reinstalling or restarting the app. */
    private fun rebuildDataExporters() {
        exporterObservationJobs.forEach(Job::cancel)
        exporterObservationJobs.clear()
        if (::navAssistV2Exporter.isInitialized) navAssistV2Exporter.stop()
        if (::exporter.isInitialized) exporter.stop()

        val navAssistV2Config = NavAssistV2ExportConfig(
            baseUrl = BuildConfig.NAV_ASSIST_V2_URL,
            intervalMs = BuildConfig.NAV_ASSIST_V2_INTERVAL_MS,
        )
        legacyExportEnabled = BuildConfig.EXPORT_ENABLED && !navAssistV2Config.isConfigured()
        exporter = WebSocketNavigationDataExporter(
            config = ExportConfig(
                // Discovery or a valid explicit v2 endpoint owns the phone-to-C3 link.
                enabled = legacyExportEnabled,
                webSocketUrl = BuildConfig.WEBSOCKET_URL,
                apiToken = BuildConfig.API_TOKEN,
                intervalMs = BuildConfig.EXPORT_INTERVAL_MS,
            ),
            stateProvider = { stateStore.state.value },
            onCommaState = commaStateStore::set,
        )
        navAssistV2Exporter = HttpNavAssistV2Exporter(
            config = navAssistV2Config,
            stateProvider = { stateStore.state.value },
            identity = navAssistIdentity,
            endpointDiscovery = UdpNavAssistV2EndpointDiscovery(navAssistIdentity, navAssistPairingStore),
            pinnedDeviceProvider = navAssistPairingStore::pinnedDevice,
            useUnauthenticatedUdp = true,
        )
        observeExporterInstances()
        exporter.start()
        navAssistV2Exporter.start()
    }

    private fun observeExporterInstances() {
        val observedLegacyExporter = exporter
        val observedNavAssistExporter = navAssistV2Exporter
        mutableCommaConnectionState.value = observedLegacyExporter.connectionState.value
        mutableCommaLastError.value = observedLegacyExporter.lastError.value
        mutableNavAssistV2Status.value = observedNavAssistExporter.status.value
        mutableNavAssistV2ResolvedEndpoint.value = observedNavAssistExporter.resolvedEndpoint.value
        mutableNavAssistV2LastError.value = observedNavAssistExporter.lastError.value
        exporterObservationJobs += scope.launch {
            observedLegacyExporter.connectionState.collect { mutableCommaConnectionState.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedLegacyExporter.lastError.collect { mutableCommaLastError.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.status.collect { mutableNavAssistV2Status.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.resolvedEndpoint.collect { mutableNavAssistV2ResolvedEndpoint.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.lastError.collect { mutableNavAssistV2LastError.value = it }
        }
    }

    private fun observeRuntime() {
        scope.launch {
            stateStore.state.collect { state ->
                logNavAssistDiagnostics(state)
                handleTeslaRouteProgress(state)
                tryStartTeslaNavigation()
                updateNotification()
            }
        }
        scope.launch {
            commaConnectionState.collect { state ->
                if (state != ExportConnectionState.CONNECTED) {
                    commaStateReady = false
                    lastTeslaNavActive = null
                }
                updateNotification()
            }
        }
        scope.launch {
            commaStateStore.state.collect { state ->
                if (state.timestampMs == 0L || commaConnectionState.value != ExportConnectionState.CONNECTED) return@collect
                commaStateReady = true
                handleTeslaNavigationActive(state.isTeslaNavActive)
            }
        }
        scope.launch {
            homeAssistantClient.navigationState.collect {
                tryStartTeslaNavigation()
            }
        }
        scope.launch {
            homeAssistantClient.connectionState.collect {
                tryStartTeslaNavigation()
                updateNotification()
            }
        }
        scope.launch {
            navAssistV2Status.collect { updateNotification() }
        }
    }

    private fun logNavAssistDiagnostics(state: NavigationState) {
        val distanceBucket = state.nextTurnDistanceMeters?.div(10)?.times(10)
        val key = "${state.navigationMode}:${state.routePlanned}:${state.routeMatched}:" +
            "${state.maneuver}:${state.guidanceStepIndex}:$distanceBucket:${state.accuracy?.toInt()}"
        if (key == lastNavAssistDiagnosticKey) return
        lastNavAssistDiagnosticKey = key
        val nowMs = System.currentTimeMillis()
        val locationAgeMs = state.locationObservedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
        val guidanceAgeMs = state.guidanceObservedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }
        Log.i(
            NAVASSIST_DIAGNOSTIC_TAG,
            "mode=${state.navigationMode} planned=${state.routePlanned} matched=${state.routeMatched} " +
                "maneuver=${state.maneuver} step=${state.guidanceStepIndex} " +
                "distanceM=${state.nextTurnDistanceMeters} accuracyM=${state.accuracy} " +
                "locationAgeMs=$locationAgeMs guidanceAgeMs=$guidanceAgeMs",
        )
    }

    private fun startHomeAssistant() {
        homeAssistantClient.start(BuildConfig.HOME_ASSISTANT_URL, BuildConfig.HOME_ASSISTANT_TOKEN)
    }

    private fun handleTeslaNavigationActive(active: Boolean) {
        if (!mutableTeslaSyncEnabled.value) return
        if (lastTeslaNavActive == active) {
            if (active) tryStartTeslaNavigation()
            return
        }
        lastTeslaNavActive = active
        if (active) {
            failedTeslaDestination = null
            handleTeslaRouteProgress(stateStore.state.value)
            tryStartTeslaNavigation()
        } else {
            routeRequestDestination = null
            activeTeslaDestination = null
            failedTeslaDestination = null
            repository.stopNavigation()
        }
    }

    private fun tryStartTeslaNavigation() {
        if (!mutableTeslaSyncEnabled.value || !commaStateReady) return
        if (commaConnectionState.value != ExportConnectionState.CONNECTED) return
        if (lastTeslaNavActive != true) return
        if (homeAssistantClient.connectionState.value != HomeAssistantConnectionState.CONNECTED) return

        val homeAssistantState = homeAssistantClient.navigationState.value
        if (homeAssistantState.navigationActive != true) return
        val destination = homeAssistantState.destination ?: return
        if (destination == activeTeslaDestination || destination == routeRequestDestination || destination == failedTeslaDestination) return

        val navigationState = stateStore.state.value
        if (navigationState.latitude == null || navigationState.longitude == null) return
        val accepted = repository.planRoute(
            latitude = destination.latitude,
            longitude = destination.longitude,
            startedFromTeslaSync = true,
        )
        if (accepted) {
            routeRequestDestination = destination
        } else {
            failedTeslaDestination = destination
        }
    }

    private fun handleTeslaRouteProgress(state: NavigationState) {
        val requestedDestination = routeRequestDestination ?: return
        if (!mutableTeslaSyncEnabled.value || !commaStateReady || lastTeslaNavActive != true) return

        if (state.routePlanned && state.startedFromTeslaSync) {
            val latestDestination = homeAssistantClient.navigationState.value.destination
            routeRequestDestination = null
            if (latestDestination != requestedDestination) {
                tryStartTeslaNavigation()
            } else if (repository.startRealtime()) {
                activeTeslaDestination = requestedDestination
                failedTeslaDestination = null
            } else {
                failedTeslaDestination = requestedDestination
            }
        } else if (state.errorMessage?.startsWith("路线规划失败") == true ||
            state.errorMessage?.startsWith("路线规划请求失败") == true
        ) {
            routeRequestDestination = null
            failedTeslaDestination = requestedDestination
        }
    }

    private fun resetTeslaSyncTracking() {
        commaStateReady = false
        lastTeslaNavActive = null
        routeRequestDestination = null
        activeTeslaDestination = null
        failedTeslaDestination = null
    }

    private fun preferences() = getSharedPreferences(HA_PREFS, MODE_PRIVATE)

    private fun updateNotification() {
        if (!::stateStore.isInitialized || !::exporter.isInitialized) return
        val content = "${stateStore.state.value.navigationMode.name} · Comma ${commaConnectionState.value.name} · " +
            "C3XL ${navAssistV2Status.value.name}"
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildNotification(content),
        )
    }

    private fun promoteToForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationForegroundService::class.java).setAction(ACTION_STOP_SERVICE),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TesNav 后台导航")
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止后台服务", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TesNav 后台导航",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持导航回调和 WebSocket 在后台运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TesNav:NavigationRuntime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        private const val NAVASSIST_DIAGNOSTIC_TAG = "TesNavNavState"
        private const val ACTION_STOP_SERVICE = "com.garan.tesnav.action.STOP_RUNTIME"
        private const val CHANNEL_ID = "tesnav_navigation_runtime"
        private const val NOTIFICATION_ID = 1001
        const val HA_PREFS = "home_assistant_sync"
        const val HA_SYNC_ENABLED = "enabled"
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationForegroundService::class.java),
            )
        }
    }
}
