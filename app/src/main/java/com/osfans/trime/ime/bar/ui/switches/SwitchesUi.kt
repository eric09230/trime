// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui.switches

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.horizontalLayoutManager

class SwitchesUi(
    override val ctx: Context,
    private val theme: Theme,
) : Ui {
    private val switchesAdapter = SwitchesAdapter(theme)

    override val root: RecyclerView =
        recyclerView {
            layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
            layoutManager = horizontalLayoutManager()
            adapter = switchesAdapter
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            val spacing = dp(theme.generalStyle.candidateSpacing).toInt()
            addItemDecoration(
                object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State,
                    ) {
                        outRect.left = spacing / 2
                        outRect.right = spacing / 2
                    }
                },
            )
        }

    fun setSwitches(list: List<InlineSwitchEntry>) {
        switchesAdapter.submitList(list)
    }

    fun setOnSwitchClick(listener: (InlineSwitchEntry) -> Unit) {
        switchesAdapter.onSwitchClick = listener
    }

    fun setOnSwitchLongClick(listener: () -> Unit) {
        switchesAdapter.onSwitchLongClick = listener
    }
}
