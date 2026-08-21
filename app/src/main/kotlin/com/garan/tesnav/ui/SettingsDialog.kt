package com.garan.tesnav.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.garan.tesnav.export.ExportConnectionState
import com.garan.tesnav.service.NavigationForegroundService
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
            service.stateStore.state.collect { renderError(navigationErrorText, it.errorMessage) }
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
}
