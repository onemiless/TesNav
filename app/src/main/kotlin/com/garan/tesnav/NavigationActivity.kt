package com.garan.tesnav

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.enums.AMapNaviViewShowMode
import com.amap.api.navi.view.OverviewButtonView
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.service.NavigationForegroundService
import com.garan.tesnav.ui.NavigationStateDialog
import com.garan.tesnav.ui.SettingsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Dedicated AMap navigation page, matching AMap's official Activity separation. */
class NavigationActivity : Activity() {
    private lateinit var naviView: AMapNaviView
    private lateinit var routeActions: LinearLayout
    private lateinit var endNavigationButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var simulationButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var debugButton: ImageButton
    private lateinit var overviewButton: OverviewButtonView

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtimeService: NavigationForegroundService? = null
    private var bindRequested = false
    private var stateJob: Job? = null
    private var currentState = NavigationState()
    private var previousMode: NavigationMode? = null
    private var routeRequestSent = false

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as NavigationForegroundService.LocalBinder).getService()
            runtimeService = service
            debugButton.isEnabled = true
            observeRuntime(service)
            requestRouteIfNeeded(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cancelRuntimeObservation()
            runtimeService = null
            bindRequested = false
            debugButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        naviView = AMapNaviView(this, createViewOptions())
        createControls()
        setContentView(createRootView())
        naviView.onCreate(savedInstanceState)
        naviView.setLazyOverviewButtonView(overviewButton)
        configureMap()
        configureActions()
    }

    private fun createViewOptions() = AMapNaviViewOptions().apply {
        setLayoutVisible(false)
        setCompassEnabled(false)
        setTrafficBarEnabled(false)
        setTrafficLayerEnabled(false)
        setRouteListButtonShow(false)
        setSettingMenuEnabled(false)
        setBroadcastModeEnabled(false)
        setRefreshButtonEnabled(false)
        setNaviStatusBarEnabled(false)
        setTilt(0)
        setZoom(15)
        setPointToCenter(0.4, 0.5)
        setAutoLockCar(false)
    }

    private fun createControls() {
        endNavigationButton = actionButton("结束导航")
        realtimeButton = actionButton("开始导航")
        simulationButton = actionButton("模拟导航")
        routeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(endNavigationButton, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(realtimeButton, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(simulationButton, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
        }
        settingsButton = floatingIconButton(R.drawable.ic_settings, "打开设置")
        debugButton = floatingIconButton(R.drawable.ic_bug_report, "查看 NavigationState").apply {
            isEnabled = false
        }
        overviewButton = OverviewButtonView(this).apply {
            contentDescription = "路线全览"
        }
    }

    private fun createRootView(): FrameLayout = FrameLayout(this).apply {
        addView(naviView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(routeActions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(32)
        })
        addView(overviewButton, leftButtonParams(stackLevel = 1))
        addView(settingsButton, leftButtonParams(stackLevel = 2))
        addView(debugButton, leftButtonParams(stackLevel = 3))
    }

    private fun leftButtonParams(stackLevel: Int) = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dp(16)
            bottomMargin = dp(16 + stackLevel * 64)
    }

    private fun configureMap() {
        naviView.map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isIndoorSwitchEnabled = false
            isTiltGesturesEnabled = false
        }
        naviView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
    }

    private fun configureActions() {
        endNavigationButton.setOnClickListener { runtimeService?.stopNavigation() }
        realtimeButton.setOnClickListener {
            if (runtimeService?.startRealtime() != true) toast("启动导航失败")
        }
        simulationButton.setOnClickListener {
            val service = runtimeService ?: return@setOnClickListener
            val succeeded = when (currentState.navigationMode) {
                NavigationMode.ROUTE_PLANNED -> service.startSimulation()
                NavigationMode.SIMULATION -> {
                    if (currentState.simulationPaused) service.resumeSimulation() else service.pauseSimulation()
                }
                else -> false
            }
            if (!succeeded) toast("模拟导航操作失败")
        }
        settingsButton.setOnClickListener {
            runtimeService?.let { service ->
                SettingsDialog(
                    context = this,
                    scope = activityScope,
                    navigationState = service.stateStore.state,
                    connectionState = service.exporter.connectionState,
                    connectionError = service.exporter.lastError,
                ).show()
            } ?: toast("后台导航服务尚未连接")
        }
        debugButton.setOnClickListener {
            runtimeService?.let { service ->
                NavigationStateDialog(this, activityScope, service.stateStore.state).show()
            }
        }
    }

    private fun requestRouteIfNeeded(service: NavigationForegroundService) {
        if (routeRequestSent || service.stateStore.state.value.navigationMode != NavigationMode.IDLE) return
        if (!intent.hasExtra(EXTRA_DESTINATION_LATITUDE) || !intent.hasExtra(EXTRA_DESTINATION_LONGITUDE)) {
            finish()
            return
        }
        routeRequestSent = true
        val accepted = service.planRoute(
            intent.getDoubleExtra(EXTRA_DESTINATION_LATITUDE, Double.NaN),
            intent.getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, Double.NaN),
        )
        if (!accepted) {
            toast(service.stateStore.state.value.errorMessage ?: "路线规划请求失败")
            finish()
        }
    }

    private fun observeRuntime(service: NavigationForegroundService) {
        cancelRuntimeObservation()
        stateJob = activityScope.launch {
            service.stateStore.state.collect(::renderNavigationState)
        }
    }

    private fun renderNavigationState(state: NavigationState) {
        val oldMode = previousMode
        currentState = state
        when (state.navigationMode) {
            NavigationMode.IDLE -> {
                routeActions.visibility = View.GONE
                if (oldMode != null && oldMode != NavigationMode.IDLE) finish()
                if (routeRequestSent && !state.errorMessage.isNullOrBlank()) {
                    toast(state.errorMessage)
                    finish()
                }
            }
            NavigationMode.ROUTE_PLANNED -> {
                routeActions.visibility = View.VISIBLE
                endNavigationButton.visibility = View.GONE
                realtimeButton.visibility = View.VISIBLE
                simulationButton.apply {
                    text = "模拟导航"
                    visibility = View.VISIBLE
                }
                if (oldMode != NavigationMode.ROUTE_PLANNED) {
                    naviView.setShowMode(AMapNaviViewShowMode.SHOW_MODE_DISPLAY_OVERVIEW)
                }
            }
            NavigationMode.REALTIME -> {
                routeActions.visibility = View.VISIBLE
                endNavigationButton.visibility = View.VISIBLE
                realtimeButton.visibility = View.GONE
                simulationButton.visibility = View.GONE
                if (oldMode != NavigationMode.REALTIME) enterNavigationView()
            }
            NavigationMode.SIMULATION -> {
                routeActions.visibility = View.VISIBLE
                endNavigationButton.visibility = View.VISIBLE
                realtimeButton.visibility = View.GONE
                simulationButton.apply {
                    text = if (state.simulationPaused) "继续" else "暂停"
                    visibility = View.VISIBLE
                }
                if (oldMode != NavigationMode.SIMULATION) enterNavigationView()
            }
            NavigationMode.ARRIVED -> routeActions.visibility = View.GONE
        }
        previousMode = state.navigationMode
    }

    private fun enterNavigationView() {
        val options = naviView.viewOptions
        options.setAutoLockCar(true)
        options.setTilt(0)
        naviView.setViewOptions(options)
        naviView.recoverLockMode()
    }

    private fun bindRuntimeService() {
        if (bindRequested) return
        bindRequested = bindService(
            Intent(this, NavigationForegroundService::class.java),
            runtimeConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun cancelRuntimeObservation() {
        stateJob?.cancel()
        stateJob = null
    }

    override fun onStart() {
        super.onStart()
        bindRuntimeService()
    }

    override fun onResume() {
        super.onResume()
        naviView.onResume()
        val options = naviView.viewOptions
        options.setTilt(0)
        naviView.setViewOptions(options)
        naviView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
    }

    override fun onPause() {
        naviView.onPause()
        super.onPause()
    }

    override fun onStop() {
        cancelRuntimeObservation()
        if (bindRequested) unbindService(runtimeConnection)
        runtimeService = null
        bindRequested = false
        debugButton.isEnabled = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        naviView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        runtimeService?.stopNavigation()
        finish()
    }

    override fun onDestroy() {
        activityScope.cancel()
        naviView.onDestroy()
        super.onDestroy()
    }

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(BRAND_BLUE)
    }

    private fun floatingIconButton(icon: Int, description: String): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setColorFilter(Color.rgb(38, 50, 56))
        setPadding(dp(13), dp(13), dp(13), dp(13))
        elevation = dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(1), Color.rgb(207, 216, 220))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_DESTINATION_LATITUDE = "destination_latitude"
        const val EXTRA_DESTINATION_LONGITUDE = "destination_longitude"
        private val BRAND_BLUE = Color.rgb(0, 120, 255)
    }
}
