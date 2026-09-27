package com.garan.tesnav.ui

import com.garan.tesnav.model.LaneAction
import com.garan.tesnav.model.LaneState
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemLanePosition
import com.garan.tesnav.model.OemVehicleLaneState

enum class LaneRecommendationSide { LEFT, RIGHT, MIXED, NONE }

data class LaneGuidanceItem(
    val index: Int,
    val symbols: String,
    val recommended: Boolean,
    val prohibited: Boolean,
    val current: Boolean = false,
)

data class LaneGuidancePresentation(
    val title: String,
    val items: List<LaneGuidanceItem>,
    val recommendationSide: LaneRecommendationSide,
    val currentLaneStatus: String? = null,
)

/** Converts AMap lane callbacks into a compact, testable navigation overlay model. */
object LaneGuidancePresenter {
    fun present(state: NavigationState, oem: OemVehicleLaneState = OemVehicleLaneState()): LaneGuidancePresentation? {
        if (state.navigationMode !in setOf(NavigationMode.REALTIME, NavigationMode.SIMULATION)) return null
        if (state.navigationMode == NavigationMode.REALTIME && state.navAssistSourceStatus != "healthy") {
            val title = if (state.routeRecalculating) "路线已变化 · 请结束后重新规划" else when (state.navAssistSourceStatus) {
                "requiresExplicitStart" -> "导航数据已失效 · 请结束后重新启动"
                "budgetsUnconfirmed" -> "导航数据尚未验证 · 仅预览"
                else -> "导航数据待确认 · 仅预览"
            }
            return LaneGuidancePresentation(title, emptyList(), LaneRecommendationSide.NONE)
        }
        val lanes = state.lanes.sortedBy(LaneState::index).take(MAX_VISIBLE_LANES)
        if (lanes.isEmpty()) {
            if (oem.receivedAtMs == 0L && state.laneCallbackCount == 0) return null
            val laneDiagnostic = when (state.laneLastEvent) {
                "hidden" -> "高德车道引导已隐藏（已驶过提示区）"
                "empty", "legacy_empty" -> "高德车道回调为空（当前路段无数据）"
                else -> "高德车道回调尚未触发"
            }
            val c3Status = oem.c3LaneDecision?.let { decision ->
                val reason = when (decision.reason) {
                    "efficiencyUnavailable" -> if (oem.egoSpeedKph != null && oem.egoSpeedKph < 60f)
                        "车速低于 60 km/h" else "当前条件不满足"
                    "efficiencyNavigationPriority" -> "导航动作优先"
                    "efficiencyLaneGeometryUnknown" -> "车道或目标归属不明"
                    "efficiencyNoConfirmedBenefit" -> "收益或邻道距离不足"
                    "efficiencyNeighborSpaceUnknown" -> "邻道空间未确认"
                    "efficiencyBenefitStabilizing" -> "正在确认超车收益"
                    "efficiencyRequest" -> "已提出超车候选，等待变道协调"
                    "efficiencySafetyBlocked" -> "盲区或原车安全条件阻止"
                    "efficiencyCooldown" -> "等待再次评估"
                    "efficiencyPermissionLost" -> "原车变道许可失效"
                    "efficiencyGapLost" -> "邻道距离或接近时间不足"
                    "efficiencyTargetUnknown" -> "邻道目标不明"
                    "efficiencyCrossingLost" -> "车道线不允许跨越"
                    "efficiencyBenefitLost" -> "超车收益消失"
                    else -> if (decision.reason.startsWith("efficiency")) "等待条件复核" else null
                }
                reason?.let { "C3 超车：$it" }
            }
            return LaneGuidancePresentation(
                title = c3Status ?: if (state.laneCallbackCount > 0 || oem.receivedAtMs == 0L) {
                    laneDiagnostic
                } else {
                    "C3 超车状态未收到"
                },
                items = emptyList(),
                recommendationSide = LaneRecommendationSide.NONE,
            )
        }

        val recommendedIndices = lanes.filter(LaneState::recommended).map(LaneState::index)
        val side = recommendationSide(lanes, recommendedIndices)
        val position = when (oem.position.takeIf { oem.positionValid }) {
            OemLanePosition.LEFTMOST -> "最左车道"
            OemLanePosition.MIDDLE -> "中间车道"
            OemLanePosition.RIGHTMOST -> "最右车道"
            OemLanePosition.SINGLE -> "单车道"
            else -> "车道未知"
        }
        fun directionTitle(direction: String, allowed: Boolean): String {
            val permission = when {
                !oem.permissionValid -> "原车${direction}侧许可未知"
                allowed -> "原车报告${direction}侧许可"
                else -> "原车未报告${direction}侧许可"
            }
            return "路线建议靠$direction · 原车：$position · $permission · 等待 C3 判断"
        }
        val laneRouteConfirmed = state.unkeyedRouteFactsConfirmed ||
            (state.lanesPathId != null && state.lanesPathId == state.acceptedPathId &&
                state.guidancePathId == state.acceptedPathId)
        val title = if (!laneRouteConfirmed) {
            "高德车道提示 · 路线归属待确认，仅供参考"
        } else when (side) {
            LaneRecommendationSide.LEFT -> directionTitle("左", oem.leftAllowed)
            LaneRecommendationSide.RIGHT -> directionTitle("右", oem.rightAllowed)
            LaneRecommendationSide.MIXED -> "高德变道引导 · 推荐车道已标绿"
            LaneRecommendationSide.NONE -> "高德车道引导"
        }
        val comparisonReady = state.navigationMode == NavigationMode.REALTIME &&
            laneRouteConfirmed && state.routeMatched == true && !state.routeRecalculating &&
            oem.positionValid && state.lanes.size == lanes.size &&
            lanes.indices.all { lanes[it].index == it }
        val possibleIndices = if (comparisonReady) when (oem.position) {
            OemLanePosition.LEFTMOST -> listOf(0)
            OemLanePosition.RIGHTMOST -> listOf(lanes.lastIndex)
            OemLanePosition.SINGLE -> if (lanes.size == 1) listOf(0) else emptyList()
            OemLanePosition.MIDDLE -> if (lanes.size >= 3) (1 until lanes.lastIndex).toList() else emptyList()
            OemLanePosition.UNKNOWN -> emptyList()
        } else emptyList()
        val currentLaneStatus = when {
            state.navigationMode != NavigationMode.REALTIME -> "模拟导航 · 无法判定本车车道"
            !laneRouteConfirmed || state.routeMatched != true || state.routeRecalculating ->
                "路线与车道提示未对齐 · 无法判定本车车道"
            !oem.positionValid -> "原车车道位置未知 · 无法判定"
            lanes.none(LaneState::recommended) -> "高德未推荐车道 · 无法判定"
            state.lanes.size != lanes.size || lanes.indices.any { lanes[it].index != it } ->
                "高德车道编号不完整 · 无法判定"
            else -> when {
                possibleIndices.isEmpty() -> "原车位置与高德车道数无法对应 · 无法判定"
                possibleIndices.all { lanes[it].recommended } -> "原车报告：在推荐车道"
                possibleIndices.none { lanes[it].recommended } -> "原车报告：不在推荐车道"
                else -> "原车只报告中间车道 · 无法区分具体车道"
            }
        }
        val currentIndex = possibleIndices.singleOrNull()
        return LaneGuidancePresentation(
            title = title,
            items = lanes.map { lane ->
                LaneGuidanceItem(
                    index = lane.index,
                    symbols = symbols(lane),
                    recommended = lane.recommended,
                    prohibited = lane.prohibited,
                    current = lane.index == currentIndex,
                )
            },
            recommendationSide = side,
            currentLaneStatus = currentLaneStatus,
        )
    }

    private fun recommendationSide(lanes: List<LaneState>, recommendedIndices: List<Int>): LaneRecommendationSide {
        if (recommendedIndices.isEmpty()) return LaneRecommendationSide.NONE
        val firstIndex = lanes.first().index
        val lastIndex = lanes.last().index
        val touchesLeft = firstIndex in recommendedIndices
        val touchesRight = lastIndex in recommendedIndices
        return when {
            touchesLeft && !touchesRight -> LaneRecommendationSide.LEFT
            touchesRight && !touchesLeft -> LaneRecommendationSide.RIGHT
            else -> LaneRecommendationSide.MIXED
        }
    }

    private fun symbols(lane: LaneState): String {
        val actions = lane.recommendedActions.ifEmpty { lane.allowedActions }
        if (actions.isEmpty()) return "·"
        return actions.distinct().joinToString(separator = "") { action ->
            when (action) {
                LaneAction.STRAIGHT -> "↑"
                LaneAction.LEFT -> "←"
                LaneAction.RIGHT -> "→"
                LaneAction.U_TURN, LaneAction.LEFT_U_TURN -> "↶"
                LaneAction.RIGHT_U_TURN -> "↷"
                LaneAction.BUS -> "公交"
                LaneAction.VARIABLE -> "可变"
                LaneAction.DEDICATED -> "专用"
                LaneAction.TIDAL -> "潮汐"
                LaneAction.UNKNOWN -> "×"
            }
        }
    }

    private const val MAX_VISIBLE_LANES = 16
}
