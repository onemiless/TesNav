package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.util.NavigationMappers
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object NavAssistV2Protocol {
    const val SCHEMA_VERSION = 2
    const val MESSAGE_TYPE = "navigation_snapshot"
    const val SOURCE_PLATFORM = "android"
    const val COORDINATE_SYSTEM = "gcj02"
    const val ENDPOINT_PATH = "/v2/snapshot"
    const val MIN_INTERVAL_MS = 200L
    const val DEFAULT_VALID_FOR_MS = 500L
    const val MIN_VALID_FOR_MS = 100L
    const val MAX_VALID_FOR_MS = 2_000L
    const val MIN_TOKEN_UTF8_BYTES = 16
    const val SIGNATURE_HEADER = "X-NavAssist-Signature"
    const val SESSION_ID_PATTERN = "^[A-Za-z0-9._:-]+$"
    const val MAX_LOCATION_ACCURACY_M = 200f
    const val MAX_SPEED_KPH = 300f
    const val MAX_MANEUVER_DISTANCE_M = 100_000
    const val MAX_ROAD_NAME_LENGTH = 256
}

data class NavAssistV2ExportConfig(
    val baseUrl: String,
    /** Shared HMAC secret. It is never transmitted as a header or body field. */
    val token: String,
    val intervalMs: Long = NavAssistV2Protocol.MIN_INTERVAL_MS,
    val validForMs: Long = NavAssistV2Protocol.DEFAULT_VALID_FOR_MS,
) {
    fun isConfigured(): Boolean {
        val parsedUrl = baseUrl.trim().toHttpUrlOrNull()
        return parsedUrl != null && parsedUrl.scheme in setOf("http", "https") &&
            token.isNotBlank() &&
            token.toByteArray(StandardCharsets.UTF_8).size >= NavAssistV2Protocol.MIN_TOKEN_UTF8_BYTES &&
            validForMs in NavAssistV2Protocol.MIN_VALID_FOR_MS..NavAssistV2Protocol.MAX_VALID_FOR_MS
    }
}

data class NavAssistV2Snapshot(
    val schemaVersion: Int,
    val messageType: String,
    val sessionId: String,
    val sequence: Long,
    val routeRevision: Long,
    val maneuverEventId: Long,
    val sourcePlatform: String,
    val sourceWallTimeMs: Long,
    val validForMs: Long,
    val navigationMode: String,
    val routeActive: Boolean,
    val routeMatched: Boolean?,
    val gpsWeak: Boolean,
    val coordinateSystem: String,
    val location: NavAssistV2Location?,
    val guidance: NavAssistV2Guidance?,
    val lanes: NavAssistV2Lanes?,
)

data class NavAssistV2Location(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val bearingDeg: Float,
    val speedKph: Float,
    val observedAtMs: Long,
    val currentStepIndex: Int?,
    val currentLinkIndex: Int?,
    val currentPointIndex: Int?,
)

data class NavAssistV2Guidance(
    val observedAtMs: Long,
    val maneuver: String,
    val maneuverDistanceM: Int?,
    val nextManeuver: String?,
    val nextManeuverDistanceM: Int?,
    val currentRoad: String?,
    val nextRoad: String?,
    val roadClass: Int?,
    val roadType: Int?,
    val advisorySpeedMps: Float?,
)

data class NavAssistV2Lanes(
    val observedAtMs: Long,
    val items: List<NavAssistV2Lane>,
)

data class NavAssistV2Lane(
    val index: Int,
    val allowedActions: List<String>,
    val recommended: Boolean,
    val recommendedActions: List<String>,
)

/** Owns the session UUID and strictly monotonic sequence for one exporter lifetime. */
class NavAssistV2Session(
    val sessionId: String = UUID.randomUUID().toString(),
    private val validForMs: Long = NavAssistV2Protocol.DEFAULT_VALID_FOR_MS,
) {
    private val sequence = AtomicLong(0L)

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sessionId.length <= 64) { "sessionId must not exceed 64 characters" }
        require(Regex(NavAssistV2Protocol.SESSION_ID_PATTERN).matches(sessionId)) {
            "sessionId contains unsupported characters"
        }
        require(validForMs in NavAssistV2Protocol.MIN_VALID_FOR_MS..NavAssistV2Protocol.MAX_VALID_FOR_MS) {
            "validForMs must be between ${NavAssistV2Protocol.MIN_VALID_FOR_MS} and ${NavAssistV2Protocol.MAX_VALID_FOR_MS}"
        }
    }

    fun nextSnapshot(state: NavigationState, sourceWallTimeMs: Long): NavAssistV2Snapshot {
        val nextSequence = sequence.incrementAndGet()
        return NavAssistV2Mapper.snapshot(
            state = state,
            sessionId = sessionId,
            sequence = nextSequence,
            sourceWallTimeMs = sourceWallTimeMs,
            validForMs = validForMs,
        )
    }
}

object NavAssistV2Mapper {
    fun snapshot(
        state: NavigationState,
        sessionId: String,
        sequence: Long,
        sourceWallTimeMs: Long,
        validForMs: Long,
    ): NavAssistV2Snapshot {
        val maneuver = NavigationMappers.maneuverWireValue(state.maneuver)
        val location = location(state)
        val guidance = state.guidanceObservedAtMs?.let { observedAtMs ->
            NavAssistV2Guidance(
                observedAtMs = observedAtMs,
                maneuver = maneuver,
                maneuverDistanceM = state.nextTurnDistanceMeters?.takeIf {
                    it in 0..NavAssistV2Protocol.MAX_MANEUVER_DISTANCE_M
                },
                nextManeuver = null,
                nextManeuverDistanceM = null,
                currentRoad = validRoadName(state.currentRoad),
                nextRoad = validRoadName(state.nextRoad),
                roadClass = NavigationMappers.validRoadClass(state.currentRoadClass),
                roadType = NavigationMappers.validRoadType(state.currentRoadType),
                // Camera enforcement limits are not advisory corner speeds.
                advisorySpeedMps = null,
            )
        }
        val routeActive = state.routePlanned &&
            !state.routeRecalculating &&
            (state.navigationMode == NavigationMode.REALTIME || state.navigationMode == NavigationMode.SIMULATION) &&
            state.routeMatched == true &&
            !state.gpsSignalWeak &&
            location != null &&
            guidance != null
        val stepIndex = state.guidanceStepIndex ?: state.currentStepIndex
        val maneuverEventId = if (
            routeActive && stepIndex != null && maneuver != "none" && maneuver != "unknown"
        ) {
            StableManeuverEventId.fromKey("$sessionId:${state.routeRevision}:$stepIndex:$maneuver")
        } else {
            0L
        }

        return NavAssistV2Snapshot(
            schemaVersion = NavAssistV2Protocol.SCHEMA_VERSION,
            messageType = NavAssistV2Protocol.MESSAGE_TYPE,
            sessionId = sessionId,
            sequence = sequence,
            routeRevision = state.routeRevision.coerceAtLeast(0L),
            maneuverEventId = maneuverEventId,
            sourcePlatform = NavAssistV2Protocol.SOURCE_PLATFORM,
            sourceWallTimeMs = sourceWallTimeMs,
            validForMs = validForMs,
            navigationMode = navigationMode(state),
            routeActive = routeActive,
            routeMatched = if (location != null) state.routeMatched else null,
            gpsWeak = state.gpsSignalWeak,
            coordinateSystem = NavAssistV2Protocol.COORDINATE_SYSTEM,
            location = location,
            guidance = guidance,
            lanes = lanes(state),
        )
    }

    fun navigationMode(state: NavigationState): String = if (state.routeRecalculating) {
        "recalculating"
    } else {
        when (state.navigationMode) {
            NavigationMode.IDLE -> "idle"
            NavigationMode.ROUTE_PLANNED -> "route_planned"
            NavigationMode.REALTIME -> "realtime"
            NavigationMode.SIMULATION -> "simulation"
            NavigationMode.ARRIVED -> "arrived"
        }
    }

    private fun location(state: NavigationState): NavAssistV2Location? {
        val observedAtMs = state.locationObservedAtMs ?: return null
        val latitude = state.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = state.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val accuracyM = state.accuracy?.takeIf {
            it.isFinite() && it in 0f..NavAssistV2Protocol.MAX_LOCATION_ACCURACY_M
        } ?: return null
        val bearingDeg = state.bearing?.takeIf { it.isFinite() && it in 0f..360f } ?: return null
        val speedKph = state.speedKph.takeIf {
            it.isFinite() && it in 0f..NavAssistV2Protocol.MAX_SPEED_KPH
        } ?: return null
        return NavAssistV2Location(
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            bearingDeg = bearingDeg,
            speedKph = speedKph,
            observedAtMs = observedAtMs,
            currentStepIndex = state.currentStepIndex?.takeIf { it >= 0 },
            currentLinkIndex = state.currentLinkIndex?.takeIf { it >= 0 },
            currentPointIndex = state.currentPointIndex?.takeIf { it >= 0 },
        )
    }

    private fun lanes(state: NavigationState): NavAssistV2Lanes? =
        state.lanesObservedAtMs?.let { observedAtMs ->
            NavAssistV2Lanes(
                observedAtMs = observedAtMs,
                items = state.lanes
                    .asSequence()
                    .filter { it.index in 0..31 }
                    .sortedBy { it.index }
                    .take(16)
                    .map { lane ->
                        val allowedActions = NavigationMappers.navAssistV2LaneActions(lane.rawLaneType)
                        val recommendedActions = lane.rawRecommendedLaneType
                            ?.let(NavigationMappers::navAssistV2LaneActions)
                            .orEmpty()
                        NavAssistV2Lane(
                            index = lane.index,
                            allowedActions = allowedActions.map { it.name }.distinct().take(16),
                            recommended = recommendedActions.isNotEmpty(),
                            recommendedActions = recommendedActions.map { it.name }.distinct().take(16),
                        )
                    }
                    .toList(),
            )
        }

    private fun validRoadName(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= NavAssistV2Protocol.MAX_ROAD_NAME_LENGTH
    }
}

/** Stable positive int64 ID; zero is reserved for "no active maneuver event". */
object StableManeuverEventId {
    fun fromKey(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (digest[index].toLong() and 0xffL)
        }
        val nonNegative = value and Long.MAX_VALUE
        return if (nonNegative == 0L) 1L else nonNegative
    }
}

/** Deterministic, compact JSON: object keys are recursively sorted and nulls are omitted. */
object CanonicalJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(value: Any): String = gson.toJson(sort(gson.toJsonTree(value)))

    private fun sort(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet()
                .sortedBy { it.key }
                .forEach { (key, value) -> add(key, sort(value)) }
        }
        element.isJsonArray -> JsonArray().apply {
            element.asJsonArray.forEach { add(sort(it)) }
        }
        else -> element
    }
}

object HmacSha256 {
    fun signLowerHex(body: String, token: String): String {
        require(token.isNotEmpty()) { "HMAC token must not be empty" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
