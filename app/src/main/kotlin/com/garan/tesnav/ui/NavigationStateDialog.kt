package com.garan.tesnav.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.NavigationMode
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Large live JSON inspector with indentation and syntax coloring. */
class NavigationStateDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<NavigationState>,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var updateJob: Job? = null

    fun show() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val jsonText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.rgb(38, 50, 56))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val speedText = TextView(context).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0, 120, 255))
            setPadding(dp(16), dp(12), dp(16), dp(8))
            visibility = android.view.View.GONE
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                jsonText,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val title = TextView(context).apply {
            text = "NavigationState 实时数据"
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
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(speedText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = Dialog(context).apply {
            setContentView(content)
            setCanceledOnTouchOutside(true)
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            updateJob?.cancel()
            updateJob = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            val metrics = context.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.90f).toInt(), (metrics.heightPixels * 0.86f).toInt())
        }
        updateJob = scope.launch {
            state.collect { navigationState ->
                val navigating = navigationState.navigationMode == NavigationMode.REALTIME ||
                    navigationState.navigationMode == NavigationMode.SIMULATION
                speedText.visibility = if (navigating) android.view.View.VISIBLE else android.view.View.GONE
                if (navigating) speedText.text = "当前速度：%.1f km/h".format(navigationState.speedKph)
                jsonText.text = syntaxHighlight(gson.toJson(navigationState))
            }
        }
    }

    private fun syntaxHighlight(json: String): SpannableString {
        val result = SpannableString(json)
        applyColor(result, STRING_PATTERN, Color.rgb(46, 125, 50))
        applyColor(result, NUMBER_PATTERN, Color.rgb(230, 81, 0))
        applyColor(result, LITERAL_PATTERN, Color.rgb(123, 31, 162))
        applyColor(result, KEY_PATTERN, Color.rgb(0, 120, 255))
        return result
    }

    private fun applyColor(text: SpannableString, pattern: Regex, color: Int) {
        pattern.findAll(text).forEach { match ->
            text.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private companion object {
        val STRING_PATTERN = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val KEY_PATTERN = Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)")
        val NUMBER_PATTERN = Regex("(?<![A-Za-z0-9_])[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        val LITERAL_PATTERN = Regex("\\b(?:true|false|null)\\b")
    }
}
