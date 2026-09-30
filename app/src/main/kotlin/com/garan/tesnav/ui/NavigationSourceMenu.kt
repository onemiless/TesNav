package com.garan.tesnav.ui

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.garan.tesnav.service.NavigationDataSource

object NavigationSourceMenu {
    fun show(
        context: Context,
        anchor: View,
        current: NavigationDataSource,
        onSelected: (NavigationDataSource) -> Unit,
    ) {
        PopupMenu(context, anchor).apply {
            NavigationDataSource.entries.forEachIndexed { index, source ->
                menu.add(0, index, index, source.menuText).apply {
                    isCheckable = true
                    isChecked = source == current
                }
            }
            setOnMenuItemClickListener { item ->
                NavigationDataSource.entries.getOrNull(item.itemId)?.let(onSelected)
                true
            }
            show()
        }
    }
}
