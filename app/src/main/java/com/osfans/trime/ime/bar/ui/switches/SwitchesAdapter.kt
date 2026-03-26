// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui.switches

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.theme.Theme

class SwitchesAdapter(
    private val theme: Theme,
) : BaseQuickAdapter<InlineSwitchEntry, SwitchesAdapter.Holder>() {
    var onSwitchClick: ((InlineSwitchEntry) -> Unit)? = null
    var onSwitchLongClick: (() -> Unit)? = null

    inner class Holder(
        val ui: SwitchUi,
    ) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(SwitchUi(context, theme))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
        item: InlineSwitchEntry?,
    ) {
        item ?: return
        holder.ui.setFirstText(item.displayText)
        holder.ui.setLastText(item.secondaryText)
        holder.ui.root.setOnClickListener {
            onSwitchClick?.invoke(item)
        }
        holder.ui.root.setOnLongClickListener {
            onSwitchLongClick?.invoke()
            true
        }
    }
}
