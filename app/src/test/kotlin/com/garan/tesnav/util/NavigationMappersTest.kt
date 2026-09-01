package com.garan.tesnav.util

import com.garan.tesnav.model.LaneAction
import com.garan.tesnav.model.NavigationManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationMappersTest {
    @Test
    fun `documented AMap icon types map to normalized maneuvers`() {
        assertEquals(NavigationManeuver.TURN_LEFT, NavigationMappers.maneuver(2))
        assertEquals(NavigationManeuver.TURN_RIGHT, NavigationMappers.maneuver(3))
        assertEquals(NavigationManeuver.SLIGHT_LEFT, NavigationMappers.maneuver(4))
        assertEquals(NavigationManeuver.SLIGHT_RIGHT, NavigationMappers.maneuver(5))
        assertEquals(NavigationManeuver.U_TURN_LEFT, NavigationMappers.maneuver(8))
        assertEquals(NavigationManeuver.STRAIGHT, NavigationMappers.maneuver(9))
        assertEquals(NavigationManeuver.ROUNDABOUT, NavigationMappers.maneuver(11))
        assertEquals(NavigationManeuver.DESTINATION, NavigationMappers.maneuver(15))
        assertEquals(NavigationManeuver.U_TURN_RIGHT, NavigationMappers.maneuver(19))
        assertEquals(NavigationManeuver.MERGE_LEFT, NavigationMappers.maneuver(65))
        assertEquals(NavigationManeuver.MERGE_RIGHT, NavigationMappers.maneuver(66))
        assertEquals(NavigationManeuver.UNKNOWN, NavigationMappers.maneuver(999))
    }

    @Test
    fun `directional maneuvers use road type to preserve ramp and exit semantics`() {
        assertEquals(NavigationManeuver.RAMP_LEFT, NavigationMappers.maneuver(2, roadType = 6))
        assertEquals(NavigationManeuver.RAMP_RIGHT, NavigationMappers.maneuver(5, roadType = 8))
        assertEquals(NavigationManeuver.EXIT_LEFT, NavigationMappers.maneuver(4, roadType = 9))
        assertEquals(NavigationManeuver.EXIT_RIGHT, NavigationMappers.maneuver(3, roadType = 9))
        assertEquals(NavigationManeuver.TURN_LEFT, NavigationMappers.maneuver(2, roadType = 15))
    }

    @Test
    fun `road metadata accepts only documented values`() {
        assertEquals(0, NavigationMappers.validRoadClass(0))
        assertEquals(10, NavigationMappers.validRoadClass(10))
        assertNull(NavigationMappers.validRoadClass(-1))
        assertNull(NavigationMappers.validRoadClass(11))

        assertEquals(6, NavigationMappers.validRoadType(6))
        assertEquals(9, NavigationMappers.validRoadType(9))
        assertEquals(58, NavigationMappers.validRoadType(58))
        assertNull(NavigationMappers.validRoadType(0))
        assertNull(NavigationMappers.validRoadType(57))
    }

    @Test
    fun `legacy v1 lane mapper remains unchanged`() {
        assertEquals(listOf(LaneAction.U_TURN), NavigationMappers.laneActions(5))
        assertEquals(
            listOf(LaneAction.STRAIGHT, LaneAction.RIGHT, LaneAction.RIGHT_U_TURN),
            NavigationMappers.laneActions(19),
        )
    }

    @Test
    fun `v2 documented composite lane codes preserve U-turn direction`() {
        assertEquals(listOf(LaneAction.LEFT_U_TURN), NavigationMappers.navAssistV2LaneActions(5))
        assertEquals(
            listOf(LaneAction.STRAIGHT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(9),
        )
        assertEquals(
            listOf(LaneAction.LEFT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(11),
        )
        assertEquals(
            listOf(LaneAction.LEFT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(14),
        )
        assertEquals(
            listOf(LaneAction.STRAIGHT, LaneAction.LEFT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(16),
        )
        assertEquals(
            listOf(LaneAction.RIGHT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(17),
        )
        assertEquals(
            listOf(LaneAction.LEFT, LaneAction.RIGHT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(18),
        )
        assertEquals(
            listOf(LaneAction.STRAIGHT, LaneAction.RIGHT, LaneAction.LEFT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(19),
        )
        assertEquals(
            listOf(LaneAction.LEFT, LaneAction.RIGHT_U_TURN),
            NavigationMappers.navAssistV2LaneActions(20),
        )
        assertEquals(listOf(LaneAction.STRAIGHT), NavigationMappers.navAssistV2LaneActions(13))
        assertEquals(emptyList<LaneAction>(), NavigationMappers.navAssistV2LaneActions(15))
        assertEquals(emptyList<LaneAction>(), NavigationMappers.navAssistV2LaneActions(22))
        assertEquals(emptyList<LaneAction>(), NavigationMappers.navAssistV2LaneActions(255))
        assertEquals(emptyList<LaneAction>(), NavigationMappers.laneRecommendedActions(1, intArrayOf(22)).single())
    }
}
