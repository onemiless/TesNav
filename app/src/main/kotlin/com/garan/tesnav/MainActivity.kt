package com.garan.tesnav

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewOptions

class MainActivity : Activity() {
    private lateinit var naviView: AMapNaviView

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
            setPointToCenter(0.5, 0.5)
            setAutoLockCar(false)
        }

        naviView = AMapNaviView(this, viewOptions)
        setContentView(naviView)
        naviView.onCreate(savedInstanceState)

        naviView.map.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = true
            isCompassEnabled = false
            isScaleControlsEnabled = false
            isIndoorSwitchEnabled = false
            isTiltGesturesEnabled = false
        }
        naviView.map.moveCamera(CameraUpdateFactory.changeTilt(0f))

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
        naviView.map.setMyLocationType(AMap.LOCATION_TYPE_LOCATE)
        naviView.map.isMyLocationEnabled = true
    }

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
        naviView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
