package com.garan.tesnav.util

import com.garan.tesnav.model.CameraType
import com.garan.tesnav.model.LaneAction
import com.garan.tesnav.model.TrafficStatus

object NavigationMappers {
    fun laneRecommendedActions(laneCount: Int, frontLane: IntArray?): List<List<LaneAction>> =
        List(laneCount.coerceAtLeast(0)) { index ->
            when (val raw = frontLane?.getOrNull(index)) {
                null, 15, 255 -> emptyList()
                else -> laneActions(raw)
            }
        }

    fun laneActions(raw: Int): List<LaneAction> = when (raw) {
        0 -> listOf(LaneAction.STRAIGHT)
        1 -> listOf(LaneAction.LEFT)
        2 -> listOf(LaneAction.STRAIGHT, LaneAction.LEFT)
        3 -> listOf(LaneAction.RIGHT)
        4 -> listOf(LaneAction.STRAIGHT, LaneAction.RIGHT)
        5 -> listOf(LaneAction.U_TURN)
        6 -> listOf(LaneAction.LEFT, LaneAction.RIGHT)
        7 -> listOf(LaneAction.STRAIGHT, LaneAction.LEFT, LaneAction.RIGHT)
        8 -> listOf(LaneAction.RIGHT_U_TURN)
        9 -> listOf(LaneAction.STRAIGHT, LaneAction.U_TURN)
        10 -> listOf(LaneAction.STRAIGHT, LaneAction.RIGHT_U_TURN)
        11, 14, 20 -> listOf(LaneAction.LEFT, LaneAction.U_TURN)
        12, 17 -> listOf(LaneAction.RIGHT, LaneAction.RIGHT_U_TURN)
        16 -> listOf(LaneAction.STRAIGHT, LaneAction.LEFT, LaneAction.U_TURN)
        18 -> listOf(LaneAction.LEFT, LaneAction.RIGHT_U_TURN)
        19 -> listOf(LaneAction.STRAIGHT, LaneAction.RIGHT, LaneAction.RIGHT_U_TURN)
        21 -> listOf(LaneAction.BUS)
        23 -> listOf(LaneAction.VARIABLE)
        24 -> listOf(LaneAction.DEDICATED)
        25 -> listOf(LaneAction.TIDAL)
        else -> listOf(LaneAction.UNKNOWN)
    }

    fun cameraType(raw: Int): CameraType = when (raw) {
        0 -> CameraType.SPEED
        1 -> CameraType.SURVEILLANCE
        2 -> CameraType.TRAFFIC_LIGHT
        3 -> CameraType.VIOLATION
        4 -> CameraType.BUS_LANE
        5 -> CameraType.EMERGENCY
        6 -> CameraType.BICYCLE
        8 -> CameraType.INTERVAL_START
        9 -> CameraType.INTERVAL_END
        10 -> CameraType.FLOW_SPEED
        11 -> CameraType.ETC
        else -> CameraType.UNKNOWN
    }

    fun trafficStatus(raw: Int): TrafficStatus = when (raw) {
        1 -> TrafficStatus.SMOOTH
        2 -> TrafficStatus.SLOW
        3 -> TrafficStatus.CONGESTED
        4 -> TrafficStatus.SEVERELY_CONGESTED
        else -> TrafficStatus.UNKNOWN
    }

    fun validSpeedLimit(value: Int?): Int? = value?.takeIf { it in 1..300 }
}
