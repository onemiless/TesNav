package com.garan.tesnav

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.ServiceSettings
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.search.AddressCandidate
import com.garan.tesnav.search.AddressLookupController
import com.garan.tesnav.search.AddressLookupView
import com.garan.tesnav.search.AddressPoint
import com.garan.tesnav.search.AmapAddressLookupGateway
import com.garan.tesnav.search.LocationFailure
import com.garan.tesnav.search.selectLocationFailure
import com.garan.tesnav.service.NavigationForegroundService
import com.garan.tesnav.ui.NavigationStateDialog
import com.garan.tesnav.ui.SettingsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Normal map page. Navigation is displayed by [NavigationActivity]. */
class MainActivity : Activity(), AddressLookupView {
    private lateinit var mapView: MapView
    private lateinit var planButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var debugButton: ImageButton
    private lateinit var destinationInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchProgress: ProgressBar
    private lateinit var searchStatus: TextView
    private lateinit var currentAddressText: TextView
    private lateinit var searchPanel: LinearLayout
    private lateinit var addressLookupController: AddressLookupController

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtimeService: NavigationForegroundService? = null
    private var bindRequested = false
    private var stateJob: Job? = null
    private var currentState = NavigationState()
    private var previousMode: NavigationMode? = null
    private var destination: LatLng? = null
    private var destinationMarker: Marker? = null
    private var navigationPageOpening = false
    private var initialCameraPositioned = false
    private var mapState = MapState.FOLLOW
    private var lastMapInteractionAt = 0L
    private var lastLocationPoint: AddressPoint? = null

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as NavigationForegroundService.LocalBinder).getService()
            runtimeService = service
            debugButton.isEnabled = true
            observeRuntime(service)
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

        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)
        ServiceSettings.updatePrivacyShow(applicationContext, true, true)
        ServiceSettings.updatePrivacyAgree(applicationContext, true)

        mapView = MapView(this)
        addressLookupController = AddressLookupController(
            gateway = AmapAddressLookupGateway(applicationContext),
            view = this,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
        )
        createControls()
        setContentView(createRootView())
        mapView.onCreate(savedInstanceState)
        configureMap()
        configureActions()

        if (hasLocationPermission()) {
            enableMapLocation()
            startRuntimeService()
            requestRemainingPermissions()
        } else {
            requestPermissions(LOCATION_PERMISSIONS, LOCATION_PERMISSION_REQUEST)
        }
    }

    private fun createControls() {
        planButton = actionButton("导航到这里").apply { visibility = View.GONE }
        settingsButton = floatingIconButton(R.drawable.ic_settings, "打开设置")
        debugButton = floatingIconButton(R.drawable.ic_bug_report, "查看 NavigationState").apply {
            isEnabled = false
        }
        destinationInput = EditText(this).apply {
            hint = "输入目的地地址或地点"
            setSingleLine(true)
            textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedRect(Color.WHITE, Color.rgb(207, 216, 220))
        }
        searchButton = actionButton("搜索").apply { textSize = 15f }
        searchProgress = ProgressBar(this).apply { visibility = View.GONE }
        searchStatus = TextView(this).apply {
            text = "也可长按地图选择目的地"
            textSize = 13f
            setTextColor(Color.rgb(84, 110, 122))
        }
        currentAddressText = TextView(this).apply {
            text = "当前位置：定位中…"
            textSize = 14f
            setTextColor(Color.rgb(38, 50, 56))
        }
        searchPanel = createSearchPanel()
    }

    private fun createSearchPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        elevation = dp(10).toFloat()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedRect(Color.argb(242, 255, 255, 255), Color.rgb(207, 216, 220))
        addView(currentAddressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(destinationInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchProgress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
            addView(searchButton, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(searchStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun createRootView(): FrameLayout = FrameLayout(this).apply {
        addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(searchPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(12)
        })
        addView(planButton, FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(32)
        })
        addView(settingsButton, leftButtonParams(stackLevel = 2))
        addView(debugButton, leftButtonParams(stackLevel = 3))
    }

    private fun leftButtonParams(stackLevel: Int) = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        marginStart = dp(16)
        bottomMargin = dp(16 + stackLevel * 64)
    }

    private fun configureMap() {
        mapView.map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isIndoorSwitchEnabled = false
            isTiltGesturesEnabled = false
        }
        mapView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
        mapView.map.setOnMapLongClickListener(::selectDestination)
        mapView.map.setOnMapTouchListener { event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                lastMapInteractionAt = SystemClock.elapsedRealtime()
                if (mapState != MapState.BROWSE) {
                    mapState = MapState.BROWSE
                    applyLocationMode(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                }
            }
        }
        mapView.map.setOnMyLocationChangeListener { location ->
            locationFailure(location)?.let { failure ->
                lastLocationPoint = null
                addressLookupController.locationFailed(
                    "定位失败（${failure.errorCode}）：${failure.errorInfo}",
                )
                return@setOnMyLocationChangeListener
            }
            val point = AddressPoint(location.latitude, location.longitude)
            if (isValidLocation(point)) {
                lastLocationPoint = point
                addressLookupController.updateLocation(point)
            } else {
                lastLocationPoint = null
                addressLookupController.locationFailed("尚无有效定位")
                return@setOnMyLocationChangeListener
            }

            if (!initialCameraPositioned) {
                initialCameraPositioned = true
                mapView.map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        INITIAL_ZOOM,
                    ),
                )
            }

            if (mapState == MapState.BROWSE &&
                location.hasSpeed() && location.speed > MOVING_SPEED_MPS &&
                SystemClock.elapsedRealtime() - lastMapInteractionAt >= BROWSE_TIMEOUT_MS
            ) {
                mapState = MapState.FOLLOW
                applyLocationMode(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
            }
        }
    }

    private fun configureActions() {
        planButton.setOnClickListener { destination?.let(::openNavigationPage) }
        searchButton.setOnClickListener(::searchDestinationAddress)
        destinationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestinationAddress()
                true
            } else {
                false
            }
        }
        settingsButton.setOnClickListener {
            runtimeService?.let { service ->
                SettingsDialog(
                    context = this,
                    scope = activityScope,
                    service = service,
                ).show()
            } ?: toast("后台导航服务尚未连接")
        }
        debugButton.setOnClickListener {
            runtimeService?.let { service ->
                NavigationStateDialog(this, activityScope, service.stateStore.state).show()
            }
        }
    }

    private fun searchDestinationAddress(@Suppress("UNUSED_PARAMETER") ignored: View? = null) {
        if (currentState.navigationMode != NavigationMode.IDLE) {
            toast("请先结束当前导航")
            return
        }
        addressLookupController.searchDestination(destinationInput.text?.toString().orEmpty())
    }

    override fun onDestinationSearchStarted(query: String) = withLiveUi {
        searchButton.isEnabled = false
        searchProgress.visibility = View.VISIBLE
        searchStatus.setTextColor(Color.rgb(84, 110, 122))
        searchStatus.text = "正在搜索：$query"
    }

    override fun onDestinationFound(candidate: AddressCandidate) = withLiveUi {
        finishDestinationSearch()
        if (currentState.navigationMode != NavigationMode.IDLE) {
            onDestinationSearchFailed("当前导航已启动，请先结束导航")
            return@withLiveUi
        }
        val point = LatLng(candidate.latitude, candidate.longitude)
        selectDestination(point)
        mapView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, DESTINATION_ZOOM))
        searchStatus.setTextColor(Color.rgb(0, 105, 92))
        searchStatus.text = "已找到：${candidate.formattedAddress}，请点击“导航到这里”确认"
        hideKeyboard()
    }

    override fun onDestinationSearchFailed(message: String) = withLiveUi {
        finishDestinationSearch()
        searchStatus.setTextColor(Color.rgb(198, 40, 40))
        searchStatus.text = message
    }

    override fun onCurrentAddressLoading() = withLiveUi {
        currentAddressText.setTextColor(Color.rgb(84, 110, 122))
        currentAddressText.text = lastLocationPoint?.let {
            "当前位置：正在解析地址…（${formatCoordinate(it)}）"
        } ?: "当前位置：定位中…"
    }

    override fun onCurrentAddressResolved(address: String) = withLiveUi {
        currentAddressText.setTextColor(Color.rgb(38, 50, 56))
        currentAddressText.text = "当前位置：$address"
    }

    override fun onCurrentAddressFailed(message: String) = withLiveUi {
        currentAddressText.setTextColor(Color.rgb(198, 40, 40))
        currentAddressText.text = lastLocationPoint?.let {
            "当前位置：地址暂不可用（${formatCoordinate(it)}）\n$message"
        } ?: "当前位置：$message"
    }

    private fun finishDestinationSearch() {
        searchButton.isEnabled = true
        searchProgress.visibility = View.GONE
    }

    private fun withLiveUi(action: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(destinationInput.windowToken, 0)
        destinationInput.clearFocus()
    }

    private fun formatCoordinate(point: AddressPoint): String =
        String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)

    private fun isValidLocation(point: AddressPoint): Boolean =
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            (point.latitude != 0.0 || point.longitude != 0.0)

    private fun locationFailure(location: Location): LocationFailure? {
        val extrasErrorCode = runCatching {
            location.extras?.getInt(MyLocationStyle.ERROR_CODE, 0) ?: 0
        }.getOrDefault(0)
        val extrasErrorInfo = runCatching {
            location.extras?.getString(MyLocationStyle.ERROR_INFO)
        }.getOrNull()
        val amapLocation = location as? AMapLocation
        return selectLocationFailure(
            extrasErrorCode = extrasErrorCode,
            extrasErrorInfo = extrasErrorInfo,
            subtypeErrorCode = amapLocation?.errorCode ?: 0,
            subtypeErrorInfo = amapLocation?.errorInfo,
        )
    }

    private fun selectDestination(point: LatLng) {
        if (currentState.navigationMode != NavigationMode.IDLE) {
            toast("请先结束当前导航")
            return
        }
        destination = point
        destinationMarker?.remove()
        destinationMarker = mapView.map.addMarker(MarkerOptions().position(point).title("目的地"))
        planButton.visibility = View.VISIBLE
    }

    private fun openNavigationPage(target: LatLng? = null) {
        if (navigationPageOpening) return
        navigationPageOpening = true
        startActivity(
            Intent(this, NavigationActivity::class.java).apply {
                target?.let {
                    putExtra(NavigationActivity.EXTRA_DESTINATION_LATITUDE, it.latitude)
                    putExtra(NavigationActivity.EXTRA_DESTINATION_LONGITUDE, it.longitude)
                }
            },
        )
    }

    private fun observeRuntime(service: NavigationForegroundService) {
        cancelRuntimeObservation()
        stateJob = activityScope.launch {
            service.stateStore.state.collect { state ->
                currentState = state
                if (state.navigationMode == NavigationMode.IDLE &&
                    previousMode != null && previousMode != NavigationMode.IDLE
                ) {
                    clearDestination()
                } else if (!navigationPageOpening) {
                    if (state.navigationMode != NavigationMode.IDLE) openNavigationPage()
                }
                previousMode = state.navigationMode
            }
        }
    }

    private fun clearDestination() {
        destination = null
        destinationMarker?.remove()
        destinationMarker = null
        planButton.visibility = View.GONE
    }

    private fun enableMapLocation() {
        applyLocationMode(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
        mapView.map.isMyLocationEnabled = true
    }

    private fun applyLocationMode(type: Int) {
        mapView.map.myLocationStyle = MyLocationStyle()
            .myLocationType(type)
            .interval(LOCATION_INTERVAL_MS)
    }

    private fun startRuntimeService() {
        NavigationForegroundService.start(applicationContext)
        if (window.decorView.isAttachedToWindow) bindRuntimeService()
    }

    private fun bindRuntimeService() {
        if (bindRequested || !hasLocationPermission()) return
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

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestRemainingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        requestBackgroundLocationPermission()
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_LOCATION_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> if (hasLocationPermission()) {
                enableMapLocation()
                startRuntimeService()
                requestRemainingPermissions()
            } else {
                addressLookupController.locationFailed("定位权限未授予")
            }
            NOTIFICATION_PERMISSION_REQUEST -> requestBackgroundLocationPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        bindRuntimeService()
    }

    override fun onResume() {
        super.onResume()
        val returnedFromNavigation = navigationPageOpening
        navigationPageOpening = false
        if (returnedFromNavigation) clearDestination()
        mapView.onResume()
        mapView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
    }

    override fun onPause() {
        mapView.onPause()
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
        mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        addressLookupController.close()
        activityScope.cancel()
        mapView.onDestroy()
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

    private fun roundedRect(fillColor: Int, strokeColor: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val LOCATION_PERMISSION_REQUEST = 1
        const val NOTIFICATION_PERMISSION_REQUEST = 2
        const val BACKGROUND_LOCATION_PERMISSION_REQUEST = 3
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val BRAND_BLUE = Color.rgb(0, 120, 255)
        const val INITIAL_ZOOM = 14f
        const val DESTINATION_ZOOM = 16f
        const val MOVING_SPEED_MPS = 1f
        const val BROWSE_TIMEOUT_MS = 15_000L
        const val LOCATION_INTERVAL_MS = 1_000L
    }

    private enum class MapState { FOLLOW, BROWSE }
}
