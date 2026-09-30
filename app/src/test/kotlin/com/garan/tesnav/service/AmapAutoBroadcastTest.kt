package com.garan.tesnav.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.garan.tesnav.export.NavAssistV2Mapper
import com.garan.tesnav.model.NavigationSourceGate

class AmapAutoBroadcastTest {
    @Test fun `retains every extra for each Amap Auto interface`() {
        val navigation = AmapAutoBroadcast.capture(
            action = "AUTONAVI_STANDARD_BROADCAST_SEND",
            extras = mapOf(
                "KEY_TYPE" to 10001,
                "CUR_ROAD_NAME" to "测试路",
                "ICON" to 3,
                "EXTRA_DRIVE_WAY" to intArrayOf(1, 2, 3),
            ),
            observedAtMs = 100L,
        )
        val traffic = AmapAutoBroadcast.capture(
            action = "AUTONAVI_STANDARD_BROADCAST_SEND",
            extras = mapOf("KEY_TYPE" to "60073", "trafficLightStatus" to 2),
            observedAtMs = 200L,
        )
        val retained = AmapAutoBroadcast.retain(AmapAutoBroadcast.retain(emptyMap(), navigation), traffic)

        assertEquals(2, retained.size)
        assertEquals("测试路", retained[navigation.interfaceKey]?.extras?.get("CUR_ROAD_NAME"))
        assertEquals("[1, 2, 3]", retained[navigation.interfaceKey]?.extras?.get("EXTRA_DRIVE_WAY"))
        assertEquals(60073, retained[traffic.interfaceKey]?.keyType)
    }

    @Test fun `maps the pinned jihui 60073 scalar contract`() {
        val observation = AmapAutoBroadcast.trafficLight(
            mapOf("trafficLightStatus" to "2", "dir" to 2, "redLightCountDownSeconds" to "37"),
            observedAtMs = 123L,
        )

        assertEquals(2, observation?.status)
        assertEquals(2, observation?.direction)
        assertEquals(37, observation?.countdownSeconds)
        assertEquals(123L, observation?.observedAtMs)
        assertNull(AmapAutoBroadcast.trafficLight(mapOf("dir" to 2), 123L))
    }

    @Test fun `maps the pinned jihui 10001 navigation interface for the shared control gate`() {
        val state = AmapAutoBroadcast.navigation(
            previous = null,
            locationFallback = com.garan.tesnav.model.NavigationState(
                latitude = 31.2,
                longitude = 121.5,
                accuracy = 4f,
                bearing = null,
                locationObservedAtMs = 990L,
                locationReceivedElapsedMs = 490L,
            ),
            extras = mapOf(
                "KEY_TYPE" to 10001,
                "ROUTE_ALL_DIS" to 12_000,
                "ROUTE_REMAIN_DIS" to 8_000,
                "ROUTE_REMAIN_TIME" to 900,
                "SEG_REMAIN_DIS" to 120,
                "ICON" to 3,
                "ROAD_TYPE" to 9,
                "CUR_ROAD_NAME" to "当前路",
                "NEXT_ROAD_NAME" to "下一路",
                "CAR_DIRECTION" to 90,
                "CUR_SPEED" to 42,
                "LIMITED_SPEED" to 40,
                "routeRemainTrafficLightNum" to 5,
            ),
            observedAtMs = 1_000L,
            receivedElapsedMs = 500L,
            sourceBudgetMs = 2_000L,
        )

        assertTrue(state.routePlanned)
        assertEquals("当前路", state.currentRoad)
        assertEquals("下一路", state.nextRoad)
        assertEquals(120, state.nextTurnDistanceMeters)
        assertEquals(8_000, state.routeRemainDistanceMeters)
        assertEquals(5, state.remainingTrafficLightCount)
        assertTrue(state.isOverspeed)
        assertFalse(state.navAssistControlAllowed)
        assertEquals("amap_auto_pending", state.navAssistSourceStatus)
        assertEquals(4f, state.accuracy)
        assertEquals(90f, state.bearing)
        assertEquals(state.acceptedPathId, state.guidancePathId)
        assertEquals(0, state.currentStepIndex)
        assertEquals(state.currentStepIndex, state.guidanceStepIndex)
        assertEquals(1L, state.routeRevision)
    }

    @Test fun `advances the Amap Auto maneuver identity without changing the route identity`() {
        val first = AmapAutoBroadcast.navigation(
            previous = null,
            locationFallback = null,
            extras = mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 80, "ICON" to 2,
                "NEXT_ROAD_NAME" to "甲路", "CAR_LATITUDE" to 31.2, "CAR_LONGITUDE" to 121.5,
                "CAR_DIRECTION" to 90, "CAR_ACCURACY" to 5),
            observedAtMs = 1_000L,
            receivedElapsedMs = 500L,
            sourceBudgetMs = 2_000L,
        )
        val next = AmapAutoBroadcast.navigation(
            previous = first,
            locationFallback = null,
            extras = mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 200, "ICON" to 3,
                "NEXT_ROAD_NAME" to "乙路", "CAR_LATITUDE" to 31.2, "CAR_LONGITUDE" to 121.5,
                "CAR_DIRECTION" to 90, "CAR_ACCURACY" to 5),
            observedAtMs = 2_000L,
            receivedElapsedMs = 1_500L,
            sourceBudgetMs = 2_000L,
        )

        assertEquals(first.acceptedPathId, next.acceptedPathId)
        assertEquals(first.routeRevision, next.routeRevision)
        assertEquals(1, next.currentStepIndex)
    }

    @Test fun `Amap Auto receives the same control gate authority as the API source`() {
        val state = AmapAutoBroadcast.navigation(
            previous = null,
            locationFallback = com.garan.tesnav.model.NavigationState(
                latitude = 31.2,
                longitude = 121.5,
                accuracy = 4f,
                bearing = null,
                locationObservedAtMs = 9_900L,
                locationReceivedElapsedMs = 900L,
            ),
            extras = mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 80, "ICON" to 2,
                "NEXT_ROAD_NAME" to "甲路", "CUR_SPEED" to 20, "CAR_DIRECTION" to 90),
            observedAtMs = 10_000L,
            receivedElapsedMs = 1_000L,
            sourceBudgetMs = 2_000L,
        )
        val gate = NavigationSourceGate(sourceBudgetMs = 2_000L, progressBudgetMs = 2_000L)
        gate.arm(900L)
        val prepared = gate.prepare(state, nowElapsedMs = 1_000L, nowWallMs = 10_000L)
        val snapshot = NavAssistV2Mapper.snapshot(prepared, "auto-session", 1L, 10_000L, 500L)

        assertTrue(prepared.navAssistControlAllowed)
        assertTrue(snapshot.routeActive)
        assertTrue(snapshot.maneuverEventId != 0L)
    }
}
