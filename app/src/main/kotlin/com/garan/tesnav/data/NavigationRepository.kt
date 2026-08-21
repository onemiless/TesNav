package com.garan.tesnav.data

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.model.AMapNaviPath
import com.garan.tesnav.model.CameraState
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.model.LaneState
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.TrafficStatus
import com.garan.tesnav.model.WarningLevel
import com.garan.tesnav.util.NavigationMappers
import com.garan.tesnav.util.OverspeedEvaluator

/** Owns the AMap navigation engine so callbacks continue while the Activity is in background. */
class NavigationRepository(
    context: Context,
    private val stateStore: NavigationStateStore,
    private val overspeedEvaluator: OverspeedEvaluator = OverspeedEvaluator(),
) : SimpleNaviListener() {
    private val appContext = context.applicationContext
    private var navi: AMapNavi? = null
    private var routeCalculationPending = false
    private var requestedNavigationMode: NavigationMode? = null

    fun initialize(): Result<Unit> = runCatching {
        navi = AMapNavi.getInstance(appContext).also {
            it.addAMapNaviListener(this)
            it.setUseInnerVoice(false)
            it.setTrafficStatusUpdateEnabled(true)
            it.setTrafficInfoUpdateEnabled(true)
            it.setCameraInfoUpdateEnabled(true)
            it.startGPS()
        }
    }.onFailure { error -> update { copy(errorMessage = "高德导航初始化失败：${error.message}") } }

    fun currentPath(): AMapNaviPath? = navi?.naviPath

    fun planRoute(latitude: Double, longitude: Double): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val engine = navi ?: return false
        val current = stateStore.state.value
        val startLat = current.latitude
        val startLng = current.longitude
        if (startLat == null || startLng == null) {
            update { copy(errorMessage = "尚未获得当前位置，请稍后再规划") }
            return false
        }

        engine.stopNavi()
        routeCalculationPending = true
        requestedNavigationMode = null
        update {
            clearRouteValues().copy(
                navigationMode = NavigationMode.IDLE,
                errorMessage = null,
            )
        }
        val accepted = runCatching {
            engine.calculateDriveRoute(
                listOf(NaviLatLng(startLat, startLng)),
                listOf(NaviLatLng(latitude, longitude)),
                emptyList(),
                PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT,
            )
        }.getOrElse { error ->
            update { copy(errorMessage = "路线规划请求失败：${error.message}") }
            false
        }
        if (!accepted) routeCalculationPending = false
        return accepted
    }

    fun startRealtime(): Boolean = startNavigation(NaviType.GPS, NavigationMode.REALTIME)

    fun startSimulation(): Boolean {
        navi?.setEmulatorNaviSpeed(DEFAULT_EMULATOR_SPEED_KPH)
        return startNavigation(NaviType.EMULATOR, NavigationMode.SIMULATION)
    }

    fun pauseSimulation(): Boolean = controlSimulation(paused = true)

    fun resumeSimulation(): Boolean = controlSimulation(paused = false)

    private fun controlSimulation(paused: Boolean): Boolean {
        val engine = navi ?: return false
        if (stateStore.state.value.navigationMode != NavigationMode.SIMULATION) return false
        return runCatching {
            if (paused) engine.pauseNavi() else engine.resumeNavi()
            update { copy(simulationPaused = paused, errorMessage = null) }
            true
        }.getOrElse { error ->
            update { copy(errorMessage = if (paused) "模拟导航暂停失败：${error.message}" else "模拟导航继续失败：${error.message}") }
            false
        }
    }

    private fun startNavigation(type: Int, mode: NavigationMode): Boolean {
        if (!stateStore.state.value.routePlanned) {
            update { copy(errorMessage = "请先规划路线") }
            return false
        }
        requestedNavigationMode = mode
        val started = navi?.startNavi(type) == true
        if (started) {
            update { copy(navigationMode = mode, simulationPaused = false, errorMessage = null) }
        } else {
            requestedNavigationMode = null
            update { copy(errorMessage = "导航启动请求未被 SDK 接受") }
        }
        return started
    }

    fun stopNavigation() {
        routeCalculationPending = false
        requestedNavigationMode = null
        navi?.stopNavi()
        update { clearRouteValues().copy(navigationMode = NavigationMode.IDLE, errorMessage = null) }
    }

    fun release() {
        navi?.stopNavi()
        navi?.stopGPS()
        navi?.removeAMapNaviListener(this)
        navi = null
        AMapNavi.destroy()
    }

    override fun onInitNaviFailure() = update { copy(errorMessage = "高德导航引擎初始化失败") }
    override fun onInitNaviSuccess() = update { copy(errorMessage = null) }

    override fun onStartNavi(type: Int) {
        val expected = requestedNavigationMode ?: return
        requestedNavigationMode = null
        val mode = when (type) {
            NaviType.EMULATOR -> NavigationMode.SIMULATION
            NaviType.GPS -> NavigationMode.REALTIME
            else -> expected
        }
        update { copy(navigationMode = mode, simulationPaused = false, errorMessage = null) }
    }

    override fun onLocationChange(location: AMapNaviLocation?) {
        if (location == null) return
        val speed = location.speed.coerceAtLeast(0f)
        val overspeed = overspeedEvaluator.evaluate(speed, stateStore.state.value.cameras)
        update {
            copy(
                latitude = location.coord?.latitude,
                longitude = location.coord?.longitude,
                accuracy = location.accuracy,
                bearing = location.bearing,
                locationTime = location.time,
                speedKph = speed,
                speedLimitKph = overspeed.speedLimitKph,
                isOverspeed = overspeed.isOverspeed,
                hasSpeedCameraAhead = overspeed.hasSpeedCameraAhead,
                warningLevel = overspeed.warningLevel,
            )
        }
    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (info == null || !stateStore.state.value.routePlanned) return
        update {
            copy(
                currentRoad = info.currentRoadName?.takeIf(String::isNotBlank),
                nextRoad = info.nextRoadName?.takeIf(String::isNotBlank),
                nextTurnType = info.iconType,
                nextTurnDistanceMeters = info.curStepRetainDistance,
                routeRemainDistanceMeters = info.pathRetainDistance,
                routeRemainTimeSeconds = info.pathRetainTime,
                remainingTrafficLightCount = info.routeRemainLightCount.takeIf { it >= 0 },
            )
        }
    }

    override fun onTrafficStatusUpdate() {
        if (!stateStore.state.value.routePlanned) return
        val worst = navi?.naviPath?.trafficStatuses.orEmpty().maxOfOrNull { it.status } ?: 0
        update { copy(trafficStatus = NavigationMappers.trafficStatus(worst)) }
    }

    override fun updateCameraInfo(infoArray: Array<out AMapNaviCameraInfo>?) {
        applyCameras(infoArray.orEmpty().map(::cameraState))
    }

    override fun updateIntervalCameraInfo(start: AMapNaviCameraInfo?, end: AMapNaviCameraInfo?, status: Int) {
        val cameras = listOfNotNull(start, end).map(::cameraState)
        if (cameras.isNotEmpty()) applyCameras(cameras)
    }

    private fun cameraState(camera: AMapNaviCameraInfo): CameraState = CameraState(
        type = NavigationMappers.cameraType(camera.cameraType),
        latitude = camera.y.takeIf { it in -90.0..90.0 },
        longitude = camera.x.takeIf { it in -180.0..180.0 },
        distanceMeters = camera.cameraDistance.takeIf { it >= 0 },
        limitSpeedKph = NavigationMappers.validSpeedLimit(camera.cameraSpeed),
        intervalRemainDistanceMeters = camera.intervalRemainDistance.takeIf { it >= 0 },
        averageSpeedKph = camera.averageSpeed.takeIf { it > 0 },
        reasonableSpeedKph = camera.reasonableSpeedInRemainDist.takeIf { it > 0 },
    )

    private fun applyCameras(cameras: List<CameraState>) {
        val overspeed = overspeedEvaluator.evaluate(stateStore.state.value.speedKph, cameras)
        update {
            copy(
                cameras = cameras,
                speedLimitKph = overspeed.speedLimitKph,
                isOverspeed = overspeed.isOverspeed,
                hasSpeedCameraAhead = overspeed.hasSpeedCameraAhead,
                warningLevel = overspeed.warningLevel,
            )
        }
    }

    override fun showLaneInfo(laneInfos: Array<out AMapLaneInfo>?, background: ByteArray?, recommended: ByteArray?) {
        if (!stateStore.state.value.routePlanned) return
        val raw = background ?: byteArrayOf()
        val front = recommended ?: byteArrayOf()
        val firstPadding = raw.indexOfFirst { it.toInt().and(0xff) == 0xff }
        val count = when {
            firstPadding >= 0 -> firstPadding
            raw.isNotEmpty() -> raw.size
            else -> laneInfos?.firstOrNull()?.laneCount ?: 0
        }
        val recommendedActions = NavigationMappers.laneRecommendedActions(
            count,
            front.map { it.toInt().and(0xff) }.toIntArray(),
        )
        val lanes = (0 until count).map { index ->
            val laneRaw = raw.getOrNull(index)?.toInt()?.and(0xff)
                ?: laneInfos?.getOrNull(index)?.backgroundLane?.firstOrNull() ?: -1
            LaneState(
                index = index,
                allowedActions = NavigationMappers.laneActions(laneRaw),
                recommended = recommendedActions[index].isNotEmpty(),
                rawLaneType = laneRaw,
                recommendedActions = recommendedActions[index],
            )
        }
        update { copy(lanes = lanes) }
    }

    override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
        if (laneInfo == null || !stateStore.state.value.routePlanned) return
        val count = laneInfo.laneCount.coerceAtLeast(0)
        val recommendedActions = NavigationMappers.laneRecommendedActions(count, laneInfo.frontLane)
        val lanes = (0 until count).map { index ->
            val raw = laneInfo.backgroundLane?.getOrNull(index) ?: -1
            LaneState(
                index = index,
                allowedActions = NavigationMappers.laneActions(raw),
                recommended = recommendedActions[index].isNotEmpty(),
                rawLaneType = raw,
                recommendedActions = recommendedActions[index],
            )
        }
        update { copy(lanes = lanes) }
    }

    override fun hideLaneInfo() = update { copy(lanes = emptyList()) }

    override fun onCalculateRouteSuccess(routeIds: IntArray?) = routeSucceeded()
    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) = routeSucceeded()

    private fun routeSucceeded() {
        if (!routeCalculationPending) return
        routeCalculationPending = false
        val path = navi?.naviPath
        update {
            copy(
                navigationMode = NavigationMode.ROUTE_PLANNED,
                simulationPaused = false,
                routePlanned = true,
                routeRemainDistanceMeters = path?.allLength,
                routeRemainTimeSeconds = path?.allTime,
                remainingTrafficLightCount = path?.trafficLightCount,
                routeTrafficLights = path?.lightList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                errorMessage = null,
            )
        }
    }

    override fun onCalculateRouteFailure(errorCode: Int) = routeFailed("路线规划失败：$errorCode")
    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) =
        routeFailed("路线规划失败：${result?.errorCode ?: -1} ${result?.errorDescription.orEmpty()}")

    private fun routeFailed(message: String) {
        if (!routeCalculationPending) return
        routeCalculationPending = false
        update { clearRouteValues().copy(errorMessage = message) }
    }

    override fun onReCalculateRouteForYaw() = update { copy(errorMessage = "检测到偏航，正在重新规划") }
    override fun onReCalculateRouteForTrafficJam() = update { copy(errorMessage = "因拥堵重新规划路线") }
    override fun onArriveDestination() = update {
        copy(navigationMode = NavigationMode.ARRIVED, simulationPaused = false, errorMessage = null)
    }
    override fun onEndEmulatorNavi() {
        if (stateStore.state.value.routePlanned) {
            update { copy(navigationMode = NavigationMode.ROUTE_PLANNED, simulationPaused = false) }
        }
    }
    override fun onGpsSignalWeak(weak: Boolean) = update {
        copy(gpsSignalWeak = weak, errorMessage = if (weak) "GPS 信号弱" else errorMessage?.takeUnless { it == "GPS 信号弱" })
    }
    override fun onGpsOpenStatus(enabled: Boolean) {
        if (!enabled) update { copy(errorMessage = "系统定位开关未开启") }
    }

    private fun update(transform: com.garan.tesnav.model.NavigationState.() -> com.garan.tesnav.model.NavigationState) {
        stateStore.update(transform)
    }

    private fun com.garan.tesnav.model.NavigationState.clearRouteValues() = copy(
        navigationMode = NavigationMode.IDLE,
        simulationPaused = false,
        currentRoad = null,
        nextRoad = null,
        nextTurnType = null,
        nextTurnDistanceMeters = null,
        routeRemainDistanceMeters = null,
        routeRemainTimeSeconds = null,
        remainingTrafficLightCount = null,
        routeTrafficLights = emptyList(),
        trafficStatus = TrafficStatus.UNKNOWN,
        lanes = emptyList(),
        cameras = emptyList(),
        speedLimitKph = null,
        isOverspeed = false,
        hasSpeedCameraAhead = false,
        warningLevel = WarningLevel.NONE,
        routePlanned = false,
        startedFromTeslaSync = false,
    )

    private companion object {
        const val DEFAULT_EMULATOR_SPEED_KPH = 120
    }
}
