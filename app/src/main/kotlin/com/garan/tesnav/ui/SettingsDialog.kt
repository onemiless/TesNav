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
import android.widget.TextView
import com.garan.tesnav.export.ExportConnectionState
import com.garan.tesnav.model.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Centered settings dialog using the same window behavior and dimensions as the state inspector. */
class SettingsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val navigationState: StateFlow<NavigationState>,
    private val connectionState: StateFlow<ExportConnectionState>,
    private val connectionError: StateFlow<String?>,
) {
    private val observationJobs = mutableListOf<Job>()

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
        val webSocketText = bodyText("Comma WebSocket：STOPPED")
        val webSocketErrorText = bodyText("").apply {
            setTextColor(Color.rgb(198, 40, 40))
            visibility = View.GONE
        }
        val navigationErrorText = bodyText("").apply {
            setTextColor(Color.rgb(198, 40, 40))
            visibility = View.GONE
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            addView(webSocketText, matchWidthParams())
            addView(webSocketErrorText, matchWidthParams(dp(6)))
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
            connectionState.collect { webSocketText.text = "Comma WebSocket：${it.name}" }
        }
        observationJobs += scope.launch {
            connectionError.collect { error ->
                webSocketErrorText.text = error.orEmpty()
                webSocketErrorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
        observationJobs += scope.launch {
            navigationState.collect { state ->
                navigationErrorText.text = state.errorMessage.orEmpty()
                navigationErrorText.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun bodyText(value: String): TextView = TextView(context).apply {
        text = value
        textSize = 16f
        setTextColor(Color.rgb(38, 50, 56))
    }

    private fun matchWidthParams(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }
}
