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
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.RouteChoice
import com.garan.tesnav.model.TrafficStatus
import com.garan.tesnav.model.WarningLevel
import com.garan.tesnav.util.NavigationMappers
import com.garan.tesnav.util.OverspeedEvaluator

/** Owns the AMap navigation engine so callbacks continue while the Activity is in background. */
class NavigationRepository(
    context: Context,
    private val stateStore: NavigationStateStore,
    private val overspeedEvaluator: OverspeedEvaluator = OverspeedEvaluator(),
    private val onRouteChanged: (AMapNaviPath?) -> Unit = {},
) : SimpleNaviListener() {
    private val appContext = context.applicationContext
    private var navi: AMapNavi? = null
    private var routeCalculationPending = false
    private var requestedNavigationMode: NavigationMode? = null
    private var publishedRouteSignature: String? = null
    private var routeRevision = 0L
    private val routeSelection = RouteSelectionCoordinator { routeId -> navi?.selectRouteId(routeId) == true }

    fun initialize(): Result<Unit> = runCatching {
        navi = AMapNavi.getInstance(appContext).also {
            it.addAMapNaviListener(this)
            it.setUseInnerVoice(true)
            it.setTrafficStatusUpdateEnabled(true)
            it.setTrafficInfoUpdateEnabled(true)
            it.setCameraInfoUpdateEnabled(true)
            it.startGPS()
        }
    }.onFailure { error -> update { copy(errorMessage = "高德导航初始化失败：${error.message}") } }

    fun currentPath(): AMapNaviPath? = navi?.naviPath

    fun selectRoute(routeId: Int): Boolean {
        val current = stateStore.state.value
        if (current.navigationMode != NavigationMode.ROUTE_PLANNED || !current.routePlanned) return false
        return when (routeSelection.select(routeId, current.routeChoices, current.selectedRouteId)) {
            RouteSelectionOutcome.REJECTED -> false
            RouteSelectionOutcome.ALREADY_SELECTED -> true
            RouteSelectionOutcome.SELECTED -> {
                val path = navi?.naviPaths?.get(routeId) ?: return false
                val choices = current.routeChoices.map { it.copy(selected = it.routeId == routeId) }
                val observedAtMs = System.currentTimeMillis()
                val revision = ++routeRevision
                update {
                    copy(
                        routeRemainDistanceMeters = path.allLength,
                        routeRemainTimeSeconds = path.allTime,
                        remainingTrafficLightCount = path.trafficLightCount,
                        routeTrafficLights = path.lightList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                        routeChoices = choices,
                        selectedRouteId = routeId,
                        routeRevision = revision,
                        routeObservedAtMs = observedAtMs,
                        guidanceObservedAtMs = null,
                        lanesObservedAtMs = null,
                        currentStepIndex = null,
                        currentLinkIndex = null,
                        currentPointIndex = null,
                        routeMatched = null,
                        maneuver = NavigationManeuver.UNKNOWN,
                        guidanceStepIndex = null,
                        currentRoadClass = null,
                        currentRoadType = null,
                    )
                }
                publishRouteIfChanged(path)
                true
            }
        }
    }

    fun planRoute(
        latitude: Double,
        longitude: Double,
        startedFromTeslaSync: Boolean = false,
    ): Boolean {
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
        val routeInvalidatedAtMs = System.currentTimeMillis()
        val revision = if (current.routePlanned) ++routeRevision else routeRevision
        update {
            clearRouteValues().copy(
                navigationMode = NavigationMode.IDLE,
                startedFromTeslaSync = startedFromTeslaSync,
                errorMessage = null,
                routeRevision = revision,
                routeObservedAtMs = if (current.routePlanned) routeInvalidatedAtMs else routeObservedAtMs,
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

    fun setSpeechEnabled(enabled: Boolean): Boolean {
        val engine = navi ?: return false
        return runCatching {
            if (enabled) engine.startSpeak() else engine.stopSpeak()
            update { copy(speechEnabled = enabled, errorMessage = null) }
            true
        }.getOrElse { error ->
            update { copy(errorMessage = "语音设置失败：${error.message}") }
            false
        }
    }

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
            navi?.startSpeak()
            update { copy(navigationMode = mode, simulationPaused = false, speechEnabled = true, errorMessage = null) }
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
        val observedAtMs = System.currentTimeMillis()
        val revision = ++routeRevision
        update {
            clearRouteValues().copy(
                navigationMode = NavigationMode.IDLE,
                errorMessage = null,
                routeRevision = revision,
                routeObservedAtMs = observedAtMs,
            )
        }
        clearPublishedRoute()
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
        val observedAtMs = System.currentTimeMillis()
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
                locationObservedAtMs = observedAtMs,
                currentStepIndex = location.curStepIndex.takeIf { it >= 0 },
                currentLinkIndex = location.curLinkIndex.takeIf { it >= 0 },
                currentPointIndex = location.curPointIndex.takeIf { it >= 0 },
                routeMatched = location.isMatchNaviPath,
            )
        }
    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        if (info == null || !stateStore.state.value.routePlanned) return
        val observedAtMs = System.currentTimeMillis()
        val stepIndex = info.curStep.takeIf { it >= 0 }
        val linkIndex = info.curLink.takeIf { it >= 0 }
        val currentLink = stepIndex?.let { step ->
            linkIndex?.let { link -> navi?.naviPath?.steps?.getOrNull(step)?.links?.getOrNull(link) }
        }
        val maneuverRoadType = stepIndex?.let { step ->
            navi?.naviPath?.steps?.getOrNull(step + 1)?.links?.firstOrNull()?.roadType
        } ?: currentLink?.roadType
        update {
            copy(
                currentRoad = info.currentRoadName?.takeIf(String::isNotBlank),
                nextRoad = info.nextRoadName?.takeIf(String::isNotBlank),
                nextTurnType = info.iconType,
                nextTurnDistanceMeters = info.curStepRetainDistance,
                routeRemainDistanceMeters = info.pathRetainDistance,
                routeRemainTimeSeconds = info.pathRetainTime,
                remainingTrafficLightCount = info.routeRemainLightCount.takeIf { it >= 0 },
                guidanceObservedAtMs = observedAtMs,
                maneuver = NavigationMappers.maneuver(info.iconType, maneuverRoadType),
                guidanceStepIndex = stepIndex,
                currentRoadClass = NavigationMappers.validRoadClass(currentLink?.roadClass),
                currentRoadType = NavigationMappers.validRoadType(currentLink?.roadType),
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
        val observedAtMs = System.currentTimeMillis()
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
                rawRecommendedLaneType = front.getOrNull(index)?.toInt()?.and(0xff)?.takeUnless { it in setOf(15, 22, 255) },
            )
        }
        update { copy(lanes = lanes, lanesObservedAtMs = observedAtMs) }
    }

    override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
        if (laneInfo == null || !stateStore.state.value.routePlanned) return
        val observedAtMs = System.currentTimeMillis()
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
                rawRecommendedLaneType = laneInfo.frontLane?.getOrNull(index)?.takeUnless { it in setOf(15, 22, 255) },
            )
        }
        update { copy(lanes = lanes, lanesObservedAtMs = observedAtMs) }
    }

    override fun hideLaneInfo() {
        val observedAtMs = System.currentTimeMillis()
        update { copy(lanes = emptyList(), lanesObservedAtMs = observedAtMs) }
    }

    override fun onCalculateRouteSuccess(routeIds: IntArray?) = routeSucceeded()
    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) = routeSucceeded()

    private fun routeSucceeded() {
        val previousState = stateStore.state.value
        if (!routeCalculationPending && !previousState.routePlanned) return
        val routeRevisionChanged = routeCalculationPending || previousState.routeRecalculating
        routeCalculationPending = false
        val engine = navi
        val path = engine?.naviPath
        val choices = engine?.let { routeChoices(it, path) }.orEmpty()
        val selectedRouteId = choices.firstOrNull { it.selected }?.routeId
        val observedAtMs = System.currentTimeMillis()
        val revision = if (routeRevisionChanged) ++routeRevision else routeRevision
        update {
            copy(
                navigationMode = if (routePlanned) navigationMode else NavigationMode.ROUTE_PLANNED,
                simulationPaused = false,
                routePlanned = true,
                routeRemainDistanceMeters = path?.allLength,
                routeRemainTimeSeconds = path?.allTime,
                remainingTrafficLightCount = path?.trafficLightCount,
                routeTrafficLights = path?.lightList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                routeChoices = choices,
                selectedRouteId = selectedRouteId,
                errorMessage = null,
                routeRevision = revision,
                routeObservedAtMs = if (routeRevisionChanged) observedAtMs else routeObservedAtMs,
                routeRecalculating = false,
                // A new route revision must receive fresh route-relative observations.
                guidanceObservedAtMs = if (routeRevisionChanged) null else guidanceObservedAtMs,
                lanesObservedAtMs = if (routeRevisionChanged) null else lanesObservedAtMs,
                currentStepIndex = if (routeRevisionChanged) null else currentStepIndex,
                currentLinkIndex = if (routeRevisionChanged) null else currentLinkIndex,
                currentPointIndex = if (routeRevisionChanged) null else currentPointIndex,
                routeMatched = if (routeRevisionChanged) null else routeMatched,
                maneuver = if (routeRevisionChanged) NavigationManeuver.UNKNOWN else maneuver,
                guidanceStepIndex = if (routeRevisionChanged) null else guidanceStepIndex,
                currentRoadClass = if (routeRevisionChanged) null else currentRoadClass,
                currentRoadType = if (routeRevisionChanged) null else currentRoadType,
            )
        }
        publishRouteIfChanged(path)
    }

    private fun routeChoices(engine: AMapNavi, selectedPath: AMapNaviPath?): List<RouteChoice> =
        engine.naviPaths.orEmpty().entries
            .sortedBy { it.key }
            .map { (routeId, path) ->
                RouteChoice(
                    routeId = routeId,
                    pathId = path.pathid,
                    label = path.labels?.trim().orEmpty(),
                    distanceMeters = path.allLength,
                    durationSeconds = path.allTime,
                    tollYuan = path.tollCost,
                    trafficLightCount = path.trafficLightCount,
                    selected = selectedPath != null && path.pathid == selectedPath.pathid,
                )
            }

    override fun onCalculateRouteFailure(errorCode: Int) = routeFailed("路线规划失败：$errorCode")
    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) =
        routeFailed("路线规划失败：${result?.errorCode ?: -1} ${result?.errorDescription.orEmpty()}")

    private fun routeFailed(message: String) {
        if (!routeCalculationPending && !stateStore.state.value.routeRecalculating) return
        routeCalculationPending = false
        val observedAtMs = System.currentTimeMillis()
        val revision = ++routeRevision
        update {
            clearRouteValues().copy(
                errorMessage = message,
                routeRevision = revision,
                routeObservedAtMs = observedAtMs,
            )
        }
        clearPublishedRoute()
    }

    override fun onReCalculateRouteForYaw() = update {
        copy(
            errorMessage = "检测到偏航，正在重新规划",
            routeRecalculating = true,
            maneuver = NavigationManeuver.UNKNOWN,
            guidanceObservedAtMs = null,
            lanesObservedAtMs = null,
            currentRoadClass = null,
            currentRoadType = null,
        )
    }
    override fun onReCalculateRouteForTrafficJam() = update {
        copy(
            errorMessage = "因拥堵重新规划路线",
            routeRecalculating = true,
            maneuver = NavigationManeuver.UNKNOWN,
            guidanceObservedAtMs = null,
            lanesObservedAtMs = null,
            currentRoadClass = null,
            currentRoadType = null,
        )
    }
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

    private fun publishRouteIfChanged(path: AMapNaviPath?) {
        val coordinates = path?.coordList.orEmpty()
        if (path == null || coordinates.size < 2) return
        val first = coordinates.first()
        val last = coordinates.last()
        val geometryHash = coordinates.fold(1L) { hash, point ->
            31L * (31L * hash + point.latitude.toBits()) + point.longitude.toBits()
        }
        val signature = "${path.pathid}:${path.allLength}:${coordinates.size}:" +
            "${first.latitude}:${first.longitude}:${last.latitude}:${last.longitude}:$geometryHash"
        if (signature == publishedRouteSignature) return
        publishedRouteSignature = signature
        onRouteChanged(path)
    }

    private fun clearPublishedRoute() {
        if (publishedRouteSignature == null) return
        publishedRouteSignature = null
        onRouteChanged(null)
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
        guidanceObservedAtMs = null,
        lanesObservedAtMs = null,
        currentStepIndex = null,
        currentLinkIndex = null,
        currentPointIndex = null,
        routeMatched = null,
        maneuver = NavigationManeuver.NONE,
        guidanceStepIndex = null,
        currentRoadClass = null,
        currentRoadType = null,
        routeRecalculating = false,
        routeChoices = emptyList(),
        selectedRouteId = null,
    )

    private companion object {
        const val DEFAULT_EMULATOR_SPEED_KPH = 120
    }
}
