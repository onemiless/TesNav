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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.model.AMapNaviPath
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.MainActivity
import com.garan.tesnav.data.NavigationRepository
import com.garan.tesnav.data.NavigationStateStore
import com.garan.tesnav.export.ExportConfig
import com.garan.tesnav.export.WebSocketNavigationDataExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the navigation engine, state callbacks, and WebSocket alive in background. */
class NavigationForegroundService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var stateStore: NavigationStateStore
        private set
    lateinit var exporter: WebSocketNavigationDataExporter
        private set
    private lateinit var repository: NavigationRepository

    inner class LocalBinder : Binder() {
        fun getService(): NavigationForegroundService = this@NavigationForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground("正在启动导航服务")
        acquireWakeLock()

        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)

        stateStore = NavigationStateStore()
        repository = NavigationRepository(applicationContext, stateStore)
        repository.initialize()
        exporter = WebSocketNavigationDataExporter(
            config = ExportConfig(
                enabled = BuildConfig.EXPORT_ENABLED,
                webSocketUrl = BuildConfig.WEBSOCKET_URL,
                apiToken = BuildConfig.API_TOKEN,
                intervalMs = BuildConfig.EXPORT_INTERVAL_MS,
            ),
            stateProvider = { stateStore.state.value },
        )
        exporter.start()
        observeRuntime()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification()
        return START_STICKY
    }

    fun planRoute(latitude: Double, longitude: Double): Boolean = repository.planRoute(latitude, longitude)
    fun startRealtime(): Boolean = repository.startRealtime()
    fun startSimulation(): Boolean = repository.startSimulation()
    fun pauseSimulation(): Boolean = repository.pauseSimulation()
    fun resumeSimulation(): Boolean = repository.resumeSimulation()
    fun stopNavigation() = repository.stopNavigation()
    fun currentPath(): AMapNaviPath? = repository.currentPath()

    override fun onDestroy() {
        if (::exporter.isInitialized) exporter.stop()
        if (::repository.isInitialized) repository.release()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeRuntime() {
        scope.launch { stateStore.state.collect { updateNotification() } }
        scope.launch { exporter.connectionState.collect { updateNotification() } }
    }

    private fun updateNotification() {
        if (!::stateStore.isInitialized || !::exporter.isInitialized) return
        val content = "${stateStore.state.value.navigationMode.name} · Comma ${exporter.connectionState.value.name}"
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
        private const val ACTION_STOP_SERVICE = "com.garan.tesnav.action.STOP_RUNTIME"
        private const val CHANNEL_ID = "tesnav_navigation_runtime"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationForegroundService::class.java),
            )
        }
    }
}
