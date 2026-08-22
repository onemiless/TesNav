package com.garan.tesnav.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.amap.api.navi.model.AMapNaviPath
import com.garan.tesnav.export.ExportConnectionState
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.service.NavigationForegroundService
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Centered settings dialog with live Comma and Home Assistant state. */
class SettingsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: NavigationForegroundService,
) {
    private val observationJobs = mutableListOf<Job>()
    private var applyingSyncToggle = false
    private var renderedRouteSignature: String? = null

    fun show() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val title = TextView(context).apply {
            text = "设置"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(38, 50, 56))
            setPadding(dp(16), dp(14), dp(8), dp(10))
        }
        val close = Button(context).apply { text = "关闭" }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        @Suppress("DEPRECATION")
        val syncToggle = Switch(context).apply {
            text = "同步特斯拉导航"
            textSize = 17f
            isChecked = service.teslaSyncEnabled.value
        }
        val webSocketText = bodyText("Comma WebSocket：STOPPED")
        val teslaNavigationText = bodyText("is_tesla_nav_active：断开")
        val homeAssistantConnectionText = bodyText("Home Assistant：DISABLED")
        val homeAssistantNavigationText = bodyText("HA 导航：未知")
        val destinationText = bodyText("HA 目的地：—")
        val webSocketErrorText = errorText()
        val homeAssistantErrorText = errorText()
        val navigationErrorText = errorText()
        val routeOverviewPreferences = context.getSharedPreferences(ROUTE_OVERVIEW_PREFS, Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        val routeOverviewToggle = Switch(context).apply {
            text = "显示路线全览统计"
            textSize = 17f
            isChecked = routeOverviewPreferences.getBoolean(SHOW_ROUTE_OVERVIEW, true)
        }
        val routeOverviewTitle = bodyText("路线全览统计").apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val routeOverviewStats = bodyText("当前没有规划路线").apply {
            textSize = 15f
            setLineSpacing(0f, 1.18f)
        }
        val routeOverviewView = RouteOverviewView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 248, 250))
                setStroke(dp(1), Color.rgb(207, 216, 220))
                cornerRadius = dp(10).toFloat()
            }
        }
        val fullRouteTitle = bodyText("完整坐标点全览").apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val simplifiedRouteTitle = bodyText("精简坐标点全览").apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val simplifiedRouteStats = bodyText("当前没有规划路线").apply {
            textSize = 15f
            setLineSpacing(0f, 1.18f)
        }
        val simplifiedRouteOverviewView = RouteOverviewView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 248, 250))
                setStroke(dp(1), Color.rgb(207, 216, 220))
                cornerRadius = dp(10).toFloat()
            }
        }
        val routeOverviewBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 252, 253))
                setStroke(dp(1), Color.rgb(224, 230, 234))
                cornerRadius = dp(12).toFloat()
            }
            addView(routeOverviewTitle, matchWidthParams())
            addView(routeOverviewStats, matchWidthParams(dp(10)))
            addView(fullRouteTitle, matchWidthParams(dp(14)))
            addView(routeOverviewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply {
                topMargin = dp(8)
            })
            addView(simplifiedRouteTitle, matchWidthParams(dp(16)))
            addView(simplifiedRouteStats, matchWidthParams(dp(8)))
            addView(simplifiedRouteOverviewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply {
                topMargin = dp(8)
            })
            visibility = if (routeOverviewToggle.isChecked) View.VISIBLE else View.GONE
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            addView(syncToggle, matchWidthParams())
            addView(webSocketText, matchWidthParams(dp(12)))
            addView(teslaNavigationText, matchWidthParams(dp(6)))
            addView(webSocketErrorText, matchWidthParams(dp(6)))
            addView(homeAssistantConnectionText, matchWidthParams(dp(16)))
            addView(homeAssistantNavigationText, matchWidthParams(dp(6)))
            addView(destinationText, matchWidthParams(dp(6)))
            addView(homeAssistantErrorText, matchWidthParams(dp(6)))
            addView(navigationErrorText, matchWidthParams(dp(12)))
            addView(routeOverviewToggle, matchWidthParams(dp(18)))
            addView(routeOverviewBlock, matchWidthParams(dp(24)))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = Dialog(context).apply {
            setContentView(content)
            setCanceledOnTouchOutside(true)
        }

        syncToggle.setOnCheckedChangeListener { _, enabled ->
            if (!applyingSyncToggle) service.setTeslaSyncEnabled(enabled)
        }
        routeOverviewToggle.setOnCheckedChangeListener { _, enabled ->
            routeOverviewPreferences.edit().putBoolean(SHOW_ROUTE_OVERVIEW, enabled).apply()
            routeOverviewBlock.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            observationJobs.forEach(Job::cancel)
            observationJobs.clear()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.90f).toInt(), (metrics.heightPixels * 0.86f).toInt())
        }

        observationJobs += scope.launch {
            service.teslaSyncEnabled.collect { enabled ->
                if (syncToggle.isChecked != enabled) {
                    applyingSyncToggle = true
                    syncToggle.isChecked = enabled
                    applyingSyncToggle = false
                }
            }
        }
        observationJobs += scope.launch {
            service.exporter.connectionState.collect { state ->
                webSocketText.text = "Comma WebSocket：${state.name}"
                renderTeslaNavigationState(teslaNavigationText, state)
            }
        }
        observationJobs += scope.launch {
            service.commaStateStore.state.collect {
                renderTeslaNavigationState(teslaNavigationText, service.exporter.connectionState.value)
            }
        }
        observationJobs += scope.launch {
            service.exporter.lastError.collect { renderError(webSocketErrorText, it) }
        }
        observationJobs += scope.launch {
            service.homeAssistantClient.connectionState.collect {
                homeAssistantConnectionText.text = "Home Assistant：${it.name}"
            }
        }
        observationJobs += scope.launch {
            service.homeAssistantClient.navigationState.collect { state ->
                homeAssistantNavigationText.text = when (state.navigationActive) {
                    true -> "HA 导航：开启"
                    false -> "HA 导航：关闭"
                    null -> "HA 导航：未知"
                }
                destinationText.text = if (state.navigationActive == true && state.destination != null) {
                    "HA 目的地：${state.destination.latitude}, ${state.destination.longitude}"
                } else {
                    "HA 目的地：—"
                }
            }
        }
        observationJobs += scope.launch {
            service.homeAssistantClient.lastError.collect { renderError(homeAssistantErrorText, it) }
        }
        observationJobs += scope.launch {
            service.stateStore.state.collect { state ->
                renderError(navigationErrorText, state.errorMessage)
                renderRouteOverview(
                    state.routePlanned,
                    routeOverviewStats,
                    routeOverviewView,
                    simplifiedRouteTitle,
                    simplifiedRouteStats,
                    simplifiedRouteOverviewView,
                )
            }
        }
    }

    private fun renderTeslaNavigationState(view: TextView, connectionState: ExportConnectionState) {
        view.text = if (connectionState == ExportConnectionState.CONNECTED) {
            "is_tesla_nav_active：${service.commaStateStore.state.value.isTeslaNavActive}"
        } else {
            "is_tesla_nav_active：断开"
        }
    }

    private fun renderError(view: TextView, error: String?) {
        view.text = error.orEmpty()
        view.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderRouteOverview(
        routePlanned: Boolean,
        statsView: TextView,
        overviewView: RouteOverviewView,
        simplifiedTitle: TextView,
        simplifiedStatsView: TextView,
        simplifiedOverviewView: RouteOverviewView,
    ) {
        val path = service.currentPath().takeIf { routePlanned }
        val coordinates = path?.coordList.orEmpty()
        val signature = if (path == null || coordinates.size < 2) {
            "empty"
        } else {
            val first = coordinates.first()
            val last = coordinates.last()
            "${path.pathid}:${path.allLength}:${coordinates.size}:${first.latitude}:${first.longitude}:${last.latitude}:${last.longitude}"
        }
        if (signature == renderedRouteSignature) return
        renderedRouteSignature = signature

        if (path == null || coordinates.size < 2) {
            statsView.text = "当前没有规划路线"
            overviewView.setRoute(emptyList())
            simplifiedTitle.text = "精简坐标点全览"
            simplifiedStatsView.text = "当前没有规划路线"
            simplifiedOverviewView.setRoute(emptyList())
            return
        }

        val points = coordinates.map { GeoPoint(it.latitude, it.longitude) }
        val distances = points.zipWithNext(::distanceMeters)
        val sortedDistances = distances.sorted()
        val minimum = sortedDistances.firstOrNull() ?: 0.0
        val median = sortedDistances.getOrNull(sortedDistances.size / 2) ?: 0.0
        val average = distances.average().takeUnless(Double::isNaN) ?: 0.0
        val maximum = sortedDistances.lastOrNull() ?: 0.0
        val compactJsonBytes = compactJsonSize(points)
        val linkCount = path.steps.orEmpty().sumOf { it.links.orEmpty().size }
        statsView.text = buildString {
            appendLine("路线长度：${formatDistance(path.allLength)}")
            appendLine("坐标点：${points.size} · Steps：${path.steps.orEmpty().size} · Links：$linkCount")
            appendLine(
                "相邻点间距：最小 ${formatMeters(minimum)} · 中位 ${formatMeters(median)} · " +
                    "平均 ${formatMeters(average)} · 最大 ${formatMeters(maximum)}",
            )
            append("紧凑坐标 JSON 估算：${formatBytes(compactJsonBytes)}")
        }
        overviewView.setRoute(points)
        simplifiedTitle.text = "精简坐标点全览（计算中）"
        simplifiedStatsView.text = "正在根据全览图尺寸计算精简数据"
        simplifiedOverviewView.setRoute(emptyList())
        simplifiedOverviewView.post {
            if (renderedRouteSignature != signature) return@post
            val result = simplifyRouteForOverview(points, simplifiedOverviewView)
            renderSimplifiedRoute(points, result, simplifiedTitle, simplifiedStatsView, simplifiedOverviewView)
        }
    }

    private fun renderSimplifiedRoute(
        originalPoints: List<GeoPoint>,
        result: SimplificationResult,
        titleView: TextView,
        statsView: TextView,
        overviewView: RouteOverviewView,
    ) {
        val simplifiedPoints = result.points
        titleView.text = "精简坐标点全览（${simplifiedPoints.size} / ${originalPoints.size} 点）"
        val simplifiedDistances = simplifiedPoints.zipWithNext(::distanceMeters)
        val sortedSimplifiedDistances = simplifiedDistances.sorted()
        val simplifiedMinimum = sortedSimplifiedDistances.firstOrNull() ?: 0.0
        val simplifiedMedian = sortedSimplifiedDistances.getOrNull(sortedSimplifiedDistances.size / 2) ?: 0.0
        val simplifiedAverage = simplifiedDistances.average().takeUnless(Double::isNaN) ?: 0.0
        val simplifiedMaximum = sortedSimplifiedDistances.lastOrNull() ?: 0.0
        val reductionPercent = (1.0 - simplifiedPoints.size.toDouble() / originalPoints.size) * 100.0
        statsView.text = buildString {
            appendLine(
                "坐标点：${simplifiedPoints.size} · 减少：${String.format(Locale.US, "%.1f%%", reductionPercent)}",
            )
            appendLine("动态容差：${formatMeters(result.toleranceMeters)}（屏幕偏差 ${SIMPLIFICATION_TOLERANCE_PIXELS} px）")
            appendLine(
                "相邻点间距：最小 ${formatMeters(simplifiedMinimum)} · 中位 ${formatMeters(simplifiedMedian)} · " +
                    "平均 ${formatMeters(simplifiedAverage)} · 最大 ${formatMeters(simplifiedMaximum)}",
            )
            append("紧凑坐标 JSON 估算：${formatBytes(compactJsonSize(simplifiedPoints))}")
        }
        overviewView.setRoute(simplifiedPoints)
    }

    private fun simplifyRouteForOverview(points: List<GeoPoint>, overviewView: RouteOverviewView): SimplificationResult {
        if (points.size <= 2) return SimplificationResult(points, 0.0)
        val meanLatitudeRadians = Math.toRadians(points.sumOf(GeoPoint::latitude) / points.size)
        val projected = points.map { point ->
            ProjectedPoint(
                x = EARTH_RADIUS_METERS * Math.toRadians(point.longitude) * cos(meanLatitudeRadians),
                y = EARTH_RADIUS_METERS * Math.toRadians(point.latitude),
            )
        }
        val spanX = (projected.maxOf(ProjectedPoint::x) - projected.minOf(ProjectedPoint::x)).coerceAtLeast(1e-9)
        val spanY = (projected.maxOf(ProjectedPoint::y) - projected.minOf(ProjectedPoint::y)).coerceAtLeast(1e-9)
        val drawingPadding = 28f * context.resources.displayMetrics.density
        val availableWidth = (overviewView.width - drawingPadding * 2f).coerceAtLeast(1f)
        val availableHeight = (overviewView.height - drawingPadding * 2f).coerceAtLeast(1f)
        val pixelsPerMeter = minOf(availableWidth / spanX, availableHeight / spanY)
        val toleranceMeters = SIMPLIFICATION_TOLERANCE_PIXELS / pixelsPerMeter
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.addLast(0 to points.lastIndex)

        while (ranges.isNotEmpty()) {
            val (start, end) = ranges.removeLast()
            var farthestIndex = -1
            var farthestDistance = 0.0
            for (index in start + 1 until end) {
                val distance = perpendicularDistance(projected[index], projected[start], projected[end])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthestIndex = index
                }
            }
            if (farthestIndex >= 0 && farthestDistance > toleranceMeters) {
                keep[farthestIndex] = true
                ranges.addLast(start to farthestIndex)
                ranges.addLast(farthestIndex to end)
            }
        }
        return SimplificationResult(points.filterIndexed { index, _ -> keep[index] }, toleranceMeters)
    }

    private fun perpendicularDistance(point: ProjectedPoint, start: ProjectedPoint, end: ProjectedPoint): Double {
        val deltaX = end.x - start.x
        val deltaY = end.y - start.y
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0.0) return hypot(point.x - start.x, point.y - start.y)
        val position = ((point.x - start.x) * deltaX + (point.y - start.y) * deltaY) / lengthSquared
        val closestX = start.x + position.coerceIn(0.0, 1.0) * deltaX
        val closestY = start.y + position.coerceIn(0.0, 1.0) * deltaY
        return hypot(point.x - closestX, point.y - closestY)
    }

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val latitude1 = Math.toRadians(first.latitude)
        val latitude2 = Math.toRadians(second.latitude)
        val latitudeDelta = latitude2 - latitude1
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeDelta / 2).let { it * it } +
            cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt((1 - a).coerceAtLeast(0.0)))
    }

    private fun compactJsonSize(points: List<GeoPoint>): Int = 2 + points.sumOf {
        it.latitude.toString().length + it.longitude.toString().length + 4
    }

    private fun formatDistance(meters: Int): String = if (meters >= 1000) {
        String.format(Locale.US, "%.1f km", meters / 1000.0)
    } else {
        "$meters m"
    }

    private fun formatMeters(meters: Double): String = String.format(Locale.US, "%.1f m", meters)

    private fun formatBytes(bytes: Int): String = if (bytes >= 1024) {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    } else {
        "$bytes B"
    }

    private fun bodyText(value: String): TextView = TextView(context).apply {
        text = value
        textSize = 16f
        setTextColor(Color.rgb(38, 50, 56))
    }

    private fun errorText(): TextView = bodyText("").apply {
        setTextColor(Color.rgb(198, 40, 40))
        visibility = View.GONE
    }

    private fun matchWidthParams(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val SIMPLIFICATION_TOLERANCE_PIXELS = 1.5
        const val ROUTE_OVERVIEW_PREFS = "route_overview_settings"
        const val SHOW_ROUTE_OVERVIEW = "show_route_overview"
    }

    private data class ProjectedPoint(val x: Double, val y: Double)
    private data class SimplificationResult(val points: List<GeoPoint>, val toleranceMeters: Double)
}
