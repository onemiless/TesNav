package com.garan.tesnav.model

enum class NavigationMode { IDLE, ROUTE_PLANNED, SIMULATION, REALTIME, ARRIVED }
enum class TrafficStatus { UNKNOWN, SMOOTH, SLOW, CONGESTED, SEVERELY_CONGESTED }
enum class WarningLevel { NONE, WARNING, CRITICAL }
enum class LaneAction { STRAIGHT, LEFT, RIGHT, U_TURN, LEFT_U_TURN, RIGHT_U_TURN, BUS, VARIABLE, DEDICATED, TIDAL, UNKNOWN }
enum class CameraType { SPEED, SURVEILLANCE, TRAFFIC_LIGHT, VIOLATION, BUS_LANE, EMERGENCY, BICYCLE, INTERVAL_START, INTERVAL_END, FLOW_SPEED, ETC, UNKNOWN }

data class GeoPoint(val latitude: Double, val longitude: Double)

data class LaneState(
    val index: Int,
    val allowedActions: List<LaneAction> = emptyList(),
    val recommended: Boolean = false,
    val rawLaneType: Int = -1,
    val recommendedActions: List<LaneAction> = emptyList(),
)

data class CameraState(
    val type: CameraType = CameraType.UNKNOWN,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val distanceMeters: Int? = null,
    val limitSpeedKph: Int? = null,
    val intervalLengthMeters: Int? = null,
    val intervalRemainDistanceMeters: Int? = null,
    val averageSpeedKph: Int? = null,
    val reasonableSpeedKph: Int? = null,
)

/** Schema-compatible latest-state snapshot consumed by Comma. */
data class NavigationState(
    val timestamp: Long = System.currentTimeMillis(),
    val navigationMode: NavigationMode = NavigationMode.IDLE,
    val simulationPaused: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val bearing: Float? = null,
    val locationTime: Long? = null,
    val speedKph: Float = 0f,
    val currentRoad: String? = null,
    val nextRoad: String? = null,
    val nextTurnType: Int? = null,
    val nextTurnDistanceMeters: Int? = null,
    val routeRemainDistanceMeters: Int? = null,
    val routeRemainTimeSeconds: Int? = null,
    val remainingTrafficLightCount: Int? = null,
    val routeTrafficLights: List<GeoPoint> = emptyList(),
    val trafficStatus: TrafficStatus = TrafficStatus.UNKNOWN,
    val lanes: List<LaneState> = emptyList(),
    val cameras: List<CameraState> = emptyList(),
    val speedLimitKph: Int? = null,
    val isOverspeed: Boolean = false,
    val hasSpeedCameraAhead: Boolean = false,
    val warningLevel: WarningLevel = WarningLevel.NONE,
    val routePlanned: Boolean = false,
    val gpsSignalWeak: Boolean = false,
    val errorMessage: String? = null,
    val startedFromTeslaSync: Boolean = false,
)
