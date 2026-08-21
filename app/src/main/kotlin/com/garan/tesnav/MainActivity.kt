package com.garan.tesnav

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.view.RouteOverLay

class MainActivity : Activity() {
    private lateinit var naviView: AMapNaviView
    private lateinit var actionButton: Button
    private var aMapNavi: AMapNavi? = null
    private var destination: LatLng? = null
    private var destinationMarker: Marker? = null
    private var routeOverLay: RouteOverLay? = null
    private var navigationState = NavigationState.IDLE

    private val naviListener = object : SimpleNaviListener() {
        override fun onCalculateRouteSuccess(routeResult: AMapCalcRouteResult) {
            runOnUiThread { showCalculatedRoute() }
        }

        override fun onCalculateRouteFailure(routeResult: AMapCalcRouteResult) {
            runOnUiThread {
                navigationState = NavigationState.DESTINATION_SELECTED
                actionButton.isEnabled = true
                actionButton.text = "导航到这里"
                Toast.makeText(this@MainActivity, "路线规划失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)

        val viewOptions = AMapNaviViewOptions().apply {
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

        naviView = AMapNaviView(this, viewOptions)
        actionButton = Button(this).apply {
            text = "导航到这里"
            textSize = 18f
            visibility = View.GONE
            setOnClickListener { handleNavigationAction() }
        }

        val root = FrameLayout(this).apply {
            addView(
                naviView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                actionButton,
                FrameLayout.LayoutParams(dp(220), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(32)
                },
            )
        }
        setContentView(root)
        naviView.onCreate(savedInstanceState)

        aMapNavi = AMapNavi.getInstance(this).also {
            it.addAMapNaviListener(naviListener)
            it.setUseInnerVoice(false)
        }

        naviView.map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isIndoorSwitchEnabled = false
            isTiltGesturesEnabled = false
        }
        naviView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))
        naviView.map.setOnMapLongClickListener(::selectDestination)

        if (hasLocationPermission()) {
            enableLocation()
        } else {
            requestPermissions(LOCATION_PERMISSIONS, LOCATION_PERMISSION_REQUEST)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enableLocation() {
        val locationStyle = MyLocationStyle().apply {
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
        }
        naviView.map.setMyLocationStyle(locationStyle)
        naviView.map.isMyLocationEnabled = true
    }

    private fun selectDestination(point: LatLng) {
        if (navigationState == NavigationState.NAVIGATING) return

        clearRoute()
        destination = point
        destinationMarker?.remove()
        destinationMarker = naviView.map.addMarker(
            MarkerOptions()
                .position(point)
                .title("目的地"),
        )
        navigationState = NavigationState.DESTINATION_SELECTED
        actionButton.apply {
            text = "导航到这里"
            isEnabled = true
            visibility = View.VISIBLE
        }
    }

    private fun handleNavigationAction() {
        when (navigationState) {
            NavigationState.DESTINATION_SELECTED -> calculateRoute()
            NavigationState.ROUTE_READY -> startNavigation()
            NavigationState.NAVIGATING -> stopNavigation()
            NavigationState.IDLE,
            NavigationState.CALCULATING,
            -> Unit
        }
    }

    private fun calculateRoute() {
        val target = destination ?: return
        val navi = aMapNavi ?: return
        if (!hasLocationPermission()) {
            requestPermissions(LOCATION_PERMISSIONS, LOCATION_PERMISSION_REQUEST)
            return
        }

        navigationState = NavigationState.CALCULATING
        actionButton.apply {
            text = "正在规划…"
            isEnabled = false
        }
        navi.startGPS()
        val requestAccepted = navi.calculateDriveRoute(
            listOf(NaviLatLng(target.latitude, target.longitude)),
            null,
            PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT,
        )
        if (!requestAccepted) {
            navigationState = NavigationState.DESTINATION_SELECTED
            actionButton.apply {
                text = "导航到这里"
                isEnabled = true
            }
            Toast.makeText(this, "路线规划请求失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCalculatedRoute() {
        if (navigationState != NavigationState.CALCULATING) return
        val path = aMapNavi?.naviPath ?: return

        destinationMarker?.remove()
        destinationMarker = null
        clearRoute()
        routeOverLay = RouteOverLay(naviView.map, path, this).also {
            it.addToMap()
            it.zoomToSpan()
        }
        navigationState = NavigationState.ROUTE_READY
        actionButton.apply {
            text = "开始导航"
            isEnabled = true
            visibility = View.VISIBLE
        }
    }

    private fun startNavigation() {
        val started = aMapNavi?.startNavi(NaviType.GPS) == true
        if (!started) {
            Toast.makeText(this, "启动导航失败", Toast.LENGTH_SHORT).show()
            return
        }

        val options = naviView.viewOptions
        options.setAutoLockCar(true)
        naviView.setViewOptions(options)
        naviView.map.isMyLocationEnabled = false
        naviView.recoverLockMode()
        navigationState = NavigationState.NAVIGATING
        actionButton.text = "结束导航"
    }

    private fun stopNavigation() {
        aMapNavi?.stopNavi()
        clearRoute()
        destinationMarker?.remove()
        destinationMarker = null
        destination = null

        val options = naviView.viewOptions
        options.setAutoLockCar(false)
        naviView.setViewOptions(options)
        enableLocation()
        navigationState = NavigationState.IDLE
        actionButton.visibility = View.GONE
    }

    private fun clearRoute() {
        routeOverLay?.destroy()
        routeOverLay = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && hasLocationPermission()) {
            enableLocation()
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        naviView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        clearRoute()
        aMapNavi?.removeAMapNaviListener(naviListener)
        aMapNavi?.stopGPS()
        aMapNavi = null
        AMapNavi.destroy()
        naviView.onDestroy()
        super.onDestroy()
    }

    private enum class NavigationState {
        IDLE,
        DESTINATION_SELECTED,
        CALCULATING,
        ROUTE_READY,
        NAVIGATING,
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
