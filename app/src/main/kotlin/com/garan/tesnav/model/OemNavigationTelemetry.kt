package com.garan.tesnav.model

import java.util.Locale

/** C3 observations. The location bridge may adapt GPS; lane fields never grant control permission. */
data class OemNavigationTelemetry(
    val gpsStatus: String,
    val latitude: Double?, val longitude: Double?, val accuracyValue: Double?,
    val hdop: Double?, val headingDeg: Double?, val speedValue: Double?,
    val mapAvailable: Boolean?, val controllerHealth: Int?, val alcState: Int?,
    val forkState: Int?, val abortReason: Int?, val navAvailable: Boolean?,
    val navUsage: Int?, val autosteerHealth: Int?, val plannerState: Int?, val navDistanceM: Int?,
    val roadEstimator: Int? = null, val oemLaneChangeState: Int? = null,
    // Optional source diagnostics. Ages are measured at C3; not GNSS fix certification.
    val gpsTimeMs: Long? = null, val gpsTimeAgeMs: Long? = null,
    val gpsPositionAgeMs: Long? = null, val gpsMotionAgeMs: Long? = null,
    val gpsAccuracyRaw: String? = null, val gpsAccuracyAgeMs: Long? = null,
) {
    fun displayText(): String {
        val gps = when (gpsStatus) {
            "observed" -> String.format(Locale.ROOT, "车载 GPS %.6f, %.6f · HDOP %.1f", latitude, longitude, hdop)
            "jump" -> "车载 GPS 位置跳变 · 暂不采用"
            "invalid" -> "车载 GPS 数据无效"
            "stale" -> "车载 GPS 数据已过期"
            else -> "车载 GPS 等待数据"
        }
        fun available(value: Boolean?) = when (value) { true -> "可用"; false -> "不可用"; null -> "未知" }
        val speed = speedValue?.let { String.format(Locale.ROOT, "%.1f km/h", it) } ?: "未知"
        val accuracy = accuracyValue?.let { String.format(Locale.ROOT, "%.1f 米", it) } ?: "未知"
        val detail = if (gpsStatus == "observed") "\n车载速度 $speed · 定位精度 $accuracy · 坐标系 GCJ-02" else ""
        val road = when (roadEstimator) {
            0 -> "正常（0）"
            1 -> "测量不稳定（1）"
            2 -> "降级（2）"
            3 -> "严重异常（3）"
            else -> "未知"
        }
        val laneChange = when (oemLaneChangeState) {
            2 -> "车速限制（2）"
            3 -> "无可用车道（3）"
            8 -> "仅左侧候选（8）"
            9 -> "仅右侧候选（9）"
            10 -> "双侧候选（10）"
            null -> "未知"
            else -> "状态码 $oemLaneChangeState（待核验）"
        }
        return "$gps$detail\n原车地图 ${available(mapAvailable)} · 原车导航 ${available(navAvailable)}" +
            "\n原车状态码：控制器 ${controllerHealth ?: "—"} · ALC ${alcState ?: "—"} · 分叉 ${forkState ?: "—"} · 退出 ${abortReason ?: "—"}（仅观测）" +
            "\n原车道路估计 $road · 变道 $laneChange（仅诊断，非变道许可）"
    }
}
