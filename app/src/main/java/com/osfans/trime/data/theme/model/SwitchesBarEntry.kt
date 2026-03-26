// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * One entry in the configurable switches bar.
 *
 * type = "switch" -> a RIME schema toggle (ascii_mode, full_shape, etc.)
 * type = "action" -> a preset_key action (Keyboard_bqrw1, Keyboard_hangeul_hnc, etc.)
 */
@Serializable
@Parcelize
data class SwitchesBarEntry(
    val type: String = "switch",
    val name: String = "",
    val label: String = "",
    val action: String = "",
) : Parcelable
