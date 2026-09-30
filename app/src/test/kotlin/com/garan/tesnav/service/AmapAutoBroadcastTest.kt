package com.garan.tesnav.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun `maps the pinned jihui 10001 navigation interface for display only`() {
        val state = AmapAutoBroadcast.navigation(
            previous = null,
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
                "CUR_SPEED" to 42,
                "LIMITED_SPEED" to 40,
                "routeRemainTrafficLightNum" to 5,
            ),
            observedAtMs = 1_000L,
            receivedElapsedMs = 500L,
        )

        assertTrue(state.routePlanned)
        assertEquals("当前路", state.currentRoad)
        assertEquals("下一路", state.nextRoad)
        assertEquals(120, state.nextTurnDistanceMeters)
        assertEquals(8_000, state.routeRemainDistanceMeters)
        assertEquals(5, state.remainingTrafficLightCount)
        assertTrue(state.isOverspeed)
        assertFalse(state.navAssistControlAllowed)
        assertEquals("amap_auto_display", state.navAssistSourceStatus)
    }
}
