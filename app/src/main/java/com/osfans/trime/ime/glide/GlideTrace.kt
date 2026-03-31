/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.glide

import kotlin.math.sqrt

data class GlidePoint(val x: Float, val y: Float, val timestamp: Long)

data class GlideTrace(val points: List<GlidePoint>) {
    val isEmpty get() = points.isEmpty()

    val startPoint: GlidePoint? get() = points.firstOrNull()
    val endPoint: GlidePoint? get() = points.lastOrNull()

    val length: Float by lazy {
        if (points.size < 2) return@lazy 0f
        var total = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            total += sqrt(dx * dx + dy * dy)
        }
        total
    }

    fun subTrace(fromIndex: Int, toIndex: Int): GlideTrace {
        return GlideTrace(
            points.subList(
                fromIndex.coerceIn(0, points.size),
                toIndex.coerceIn(0, points.size),
            ),
        )
    }
}
