// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui.switches

import com.osfans.trime.core.RimeSchema
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.KeyActionManager

sealed interface InlineSwitchEntry {
    val displayText: String
    val secondaryText: String

    data class SwitchItem(
        val switch: RimeSchema.Switch,
        val enabledIndex: Int,
    ) : InlineSwitchEntry {
        override val displayText: String
            get() = switch.states.getOrElse(enabledIndex) { "" }

        override val secondaryText: String
            get() = if (switch.options.isEmpty() && switch.states.size == 2) {
                "\u2192 ${switch.states[1 - enabledIndex]}"
            } else {
                ""
            }

        companion object {
            fun fromSwitch(rime: RimeSession, sw: RimeSchema.Switch): SwitchItem? {
                if (sw.states.size <= 1) return null
                val enabledIndex = if (sw.name.isNotEmpty()) {
                    if (sw.states.size != 2) return null
                    if (rime.run { getRuntimeOption(sw.name) }) 1 else 0
                } else {
                    val idx = sw.options.indexOfFirst { rime.run { getRuntimeOption(it) } }
                    if (idx >= 0) idx else 0
                }
                return SwitchItem(sw, enabledIndex)
            }
        }
    }

    data class ActionItem(
        val label: String,
        val action: String,
    ) : InlineSwitchEntry {
        override val displayText: String get() = label
        override val secondaryText: String get() = ""

        /** Extract target keyboard ID from compound action like '{Keyboard_xxx}{text_6}' */
        val targetKeyboardId: String by lazy {
            val match = Regex("\\{(Keyboard_[^}]+)\\}").find(action) ?: return@lazy ""
            KeyActionManager.getAction(match.groupValues[1]).select
        }
    }
}
