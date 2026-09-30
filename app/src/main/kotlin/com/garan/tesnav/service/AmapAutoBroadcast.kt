package com.garan.tesnav.service

import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.TrafficLightObservation
import com.garan.tesnav.util.NavigationMappers

enum class NavigationDataSource { AMAP_API, AMAP_AUTO }

/** Latest lossless-enough snapshot of one Amap Auto broadcast interface. */
internal data class AmapAutoBroadcastSnapshot(
    val action: String?,
    val keyType: Int?,
    val extras: Map<String, String?>,
    val observedAtMs: Long,
) {
    val interfaceKey: String = "${action.orEmpty()}:${keyType ?: "unknown"}"
}

internal object AmapAutoBroadcast {
    fun capture(action: String?, extras: Map<String, Any?>, observedAtMs: Long): AmapAutoBroadcastSnapshot =
        AmapAutoBroadcastSnapshot(
            action = action,
            keyType = extras["KEY_TYPE"].asInt(),
            extras = extras.mapValues { (_, value) -> value.asStableString() },
            observedAtMs = observedAtMs,
        )

    fun retain(
        current: Map<String, AmapAutoBroadcastSnapshot>,
        snapshot: AmapAutoBroadcastSnapshot,
    ): Map<String, AmapAutoBroadcastSnapshot> = current + (snapshot.interfaceKey to snapshot)

    /** Exact KEY_TYPE=60073 scalar contract used by the pinned jihui Amap app. */
    fun trafficLight(extras: Map<String, Any?>, observedAtMs: Long): TrafficLightObservation? {
        val status = extras["trafficLightStatus"].asInt() ?: return null
        return TrafficLightObservation(
            status = status,
            direction = extras["dir"].asInt(),
            countdownSeconds = extras["redLightCountDownSeconds"].asInt()?.takeIf { it in 0..3_600 },
            observedAtMs = observedAtMs,
        )
    }

    fun navigation(
        previous: NavigationState?,
        locationFallback: NavigationState?,
        extras: Map<String, Any?>,
        observedAtMs: Long,
        receivedElapsedMs: Long,
        sourceBudgetMs: Long,
    ): NavigationState {
        val icon = extras["NEW_ICON"].asInt()?.takeIf { it > 0 } ?: extras["ICON"].asInt()
        val roadType = extras["ROAD_TYPE"].asInt()
        val routeDistance = extras["ROUTE_REMAIN_DIS"].asInt()?.takeIf { it >= 0 }
        val turnDistance = extras["SEG_REMAIN_DIS"].asInt()?.takeIf { it >= 0 }
        val routeActive = extras["ROUTE_ALL_DIS"].asInt()?.let { it > 0 } == true ||
            routeDistance?.let { it > 0 } == true ||
            (icon?.let { it > 0 } == true && turnDistance?.let { it > 0 } == true)
        val speed = extras["CUR_SPEED"].asFloat()?.coerceAtLeast(0f) ?: previous?.speedKph ?: 0f
        val speedLimit = extras["LIMITED_SPEED"].asInt()?.takeIf { it > 0 }
        val broadcastLatitude = extras["CAR_LATITUDE"].asDouble()?.takeIf { it in -90.0..90.0 }
        val broadcastLongitude = extras["CAR_LONGITUDE"].asDouble()?.takeIf { it in -180.0..180.0 }
        val broadcastAccuracy = (extras["CAR_ACCURACY"] ?: extras["GPS_ACCURACY"] ?: extras["ACCURACY"])
            .asFloat()?.takeIf { it.isFinite() && it > 0f }
        val fallbackReceivedAt = locationFallback?.locationReceivedElapsedMs
        val fallbackFresh = fallbackReceivedAt != null && fallbackReceivedAt <= receivedElapsedMs &&
            receivedElapsedMs - fallbackReceivedAt <= sourceBudgetMs && locationFallback.accuracy?.let {
                it.isFinite() && it > 0f
            } == true
        val useFallbackLocation = broadcastAccuracy == null && fallbackFresh
        val latitude = if (useFallbackLocation) locationFallback?.latitude else broadcastLatitude
        val longitude = if (useFallbackLocation) locationFallback?.longitude else broadcastLongitude
        val bearing = if (useFallbackLocation) {
            locationFallback?.bearing ?: extras["CAR_DIRECTION"].asFloat()
        } else {
            extras["CAR_DIRECTION"].asFloat()
        }
        val accuracy = broadcastAccuracy ?: locationFallback?.accuracy?.takeIf { fallbackFresh }
        val locationObservedAtMs = if (useFallbackLocation) locationFallback?.locationObservedAtMs else observedAtMs
        val locationReceivedElapsedMs = if (useFallbackLocation) fallbackReceivedAt else receivedElapsedMs
        val maneuver = NavigationMappers.maneuver(icon, roadType)
        val previousActive = previous?.routePlanned == true && previous.navigationMode == NavigationMode.REALTIME
        val newRoute = routeActive && !previousActive
        val maneuverChanged = previousActive && routeActive &&
            (previous?.maneuver != maneuver || previous.nextRoad != (extras["NEXT_ROAD_NAME"].asText() ?: previous.nextRoad))
        val distanceReset = previousActive && routeActive && turnDistance != null &&
            previous?.nextTurnDistanceMeters?.let { turnDistance > it + 50 } == true
        val stepIndex = when {
            !routeActive -> null
            newRoute -> 0
            maneuverChanged || distanceReset -> (previous?.currentStepIndex ?: 0) + 1
            else -> previous?.currentStepIndex ?: 0
        }
        val pathId = when {
            !routeActive -> null
            newRoute -> observedAtMs.coerceAtLeast(1L)
            else -> previous?.acceptedPathId ?: observedAtMs.coerceAtLeast(1L)
        }
        val routeRevision = if (newRoute) (previous?.routeRevision ?: 0L) + 1L else previous?.routeRevision ?: 0L

        return NavigationState(
            navigationMode = if (routeActive) NavigationMode.REALTIME else NavigationMode.IDLE,
            latitude = latitude ?: previous?.latitude,
            longitude = longitude ?: previous?.longitude,
            accuracy = accuracy ?: previous?.accuracy,
            bearing = bearing ?: previous?.bearing,
            locationTime = observedAtMs,
            speedKph = speed,
            currentRoad = extras["CUR_ROAD_NAME"].asText() ?: previous?.currentRoad,
            nextRoad = extras["NEXT_ROAD_NAME"].asText() ?: previous?.nextRoad,
            nextTurnType = icon,
            nextTurnDistanceMeters = turnDistance,
            routeRemainDistanceMeters = routeDistance,
            routeRemainTimeSeconds = extras["ROUTE_REMAIN_TIME"].asInt()?.takeIf { it >= 0 },
            remainingTrafficLightCount = extras["routeRemainTrafficLightNum"].asInt()?.takeIf { it >= 0 },
            speedLimitKph = speedLimit,
            isOverspeed = speedLimit?.let { speed > it } ?: false,
            routePlanned = routeActive,
            locationObservedAtMs = locationObservedAtMs,
            locationReceivedElapsedMs = locationReceivedElapsedMs,
            locationSourceStatus = "amap_auto",
            guidanceReceivedElapsedMs = receivedElapsedMs,
            guidanceCallbackElapsedMs = receivedElapsedMs,
            acceptedPathId = pathId,
            guidancePathId = pathId,
            navAssistControlAllowed = false,
            navAssistSourceStatus = "amap_auto_pending",
            guidanceObservedAtMs = observedAtMs,
            routeObservedAtMs = observedAtMs,
            routeMatched = routeActive,
            maneuver = maneuver,
            currentStepIndex = stepIndex,
            guidanceStepIndex = stepIndex,
            currentRoadType = roadType,
            trafficLight = previous?.trafficLight,
            routeRevision = routeRevision,
        )
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        else -> toString().toIntOrNull()
    }

    private fun Any?.asFloat(): Float? = when (this) {
        is Number -> toFloat()
        else -> toString().toFloatOrNull()
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        else -> toString().toDoubleOrNull()
    }

    private fun Any?.asText(): String? = toString().trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun Any?.asStableString(): String? = when (this) {
        null -> null
        is ByteArray -> contentToString()
        is ShortArray -> contentToString()
        is IntArray -> contentToString()
        is LongArray -> contentToString()
        is FloatArray -> contentToString()
        is DoubleArray -> contentToString()
        is BooleanArray -> contentToString()
        is CharArray -> contentToString()
        is Array<*> -> contentDeepToString()
        else -> toString()
    }
}
