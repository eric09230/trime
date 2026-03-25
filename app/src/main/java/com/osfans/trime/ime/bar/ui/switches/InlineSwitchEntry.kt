// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui.switches

import com.osfans.trime.core.RimeSchema
import com.osfans.trime.daemon.RimeSession

data class InlineSwitchEntry(
    val switch: RimeSchema.Switch,
    val enabledIndex: Int,
) {
    val currentStateText: String
        get() = switch.states.getOrElse(enabledIndex) { "" }

    val altStateText: String
        get() = if (switch.options.isEmpty() && switch.states.size == 2) {
            "\u2192 ${switch.states[1 - enabledIndex]}"
        } else {
            ""
        }

    companion object {
        fun fromSwitch(rime: RimeSession, sw: RimeSchema.Switch): InlineSwitchEntry? {
            if (sw.states.size <= 1) return null
            val enabledIndex = if (sw.name.isNotEmpty()) {
                if (sw.states.size != 2) return null
                if (rime.run { getRuntimeOption(sw.name) }) 1 else 0
            } else {
                val idx = sw.options.indexOfFirst { rime.run { getRuntimeOption(it) } }
                if (idx >= 0) idx else 0
            }
            return InlineSwitchEntry(sw, enabledIndex)
        }
    }
}
